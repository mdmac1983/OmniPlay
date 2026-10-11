package app.orionmd.omniplay

import org.json.JSONObject

/**
 * "Date added" — when a file arrived in your library, which is what Recently added and the Date added sort show.
 *
 * Cloud dates can't be trusted for this: pCloud's apps upload a file with its original creation date, so a song
 * added today can say 2014. So the app remembers when it first saw each file: a file that turns up in a sync is
 * "added" at that sync. For the files that were already there the first time (or after an update), the files' own
 * dates are used — in upload order where the cloud gives one (pCloud file ids go up with every upload), so the
 * newest uploads still come first.
 */
object Added {
    /**
     * Returns the "added" date for each of [ids]. [own] = the files' own dates, [seq] = upload order (higher = later,
     * or null when unknown). [known] (id → date from earlier syncs) is updated in place; ids no longer present are
     * dropped.
     */
    fun dates(ids: List<Long>, own: List<Long>, seq: List<Long>?, known: MutableMap<Long, Long>, now: Long): List<Long> {
        val idx = ids.indices
        if (known.isEmpty()) {
            // first time: the files' own dates, re-dealt in upload order when it's known, strictly increasing so the
            // order survives ties (e.g. a whole folder copied at once)
            val order = if (seq != null) idx.sortedBy { seq[it] } else idx.sortedBy { own[it] }
            val sorted = own.sorted()
            var prev = Long.MIN_VALUE
            order.forEachIndexed { k, i -> val d = maxOf(sorted[k], if (prev == Long.MIN_VALUE) sorted[k] else prev + 1); known[ids[i]] = d; prev = d }
        } else {
            // new since the last sync: added now (newest upload last, a millisecond apart)
            val fresh = idx.filter { ids[it] !in known }
            val order = if (seq != null) fresh.sortedBy { seq[it] } else fresh.sortedBy { own[it] }
            order.forEachIndexed { k, i -> known[ids[i]] = now - (order.size - 1 - k) }
        }
        known.keys.retainAll(ids.toHashSet())
        return ids.map { known[it] ?: now }
    }

    fun parse(text: String?): MutableMap<Long, Long> = HashMap<Long, Long>().also { m ->
        try { val o = JSONObject(text ?: return@also); for (k in o.keys()) k.toLongOrNull()?.let { m[it] = o.optLong(k) } } catch (_: Exception) {}
    }
    fun json(m: Map<Long, Long>): String = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v) } }.toString()
}
