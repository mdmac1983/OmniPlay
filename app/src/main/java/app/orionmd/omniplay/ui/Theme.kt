package app.orionmd.omniplay.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's colours. Two styles (Settings → Appearance → App style): "apple" (the default) follows Apple's Human
 * Interface Guidelines for dark mode — system blue tint, iOS grouped greys, 17 sp body text, large titles, an iOS tab
 * bar, inset grouped Settings and iOS switches; "classic" is Media Hub's original purple look. The values are state, so
 * switching restyles every screen at once.
 */
object C {
    var apple by mutableStateOf(true); private set
    var bg by mutableStateOf(Color(0xFF0E0F14)); private set
    var s1 by mutableStateOf(Color(0xFF171923)); private set
    var s2 by mutableStateOf(Color(0xFF1F2230)); private set
    var s3 by mutableStateOf(Color(0xFF2A2E40)); private set
    var line by mutableStateOf(Color(0xFF2A2D3A)); private set
    var tx by mutableStateOf(Color(0xFFEDEEF3)); private set
    var mu by mutableStateOf(Color(0xFF9095A8)); private set
    var ac by mutableStateOf(Color(0xFF7C5CFF)); private set
    var ac2 by mutableStateOf(Color(0xFF22C3A6)); private set
    var warn by mutableStateOf(Color(0xFFFF8A5C)); private set
    var danger by mutableStateOf(Color(0xFF3A1F22)); private set
    var dangerTx by mutableStateOf(Color(0xFFFF9A9A)); private set
    val claude = Color(0xFFD97757)
    /** translucent so the watermark shows through */
    var card by mutableStateOf(Color(0x99171923)); private set

    /** "apple" or "classic". */
    fun use(style: String) {
        apple = style != "classic"
        if (apple) {
            // iOS dark-mode system colours: grouped backgrounds, separator, labels, blue / green / orange / red
            bg = Color(0xFF000000); s1 = Color(0xFF1C1C1E); s2 = Color(0xFF2C2C2E); s3 = Color(0xFF3A3A3C)
            line = Color(0xFF38383A); tx = Color(0xFFFFFFFF); mu = Color(0xFF98989F)
            ac = Color(0xFF0A84FF); ac2 = Color(0xFF30D158); warn = Color(0xFFFF9F0A)
            danger = Color(0x29FF453A); dangerTx = Color(0xFFFF453A); card = Color(0xEB1C1C1E)
        } else {
            bg = Color(0xFF0E0F14); s1 = Color(0xFF171923); s2 = Color(0xFF1F2230); s3 = Color(0xFF2A2E40)
            line = Color(0xFF2A2D3A); tx = Color(0xFFEDEEF3); mu = Color(0xFF9095A8)
            ac = Color(0xFF7C5CFF); ac2 = Color(0xFF22C3A6); warn = Color(0xFFFF8A5C)
            danger = Color(0xFF3A1F22); dangerTx = Color(0xFFFF9A9A); card = Color(0x99171923)
        }
    }
    init { use("apple") }
    val STYLES = listOf("apple" to "Apple (iOS look)", "classic" to "Classic Media Hub")
}

/** On/off switch colours: iOS green with a white knob in the Apple style. */
@Composable
fun switchColors(): SwitchColors = if (C.apple) SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = C.ac2, checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF39393D), uncheckedBorderColor = Color.Transparent,
) else SwitchDefaults.colors(checkedThumbColor = C.ac, checkedTrackColor = C.ac.copy(alpha = .5f))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val base = Typography()
    // Apple: 17 sp body with SF-like tight tracking; the system font stands in for SF Pro, which only Apple platforms may use
    val type = if (C.apple) base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    ) else base
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.ac, onPrimary = Color.White, secondary = C.ac2, background = C.bg, onBackground = C.tx,
            surface = C.s1, onSurface = C.tx, surfaceVariant = C.s2, onSurfaceVariant = C.mu, outline = C.line,
            error = C.dangerTx, surfaceContainer = C.s1, surfaceContainerHigh = C.s2, surfaceContainerHighest = C.s3,
        ),
        typography = type,
        shapes = if (C.apple) Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)) else Shapes(),
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
    val guide = Icons.AutoMirrored.Rounded.MenuBook
    val features = Icons.Rounded.Checklist
    val chevron = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    val unfold = Icons.Rounded.UnfoldMore
}
