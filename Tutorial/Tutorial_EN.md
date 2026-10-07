# User Guide

> This guide is maintained for the latest version of the app. Earlier versions may have different screens or features.

For feedback and discussion, join the [Discord server](https://discord.gg/qxfC2kqfr).

---

## Main Translation Workflow

### 1. Import a manga

On the **Library** screen:

- Tap **+** in the bottom-right corner to create a folder, then add manga images to it.
- Tap **Import Manga Folder** to import a complete folder.
  - A folder containing chapter subfolders is imported as a collection with child chapters.
  - A folder containing images directly is imported as one manga.
- Tap **Import CBZ/ZIP/PDF** to import an archive or PDF. The app shows an import progress message.
- Ensure that image filenames match their intended reading order, such as `1.jpg`, `2.jpg`, and `3.jpg`.

### 2. Configure an API

The app requires an API from a supported AI provider. See the [API Setup Guide](./API_Guide_EN.md) if you do not have one yet.

> The app UI language determines the target language. Simplified Chinese, Traditional Chinese, English, Brazilian Portuguese, and Russian UIs translate into their respective languages.

In **Settings**, enter the API details for your provider:

1. **API Format**: select the format specified in the provider section of the API guide.
2. **API URL**: enter the provider's base URL. The app appends `/chat/completions` for **OpenAI compatible** and `/responses` for **OpenAI Responses** when needed.
3. **API Key**: paste the key generated in the provider dashboard. Keep it private.
4. **Model name**: enter the exact model ID shown by the provider, or select one with **Get Model List** when available.

Return to the library after saving the settings.

### 3. Translate

1. Set the source language in the folder and tap **Translate**. Japanese, English, Korean, French, Spanish, Portuguese, German, Italian, Russian, and Chinese are supported. You can also translate a whole collection.
2. Wait for the translation to finish, or tap **Start Reading** after preprocessing to read while pages continue translating.
3. A high-priority notification is sent when background translation succeeds, fails, or is canceled. Tap it to return to the library.

> The app automatically switches works that resemble webtoons to **Webtoon Scroll**. Change this in the folder's reading settings when necessary.
>
> **Full-text fast translation** processes windows of up to 100 pages: OCR, glossary extraction, then translation and saving, before moving to the next window. It no longer waits for OCR of the entire work, but glossary extraction still delays the first translated page. Turn it off to start reading sooner with ordinary translation.

### 4. Read and adjust bubbles

- Drag a translated bubble to move it. Changes are saved automatically.
- Double-tap the page to enter or leave zoom mode. In zoom mode, use the cancel control or the `+` and `-` controls to fine-tune bubbles.
- To jump to a page, tap its image filename in the library folder.
- To retranslate pages, return to the library and long-press an image to enter selection mode. Select the pages and tap **Retranslate Selected**. Use **Select All** to retranslate every page; there is no need to delete the manga.
- To add a missing text area, tap the edit button in the top-right corner, tap **+**, place an empty bubble over the text, then confirm with the green checkmark. Webtoon mode allows bubbles to span pages.
- If full-text fast translation is too slow, turn it off to use concurrent page-by-page translation instead.
- Adjust normal or free-text bubble opacity under **Normal Bubble Settings** when bubbles cover too much artwork. Floating-window bubbles have separate settings.
- Free-text bubbles automatically adapt their text color to the background.
- Customize the global translation style in Settings, or set a style for a folder. Chapters in a collection share the collection's style. Turning off **Follow Global Style** and leaving the field empty adds no style instruction.
- Upload a custom font or enable bold text under **Font Settings**. Normal and floating-window bubbles share this font setting.

---

## Translation Options: Cost and Quality

| Option | Requests and cost | Quality and suitable uses |
| --- | --- | --- |
| Ordinary translation: local OCR, supplementation off | Only recognized text is sent to the translation model; usually the cheapest option | Good for clear lettering, regular layouts, and simple content. OCR mistakes affect the translation |
| Ordinary translation + AI OCR supplementation | Problematic regions receive extra cropped-image OCR requests before text translation. Cost can stay low when few regions need help and the OCR model is inexpensive | Balances cost and recognition quality. It cannot catch every OCR mistake |
| Full-text fast translation + API OCR | Completes API OCR, glossary extraction, and translation in windows; image-recognition requests and costs are higher | Usually the highest-quality combination overall. API OCR can reduce local-recognition errors, while full-text translation keeps character and place names consistent; suitable when final quality matters most |
| Vision-language full-page translation | Sends annotated pages or long-image sections to the main vision model and skips OCR; usually costs more than local OCR plus text translation | Theoretically the fastest option. Actual speed depends on API response time, concurrency, and image processing; detection and model capabilities still limit results |
| Vision-language translation + moderate page merging | Sends several pages and their sections together, increasing image usage and cost per request; still a higher-budget option | Can reduce request count and let the model use neighboring dialogue as context, but actual speed still depends on the API. Excessive merging can reduce quality or cause omissions |

### Suggested starting configurations

- **Lowest cost**: turn off full-text fast translation and vision-language translation, use local OCR with supplementation off, and choose an inexpensive text model with adequate translation quality. For works with few recurring names, you can also disable **Glossary Processing**; existing terms remain available as context.
- **Balanced cost and quality**: keep local OCR on and start AI OCR supplementation at **Low**, increasing to **Medium** if recognition is still poor. Configure an inexpensive image-capable OCR model that recognizes the source language well. The main model continues translating the text, so OCR supplementation does not need to use an expensive main model.
- **Highest quality**: usually enable full-text fast translation, turn off local OCR, and use a capable API OCR model together with a strong translation model. This improves OCR accuracy and glossary consistency, but increases requests and cost.
- **Theoretical fastest**: enable vision-language full-page translation and use a fast, highly concurrent vision model. Actual speed depends on the API provider and network conditions, and image costs are usually higher.

### Why per-request billing is a poor fit for OCR supplementation

OCR supplementation sends requests **per recognition region**, not once per page. If eight regions on a page need supplementation, that can add eight image-recognition requests before text translation. Medium and High review more regions, potentially increasing requests, latency, and cost substantially. **Prefer an inexpensive vision model billed by usage, and check its image-input pricing.** OCR currently supports only the OpenAI-compatible chat API, and the model must accept images; a text-only model cannot perform supplementation.

### Page merging for translation

- Moderate merging can improve dialogue context and naming consistency while reducing request count and repeated prompts. This may save money on a **translation API** billed per request, but it does not reduce API OCR or supplementation request counts.
- The limit is 100 pages, with 5–15 suggested. Start smaller for vision-language translation, long images, or dense text. Large requests increase device and AI workload.

> Draw a box manually around missed text regions to add them to the translation.

---

## Floating-Window Translation

On the Library screen, tap **Floating Translation**. Grant overlay and screen-capture permissions, then choose the translation language for the session. The floating button uses these default gestures; you can reassign single, double, long, and triple taps in **Floating Translation Settings**:

| Action | Result |
| --- | --- |
| Tap the floating button | Recognize and translate text currently on screen |
| Double-tap the floating button | Clear all bubbles |
| Long-press the floating button | Open the menu |

The long-press menu includes:

- **Edit mode**: the overlay receives touch input. Drag bubbles, long-press to delete one, or tap **Add Bubble** and draw a rectangle for text the detector missed. New bubbles are translated after confirmation.
- **Swipe translation**: enter edit mode directly and select regions manually without automatic detection.
- **Translation detection region**: draw and confirm a region on the screen. It takes effect on the next translation; you can also restore the full screen.
- **Exit**: close the floating window.

Floating-window edits exist only for the current session. They reset after a new recognition pass or when the floating window closes. If the screen is blank or screen capture is restricted, the app stops recognition and shows a message. For other missing results, check screen-capture permission and API settings.

---

## Other Settings

### OCR settings

Open **OCR Settings** from Settings:

- **Use local OCR** recognizes text offline with the bundled engines; AI translation still needs a network connection. For Russian, disable local OCR and configure an OCR API, or use vision-language full-page translation.
- **AI OCR supplementation** uses the API to recognize an entire problematic crop when local OCR is enabled. Choose Low, Medium, or High; turning supplementation off uses local recognition only, while Medium and High increase requests. See the cost guidance above.
- Disabling local OCR uses an online OCR API for all regions. Its URL, key, and model must be available; each blank field falls back to the corresponding main API setting.
- OCR supports only the OpenAI-compatible chat API. Choose a model that accepts images and recognizes the source language.
- **Use API OCR for manually selected bubbles** is independent of supplementation and shared by the reader and floating window. When enabled, manual regions still generate OCR API requests even if supplementation is off.
- Increase **OCR API timeout** when needed. The allowed range is 30 to 1200 seconds.

### Custom request parameters

Parameters apply to the main provider. A provider cannot have duplicate parameter names.

### AI request settings

**Pages per request** accepts 1–100; 1 disables page merging. Start with 5–15 pages. Ordinary, full-text fast, and vision-language full-page translation support merging in folder, collection, and batch tasks. Results are saved page by page; vision-language merging sends several separate images. Larger batches are not always better; see the cost and quality guidance above.

### Folder translation options

- **Vision-language full-page translation**: enable it in a folder to send an annotated full page to the main image-capable model and skip OCR. Long images are sent in sections. It cannot be enabled together with **Full-text fast translation**.
- **Full-text fast translation** completes OCR, glossary extraction, and translation in windows of up to 100 pages, accumulating terms between windows rather than processing OCR for the whole work first. Cross-page bubbles are handled in Webtoon Scroll only, not ordinary reading mode.
- **Glossary Processing**: ordinary and vision-language full-page translation can extract and update terms when enabled. When disabled, existing terms can still be used, but no new terms are saved. Full-text fast translation always enables glossary processing.

### Floating translation settings

Configure a separate API URL, key, and model for floating translation. Leave these blank to use the main API settings.

- **Vision-language full-page translation** sends an annotated image of the page's bubble and text regions to an image-capable model and skips OCR. Long images are sent in sections. Set its concurrent translation count separately.
- The library's **Pages per request** setting does not apply to floating translation, which currently does not process glossary terms. With vision-language translation off, floating translation shares the OCR settings and supplementation policy; OCR requests use the configuration in **OCR Settings**.
- **Proofreading mode** enters edit mode after every recognition pass.
- **Auto-clear bubbles** clears bubbles automatically when the page changes.
- **Floating-button gestures** let you change what single, double, long, and triple taps do.

---

## Tips

- Export a folder as CBZ, PDF, or images. The default export directory is `Documents/manga-translate`.
- Clear app cache to remove the floating-translation cache. Saved manga page translations remain available and are not automatically translated again. Use **Retranslate Selected** to replace them.
- Use **Export Manga and Settings** in Settings to create a backup, and **Import Manga and Settings** to restore it.
- Open a collection and tap **Import Chapters** to import child chapters. You can select several chapters at once; empty or duplicate folders are skipped.
