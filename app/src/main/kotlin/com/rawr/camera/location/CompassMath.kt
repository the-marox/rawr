package com.rawr.camera.location

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Lens heading from a rotation vector; pure so it can be checked without sensors. */
object CompassMath {
    /**
     * Below this horizontal share of the lens axis (sin 15 degrees) the lens points within 15 degrees of straight
     * up or down, where a compass direction means little and swings with every small tilt.
     */
    private const val MIN_HORIZONTAL = 0.2588f

    fun normalizeDeg(deg: Float): Float {
        val wrapped = deg % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }

    /**
     * Compass direction of the rear lens axis, degrees clockwise from the reference north of the sensor
     * (magnetic for TYPE_ROTATION_VECTOR), or null when the lens points near vertical.
     *
     * The rotation vector is a unit quaternion (x, y, z, [w]) taking device coordinates to the world frame
     * (X east, Y north, Z up); see SensorManager.getRotationMatrixFromVector. The rear lens looks along device -Z,
     * so its world direction is minus the third column of that matrix. The result does not depend on portrait or
     * landscape: turning the phone about its own Z axis leaves the lens axis where it is.
     */
    fun rearLensHeadingDeg(rotationVector: FloatArray): Float? {
        if (rotationVector.size < 3) return null
        val x = rotationVector[0]
        val y = rotationVector[1]
        val z = rotationVector[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val w = if (rotationVector.size >= 4 && rotationVector[3].isFinite()) rotationVector[3]
        else sqrt(maxOf(0f, 1f - x * x - y * y - z * z))
        val r2 = 2f * x * z + 2f * y * w
        val r5 = 2f * y * z - 2f * x * w
        val east = -r2
        val north = -r5
        if (hypot(east, north) < MIN_HORIZONTAL) return null
        return normalizeDeg(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat())
    }
}
