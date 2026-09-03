# Manga Translator AI Agent 指南

## 适用范围

本文面向在本仓库中开发、修复和审查代码的 AI Agent，提供必须遵守的约束、验证方式和高频源码入口。

- `必须` / `不得` 表示硬性要求；`优先` 表示没有明确反例时采用的默认方案。
- 行为细节以现有测试和源码为准。若本文与实现不一致，先确认是文档过期还是代码回归，再同步修正实现、测试或文档，不得静默选择其中一方。
- 开始修改前检查工作区状态；保留用户已有改动，不得自动拉取远端、切换分支或回退无关文件。
- 修改范围应紧贴任务。不要借机重构无关模块，也不要把临时实现过程或单次调参记录到本文。

## 项目基线

- 单模块 Android 应用，模块为 `app`。
- JDK 17；Java/Kotlin JVM target 17。
- `compileSdk` / `targetSdk` 36，`minSdk` 24；仅打包 `arm64-v8a`。
- Kotlin、AGP 和依赖版本以 `build.gradle.kts`、`app/build.gradle.kts` 为唯一来源。
- 依赖仓库统一配置在 `settings.gradle.kts`，不得在根或模块构建脚本重复声明。
- Kotlin 源码根目录：`app/src/main/java/com/manga/translate/`。
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
- 翻译结果和 OCR 缓存的有效性必须包含所有会改变译文语义的维度。`TranslationStore.isMetadataUsable()` 校验源文件指纹、版本、`language`、`mode`、`promptAsset` 和 `ocrCacheMode`；`apiFormat` 仅记录产出译文的供应商协议，不参与可用性判定，切换 AI 供应商（含跨 API 格式）不得使已落盘译文失效。批量补填共用 `matchesPartialTranslationRequest()`。新增会影响译文的配置维度时必须同步接入，不得放宽比较。
- 悬浮窗翻译缓存的键与相似度匹配范围统一由 `FloatingCacheScope`（语言 + `providerId` + `modelName` + `promptAsset`）决定。该缓存启用相似文本匹配，缺失任一维度会导致静默返回错误译文；新增影响译文的维度必须加入 scope 并提升缓存 schema 版本。
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
| 漫画库 | `library/LibraryFragment.kt`、`LibraryRepository.kt`、`LibraryImportExportCoordinator.kt`、`LibraryPreferencesGateway.kt` |
| 阅读 | `reader/ReadingFragment.kt`、`ReadingSessionViewModel.kt`、`ReadingBitmapDecoder.kt`、`ReadingRegionImageView.kt`、`WebtoonReadingAdapter.kt` |
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
- 并发逐页翻译使用 glossary 快照，成功后串行合并。关闭译名处理时仍可读取已有 `glossary.json` 作为上下文，但不得提取、合并或写入新译名。
- 全文速译固定启用译名处理；`CrossPageBubbleMerger` 只用于 `WEBTOON_SCROLL`，普通横向阅读不得执行跨页气泡合并。

### 检测与 OCR

- `PageRegionDetector` 是主翻译与悬浮窗共享的页面区域入口，支持仅气泡、仅文字、气泡加游离文字三种模式。
- 普通气泡来自 `BubbleDetector`，标记为 `BubbleSource.BUBBLE_DETECTOR` 并保留可用的 `maskContour`；Paddle 文字块由 `TextBlockMerger` 合并，标记为 `BubbleSource.TEXT_DETECTOR`。
- 长图分块、坐标映射、去重阈值和模型输入细节属于检测模块，不得复制到 Pipeline 或悬浮窗 Service。
- 本地 OCR 的语言路由、裁剪、行识别和 API fallback 统一在 `OcrSharedTools.kt` 调整。

### 阅读与渲染

- 普通页优先使用 `ARGB_8888`；长图或超高分辨率页面通过 `ReadingRegionImageView` 分块解码，并以源图分辨率作为布局坐标。
- 普通气泡设置作用于阅读页、条漫页和导出；悬浮窗气泡设置只作用于 overlay。两套大小、透明度、形状和文字密度设置不得串用。
- 全局字体由 `BubbleFontResolver` 管理，普通渲染与悬浮窗共用；上传字体持久化在应用私有 `custom_fonts` 目录。
- `BubbleTextScaling` 和竖排布局是共享算法。修改排版、路径缩放或密度规则时，必须检查 `BubbleRenderer`、`FloatingTranslationView`、`FloatingDetectionOverlayView` 以及对应测试。
- `BubbleSource.TEXT_DETECTOR` 才是普通模式中的游离文字框；用户手动框继续按普通气泡参数处理。

### 后台任务

- `LibraryFragment` 只提交任务描述；`TranslationKeepAliveService` 持有任务 `CoroutineScope` / `Job`，是文件夹、合集和批量翻译的实际执行宿主。
- `FolderTranslationCoordinator` 负责编排并返回任务，不得自行启动或停止保活 Service。
- `TranslationTaskPersistence` 只记录活动任务描述，供同进程管理和异常清理；应用冷启动或 Service 被系统重建时必须清除，绝不自动续跑旧任务。
- 进程重启后只能由用户重新发起任务；此时依靠已有 `*.ocr.json` / `*.json` 页级结果跳过已完成页面。
- Service 状态通过 `LibraryUiBridge` 和 `ServiceLibraryUiCallbacks` 转发，不得让后台任务直接持有 Fragment/View 引用。

### 网络请求

- 文字、图片、OCR 和模型列表请求统一由 `LlmClient` 通过 OkHttp 执行；超时、取消、请求头和重试策略不得散落到 Coordinator。
- OpenAI 兼容地址：已以 `/chat/completions` 结尾则原样使用，否则直接追加该路径；不得自动插入 `/v1`。
- OpenAI Responses 地址同理追加 `/responses`；两种 OpenAI 格式的模型列表地址追加 `/models`。
- `OPENAI_RESPONSES` 用 `model`、`input` 和可选 `instructions`，响应优先读 `output_text`，再解析 `output[].content[].text`。
- OCR API 只支持 OpenAI 兼容 chat，不支持 Responses 格式。
- 主 AI 自动重试次数来自 `SettingsStore`；可重试范围由 `LlmClient` 统一判断，包括网络错误、超时、HTTP 408/429/5xx 和明确的暂时不可用响应。

### 设置与持久化

- `SettingsStore` 是设置访问门面；新增设置应归入 `ApiSettingsStore`、`OcrSettingsStore`、`RenderSettingsStore`、`AppSettingsStore`、`LlmParameterStore` 或 `ProviderProfileStore`，不得继续膨胀 UI 层持久化逻辑。
- 文件夹级设置、标签、排序和阅读方式统一经 `LibraryPreferencesGateway`。
- AI 供应商 profile 位于 `files/ai_provider_profiles.json`；附加供应商和其他偏好键以对应 Store 常量为准，不在本文复制完整键表。
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
| 应用备份包（ZIP） | `AppBackupManager`；包含 `manga_library/`、设置偏好、AI profile 和自定义字体 |
| 当前后台任务描述 | `TranslationTaskPersistence` |
| 阅读进度 | `ReadingProgressStore` |

Prompt 位于 `assets/prompts/`，基础文件包括 `llm_prompts.json`、`llm_prompts_abstract.json`、`llm_prompts_FullTrans.json`、`float_llm_prompts.json`、`vl_bubble_prompts.json` 和 `ocr_prompts.json`。加载时必须通过 `PromptAssetResolver` 处理语言变体。

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
- Java/Kotlin 未捕获异常会写 `crash_latest.log`；ONNX 等 native 崩溃不会进入 Java handler，使用 `adb logcat` 或 tombstone 排查。
- 翻译问题依次检查 `TranslationPipeline`、`TextBubbleTranslationCoordinator`、`LlmClient` 和对应 `*.json` / `*.ocr.json`。
- 翻译供应商问题检查 `SettingsStore.load()`、`TranslationPipeline` 和 `FolderTranslationCoordinator`。
- OCR/区域问题检查 `OcrSharedTools`、`PageRegionDetector`、模型加载日志与坐标映射；不要先改缓存兼容规则。
- 后台任务问题检查 `TranslationKeepAliveService`、`FolderTranslationCoordinator`、`TranslationTaskPersistence` 和 `LibraryUiBridge`。

## 更新与文档维护

- 更新元数据位于根目录 `update.json`，解析和弹窗入口为 `UpdateChecker.kt` / `MainActivity.kt`。
- `update.json` 的 `changelog` 为简体中文默认值；`changelog_hant`、`changelog_en`、`changelog_ru` 为语言覆盖，缺失时回退 `changelog`。
- 新增模块、资源类型、持久化格式或跨模块行为约束时更新本文；普通实现调整只需更新源码和测试。
- 本文只保留稳定约束和定位信息。具体阈值、模型尺寸、完整偏好键、版本迁移历史及一次性排查记录应留在源码、测试、提交说明或专项文档中。
