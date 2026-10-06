package app.orionmd.omniplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/** Settings → Saved on this device: total, and a button to the full page. */
@Composable
fun StorageSummary(st: AppState) {
    val sizes by produceState<Map<String, Pair<Int, Long>>?>(null) { value = withContext(Dispatchers.IO) { Storage.scan(st.ctx) } }
    Muted("Pictures, info, lyrics and subtitles Media Hub has saved so everything shows offline. See what's there and clear any of it.", Modifier.padding(bottom = 8.dp), 12.sp)
    Text(sizes?.let { "${bytes(it.values.sumOf { v -> v.second })} saved" } ?: "Counting…", fontWeight = FontWeight.SemiBold)
    Btn("See & clear saved data", { st.open(Page.Storage) }, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(top = 8.dp))
}

/** Everything saved on the device, per category, with View and Clear. */
@Composable
fun StoragePage(st: AppState, pad: PaddingValues) {
    var tick by remember { mutableIntStateOf(0) }
    val sizes by produceState<Map<String, Pair<Int, Long>>?>(null, tick) { value = withContext(Dispatchers.IO) { Storage.scan(st.ctx) } }
    val clearable = Storage.CATS.filter { it.clearNote != null }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Text("Saved on this device", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Muted("Kept in Media Hub's private storage, encrypted — file managers and other apps can't open it. Downloads you saved to the phone's Music / Movies / Pictures folders aren't counted here.",
                Modifier.padding(top = 4.dp, bottom = 10.dp), 13.sp)
            val total = sizes?.values?.sumOf { it.second }
            Tiles(listOf((if (total != null) bytes(total) else "…") to "Saved in total", (sizes?.let { s -> bytes(clearable.sumOf { s[it.key]?.second ?: 0 }) } ?: "…") to "Can be cleared"))
            Btn("Clear all saved pictures & info", {
                st.dialog = Dlg.Confirm("Clear everything that can be cleared?", "Pictures, song and movie info, lyrics, subtitles and temporary files are removed (your Fix match choices, downloads, logos and library lists stay). It's all fetched again when you're online.", true) {
                    st.clearSaved(clearable.map { it.key }) { tick++ } }
            }, kind = BtnKind.Danger, small = true, modifier = Modifier.padding(top = 10.dp))
        }
        items(Storage.CATS, key = { it.key }) { c ->
            val v = sizes?.get(c.key)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(c.title, fontWeight = FontWeight.SemiBold); Muted(c.desc, size = 12.sp) }
                    Text(if (v == null) "…" else bytes(v.second), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
                if (v != null) Muted("${fmt(v.first)} file${if (v.first == 1) "" else "s"}", Modifier.padding(top = 2.dp), 12.sp)
                if (v != null && v.first > 0) BtnRow(Modifier.padding(top = 8.dp)) {
                    if (c.view.isNotEmpty()) Btn("View", { st.open(Page.SavedFiles(c.key)) }, kind = BtnKind.Ghost, small = true)
                    if (c.key == "offline") Btn("Manage", { st.open(Page.Downloads) }, kind = BtnKind.Ghost, small = true)
                    c.clearNote?.let { note -> Btn("Clear", { st.dialog = Dlg.Confirm("Clear ${c.title.lowercase()}?", "Frees ${bytes(v.second)}. $note", true) { st.clearSaved(listOf(c.key)) { tick++ } } }, kind = BtnKind.Danger, small = true) }
                }
            }
        }
    }
}

/** Clears categories, then reloads what the screens show. */
fun AppState.clearSaved(keys: List<String>, done: () -> Unit) {
    scope.launch {
        withContext(Dispatchers.IO) { keys.forEach { k -> try { Storage.clear(ctx, store, k) } catch (_: Exception) {} } }
        if ("songinfo" in keys) { meta = parseMeta(store.musicMeta()) }
        loadLibrary(); ImageLoader.bump(); countLyrics()
        say("Cleared"); done()
    }
}

/** One category: pictures as a grid, lyrics / subtitles as a list (tap to read), info as the list of titles. */
@Composable
fun SavedFilesPage(st: AppState, key: String, pad: PaddingValues) {
    val c = Storage.cat(key)
    val files by produceState<List<File>?>(null, key) { value = withContext(Dispatchers.IO) { c.files(st.ctx).sortedByDescending { it.lastModified() } } }
    val names = remember(st.allItems, st.tmdb, st.meta) { st.savedNames() }
    fun nameOf(f: File): String = names[f.name] ?: f.name.substringBefore('.').substringBefore('_').toLongOrNull()?.let { names[it.toString()] } ?: f.name
    val list = files
    when (c.view) {
        "images" -> LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { BackBar(st) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(c.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Muted(if (list == null) "Reading…" else "${fmt(list.size)} files · ${bytes(list.sumOf { it.length() })} · newest first", size = 12.sp)
                }
            }
            items(list ?: emptyList(), key = { it.name }) { f ->
                Column(Modifier.clickable { st.dialog = Dlg.Message(nameOf(f), "${bytes(f.length())} · saved ${java.text.DateFormat.getDateInstance().format(java.util.Date(f.lastModified()))}") }) {
                    SavedImage(f, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.s2))
                    Text(nameOf(f), fontSize = 10.sp, color = C.mu, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        "titles" -> {
            val rows = remember(st.tmdb, st.meta, key) { st.savedTitles(key) }
            LazyColumn(contentPadding = contentPad(pad)) {
                item { BackBar(st) }
                item { Text(c.title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Muted("${fmt(rows.size)} saved", Modifier.padding(bottom = 8.dp), 12.sp) }
                items(rows) { (t, sub) -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub.isNotEmpty()) Muted(sub, size = 12.sp) } }
            }
        }
        else -> LazyColumn(contentPadding = contentPad(pad)) {
            item { BackBar(st) }
            item { Text(c.title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Muted(if (list == null) "Reading…" else "${fmt(list.size)} files", Modifier.padding(bottom = 8.dp), 12.sp) }
            items(list ?: emptyList(), key = { it.name }) { f ->
                Row(Modifier.fillMaxWidth().clickable { st.dialog = Dlg.Message(nameOf(f), Storage.text(f).take(6000)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nameOf(f), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(bytes(f.length()), Modifier.padding(start = 8.dp), 12.sp)
                }
            }
        }
    }
}

/** Saved file name (or item id) → what it belongs to: song / video titles, movie & show names for TMDB pictures. */
private fun AppState.savedNames(): Map<String, String> {
    val m = HashMap<String, String>()
    allItems.forEach { x -> m[x.i.toString()] = if (x.c == 2) vtitle(x) else listOf(x.artist, x.title).filter { it.isNotBlank() }.joinToString(" – ") }
    tmdb.forEach { (_, o) ->
        val t = o.optString("title"); if (t.isBlank()) return@forEach
        listOf("poster", "backdrop").forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.trimStart('/')?.let { p -> listOf("w92", "w185", "w300", "w342", "w780", "original").forEach { s -> m["${s}_$p"] = t } } }
    }
    meta.forEach { (id, mm) -> mm.cv?.let { u -> m[MusicMeta.coverFile(store, u).name] = m[id.toString()] ?: "Album cover" } }
    return m
}

/** The saved info as a readable list. */
private fun AppState.savedTitles(key: String): List<Pair<String, String>> = if (key == "tmdbinfo")
    tmdb.filter { (k, v) -> !k.contains('#') && !v.optBoolean("none") }.values.map { o ->
        o.optString("title").ifBlank { "(untitled)" } to listOf(if (o.optString("type") == "tv") "TV" else "Movie", o.optString("year"), if (o.optBoolean("manual")) "your match" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }.sortedBy { it.first.lowercase() }
else allItems.filter { it.c == 3 }.mapNotNull { x -> meta[x.i]?.let { mm -> listOf(x.artist, x.title).filter { it.isNotBlank() }.joinToString(" – ") to listOf(mm.g ?: "", if (mm.y > 0) mm.y.toString() else "").filter { it.isNotBlank() }.joinToString(" · ") } }
    .sortedBy { it.first.lowercase() }

@Composable
private fun SavedImage(f: File, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = Vault.readBytes(f) ?: return@withContext null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var s = 1; while (o.outWidth / (s * 2) >= 200 && o.outHeight / (s * 2) >= 200) s *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
                    ?: BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()   // logos are plain files
            } catch (_: Exception) { null }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}
