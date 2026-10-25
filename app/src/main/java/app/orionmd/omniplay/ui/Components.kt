@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.orionmd.omniplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import app.orionmd.omniplay.ImageLoader
import app.orionmd.omniplay.Img

private val phBrush = Brush.linearGradient(listOf(C.s3, C.s2))

/** Picture with a letter placeholder; loads (and caches) only when it's composed, i.e. on screen. */
@Composable
fun Thumb(req: Img?, label: String, modifier: Modifier = Modifier, px: Int = 300, corner: Dp = 8.dp, fontSize: TextUnit = 16.sp,
          contentScale: ContentScale = ContentScale.Crop, alsoLarge: Img? = null, placeholder: Boolean = true, fallback: Img? = null,
          colorFilter: androidx.compose.ui.graphics.ColorFilter? = null) {
    Box(modifier.clip(RoundedCornerShape(corner)).let { if (placeholder) it.background(phBrush) else it }, contentAlignment = Alignment.Center) {
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♫"
        if (placeholder) Text(letter, color = C.mu, fontWeight = FontWeight.Bold, fontSize = fontSize)
        if (req != null || fallback != null) {
            val epoch = ImageLoader.epoch
            val bmp by produceState((req?.let { ImageLoader.cached(it, px) } ?: fallback?.let { ImageLoader.cached(it, px) })?.asImageBitmap(), req, fallback, epoch) {
                // main picture, else the fallback (e.g. the Deezer/iTunes cover); try again a few times if neither loads yet
                // not in memory yet: wait a moment first, so flinging through a long list doesn't decode every row it passes
                if (value == null) delay(90)
                for (wait in longArrayOf(0, 3000, 10000, 30000)) {
                    if (wait > 0) delay(wait)
                    val b = req?.let { ImageLoader.load(it, px) } ?: fallback?.let { ImageLoader.load(it, px) }
                    if (b != null) { value = b.asImageBitmap(); break }
                }
            }
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale, colorFilter = colorFilter) }
        }
        if (alsoLarge != null) {
            val big by produceState<ImageBitmap?>(ImageLoader.cached(alsoLarge, 1024)?.asImageBitmap(), alsoLarge) { value = ImageLoader.load(alsoLarge, 1024)?.asImageBitmap() }
            big?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale, colorFilter = colorFilter) }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
        .border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(14.dp), content = content)
}

@Composable
fun CardTitle(title: String, small: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (small != null) Text(small, color = C.mu, fontSize = 12.sp)
    }
}

@Composable
fun SectionHeader(t: String) = Text(t.uppercase(), color = C.mu, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))

enum class BtnKind { Primary, Ghost, Danger, Pill }

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Primary, icon: ImageVector? = null,
        small: Boolean = false, enabled: Boolean = true) {
    val (bg, fg) = when (kind) { BtnKind.Primary -> C.ac to Color.White; BtnKind.Ghost -> C.s2 to C.tx; BtnKind.Danger -> C.danger to C.dangerTx; BtnKind.Pill -> Color(0x47000000) to Color.White }
    val shape = if (kind == BtnKind.Pill) CircleShape else RoundedCornerShape(if (small) 8.dp else 10.dp)
    Row(modifier.clip(shape).background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * .5f)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, tint = fg.copy(alpha = if (enabled) 1f else .5f), modifier = Modifier.size(if (small) 16.dp else 18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg.copy(alpha = if (enabled) 1f else .5f), fontWeight = FontWeight.SemiBold, fontSize = if (small) 13.sp else 15.sp, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.tx, size: Dp = 40.dp, iconSize: Dp = 26.dp) {
    Box(modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Wrapping row of buttons (like the old .sbtns). */
@Composable
fun BtnRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
fun Tiles(tiles: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (v, l) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Text(v, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l, color = C.mu, fontSize = 12.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Horizontal bar chart; rows are tappable when onTap is set. */
@Composable
fun Bars(rows: List<Pair<String, Int>>, onTap: ((String) -> Unit)? = null) {
    val max = maxOf(1, rows.maxOfOrNull { it.second } ?: 1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (n, c) ->
            Column(Modifier.fillMaxWidth().let { if (onTap != null) it.clickable { onTap(n) } else it }) {
                Row { Text(n, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(app.orionmd.omniplay.fmt(c), color = C.mu, fontSize = 12.sp) }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.s3)) {
                    Box(Modifier.fillMaxWidth(c.toFloat() / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(listOf(C.ac, C.ac2))))
                }
            }
        }
    }
}

/** Segmented tabs (Songs / Artists / …). */
@Composable
fun Seg(tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xBF171923)).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(3.dp)) {
        tabs.forEach { (k, l) ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(if (k == selected) C.s3 else Color.Transparent).clickable { onSelect(k) }.padding(vertical = 8.dp, horizontal = 2.dp), contentAlignment = Alignment.Center) {
                Text(l, fontSize = if (l.contains('\n')) 12.sp else 13.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = if (k == selected) C.tx else C.mu,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, password: Boolean = false, label: String? = null) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true,
        label = label?.let { { Text(it) } }, placeholder = { Text(placeholder, color = C.mu) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = C.s2, unfocusedContainerColor = C.s2, unfocusedBorderColor = C.line, focusedBorderColor = C.ac))
}

/** Compact select box. */
@Composable
fun Select(value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.s2).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Ic.arrowDrop, null, tint = C.mu, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (k, l) -> DropdownMenuItem(text = { Text(l, fontWeight = if (k == value) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onChange(k) }) }
        }
    }
}

@Composable
fun Check(checked: Boolean, label: String, bold: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = C.ac))
        Text(label, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun Progress(fraction: Float?) {
    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = C.ac, trackColor = C.s3)
    else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = C.ac, trackColor = C.s3)
}

/** Standard list row: thumbnail, two lines of text, optional trailing text. */
@Composable
fun ListRow(req: Img?, label: String, title: String, sub: String, onClick: () -> Unit, trailing: String? = null, highlight: Boolean = false,
            thumbW: Dp = 48.dp, thumbRatio: Float = 1f, badge: String? = null, fallback: Img? = null,
            downloaded: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Thumb(req, label, Modifier.width(thumbW).aspectRatio(thumbRatio), px = if (thumbW <= 56.dp) 160 else 300, fallback = fallback)
            if (badge != null) Text(badge, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (highlight) C.ac else C.tx, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = C.mu, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (downloaded) Icon(Ic.done, "Downloaded", tint = C.ac2, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        if (trailing != null) Text(trailing, color = C.mu, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun Muted(t: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp) = Text(t, color = C.mu, fontSize = size, modifier = modifier)

@Composable
fun Empty(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = C.mu, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(14.dp)); Btn(action, onAction) }
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

/**
 * Fast scroller for long lists (shown from [min] items): drag the handle on the right edge to fly through thousands of
 * items; a bubble shows where you are (the letter, year or genre of the current sort).
 */
@Composable
fun FastScroll(count: Int, first: () -> Int, scrollTo: suspend (Int) -> Unit, label: (Int) -> String?, modifier: Modifier = Modifier, min: Int = 60) {
    if (count < min) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var frac by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var h by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val shown = if (dragging) frac else (first().toFloat() / (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    val dens = androidx.compose.ui.platform.LocalDensity.current
    fun jump(y: Float) { frac = (y / h).coerceIn(0f, 1f); val i = (frac * (count - 1)).toInt(); scope.launch { scrollTo(i) } }
    Box(modifier.fillMaxHeight().width(44.dp).onSizeChanged { h = it.height.coerceAtLeast(1) }
        .pointerInput(count) {
            detectVerticalDragGestures(onDragStart = { dragging = true; jump(it.y) }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { ch, _ -> ch.consume(); jump(ch.position.y) }
        }.pointerInput(count) { detectTapGestures { jump(it.y) } }) {
        val thumbH = 44.dp
        val y = with(dens) { ((h - thumbH.toPx()) * shown).toDp() }
        Box(Modifier.align(Alignment.TopEnd).offset(y = y).padding(end = 4.dp).size(8.dp, thumbH).clip(RoundedCornerShape(4.dp)).background(if (dragging) C.ac else C.mu.copy(alpha = .55f)))
        if (dragging) label((frac * (count - 1)).toInt())?.let { l ->
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-56).dp, y = y - 4.dp).size(52.dp).clip(RoundedCornerShape(26.dp, 26.dp, 4.dp, 26.dp)).background(C.ac), contentAlignment = Alignment.Center) {
                Text(l, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (l.length > 3) 12.sp else 22.sp, maxLines = 1)
            }
        }
    }
}

/** First letter (or # for numbers / symbols) of a sort value, for the fast-scroll bubble. */
fun initial(s: String?): String { val c = s?.trim()?.removePrefix("The ")?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"; return if (c.isLetter()) c.toString() else "#" }
