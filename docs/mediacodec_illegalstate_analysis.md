# MediaCodec `IllegalStateException` 报错分析

日志核心：

- 异常类型：`java.lang.IllegalStateException`
- 抛出点：`android.media.MediaCodec.native_dequeueOutputBuffer`
- 业务调用点：`VideoProcessingManager.kt` 的 `drainEncoder`（约第 284 行）

## 结论（高概率根因）

`dequeueOutputBuffer()` 在 **错误的 Codec 状态** 被调用。`MediaCodec` 状态机比较严格，只要发生以下任一情况，就会在 native 层直接抛 `IllegalStateException`：

1. 编码器还没 `start()` 就开始 `dequeueOutputBuffer()`。
2. 编码器已经 `stop()` / `release()` 后仍继续 drain。
3. 协程并发导致一个协程释放编码器，另一个协程还在取输出。
4. 上游输入 EOS/异常后，drain 循环未正确退出，继续访问失效 codec。
5. 复用同一个 `MediaCodec` 实例处理多段任务，状态未完全重置。

从堆栈看，异常发生在 `withContext` 的协程流程中，说明**并发/时序问题**概率很高（例如取消任务时资源被提前释放）。

## 为什么日志里显示“处理失败: null”

`IllegalStateException` 默认 message 常为空，因此上层如果只打印 `e.message`，会看到 `null`。这不代表没有异常，真实信息在堆栈中。

## 建议排查顺序

1. 在编码器生命周期关键点打印日志（带 sessionId + codec hash）：
   - `configure`
   - `start`
   - 首次 `drain`
   - `signalEndOfInputStream` / 输入 EOS
   - `stop`
   - `release`
2. 检查是否有多个协程同时访问同一 `MediaCodec`。
3. 检查取消逻辑：`Job.cancel` 后是否仍在 drain 循环中调用 `dequeueOutputBuffer`。
4. 确认 `release()` 只执行一次，且一定在 drain 彻底结束后。
5. 若使用 `MediaMuxer`，确认轨道添加与写入顺序正确，避免异常回流触发 codec 提前关闭。

## 工程修复建议

- 使用显式状态位保护：`isCodecStarted`、`isCodecReleased`（原子变量）。
- 所有 `dequeueOutputBuffer()` 前先检查状态；无效状态立即 break。
- 在协程取消时统一走 `finally`，先停止循环再释放资源。
- 资源收口：将 `stop/release` 放在单一线程、单一函数中，避免重复调用。
- 日志改为打印 `e::class.java.name` + 完整 stacktrace，而非仅 `message`。

## 可快速验证的假设

如果把 drain 逻辑串行化（单协程单线程）后问题消失，基本可确认是并发释放/越序调用导致的状态机错误。
