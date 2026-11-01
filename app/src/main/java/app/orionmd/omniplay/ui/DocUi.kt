package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Page
import kotlinx.coroutines.launch

/** The Guide / Features documents (bundled from GUIDE.md / FEATURES.md at build time), rendered from Markdown. */
@Composable
fun DocPage(st: AppState, p: Page.Doc, pad: PaddingValues) {
    val ctx = LocalContext.current
    val text = remember(p.file) { try { ctx.assets.open("docs/${p.file}").bufferedReader().use { it.readText() } } catch (_: Exception) { "# ${p.title}\n\nThis document isn't included in this build." } }
    val blocks = remember(text) { Md.parse(text) }
    val anchors = remember(blocks) { blocks.withIndex().filter { it.value is Md.Block.Heading }.associate { Md.slug((it.value as Md.Block.Heading).text) to it.index } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val open = { url: String ->
        if (url.startsWith("#")) anchors[url.removePrefix("#")]?.let { scope.launch { list.animateScrollToItem(it) } }
        else try { st.host.launch(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        Unit
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(st, p.title)
        LazyColumn(state = list, contentPadding = contentPad(pad)) {
            itemsIndexed(blocks) { _, b -> MdBlock(b, open) }
        }
    }
}

@Composable
private fun MdBlock(b: Md.Block, open: (String) -> Unit) {
    when (b) {
        is Md.Block.Heading -> Text(Md.inline(b.text, open), fontWeight = FontWeight.Bold, color = if (b.level <= 2) C.ac else C.tx,
            fontSize = when (b.level) { 1 -> 24.sp; 2 -> 19.sp; else -> 16.sp }, modifier = Modifier.padding(top = if (b.level == 1) 8.dp else 18.dp, bottom = 6.dp))
        is Md.Block.Para -> Text(Md.inline(b.text, open), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 4.dp))
        is Md.Block.Item -> Row(Modifier.padding(start = (b.depth * 16).dp, top = 2.dp, bottom = 2.dp)) {
            Text(b.marker, color = C.ac, fontSize = 14.sp, modifier = Modifier.width(if (b.marker.length > 2) 26.dp else 16.dp))
            Text(Md.inline(b.text, open), fontSize = 14.sp, lineHeight = 20.sp)
        }
        is Md.Block.Table -> Column(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.card).horizontalScroll(rememberScrollState())) {
            b.rows.forEachIndexed { r, cells ->
                Row(Modifier.background(if (r == 0) C.s2 else Color.Transparent)) {
                    cells.forEachIndexed { i, c ->
                        Text(Md.inline(c, open), fontSize = 13.sp, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(if (i == 0) 150.dp else 240.dp).padding(8.dp))
                    }
                }
                if (r < b.rows.lastIndex) HorizontalDivider(color = C.line)
            }
        }
        is Md.Block.Code -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp)).background(C.s2).padding(10.dp))
        Md.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 12.dp), color = C.line)
    }
}

/** A small Markdown reader: headings, paragraphs, (nested) bullet / numbered lists, tables, code blocks, rules, inline styles and links. */
object Md {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Item(val depth: Int, val marker: String, val text: String) : Block()
        data class Table(val rows: List<List<String>>) : Block()
        data class Code(val text: String) : Block()
        data object Rule : Block()
    }

    private val bullet = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.setLength(0) }
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.startsWith("```") -> { flush(); val sb = StringBuilder(); i++; while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.appendLine(lines[i]); i++ }; out += Block.Code(sb.toString().trimEnd()) }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); val n = t.takeWhile { it == '#' }.length; out += Block.Heading(n, t.drop(n).trim()) }
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += Block.Rule }
                t.startsWith("|") -> {
                    flush(); val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim().trim('|').split('|').map { it.trim() }
                        if (!row.all { it.matches(Regex("^:?-{2,}:?$")) }) rows += row
                        i++
                    }
                    out += Block.Table(rows); continue
                }
                bullet.matches(l) -> {
                    flush(); val m = bullet.find(l)!!
                    val depth = m.groupValues[1].replace("\t", "  ").length / 2
                    val mk = m.groupValues[2].let { if (it[0].isDigit()) it.trimEnd(')').trimEnd('.') + "." else "•" }
                    val sb = StringBuilder(m.groupValues[3])
                    // continuation lines (indented, not a new item)
                    while (i + 1 < lines.size && lines[i + 1].isNotBlank() && lines[i + 1].startsWith(" ") && !bullet.matches(lines[i + 1]) && !lines[i + 1].trim().startsWith("|")) { i++; sb.append(' ').append(lines[i].trim()) }
                    out += Block.Item(depth, mk, sb.toString())
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    /** GitHub-style anchor for a heading ("2. Trial & activation" → "2-trial--activation"). */
    fun slug(h: String) = h.lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.replace(' ', '-')

    private val token = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?!\\w)|(?<!\\w)_([^_\\s][^_]*?)_(?!\\w)")

    fun inline(s: String, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        var pos = 0
        for (m in token.findAll(s)) {
            append(s.substring(pos, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(g[1], open)) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = C.s3, fontSize = 13.sp)) { append(g[2]) }
                g[3].isNotEmpty() -> {
                    val url = g[4]
                    pushLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = C.ac2))) { open(url) })
                    append(inline(g[3], open)); pop()
                }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            }
            pos = m.range.last + 1
        }
        append(s.substring(pos))
    }
}
