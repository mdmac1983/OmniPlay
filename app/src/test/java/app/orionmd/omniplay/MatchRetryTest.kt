package app.orionmd.omniplay

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRetryTest {
    private val day = 24L * 3600 * 1000

    @Test fun new_files_are_looked_up() = assertTrue(needsMatch(null))

    @Test fun a_match_is_kept() = assertFalse(needsMatch(JSONObject().put("id", 603).put("title", "The Matrix")))

    @Test fun no_match_is_tried_again_after_a_few_days() {
        assertFalse(needsMatch(noMatch()))
        assertTrue(needsMatch(JSONObject().put("none", true).put("t", System.currentTimeMillis() - 4 * day)))
        assertTrue(needsMatch(JSONObject().put("none", true)))   // saved by an older version (no date)
    }

    @Test fun your_own_no_match_stays() = assertFalse(needsMatch(JSONObject().put("none", true).put("manual", true)))
}
