package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mapping the on-screen viewfinder square to the camera frame, which is shown center-cropped. */
class PaveQrCropTest {
    @Test
    fun `upright frame shown at its own size`() {
        assertEquals(QrCrop(220, 140, 200, 200), viewfinderCrop(640, 480, 0, 640f, 480f, 200f))
    }

    @Test
    fun `upright frame scaled and cropped to a wide box`() {
        // 640x480 shown in a 1280x600 box: scale 2, so 1280x960 with 180 px cut at the top and bottom.
        assertEquals(QrCrop(270, 190, 100, 100), viewfinderCrop(640, 480, 0, 1280f, 600f, 200f))
    }

    @Test
    fun `portrait phone, frame rotated by 90 degrees`() {
        // Buffer 640x480, upright 480x640, shown in a 960x480 box: scale 2, 400 px cut top and bottom.
        // Finder 200 px: upright x 190..290, y 270..370; buffer x = upright y, buffer y = 480 - upright x.
        assertEquals(QrCrop(270, 190, 100, 100), viewfinderCrop(640, 480, 90, 960f, 480f, 200f))
    }

    @Test
    fun `frame rotated by 270 degrees`() {
        // Buffer x = 640 - upright y, buffer y = upright x: the centered square lands at the same place.
        assertEquals(QrCrop(270, 190, 100, 100), viewfinderCrop(640, 480, 270, 960f, 480f, 200f))
        // Without the rotation the same box would show another part of the frame.
        assertEquals(QrCrop(253, 173, 134, 134), viewfinderCrop(640, 480, 0, 960f, 480f, 200f))
    }

    @Test
    fun `frame rotated by 180 degrees`() {
        assertEquals(QrCrop(220, 140, 200, 200), viewfinderCrop(640, 480, 180, 640f, 480f, 200f))
    }

    @Test
    fun `a square that does not fit inside the frame gives no crop, never the whole frame`() {
        assertNull(viewfinderCrop(640, 480, 0, 640f, 480f, 2000f))
        // A stale, tiny view would blow the square up past the frame: skipped.
        assertNull(viewfinderCrop(640, 480, 0, 120f, 90f, 200f))
    }

    @Test
    fun `an unknown or implausible view size gives no crop`() {
        assertNull(viewfinderCrop(640, 480, 0, 0f, 0f, 200f))
        assertNull(viewfinderCrop(640, 480, 0, 150f, 600f, 200f)) // narrower than the viewfinder itself
        assertNull(viewfinderCrop(0, 480, 0, 640f, 480f, 200f))
    }
}
