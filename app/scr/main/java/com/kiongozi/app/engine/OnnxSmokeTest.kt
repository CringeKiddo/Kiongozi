package com.kiongozi.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.Random
import kotlin.math.abs

enum class Backend(val label: String) {
    CPU("CPU (4 threads)"),
    NNAPI("NNAPI GPU/NPU (fp16)"),
}

data class BackendResult(
    val backend: Backend,
    val ok: Boolean,
    val sessionMs: Long = 0,
    val warmupMs: Long = 0,
    val medianMs: Long = 0,
    val error: String? = null,
    val firstOutput: FloatArray? = null,
)

data class ModelCheck(
    val ok: Boolean,
    val lines: List<String>,
    /** Fastest backend that produced valid output; Batch 2 will use this one. */
    val fastest: Backend?,
)

/**
 * Proves, on the actual phone, that:
 *  1) the .onnx model made it into the APK,
 *  2) ONNX Runtime can load it on CPU and on NNAPI (GPU/NPU/DSP via the vendor driver),
 *  3) one 30 s chunk runs through each backend, with timing and a CPU-vs-NNAPI output diff.
 *
 * NOTE: ORT does not report how many graph nodes NNAPI really took. Unsupported ops silently
 * fall back to CPU. The timing comparison is the honest signal of whether the GPU helps.
 */
object OnnxSmokeTest {
    private const val ASSET = "models/beat_this.onnx"
    private const val FRAMES = 1500L
    private const val MELS = 128L

    suspend fun run(context: Context): ModelCheck = withContext(Dispatchers.Default) {
        val lines = mutableListOf<String>()
        try {
            // Copy asset -> filesDir once per install/update (ORT loads from a real path)
            val target = File(context.filesDir, "models/beat_this.onnx")
            target.parentFile?.mkdirs()
            val lastUpdate = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!target.exists() || target.lastModified() < lastUpdate) {
                context.assets.open(ASSET).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            lines += "model: beat_this.onnx  %.1f MB".format(target.length() / 1_000_000.0)

            val env = OrtEnvironment.getEnvironment()

            // Print I/O info once (via a plain CPU session)
            env.createSession(target.absolutePath, OrtSession.SessionOptions()).use { s ->
                s.inputInfo.forEach { (name, info) ->
                    val t = info.info as? TensorInfo
                    lines += "in  $name ${t?.shape?.contentToString()} ${t?.type}"
                }
                s.outputInfo.forEach { (name, info) ->
                    val t = info.info as? TensorInfo
                    lines += "out $name ${t?.shape?.contentToString()} ${t?.type}"
                }
            }

            // Deterministic fake log-mel input in [0, 6)
            val rnd = Random(0)
            val input = FloatArray((FRAMES * MELS).toInt()) { rnd.nextFloat() * 6f }

            val results = Backend.values().map { bench(env, target.absolutePath, it, input) }
            results.forEach { r ->
                lines += if (r.ok) {
                    "${r.backend.label}: session ${r.sessionMs} ms | 1st run ${r.warmupMs} ms | " +
                        "30 s chunk ${r.medianMs} ms"
                } else {
                    "${r.backend.label}: FAILED ${r.error}"
                }
            }

            val cpu = results.first { it.backend == Backend.CPU }
            val nn = results.first { it.backend == Backend.NNAPI }
            if (cpu.ok && nn.ok && cpu.firstOutput != null && nn.firstOutput != null) {
                var d = 0f
                for (i in cpu.firstOutput.indices) d = maxOf(d, abs(cpu.firstOutput[i] - nn.firstOutput[i]))
                lines += "CPU vs NNAPI beat-logit max diff: %.4f".format(d)
                if (nn.medianMs >= cpu.medianMs) {
                    lines += "NNAPI is not faster than CPU here (likely CPU fallback for some ops)"
                }
            }

            val fastest = results.filter { it.ok }.minByOrNull { it.medianMs }?.backend
            lines += if (fastest != null) "-> fastest: ${fastest.label}" else "-> no working backend"
            ModelCheck(fastest != null, lines, fastest)
        } catch (t: Throwable) {
            lines += "FAILED: ${t.javaClass.simpleName}: ${t.message?.take(200)}"
            ModelCheck(false, lines, null)
        }
    }

    private fun options(b: Backend): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        if (b == Backend.NNAPI) {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
    }

    private fun bench(env: OrtEnvironment, path: String, b: Backend, input: FloatArray): BackendResult {
        return try {
            val t0 = SystemClock.elapsedRealtime()
            env.createSession(path, options(b)).use { s ->
                val sessionMs = SystemClock.elapsedRealtime() - t0
                val name = s.inputNames.first()
                val buf = ByteBuffer.allocateDirect(input.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(input)
                buf.rewind()
                OnnxTensor.createTensor(env, buf, longArrayOf(1, FRAMES, MELS)).use { tensor ->
                    var first: FloatArray? = null
                    var warmupMs = 0L
                    val times = LongArray(3)
                    for (i in 0 until 4) { // run 0 = warm-up (NNAPI compiles here)
                        val t1 = SystemClock.elapsedRealtime()
                        s.run(mapOf(name to tensor)).use { r ->
                            val ms = SystemClock.elapsedRealtime() - t1
                            if (i == 0) {
                                warmupMs = ms
                                val fb = (r[0] as OnnxTensor).floatBuffer
                                first = FloatArray(fb.remaining()).also { fb.get(it) }
                            } else {
                                times[i - 1] = ms
                            }
                        }
                    }
                    times.sort()
                    BackendResult(b, true, sessionMs, warmupMs, times[1], null, first)
                }
            }
        } catch (t: Throwable) {
            BackendResult(b, false, error = "${t.javaClass.simpleName}: ${t.message?.take(160)}")
        }
    }
}
