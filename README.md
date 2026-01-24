# Manga Translator 📖

面向安卓的漫画翻译 App：本地气泡检测与 OCR，结合 OpenAI 兼容接口完成翻译，并在原图上覆盖显示可拖动的翻译气泡。
使用教程：https://github.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md

## 主要功能 ✨
- 日译中，英译中
- 漫画库管理：新建文件夹、批量导入图片、EhViewer 导入
- 翻译流程：气泡检测 + OCR + LLM 翻译，支持标准模式与全文速译
- 阅读体验：翻译覆盖层、翻译位置可拖动、阅读进度自动保存
- 译名表与缓存：按文件夹维护 glossary.json，自动累积固定译名
- 更新与日志：启动检查更新，翻译期间前台服务与日志查看

## 快速使用 🚀
1. 在漫画库中新建文件夹并导入图片
2. 确保图片文件名顺序与阅读顺序一致（例如 1.jpg, 2.jpg）
3. 在设置页填写 API 地址、API Key、模型名称（OpenAI 兼容）
4. 回到漫画库，选择文件夹并点击“翻译文件夹”
5. 翻译完成后点击“开始阅读”，在阅读页可拖动气泡位置

*全文速译建议：页数较多时分批上传翻译，或在设置中提高 API 超时。*

## 常见问题 ❓
- 翻译失败或结果为空：确认 API 地址以 `/v1` 结尾，模型名与供应商一致，且网络可达
- 翻译顺序错乱：请先对图片按阅读顺序重命名
- 译名不生效：译名只会补充缺失项，不会覆盖已有词条

## 交流
QQ群：1080302768

## Star History
** 喜欢的话可以点个Star哦 **
[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator&type=date&legend=top-left)


## 数据与文件说明 🗂️
- 漫画库存储：`/Android/data/<package>/files/manga_library/`
- 每张图片生成同名 `*.json` 翻译结果，OCR 缓存为 `*.ocr.json`
- 译名表：每个文件夹维护 `glossary.json`
- 阅读进度、全文速译开关等存储在 SharedPreferences

## 从源码构建 🧩

### 环境要求
- JDK 17.0.16+（推荐 17.0.17）
- Kotlin 2.0.0+
- Gradle 8.10+
- Android SDK: platform 34, build-tools 35.0.0

### 构建命令
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### 模型与资源
将以下模型文件放入 `assets/`：
- `comic-speech-bubble-detector.onnx`（气泡检测）
- `encoder_model.onnx`、`decoder_model.onnx`（OCR）

模型下载链接：
- 气泡检测模型：https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m
- OCR 模型：https://huggingface.co/l0wgear/manga-ocr-2025-onnx

提示词与 OCR 配置位于 `assets/`，名称需与代码保持一致。

### 发布版本号同步
需同时修改：
- `app/src/main/java/com/manga/translate/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`
