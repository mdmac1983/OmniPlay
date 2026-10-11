package app.orionmd.omniplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Media Hub searches TMDB for: the show or movie name only, without episode numbers, quality tags or site names. */
class TmdbParseTest {
    private fun p(name: String, folder: String = ""): JSONObject = JSONObject().put("n", name).put("f", folder).put("i", 1).also { Tmdb.parse(it) }
    private fun tv(name: String, folder: String, show: String, se: Int, ep: Int, year: Int = 0) {
        val o = p(name, folder)
        assertEquals(name, "tv", o.optString("k")); assertEquals(name, show, o.optString("show"))
        assertEquals("$name season", se, o.optInt("se")); assertEquals("$name episode", ep, o.optInt("ep")); assertEquals("$name year", year, o.optInt("py"))
    }
    private fun movie(name: String, title: String, year: Int = 0) {
        val o = p(name); assertEquals(name, "movie", o.optString("k")); assertEquals(name, title, o.optString("pt")); assertEquals("$name year", year, o.optInt("py"))
    }

    @Test fun tv_episodes() {
        tv("Bad.Boy.S01E01.360p.mp4", "", "Bad Boy", 1, 1)
        tv("Bad Boy - S01E02 - Bad Boy Comes Out 360p.mkv", "", "Bad Boy", 1, 2)
        tv("The Office S02 E05 480p.mp4", "", "The Office", 2, 5)
        tv("Game of Thrones 1x01 Winter Is Coming.avi", "", "Game of Thrones", 1, 1)
        tv("Show Name Season 1 Episode 3 360p.mp4", "", "Show Name", 1, 3)
        tv("Breaking Bad (2008) - S01E01 - Pilot (1080p BluRay x265).mkv", "", "Breaking Bad", 1, 1, 2008)
        tv("Stranger Things S04 E01 Chapter One 360p.mp4", "", "Stranger Things", 4, 1)
        tv("Severance S1E3.mp4", "", "Severance", 1, 3)
        tv("Bad Boy E01 360p.mp4", "", "Bad Boy", 1, 1)
        tv("Bad Boy Episode 1 360p.mp4", "", "Bad Boy", 1, 1)
        tv("Bad Boy - Season 1 - Episode 01.mp4", "", "Bad Boy", 1, 1)
        tv("[SubsPlease] Jujutsu Kaisen - 05 (1080p) [ABCD1234].mkv", "Anime", "Jujutsu Kaisen", 1, 5)
    }

    @Test fun episodes_named_only_by_number_take_the_show_from_the_folder() {
        tv("S01E01.mp4", "Bad Boy/Season 1", "Bad Boy", 1, 1)
        tv("S01E01 - Pilot 720p.mkv", "Videos/Breaking Bad/Season 1", "Breaking Bad", 1, 1)
        tv("Episode 5.mp4", "Videos/The Office/Season 2", "The Office", 2, 5)
        tv("Ep 03.mp4", "Shows/Severance", "Severance", 1, 3)
    }

    @Test fun movies() {
        movie("www.1TamilMV.cz - Leo (2023) Tamil HQ HDRip - 360p - x264 - AAC.mkv", "Leo", 2023)
        movie("Inception.2010.360p.BrRip.x264.YIFY.mp4", "Inception", 2010)
        movie("Inception 360p.mp4", "Inception")
        movie("The Matrix (1999) [720p] [BluRay] [YTS.MX].mp4", "The Matrix", 1999)
        movie("Oppenheimer.2023.HDTS.360p.mp4", "Oppenheimer", 2023)
        movie("Movie_Name_2019_480p_HEVC.mkv", "Movie Name", 2019)
    }
}
