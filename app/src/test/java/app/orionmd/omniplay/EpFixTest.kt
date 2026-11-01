package app.orionmd.omniplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpFixTest {
    private fun ep(id: Long, se: Int, ep: Int, k: String = "tv") = Item(i = id, n = "Show.S0${se}E0$ep.mkv", c = 2, s = 0, f = "", src = "d", u = "", cr = 0,
        t = null, a = null, al = null, g = null, tr = 0, d = 0, y = 0, th = true, k = k, show = "Show", se = se, ep = ep, pt = null, py = 0, concert = false)

    @Test fun your_season_and_episode_replace_the_file_name_ones() {
        val fixed = applyEpFix(ep(7, 1, 1), JSONObject().put("7", "2:5"))
        assertEquals(2, fixed.se); assertEquals(5, fixed.ep)
    }

    @Test fun other_files_movies_and_bad_values_are_left_alone() {
        val fixes = JSONObject().put("7", "2:5").put("8", "x:1").put("9", "3")
        val other = ep(1, 1, 1); assertSame(other, applyEpFix(other, fixes))
        val movie = ep(7, 0, 0, "movie"); assertSame(movie, applyEpFix(movie, fixes))
        val bad = ep(8, 1, 2); assertSame(bad, applyEpFix(bad, fixes))
        val half = ep(9, 1, 3); assertSame(half, applyEpFix(half, fixes))
    }
}
