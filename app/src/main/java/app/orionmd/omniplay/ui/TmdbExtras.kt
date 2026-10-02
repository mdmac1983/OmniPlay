package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Img
import app.orionmd.omniplay.Match
import app.orionmd.omniplay.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The TMDB sections of a movie / TV page: Overview, Top billed cast (with photos) and Media — most popular, videos
 * (trailers, teasers, clips → YouTube), backdrops and posters (tap one for full screen).
 */
@Composable
fun TmdbSections(st: AppState, m: Match?) {
    if (m == null) return
    val id = m.json.optInt("id"); val type = m.json.optString("type").ifEmpty { "movie" }
    var x by remember(id) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(id, st.online) { if (x == null) x = withContext(Dispatchers.IO) { try { Tmdb.extras(st.store, type, id) } catch (_: Exception) { null } } }
    var full by remember { mutableStateOf<Img?>(null) }

    if (m.overview.isNotEmpty() || m.tagline.isNotEmpty()) {
        SectionHeader("Overview")
        if (m.tagline.isNotEmpty()) Text(m.tagline, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = C.mu, modifier = Modifier.padding(bottom = 6.dp))
        if (m.overview.isNotEmpty()) Text(m.overview, fontSize = 15.sp, lineHeight = 22.sp)
        val j = m.json
        val crew = listOf("Director" to j.optString("director"), "Writers" to j.optString("writers"), "Created by" to j.optString("creators")).filter { it.second.isNotEmpty() }
        if (crew.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            crew.forEach { (k, v) -> Column(Modifier.weight(1f)) { Text(v, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Muted(k, size = 12.sp) } }
        }
    }
    val e = x
    if (e == null) { if (!st.online) Muted("Cast and media show here when you're online.", Modifier.padding(top = 12.dp), 12.sp); return }

    // ---------- top billed cast ----------
    val cast = e.optJSONArray("cast")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    if (cast.isNotEmpty()) {
        var all by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionHeader("Top billed cast") }
            if (cast.size > 9) Text(if (all) "Less" else "Full cast (${cast.size})", color = C.ac, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp).clickable { all = !all })
        }
        if (!all) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cast.take(9)) { p -> CastCard(p) }
        } else Column { cast.forEach { p ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(p.optString("p").takeIf { it.isNotEmpty() }?.let { Img.Poster("w185", it) }, p.optString("n"), Modifier.size(44.dp).clip(CircleShape), px = 160, corner = 22.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(p.optString("n"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Muted(p.optString("c") + (p.optInt("eps").takeIf { it > 0 }?.let { " · $it episodes" } ?: ""), size = 12.sp)
                }
            }
        } }
    }

    // ---------- media ----------
    fun list(k: String) = e.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.get(it) } }.orEmpty()
    val vids = list("videos").map { it as JSONObject }; val backs = list("backdrops").map { it.toString() }; val posts = list("posters").map { it.toString() }
    if (vids.isNotEmpty() || backs.isNotEmpty() || posts.isNotEmpty()) {
        SectionHeader("Media")
        var tab by remember { mutableStateOf("popular") }
        Seg(listOf("popular" to "Most popular", "videos" to "Videos ${vids.size}", "backdrops" to "Backdrops ${backs.size}", "posters" to "Posters ${posts.size}"), tab) { tab = it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                "videos" -> items(vids) { v -> VideoCard(st, v) }
                "backdrops" -> items(backs) { b -> Thumb(Img.Poster("w780", b), "", Modifier.height(150.dp).aspectRatio(16f / 9f).clickable { full = Img.Poster("original", b) }, px = 400, corner = 10.dp) }
                "posters" -> items(posts) { b -> Thumb(Img.Poster("w342", b), "", Modifier.height(210.dp).aspectRatio(2f / 3f).clickable { full = Img.Poster("original", b) }, px = 400, corner = 10.dp) }
                else -> {
                    vids.firstOrNull()?.let { v -> item { VideoCard(st, v) } }
                    backs.firstOrNull()?.let { b -> item { Thumb(Img.Poster("w780", b), "", Modifier.height(150.dp).aspectRatio(16f / 9f).clickable { full = Img.Poster("original", b) }, px = 400, corner = 10.dp) } }
                    posts.firstOrNull()?.let { b -> item { Thumb(Img.Poster("w342", b), "", Modifier.height(150.dp).aspectRatio(2f / 3f).clickable { full = Img.Poster("original", b) }, px = 400, corner = 10.dp) } }
                    vids.drop(1).take(2).forEach { v -> item { VideoCard(st, v) } }
                    backs.drop(1).take(3).forEach { b -> item { Thumb(Img.Poster("w780", b), "", Modifier.height(150.dp).aspectRatio(16f / 9f).clickable { full = Img.Poster("original", b) }, px = 400, corner = 10.dp) } }
                }
            }
        }
    }
    full?.let { img ->
        Dialog({ full = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = null }, contentAlignment = Alignment.Center) {
                Thumb(img, "", Modifier.fillMaxWidth(), px = 1024, corner = 0.dp, contentScale = ContentScale.Fit, placeholder = false)
            }
        }
    }
}

@Composable
private fun CastCard(p: JSONObject) {
    Column(Modifier.width(104.dp).clip(RoundedCornerShape(10.dp)).background(C.s2)) {
        Thumb(p.optString("p").takeIf { it.isNotEmpty() }?.let { Img.Poster("w185", it) }, p.optString("n"), Modifier.fillMaxWidth().aspectRatio(2f / 3f), px = 300, corner = 0.dp, fontSize = 30.sp)
        Column(Modifier.padding(8.dp)) {
            Text(p.optString("n"), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            Text(p.optString("c"), color = C.mu, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            p.optInt("eps").takeIf { it > 0 }?.let { Text("$it episodes", color = C.mu, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun VideoCard(st: AppState, v: JSONObject) {
    val key = v.optString("key")
    Column(Modifier.width(240.dp).clickable { st.openUrl("https://www.youtube.com/watch?v=$key") }) {
        Box(Modifier.fillMaxWidth().height(135.dp), contentAlignment = Alignment.Center) {
            Thumb(Img.Cover("https://img.youtube.com/vi/$key/hqdefault.jpg"), v.optString("name"), Modifier.fillMaxSize(), px = 400, corner = 10.dp)
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) { Icon(Ic.play, "Play", tint = Color.White) }
        }
        Text(v.optString("name"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Muted(v.optString("type"), size = 11.sp)
    }
}
