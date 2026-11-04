[中文](./README.md) | English

# Manga Translator 📖

An Android app for translating and reading manga. It detects speech bubbles and free-standing text, combines local or cloud OCR with AI translation, or sends images directly to a vision model, then overlays translations on the original pages. It supports batch library translation, webtoon reading, and screen translation over other apps.

**Bring your own translation API.** Detection and local OCR can run on your device; translation requires a configured AI service.

[Download APK](https://github.com/jedzqer/manga-translator-android/releases) · [English Tutorial](./Tutorial/Tutorial_EN.md) · [API Guide](./Tutorial/API_Guide_EN.md)

Requires Android 7.0 (API 24) or later.

| Original | Translated |
| --- | --- |
| ![Original](./Tutorial/FirePunch.webp) | ![Translated](./Tutorial/translated.webp) |

## Key Features

- **Manga library**: folders, collections and chapters, tags, sorting and batch actions; import images or manga folders, and import/export CBZ, ZIP and PDF.
- **Translation options**: local OCR, API OCR, AI OCR supplementation for low-confidence recognition, direct vision-language (VL) translation, and full-text fast translation. Translate folders, collections or multiple selections.
- **Context across pages**: combine pages into AI requests while saving each page separately; full-text fast translation maintains a glossary and a rolling story synopsis.
- **Reading and editing**: horizontal paging, webtoon scrolling, tiled loading for large images, zoom, draggable bubbles, manual region selection and saved reading progress. Webtoon mode supports seam repair and cross-page bubble merging.
- **Layout and fonts**: horizontal or vertical text, custom fonts, bold text, bubble shapes, size, opacity and text fill ratios. Reader and floating overlays have separate bubble settings; reading and export share the reader settings.
- **Screen translation**: capture the screen and overlay translations, with manual selection, configurable floating-ball gestures, translucent touch-through mode or opaque mode.
- **AI configuration**: OpenAI-compatible Chat, OpenAI Responses and Gemini formats; saved provider profiles, custom request parameters, prompts and translation styles. Folders and collections can override the global style.
- **Tasks and backups**: background progress and completion notifications, in-app logs and update checks; separately back up manga and translations or app configuration, including providers and custom fonts.

## Choosing a Translation Mode

| Mode | Workflow and use |
| --- | --- |
| Standard | Detect regions → OCR → text-model translation. Use local OCR or an OpenAI-compatible OCR API; optionally supplement low-confidence local results with AI OCR. |
| Direct VL | Send images with labeled region IDs to a model that supports image input. Library translation can include the full page or only bubbles; screen translation uses bubbles on a white mask. |
| Full-text fast translation | Process bounded windows of pages through OCR, glossary extraction, rolling synopsis updates and text translation. Useful for continuous story context. The first translated page takes longer; glossary processing is always enabled, and this mode does not use direct VL translation. |

“Pages per request” accepts 1–500; 1 disables merging, and VL uses at most 50 pages per request. Merging affects AI requests only: images and results remain separate pages. The app adjusts processing windows to available memory, and text requests are also batched against the model context budget.

Model context settings accept separate maximum input, maximum output and total context limits. Empty fields use app defaults, which are not verified provider limits. Enter the limits of your actual model, especially for local deployments with smaller context windows.

## Supported Languages

The translation target follows the UI language:

| UI language | Translation target |
| --- | --- |
| 简体中文 | Simplified Chinese |
| 繁體中文 | Traditional Chinese |
| English | English |
| Português (Brasil) | Brazilian Portuguese |
| Русский | Russian |

Set the source language per folder: Japanese, English, Korean, Simplified Chinese, Traditional Chinese, mixed Chinese/English, French, Spanish, Portuguese, German, Italian or Russian. Russian recognition requires API OCR. Screen translation lets you choose the source language and horizontal or vertical layout when starting.

## Quick Start

1. Install the APK and configure the main AI service in Settings: API format, URL, key and model name. You can use “Fetch model list” to select a model.
2. Choose recognition settings. Local OCR is ready to use; API OCR or AI OCR supplementation requires a separately configured OCR service with image input. Direct VL requires image input support from the main AI model.
3. Import images, folders or CBZ / ZIP / PDF files into the library. Check page order and choose the source language, reading mode and translation mode.
4. Start folder or collection translation and follow task progress. Open the reader to adjust bubble positions, layout and fonts.
5. For screen translation, grant overlay and screen-capture permissions, choose a language and start the floating ball. Use its actions to translate or clear the screen.

## FAQ

**What API URL should I enter?**

Use the address supplied by your provider, such as `https://api.deepseek.com/v1`. OpenAI-compatible Chat appends `/chat/completions`; Responses appends `/responses`. URLs already ending in the matching endpoint are used as-is; the app does not insert `/v1`. OCR APIs support OpenAI-compatible Chat only. Match the format, model name and image-input support to your provider. See the [API Guide](./Tutorial/API_Guide_EN.md).

**What if requests exceed limits, time out or return incomplete translations?**

Check your provider balance, network, error details and model context settings. Reduce concurrency or pages per request. Larger batches are not necessarily faster and may reduce quality. The app batches requests automatically and uses bounded retries; full-text fast translation does not require manually splitting an entire folder.

**Why did existing translations stay the same after changing language, model or style?**

Saved translations are user data and remain available regardless of setting changes. Explicitly retranslate to replace them; partially completed pages fill only untranslated bubbles. OCR cache validity is checked separately against recognition settings.

**Will translation resume automatically after the app process is stopped?**

No. Start the task again yourself; existing page translations and valid OCR caches let the app skip completed work.

**Why can’t I interact with the app underneath translated bubbles?**

Opaque mode intercepts screen touches. Use a configured clear-screen gesture to exit; this mode requires at least one such gesture. Choose translucent touch-through mode to interact with the underlying app. On Android 12 and later, its window opacity is capped at 80%.

**Why can’t I connect to a local-network service?**

Check the server address and network connectivity. On Android 17 and later, also grant local-network access from Settings.

## Data and Backups

The manga library is stored in the app’s external private directory: `Android/data/com.manga.translate.v3/files/manga_library/`.

| Data | Contents |
| --- | --- |
| `*.json` | Per-page translation results |
| `*.ocr.json` | Rebuildable OCR cache |
| `glossary.json` | Folder glossary |
| `synopsis.json` / `synopsis_<target language>.json` | Full-text fast translation synopsis, separated by target language |

Standard translation can disable glossary processing: existing terms remain available as context, but no new terms are added. OCR results merged across pages are kept only in memory.

Export an app backup from Settings, choosing manga and translations (including library preferences and reading progress), app configuration (including API keys, provider profiles and custom fonts), or both. Export before uninstalling or moving devices, and keep backups containing keys private.

## Build from Source

### Environment and Commands

- JDK 17.
- Android SDK Platform 37 and Build Tools 37.0.0.
- Use the repository’s Gradle Wrapper (9.3.1). Plugin and dependency versions are defined in `build.gradle.kts` and `app/build.gradle.kts`.
- The registered Gradle module is `:app`; source packages are organized by business domain.

Set the SDK path with `ANDROID_HOME` or `sdk.dir` in `local.properties`, then run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lint
```

Debug APKs are written to `app/build/outputs/apk/debug/`; release APKs go to `app/build/outputs/apk/release/`. Configure release signing through Gradle properties `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without signing configuration, release artifacts are not automatically signed.

### Models and Assets

The bubble/text segmentation model that ships with this repository needs no extra download:

- `assets/models/detection/mixed-dual-s-e5_float16.tflite`: dual-label (bubble / free text) instance segmentation, trained with Ultralytics YOLO26s-seg on annotated data, 1472x1472 input, TFLite FP16, about 21 MB, released under AGPL-3.0 like the rest of this project
- SHA-256: `d030134cb00fb178a6433a8f2cfe5680c0bf34365ec64e0f6e5d0f28ec252c76`

All other models are third-party models and are not distributed with this repository. Download them yourself and place them at the paths below before building:

- `assets/models/detection/ppocrv6_small_det_640_fp32.tflite`: Paddle text-line detection inside OCR regions
- `assets/models/ocr/ppocrv6_small_rec_{320,640,960}_fp32.tflite` and `assets/models/ocr/characters.json`: Japanese, English, Chinese, and mixed OCR
- `assets/models/ocr/korean_PP-OCRv5_mobile_rec.onnx` and `assets/models/ocr/korean_PP-OCRv5_mobile_rec_dict.txt`: Korean OCR and character dictionary

Download them from the upstream repositories yourself; upstream filenames match:

```bash
cd assets/models
litert=https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT/resolve/main
curl -fL -o detection/ppocrv6_small_det_640_fp32.tflite $litert/ppocrv6_small_det_640_fp32.tflite
for w in 320 640 960; do curl -fL -o ocr/ppocrv6_small_rec_${w}_fp32.tflite $litert/ppocrv6_small_rec_${w}_fp32.tflite; done
curl -fL -o ocr/characters.json $litert/characters.json
kr=https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main
curl -fL -o ocr/korean_PP-OCRv5_mobile_rec.onnx $kr/inference.onnx
```

- PP-OCRv6 LiteRT models: https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT
- Korean OCR model: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx (rename `inference.onnx` to `korean_PP-OCRv5_mobile_rec.onnx`; write the `PostProcess.character_dict` entries of `inference.yml` one per line to `korean_PP-OCRv5_mobile_rec_dict.txt`, 11945 lines)

Without the third-party models the project still compiles, but local detection or OCR fails at runtime because the assets are missing.

General OCR and line detection use LiteRT GPU FP32 with CPU fallback. Recognition loads 320/640/960 models on demand by line aspect ratio; wider lines are scaled in full to 960. The dictionary retains its original CTC indices. Korean recognition still uses ONNX.
The app retains centered letterboxing, axis-aligned line boxes and Android image resizing; it does not claim pixel parity with the upstream OpenCV/PyTorch pipeline.

Built-in prompts live in `assets/prompts/`, with variants for all five UI languages. Asset filenames must match the code.

## Acknowledgements

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR): OCR models.
- [Ultralytics](https://github.com/ultralytics/ultralytics): YOLO26s-seg training framework, used to train the segmentation model bundled with this repository (AGPL-3.0).
- [LiteRT Community](https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT): PP-OCRv6 LiteRT models.
- [Mihon SSIV](https://github.com/mihonapp/subsampling-scale-image-view): tiled image display.
- All contributors and users for their support.

## License

This project's source code and the segmentation model `assets/models/detection/mixed-dual-s-e5_float16.tflite` bundled with this repository are released under [AGPL-3.0](./LICENSE), Copyright (C) 2026 jedzqer. If you distribute a modified version, or offer this app as a network service, you must publish the complete corresponding source code under AGPL-3.0, including the model and related training and inference scripts.

The bundled model was trained with Ultralytics YOLO26s-seg and therefore inherits AGPL-3.0; using this app in a closed-source or commercial setting requires an Ultralytics Enterprise License.

Third-party models are not distributed with this repository and keep their own licenses: the PP-OCRv6 LiteRT models and the Korean OCR model are Apache-2.0.

Releases published earlier under MIT (v3.8.0 and before) remain usable under MIT; this change applies to later code.

## Community

[Discord server](https://discord.gg/qxfC2kqfr)

If you like this project, please consider giving it a star.

[![Star History Chart](https://api.star-history.com/svg?repos=jedzqer/manga-translator-android&type=date&legend=top-left)](https://www.star-history.com/#jedzqer/manga-translator-android&type=date&legend=top-left)
