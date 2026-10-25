package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Movie / TV recognition:
 *  - parse(): turns a file name + folder path into kind (movie | tv | mv = music video), clean title, year, season/episode
 *  - TMDB lookups with the user's API Read Access Token (falls back to the v3 API key)
 *  - poster/backdrop images are cached in files/tmdbimg so they show offline
 */
object Tmdb {
    private val TV_RX = Regex("(?i)^(.*?)[\\s\\-\\[(]*\\bs(\\d{1,2})[\\s._-]*e(\\d{1,3})")
    private val TV_RX2 = Regex("(?i)^(.*?)[\\s\\-]*\\b(\\d{1,2})x(\\d{2})\\b")
    /** "Show Season 1 Episode 3", "Show - Season 1 - Ep 03" */
    private val TV_RX3 = Regex("(?i)^(.*?)[\\s._-]*\\bseason[\\s._-]*(\\d{1,2})[\\s._-]*(?:episode|ep)[\\s._-]*(\\d{1,3})\\b")
    /** "Show Episode 5", "Show Ep 05", "Show E05" (season 1, or the season folder's number) */
    private val EP_RX = Regex("(?i)^(.*?)[\\s._-]*\\b(?:episode|ep|e)[\\s._-]*(\\d{1,3})\\b")
    /** A season pack file: "Show S01 360p" */
    private val SEASON_RX = Regex("(?i)^(.*?)[\\s._-]*\\b(?:s|season[\\s._-]*)(\\d{1,2})\\b(?![\\s._-]*e\\d)")
    /** Anime releases: "[Group] Show - 05 (1080p) [hash]" */
    private val ANIME_RX = Regex("^\\s*\\[[^]]+]\\s*(.+?)\\s+-\\s+(\\d{1,4})(?:v\\d)?(?=\\s|\\[|\\(|$)")
    /** Website names some downloads start with: "www.Site.cz - Title" */
    private val SITE_RX = Regex("(?i)^\\s*(?:\\[\\s*)?(?:www\\.)?[a-z0-9-]+\\.(?:com|net|org|cz|to|tv|me|io|in|ws|cc|co|mx|lol|xyz|sx|ag|is|pw|biz|info|club|site|win)(?:\\s*])?\\s*-?\\s*")
    private val SEASON_FOLDER = Regex("(?i)^(season|series|s|staffel|saison|temporada)[\\s._-]*\\d+|^specials?$")
    private val GENERIC_FOLDER = Regex("(?i)^(videos?|movies?|films?|tv|tv shows?|shows?|series|downloads?|download|media|dcim|camera|telegram|whatsapp video|anime|cartoons?|kids|complete|completed|new folder)$")
    private val YEAR_RX = Regex("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])")
    private val JUNK = Regex("(?i)\\b(\\d{3,4}p|4k|uhd|hdr|bluray|blu-ray|blu ray|brrip|bdrip|webrip|web-dl|webdl|web|hdrip|dvdrip|dvd|hdtv|x264|x265|h264|h265|hevc|aac|ac3|dts|10bit|remux|proper|repack|extended|unrated|imax|yts|yify|rarbg|hdcam|hdts|camrip|hc|amzn|nf|dsnp|hmax|atvp|ddp?5|aac2|esubs?|subs|multi|dual audio|internal|limited|dubbed)\\b.*$")
    val CONCERT_RX = Regex("(?i)\\b(concert|in concert|unplugged|live (at|in|from|on)|world tour|[a-z']+ tour|tour \\d{4}|live \\d{4}|the .{2,30} live)\\b")
    private val CONCERT_FOLDER = Regex("(?i)\\b(concerts?|live shows?|live performances?)\\b")
    private val MV_RX = Regex("(?i)(official (music )?video|music video|lyric video|official audio|\\(audio\\)|visualizer)")

    fun clean(s: String): String = s
        .replace(SITE_RX, "")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("(?<=\\S)-+(?=\\S)|\\s+-+\\s+"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(JUNK, " ")
        .replace(Regex("\\(\\s*\\)|\\(\\s*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '(', ')', '[', ']', ':', ',')

    /** Adds k/pt/py/se/ep/show to a video record. */
    fun parse(o: JSONObject) {
        val name = o.optString("n").substringBeforeLast('.')
        val segs = o.optString("f").split('/').filter { it.isNotBlank() }
        val low = segs.map { it.lowercase() }
        // the deepest folder that says TV or Movies decides ("TV and Movies/Movies/x.mkv" is a movie, "TV and Movies/TV Shows/…" TV)
        val tvRx = Regex("\\b(tv|series|shows?)\\b"); val movieRx = Regex("\\b(movies?|films?)\\b")
        val tvAt = low.indexOfLast { tvRx.containsMatchIn(it) && !movieRx.containsMatchIn(it) }
        val movieAt = low.indexOfLast { movieRx.containsMatchIn(it) && !tvRx.containsMatchIn(it) }
        val tvFolder = if (tvAt > movieAt) tvAt else -1
        val mvFolder = low.any { it.contains("music video") || it.contains("musicvideo") }
        // concerts go with the music videos, not the movies ("Concerts/…", "… Live at Wembley", "MTV Unplugged", "The Eras Tour")
        val concert = low.any { CONCERT_FOLDER.containsMatchIn(it) } || CONCERT_RX.containsMatchIn(name.replace(Regex("[._]+"), " "))

        val bare = name.replace(SITE_RX, "")
        // season / episode: "S01E01", "1x01", "Season 1 Episode 1", then (TV folders or a show name before it) "Episode 5" / "E05" / "Ep 5"
        val folderSeason = segs.lastOrNull()?.let { Regex("(?i)^(?:season|series|s|staffel|saison|temporada)[\\s._-]*(\\d{1,2})$").find(it.trim()) }?.groupValues?.get(1)?.toInt()
        val anime = ANIME_RX.find(bare)
        val m = TV_RX.find(bare) ?: TV_RX2.find(bare) ?: TV_RX3.find(bare)
        val ep = if (m == null && anime == null) EP_RX.find(bare)?.takeIf { it.groupValues[1].isNotBlank() || tvFolder >= 0 || folderSeason != null } else null
        val pack = if (m == null && anime == null && ep == null) SEASON_RX.find(bare)?.takeIf { it.groupValues[1].isNotBlank() } else null
        val se = m?.groupValues?.get(2)?.toInt() ?: pack?.groupValues?.get(2)?.toInt() ?: if (ep != null || anime != null) (folderSeason ?: 1) else 0
        val epNo = m?.groupValues?.get(3)?.toInt() ?: ep?.groupValues?.get(2)?.toInt() ?: anime?.groupValues?.get(2)?.toInt() ?: 0
        val showPart = (m ?: ep ?: pack)?.groupValues?.get(1) ?: anime?.groupValues?.get(1)
        val isTv = m != null || ep != null || pack != null || anime != null
        when {
            mvFolder || (!isTv && tvFolder < 0 && (MV_RX.containsMatchIn(name) || concert)) -> {
                o.put("k", "mv"); o.put("pt", clean(name.replace(Regex("\\(\\d+ ?X ?\\d+\\)", RegexOption.IGNORE_CASE), "")))
                if (concert) o.put("concert", true)
                YEAR_RX.findAll(name).lastOrNull()?.takeIf { it.range.first > 0 }?.let { o.put("py", it.value.toInt()) }
            }
            isTv || tvFolder >= 0 -> {
                o.put("k", "tv")
                var show = showPart?.let { clean(it) } ?: ""
                if (show.isEmpty()) {
                    // only "S01E01.mkv" / "Episode 5.mkv": the show is the folder it's in (TV Shows/<Show>/Season 1/file → <Show>)
                    show = segs.asReversed().firstOrNull { f -> !SEASON_FOLDER.containsMatchIn(f.trim()) && !GENERIC_FOLDER.matches(f.trim()) }?.let { clean(it) } ?: ""
                }
                if (show.isEmpty() && !isTv) show = clean(name)
                val showYear = YEAR_RX.findAll(show).lastOrNull()?.takeIf { it.range.first > 0 }
                if (showYear != null) o.put("py", showYear.value.toInt())
                show = clean(YEAR_RX.replace(show, "")).ifEmpty { show }
                o.put("show", show.ifEmpty { clean(name) })
                if (se > 0) o.put("se", se); if (epNo > 0) o.put("ep", epNo)
                // the episode's own title, when the name has one after the number ("S01E02 - Bad Boy Comes Out")
                val after = (m ?: ep)?.let { bare.substring(it.range.last + 1) } ?: anime?.let { bare.substring(it.range.last + 1) } ?: ""
                o.put("pt", clean(after).ifEmpty { clean(name) })
            }
            else -> {
                o.put("k", "movie")
                var t = bare.replace(Regex("[._]+"), " ").replace(Regex("(?<=\\S)-+(?=\\S)"), " ")
                // a year is 1900..next year and not the first word ("2012", "1917" are titles; "Blade Runner 2049" isn't a year)
                val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
                val years = YEAR_RX.findAll(t).filter { it.range.first > 0 && it.value.toInt() <= maxYear }.toList()
                if (years.isNotEmpty()) {
                    val y = years.last()
                    o.put("py", y.value.toInt())
                    t = t.substring(0, y.range.first)
                }
                o.put("pt", clean(t).ifEmpty { clean(name) })
            }
        }
    }

    /**
     * Videos that aren't music videos (concert films, documentaries, specials…): accept a TMDB movie only when the
     * title matches the file name exactly (and the year, when the name has one) — random clips must stay unmatched.
     */
    private fun strictMovie(store: Store, o: JSONObject): JSONObject? {
        val name = Lyrics.cleanTitle(o.optString("n").substringBeforeLast('.').replace(Regex("\\[[^]]*]"), "")).replace(Regex("[._]+"), " ").trim()
        val y = YEAR_RX.findAll(name).lastOrNull()?.takeIf { it.range.first > 0 }
        val t = clean(if (y != null) name.substring(0, y.range.first) else name)
        if (t.length < 3) return null
        fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()
        val r = search(store, t, "movie", y?.value?.toInt() ?: 0)
        return (0 until r.length()).map { r.getJSONObject(it) }.firstOrNull { norm(it.optString("title")) == norm(t) }
    }

    /** Concert films are often on TMDB ("Taylor Swift: The Eras Tour"): accept a close title match (and the year, if known). */
    private fun concertMovie(store: Store, o: JSONObject): JSONObject? {
        val name = Lyrics.cleanTitle(o.optString("n").substringBeforeLast('.').replace(Regex("\\[[^]]*]"), "")).replace(Regex("[._]+"), " ").trim()
        val y = YEAR_RX.findAll(name).lastOrNull()?.takeIf { it.range.first > 0 }
        val t = clean(if (y != null) name.substring(0, y.range.first) else name).replace(Regex("\\s+[-–—]+\\s+"), " ")
        fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()
        val nt = norm(t); if (nt.length < 4) return null
        val r = search(store, t, "movie", y?.value?.toInt() ?: 0).let { if (it.length() == 0 && y != null) search(store, t, "movie") else it }
        return (0 until r.length()).map { r.getJSONObject(it) }.firstOrNull { c ->
            val ct = norm(c.optString("title")); val words = nt.split(' ').toSet()
            ct == nt || ct.split(' ').count { it in words } >= maxOf(2, (ct.split(' ').size * 0.75).toInt())
        }
    }

    /** Cache key: movies are per file; all episodes of a show share one match. */
    fun key(o: JSONObject): String? = when (o.optString("k")) {
        "movie" -> "f:" + o.getLong("i")
        "tv" -> "tv:" + o.optString("show").lowercase()
        "mv" -> "mv:" + o.getLong("i")
        else -> null
    }

    class NoKey : Exception("Add your free TMDB API key in Settings → API keys (themoviedb.org → Settings → API)")

    /** TMDB request with the Read Access Token (preferred) or the v3 API key from Settings. */
    private fun get(store: Store, path: String): JSONObject {
        val sep = if (path.contains('?')) "&" else "?"
        val token = store.tmdbToken(); val key = store.tmdbKey()
        if (token.isEmpty() && key.isEmpty()) throw NoKey()
        fun call(withKey: Boolean): Pair<Int, String> {
            val c = URL("https://api.themoviedb.org/3$path${sep}language=en-US" + if (withKey) "&api_key=${enc(key)}" else "").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            if (!withKey) c.setRequestProperty("Authorization", "Bearer $token")
            try {
                val code = c.responseCode
                return code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
            } finally { c.disconnect() }
        }
        var r = Api.retry {
            val x = if (token.isNotEmpty()) call(false) else call(true)
            if (x.first == 429) throw RateLimited(1000) else x
        }
        if (r.first == 401 && token.isNotEmpty() && key.isNotEmpty()) r = call(true)
        if (r.first == 401) throw Exception("TMDB rejected the key — check it in Settings → API keys")
        if (r.first !in 200..299) throw Exception("TMDB error ${r.first}")
        return JSONObject(r.second)
    }

    /** Quick check for Settings → API keys → Test. */
    fun test(store: Store): String = try { get(store, "/configuration"); "TMDB key works ✓" } catch (e: Exception) { e.message ?: "TMDB key test failed" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Raw search — used by the "Fix match" dialog. */
    fun search(store: Store, query: String, type: String, year: Int = 0): JSONArray {
        val out = JSONArray()
        val q = enc(query)
        val path = if (type == "tv") "/search/tv?query=$q" + (if (year > 0) "&first_air_date_year=$year" else "")
                   else "/search/movie?query=$q" + (if (year > 0) "&primary_release_year=$year" else "")
        val res = get(store, path).optJSONArray("results") ?: return out
        for (k in 0 until minOf(res.length(), 12)) out.put(slim(res.getJSONObject(k), type))
        return out
    }

    fun slim(r: JSONObject, type: String): JSONObject {
        // already slim (search() results are): slimming again would blank the poster, backdrop, year and show title
        if (r.has("type") && r.has("poster") && !r.has("poster_path")) return r
        val date = if (type == "tv") r.optString("first_air_date") else r.optString("release_date")
        return JSONObject()
            .put("id", r.optInt("id"))
            .put("type", type)
            .put("title", if (type == "tv") r.optString("name") else r.optString("title"))
            .put("year", date.take(4))
            .put("poster", r.optString("poster_path").takeIf { it != "null" } ?: "")
            .put("backdrop", r.optString("backdrop_path").takeIf { it != "null" } ?: "")
            .put("overview", r.optString("overview"))
            .put("rating", r.optDouble("vote_average", 0.0))
    }

    /** Best automatic match for one record (null = nothing found). */
    fun autoMatch(store: Store, o: JSONObject): JSONObject? {
        val kind = o.optString("k")
        if (kind == "mv") return if (o.optBoolean("concert")) concertMovie(store, o) ?: MvDb.match(o) else MvDb.match(o) ?: strictMovie(store, o)
        return if (kind == "tv") {
            val show = o.optString("show"); val y = o.optInt("py", 0)
            var r = if (y > 0) search(store, show, "tv", y).optJSONObject(0) else null
            if (r == null) r = search(store, show, "tv").optJSONObject(0)
            // leftover words ("the office us", "Show Complete"): try dropping up to two from the end
            var words = show.split(' ').filter { it.isNotBlank() }
            while (r == null && words.size >= 2 && words.size > show.split(' ').size - 2) {
                words = words.dropLast(1)
                r = search(store, words.joinToString(" "), "tv").optJSONObject(0)
            }
            r
        } else {
            val t = o.optString("pt"); val y = o.optInt("py", 0)
            var r = if (y > 0) search(store, t, "movie", y).optJSONObject(0) else null
            if (r == null) r = search(store, t, "movie").optJSONObject(0)
            // leftover words at the end (release group, "Directors Cut"…): try dropping up to two of them
            var words = t.split(' ').filter { it.isNotBlank() }
            while (r == null && words.size >= 4) {
                words = words.dropLast(1)
                r = search(store, words.joinToString(" "), "movie").optJSONObject(0)
                if (words.size <= t.split(' ').size - 2) break
            }
            r
        }
    }

    /**
     * Full details for the detail page and the catalog PDFs — fetched once and stored in the match ("full": true):
     * genres, runtime / seasons, tagline, cast, director / creators, writers, release dates, age rating, studios / networks,
     * countries, languages, status.
     */
    fun details(store: Store, m: JSONObject): JSONObject {
        val tv = m.optString("type") == "tv"
        val d = get(store, "/${if (tv) "tv" else "movie"}/${m.optInt("id")}?append_to_response=" + if (tv) "aggregate_credits,content_ratings" else "credits,release_dates")
        fun names(a: JSONArray?, key: String = "name") = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString(key)?.takeIf { s -> s.isNotBlank() } }
        m.put("genres", names(d.optJSONArray("genres")).joinToString(", "))
        m.put("tagline", d.optString("tagline"))
        m.put("status", d.optString("status"))
        m.put("lang", d.optString("original_language"))
        m.put("countries", names(d.optJSONArray("production_countries")).joinToString(", "))
        m.put("studios", names(d.optJSONArray("production_companies")).take(4).joinToString(", "))
        d.optString("overview").takeIf { it.isNotBlank() }?.let { m.put("overview", it) }
        d.optDouble("vote_average", -1.0).takeIf { it >= 0 }?.let { m.put("rating", it) }
        m.put("votes", d.optInt("vote_count"))
        val cast = JSONArray()
        if (tv) {
            m.put("seasons", d.optInt("number_of_seasons")); m.put("episodes", d.optInt("number_of_episodes"))
            m.put("first", d.optString("first_air_date")); m.put("last", d.optString("last_air_date"))
            m.put("runtime", d.optJSONArray("episode_run_time")?.optInt(0) ?: 0)
            m.put("creators", names(d.optJSONArray("created_by")).joinToString(", "))
            m.put("networks", names(d.optJSONArray("networks")).joinToString(", "))
            val c = d.optJSONObject("aggregate_credits")?.optJSONArray("cast")
            if (c != null) for (k in 0 until minOf(c.length(), 20)) { val p = c.getJSONObject(k)
                val role = p.optJSONArray("roles")?.optJSONObject(0)?.optString("character") ?: ""
                cast.put(JSONObject().put("n", p.optString("name")).put("c", role)) }
            m.put("cert", d.optJSONObject("content_ratings")?.optJSONArray("results")?.let { r -> (0 until r.length()).map { r.getJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }?.optString("rating") } ?: "")
        } else {
            m.put("runtime", d.optInt("runtime"))
            m.put("released", d.optString("release_date"))
            m.put("budget", d.optLong("budget")); m.put("revenue", d.optLong("revenue"))
            val cr = d.optJSONObject("credits")
            val c = cr?.optJSONArray("cast")
            if (c != null) for (k in 0 until minOf(c.length(), 20)) { val p = c.getJSONObject(k); cast.put(JSONObject().put("n", p.optString("name")).put("c", p.optString("character"))) }
            val crew = cr?.optJSONArray("crew")
            val crewL = if (crew == null) emptyList() else (0 until crew.length()).map { crew.getJSONObject(it) }
            m.put("director", crewL.filter { it.optString("job") == "Director" }.map { it.optString("name") }.distinct().joinToString(", "))
            m.put("writers", crewL.filter { it.optString("department") == "Writing" }.map { it.optString("name") }.distinct().take(4).joinToString(", "))
            m.put("cert", d.optJSONObject("release_dates")?.optJSONArray("results")?.let { r -> (0 until r.length()).map { r.getJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }
                ?.optJSONArray("release_dates")?.let { ds -> (0 until ds.length()).map { ds.getJSONObject(it).optString("certification") }.firstOrNull { it.isNotBlank() } } } ?: "")
        }
        m.put("cast", cast)
        // fill in anything a match is missing (matches saved by older versions lost these)
        fun fill(k: String, v: String) { if (m.optString(k).isBlank() && v.isNotBlank() && v != "null") m.put(k, v) }
        fill("poster", d.optString("poster_path")); fill("backdrop", d.optString("backdrop_path"))
        fill("title", if (tv) d.optString("name") else d.optString("title"))
        fill("year", (if (tv) d.optString("first_air_date") else d.optString("release_date")).take(4))
        if (m.optDouble("rating", 0.0) <= 0) d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { m.put("rating", it) }
        m.put("detailed", true); m.put("full", true)
        return m
    }

    /**
     * Extras for the detail page, fetched when it opens and kept for a week: top-billed cast with photos, trailers and
     * clips (YouTube), and the most popular backdrops and posters (TMDB's "Media" section).
     * {cast:[{n,c,p}], videos:[{key,name,type}], backdrops:[path], posters:[path]}
     */
    fun extras(store: Store, type: String, id: Int): JSONObject? {
        if (id <= 0) return null
        val dir = File(store.tmdbImgDir.parentFile, "tmdbx").apply { mkdirs() }
        val f = File(dir, "${type}_$id.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) try { return JSONObject(f.readText()) } catch (_: Exception) {}
        val tv = type == "tv"
        val d = try { get(store, "/${if (tv) "tv" else "movie"}/$id?append_to_response=${if (tv) "aggregate_credits" else "credits"},videos,images&include_image_language=en,null") }
            catch (_: Exception) { return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { null } else null }
        val cast = JSONArray()
        val c = (if (tv) d.optJSONObject("aggregate_credits") else d.optJSONObject("credits"))?.optJSONArray("cast")
        if (c != null) for (k in 0 until minOf(c.length(), 30)) { val p = c.getJSONObject(k)
            val role = if (tv) p.optJSONArray("roles")?.optJSONObject(0)?.optString("character") ?: "" else p.optString("character")
            val eps = if (tv) p.optInt("total_episode_count") else 0
            cast.put(JSONObject().put("n", p.optString("name")).put("c", role).put("p", p.optString("profile_path").takeIf { it != "null" } ?: "").put("eps", eps)) }
        val vids = JSONArray()
        d.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
            val l = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
                .sortedWith(compareBy<JSONObject> { when (it.optString("type")) { "Trailer" -> 0; "Teaser" -> 1; "Clip" -> 2; "Featurette" -> 3; else -> 4 } }.thenByDescending { it.optBoolean("official") })
            l.take(20).forEach { v -> vids.put(JSONObject().put("key", v.optString("key")).put("name", v.optString("name")).put("type", v.optString("type"))) }
        }
        fun imgs(k: String) = JSONArray().also { out -> d.optJSONObject("images")?.optJSONArray(k)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { it.optDouble("vote_average", 0.0) * 1000 + it.optInt("vote_count") }.take(20).forEach { out.put(it.optString("file_path")) } } }
        val o = JSONObject().put("cast", cast).put("videos", vids).put("backdrops", imgs("backdrops")).put("posters", imgs("posters"))
        try { f.writeText(o.toString()) } catch (_: Exception) {}
        return o
    }

    /** One TV season's episodes: {"<episode no>": {name, overview, still, air, runtime, rating}}. */
    fun season(store: Store, tvId: Int, season: Int): JSONObject {
        val d = get(store, "/tv/$tvId/season/$season")
        val out = JSONObject()
        val a = d.optJSONArray("episodes") ?: return out
        for (k in 0 until a.length()) { val e = a.getJSONObject(k)
            out.put(e.optInt("episode_number").toString(), JSONObject().put("name", e.optString("name")).put("overview", e.optString("overview"))
                .put("still", e.optString("still_path").takeIf { it != "null" } ?: "").put("air", e.optString("air_date")).put("runtime", e.optInt("runtime"))
                .put("rating", e.optDouble("vote_average", 0.0))) }
        return out
    }

    /** Image path "/abc.jpg" at size "w342" → local cached file (downloads if needed). */
    fun image(store: Store, size: String, path: String): File? {
        if (path.isBlank()) return null
        val f = File(store.tmdbImgDir, size + "_" + path.trimStart('/'))
        if (f.exists()) return f
        return if (Net.download("https://image.tmdb.org/t/p/$size/${path.trimStart('/')}", f)) f else null
    }
}
