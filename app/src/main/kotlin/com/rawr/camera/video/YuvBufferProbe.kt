package com.rawr.camera.video

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Can the GPU write straight into the HEVC encoder's YUV input buffers? For
 * 4K60 P010 (10-bit) and NV12 (8-bit), with the writer's default usage and with
 * GPU_DATA_BUFFER added, it reports the buffer layout and whether Vulkan imports
 * it as a storage buffer (the recipe camera RAW uses on Adreno).
 */
internal object YuvBufferProbe {
    private data class Case(val name: String, val imageFormat: Int, val profile: Int, val extraUsage: Long)

    fun run(): JSONObject {
        val report = JSONObject()
        val cases = listOf(
            Case("p010_default", ImageFormat.YCBCR_P010, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, 0),
            Case("p010_gpu_buffer", ImageFormat.YCBCR_P010, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                HardwareBuffer.USAGE_GPU_DATA_BUFFER),
            Case("nv12_default", ImageFormat.YUV_420_888, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, 0),
            Case("nv12_gpu_buffer", ImageFormat.YUV_420_888, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                HardwareBuffer.USAGE_GPU_DATA_BUFFER)
        )
        val results = JSONArray()
        for (case in cases) results.put(runCase(case))
        report.put("cases", results)
        return report
    }

    private fun runCase(case: Case): JSONObject {
        val out = JSONObject().put("case", case.name)
        var codec: MediaCodec? = null
        var writer: ImageWriter? = null
        var surface: android.view.Surface? = null
        try {
            codec = MediaCodec.createByCodecName("c2.qti.hevc.encoder")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, case.profile)
                setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            val builder = ImageWriter.Builder(surface).setImageFormat(case.imageFormat).setMaxImages(3)
            if (case.extraUsage != 0L) {
                builder.setUsage(HardwareBuffer.USAGE_CPU_READ_RARELY or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    case.extraUsage)
            }
            writer = builder.build()
            codec.start()
            val image = writer.dequeueInputImage()
            out.put("imageFormat", image.format).put("width", image.width).put("height", image.height)
            val buffer = image.hardwareBuffer
            if (buffer != null) {
                out.put("ahbFormat", buffer.format).put("ahbUsage", buffer.usage.toString())
                val r = VideoGpuProbe.inspectHardwareBufferAsBuffer(buffer)
                out.put("allocationSize", r[0]).put("memoryTypeBits", r[1]).put("bufferImportResult", r[2])
                val planes = JSONArray()
                for (p in 0 until 3) {
                    planes.put(JSONObject().put("offset", r[3 + p * 3]).put("rowStride", r[4 + p * 3])
                        .put("pixelStride", r[5 + p * 3]))
                }
                out.put("planes", planes).put("descStride", r[12]).put("descUsage", r[13].toString())
                buffer.close()
            }
            image.close()
            out.put("success", true)
        } catch (error: Throwable) {
            out.put("success", false).put("error", error.toString())
        } finally {
            runCatching { writer?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
        }
        return out
    }
}
