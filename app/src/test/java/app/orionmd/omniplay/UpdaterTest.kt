package app.orionmd.omniplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions_compare_as_numbers() {
        assertTrue(Updater.newer("4.10", "4.9"))
        assertTrue(Updater.newer("5.0", "4.9"))
        assertTrue(Updater.newer("4.9", "4.8"))
        assertFalse(Updater.newer("4.8", "4.8"))
        assertFalse(Updater.newer("4.8", "4.10"))
        assertFalse(Updater.newer("3.9", "4.0"))
    }
}
