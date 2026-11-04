中文 | [English](./README_EN.md)

# Manga Translator 📖

面向 Android 的漫画翻译与阅读 App。自动检测气泡和游离文字，使用本地 OCR 或云端 OCR 配合 AI 翻译，也可直接交给视觉模型翻译，再将译文覆盖到原图上。支持漫画库批量处理、条漫阅读和跨应用悬浮窗翻译。

**需要自备翻译 API。** 本地检测和 OCR 可在设备上运行，翻译仍需要配置 AI 服务。

[下载 APK](https://github.com/jedzqer/manga-translator-android/releases) · [简中教程](./Tutorial/简中教程.md) · [API 获取指南](./Tutorial/API获取指南.md)

运行要求：Android 7.0（API 24）及以上。

| 原图 | 翻译结果 |
| --- | --- |
| ![原图](./Tutorial/FirePunch.webp) | ![翻译结果](./Tutorial/translated.webp) |

## 主要功能

- **漫画库**：管理文件夹、集合与章节，支持标签、排序、批量操作，导入图片或漫画文件夹，以及 CBZ、ZIP、PDF 导入导出。
- **多种翻译方式**：本地 OCR、API OCR、低置信度 AI OCR 补充、VL 视觉直译和全文速译；支持文件夹、集合及批量翻译。
- **跨页上下文**：多页合并请求，逐页保存结果；全文速译滚动维护译名表与剧情概览。
- **阅读与编辑**：横向阅读、条漫滚动、长图分块加载、缩放、气泡拖动和手动框选，自动保存阅读进度；条漫支持接缝气泡修复与跨页合并。
- **排版与字体**：横排或竖排、自定义字体与加粗、气泡形状、大小、透明度和文字填充比例。普通阅读与悬浮窗分别设置气泡样式，阅读与导出共用普通气泡设置。
- **悬浮窗翻译**：截取当前屏幕并叠加译文，支持手动框选、自定义悬浮球手势、半透明穿透或不透明模式。
- **AI 配置**：支持 OpenAI 兼容 Chat、OpenAI Responses 和 Gemini 接口，保存及切换供应商配置，自定义请求参数、提示词与翻译文风；文件夹或集合可单独设置文风。
- **任务与备份**：后台翻译进度和完成通知，应用内日志与更新检查；可分别备份漫画和译文、软件配置（含供应商与自定义字体）。

## 翻译方式怎么选

| 方式 | 工作流程与适用场景 |
| --- | --- |
| 标准翻译 | 检测区域 → OCR → 文字模型翻译。可使用本地 OCR 或 OpenAI 兼容 API OCR；本地 OCR 可启用低置信度 AI 补充。 |
| VL 直译 | 将标注了区域 ID 的图片发送给支持图片输入的模型。漫画库可选择完整画面或仅气泡；悬浮窗使用仅气泡的白底蒙版。 |
| 全文速译 | 在有界页面窗口内完成 OCR、译名提取、剧情概览更新和文字翻译，适合需要连续剧情上下文的作品。首张译图等待更久，固定启用译名处理，不使用 VL 直译。 |

“每次请求合并页数”可设为 1–500，1 表示不合并；VL 每次实际最多 50 页。合并仅影响 AI 请求，图片和译文仍逐页保存。处理窗口由应用根据内存自动调整，文字请求还会按模型上下文预算分批。

模型上下文可分别配置最大输入、最大输出和总上下文；未填写时使用应用预设，并不代表服务商模型的真实能力。请按实际模型限制填写，尤其是本地部署的小上下文模型。

## 支持的语言

目标语言跟随界面语言：

| 界面语言 | 翻译目标 |
| --- | --- |
| 简体中文 | 简体中文 |
| 繁体中文 | 繁体中文 |
| English | 英语 |
| Português (Brasil) | 巴西葡萄牙语 |
| Русский | 俄语 |

源语言可按文件夹设置：日文、英文、韩文、简体中文、繁体中文、中英混合、法文、西班牙文、葡萄牙文、德文、意大利文和俄文。俄文识别需要 API OCR；悬浮窗启动时可选择源语言及横竖排版。

## 快速使用

1. 安装 APK，在设置页填写主 AI 的接口格式、API 地址、Key 和模型名称，可通过“获取模型列表”选择模型。
2. 选择识别方式：本地 OCR 可直接使用；API OCR 或 AI OCR 补充需要另外配置支持图片输入的 OCR 服务；VL 直译需要主 AI 模型支持图片输入。
3. 在漫画库导入图片、文件夹或 CBZ / ZIP / PDF，确认页序，并设置源语言、阅读模式和需要的翻译方式。
4. 发起文件夹或集合翻译，在任务进度中查看状态；完成后进入阅读，可调整气泡位置、排版和字体。
5. 使用屏幕翻译时，授予悬浮窗与屏幕捕获权限，选择语言并启动悬浮球；通过悬浮球操作翻译或清屏。

## 常见问题

**API 地址怎么填？**

使用服务商提供的地址，例如 `https://api.deepseek.com/v1`。OpenAI 兼容格式自动追加 `/chat/completions`，Responses 格式追加 `/responses`；已经包含对应端点时原样使用，不会自动插入 `/v1`。OCR API 仅支持 OpenAI 兼容 Chat。接口格式、模型名和图片输入能力需与服务商一致，详见 [API 获取指南](./Tutorial/API获取指南.md)。

**请求超限、超时或译文不完整怎么办？**

检查服务商余额、网络和报错信息，并核对模型上下文设置。可降低并发数和合并页数；提高合并页数不保证更快，也可能降低质量。应用会自动分批并进行有限重试，无需为了全文速译手动拆分整个文件夹。

**更换语言、模型或文风后，为什么已有译文没变？**

已保存的译文是用户数据，会直接读取，不因设置变化自动失效。需要更新时主动重译；部分完成的页面只补填尚未翻译的气泡。OCR 缓存单独按识别配置校验。

**应用被系统结束后，会自动继续翻译吗？**

不会。重新打开后需手动发起任务，应用会利用已有页级译文和有效 OCR 缓存跳过已完成工作。

**悬浮译文显示时，为什么不能操作下方应用？**

不透明模式会拦截屏幕触摸，需使用配置的清屏手势退出；该模式要求至少设置一个清屏手势。需要继续操作下方应用时，选择半透明穿透模式，Android 12 及以上窗口透明度最高为 80%。

**局域网服务连不上？**

检查手机与服务所在网络及地址。Android 17 及以上还需要在设置页授予局域网访问权限。

## 数据与备份

漫画库位于应用的外部私有目录 `Android/data/com.manga.translate.v3/files/manga_library/`。

| 数据 | 内容 |
| --- | --- |
| `*.json` | 页级翻译结果 |
| `*.ocr.json` | 可重建的 OCR 缓存 |
| `glossary.json` | 文件夹译名表 |
| `synopsis.json` / `synopsis_<目标语言>.json` | 按目标语言保存的全文速译剧情概览 |

普通翻译可关闭译名处理，关闭后仍读取已有译名，但不新增译名。跨页合并的 OCR 结果仅保留在内存中。

在设置页导出应用备份，可独立选择“漫画和译文”（含库偏好、阅读进度）与“软件配置”（含 API 密钥、供应商配置、自定义字体）。换机或卸载前请先导出备份，并妥善保管包含密钥的备份文件。

## 从源码构建

### 环境与命令

- JDK 17。
- Android SDK Platform 37、Build Tools 37.0.0。
- 使用仓库的 Gradle Wrapper（9.3.1）；插件及依赖版本以 `build.gradle.kts` 和 `app/build.gradle.kts` 为准。
- 当前 Gradle 构建模块为 `:app`，源码按业务领域分包。

通过 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` 配置 SDK 路径，然后运行：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lint
```

Debug APK 位于 `app/build/outputs/apk/debug/`，Release APK 位于 `app/build/outputs/apk/release/`。发布签名通过 Gradle 属性 `STORE_FILE`、`STORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 配置；缺少签名配置时 release 产物不会自动签名。

### 模型与资源

自研的气泡/文字分割模型随本仓库提供，无需额外下载：

- `assets/models/detection/mixed-dual-s-e5_float16.tflite`：双标签（气泡 / 游离文字）实例分割，基于 Ultralytics YOLO26s-seg 训练（真实漫画标注 + 合成漫画数据混合），1472×1472 输入，TFLite FP16，约 21 MB，与本项目源码同为 AGPL-3.0
- SHA-256：`d030134cb00fb178a6433a8f2cfe5680c0bf34365ec64e0f6e5d0f28ec252c76`

其余模型都是第三方模型，不随开源仓库分发，构建前请自行下载并放到对应路径：

- `assets/models/detection/ppocrv6_small_det_640_fp32.tflite`：OCR 区域内部的 Paddle 文字行检测
- `assets/models/ocr/ppocrv6_small_rec_{320,640,960}_fp32.tflite` 与 `assets/models/ocr/characters.json`：日文、英文、中文及中英混合 OCR
- `assets/models/ocr/korean_PP-OCRv5_mobile_rec.onnx` 与 `assets/models/ocr/korean_PP-OCRv5_mobile_rec_dict.txt`：韩文 OCR 与字符表

也可以直接从上游仓库下载，下载到的文件名与上游一致：

```bash
cd assets/models
litert=https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT/resolve/main
curl -fL -o detection/ppocrv6_small_det_640_fp32.tflite $litert/ppocrv6_small_det_640_fp32.tflite
for w in 320 640 960; do curl -fL -o ocr/ppocrv6_small_rec_${w}_fp32.tflite $litert/ppocrv6_small_rec_${w}_fp32.tflite; done
curl -fL -o ocr/characters.json $litert/characters.json
kr=https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main
curl -fL -o ocr/korean_PP-OCRv5_mobile_rec.onnx $kr/inference.onnx
```

- PP-OCRv6 LiteRT 模型：https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT
- 韩文 OCR 模型：https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx （`inference.onnx` 改名为 `korean_PP-OCRv5_mobile_rec.onnx`；字符表取 `inference.yml` 中 `PostProcess.character_dict`，逐行写成 `korean_PP-OCRv5_mobile_rec_dict.txt`，共 11945 行）

缺第三方模型时工程仍可编译，但运行到本地检测或 OCR 会因缺少 assets 文件失败。

通用 OCR 与行检测使用 LiteRT GPU FP32，失败后在 CPU 重试。识别模型按行宽按需加载 320/640/960，超宽行完整缩放到 960；字典保留原始 CTC 索引。韩文仍使用 ONNX。
应用保留居中补边、轴对齐行框和 Android 图像缩放；不声明与上游 OpenCV/PyTorch 整条流程逐像素一致。

内置提示词位于 `assets/prompts/`，包含五种界面语言的变体；资源文件名需与代码保持一致。

## 致谢

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)：OCR 模型。
- [Ultralytics](https://github.com/ultralytics/ultralytics)：YOLO26s-seg 训练框架，自研气泡/文字分割模型由此训练（AGPL-3.0）。
- [LiteRT Community](https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT)：PP-OCRv6 LiteRT 模型。
- [Mihon SSIV](https://github.com/mihonapp/subsampling-scale-image-view)：大图分块显示。
- 所有贡献者与用户的支持。

## 许可证

本项目源码与 `assets/models` 下的自研分割模型 `mixed-dual-s-e5_float16.tflite` 均按 [AGPL-3.0](./LICENSE) 发布，Copyright (C) 2026 jedzqer。分发修改版，或通过网络向他人提供本应用服务时，需按 AGPL-3.0 公开完整对应源码，包括模型与相关训练、推理脚本。

自研模型基于 Ultralytics YOLO26s-seg 训练，因此沿用 AGPL-3.0；如在闭源或商业环境中使用本应用，需自行向 Ultralytics 取得企业许可。

第三方模型不随本仓库分发，许可归各自作者：PP-OCRv6 LiteRT 模型与韩文 OCR 模型均为 Apache-2.0。

更早以 MIT 发布的版本（v3.8.0 及之前）依旧按当时的 MIT 许可可用，本次变更只约束其后的代码。

## 交流

QQ群：1080302768

喜欢这个项目的话，欢迎点个 Star。

[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator-android&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator-android&type=date&legend=top-left)
