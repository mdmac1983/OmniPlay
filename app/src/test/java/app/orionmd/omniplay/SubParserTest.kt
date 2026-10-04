package app.orionmd.omniplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SubParserTest {
    @Test fun srt() {
        val s = "1\n00:00:01,000 --> 00:00:03,500\nHello <i>there</i>\nsecond line\n\n2\n00:00:04,000 --> 00:00:05,000\nBye\n3\n00:00:06,000 --> 00:00:07,250\nNo blank line before me\n"
        val c = SubParser.parse(s, "a.srt")
        assertEquals(3, c.size)
        assertEquals(SubCue(1000, 3500, "Hello <i>there</i>\nsecond line"), c[0])
        assertEquals("Bye", c[1].text); assertEquals(7250, c[2].end)
        assertEquals(listOf(c[0]), SubParser.at(c, 2000)); assertEquals(emptyList<SubCue>(), SubParser.at(c, 3700))
    }
    @Test fun vtt() {
        val s = "WEBVTT\n\nNOTE hi\n\n00:01.000 --> 00:02.000 align:middle\n<v Bob>Hi <c.yellow>you</c>\n\n01:00:00.500 --> 01:00:01.000\nLate\n"
        val c = SubParser.parse(s, "a.vtt")
        assertEquals(2, c.size); assertEquals("Hi you", c[0].text); assertEquals(3_600_500L, c[1].start)
    }
    @Test fun ass() {
        val s = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.50,0:00:02.00,Default,,0,0,0,,{\\an8}{\\i1}Hi{\\i0}, you\\Nthere\n"
        val c = SubParser.parse(s, "a.ass")
        assertEquals(1, c.size); assertEquals(SubCue(1500, 2000, "<i>Hi</i>, you\nthere"), c[0])
    }
    @Test fun encodings() {
        val latin = "Ça marche — très bien".toByteArray(charset("windows-1252"))
        assertEquals("Ça marche — très bien", SubParser.decode(latin, "auto"))           // not valid UTF-8 → Windows-1252
        assertEquals("Привет", SubParser.decode("Привет".toByteArray(charset("windows-1251")), "windows-1251"))
        assertEquals("ünï", SubParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ünï".toByteArray(), "windows-1251"))  // BOM wins
    }
    @Test fun markdown_reader() {
        val b = app.orionmd.omniplay.ui.Md.parse("# Title\n\nSome **bold** text\nwraps.\n\n- one\n  - two\n1. first\n\n| A | B |\n|---|---|\n| x | y |\n\n---\n")
        assertEquals(app.orionmd.omniplay.ui.Md.Block.Heading(1, "Title"), b[0])
        assertEquals(app.orionmd.omniplay.ui.Md.Block.Para("Some **bold** text wraps."), b[1])
        assertEquals(app.orionmd.omniplay.ui.Md.Block.Item(1, "•", "two"), b[3])
        assertEquals(app.orionmd.omniplay.ui.Md.Block.Item(0, "1.", "first"), b[4])
        assertEquals(listOf(listOf("A", "B"), listOf("x", "y")), (b[5] as app.orionmd.omniplay.ui.Md.Block.Table).rows)
        assertEquals("2-trial--activation", app.orionmd.omniplay.ui.Md.slug("2. Trial & activation"))
    }
}
