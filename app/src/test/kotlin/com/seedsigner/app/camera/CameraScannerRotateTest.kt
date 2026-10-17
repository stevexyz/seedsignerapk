package com.seedsigner.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * rotatePlane is the arithmetic behind the camera scanner's 4-rotation
 * detector sweep (the pinned ZXing detector limitation, VENDORED.md). It
 * never ran against a real camera frame before the emulated-camera E2E,
 * where the swapped 90°/270° mapping threw ArrayIndexOutOfBoundsException
 * on every landscape frame — these tests pin the mapping and the
 * dimension/round-trip invariants.
 */
class CameraScannerRotateTest {

    @Test
    fun `landscape frame rotates without out-of-bounds`() {
        val src = ByteArray(1280 * 960) { (it % 251).toByte() }
        for (q in 1..3) {
            val (dst, nw, nh) = rotatePlane(src, 1280, 960, q)
            assertEquals(if (q % 2 == 0) 1280 else 960, nw)
            assertEquals(if (q % 2 == 0) 960 else 1280, nh)
            assertEquals(nw * nh, dst.size)
        }
    }

    @Test
    fun `quarter turn moves corners correctly`() {
        // 3x2 grid (w=3, h=2):
        //  0 1 2
        //  3 4 5
        val src = ByteArray(6) { it.toByte() }
        // 90° CW -> 2x3:
        //  3 0
        //  4 1
        //  5 2
        val (dst90, w90, h90) = rotatePlane(src, 3, 2, 1)
        assertEquals(2, w90); assertEquals(3, h90)
        assertEquals(listOf<Byte>(3, 0, 4, 1, 5, 2), dst90.toList())
        // 180 -> 3x2:
        //  5 4 3
        //  2 1 0
        val (dst180, w180, h180) = rotatePlane(src, 3, 2, 2)
        assertEquals(3, w180); assertEquals(2, h180)
        assertEquals(listOf<Byte>(5, 4, 3, 2, 1, 0), dst180.toList())
        // 270° CW (90° CCW) -> 2x3:
        //  2 5
        //  1 4
        //  0 3
        val (dst270, w270, h270) = rotatePlane(src, 3, 2, 3)
        assertEquals(2, w270); assertEquals(3, h270)
        assertEquals(listOf<Byte>(2, 5, 1, 4, 0, 3), dst270.toList())
    }

    @Test
    fun `four quarter turns round-trip and preserve pixels`() {
        val src = ByteArray(11 * 13) { (it * 7 % 256).toByte() }
        var plane = src
        var w = 11
        var h = 13
        // four successive 90° turns = identity
        for (q in 1..4) {
            val (p, nw, nh) = rotatePlane(plane, w, h, 1)
            plane = p; w = nw; h = nh
        }
        assertEquals(11, w); assertEquals(13, h)
        assertEquals(src.toList(), plane.toList())
    }
}
