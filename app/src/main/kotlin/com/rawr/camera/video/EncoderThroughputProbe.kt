package com.rawr.camera.video

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * How fast can the HEVC encoder take 4K frames, by input format? No camera and
 * no GPU work: an ImageWriter feeds the encoder Surface as fast as it frees
 * buffers (frames are written once and reused), timestamps on a 60 fps grid.
 * YUV (NV12/P010) needs no conversion; RGBA (what Rawr feeds today) is
 * converted to YUV by the platform before encoding.
 */
internal object EncoderThroughputProbe {
    private data class Case(val name: String, val format: Int, val profile: Int)

    private const val FRAMES = 240
    private const val WIDTH = 3840
    private const val HEIGHT = 2160

    fun run(): JSONObject {
        val cases = listOf(
            Case("nv12", ImageFormat.YUV_420_888, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
            Case("p010", ImageFormat.YCBCR_P010, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
            Case("rgba8", PixelFormat.RGBA_8888, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
            Case("rgba1010102", HardwareBuffer.RGBA_1010102, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        )
        val out = JSONArray()
        for (content in listOf("static", "noise")) for (case in cases) out.put(runCase(case, content))
        return JSONObject().put("width", WIDTH).put("height", HEIGHT).put("frames", FRAMES).put("cases", out)
    }

    private fun fillNoise(image: android.media.Image, seed: Int) {
        var x = 0x9E3779B9.toInt() xor (seed * 0x85EBCA6B.toInt())
        for (plane in image.planes) {
            val buf = plane.buffer
            buf.rewind()
            val words = buf.remaining() / 4
            val ints = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            for (k in 0 until words) {
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                ints.put(k, x)
            }
        }
    }

    private fun runCase(case: Case, content: String): JSONObject {
        val result = JSONObject().put("case", case.name).put("content", content)
        var codec: MediaCodec? = null
        var writer: ImageWriter? = null
        var surface: android.view.Surface? = null
        val encoded = AtomicInteger(0)
        var drain: Thread? = null
        try {
            codec = MediaCodec.createByCodecName("c2.qti.hevc.encoder")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, case.profile)
                setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            writer = ImageWriter.Builder(surface).setImageFormat(case.format).setMaxImages(4).build()
            codec.start()
            val c = codec
            drain = Thread {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val index = runCatching { c.dequeueOutputBuffer(info, 20_000) }.getOrElse { break }
                    if (index >= 0) {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                            encoded.incrementAndGet()
                        c.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }.apply { start() }
            val frameNs = 1_000_000_000L / 60
            val begin = SystemClock.elapsedRealtimeNanos()
            var maxDequeueMs = 0.0
            var fillNs = 0L
            for (i in 0 until FRAMES) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val image = writer.dequeueInputImage()
                val waitMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                // Fill each pooled buffer once with different noise: the encoder then
                // sees a fully changing, high-entropy frame every time (worst case,
                // unlike a static frame that motion search skips).
                if (content == "noise" && i < 4) {
                    val f0 = SystemClock.elapsedRealtimeNanos()
                    fillNoise(image, i)
                    fillNs += SystemClock.elapsedRealtimeNanos() - f0
                }
                if (i > 8 && waitMs > maxDequeueMs) maxDequeueMs = waitMs
                image.timestamp = begin + i * frameNs
                writer.queueInputImage(image)
            }
            val feedMs = (SystemClock.elapsedRealtimeNanos() - begin - fillNs) / 1e6
            codec.signalEndOfInputStream()
            drain.join(5_000)
            val totalMs = (SystemClock.elapsedRealtimeNanos() - begin - fillNs) / 1e6
            result.put("success", true)
                .put("encodedFrames", encoded.get())
                .put("feedMs", "%.0f".format(feedMs))
                .put("totalMs", "%.0f".format(totalMs))
                .put("sustainedFps", "%.1f".format(encoded.get() * 1000.0 / totalMs))
                .put("feedFps", "%.1f".format(FRAMES * 1000.0 / feedMs))
                .put("maxDequeueWaitMs", "%.1f".format(maxDequeueMs))
        } catch (error: Throwable) {
            result.put("success", false).put("error", error.toString())
        } finally {
            runCatching { drain?.interrupt() }
            runCatching { writer?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
        }
        return result
    }
}
