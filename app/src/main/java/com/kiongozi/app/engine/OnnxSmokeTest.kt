package com.kiongozi.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
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
 * Progress is reported live (every stage), each backend has a timeout, and NNAPI is guarded
 * by a marker file so a crash/hang there is skipped on the next launch instead of looping.
 */
object OnnxSmokeTest {
    private const val ASSET = "models/beat_this.onnx"
    private const val FRAMES = 1500L
    private const val MELS = 128L
    private const val TIMEOUT_MS = 120_000L

    suspend fun run(context: Context, onProgress: (List<String>) -> Unit): ModelCheck {
        val lines = CopyOnWriteArrayList<String>()
        fun say(s: String) {
            lines += s
            onProgress(lines.toList())
        }
        try {
            say("preparing model…")
            val target = withContext(Dispatchers.Default) {
                val f = File(context.filesDir, "models/beat_this.onnx")
                f.parentFile?.mkdirs()
                val lastUpdate =
                    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                if (!f.exists() || f.lastModified() < lastUpdate) {
                    context.assets.open(ASSET).use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                f
            }
            say("model: beat_this.onnx  %.1f MB".format(target.length() / 1_000_000.0))

            val env = OrtEnvironment.getEnvironment()
            val rnd = Random(0)
            val input = FloatArray((FRAMES * MELS).toInt()) { rnd.nextFloat() * 6f }

            // ---- CPU ----
            val cpu = blocking(TIMEOUT_MS) { bench(env, target.absolutePath, Backend.CPU, input, ::say) }
                ?: BackendResult(Backend.CPU, false, error = "timed out after ${TIMEOUT_MS / 1000} s")
            say(summary(cpu))

            // ---- NNAPI (guarded) ----
            val marker = File(context.filesDir, "nnapi_test.marker")
            val nn: BackendResult
            if (marker.exists()) {
                nn = BackendResult(
                    Backend.NNAPI, false,
                    error = "skipped: a previous NNAPI attempt crashed or hung " +
                        "(clear app data / reinstall to retry)",
                )
            } else {
                marker.writeText("running")
                val r = blocking(TIMEOUT_MS) { bench(env, target.absolutePath, Backend.NNAPI, input, ::say) }
                if (r != null) marker.delete() // finished (ok or clean failure); keep marker only on hang
                nn = r ?: BackendResult(Backend.NNAPI, false, error = "timed out after ${TIMEOUT_MS / 1000} s")
            }
            say(summary(nn))

            val a = cpu.firstOutput
            val b = nn.firstOutput
            if (cpu.ok && nn.ok && a != null && b != null) {
                var d = 0f
                for (i in a.indices) d = maxOf(d, abs(a[i] - b[i]))
                say("CPU vs NNAPI beat-logit max diff: %.4f".format(d))
                if (nn.medianMs >= cpu.medianMs) {
                    say("NNAPI is not faster than CPU here (likely CPU fallback for some ops)")
                }
            }

            val fastest = listOf(cpu, nn).filter { it.ok }.minByOrNull { it.medianMs }?.backend
            say(if (fastest != null) "-> fastest: ${fastest.label}" else "-> no working backend")
            return ModelCheck(fastest != null, lines.toList(), fastest)
        } catch (t: Throwable) {
            say("FAILED: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            return ModelCheck(false, lines.toList(), null)
        }
    }

    private fun summary(r: BackendResult): String =
        if (r.ok) {
            "${r.backend.label}: session ${r.sessionMs} ms | 1st run ${r.warmupMs} ms | " +
                "30 s chunk ${r.medianMs} ms"
        } else {
            "${r.backend.label}: FAILED ${r.error}"
        }

    /** Runs blocking native work on its own thread so a hang can't freeze the UI; null = timed out. */
    private suspend fun <T> blocking(timeoutMs: Long, block: () -> T): T? {
        val d = CompletableDeferred<Result<T>>()
        Thread({ d.complete(runCatching(block)) }, "ort-bench").apply {
            isDaemon = true
            start()
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: return null
        return r.getOrThrow()
    }

    private fun options(b: Backend): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        if (b == Backend.NNAPI) {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
    }

    private fun bench(
        env: OrtEnvironment,
        path: String,
        b: Backend,
        input: FloatArray,
        say: (String) -> Unit,
    ): BackendResult {
        return try {
            say("${b.label}: creating session…")
            val t0 = SystemClock.elapsedRealtime()
            env.createSession(path, options(b)).use { s ->
                val sessionMs = SystemClock.elapsedRealtime() - t0
                say("${b.label}: session ready in $sessionMs ms")
                if (b == Backend.CPU) {
                    s.inputInfo.forEach { (name, info) ->
                        val t = info.info as? TensorInfo
                        say("in  $name ${t?.shape?.contentToString()} ${t?.type}")
                    }
                    s.outputInfo.forEach { (name, info) ->
                        val t = info.info as? TensorInfo
                        say("out $name ${t?.shape?.contentToString()} ${t?.type}")
                    }
                }
                val name = s.inputNames.first()
                val buf = ByteBuffer.allocateDirect(input.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(input)
                buf.rewind()
                OnnxTensor.createTensor(env, buf, longArrayOf(1, FRAMES, MELS)).use { tensor ->
                    var first: FloatArray? = null
                    var warmupMs = 0L
                    val times = LongArray(2)
                    for (i in 0 until 3) { // run 0 = warm-up (NNAPI compiles here)
                        say("${b.label}: run ${i + 1}/3…")
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
                    BackendResult(b, true, sessionMs, warmupMs, times[0], null, first)
                }
            }
        } catch (t: Throwable) {
            BackendResult(b, false, error = "${t.javaClass.simpleName}: ${t.message?.take(160)}")
        }
    }
}
