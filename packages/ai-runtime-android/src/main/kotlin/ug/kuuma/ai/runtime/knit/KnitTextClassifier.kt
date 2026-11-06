package ug.kuuma.ai.runtime.knit

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** One instance belongs to one serial worker. Buffers are reused and it must be closed there. */
internal class KnitTextClassifier(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val tokenizer by lazy(LazyThreadSafetyMode.NONE) {
        assets.open(TOKENIZER).bufferedReader().use { KnitUnigramTokenizer(it.readText()) }
    }
    private var runtime: ModelBuffers? = null
    private var closed = false

    fun classify(text: String): KnitRawResult {
        check(!closed)
        if (text.length > 640) return tooLong()
        if (text.isBlank()) return KnitRawResult("UNCERTAIN", "", "INVALID")
        val encoded = tokenizer.encode(text) ?: return tooLong()

        val model = runtime ?: openInterpreter().also { runtime = it }
        for (index in model.inputs.indices) {
            val values = if (model.inputIsMask[index]) encoded.mask else encoded.ids
            val buffer = model.inputs[index] as ByteBuffer
            buffer.clear()
            for (value in values) {
                if (model.longInputs[index]) buffer.putLong(value.toLong()) else buffer.putInt(value)
            }
            buffer.rewind()
        }
        // A partial/failed output must never reuse a previous message's scores.
        model.output[0].fill(Float.NaN)
        model.interpreter.runForMultipleInputsOutputs(model.inputs, model.outputs)

        val scores = model.output[0]
        if (scores.any { !it.isFinite() || it !in 0f..1f }) {
            return KnitRawResult("UNCERTAIN", "", "INVALID")
        }
        val threat = scores[THREAT_INDEX]
        val insult = scores[INSULT_INDEX]
        val label = if (maxOf(threat, insult) >= 0.50f) "NEEDS_REVIEW" else "NO_FLAG"
        val trace = String.format(Locale.ROOT, "Threat score: %.4f, insult score: %.4f", threat, insult)
        return KnitRawResult(label, trace, "VALID", threat.toDouble(), insult.toDouble())
    }

    private fun openInterpreter(): ModelBuffers {
        val labels = assets.open(LABEL_FILE).bufferedReader().useLines { it.toList() }
        check(labels == LABELS) { "Unexpected Knit classifier label order." }
        val model = assets.openFd(MODEL).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
        val runtime = Interpreter(model, Interpreter.Options().setNumThreads(2))
        try {
            check(runtime.inputTensorCount == 2 && runtime.outputTensorCount == 1)
            for (index in 0..1) {
                val input = runtime.getInputTensor(index)
                check(input.shape().contentEquals(intArrayOf(1, KnitUnigramTokenizer.INPUT_LENGTH)))
                check(input.dataType() == DataType.INT32 || input.dataType() == DataType.INT64)
            }
            val output = runtime.getOutputTensor(0)
            check(output.shape().contentEquals(intArrayOf(1, LABELS.size)))
            check(output.dataType() == DataType.FLOAT32)
            val firstIsMask = runtime.getInputTensor(0).name().contains("mask", ignoreCase = true)
            return ModelBuffers(
                runtime,
                BooleanArray(2) { (it == 0) == firstIsMask },
                BooleanArray(2) { runtime.getInputTensor(it).dataType() == DataType.INT64 }
            )
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }

    override fun close() {
        closed = true
        val model = runtime
        runtime = null
        model?.interpreter?.close()
    }

    private class ModelBuffers(
        val interpreter: Interpreter,
        val inputIsMask: BooleanArray,
        val longInputs: BooleanArray
    ) {
        val inputs = Array<Any>(2) { index ->
            ByteBuffer.allocateDirect(KnitUnigramTokenizer.INPUT_LENGTH * if (longInputs[index]) 8 else 4)
                .order(ByteOrder.nativeOrder())
        }
        val output = Array(1) { FloatArray(LABELS.size) }
        val outputs = mapOf<Int, Any>(0 to output)
    }

    companion object {
        private const val MODEL = "knit/toxicity.tflite"
        private const val TOKENIZER = "knit/tokenizer.json"
        private const val LABEL_FILE = "knit/labels.txt"
        private const val INSULT_INDEX = 4
        private const val THREAT_INDEX = 5
        private val LABELS = listOf(
            "toxicity", "severe_toxicity", "obscene", "identity_attack", "insult", "threat", "sexual_explicit",
            "male", "female", "homosexual_gay_or_lesbian", "christian", "jewish", "muslim", "black", "white",
            "psychiatric_or_mental_illness"
        )

        /** Readiness here means the bundled files exist. The worker validates them on first use. */
        fun isReady(context: Context): Boolean = try {
            val names = context.assets.list("knit").orEmpty()
            arrayOf(MODEL, TOKENIZER, LABEL_FILE).all { it.substringAfter('/') in names }
        } catch (_: java.io.IOException) {
            false
        }

        private fun tooLong() = KnitRawResult("UNCERTAIN", "", "TOO_LONG")
    }
}

internal data class KnitRawResult(
    val label: String,
    val rawOutput: String,
    val outputStatus: String,
    val threat: Double? = null,
    val insult: Double? = null
)
