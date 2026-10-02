package app.orionmd.omniplay.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object C {
    val bg = Color(0xFF0E0F14); val s1 = Color(0xFF171923); val s2 = Color(0xFF1F2230); val s3 = Color(0xFF2A2E40)
    val line = Color(0xFF2A2D3A); val tx = Color(0xFFEDEEF3); val mu = Color(0xFF9095A8)
    val ac = Color(0xFF7C5CFF); val ac2 = Color(0xFF22C3A6); val warn = Color(0xFFFF8A5C)
    val danger = Color(0xFF3A1F22); val dangerTx = Color(0xFFFF9A9A)
    val claude = Color(0xFFD97757)
    val card = Color(0x99171923)   // translucent so the watermark shows through
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.ac, onPrimary = Color.White, secondary = C.ac2, background = C.bg, onBackground = C.tx,
            surface = C.s1, onSurface = C.tx, surfaceVariant = C.s2, onSurfaceVariant = C.mu, outline = C.line,
            error = C.dangerTx, surfaceContainer = C.s1, surfaceContainerHigh = C.s2, surfaceContainerHighest = C.s3,
        ),
    ) {
        // default text & icon colour for everything (Material otherwise falls back to black outside a Surface)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides C.tx, content = content)
    }
}

/** The app's icons: Google's Material icons (rounded style), crisp at every size. */
object Ic {
    val play = Icons.Rounded.PlayArrow
    val pause = Icons.Rounded.Pause
    val prev = Icons.Rounded.SkipPrevious
    val next = Icons.Rounded.SkipNext
    val back = Icons.AutoMirrored.Rounded.ArrowBack
    val forward = Icons.AutoMirrored.Rounded.ArrowForward
    val down = Icons.Rounded.KeyboardArrowDown
    val shuffle = Icons.Rounded.Shuffle
    val repeat = Icons.Rounded.Repeat
    val repeat1 = Icons.Rounded.RepeatOne
    val pdf = Icons.Rounded.PictureAsPdf
    val lock = Icons.Rounded.Lock
    val save = Icons.Rounded.SaveAlt
    val info = Icons.Rounded.Info
    val expand = Icons.Rounded.Fullscreen
    val close = Icons.Rounded.Close
    val stop = Icons.Rounded.Stop
    val download = Icons.Rounded.Download
    val done = Icons.Rounded.Done
    val cc = Icons.Rounded.ClosedCaption
    val finance = Icons.AutoMirrored.Rounded.ShowChart
    val dash = Icons.Rounded.Dashboard
    val lib = Icons.Rounded.VideoLibrary
    val settings = Icons.Rounded.Settings
    val sortUp = Icons.Rounded.ArrowUpward
    val sortDown = Icons.Rounded.ArrowDownward
    val rotate = Icons.Rounded.RotateRight
    val tune = Icons.Rounded.Tune
    val podcast = Icons.Rounded.Podcasts
    val sleep = Icons.Rounded.Bedtime
    val eq = Icons.Rounded.Equalizer
    val lyrics = Icons.Rounded.Lyrics
    val claude = Icons.Rounded.AutoAwesome
    val mic = Icons.Rounded.Mic
    val volume = Icons.AutoMirrored.Rounded.VolumeUp
    val volumeOff = Icons.AutoMirrored.Rounded.VolumeOff
    val send = Icons.AutoMirrored.Rounded.Send
    val arrowDrop = Icons.Rounded.ArrowDropDown
    val search = Icons.Rounded.Search
    val sync = Icons.Rounded.Sync
    val skin = Icons.Rounded.Palette
    val playlistAdd = Icons.AutoMirrored.Rounded.PlaylistAdd
    val playlist = Icons.AutoMirrored.Rounded.QueueMusic
    val dragHandle = Icons.Rounded.DragHandle
    val more = Icons.Rounded.MoreVert
    val movie = Icons.Rounded.Movie
    val music = Icons.Rounded.MusicNote
    val home = Icons.Rounded.Home
    val cloud = Icons.Rounded.CloudQueue
    val folder = Icons.Rounded.Folder
    val album = Icons.Rounded.Album
    val artist = Icons.Rounded.Person
    val genre = Icons.Rounded.Category
    val key = Icons.Rounded.Key
    val add = Icons.Rounded.Add
    val trash = Icons.Rounded.DeleteOutline
    val offline = Icons.Rounded.DownloadDone
    val device = Icons.Rounded.Smartphone
    val tv = Icons.Rounded.Tv
    val link = Icons.Rounded.Link
    val shield = Icons.Rounded.Shield
    val star = Icons.Rounded.Star
    val gallery = Icons.Rounded.PhotoLibrary
}
