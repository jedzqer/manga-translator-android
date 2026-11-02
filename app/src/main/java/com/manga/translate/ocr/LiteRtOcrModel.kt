package com.manga.translate.ocr

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.manga.translate.detection.ModelExecutionThread
import com.manga.translate.platform.AppLogger

/** Lazy session; all native resources retain GPU thread affinity, including CPU fallback. */
internal class LiteRtOcrModel(
    context: Context,
    private val asset: String,
    private val inputShape: List<Int>,
    private val outputShape: List<Int>
) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = ModelExecutionThread()
    private var session: Session? = null
    private var cpuOnly = false
    private var closed = false

    @Synchronized
    fun run(input: FloatArray): FloatArray {
        check(!closed) { "OCR model is closed" }
        require(input.size == inputShape.reduce(Int::times))
        return worker.call {
            try {
                runSession(input)
            } catch (failure: Exception) {
                fallback(input, failure)
            } catch (failure: LinkageError) {
                fallback(input, failure)
            }
        }
    }

    private fun runSession(input: FloatArray): FloatArray {
        val current = session ?: Session(context, asset, cpuOnly, inputShape, outputShape).also { session = it }
        return current.run(input).also {
            require(it.size == outputShape.reduce(Int::times) && it.all(Float::isFinite)) {
                "Unexpected OCR output size or non-finite values"
            }
        }
    }

    private fun fallback(input: FloatArray, failure: Throwable): FloatArray {
        if (cpuOnly) throw failure
        cpuOnly = true
        AppLogger.log("LiteRtOcr", "$asset GPU failed; retrying on CPU", failure)
        val previous = session
        session = null
        previous?.close()
        return runSession(input)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            worker.call { session?.close(); session = null }
        } finally {
            worker.close()
        }
    }

    private class Session(
        context: Context, asset: String, cpu: Boolean,
        inputShape: List<Int>, outputShape: List<Int>
    ) : AutoCloseable {
        private var model: CompiledModel? = null
        private var inputs: List<TensorBuffer> = emptyList()
        private var outputs: List<TensorBuffer> = emptyList()
        init {
            try {
                val options = CompiledModel.Options(if (cpu) Accelerator.CPU else Accelerator.GPU)
                if (!cpu) options.gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32
                )
                val compiled = CompiledModel.create(context.assets, asset, options)
                model = compiled
                val inputType = compiled.getInputTensorType("args_0")
                val outputType = compiled.getOutputTensorType("output_0")
                require(inputType.elementType == TensorType.ElementType.FLOAT &&
                    inputType.layout?.dimensions == inputShape) { "Unexpected OCR input tensor" }
                require(outputType.elementType == TensorType.ElementType.FLOAT &&
                    outputType.layout?.dimensions == outputShape) { "Unexpected OCR output tensor" }
                inputs = compiled.createInputBuffers()
                outputs = compiled.createOutputBuffers()
                require(inputs.size == 1 && outputs.size == 1)
                AppLogger.log("LiteRtOcr", "$asset backend=${if (cpu) "CPU" else "GPU FP32"}")
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
        fun run(input: FloatArray): FloatArray {
            inputs.single().writeFloat(input)
            checkNotNull(model).run(inputs, outputs)
            return outputs.single().readFloat()
        }
        override fun close() {
            try {
                try { inputs.forEach { it.close() } }
                finally { outputs.forEach { it.close() } }
            } finally { model?.close() }
        }
    }
}
