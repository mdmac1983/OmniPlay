package app.orionmd.omniplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movie filters and output sizes. */
class MovieFxTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = FloatArray(3) { i -> m[i * 4] * r + m[i * 4 + 1] * g + m[i * 4 + 2] * b + m[i * 4 + 3] }

    @Test fun original_changes_nothing() { assertTrue(MovieFx.isIdentity(MovieFx.matrix("none", 0f, 0f, 0f))) }

    @Test fun black_and_white_makes_grey() {
        val o = apply(MovieFx.matrix("bw", 0f, 0f, 0f), 1f, 0f, 0f)
        assertEquals(o[0], o[1], 1e-4f); assertEquals(o[1], o[2], 1e-4f)
    }

    @Test fun negative_inverts() {
        val o = apply(MovieFx.matrix("invert", 0f, 0f, 0f), .2f, .5f, 1f)
        assertEquals(.8f, o[0], 1e-4f); assertEquals(.5f, o[1], 1e-4f); assertEquals(0f, o[2], 1e-4f)
    }

    @Test fun brightness_lifts_and_contrast_keeps_middle_grey() {
        assertTrue(apply(MovieFx.matrix("none", .5f, 0f, 0f), .5f, .5f, .5f)[0] > .5f)
        assertEquals(.5f, apply(MovieFx.matrix("none", 0f, .8f, 0f), .5f, .5f, .5f)[0], 1e-4f)
    }

    @Test fun export_matrix_is_column_major_with_offsets_last() {
        val m = MovieFx.matrix("invert", 0f, 0f, 0f); val gl = MovieFx.gl(m)
        assertEquals(-1f, gl[0], 1e-4f); assertEquals(1f, gl[12], 1e-4f); assertEquals(1f, gl[15], 1e-4f)
        assertEquals(255f, MovieFx.compose(m)[4], 1e-3f)
    }

    @Test fun sizes_are_even_and_follow_the_format() {
        for (f in listOf("9:16", "16:9", "1:1", "4:5")) for (r in listOf(480, 720, 1080, 2160)) {
            val (w, h) = Reel(format = f, res = r).size()
            assertEquals(0, w % 2); assertEquals(0, h % 2); assertEquals(r, minOf(w, h))
        }
        assertTrue(Reel(quality = "low").bitrate() < Reel(quality = "high").bitrate())
        assertTrue(Reel(res = 720).bitrate() < Reel(res = 1080).bitrate())
    }
}
