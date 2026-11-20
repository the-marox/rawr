package com.rawr.camera.video

import kotlin.test.*

class GcsvTest {
    @Test fun headerFollowsTheGcsvLayout() {
        val header = Gcsv.header("rawr_24129PN74G", "RAWR_20261120_083000_123.mp4", 1_795_000_000L, "RAWR 0.5.1",
            withAccel = true)
        assertEquals(
            """
            GYROFLOW IMU LOG
            version,1.3
            id,rawr_24129PN74G
            orientation,XYZ
            note,Android TYPE_GYROSCOPE and TYPE_ACCELEROMETER in device axes; t = 0 at the first video frame
            fwversion,RAWR 0.5.1
            timestamp,1795000000
            vendor,Rawr
            videofilename,RAWR_20261120_083000_123.mp4
            tscale,0.000001
            gscale,0.00001
            ascale,0.00001
            t,gx,gy,gz,ax,ay,az
            """.trimIndent() + "\n",
            header
        )
        // Every header line has exactly two fields, or Gyroflow's reader would take it for data.
        header.lines().drop(1).takeWhile { !it.startsWith("t,") }.forEach { assertEquals(2, it.split(',').size, it) }
    }

    @Test fun gyroOnlyLogHasNoAccelerometerColumns() {
        val header = Gcsv.header("a,b", "v.mp4", 0L, "x", withAccel = false)
        assertFalse(header.contains("ascale"))
        assertTrue(header.endsWith("t,gx,gy,gz\n"))
        assertTrue(header.contains("id,a b\n"))
    }

    @Test fun rowsAreFixedPointInScaledUnits() {
        // 0.1 rad/s -> 10000 at gscale 1e-5; 1 g (9.80665 m/s²) -> 100000 at ascale 1e-5.
        assertEquals("1000,10000,-20000,0,0,100000,0\n",
            Gcsv.row(1000L, floatArrayOf(0.1f, -0.2f, 0f), floatArrayOf(0f, 9.80665f, 0f)))
        assertEquals("5000,1,0,-1\n", Gcsv.row(5000L, floatArrayOf(0.00001f, 0f, -0.00001f), null))
    }

    @Test fun fastCameraStretchesSensorTimeToTheFileClock() {
        // 30 fps recording from a camera at 30.04 fps: 601 frames in 19.973 s of real time sit on the 33 333 µs
        // grid up to 20.000 s in the file. First frame at boot 5 s.
        val timeline = Gcsv.VideoTimeline.of(5_000_000L, 5_000_000L + 19_973_369L, 5_000_000L + 19_999_800L)!!
        assertEquals(1.0013, timeline.scale, 0.0001)
        assertEquals(0L, timeline.fileTimeUs(5_000_000L))
        assertTrue(kotlin.math.abs(timeline.fileTimeUs(5_000_000L + 19_973_369L) - 19_999_800L) <= 2L)
    }

    @Test fun slowCameraAndOddStatsAreHandled() {
        // Frames late: the grid follows real time with gaps, the clocks agree.
        assertEquals(1.0, Gcsv.VideoTimeline.of(1_000_000L, 11_000_000L, 11_000_000L)!!.scale, 1e-9)
        // A 10 % difference is not clock drift: keep the real clock.
        assertEquals(1.0, Gcsv.VideoTimeline.of(1L, 10_000_001L, 11_000_001L)!!.scale)
        // Older stats without the file time, or a single frame: real clock from the first frame.
        assertEquals(1.0, Gcsv.VideoTimeline.of(1_000L, 5_000L, -1L)!!.scale)
        assertEquals(1.0, Gcsv.VideoTimeline.of(1_000L, 1_000L, 1_000L)!!.scale)
        assertNull(Gcsv.VideoTimeline.of(-1L, 10L, 10L))
    }

    @Test fun retimingMovesRowsToTheFirstFrameAndKeepsTheHeader() {
        val timeline = Gcsv.VideoTimeline(firstFrameBootUs = 1_150_000L, scale = 1.0)
        // The log started at boot 1.0 s; the first frame came 150 ms later.
        assertEquals("tscale,0.000001", Gcsv.retimeLine("tscale,0.000001", 1_000_000L, timeline))
        assertEquals("t,gx,gy,gz,ax,ay,az", Gcsv.retimeLine("t,gx,gy,gz,ax,ay,az", 1_000_000L, timeline))
        assertNull(Gcsv.retimeLine("100000,1,2,3,4,5,6", 1_000_000L, timeline))
        assertEquals("0,1,2,3,4,5,6", Gcsv.retimeLine("150000,1,2,3,4,5,6", 1_000_000L, timeline))
        assertEquals("850000,1,2,3", Gcsv.retimeLine("1000000,1,2,3", 1_000_000L, timeline))
    }

    @Test fun sidecarTakesTheVideoName() {
        assertEquals("RAWR_20261120_083000_123.gcsv", Gcsv.sidecarName("RAWR_20261120_083000_123.mp4"))
        assertEquals("RAWR_1.gcsv", Gcsv.sidecarName("RAWR_1.mov"))
        assertEquals("clip.gcsv", Gcsv.sidecarName("clip"))
    }
}
