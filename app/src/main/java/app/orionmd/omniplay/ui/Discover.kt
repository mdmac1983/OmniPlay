package app.orionmd.omniplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*

/** Discover: search Deezer/iTunes and preview tracks (30-second clips). Full tracks come from your own files/clouds. */
@Composable
fun DiscoverScreen(st: AppState, pad: PaddingValues) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(st, "Discover")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(q, { q = it }, "Search music to preview…", Modifier.weight(1f))
            IconBtn(Ic.search, { if (q.isNotBlank()) st.searchCatalog(q.trim()) }, Modifier.padding(start = 4.dp))
        }
        Muted("Previews are 30-second clips from Deezer/iTunes. Your library and cloud files play in full.", Modifier.padding(horizontal = 16.dp), 12.sp)
        val res = st.catalog
        LazyColumn(contentPadding = contentPad(pad), modifier = Modifier.weight(1f)) {
            if (res == null) item { Empty("Search for a song, artist or album to preview.") }
            else if (res.isEmpty()) item { Empty("Nothing found.") }
            else items(res) { o ->
                Row(Modifier.fillMaxWidth().clickable { st.playPreview(o) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(o.optString("cover").takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, o.optString("t"), Modifier.size(46.dp), px = 160)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(o.optString("t"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(listOf(o.optString("a"), o.optString("al")).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
                    }
                    IconBtn(Ic.play, { st.playPreview(o) }, size = 36.dp, iconSize = 22.dp)
                }
            }
        }
    }
}

/** Browse a cloud account's folders and pick one to add to the library. */
@Composable
fun CloudBrowsePage(st: AppState, p: Page.CloudBrowse, pad: PaddingValues) {
    val acct = remember(p.acctId) { app.orionmd.omniplay.cloud.Cloud.account(p.acctId) }
    var files by remember(p.folderId) { mutableStateOf<List<app.orionmd.omniplay.cloud.CloudFile>?>(null) }
    var err by remember(p.folderId) { mutableStateOf<String?>(null) }
    var syncOn by remember { mutableStateOf(true) }
    LaunchedEffect(p.folderId) {
        if (acct == null) { err = "Account missing"; return@LaunchedEffect }
        try { files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.orionmd.omniplay.cloud.Cloud.list(acct, p.folderId) }.filter { it.isDir } }
        catch (e: Exception) { err = e.message ?: "Couldn't list this folder" }
    }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st); Text(if (p.path.isEmpty()) (acct?.label ?: "Cloud") else p.path.substringAfterLast('/'), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Muted("Pick a folder to add, or open one to go deeper.", Modifier.padding(vertical = 6.dp), 12.sp)
            Check(syncOn, "Keep in sync (check for new files in the background)") { syncOn = it }
            Btn("Add this folder", { st.addCloudFolder(p.acctId, p.folderId, p.path, syncOn); st.pages.clear(); st.tab = "home"; st.say("Added — scanning…") }, icon = Ic.add, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth())
            SectionHeader("Subfolders")
        }
        err?.let { item { Muted(it, Modifier.padding(8.dp)) } }
        if (files == null && err == null) item { Progress(null) }
        items(files ?: emptyList(), key = { it.id }) { f ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.CloudBrowse(p.provider, p.acctId, f.id, if (p.path.isEmpty()) f.name else "${p.path}/${f.name}")) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.folder, null, tint = C.ac); Spacer(Modifier.width(12.dp)); Text(f.name, fontWeight = FontWeight.SemiBold)
            }
        }
        if (files?.isEmpty() == true) item { Muted("No subfolders here — add this folder above.", Modifier.padding(8.dp)) }
    }
}

@Composable private fun Icon(ic: androidx.compose.ui.graphics.vector.ImageVector, cd: String?, tint: androidx.compose.ui.graphics.Color) = androidx.compose.material3.Icon(ic, cd, tint = tint)
