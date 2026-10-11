package app.orionmd.omniplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Page

@Composable
fun OmniApp(st: AppState) {
    // phone Back: close full-screen panels / selection / sub-pages before leaving the app
    androidx.activity.compose.BackHandler(enabled = st.visFull || st.soundOpen || (st.sheetOpen && st.player.track != null) || st.gallery.sel.isNotEmpty() || st.pages.isNotEmpty() || st.tab != "dash") {
        when {
            st.visFull -> st.visFull = false
            st.soundOpen -> st.soundOpen = false
            st.sheetOpen -> st.sheetOpen = false
            st.gallery.sel.isNotEmpty() -> st.gallery.sel.clear()
            st.pages.isNotEmpty() -> st.back()
            else -> st.tab = "dash"
        }
    }
    // when a full-screen surface is up, show only that
    // (dialogs are drawn on top of these too — the skin / visualizer pickers open from the Sound screen)
    if (st.visFull) { VisualizerFull(st); Dialogs(st); return }
    if (st.soundOpen) { SoundScreen(st); Dialogs(st); st.toast?.let { (m, _) -> Toast(m) { st.toast = null } }; return }
    if (st.sheetOpen && st.player.track != null) { NowPlaying(st); Dialogs(st); return }
    // full-screen gallery pages (no tab bar)
    when (val top = st.pages.lastOrNull()) {
        is Page.GViewer -> { androidx.activity.compose.BackHandler { st.back() }; GViewerScreen(st, top); Dialogs(st); st.toast?.let { (m, _) -> Toast(m) { st.toast = null } }; return }
        is Page.GEditor -> { androidx.activity.compose.BackHandler { st.back() }; GEditorScreen(st, top); Dialogs(st); st.toast?.let { (m, _) -> Toast(m) { st.toast = null } }; return }
        is Page.GCreate -> { androidx.activity.compose.BackHandler { st.back() }; Box(Modifier.fillMaxSize().background(C.bg)) { GCreateScreen(st, top, PaddingValues()) }; Dialogs(st); st.toast?.let { (m, _) -> Toast(m) { st.toast = null } }; return }
        else -> {}
    }

    val tabs = listOf("dash" to ("Home" to Ic.home), "home" to ("Music" to Ic.lib), "video" to ("Video" to Ic.movie), "gallery" to ("Gallery" to Ic.gallery), "set" to ("Settings" to Ic.settings))
    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(st)
                NavigationBar(containerColor = C.s1) {
                    tabs.forEach { (id, t) ->
                        NavigationBarItem(
                            selected = st.tab == id && st.pages.isEmpty(),
                            onClick = { if (st.tab == id) st.pages.clear(); st.tab = id },
                            icon = { Icon(t.second, t.first) }, label = { Text(t.first, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = C.ac, selectedTextColor = C.ac, indicatorColor = C.s3, unselectedIconColor = C.mu, unselectedTextColor = C.mu),
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Watermark(st)
            val top = st.pages.lastOrNull()
            if (top != null) Router(st, top, pad)
            else when (st.tab) {
                "dash" -> DashboardScreen(st, pad)
                "home" -> MusicLibrary(st, pad)
                "video" -> VideoLibrary(st, pad)
                "gallery" -> GalleryScreen(st, pad)
                "search" -> DiscoverScreen(st, pad)
                "set" -> SettingsScreen(st, pad)
            }
        }
    }
    Dialogs(st)
    st.toast?.let { (msg, _) -> Toast(msg) { st.toast = null } }
}

@Composable
private fun Router(st: AppState, page: Page, pad: androidx.compose.foundation.layout.PaddingValues) {
    when (page) {
        is Page.Album -> AlbumPage(st, page, pad)
        is Page.Artist -> ArtistPage(st, page, pad)
        is Page.FolderPage -> FolderPage(st, page, pad)
        is Page.GenrePage -> GenrePage(st, page, pad)
        is Page.Movie -> MoviePage(st, page, pad)
        is Page.Show -> ShowPage(st, page, pad)
        is Page.PlaylistPage -> PlaylistPage(st, page, pad)
        is Page.PodcastShow -> PodcastShowPage(st, page, pad)
        is Page.CloudBrowse -> CloudBrowsePage(st, page, pad)
        Page.Podcasts -> PodcastsPage(st, pad)
        Page.Discover -> DiscoverScreen(st, pad)
        is Page.TrackList -> TrackListPage(st, page, pad)
        Page.Settings -> SettingsScreen(st, pad)
        Page.Downloads -> DownloadsPage(st, pad)
        Page.Storage -> StoragePage(st, pad)
        is Page.SavedFiles -> SavedFilesPage(st, page.key, pad)
        is Page.Doc -> DocPage(st, page, pad)
        is Page.GAlbumPage -> GAlbumScreen(st, page, pad)
        is Page.GGroupPage -> GGroupScreen(st, page, pad)
        Page.GTrash -> GTrashScreen(st, pad)
        is Page.GViewer, is Page.GEditor, is Page.GCreate -> {}
    }
}

@Composable
private fun Toast(msg: String, onGone: () -> Unit) {
    LaunchedEffect(msg) { kotlinx.coroutines.delay(2600); onGone() }
    Box(Modifier.fillMaxSize().padding(bottom = 150.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
        AnimatedVisibility(true) {
            Text(msg, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xE6202433)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
