package app.orionmd.omniplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTest {
    private fun song(src: String, folder: String) = Item(i = 1, n = "a.mp3", c = 3, s = 0, f = folder, src = src, u = "", cr = 0, t = null, a = null, al = null, g = null,
        tr = 0, d = 0, y = 0, th = false, k = null, show = null, se = 0, ep = 0, pt = null, py = 0, concert = false)

    @Test fun a_locked_source_hides_everything_from_it() {
        assertTrue(Lock.hides(setOf("src:f1"), song("f1", "Music/Any")))
        assertFalse(Lock.hides(setOf("src:f1"), song("device", "Music/Any")))
    }

    @Test fun a_locked_folder_hides_its_files_and_subfolders_only() {
        val l = setOf("dir:Music/Private")
        assertTrue(Lock.hides(l, song("device", "Music/Private")))
        assertTrue(Lock.hides(l, song("device", "Music/Private/2024")))
        assertFalse(Lock.hides(l, song("device", "Music/Private Mixes")))   // a different folder with a similar name
        assertFalse(Lock.hides(l, song("device", "Music")))
    }

    @Test fun nothing_locked_hides_nothing() = assertFalse(Lock.hides(emptySet(), song("device", "Music")))
}
