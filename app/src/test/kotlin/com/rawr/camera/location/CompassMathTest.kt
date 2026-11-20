package com.rawr.camera.location

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class CompassMathTest {
    /** Quaternion (x, y, z, w) for a rotation of [deg] about a world axis. */
    private fun axis(ax: Float, ay: Float, az: Float, deg: Double): FloatArray {
        val half = Math.toRadians(deg) / 2
        val s = sin(half).toFloat()
        return floatArrayOf(ax * s, ay * s, az * s, cos(half).toFloat())
    }

    /** a then b, both about world axes: q = b * a. */
    private fun then(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a.toList()
        val (bx, by, bz, bw) = b.toList()
        return floatArrayOf(
            bw * ax + bx * aw + by * az - bz * ay,
            bw * ay - bx * az + by * aw + bz * ax,
            bw * az + bx * ay - by * ax + bz * aw,
            bw * aw - bx * ax - by * ay - bz * az
        )
    }

    // Phone upright in portrait, screen towards the photographer: the rear lens looks at the horizon.
    private val uprightFacingNorth = axis(1f, 0f, 0f, 90.0)

    private fun facing(yawClockwiseDeg: Double) = then(uprightFacingNorth, axis(0f, 0f, 1f, -yawClockwiseDeg))

    @Test fun uprightPhoneLooksWhereTheLensPoints() {
        assertEquals(0f, CompassMath.rearLensHeadingDeg(uprightFacingNorth)!!, 0.01f)
        assertEquals(90f, CompassMath.rearLensHeadingDeg(facing(90.0))!!, 0.01f)
        assertEquals(180f, CompassMath.rearLensHeadingDeg(facing(180.0))!!, 0.01f)
        assertEquals(247.5f, CompassMath.rearLensHeadingDeg(facing(247.5))!!, 0.01f)
    }

    @Test fun landscapeDoesNotChangeTheHeading() {
        // Turning the phone into landscape is a rotation about its own lens axis.
        val landscape = then(axis(0f, 0f, 1f, 90.0), facing(90.0))
        assertEquals(90f, CompassMath.rearLensHeadingDeg(landscape)!!, 0.01f)
    }

    @Test fun nearVerticalLensHasNoHeading() {
        assertNull(CompassMath.rearLensHeadingDeg(floatArrayOf(0f, 0f, 0f, 1f)))       // flat, lens down
        assertNull(CompassMath.rearLensHeadingDeg(axis(1f, 0f, 0f, 10.0)))              // lens 80 degrees down
        assertNotNull(CompassMath.rearLensHeadingDeg(axis(1f, 0f, 0f, 30.0)))           // lens 60 degrees down
    }

    @Test fun missingScalarPartIsDerived() {
        val q = facing(90.0)
        assertEquals(90f, CompassMath.rearLensHeadingDeg(floatArrayOf(q[0], q[1], q[2]))!!, 0.5f)
        assertNull(CompassMath.rearLensHeadingDeg(floatArrayOf(0f, 0f)))
    }

    @Test fun anglesWrapIntoOneTurn() {
        assertEquals(350f, CompassMath.normalizeDeg(-10f), 1e-4f)
        assertEquals(10f, CompassMath.normalizeDeg(370f), 1e-4f)
    }
}
