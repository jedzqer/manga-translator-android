# Manga Translator AI Agent 指南

## 适用范围

本文面向在本仓库中开发、修复和审查代码的 AI Agent，提供必须遵守的约束、验证方式和高频源码入口。

- 行为细节以现有测试和源码为准。若本文与实现不一致，先确认是文档过期还是代码回归，再同步修正实现、测试或文档，不得静默选择其中一方。
- 开始修改前检查工作区状态；保留用户已有改动。
- 修改范围应紧贴任务。不要借机重构无关模块，也不要把临时实现过程或单次调参记录到本文。

## 项目基线

- Android 应用按业务领域组织为 `library`、`reader`、`translation`、`detection`、`ocr`、`network`、`rendering`、`floating`、`background`、`settings`、`storage` 等源码模块，共享依赖由 `di/AppContainer.kt` 装配。
- Gradle 构建模块以 `settings.gradle.kts` 的实际声明为准；当前注册的构建模块为 `:app`。业务源码模块与 Gradle 子项目须明确区分，不得用构建模块数量概括业务架构。
- JDK 17；Java/Kotlin JVM target 17。
- `compileSdk` / `targetSdk` 37，`minSdk` 24；仅打包 `arm64-v8a`。
- Kotlin、AGP 和依赖版本以 `build.gradle.kts`、`app/build.gradle.kts` 为唯一来源。
- 依赖仓库统一配置在 `settings.gradle.kts`，不得在根或模块构建脚本重复声明。
- Kotlin 源码根目录：`app/src/main/java/com/manga/translate/`，其下按业务领域分包；单元测试位于 `app/src/test/java/com/manga/translate/`。
- Android 资源：`app/src/main/res/`；Manifest：`app/src/main/AndroidManifest.xml`。
- Assets 同时来自 `app/src/main/assets/` 和根目录 `assets/`。

推荐环境变量：

```bash
export ANDROID_HOME=/home/jed/Android
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

所有构建操作必须使用 Gradle Wrapper：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lint
```

- Kotlin 编译或定向单测至少预留 5 分钟。
- APK、完整单测或 lint 至少预留 10 分钟。
- Debug APK：`app/build/outputs/apk/debug/`；Release APK：`app/build/outputs/apk/release/`；报告：`app/build/reports/`。

## 强制开发约束

### 用户数据与兼容性

- 修复检测、OCR、渲染或缓存问题时，不得删除、覆盖、隐藏或主动使已有 `*.json` 翻译结果失效。
- 不得仅为强制重新处理而修改 `TranslationStore` 的 metadata 可用性规则、结果版本或缓存兼容判定。
- 已落盘 `*.json` 翻译结果是用户数据，不做缓存有效性校验；文风、语言、模式、供应商、源文件指纹或版本变化不得隐藏、丢弃或自动重译已有结果。只有用户主动重译才替换已有译文；部分结果补填仅处理未译气泡，失败时保留已有内容。metadata（含可选 `styleFingerprint`）只记录产出信息，旧文件缺失新字段仍正常读取。
- OCR 等可重建缓存继续按各自语义维度严格校验，不得将译文结果的直接读取规则扩展到 OCR 缓存。
- 悬浮窗翻译缓存的键与相似度匹配范围统一由 `FloatingCacheScope`（语言 + `providerId` + `modelName` + `promptAsset` + `promptFingerprint`）决定。该缓存启用相似文本匹配，缺失任一维度会导致静默返回错误译文；新增影响译文的维度必须加入 scope 并提升缓存 schema 版本。
- 跨页合并后的 `PageOcrResult` 只存在于内存中，不得落盘。若将来需要持久化，必须先在 `OcrMetadata` 中记录阅读模式，否则普通阅读会读到跨页坐标。
- 必须变更持久化格式时，先说明影响并获得用户同意，同时提供向后兼容、迁移或恢复方案。`cacheDir` 下的可重建缓存（如 `floating_translate_cache.json`）可通过提升版本直接丢弃，不属于用户数据。
- 漫画库文件操作统一经 `LibraryRepository`；翻译、OCR、glossary 和进度数据统一经对应 Store，避免在 UI 或 Coordinator 中另写一套文件协议。

### 多语言与文案

- 用户可见文案不得硬编码在 Kotlin 或布局中，必须写入资源。
- 日常功能和修复至少同步更新基础资源 `app/src/main/res/values/strings.xml`（简体中文）。
- 新增或修改用户可见文案时，必须同步补齐全部四套语言资源（`values-b+zh+Hant`、`values-en`、`values-pt-rBR`、`values-ru`）；只更新简体中文会导致其他界面语言回退。非英语语言资源中与英文逐字相同的长文案属于未翻译缺口，不得引入。
- 新增界面语言时，必须同时接入 `model/AppLanguage.kt`、`res/xml/locales_config.xml`、对应 `values-*` 资源，以及该语言需要的 Prompt 变体。
- 当前覆盖资源为繁体中文、英语、巴西葡萄牙语和俄语；繁体 Prompt 通过 `PromptAssetResolver` 优先解析 `_hant` 变体，缺失时回退基础文件。

### 依赖与共享实例

- 项目使用轻量 `di/AppContainer.kt`，不使用完整 DI 框架。
- `Activity`、`Fragment` 和 `Service` 必须通过 `context.appContainer` 获取共享核心依赖，不得各自重复创建 Pipeline、Store、检测器或 OCR 引擎。
- 新增 OCR 调用必须复用 `OcrEngineRegistry` 和 `BubbleTextRecognizer`，不得在业务类直接持有新的 `PPOcrV6SmallRec`、`KoreanOcr` 或文字检测器实例。

## 源码导航

本仓库已建立 CodeGraph 代码图谱（根目录 `.codegraph/`，Kotlin 全量索引，随文件改动自动同步）。定位代码时`优先`查图谱，不要一上来就 grep 或逐个读文件。

- 主入口 `codegraph explore "<符号名或问题>"`：一次返回相关符号的逐行源码、彼此的调用链和受影响范围（blast radius）。配置了 MCP 的 Agent 用等价的 `codegraph_explore` 工具。
- `codegraph query <关键字>` 按名字找符号位置；`codegraph node <符号>` 看单个符号的源码与调用上下游。
- 改动共享契约前用 `codegraph impact <符号>` 确认影响面，`codegraph callers <符号>` 列出全部调用方，`codegraph affected <文件...>` 找出需要跟着跑的测试。
- `codegraph explore` 输出的源码即当前磁盘内容，已展示的文件不需再读一遍。图谱只覆盖代码符号，资源、Prompt asset、`update.json` 等非代码文件仍需直接查看。
- 图谱是本地派生数据，不进版本库；新克隆的工作副本执行一次 `codegraph init` 即可，无需手动重建或提交。

下表是按领域的高频入口，用于确定探索起点；具体调用关系查图谱，不在本文展开。以下路径均相对于 `app/src/main/java/com/manga/translate/`：

| 领域 | 入口与关键文件 |
|---|---|
| 应用入口 | `app/MangaTranslateApp.kt`、`app/MainActivity.kt`、`di/AppContainer.kt` |
| 漫画库 | `library/LibraryFragment.kt`、`LibraryViewModel.kt`、`LibraryPageLoader.kt`、`LibraryRepository.kt`、`LibraryImportExportCoordinator.kt`、`LibraryPreferencesGateway.kt` |
| 阅读 | `reader/ReadingFragment.kt`、`ReadingSessionViewModel.kt`、`ReadingPageTransitionController.kt`、`ReadingBitmapDecoder.kt`、`ReadingRegionImageView.kt`、`WebtoonReadingAdapter.kt` |
| 翻译主流程 | `translation/TranslationPipeline.kt`、`FolderTranslationCoordinator.kt`、`TextBubbleTranslationCoordinator.kt` |
| 页面检测 | `detection/PageRegionDetector.kt`、`BubbleDetector.kt`、`TextBlockMerger.kt` |
| OCR | `ocr/OcrSharedTools.kt`、`OcrEngine.kt`、`PPOcrV6SmallRec.kt`、`KoreanOcr.kt`、`EnglishLineDetector.kt` |
| 网络与模型协议 | `network/LlmClient.kt`、`LlmContracts.kt`、`model/ApiFormat.kt`、`OcrApiFormat.kt` |
| 气泡渲染 | `rendering/BubbleRenderer.kt`、`BubbleShapePaths.kt`、`BubbleTextScaling.kt`、`VerticalTextLayout.kt`、`VerticalTextRenderer.kt` |
| 悬浮窗 | `floating/FloatingBallOverlayService.kt`、`FloatingDetectionOverlayView.kt`、`FloatingEmptyBubbleCoordinator.kt`、`ProjectionCaptureSession.kt` |
| 悬浮翻译协调 | `translation/FloatingBubbleTranslationCoordinator.kt` |
| 后台任务 | `background/TranslationKeepAliveService.kt`、`ServiceLibraryUiCallbacks.kt`、`library/LibraryUiBridge.kt` |
| 设置 | `settings/ui/SettingsFragment.kt`、`settings/SettingsStore.kt` 及同目录各领域 Store |
| 持久化 | `storage/TranslationStore.kt`、`OcrStore.kt`、`GlossaryStore.kt`、`TranslationProgressStore.kt`、`TranslationTaskPersistence.kt` |
| 平台能力 | `platform/AppLogger.kt`、`PromptAssetResolver.kt`、`ModelErrorDialogs.kt`、`ErrorDialogFormatter.kt`、`PerformanceTrace.kt`、`RequestPerfTrace.kt` |
| 更新 | `app/UpdateChecker.kt`、根目录 `update.json` |

## 核心行为契约

### 翻译与结构化响应

- `TranslationPipeline` 是单页翻译主入口，负责缓存检查、区域检测、OCR 或 VL、LLM 翻译及结果落盘。逐页、全文、文件夹和合集翻译不得绕开它另拼主流程。
- 文本气泡统一经 `TextBubbleTranslationCoordinator`，协议为 `items[{id,text}] -> items[{id,translation}]`。
- 响应 ID 集合必须与请求完全一致。重复 ID、额外 ID 或缺失 ID 均为 `LlmResponseException`；不得将其静默丢弃或留空。
- 译文字段只接受 `translation`、`translated_text` 或 `translatedText`，不得把输入侧 `text` 当作译文。
- 译文与 OCR 原文相同是合法结果。无意义气泡仍必须返回对应 ID 和空译文；完整响应中的空译文表示移除该气泡。
- 调整上述协议、glossary 回传或错误分类时，必须同步检查 `LlmClient`、`TextBubbleTranslationCoordinator` 及其单元测试。

### 翻译供应商与 glossary

- 翻译固定使用主供应商，不得再引入附加供应商池或调度。
- 自定义请求参数作用于主供应商，参数键不可重复。
- 并发逐页翻译使用 glossary 快照，成功后串行合并。关闭译名处理时仍可读取已有 `glossary.json` 作为上下文，但不得提取、合并或写入新译名；文字请求必须将开关传至请求构造层，关闭时明确要求 `glossary_used` 为空对象。
- VL 整页直译由共享 `VlPageTranslationCoordinator` 处理：漫画库可按文件夹或集合选择完整画面或仅气泡：完整画面保留原图并标注 ID，仅气泡使用白底蒙版着色标注；悬浮窗和 API OCR 仍使用白底蒙版。默认每页或长图检测分块发送一次结构化图片请求；合并设置大于 1 时，漫画库按页序将多页及其检测分块作为独立图片合并发送，ID 按图片顺序在请求内连续编号，响应拆回各页原始 ID 与几何。合并请求受 AI 并发数限制，超时按含目标页数扩展，逐张编码后释放位图；已有结果仍直接读取，部分结果单独补填。文本块优先编号，无文本块气泡单独编号；响应 ID 必须与请求完全一致。回填时同一气泡内的非空文字块译文合并到父气泡的坐标与蒙版，游离文字保持文字块几何；误检与纯拟声词返回空译文并过滤。漫画库沿用译名处理开关，开启时使用 glossary 快照并在成功后串行合并，关闭时只读已有译名。悬浮窗暂不处理译名；携带译名上下文或启用译名提取的图片请求不得复用悬浮窗图片缓存。全文速译不接入 VL。
- 多页气泡合并仅合并 AI 请求，不拼接图片或改写页内坐标；由 `TranslationPipeline.translatePagesWithGlossary()` 分配请求级唯一 ID 并拆回逐页结果。合并后的请求受 AI 并发数限制，超时按实际含文字页数扩展；已有部分译文仍走补填流程，已有缓存不因合并页数变化而失效。普通阅读模式使用有界、保持页序的 OCR 队列，凑批后即可发送并继续准备后续页面；不得将该流水线直接套用于条漫或全文速译。
- 全文速译按有界窗口依次完成 OCR、译名提取和翻译落盘，译名请求同时限制文本量并沿用已累积的译名；不得恢复为整文件夹 OCR 结果常驻或一次性全文译名请求。条漫窗口须保留相邻页衔接，跨页合并结果仍只存在于内存。
- 全文速译固定启用译名处理；`CrossPageBubbleMerger` 只用于 `WEBTOON_SCROLL`，普通横向阅读不得执行跨页气泡合并。

### 检测与 OCR

- `PageRegionDetector` 是主翻译与悬浮窗共享的页面区域入口，固定使用双标签模型同时检测普通气泡和游离文字，不提供检测模式切换。
- 普通气泡来自 `BubbleDetector`，标记为 `BubbleSource.BUBBLE_DETECTOR`；当前双标签分割模型类别 0 为气泡、类别 1 为文字，气泡提供 `maskContour`。文字类别覆盖气泡内外，VL 内存检测结果保留全部文本蒙板和实际检测分块；普通 OCR 页面层过滤归属于气泡的文字块后，其余文字块标记为 `BubbleSource.TEXT_DETECTOR`，不得作为 OCR 行框复用。页面检测统一使用 TFLite，Paddle 仅保留 OCR 阶段按需行检测；`TextBlockMerger` 保留为行框工具。
- `BubbleDetector` 持有共享 LiteRT GPU / CPU 会话，推理与关闭必须串行；`PageRegionDetector.releaseLoadedDetectors()` 负责释放。模型 assets 使用不压缩的 `.tflite` 以支持映射加载。 GPU CompiledModel 的创建、推理和释放必须在同一专用线程执行；不支持 GPU 或 GPU 初始化、推理失败时回退 CPU，并重试当前图片。
- 云端 OCR 区域处理使用有界 worker，裁剪、编码、请求到图片释放的整个生命周期受并发约束，同时保留共享网络限流；不得为每个区域一次性启动持有图片的等待任务。
- 漫画库页面 API OCR 与本地 OCR 的批量 API 补充复用 VL 的白底蒙版、着色和 ID 标注，按检测结果中的实际分块发送结构化请求；每个待识别区域只分配到一个块，跨块区域完整保留，响应 ID 必须与当前块一致，结果恢复原页 ID 与几何。任一块失败不得保存本次 OCR 缓存。
- 长图分块、坐标映射、去重阈值和模型输入细节属于检测模块，不得复制到 Pipeline 或悬浮窗 Service。
- 本地 OCR 的语言路由、裁剪、行识别和 API fallback 统一在 `OcrSharedTools.kt` 调整。除韩语专用模型和俄语 API OCR 外，本地文字识别统一使用 PP-OCRv6 CPU 推理，不调用 PP-OCRv5 LiteRT。识别引擎由 `OcrEngineRegistry` 共享。日文行检测比较原方向与旋转方向，选中的行框必须映射回原裁剪坐标。
- AI OCR 补充模式在本地行过滤前检查识别置信度，对低置信度区域发送整块裁剪图；手动框选默认 API OCR 为独立开关，阅读页与悬浮窗共用识别路由。补充请求失败不得保存本次 OCR 缓存；补充模式及 API 配置计入 OCR 缓存匹配，已有翻译结果仍直接读取。

### 阅读与渲染

- 普通页优先使用 `ARGB_8888`；长图或超高分辨率页面通过 `ReadingRegionImageView` 分块解码，并以源图分辨率作为布局坐标。
- 普通气泡设置作用于阅读页、条漫页和导出；悬浮窗气泡设置只作用于 overlay。两套大小、透明度、形状和文字密度设置不得串用。
- 全局字体由 `BubbleFontResolver` 管理，普通渲染与悬浮窗共用；上传字体持久化在应用私有 `custom_fonts` 目录。
- 普通气泡、游离文字气泡和悬浮窗气泡的文字填充比例独立保存，范围为 20%–100%，默认 100%。比例缩放自动适配后的字号，不改变气泡几何；横排、竖排、阅读与导出共用该规则，手动框使用普通气泡比例。
- `BubbleTextScaling` 和竖排布局是共享算法。修改排版、路径缩放或密度规则时，必须检查 `BubbleRenderer`、`FloatingTranslationView`、`FloatingDetectionOverlayView` 以及对应测试。
- `BubbleSource.TEXT_DETECTOR` 才是普通模式中的游离文字框；用户手动框继续按普通气泡参数处理。

### 后台任务

- `LibraryFragment` 只提交任务描述；`TranslationKeepAliveService` 持有任务 `CoroutineScope` / `Job`，是文件夹、合集和批量翻译的实际执行宿主。
- 翻译任务通过 `PendingTranslationTasks` 在同进程中一次性交接，启动 Service 的 Intent 只携带任务 ID，不得携带整批图片路径或任务 JSON，避免超过 Binder 事务大小限制。进程死亡后交接任务自然失效，不从持久化描述恢复。
- `FolderTranslationCoordinator` 负责编排并返回任务，不得自行启动或停止保活 Service。
- `TranslationTaskPersistence` 只记录活动任务描述，供同进程管理和异常清理；应用冷启动或 Service 被系统重建时必须清除，绝不自动续跑旧任务。
- 进程重启后只能由用户重新发起任务；此时依靠已有 `*.ocr.json` / `*.json` 页级结果跳过已完成页面。
- Service 的任务展示快照由 `GlobalTaskProgressStore` 提供，`LibraryUiBridge` / `ServiceLibraryUiCallbacks` 仅适配瞬时 UI 操作和待确认错误；不得让后台任务直接持有 Fragment/View 引用。
- 任务展示状态统一由 `GlobalTaskProgressStore` 的只读 `StateFlow` 提供；Service 接收任务时分配 taskId，Coordinator 仅通过绑定该 ID 的 `TaskProgressWriter` 更新快照。旧任务回调不能更新新任务，同任务完成、取消和失败的首个终态不可被后续进度或终态覆盖；`running` 由 Service 在清理完成后释放。
- 页面按视图生命周期在主线程收集快照并合并积压进度，完成或清除状态不得被排队中的旧进度覆盖。`LibraryUiBridge` 不维护第二套任务进度，负责带任务 ID 校验的瞬时 UI 操作及待确认错误/决策；无人订阅和视图重建不能丢失未确认错误，决策确认必须幂等，超时或取消清除模型决策。
- 书架列表、目录、筛选和选择状态由 `LibraryViewModel` 管理，目录加载复用 `LibraryPageLoader`；阅读目录、图片列表、页索引与模式由 `ReadingSessionViewModel` 一次发布一致快照。ViewModel 不持有 Fragment、View 或 Activity，SavedStateHandle 只保存轻量恢复信息。状态写入入口和恢复来源见 `dev_doc/页面状态与任务生命周期.md`。

### 网络请求

- 文字、图片、OCR 和模型列表请求统一由 `LlmClient` 通过 OkHttp 执行；超时、取消、请求头和重试策略不得散落到 Coordinator。
- 主 AI 请求的连接超时独立于页数；按含文字页数扩展的单次超时必须封顶，网络重试、模型响应重试及退避共享有上限的总预算。预算耗尽不得重新获取预算继续重试，取消必须覆盖响应体读取；OCR 保留独立超时设置。
- Android 17（API 37）及以上连接局域网 AI / OCR 服务需要 `ACCESS_LOCAL_NETWORK` 运行时权限，通过设置页授权入口申请；HTTP 地址兼容性由 `res/xml/network_security_config.xml` 管理。
- OpenAI 兼容地址：已以 `/chat/completions` 结尾则原样使用，否则直接追加该路径；不得自动插入 `/v1`。
- OpenAI Responses 地址同理追加 `/responses`；两种 OpenAI 格式的模型列表地址追加 `/models`。
- `OPENAI_RESPONSES` 用 `model`、`input` 和可选 `instructions`，响应优先读 `output_text`，再解析 `output[].content[].text`。
- OCR API 只支持 OpenAI 兼容 chat，不支持 Responses 格式。
- 主 AI 自动重试次数来自 `SettingsStore`；可重试范围由 `LlmClient` 统一判断，包括网络错误、超时、HTTP 408/429/5xx 和明确的暂时不可用响应。

### 设置与持久化

- `SettingsStore` 是设置访问门面；新增设置应归入 `ApiSettingsStore`、`OcrSettingsStore`、`RenderSettingsStore`、`AppSettingsStore`、`LlmParameterStore` 或 `ProviderProfileStore`，不得继续膨胀 UI 层持久化逻辑。
- 文件夹级设置、标签、排序和阅读方式统一经 `LibraryPreferencesGateway`。
- 文件夹及集合文风由 `LibraryPreferencesGateway` 管理，默认跟随全局；集合内章节共用集合设置。专属空字符串表示不附加文风要求，不能当作跟随全局。实际文风随请求传递，禁止临时修改全局文风实现覆盖。
- AI 供应商 profile 位于 `files/ai_provider_profiles.json`；供应商配置和其他偏好键以对应 Store 常量为准，不在本文复制完整键表。
- 设置弹窗默认对齐 `dialog_ocr_settings.xml`：滚动容器关闭 padding 裁剪，内容边距和控件节奏复用现有布局资源；不要为单个弹窗另建一套视觉规则。

## 数据位置

漫画库位于 `getExternalFilesDir()/manga_library/`。

| 数据 | 负责组件 |
|---|---|
| `*.json` 翻译结果 | `TranslationStore` |
| `*.ocr.json` OCR 缓存 | `OcrStore` |
| `glossary.json` | `GlossaryStore` |
| `.extract-state.json` | `ExtractStateStore` |
| 页级任务进度 | `TranslationProgressStore` |
| `cache/floating_translate_cache.json` | `FloatingTranslationCacheStore`；清除应用缓存即可删除 |
| `files/ai_provider_profiles.json` | `ProviderProfileStore` |
| 应用备份包（ZIP） | `AppBackupManager`；可独立选择漫画和译文（含库偏好、阅读进度）与软件配置（含 AI profile、自定义字体），沿用 v2 格式；迁移约定见 `dev_doc/备份迁移协议.md` |
| 当前后台任务描述 | `TranslationTaskPersistence` |
| 阅读进度 | `ReadingProgressStore` |

Prompt 位于 `assets/prompts/`，基础文件包括 `llm_prompts.json`、`llm_prompts_abstract.json`、`llm_prompts_FullTrans.json`、`float_llm_prompts.json`、`vl_bubble_prompts.json`、`vl_page_prompts.json` 和 `ocr_prompts.json`。加载时必须通过 `PromptAssetResolver` 处理语言变体。自定义提示词由 `CustomPromptStore` 按界面语言与模式保存在设置偏好中，请求构造通过该 Store 读取覆盖内容，未配置时读取内置资源。内置提示词以 `_说明` 字段按模式提供说明，编辑器直接展示，导出保留文档自身的说明，不另行注入。导入使用严格 JSON 校验，兼容缺失说明的旧文件；说明不参与协议占位符校验、保存的有效指令或缓存指纹，不得发送给模型。自定义提示词指纹计入悬浮缓存 scope 及使用 API 的 OCR 缓存，不影响已有译文的直接读取规则。

## 验证要求

- 文档或纯注释：检查 diff、路径和命令是否真实存在。
- Kotlin 小改动：至少运行 `./gradlew :app:compileDebugKotlin`。
- 有对应测试的逻辑改动：运行相关定向测试；共享契约、Store、Pipeline、网络或渲染算法改动应运行 `./gradlew :app:testDebugUnitTest`。
- 资源、Manifest、依赖、打包或跨模块行为：运行 `./gradlew :app:assembleDebug`；发布相关改动再检查 release 构建和 `update.json`。
- UI 改动必须检查窄屏、长文案、滚动、深浅主题和关键交互，不得只以编译通过作为完成标准。
- 无法运行要求的验证时，交付说明中必须明确未验证项和原因。

可按类定向运行 Robolectric/JUnit 测试，例如：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.manga.translate.TextBubbleTranslationCoordinatorTest'
```

## 日志与排查

- 日志入口为 `platform/AppLogger.kt`；日志优先写外部私有目录上级的 `log/`，回退到 `files/logs/`。
- 性能基线通过 `PerformanceTrace`（分阶段耗时 + `attribute()` 页面上下文）和 `RequestPerfTrace`（每请求重试次数、各次尝试的 HTTP 状态、耗时）以日志形式输出，两者共用 `loadModelIoLogging()` 开关，默认关闭。关闭时不采样时钟、不产生日志行。
- 新增性能埋点应复用这两个类，不要在业务代码里另写计时与日志拼接；耗时数值属于一次性测量结果，不得记录到本文。
- 普通日志通过有界队列在后台串行格式化、聚合并写入文件和 logcat，不得在调用线程等待写盘或强制刷盘；队列溢出必须记录丢弃数量。重复错误保留首次堆栈并定时输出计数摘要，致命异常绕过队列和聚合，同步写入并强制刷盘。
- Java/Kotlin 未捕获异常会写 `crash_latest.log`；ONNX 等 native 崩溃不会进入 Java handler，使用 `adb logcat` 或 tombstone 排查。
- 翻译问题依次检查 `TranslationPipeline`、`TextBubbleTranslationCoordinator`、`LlmClient` 和对应 `*.json` / `*.ocr.json`。
- 翻译供应商问题检查 `SettingsStore.load()`、`TranslationPipeline` 和 `FolderTranslationCoordinator`。
- OCR/区域问题检查 `OcrSharedTools`、`PageRegionDetector`、模型加载日志与坐标映射；不要先改缓存兼容规则。
- 后台任务问题检查 `TranslationKeepAliveService`、`FolderTranslationCoordinator`、`TranslationTaskPersistence` 和 `LibraryUiBridge`。

## 更新与文档维护

- 现状类文档（包括本文、README 和架构说明）只记录当前有效的实现、约束与使用方式，不记录修改过程、阶段回顾、前后对比或已废弃方案。实现变化时直接改写或删除过时描述，不追加“原先／本次改为／已完成”等过程记录；变更历史由版本控制和发布说明承载。开发计划只保留尚待执行的事项，完成后同步更新相关现状文档并移除对应计划，不累积完成日志。
- 更新元数据位于根目录 `update.json`，解析和弹窗入口为 `UpdateChecker.kt` / `MainActivity.kt`。
- 自动检查更新按持久化的成功时间限频，至少间隔 24 小时；连续无更新逐步延长至最多 30 天，发现新版本恢复每日检查。失败不推进成功记录，自动检查须串行判定限频；手动检查不受此限制。记录通过 `SettingsStore` / `AppSettingsStore` 访问。
- `update.json` 的 `changelog` 为简体中文默认值；`changelog_hant`、`changelog_en`、`changelog_ru` 为语言覆盖，缺失时回退 `changelog`。
- 新增模块、资源类型、持久化格式或跨模块行为约束时更新本文；普通实现调整只需更新源码和测试。
- 本文只保留当前有效的稳定约束和定位信息。具体阈值、模型尺寸和完整偏好键以源码与测试为准；版本迁移历史及一次性排查记录放在提交说明、发布说明或专门的历史记录中，不混入现状文档。兼容和恢复说明只保留当前仍适用的规则。
