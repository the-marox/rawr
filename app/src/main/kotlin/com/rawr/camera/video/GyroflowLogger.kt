package com.rawr.camera.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.io.Writer
import kotlin.math.roundToLong

/**
 * Gyroflow's text gyro log, `.gcsv` v1.3 (docs.gyroflow.xyz/app/technical-details/gcsv-format), kept pure so the
 * layout can be checked without sensors.
 *
 * Values are fixed-point integers: t in microseconds from the start of the recording, gyro in 1e-5 rad/s,
 * acceleration in 1e-5 g. The axes are Android's device axes (X right, Y up, Z out of the screen, phone upright),
 * which Gyroflow reads as orientation `XYZ`, as it does for other Android loggers.
 */
internal object Gcsv {
    private const val GYRO_SCALE = 1e-5
    private const val ACCEL_SCALE = 1e-5
    private const val STANDARD_GRAVITY = 9.80665

    fun header(id: String, videoFileName: String, startUnixSeconds: Long, firmware: String, withAccel: Boolean) =
        buildString {
            append("GYROFLOW IMU LOG\n")
            append("version,1.3\n")
            append("id,").append(field(id)).append('\n')
            append("orientation,XYZ\n")
            // Header values must not contain commas: a three-field line would end the header block.
            append("note,Android TYPE_GYROSCOPE and TYPE_ACCELEROMETER in device axes; t = 0 at the first video frame\n")
            append("fwversion,").append(field(firmware)).append('\n')
            append("timestamp,").append(startUnixSeconds).append('\n')
            append("vendor,Rawr\n")
            append("videofilename,").append(field(videoFileName)).append('\n')
            append("tscale,0.000001\n")
            append("gscale,0.00001\n")
            if (withAccel) append("ascale,0.00001\n")
            append(if (withAccel) "t,gx,gy,gz,ax,ay,az\n" else "t,gx,gy,gz\n")
        }

    /** One sample: [gyroRadS] in rad/s and, when the log has them, [accelMs2] in m/s². */
    fun row(tUs: Long, gyroRadS: FloatArray, accelMs2: FloatArray?): String = buildString {
        append(tUs)
        for (axis in 0..2) append(',').append((gyroRadS[axis] / GYRO_SCALE).roundToLong())
        if (accelMs2 != null) {
            for (axis in 0..2) append(',').append((accelMs2[axis] / STANDARD_GRAVITY / ACCEL_SCALE).roundToLong())
        }
        append('\n')
    }

    /**
     * How sensor time maps onto the video file's time. The recorder writes frame times on the exact 1/fps grid
     * from the first frame, so when the camera runs slightly fast (30.04 fps for a 30 fps recording) the file's
     * clock drifts from real time by that ratio; [scale] stretches sensor time to match, and [firstFrameBootUs]
     * is t = 0.
     */
    data class VideoTimeline(val firstFrameBootUs: Long, val scale: Double) {
        fun fileTimeUs(bootUs: Long): Long = ((bootUs - firstFrameBootUs) * scale).roundToLong()

        companion object {
            /** Correction beyond this is not clock drift but a reporting error: keep the time unscaled. */
            private const val MAX_SCALE_ERROR = 0.02

            /**
             * From the recorder's stats: the first frame and the last frame in real (boot) time, and the last
             * frame's time as written to the file. Null when the stats do not say when the first frame was.
             */
            fun of(firstFrameBootUs: Long, lastFrameBootUs: Long, lastFileFrameBootUs: Long): VideoTimeline? {
                if (firstFrameBootUs <= 0) return null
                val realSpanUs = lastFrameBootUs - firstFrameBootUs
                val fileSpanUs = lastFileFrameBootUs - firstFrameBootUs
                val scale = if (realSpanUs > 0 && fileSpanUs > 0) fileSpanUs.toDouble() / realSpanUs else 1.0
                return VideoTimeline(firstFrameBootUs, scale.takeIf { kotlin.math.abs(it - 1.0) <= MAX_SCALE_ERROR } ?: 1.0)
            }
        }
    }

    /**
     * Moves one data row from log time (µs since [logStartBootUs]) to [timeline]'s file time; header lines pass
     * through unchanged; rows before the first frame are dropped (null).
     */
    fun retimeLine(line: String, logStartBootUs: Long, timeline: VideoTimeline): String? {
        val comma = line.indexOf(',')
        val t = if (comma > 0) line.substring(0, comma).toLongOrNull() else null
        if (t == null) return line
        val fileUs = timeline.fileTimeUs(logStartBootUs + t)
        if (fileUs < 0) return null
        return fileUs.toString() + line.substring(comma)
    }

    /** `RAWR_x.mp4` -> `RAWR_x.gcsv`: Gyroflow finds a log with the video's name beside it on its own. */
    fun sidecarName(videoFileName: String): String = videoFileName.substringBeforeLast('.', videoFileName) + ".gcsv"

    private fun field(value: String) = value.replace(',', ' ').replace('\n', ' ').trim()
}

/**
 * Records the gyroscope (and accelerometer) at 200 Hz while a video records, for stabilisation in Gyroflow.
 * Samples are written on their own thread to a temporary file; [stop] hands the finished log back.
 * t = 0 is the moment [start] runs, right after the recorder started; Gyroflow's sync refines the offset.
 */
internal class GyroflowLogger(private val context: Context) {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var writer: Writer? = null
    private var file: File? = null
    private var listener: SensorEventListener? = null
    @Volatile private var failed = false
    private var rows = 0L

    /** Boot time (µs) of t = 0 in the log as written; [Gcsv.retimeLine] moves it to the first video frame. */
    var startBootUs = 0L
        private set

    /** Starts the log for [videoFileName]; false (and nothing recorded) when the phone has no gyroscope. */
    @Synchronized fun start(videoFileName: String, firmware: String): Boolean {
        check(listener == null) { "Gyro log already running" }
        val sensors = manager ?: return false
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val out = File.createTempFile("gyroflow_", ".gcsv", context.cacheDir)
        val sink = out.bufferedWriter()
        sink.write(Gcsv.header("rawr_${Build.MODEL.orEmpty()}", videoFileName, System.currentTimeMillis() / 1000,
            firmware, withAccel = accel != null))
        val worker = HandlerThread("RawrGyroflowLog").apply { start() }
        val startNanos = SystemClock.elapsedRealtimeNanos()
        startBootUs = startNanos / 1000
        val latestAccel = FloatArray(3)
        var haveAccel = false
        val next = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (failed) return
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        event.values.copyInto(latestAccel, 0, 0, 3)
                        haveAccel = true
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        // Sensor timestamps share SystemClock.elapsedRealtimeNanos's clock.
                        if (event.timestamp < startNanos || (accel != null && !haveAccel)) return
                        try {
                            sink.write(Gcsv.row((event.timestamp - startNanos) / 1000, event.values,
                                if (accel != null) latestAccel else null))
                            rows++
                        } catch (_: java.io.IOException) {
                            failed = true
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val handler = Handler(worker.looper)
        failed = false
        rows = 0
        if (!sensors.registerListener(next, gyro, SAMPLING_US, handler)) {
            worker.quitSafely()
            sink.close()
            out.delete()
            return false
        }
        accel?.let { sensors.registerListener(next, it, SAMPLING_US, handler) }
        thread = worker
        writer = sink
        file = out
        listener = next
        return true
    }

    /** Stops logging; returns the finished log, or null when nothing usable was recorded. */
    @Synchronized fun stop(): File? {
        val active = listener ?: return null
        manager?.unregisterListener(active)
        listener = null
        thread?.let {
            it.quitSafely()
            it.join(STOP_JOIN_MS)
        }
        thread = null
        runCatching { writer?.close() }.onFailure { failed = true }
        writer = null
        val out = file
        file = null
        if (out == null || failed || rows == 0L) {
            out?.delete()
            return null
        }
        return out
    }

    /**
     * Rewrites the finished log [file] so t follows the video file's clock from its first frame (see
     * [Gcsv.VideoTimeline]). Returns the new file; the input is deleted.
     */
    fun retime(file: File, timeline: Gcsv.VideoTimeline): File {
        val out = File.createTempFile("gyroflow_", ".gcsv", context.cacheDir)
        try {
            out.bufferedWriter().use { writer ->
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Gcsv.retimeLine(line, startBootUs, timeline)?.let { writer.write(it); writer.write("\n") }
                    }
                }
            }
        } catch (error: Exception) {
            out.delete()
            throw error
        }
        file.delete()
        return out
    }

    /** Stops and discards the log (the recording failed). */
    fun abort() {
        stop()?.delete()
    }

    private companion object {
        /** 200 Hz: the fastest rate apps get without the HIGH_SAMPLING_RATE_SENSORS permission. */
        const val SAMPLING_US = 5_000
        const val STOP_JOIN_MS = 2_000L
    }
}
