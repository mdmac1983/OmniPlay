package app.orionmd.omniplay.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState

/**
 * Music player skins (Now Playing → palette button, or Settings → Playback → Music player): colours, background,
 * how the artwork is shown (rounded, circle, square, a spinning vinyl record or a polaroid), the play button and the font.
 */
data class PlayerSkin(
    val id: String, val name: String,
    val bg: List<Color>,            // background gradient, top → bottom
    val accent: Color,              // progress, active buttons
    val text: Color, val muted: Color,
    val playBg: Color, val playFg: Color,
    val art: String = "rounded",    // rounded | circle | square | vinyl | polaroid
    val outlinePlay: Boolean = false,
    val font: FontFamily = FontFamily.Default,
    val blurArt: Boolean = false,   // the album art, blurred, behind everything
    val glow: Boolean = false,      // soft glow around the artwork and play button
    val light: Boolean = false,     // light background (the Up next list sits on a dark card)
    val pattern: String = "",       // drawn over the background: storm | rays | clouds | drip | eqgrid | confetti | camo | cross | hearts
    val heavy: Boolean = false,     // extra-bold title
    val italic: Boolean = false,    // slanted title
    val boxTitle: Boolean = false,  // the title sits in a solid box (accent background)
    val theme: String = "",         // themed pack this skin belongs to (shown in the picker)
)

val SKINS = listOf(
    PlayerSkin("midnight", "Midnight", listOf(Color(0xFF221C45), C.bg), C.ac, C.tx, C.mu, C.tx, C.bg),
    PlayerSkin("neon", "Neon", listOf(Color(0xFF05010F), Color(0xFF000000)), Color(0xFF00E5FF), Color.White, Color(0xFF7FDFFF), Color(0xFF00E5FF), Color(0xFF00E5FF), outlinePlay = true, glow = true),
    PlayerSkin("vinyl", "Vinyl", listOf(Color(0xFF3B2416), Color(0xFF140C07)), Color(0xFFFFB74D), Color(0xFFFFF3E0), Color(0xFFD7B899), Color(0xFFFFB74D), Color(0xFF2B1A10), art = "vinyl", font = FontFamily.Serif),
    PlayerSkin("cassette", "Retro '70s", listOf(Color(0xFFE0A030), Color(0xFF8A3B12)), Color(0xFF2B1A10), Color(0xFF2B1A10), Color(0xFF5A3418), Color(0xFF2B1A10), Color(0xFFF4C66A), art = "square", font = FontFamily.Serif, light = true),
    PlayerSkin("sunset", "Sunset", listOf(Color(0xFFFF7E5F), Color(0xFF6A2C70), Color(0xFF1F1037)), Color(0xFFFFD166), Color.White, Color(0xFFFFE0D6), Color.White, Color(0xFF6A2C70), art = "circle"),
    PlayerSkin("ocean", "Ocean", listOf(Color(0xFF0B3D5C), Color(0xFF02111B)), Color(0xFF4FC3F7), Color(0xFFE1F5FE), Color(0xFF90CAF9), Color(0xFF4FC3F7), Color(0xFF02111B)),
    PlayerSkin("forest", "Forest", listOf(Color(0xFF1B3A24), Color(0xFF07130B)), Color(0xFF9CCC65), Color(0xFFF1F8E9), Color(0xFFA5C8A0), Color(0xFF9CCC65), Color(0xFF07130B)),
    PlayerSkin("light", "Minimal light", listOf(Color(0xFFFAFAFA), Color(0xFFEDEEF2)), Color(0xFF3949AB), Color(0xFF111318), Color(0xFF5F6472), Color(0xFF111318), Color.White, light = true),
    PlayerSkin("glass", "Frosted art", listOf(Color(0x66000000), Color(0xCC000000)), Color.White, Color.White, Color(0xFFD0D0D8), Color.White, Color.Black, blurArt = true, glow = true),
    PlayerSkin("gold", "Gold luxe", listOf(Color(0xFF1A1508), Color(0xFF000000)), Color(0xFFD4AF37), Color(0xFFF5E6B3), Color(0xFFB59B55), Color(0xFFD4AF37), Color.Black, outlinePlay = true, font = FontFamily.Serif, art = "circle"),
    PlayerSkin("cyber", "Cyberpunk", listOf(Color(0xFF2B0036), Color(0xFF05000A)), Color(0xFFFCEE0A), Color(0xFFFF2A6D), Color(0xFF05D9E8), Color(0xFFFCEE0A), Color(0xFF05000A), art = "square", font = FontFamily.Monospace, glow = true),
    PlayerSkin("pastel", "Pastel", listOf(Color(0xFFFFD1DC), Color(0xFFE0D4FF)), Color(0xFFB0679A), Color(0xFF3D2A4A), Color(0xFF7A6488), Color(0xFF3D2A4A), Color(0xFFFFE9F0), art = "circle", light = true),
    PlayerSkin("noir", "Noir", listOf(Color(0xFF1C1C1C), Color(0xFF000000)), Color.White, Color.White, Color(0xFF9E9E9E), Color.White, Color.Black, art = "square", outlinePlay = true),
    PlayerSkin("aurora", "Aurora", listOf(Color(0xFF0B3D2E), Color(0xFF2A1250), Color(0xFF05060F)), Color(0xFF6CF0C2), Color.White, Color(0xFFB8C6E0), Color(0xFF6CF0C2), Color(0xFF05060F), glow = true),
    // ---------- themed packs (styles inspired by them — colours and patterns only, no logos) ----------
    PlayerSkin("fn_bus", "Battle Bus", listOf(Color(0xFF3FA9F5), Color(0xFF1D5FCC), Color(0xFF0B2A6B)), Color(0xFFFFD60A), Color.White, Color(0xFFD6ECFF), Color(0xFFFFD60A), Color(0xFF0B2A6B), art = "square", heavy = true, italic = true, pattern = "clouds", theme = "Fortnite"),
    PlayerSkin("fn_storm", "Storm Circle", listOf(Color(0xFF5B2A86), Color(0xFF2A0E4A), Color(0xFF12051F)), Color(0xFFC86BFA), Color.White, Color(0xFFD9B8F0), Color(0xFFC86BFA), Color(0xFF12051F), heavy = true, italic = true, glow = true, pattern = "storm", theme = "Fortnite"),
    PlayerSkin("fn_legend", "Legendary Drop", listOf(Color(0xFFF7A928), Color(0xFFC2560E), Color(0xFF3A1604)), Color(0xFFFFE08A), Color.White, Color(0xFFFFE3C2), Color.White, Color(0xFFC2560E), art = "square", heavy = true, italic = true, pattern = "rays", theme = "Fortnite"),
    PlayerSkin("mello_drip", "Mello Drip", listOf(Color(0xFFFFFFFF), Color(0xFFF1F3F8)), Color(0xFFFF4FA3), Color(0xFF111111), Color(0xFF555A66), Color(0xFF111111), Color.White, art = "circle", heavy = true, light = true, pattern = "drip", theme = "Marshmello"),
    PlayerSkin("mello_neon", "Mello Neon", listOf(Color(0xFF0A0A12), Color(0xFF000000)), Color(0xFFFF4FA3), Color.White, Color(0xFF9AE6FF), Color.White, Color.Black, art = "circle", heavy = true, glow = true, outlinePlay = true, pattern = "eqgrid", theme = "Marshmello"),
    PlayerSkin("mello_candy", "Mello Candy", listOf(Color(0xFFA0E7FF), Color(0xFFFFC2E2), Color(0xFFFFF4B8)), Color(0xFF7B5CFF), Color(0xFF1E1A3A), Color(0xFF4F4A70), Color.White, Color(0xFF7B5CFF), art = "circle", heavy = true, light = true, pattern = "confetti", theme = "Marshmello"),
    PlayerSkin("sup_box", "Box Red", listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F2)), Color(0xFFED1C24), Color(0xFF111111), Color(0xFF5A5A5A), Color(0xFFED1C24), Color.White, art = "square", heavy = true, italic = true, boxTitle = true, light = true, theme = "Supreme"),
    PlayerSkin("sup_camo", "Box Camo", listOf(Color(0xFF4B5320), Color(0xFF2F3418)), Color(0xFFED1C24), Color.White, Color(0xFFDADFC0), Color(0xFFED1C24), Color.White, art = "square", heavy = true, italic = true, boxTitle = true, pattern = "camo", theme = "Supreme"),
    PlayerSkin("ch_cross", "Chrome Cross", listOf(Color(0xFF1E1E1E), Color(0xFF050505)), Color(0xFFC9CCD1), Color(0xFFEDEFF2), Color(0xFF9EA3AB), Color(0xFFC9CCD1), Color.Black, art = "circle", font = FontFamily.Serif, heavy = true, outlinePlay = true, pattern = "cross", theme = "Chrome Hearts"),
    PlayerSkin("ch_heart", "Silver Heart", listOf(Color(0xFF141014), Color(0xFF000000)), Color(0xFFFF6EB4), Color(0xFFF2F2F5), Color(0xFFB8B8C2), Color(0xFFD9DBE0), Color.Black, font = FontFamily.Serif, heavy = true, glow = true, pattern = "hearts", theme = "Chrome Hearts"),
    // fashion & sportswear — styles inspired by them (colours, prints and textures only; no logos or monograms)
    PlayerSkin("gc_web", "Web Stripe", listOf(Color(0xFF0B3B24), Color(0xFF04140C)), Color(0xFFC8102E), Color(0xFFF3EBD8), Color(0xFFBFB59A), Color(0xFFF3EBD8), Color(0xFF0B3B24), font = FontFamily.Serif, pattern = "webstripe", theme = "Gucci"),
    PlayerSkin("gc_ivory", "Ivory Heritage", listOf(Color(0xFFF4EEDF), Color(0xFFE8DDC6)), Color(0xFF0B5A33), Color(0xFF2A1F14), Color(0xFF6B5B45), Color(0xFF0B5A33), Color(0xFFF4EEDF), font = FontFamily.Serif, light = true, pattern = "webstripe", theme = "Gucci"),
    PlayerSkin("lv_trunk", "Trunk", listOf(Color(0xFF5B3A22), Color(0xFF2A180C)), Color(0xFFD4A85A), Color(0xFFF6E7C8), Color(0xFFCDB48C), Color(0xFFD4A85A), Color(0xFF2A180C), art = "square", font = FontFamily.Serif, pattern = "trunk", theme = "Louis Vuitton"),
    PlayerSkin("lv_clasp", "Gold Clasp", listOf(Color(0xFF17120C), Color(0xFF050403)), Color(0xFFC9A55B), Color(0xFFF2E3C2), Color(0xFFAE9467), Color(0xFFC9A55B), Color.Black, art = "circle", font = FontFamily.Serif, outlinePlay = true, pattern = "studs", theme = "Louis Vuitton"),
    PlayerSkin("pr_nylon", "Black Nylon", listOf(Color(0xFF1C1C1F), Color(0xFF050506)), Color(0xFFB7C4D6), Color.White, Color(0xFFA2A7B0), Color(0xFFB7C4D6), Color.Black, art = "square", outlinePlay = true, pattern = "nylon", theme = "Prada"),
    PlayerSkin("pr_saffiano", "Saffiano Blush", listOf(Color(0xFFF6D7D9), Color(0xFFE9BFC3)), Color(0xFF1C1C1C), Color(0xFF1C1C1C), Color(0xFF5E4A4C), Color(0xFF1C1C1C), Color(0xFFF6D7D9), art = "square", light = true, pattern = "saffiano", theme = "Prada"),
    PlayerSkin("et_print", "Loud Print", listOf(Color(0xFFFF2E93), Color(0xFF5B1AB8)), Color(0xFFE4FF1A), Color.White, Color(0xFFFFD1EC), Color(0xFFE4FF1A), Color(0xFF3A0E7A), art = "circle", heavy = true, italic = true, pattern = "neoncamo", theme = "Ethika"),
    PlayerSkin("et_stripe", "Staple Stripe", listOf(Color(0xFF101014), Color(0xFF000000)), Color(0xFF00F0FF), Color.White, Color(0xFFB9C2D0), Color(0xFF00F0FF), Color.Black, heavy = true, glow = true, pattern = "diag", theme = "Ethika"),
    PlayerSkin("ua_black", "Armour Black", listOf(Color(0xFF1B1B1D), Color(0xFF000000)), Color(0xFFD50032), Color.White, Color(0xFFA0A0A6), Color(0xFFD50032), Color.White, art = "square", heavy = true, pattern = "carbon", theme = "Under Armour"),
    PlayerSkin("ua_steel", "Steel", listOf(Color(0xFF3A3F45), Color(0xFF15181B)), Color(0xFFE6E8EB), Color.White, Color(0xFFB3B8BF), Color(0xFFE6E8EB), Color(0xFF15181B), art = "square", heavy = true, pattern = "carbon", theme = "Under Armour"),
    PlayerSkin("nk_volt", "Volt", listOf(Color(0xFF111111), Color(0xFF000000)), Color(0xFFCEFF00), Color.White, Color(0xFFB0B0B0), Color(0xFFCEFF00), Color.Black, art = "square", heavy = true, italic = true, pattern = "speed", theme = "Nike"),
    PlayerSkin("nk_box", "Orange Box", listOf(Color(0xFFF26B21), Color(0xFFC2410C)), Color.White, Color.White, Color(0xFFFFE1CF), Color.White, Color(0xFFC2410C), art = "square", heavy = true, boxTitle = true, pattern = "speed", theme = "Nike"),
    PlayerSkin("jd_bred", "Bred", listOf(Color(0xFF151515), Color(0xFF000000)), Color(0xFFCE1141), Color.White, Color(0xFFB5B5B5), Color(0xFFCE1141), Color.White, art = "square", heavy = true, pattern = "cement", theme = "Jordan"),
    PlayerSkin("jd_chicago", "Chicago", listOf(Color(0xFFFFFFFF), Color(0xFFECECEC)), Color(0xFFCE1141), Color(0xFF111111), Color(0xFF555555), Color(0xFFCE1141), Color.White, art = "square", heavy = true, italic = true, boxTitle = true, light = true, pattern = "cement", theme = "Jordan"),
    PlayerSkin("ad_originals", "Originals", listOf(Color(0xFF141414), Color(0xFF000000)), Color.White, Color.White, Color(0xFFA8A8A8), Color.White, Color.Black, art = "square", heavy = true, outlinePlay = true, pattern = "track", theme = "Adidas"),
    PlayerSkin("ad_terrace", "Terrace Blue", listOf(Color(0xFF0B4FB3), Color(0xFF06275A)), Color(0xFFF2C14E), Color.White, Color(0xFFCFE0FF), Color(0xFFF2C14E), Color(0xFF06275A), art = "square", heavy = true, pattern = "track", theme = "Adidas"),
    PlayerSkin("polaroid", "Polaroid", listOf(Color(0xFFEFE6D8), Color(0xFFD9CDB8)), Color(0xFFD2553F), Color(0xFF2E2A25), Color(0xFF6E6558), Color(0xFF2E2A25), Color(0xFFEFE6D8), art = "polaroid", font = FontFamily.Cursive, light = true),
)

fun skinOf(id: String) = SKINS.firstOrNull { it.id == id } ?: SKINS[0]

/** The background of Now Playing for a skin (a gradient, or the album art blurred). */
@Composable
fun SkinBackground(st: AppState, t: org.json.JSONObject, s: PlayerSkin) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(s.bg))) {
        if (s.blurArt) {
            // a tiny copy of the cover stretched over the screen reads as a soft blur (works on every Android version)
            Thumb(st.playerArt(t), "", Modifier.fillMaxSize().alpha(.85f), px = 16, corner = 0.dp, fallback = st.playerAlt(t))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(s.bg)))
        }
        if (s.pattern.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { skinPattern(s) }
        if (st.pref("skinLogoWm", "0") == "1") SkinLogo(s.id, Modifier.align(Alignment.Center).fillMaxWidth(.75f).aspectRatio(1f), alpha = .1f)
    }
}

/** Decorative background for themed skins (drawn, so it scales to any screen). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.skinPattern(s: PlayerSkin) {
    val w = size.width; val h = size.height; val rnd = kotlin.random.Random(s.id.hashCode()); val u = minOf(w, h) / 430f   // 1 ≈ 1 px on a phone-sized screen
    when (s.pattern) {
        "storm" -> {   // the closing storm: a glowing ring with purple haze outside it
            val c = Offset(w / 2, h * .32f); val r = minOf(w, h) * .62f
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color(0x66A040FF), Color(0xAA3A0A66)), c, r * 1.6f), r * 1.6f, c)
            drawCircle(Color(0xFFE0A8FF).copy(alpha = .55f), r, c, style = Stroke(5f * u))
            drawCircle(Color(0xFFE0A8FF).copy(alpha = .18f), r + 14f * u, c, style = Stroke(18f * u))
            repeat(5) { val x = rnd.nextFloat() * w; var y = 0f; var px = x; val path = androidx.compose.ui.graphics.Path().apply { moveTo(x, 0f) }
                while (y < h * .25f) { y += (30f + rnd.nextFloat() * 40f) * u; px += (rnd.nextFloat() * 60f - 30f) * u; path.lineTo(px, y) }
                drawPath(path, Color.White.copy(alpha = .12f), style = Stroke(2.5f * u)) }
        }
        "rays" -> { val c = Offset(w / 2, h * .3f); for (k in 0 until 18) if (k % 2 == 0) drawArc(Color.White.copy(alpha = .07f), k * 20f, 10f, true, Offset(c.x - h, c.y - h), androidx.compose.ui.geometry.Size(h * 2, h * 2)) }
        "clouds" -> repeat(7) { val cx = rnd.nextFloat() * w; val cy = if (it % 2 == 0) rnd.nextFloat() * h * .25f else h * (.75f + rnd.nextFloat() * .25f); val r = (40f + rnd.nextFloat() * 50f) * u
            for (j in 0 until 4) drawCircle(Color.White.copy(alpha = .14f), r * (.7f + j % 2 * .3f), Offset(cx + (j - 1.5f) * r * .8f, cy + (if (j % 2 == 0) 0f else -r * .35f))) }
        "drip" -> { val col = s.accent.copy(alpha = .9f); drawRect(col, size = androidx.compose.ui.geometry.Size(w, 12f * u))
            var x = 0f; while (x < w) { val dw = (26f + rnd.nextFloat() * 30f) * u; val dl = (14f + rnd.nextFloat() * 46f) * u
                drawRoundRect(col, Offset(x, 0f), androidx.compose.ui.geometry.Size(dw, dl), androidx.compose.ui.geometry.CornerRadius(dw / 2, dw / 2)); x += dw + (10f + rnd.nextFloat() * 30f) * u } }
        "eqgrid" -> { val n = 28; val bw = w / n
            for (k in 0 until n) { val bh = h * (.05f + rnd.nextFloat() * .18f); drawRect(Brush.verticalGradient(listOf(Color(0xFFFF4FA3), Color(0xFF00E5FF)), h - bh, h), Offset(k * bw + 2f * u, h - bh), androidx.compose.ui.geometry.Size(bw - 4f * u, bh), alpha = .35f) }
            var y = h * .62f; while (y < h) { drawLine(Color(0xFF00E5FF).copy(alpha = .12f), Offset(0f, y), Offset(w, y), 1.5f * u); y += 28f * u } }
        "confetti" -> repeat(60) { val col = listOf(Color(0xFFFF6FB5), Color(0xFF7B5CFF), Color(0xFF4FD1FF), Color(0xFFFFD84F), Color(0xFF6FFFB0))[it % 5]
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            rotate(rnd.nextFloat() * 180f, Offset(x, y)) { drawRoundRect(col.copy(alpha = .55f), Offset(x, y), androidx.compose.ui.geometry.Size(18f * u, 8f * u), androidx.compose.ui.geometry.CornerRadius(4f * u, 4f * u)) } }
        "camo" -> repeat(90) { val col = listOf(Color(0xFF6B6F3A), Color(0xFF3D2B1F), Color(0xFF1F2412), Color(0xFF8A8455))[it % 4]
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val rw = (60f + rnd.nextFloat() * 120f) * u
            drawOval(col.copy(alpha = .75f), Offset(x - rw / 2, y - rw / 3), androidx.compose.ui.geometry.Size(rw, rw * (.4f + rnd.nextFloat() * .4f))) }
        "cross" -> { val step = 120f * u; var y = 40f * u; var row = 0
            while (y < h) { var x = (if (row % 2 == 0) 40f else 100f) * u
                while (x < w) { cross(Offset(x, y), 26f * u, Color(0xFFC9CCD1).copy(alpha = .1f)); x += step }; y += step * .8f; row++ } }
        "hearts" -> { val step = 110f * u; var y = 50f * u; var row = 0
            while (y < h) { var x = (if (row % 2 == 0) 50f else 105f) * u
                while (x < w) { heart(Offset(x, y), 20f * u, if ((row + (x / step).toInt()) % 3 == 0) Color(0xFFFF6EB4).copy(alpha = .22f) else Color(0xFFD9DBE0).copy(alpha = .08f)); x += step }; y += step * .85f; row++ } }
        // ---------- fashion & sportswear textures ----------
        "webstripe" -> {   // green-red-green web band, top and bottom
            for (y0 in listOf(h * .045f, h * .925f)) {
                val bh = 30f * u; val third = bh / 3
                drawRect(Color(0xFFEFE6CF).copy(alpha = .6f), Offset(0f, y0 - 2f * u), androidx.compose.ui.geometry.Size(w, bh + 4f * u))
                drawRect(Color(0xFF0B6B3A), Offset(0f, y0), androidx.compose.ui.geometry.Size(w, third))
                drawRect(Color(0xFFC8102E), Offset(0f, y0 + third), androidx.compose.ui.geometry.Size(w, third))
                drawRect(Color(0xFF0B6B3A), Offset(0f, y0 + third * 2), androidx.compose.ui.geometry.Size(w, third))
            }
        }
        "trunk" -> {   // leather-trimmed edges with brass rivets, like an old travel trunk
            val trim = Color(0xFFC69C6D).copy(alpha = .32f); val tw = 24f * u
            drawRect(trim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(tw, h)); drawRect(trim, Offset(w - tw, 0f), androidx.compose.ui.geometry.Size(tw, h))
            drawRect(trim, Offset(0f, h * .58f), androidx.compose.ui.geometry.Size(w, tw))
            var y = 30f * u; while (y < h) { for (x in listOf(tw / 2, w - tw / 2)) { drawCircle(Color(0xFFD4A85A).copy(alpha = .7f), 4f * u, Offset(x, y)); drawCircle(Color(0xFF6B4A1E).copy(alpha = .5f), 1.6f * u, Offset(x, y)) }; y += 64f * u }
            var x = 40f * u; while (x < w - 30f * u) { drawCircle(Color(0xFFD4A85A).copy(alpha = .7f), 4f * u, Offset(x, h * .58f + tw / 2)); x += 64f * u }
        }
        "studs" -> { val step = 46f * u; var y = 20f * u; var row = 0
            while (y < h) { var x = (if (row % 2 == 0) 20f else 43f) * u; while (x < w) { drawCircle(Color(0xFFC9A55B).copy(alpha = .3f), 2.4f * u, Offset(x, y)); x += step }; y += step * .5f; row++ } }
        "nylon" -> { val step = 7f * u; var k = -h; while (k < w) { drawLine(Color.White.copy(alpha = .035f), Offset(k, 0f), Offset(k + h, h), 1f); drawLine(Color.White.copy(alpha = .025f), Offset(k + h, 0f), Offset(k, h), 1f); k += step } }
        "saffiano" -> repeat(1400) { val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val l = (3f + rnd.nextFloat() * 5f) * u
            if (it % 2 == 0) drawLine(Color.Black.copy(alpha = .07f), Offset(x, y), Offset(x + l, y + l), 1.2f) else drawLine(Color.White.copy(alpha = .12f), Offset(x, y + l), Offset(x + l, y), 1.2f) }
        "neoncamo" -> repeat(80) { val col = listOf(Color(0xFFE4FF1A), Color(0xFF00F0FF), Color(0xFFFF7AD9), Color(0xFF2A0A6B))[it % 4]
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val rw = (50f + rnd.nextFloat() * 110f) * u
            drawOval(col.copy(alpha = .3f), Offset(x - rw / 2, y - rw / 3), androidx.compose.ui.geometry.Size(rw, rw * (.4f + rnd.nextFloat() * .4f))) }
        "diag" -> { val cols = listOf(Color(0xFF00F0FF), Color(0xFFFF2E93), Color(0xFFE4FF1A)); var k = -h; var i = 0
            while (k < w) { val path = androidx.compose.ui.graphics.Path().apply { moveTo(k, h); lineTo(k + 40f * u, h); lineTo(k + 40f * u + h * .6f, 0f); lineTo(k + h * .6f, 0f); close() }
                drawPath(path, cols[i % 3].copy(alpha = .14f)); k += 120f * u; i++ } }
        "carbon" -> { val r = 16f * u; val dx = r * 1.732f; var y = 0f; var row = 0
            while (y < h + r) { var x = if (row % 2 == 0) 0f else dx / 2
                while (x < w + r) { val hex = androidx.compose.ui.graphics.Path().apply { for (k in 0 until 6) { val a = Math.toRadians(60.0 * k + 30).toFloat(); val px = x + r * kotlin.math.cos(a); val py = y + r * kotlin.math.sin(a); if (k == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                    drawPath(hex, Color.White.copy(alpha = .05f), style = Stroke(1.2f * u)); x += dx }
                y += r * 1.5f; row++ } }
        "speed" -> repeat(26) { val x = rnd.nextFloat() * w * 1.2f - w * .1f; val y = rnd.nextFloat() * h; val l = (80f + rnd.nextFloat() * 160f) * u
            drawLine(s.accent.copy(alpha = .08f + rnd.nextFloat() * .1f), Offset(x, y), Offset(x + l, y - l * .36f), (2f + rnd.nextFloat() * 4f) * u) }
        "cement" -> {   // grey crackle print
            val col = if (s.light) Color(0xFF6E6E6E) else Color(0xFFB0B0B0)
            repeat(70) { var x = rnd.nextFloat() * w; var y = rnd.nextFloat() * h
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(x, y); repeat(4) { x += (rnd.nextFloat() * 50f - 25f) * u; y += (rnd.nextFloat() * 50f - 25f) * u; lineTo(x, y) } }
                drawPath(path, col.copy(alpha = .14f), style = Stroke(1.6f * u)) }
            repeat(500) { drawCircle(col.copy(alpha = .12f), (0.8f + rnd.nextFloat() * 1.6f) * u, Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
        }
        "track" -> { var y = 0f; while (y < h) { drawLine(Color.White.copy(alpha = .04f), Offset(0f, y), Offset(w, y), 1f); y += 12f * u }
            drawRect(s.accent.copy(alpha = .25f), Offset(w - 22f * u, 0f), androidx.compose.ui.geometry.Size(6f * u, h)) }
    }
}

/** A gothic cross with flared ends. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.cross(c: Offset, r: Float, col: Color) {
    val t = r * .28f
    drawRect(col, Offset(c.x - t / 2, c.y - r), androidx.compose.ui.geometry.Size(t, r * 2.2f))
    drawRect(col, Offset(c.x - r * .75f, c.y - r * .45f), androidx.compose.ui.geometry.Size(r * 1.5f, t))
    for (p in listOf(Offset(c.x, c.y - r), Offset(c.x, c.y + r * 1.2f), Offset(c.x - r * .75f, c.y - r * .45f + t / 2), Offset(c.x + r * .75f, c.y - r * .45f + t / 2))) drawCircle(col, t * .9f, p)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.heart(c: Offset, r: Float, col: Color) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x, c.y + r * .9f)
        cubicTo(c.x - r * 1.4f, c.y - r * .1f, c.x - r * .7f, c.y - r * 1.1f, c.x, c.y - r * .35f)
        cubicTo(c.x + r * .7f, c.y - r * 1.1f, c.x + r * 1.4f, c.y - r * .1f, c.x, c.y + r * .9f)
        close()
    }
    drawPath(p, col)
}

/** The artwork in the skin's style. [playing] spins the vinyl. */
@Composable
fun SkinArt(st: AppState, t: org.json.JSONObject, s: PlayerSkin, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val art = st.playerArt(t); val alt = st.playerAlt(t)
    val label = t.optString("t").ifEmpty { t.optString("n") }
    val glow = if (s.glow) Modifier.border(2.dp, Brush.sweepGradient(listOf(s.accent, s.accent.copy(alpha = .2f), s.accent)), when (s.art) { "circle", "vinyl" -> CircleShape; "square" -> RoundedCornerShape(2.dp); else -> RoundedCornerShape(18.dp) }) else Modifier
    when (s.art) {
        "circle" -> Thumb(art, label, modifier.aspectRatio(1f).clip(CircleShape).then(glow).androidxClick(onClick), px = 800, corner = 400.dp, fontSize = 64.sp, fallback = alt)
        "square" -> Thumb(art, label, modifier.aspectRatio(1f).then(glow).androidxClick(onClick), px = 800, corner = 2.dp, fontSize = 64.sp, fallback = alt)
        "vinyl" -> {
            val spin = rememberInfiniteTransition(label = "vinyl").animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "spin")
            val angle by spin
            Box(modifier.aspectRatio(1f).androidxClick(onClick), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().rotate(if (playing) angle else 0f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2; val c = Offset(size.width / 2, size.height / 2)
                        drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0A0A0A)), c, r), r, c)
                        for (k in 0 until 18) drawCircle(Color.White.copy(alpha = if (k % 3 == 0) .07f else .03f), r * (.42f + k * .031f), c, style = Stroke(width = 1.2f))
                        // light reflection
                        drawArc(Color.White.copy(alpha = .08f), -60f, 40f, true, Offset(c.x - r, c.y - r), size.copy(width = r * 2, height = r * 2))
                    }
                    Thumb(art, label, Modifier.fillMaxWidth(.4f).aspectRatio(1f).clip(CircleShape), px = 400, corner = 200.dp, fontSize = 28.sp, fallback = alt)
                    Box(Modifier.fillMaxWidth(.035f).aspectRatio(1f).clip(CircleShape).background(Color(0xFF0A0A0A)))
                }
            }
        }
        "polaroid" -> Column(modifier.androidxClick(onClick).background(Color(0xFFFFFEF8)).border(1.dp, Color(0x22000000)).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Thumb(art, label, Modifier.fillMaxWidth().aspectRatio(1f), px = 800, corner = 0.dp, fontSize = 64.sp, fallback = alt)
            Text(label, fontFamily = FontFamily.Cursive, fontSize = 22.sp, color = Color(0xFF2E2A25), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        else -> Thumb(art, label, modifier.aspectRatio(1f).then(glow).androidxClick(onClick), px = 800, corner = 18.dp, fontSize = 64.sp, fallback = alt,
            alsoLarge = (art as? app.orionmd.omniplay.Img.Art)?.copy(large = true))
    }
}

private fun Modifier.androidxClick(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

/** A swatch for the skin picker. */
@Composable
fun SkinSwatch(s: PlayerSkin, selected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(s.bg))
            .border(if (selected) 3.dp else 1.dp, if (selected) C.ac else C.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            if (s.pattern.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { skinPattern(s) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val shape = when (s.art) { "circle", "vinyl" -> CircleShape; "square", "polaroid" -> RoundedCornerShape(2.dp); else -> RoundedCornerShape(8.dp) }
                Box(Modifier.fillMaxWidth(.55f).aspectRatio(1f).clip(shape).background(if (s.art == "vinyl") Color(0xFF111111) else if (s.art == "polaroid") Color.White else s.accent.copy(alpha = .6f)))
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(.6f).aspectRatio(12f).clip(RoundedCornerShape(2.dp)).background(s.accent))
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(.18f).aspectRatio(1f).clip(CircleShape).background(if (s.outlinePlay) Color.Transparent else s.playBg).border(2.dp, s.playBg, CircleShape))
            }
            SkinLogo(s.id, Modifier.align(Alignment.TopEnd).padding(5.dp).fillMaxWidth(.32f).aspectRatio(1f))
        }
        if (s.theme.isNotEmpty()) Text(s.theme, fontSize = 10.sp, color = C.mu, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(s.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The skin's background (gradient + pattern) as a bitmap — used behind the video player's picture. */
fun skinBitmap(s: PlayerSkin, w: Int, h: Int, density: Float, dim: Float = 0f): android.graphics.Bitmap {
    val img = androidx.compose.ui.graphics.ImageBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(density), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(img), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) {
        drawRect(Brush.verticalGradient(s.bg))
        if (s.pattern.isNotEmpty()) skinPattern(s)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
    return img.asAndroidBitmap()
}

/**
 * Your own logo on a skin: any picture from the phone (a transparent PNG looks best), shown as a badge in the music and
 * video players and, if you like, as a faint watermark. Kept only on this device (Media Hub ships no brand logos).
 */
object SkinLogos {
    /** Bumped when a logo changes, so screens reload it. */
    private val genState = androidx.compose.runtime.mutableIntStateOf(0)
    var gen: Int get() = genState.intValue; set(v) { genState.intValue = v }
    private fun file(ctx: android.content.Context, id: String) = java.io.File(java.io.File(ctx.filesDir, "skinlogos").apply { mkdirs() }, "$id.png")
    fun has(ctx: android.content.Context, id: String) = file(ctx, id).exists()
    fun load(ctx: android.content.Context, id: String): android.graphics.Bitmap? = file(ctx, id).takeIf { it.exists() }?.let { try { android.graphics.BitmapFactory.decodeFile(it.path) } catch (_: Throwable) { null } }
    /** Copies the picked picture (scaled to at most 600 px, transparency kept). */
    fun save(ctx: android.content.Context, id: String, uri: android.net.Uri): Boolean = try {
        val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("unreadable")
        val sc = minOf(1f, 600f / maxOf(src.width, src.height))
        val b = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * sc).toInt().coerceAtLeast(1), (src.height * sc).toInt().coerceAtLeast(1), true) else src
        file(ctx, id).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        gen++; true
    } catch (_: Throwable) { false }
    fun remove(ctx: android.content.Context, id: String) { file(ctx, id).delete(); gen++ }
}

/** The skin's logo badge (nothing if this skin has none). */
@Composable
fun SkinLogo(skinId: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = SkinLogos.gen
    val bmp: androidx.compose.ui.graphics.ImageBitmap? = androidx.compose.runtime.remember(skinId, g) { SkinLogos.load(ctx, skinId)?.asImageBitmap() }
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, modifier.alpha(alpha), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}
