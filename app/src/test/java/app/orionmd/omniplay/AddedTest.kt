package app.orionmd.omniplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddedTest {
    @Test fun firstSyncUsesUploadOrderNotFileDates() {
        // file 30 was uploaded last but carries an old creation date (as pCloud's apps upload them)
        val known = HashMap<Long, Long>()
        val d = Added.dates(listOf(10, 20, 30), listOf(5_000, 6_000, 1_000), listOf(10, 20, 30), known, 99_000)
        assertTrue(d[2] > d[1] && d[1] > d[0])
    }

    @Test fun newFilesAreAddedAtTheSync() {
        val known = HashMap<Long, Long>()
        Added.dates(listOf(1, 2), listOf(100, 200), null, known, 1_000)
        val d = Added.dates(listOf(1, 2, 3), listOf(100, 200, 50), null, known, 5_000)
        assertEquals(100L, d[0]); assertEquals(200L, d[1]); assertEquals(5_000L, d[2])
    }

    @Test fun tiesKeepAnOrderAndRemovedFilesAreForgotten() {
        val known = HashMap<Long, Long>()
        val d = Added.dates(listOf(1, 2, 3), listOf(0, 0, 0), listOf(1, 2, 3), known, 10)
        assertTrue(d[2] > d[1] && d[1] > d[0])
        Added.dates(listOf(1, 3), listOf(0, 0), listOf(1, 3), known, 20)
        assertEquals(setOf(1L, 3L), known.keys)
        assertEquals(known, Added.parse(Added.json(known)))
    }
}
