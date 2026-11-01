package app.orionmd.omniplay

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A service said "too many requests": wait [waitMs] (or a moment) and ask again. */
class RateLimited(val waitMs: Long = 0) : Exception("Too many requests — slowing down")

/**
 * Online look-ups run without fixed pauses, several at a time. Only when a service pushes back (HTTP 429, Deezer's
 * "quota exceeded") does a request wait and retry, so the answer isn't lost or saved as "no match".
 */
object Api {
    const val PARALLEL = 6

    fun <T> retry(block: () -> T): T {
        var n = 0
        while (true) {
            try { return block() } catch (e: RateLimited) {
                if (++n > 6) throw e
                Thread.sleep(maxOf(e.waitMs, 500L shl (n - 1)).coerceAtMost(15_000))
            }
        }
    }

    /**
     * Runs [work] for every item, [n] at a time; blocks until all are done or [stop] is set. [work] gets the item;
     * [done] is called after each with how many have finished.
     */
    fun <T> each(list: List<T>, stop: AtomicBoolean = AtomicBoolean(false), n: Int = PARALLEL, done: (Int) -> Unit = {}, work: (T) -> Unit) {
        if (list.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(n, list.size))
        val count = AtomicInteger()
        try {
            list.map { x -> pool.submit { if (!stop.get()) { try { work(x) } catch (_: Exception) {}; done(count.incrementAndGet()) } } }
                .forEach { try { it.get() } catch (_: Exception) {} }
        } finally { pool.shutdownNow() }
    }
}
