package app.orionmd.omniplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import app.orionmd.omniplay.R
import org.json.JSONObject

/**
 * Dashboard: what's in the library, the two "Sync all" jobs (music / video data saved for offline), continue watching,
 * recently played, recently added, and podcast episodes saved for offline.
 */
@Composable
fun DashboardScreen(st: AppState, pad: PaddingValues) {
    st.prefGen
    val songs = remember(st.allItems) { st.songs() }
    val vids = remember(st.allItems) { st.videos() }
    val albums = remember(songs) { songs.map { it.albKey }.distinct().size }
    val artists = remember(songs) { songs.map { it.akey }.distinct().size }
    val movies = remember(vids) { vids.count { it.k == "movie" } }
    val shows = remember(vids) { vids.filter { it.k == "tv" }.map { it.show?.lowercase() }.distinct().size }
    val withArt = remember(songs, st.meta) { songs.count { st.meta[it.i]?.cv != null } }
    val matched = remember(vids, st.tmdb) { vids.count { st.match(it) != null } }
    val eps = st.offlineEpisodes()
    val most = remember(st.plays, songs) { val byId = songs.associateBy { it.i }; st.plays.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(15) }
    val recentSongs = remember(songs) { songs.sortedByDescending { it.cr }.take(15) }
    val recentVids = remember(vids) { vids.sortedByDescending { it.cr }.take(15) }
    val cont = st.continueWatching()

    LazyColumn(contentPadding = contentPad(pad)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_emblem), null, Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("OmniPlay", fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Muted(if (st.online) "Online" else "Offline — playing from the device", size = 12.sp)
                }
                IconBtn(Ic.sync, { st.sync() })
            }
        }
        if (!Activation.isLicensed(st.store)) item {
            val d = Activation.trialDaysLeft(st.store)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.key, null, tint = if (d <= 3) C.warn else C.ac)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Free trial — $d day${if (d == 1) "" else "s"} left", fontWeight = FontWeight.SemiBold)
                        Muted("Buy an activation key to keep OmniPlay after the trial.", size = 12.sp)
                    }
                    Btn("Buy key", { st.openUrl(Activation.BUY_URL) }, small = true)
                }
                Text("Have a key? Enter it", color = C.ac, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable {
                    st.dialog = Dlg.TextInput("Activation key", "Key", "") { k -> val (_, m) = Activation.activate(st.store, k); st.say(m); st.prefGen++ } })
            }
        }
        item {
            Tiles(listOf(fmt(songs.size) to "Songs", fmt(albums) to "Albums", fmt(artists) to "Artists", fmt(vids.size) to "Videos",
                fmt(movies) to "Movies", fmt(shows) to "TV shows", fmt(st.dl.count { it.value }) to "Saved offline", fmt(eps.size) to "Podcast episodes saved"))
        }

        // ---------- sync all ----------
        item { SectionHeader("Sync for offline") }
        item {
            SyncCard(st, Ic.music, "Music", st.sync.music,
                "Album art, song info (genre, year, length), album details and lyrics for all ${fmt(songs.size)} songs.",
                "${fmt(withArt)} with online art · ${if (st.lyricsSaved >= 0) fmt(st.lyricsSaved) else "…"} lyrics saved", st.sync.musicAt,
                onRun = { st.syncMusic() })
            SyncCard(st, Ic.movie, "Videos", st.sync.video,
                "TMDB details, posters, backdrops, cast, episode info" + (if (st.store.secret("wyzieKey").isNotEmpty()) " and subtitles" else " (add a Wyzie key for subtitles)") + " for all ${fmt(vids.size)} videos.",
                "${fmt(matched)} of ${fmt(vids.size)} matched", st.sync.videoAt,
                onRun = { st.syncVideos() })
            Row(Modifier.padding(vertical = 4.dp)) {
                Btn("Sync everything", { st.syncAll() }, icon = Ic.download, small = true)
                if (st.sync.music != null || st.sync.video != null) { Spacer(Modifier.width(8.dp)); Btn("Stop", { st.sync.cancel = true }, kind = BtnKind.Ghost, small = true, icon = Ic.stop) }
            }
            if (!st.online) Muted("You're offline — sync runs when you're connected. Everything already synced shows offline.", Modifier.padding(top = 4.dp), 12.sp)
        }

        // ---------- continue watching ----------
        if (cont.isNotEmpty()) {
            item { SectionHeader("Continue watching") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cont, key = { it.first.i }) { (x, frac) ->
                        Column(Modifier.width(120.dp).clickable { st.openVideo(x, listOf(x)) }) {
                            Thumb(st.posterReq(x), st.vtitle(x), Modifier.fillMaxWidth().aspectRatio(2f / 3f), px = 300)
                            Progress(frac)
                            Text(st.vtitle(x), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        // ---------- recently played / most played ----------
        if (most.isNotEmpty()) {
            item { SectionHeader("Most played") }
            item { SongStrip(st, most) }
        }
        if (recentSongs.isNotEmpty()) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionHeader("Recently added music") }; Text("Shuffle all", color = C.ac, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp).clickable { st.playList(songs.shuffled(), 0) }) } }
            item { SongStrip(st, recentSongs) }
        }
        if (recentVids.isNotEmpty()) {
            item { SectionHeader("Recently added videos") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recentVids, key = { it.i }) { x ->
                        Column(Modifier.width(110.dp).clickable { if (x.k == "tv") st.open(Page.Show(st.mkey(x) ?: "")) else st.open(Page.Movie(x)) }) {
                            Thumb(st.posterReq(x), st.vtitle(x), Modifier.fillMaxWidth().aspectRatio(2f / 3f), px = 300)
                            Text(st.vtitle(x), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        // ---------- podcasts saved offline ----------
        if (eps.isNotEmpty()) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionHeader("Podcasts saved offline") }; Text("All podcasts", color = C.ac, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp).clickable { st.open(Page.Podcasts) }) } }
            items(eps.take(5), key = { "pe" + it.optLong("id") }) { o -> OfflineEpisodeRow(st, o, eps) }
        }

        // ---------- shortcuts ----------
        item { SectionHeader("Shortcuts") }
        item {
            BtnRow {
                Btn("Podcasts", { st.open(Page.Podcasts) }, icon = Ic.podcast, small = true, kind = BtnKind.Ghost)
                Btn("Offline", { st.open(Page.Downloads) }, icon = Ic.offline, small = true, kind = BtnKind.Ghost)
                Btn("Sound", { st.soundOpen = true }, icon = Ic.tune, small = true, kind = BtnKind.Ghost)
                Btn("Add music or video", { st.tab = "set"; st.openSettingsCat("sources") }, icon = Ic.add, small = true, kind = BtnKind.Ghost)
            }
        }
        if (st.allItems.isEmpty()) item { Empty("Your library is empty.\nAdd this device, a folder or a cloud account to get started.", "Open Settings") { st.tab = "set"; st.openSettingsCat("sources") } }
    }
}

@Composable
private fun SyncCard(st: AppState, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, prog: SyncProg?, desc: String, status: String, at: Long, onRun: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.ac)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("$title — sync all", fontWeight = FontWeight.SemiBold)
                Muted(status + " · last sync ${ago(at)}", size = 12.sp)
            }
            if (prog == null) Btn("Sync", onRun, small = true)
        }
        Muted(desc, Modifier.padding(top = 6.dp), 12.sp)
        if (prog != null) {
            Spacer(Modifier.height(8.dp))
            Progress(if (prog.total > 0) prog.done.toFloat() / prog.total else null)
            Muted("${prog.step} · ${fmt(prog.done)} / ${fmt(prog.total)}", Modifier.padding(top = 4.dp), 12.sp)
        }
    }
}

@Composable
private fun SongStrip(st: AppState, list: List<Item>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.i }) { x ->
            Column(Modifier.width(110.dp).clickable { st.playItem(x, list) }) {
                Thumb(st.artReq(x), x.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 300, fallback = st.artAlt(x))
                Text(x.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Muted(x.artist, size = 11.sp)
            }
        }
    }
}

@Composable
internal fun OfflineEpisodeRow(st: AppState, o: JSONObject, all: List<JSONObject>) {
    val s = o.optJSONObject("show") ?: JSONObject()
    val img = o.optString("image").ifEmpty { s.optString("image") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ListRow(img.takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, o.optString("title"), o.optString("title"),
                listOf(s.optString("title"), if (o.optLong("duration") > 0) mmss(o.optLong("duration")) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                { st.playOfflineEpisodes(all, all.indexOf(o)) }, highlight = st.player.track?.optLong("i") == o.optLong("id"), downloaded = true)
        }
        IconBtn(Ic.trash, { st.dialog = Dlg.Confirm("Remove this episode from the device?", o.optString("title"), true) { st.removeEpisode(o.optLong("id")) } }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
    }
}
