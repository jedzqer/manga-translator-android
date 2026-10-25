# Manga Translator 📖

[中文](./README.md) | English

An Android manga translation app with local speech bubble detection and OCR, combined with an OpenAI-compatible API for translation. Translated text is rendered back onto the original image as draggable text bubbles. It also supports screen translation with a floating overlay, so you can recognize and translate manga text directly from any app or from the home screen.

Tutorial: [English Tutorial](./Tutorial/Tutorial_EN.md) | [Simplified Chinese Tutorial](./Tutorial/简中教程.md)

| Original | Translated |
|------|----------|
| ![Original](./Tutorial/FirePunch.webp) | ![Translated](./Tutorial/translated.webp) |

## Key Features ✨
- Translate Japanese, English, Korean, French, Spanish, Portuguese, German, Italian, Russian and more into Chinese, or translate Chinese into English/Russian
- Screen translation: supports floating-window translation to recognize and translate manga text from any screen
- Manga library management: create folders, import images in batch, import manga folders, and support CBZ, ZIP, and PDF import/export
- Translation pipeline: speech bubble detection + local or OpenAI-compatible API OCR + LLM translation, with both standard mode and full-text fast translation
- Reading experience: translation overlay, draggable translated bubbles, and automatic reading progress saving; new cancel button and +/- fine-tune controls, synchronized zoom between webtoon and normal reading
- Font settings: custom bubble fonts and bold style are supported; normal bubbles and floating-window bubbles share the same font configuration
- Glossary and cache: maintain `glossary.json` per folder and automatically accumulate consistent name translations
- Background translation notification: sends an audible high-priority system notification when folder/batch translation finishes; tapping it returns to the library
- Updates and logs: check for updates on launch, foreground service during translation, and in-app log viewing
- Webtoon/long-image support: automatically detect whether a work is closer to webtoon layout and switch reading mode; cross-page bubble merging is supported in webtoon/long-image mode

## Supported Translation Languages 🌐
- Target language is determined by the app UI language:
  - Simplified Chinese UI -> Simplified Chinese
  - Traditional Chinese UI -> Traditional Chinese
  - English UI -> English
  - Russian UI -> Russian
  - Brazilian Portuguese UI -> Brazilian Portuguese
- Source language for each folder can be set independently in the library and supports: Japanese, English, Korean, Simplified Chinese, Traditional Chinese, Chinese-English mixed, French, Spanish, Portuguese, German, Italian, Russian
- When the app UI is switched to Traditional Chinese, it will prioritize Traditional Chinese prompts

## Quick Start 🚀
1. Create a folder in the manga library and import images
2. Make sure image filenames match the reading order, such as `1.jpg`, `2.jpg`
3. In Settings > OCR Settings, choose local OCR or enter the URL, key, and model for an OpenAI-compatible OCR API
4. Return to the library, choose a folder, and tap "Translate Folder"
5. After translation finishes, tap "Start Reading" and drag bubble positions on the reader page as needed

*For full-text fast translation, it is recommended to upload and translate in batches for large folders, or increase the API timeout in Settings.*

## FAQ ❓
- Translation fails or returns empty results: make sure the API URL is the OpenAI-compatible base URL provided by the service (for example, `https://api.deepseek.com/v1` or `https://open.bigmodel.cn/api/paas/v4`); the app will auto-append `/chat/completions`. The model name must match the provider and the network must be reachable
- Translation order is incorrect: rename images first so they match the reading order
- How do I get an AI API: please search for a suitable provider based on your needs

## Community
Join the QQ group for questions and discussion: `1080302768`

## Star History
** If you like this project, please consider giving it a star **
[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator-android&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator-android&type=date&legend=top-left)

## Data and File Layout 🗂️
- Manga library storage: `/Android/data/<package>/files/manga_library/`
- Each image generates a same-name `*.json` translation result, and OCR cache is stored as `*.ocr.json`
- Glossary: each folder maintains its own `glossary.json`
- Reading progress, full-text fast translation switches, and related settings are stored in SharedPreferences

## Build from Source 🧩

### Requirements
- JDK 17.0.17+
- Kotlin 2.3.0
- Gradle 9.3.1 (Wrapper)
- Android SDK: platform 37.0 (Android 17), build-tools 37.0.0

### Build Commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### Models and Assets
Place the following model files into the corresponding subdirectories under `assets/`:
- `models/detection/mixed-dual-s-e5_float16.tflite` (YOLO26s-seg bubble/text segmenter at 1472x1472, TFLite FP16)
- `models/detection/PP-OCRv6_det_mobile_infer.onnx` (Paddle text-line detection inside OCR regions)
- `models/ocr/PP-OCRv6_small_rec.onnx` (Japanese, English, Chinese, and mixed OCR)
- `models/ocr/korean_PP-OCRv5_mobile_rec.onnx` and `models/ocr/korean_PP-OCRv5_mobile_rec_dict.txt` (Korean OCR and character dictionary)
- `models/detection/PP-OCRv6_det_mobile_infer.onnx` (English line detection)

Model download links:
- Speech-bubble and free-text detection model: YOLO26s dual-label text block detector (bundled in app assets)
- Text detection model: PaddleOCR PP-OCRv6 mobile det
- General recognition model: https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx
- English detection model: https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx
- Korean OCR model: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx

Prompts, fonts, and OCR configuration files are located in subdirectories under `assets/`, and their names must stay consistent with the code.

### Release Version Sync
Update all of the following files at the same time:
- `app/src/main/java/com/manga/translate/VersionInfo.kt`
- `app/build.gradle.kts`
- `update.json`

## Acknowledgements 🙏

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - OCR model support
- [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) - MangaOCR model support
- [bluolightning/manga-ocr-mobile](https://huggingface.co/bluolightning/manga-ocr-mobile) - MangaOCR-mobile model support
- Support from all users
