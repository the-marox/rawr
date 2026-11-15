package com.rawr.camera.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import java.io.File
import java.util.concurrent.Executor

/**
 * Debug-only Camera2 (Java API) probe for the Xiaomi 17 Ultra 50 MP RAW mode.
 *
 * The NDK can't set an output's stream use case; Unspektrawesome gets real 8192x6144 RAW with
 * stream use case 0x80103 on the RAW output. This opens the camera the same way and logs how much
 * of each RAW frame the HAL actually filled, so use case 0 and 0x80103 can be compared directly.
 *
 *   adb shell am start -n x17u.rawr.camera.debug/com.rawr.camera.probe.Xiaomi50mpProbeActivity --el useCase 524547
 *   adb shell am start -n x17u.rawr.camera.debug/com.rawr.camera.probe.Xiaomi50mpProbeActivity --el useCase 0
 *   adb logcat -s Rawr50Probe
 *
 * Extras (all optional): cameraId (String, "2"), width/height (Int, 8192/6144), useCase (Long, 0x80103),
 * yuv (Boolean, true: add a 640x480 YUV like Unspektrawesome), yuvUseCase (Long, 0), frames (Int, 45),
 * sessionKeys (Boolean, true), timeoutMs (Int, 8000: give up when no frame arrives).
 * Stock-camera impersonation (200 MP experiments): opMode (Int, 0 = regular; vendor modes >= 0x8000,
 * stock uses 36868/36878), clientName (String, e.g. "com.android.camera"), appModule (Int, stock pro = 167),
 * superRemosaic (Boolean), thirdPartyCalled (Int, -1 = unset), rawMaxImages (Int, 0 = default).
 * Companion YUV: yuvWidth/yuvHeight (Int, 640/480), yuvUsage (Long, 0; 256 = GPU texture, which the
 * Xiaomi MiHAL needs to find a "preview" stream in vendor operation modes). Seamless with binnedWidth 0:
 * stock layout without a binned RAW (YUV preview repeating, full-size RAW only on the stills).
 * Results also go to files/xiaomi50mp_probe.txt.
 */
class Xiaomi50mpProbeActivity : Activity() {
    private val thread = HandlerThread("Rawr50Probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val report = StringBuilder()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var framesSeen = 0
    private var finished = false

    private val cameraId by lazy { intent.getStringExtra("cameraId") ?: "2" }
    private val width by lazy { intent.getIntExtra("width", 8192) }
    private val height by lazy { intent.getIntExtra("height", 6144) }
    private val useCase by lazy { intent.getLongExtra("useCase", 0x80103L) }
    private val withYuv by lazy { intent.getBooleanExtra("yuv", true) }
    private val yuvUseCase by lazy { intent.getLongExtra("yuvUseCase", 0L) }
    private val frameLimit by lazy { intent.getIntExtra("frames", 45) }
    private val sessionKeys by lazy { intent.getBooleanExtra("sessionKeys", true) }
    private val timeoutMs by lazy { intent.getIntExtra("timeoutMs", 8000) }
    // Seamless mode (stock-camera style): binned RAW + YUV stream continuously, the 8192x6144 RAW only
    // receives a few still requests that carry the 50 MP keys. stillKeys: put the keys on the stills only.
    private val seamless by lazy { intent.getBooleanExtra("seamless", false) }
    private val binnedWidth by lazy { intent.getIntExtra("binnedWidth", 4096) }
    private val binnedHeight by lazy { intent.getIntExtra("binnedHeight", 3072) }
    private val stills by lazy { intent.getIntExtra("stills", 3) }
    // Per-lens keys: main 50 MP = mode 0 + remosaic; tele 50 MP (Unspektrawesome) = mode 1, no remosaic, 24 fps.
    private val sensorMode by lazy { intent.getIntExtra("mode", 0) }
    private val remosaic by lazy { intent.getBooleanExtra("remosaic", true) }
    private val fps by lazy { intent.getIntExtra("fps", 30) }
    // Seamless stills: also target the binned RAW (Rawr's default for use-case-0 capture routes).
    private val stillBinned by lazy { intent.getBooleanExtra("stillBinned", false) }
    private val opMode by lazy { intent.getIntExtra("opMode", 0) }
    private val clientName by lazy { intent.getStringExtra("clientName") }
    private val appModule by lazy { intent.getIntExtra("appModule", -1) }
    private val superRemosaic by lazy { intent.getBooleanExtra("superRemosaic", false) }
    private val thirdPartyCalled by lazy { intent.getIntExtra("thirdPartyCalled", -1) }
    private val yuvWidth by lazy { intent.getIntExtra("yuvWidth", 640) }
    private val yuvHeight by lazy { intent.getIntExtra("yuvHeight", 480) }
    private val yuvUsage by lazy { intent.getLongExtra("yuvUsage", 0L) }
    // 35 = YUV_420_888, 34 = PRIVATE (what a SurfaceTexture preview looks like to the HAL).
    private val yuvFormat by lazy { intent.getIntExtra("yuvFormat", ImageFormat.YUV_420_888) }
    private var yuvFrames = 0
    // Seamless viewfinder fps range. The still reuses it: aeTargetFpsRange is a session key, so a
    // different range on the still makes cameraserver reconfigure, which the 200 MP MiHAL session
    // can't do in time ("Can't idle device in 5 seconds").
    private val pvFpsMin by lazy { intent.getIntExtra("pvFpsMin", 30) }
    private val pvFpsMax by lazy { intent.getIntExtra("pvFpsMax", 30) }
    // Full-size reader format (32 = RAW_SENSOR, 37 = RAW10, 38 = RAW12) and an optional byte dump of
    // the first full frame (dumpMb MB from the start plus 2 MB around the filled/empty boundary).
    private val rawFormat by lazy { intent.getIntExtra("rawFormat", ImageFormat.RAW_SENSOR) }
    // Binned RAW stream format/use case, and generic vendor keys for session + every request:
    // skeys = "name=i:1,name2=b:1,name3=l:5" (i = int32, b = byte, l = int64).
    private val binnedFormat by lazy { intent.getIntExtra("binnedFormat", ImageFormat.RAW_SENSOR) }
    private val binnedUseCase by lazy { intent.getLongExtra("binnedUseCase", 0L) }
    private val skeys by lazy { intent.getStringExtra("skeys").orEmpty() }
    private val dumpMb by lazy { intent.getIntExtra("dumpMb", 0) }
    private val stillFpsMatch by lazy { intent.getBooleanExtra("stillFpsMatch", true) }
    private val rawMaxImages by lazy { intent.getIntExtra("rawMaxImages", 0) }
    private val sessionType get() = if (opMode != 0) opMode else SessionConfiguration.SESSION_REGULAR
    private var binnedReader: ImageReader? = null
    private var binnedFrames = 0
    private var stillsSent = 0
    private var stillRequest: CaptureRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            handler.post { open() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) handler.post { open() }
        else finishProbe("camera permission denied")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        report.append(line).append('\n')
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        log("PROBE50_START yuvSize=${yuvWidth}x$yuvHeight yuvUsage=$yuvUsage yuvFormat=$yuvFormat binnedFormat=$binnedFormat binnedUseCase=$binnedUseCase skeys=$skeys rawFormat=$rawFormat dumpMb=$dumpMb pvFps=$pvFpsMin-$pvFpsMax stillFpsMatch=$stillFpsMatch binned=${binnedWidth}x$binnedHeight seamless=$seamless mode=$sensorMode remosaic=$remosaic fps=$fps stillBinned=$stillBinned camera=$cameraId size=${width}x$height useCase=0x${useCase.toString(16)} yuv=$withYuv yuvUseCase=0x${yuvUseCase.toString(16)} sessionKeys=$sessionKeys opMode=$opMode client=$clientName appModule=$appModule superRemosaic=$superRemosaic thirdPartyCalled=$thirdPartyCalled rawMaxImages=$rawMaxImages")
        val manager = getSystemService(CameraManager::class.java)
        try {
            manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure(camera)
                }
                override fun onDisconnected(camera: CameraDevice) = finishProbe("disconnected")
                override fun onError(camera: CameraDevice, error: Int) = finishProbe("device error $error")
            })
        } catch (e: Exception) {
            finishProbe("openCamera failed: $e")
        }
    }

    private fun <T> key(name: String, type: Class<T>) = CaptureRequest.Key(name, type)

    private fun applyXiaomiKeys(builder: CaptureRequest.Builder) {
        // Same types as the working Unspektrawesome profile: I32_ARRAY and U8_ARRAY.
        builder.set(key("org.codeaurora.qcamera3.sensor_meta_data.current_mode", IntArray::class.java), intArrayOf(sensorMode))
        if (remosaic) builder.set(key("xiaomi.remosaic.enabled", ByteArray::class.java), byteArrayOf(1))
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        applyStockKeys(builder)
    }

    private fun newYuvReader(): ImageReader =
        if (yuvUsage != 0L) ImageReader.newInstance(yuvWidth, yuvHeight, yuvFormat, 4, yuvUsage)
        else ImageReader.newInstance(yuvWidth, yuvHeight, yuvFormat, 4)

    private fun applyGenericKeys(builder: CaptureRequest.Builder) {
        for (entry in skeys.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val name = entry.substringBefore('=')
            val typed = entry.substringAfter('=')
            val value = typed.substringAfter(':')
            try {
                when (typed.substringBefore(':')) {
                    "i" -> builder.set(key(name, IntArray::class.java), intArrayOf(value.toInt()))
                    "b" -> builder.set(key(name, ByteArray::class.java), byteArrayOf(value.toInt().toByte()))
                    "l" -> builder.set(key(name, LongArray::class.java), longArrayOf(value.toLong()))
                    else -> log("PROBE50_SKEY_BAD $entry")
                }
            } catch (e: Exception) {
                log("PROBE50_SKEY_FAILED $entry $e")
            }
        }
    }

    private fun applyStockKeys(builder: CaptureRequest.Builder) {
        applyGenericKeys(builder)
        clientName?.let {
            builder.set(key("com.xiaomi.sessionparams.clientName", ByteArray::class.java), it.toByteArray() + 0.toByte())
        }
        if (appModule >= 0) builder.set(key("xiaomi.app.module", IntArray::class.java), intArrayOf(appModule))
        if (superRemosaic) builder.set(key("com.xiaomi.control.qcfa.isSuperRemosaic", ByteArray::class.java), byteArrayOf(1))
        if (thirdPartyCalled >= 0) {
            builder.set(key("com.xiaomi.camera.thirdPartyCalled", ByteArray::class.java), byteArrayOf(thirdPartyCalled.toByte()))
        }
    }

    private fun configure(camera: CameraDevice) {
        if (seamless) {
            configureSeamless(camera)
            return
        }
        try {
            // CPU_READ_OFTEN | GRALLOC_USAGE_RENDERSCRIPT (1 << 20): cameraserver then skips the size check.
            val raw = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, if (rawMaxImages > 0) rawMaxImages else 6, 0x100003L)
            raw.setOnImageAvailableListener({ reader -> onRaw(reader) }, handler)
            rawReader = raw
            val outputs = mutableListOf(OutputConfiguration(raw.surface).apply {
                if (useCase != 0L) streamUseCase = useCase
            })
            val targets = mutableListOf(raw.surface)
            if (withYuv) {
                val yuv = newYuvReader()
                yuv.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                yuvReader = yuv
                outputs += OutputConfiguration(yuv.surface).apply {
                    if (yuvUseCase != 0L) streamUseCase = yuvUseCase
                }
                targets += yuv.surface
            }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyXiaomiKeys(request)
            targets.forEach(request::addTarget)
            val config = SessionConfiguration(sessionType, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        log("PROBE50_CONFIGURED")
                        handler.postDelayed({ if (framesSeen < frameLimit) finishProbe("timeout") }, timeoutMs.toLong())
                        try {
                            s.setRepeatingRequest(request.build(), null, handler)
                        } catch (e: Exception) {
                            finishProbe("setRepeatingRequest failed: $e")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) = finishProbe("configure failed")
                })
            if (sessionKeys || skeys.isNotEmpty()) {
                val params = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (sessionKeys) applyXiaomiKeys(params) else applyGenericKeys(params)
                config.sessionParameters = params.build()
            }
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            finishProbe("configure threw: $e")
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureSeamless(camera: CameraDevice) {
        try {
            val full = ImageReader.newInstance(width, height, rawFormat, if (rawMaxImages > 0) rawMaxImages else 4, 0x100003L)
            full.setOnImageAvailableListener({ reader -> onRaw(reader) }, handler)
            rawReader = full
            // binnedWidth 0: no binned RAW, the YUV is the only repeating output (stock layout).
            val binned = if (binnedWidth > 0) ImageReader.newInstance(binnedWidth, binnedHeight, binnedFormat, 4).also {
                it.setOnImageAvailableListener({ reader -> onBinned(reader) }, handler)
                binnedReader = it
            } else null
            // yuv=false: no companion (the 200 MP tele HAL crashes configuring it in the regular mode).
            val yuv = if (withYuv || binned == null) newYuvReader().also {
                it.setOnImageAvailableListener({ r -> onYuv(r, binned == null) }, handler)
                yuvReader = it
            } else null
            // The YUV goes first: in vendor operation modes MiHAL only takes stream index 0 as the
            // preview (PRIVATE format + GPU/composer usage or use case PREVIEW), see getFwkStreams.
            val outputs = listOfNotNull(
                yuv?.let { OutputConfiguration(it.surface).apply { if (yuvUseCase != 0L) streamUseCase = yuvUseCase } },
                binned?.let { OutputConfiguration(it.surface).apply { if (binnedUseCase != 0L) streamUseCase = binnedUseCase } },
                OutputConfiguration(full.surface).apply { if (useCase != 0L) streamUseCase = useCase },
            )
            val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                binned?.let { addTarget(it.surface) }
                yuv?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(pvFpsMin, pvFpsMax))
                applyStockKeys(this)
            }
            val stillBinnedTarget = stillBinned
            stillRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(full.surface)
                if (binned != null && (stillBinnedTarget || yuv == null)) addTarget(binned.surface) else yuv?.let { addTarget(it.surface) }
                applyXiaomiKeys(this)
                if (stillFpsMatch) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(pvFpsMin, pvFpsMax))
            }.build()
            val config = SessionConfiguration(sessionType, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        log("PROBE50_CONFIGURED seamless binned=${binnedWidth}x$binnedHeight full=${width}x$height")
                        handler.postDelayed({ if (framesSeen < stills) finishProbe("timeout") }, timeoutMs.toLong())
                        try {
                            s.setRepeatingRequest(preview.build(), null, handler)
                        } catch (e: Exception) {
                            finishProbe("setRepeatingRequest failed: $e")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) = finishProbe("configure failed")
                })
            if (sessionKeys || skeys.isNotEmpty()) {
                val params = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (sessionKeys) applyXiaomiKeys(params) else applyGenericKeys(params)
                config.sessionParameters = params.build()
            }
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            finishProbe("configure threw: $e")
        }
    }

    private fun onYuv(reader: ImageReader, drivesStills: Boolean) {
        reader.acquireLatestImage()?.close() ?: return
        yuvFrames++
        if (yuvFrames == 1 || yuvFrames % 30 == 0) log("PROBE50_PREVIEW n=$yuvFrames")
        if (drivesStills) maybeSendStill(yuvFrames)
    }

    private fun onBinned(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        binnedFrames++
        image.close()
        if (binnedFrames == 1 || binnedFrames % 30 == 0) log("PROBE50_BINNED n=$binnedFrames")
        maybeSendStill(binnedFrames)
    }

    private fun maybeSendStill(viewfinderFrames: Int) {
        // After the viewfinder settles, send the stills one by one (stock cameras switch modes per still).
        if (viewfinderFrames >= 30 && stillsSent < stills && stillsSent == framesSeen) {
            stillsSent++
            log("PROBE50_STILL_SENT n=$stillsSent")
            try {
                session?.capture(requireNotNull(stillRequest), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest,
                                                 failure: android.hardware.camera2.CaptureFailure) {
                        log("PROBE50_STILL_FAILED reason=${failure.reason}")
                    }
                }, handler)
            } catch (e: Exception) {
                finishProbe("capture failed: $e")
            }
        }
    }

    private fun onRaw(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        try {
            framesSeen++
            if (seamless || framesSeen % 5 == 1 || framesSeen == frameLimit) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                // Grid sample: fraction of samples at/below black (64), mean, and per-half zero fractions.
                var total = 0
                var low = 0
                var topLow = 0
                var bottomLow = 0
                var sum = 0L
                var zeros = 0
                val stepY = maxOf(1, image.height / 96)
                val stepX = maxOf(1, image.width / 128)
                for (y in 0 until image.height step stepY) {
                    for (x in 0 until image.width step stepX) {
                        val offset = y * rowStride + x * pixelStride
                        if (offset + 1 >= buffer.limit()) continue
                        val v = (buffer.get(offset).toInt() and 0xff) or ((buffer.get(offset + 1).toInt() and 0xff) shl 8)
                        total++
                        sum += v
                        if (v == 0) zeros++
                        if (v <= 64) {
                            low++
                            if (y < image.height / 2) topLow++ else bottomLow++
                        }
                    }
                }
                // Where the HAL's data actually ends: last 4 KiB block holding a non-zero byte.
                var lastNonZero = -1L
                run {
                    var block = (buffer.limit() - 1) / 4096
                    while (block >= 0) {
                        val start = block * 4096
                        val end = minOf(buffer.limit(), start + 4096)
                        var i = end - 1
                        while (i >= start) { if (buffer.get(i).toInt() != 0) { lastNonZero = i.toLong(); break }; i-- }
                        if (lastNonZero >= 0) break
                        block--
                    }
                }
                log("PROBE50_LAYOUT format=${image.format} limit=${buffer.limit()} rowStride=$rowStride pixelStride=$pixelStride " +
                    "lastNonZero=$lastNonZero filledRows=${if (rowStride > 0) (lastNonZero + 1) / rowStride else -1} " +
                    "raw10Bytes=${image.width.toLong() * image.height * 10 / 8} raw12Bytes=${image.width.toLong() * image.height * 12 / 8}")
                if (dumpMb > 0 && framesSeen == 1) dumpBuffer(buffer, lastNonZero)
                val half = total / 2.0
                log(String.format(java.util.Locale.ROOT,
                    "PROBE50_FRAME n=%d dims=%dx%d rowStride=%d zeroFrac=%.4f fracAtOrBelowBlack=%.4f topHalf=%.4f bottomHalf=%.4f mean=%.1f",
                    framesSeen, image.width, image.height, rowStride, zeros.toDouble() / total, low.toDouble() / total,
                    topLow / half, bottomLow / half, sum.toDouble() / total))
            }
        } finally {
            image.close()
        }
        if (framesSeen >= (if (seamless) stills else frameLimit)) finishProbe("done binnedFrames=$binnedFrames")
    }

    private fun dumpBuffer(buffer: java.nio.ByteBuffer, lastNonZero: Long) {
        val dir = getExternalFilesDir(null) ?: filesDir
        fun write(name: String, from: Int, size: Int) {
            val start = from.coerceIn(0, buffer.limit())
            val n = minOf(size, buffer.limit() - start)
            val bytes = ByteArray(n)
            val dup = buffer.duplicate()
            dup.position(start)
            dup.get(bytes)
            File(dir, name).writeBytes(bytes)
            log("PROBE50_DUMP file=${File(dir, name).absolutePath} offset=$start bytes=$n")
        }
        try {
            write("probe_head.bin", 0, dumpMb * 1024 * 1024)
            write("probe_mid.bin", buffer.limit() / 2, dumpMb * 1024 * 1024)
            if (lastNonZero > 0) write("probe_tail.bin", (lastNonZero - 1024 * 1024).toInt(), 2 * 1024 * 1024)
        } catch (e: Exception) {
            log("PROBE50_DUMP_FAILED $e")
        }
    }

    private fun finishProbe(reason: String) {
        if (finished) return
        finished = true
        log("PROBE50_END reason=$reason frames=$framesSeen")
        try { session?.close() } catch (ignored: Exception) {}
        try { device?.close() } catch (ignored: Exception) {}
        rawReader?.close()
        yuvReader?.close()
        binnedReader?.close()
        try { File(filesDir, "xiaomi50mp_probe.txt").writeText(report.toString()) } catch (ignored: Exception) {}
        runOnUiThread { finish() }
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Rawr50Probe"
    }
}
