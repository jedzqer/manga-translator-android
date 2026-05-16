# MiniCPM-V 4.6 端侧部署与架构演进计划 (Android)

## 1. 架构选型与分析

### 1.1 推理框架对比分析 (vLLM vs SGLang vs Ollama vs llama.cpp)
在官方支持的四个框架中，针对 **Android 端侧 (On-Device)** 场景，结论是唯一且明确的：
*   **vLLM & SGLang**：专为服务端 GPU 集群设计（依赖 CUDA/Triton 等），Python 生态重，**绝对无法**在 Android 端侧运行。
*   **Ollama**：基于 Go 和 llama.cpp 封装，适合桌面端一键部署。若强行移植 Android，需要以后台 Daemon 进程运行，存在严重的进程保活、通信开销和体积冗余问题，**不推荐**。
*   **llama.cpp (The Winner)**：纯 C/C++ 编写，无外部依赖，天然支持跨平台编译。通过 NDK (JNI) 可直接嵌入 Android App 的进程空间中，内存零拷贝，支持 ARM NEON 和 Vulkan 后端加速。**性价比与可行性最高，是端侧部署的行业标准。**

### 1.2 量化精度 (Quantization) 策略
MiniCPM-V 4.6 参数量为 1.3B，全精度 (FP16) 约占用 2.6GB 内存。为了在端侧兼顾速度、内存与效果：
*   **推荐默认量化：`Q4_K_M` (约 800MB)**。在绝大多数中低端手机（4GB+ RAM）上均可流畅运行，且视觉与文本翻译能力衰减极小。
*   **高质量选项：`Q8_0` (约 1.4GB)**。针对旗舰机型（8GB+ RAM），基本达到无损画质。
*   **自定义选项**：不在代码中硬编码量化版本，而是通过“本地模型导入”功能，让用户自行选择下载的 `.gguf` 和视觉投影文件 (`mmproj.gguf`)，将精度选择权完全交还给用户。

### 1.3 体验升级与功能扩展 (Beyond Manga Translation)
既然引入了强大的端侧多模态模型，只做“漫画翻译”大材小用。可以扩展以下功能：
1.  **通用视觉问答 (General Vision QA)**：在悬浮球菜单中增加“分析屏幕/图片”选项，不强制要求输出 JSON，而是让模型直接描述图片内容、提取网页文本或解释当前屏幕的 UI。
2.  **流式反馈 (Streaming Output)**：长文本翻译不再等待整页完成，而是采用打字机效果，提升用户心理预期。

---

## 2. 实施 TODO 清单 (Phase 1 - 5)

### Phase 1: 基础设施与项目管理
- [x] 检查并关联用户的 Fork 远程仓库 (`https://github.com/222222222l/manga-translator-android`)。
- [x] 创建功能分支 `feature/minicpm-v-integration`。
- [x] 清理无用依赖：移除原有的臃肿 OCR 库和模型 (将随管线重构同步进行)。
- [x] 配置 GitHub Actions 云端构建 Debug APK（安装 Android SDK/NDK/CMake，并提前切换到 Node.js 24 运行时）。

### Phase 2: C++ 引擎层接入 (llama.cpp)
- [x] 在 `app/src/main/cpp` 中引入 `llama.cpp` 源码（包含 `llava` 多模态扩展支持）。
- [x] 配置 `CMakeLists.txt`，启用 Android NDK 编译，开启 NEON 优化，视情况开启 Vulkan 支持。
- [x] 编写 JNI 接口 `minicpm_jni.cpp`，暴露 `init_model`, `process_image`, `generate_text`, `clear_kv_cache` 等方法。

### Phase 3: Android 数据与设置层
- [x] 增加 `VlmModelManager`：管理内部存储中的 `.gguf` 文件。
- [x] 改造 `SettingsFragment`：
  - 新增 `MiniCPM-V 端侧模型` 设置大类。
  - 提供“导入语言模型 (LLM)”和“导入视觉映射模型 (mmproj)”的按钮。
  - 提供 CPU 线程数 (Threads) 自定义选项。

### Phase 4: 核心翻译管线重构 (Translation Pipeline)
- [ ] 编写 `LocalVlmClient.kt`，封装 JNI 调用，替代原有的 OkHttp 远程调用逻辑。
- [ ] 重写 `TranslationPipeline.kt`：
  - 构造系统 Prompt：强制模型以 JSON 格式输出 `[{"box": [x,y,w,h], "text": "译文"}]`。
  - 传入图片和 Prompt，获取 VLM 结果。
  - 编写高鲁棒性的 JSON 提取器（防幻觉正则解析）。
- [ ] 坐标对齐与渲染：将 VLM 相对坐标转换为原始图像像素坐标，送入 `BubbleRenderer`。

### Phase 5: UX 体验增强
- [ ] 增加流式解析回调：JNI 边生成 token 边解析，气泡逐个渲染。
- [ ] 在悬浮球中增加“通用分析”模式，复用 `LocalVlmClient` 进行非格式化的自由对话。

---
*文档生成于：2026-05-16*
