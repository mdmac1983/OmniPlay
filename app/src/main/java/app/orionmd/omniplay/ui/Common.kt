package app.orionmd.omniplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.R

@Composable internal fun BackBar(st: AppState) { Row(Modifier.padding(vertical = 4.dp)) { IconBtn(Ic.back, { st.back() }) } }
internal fun contentPad(pad: PaddingValues) = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 8.dp)

/** The OrionMD artwork behind the screens, at the strength chosen in Settings. */
@Composable
fun Watermark(st: AppState, modifier: Modifier = Modifier) {
    st.prefGen
    val strength = st.pref("watermark", "medium")
    if (strength == "off") return
    val a = when (strength) { "light" -> .05f; "strong" -> .18f; else -> .10f }
    Box(modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.watermark_tall), null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop)
    }
}

/** The full splash artwork from assets (used on the gate screen). */
@Composable
fun SplashArt(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember {
        try { ctx.assets.open("brand/splash.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap() } catch (_: Exception) { null }
    }
    if (bmp != null) Image(bmp, null, modifier, contentScale = ContentScale.Fit)
    else Image(painterResource(R.drawable.brand_emblem), null, modifier, contentScale = ContentScale.Fit)
}

/**
 * Full-screen OrionMD OmniPlay splash shown each time the app opens (portrait or landscape artwork to fit the screen),
 * fading out after [ms] or on tap. Settings → Appearance can turn it off.
 */
@Composable
fun SplashScreen(ms: Long = 2200, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val bmp = remember(wide) {
        try { ctx.assets.open(if (wide) "brand/splash_wide.jpg" else "brand/splash.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap() } catch (_: Exception) { null }
    }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(350))
        kotlinx.coroutines.delay(ms)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(300))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0820))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDone() }) {
        if (bmp != null) Image(bmp, "OrionMD OmniPlay", Modifier.fillMaxSize().alpha(alpha.value), contentScale = if (wide) ContentScale.Fit else ContentScale.Crop)
    }
}
