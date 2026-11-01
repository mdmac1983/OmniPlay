package app.orionmd.omniplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ApiTest {
    @Test fun a_rate_limited_request_is_asked_again_until_it_answers() {
        var calls = 0
        val r = Api.retry { calls++; if (calls < 3) throw RateLimited(1) else "ok" }
        assertEquals("ok", r); assertEquals(3, calls)
    }

    @Test fun other_failures_are_not_retried() {
        var calls = 0
        try { Api.retry<String> { calls++; throw java.io.IOException("offline") } } catch (_: java.io.IOException) {}
        assertEquals(1, calls)
    }

    @Test fun every_item_is_done_several_at_a_time() {
        val seen = Collections.synchronizedSet(HashSet<Int>()); val running = AtomicInteger(); val most = AtomicInteger()
        val start = System.currentTimeMillis()
        Api.each((1..60).toList()) { i ->
            val r = running.incrementAndGet(); most.accumulateAndGet(r, ::maxOf)
            Thread.sleep(50); seen.add(i); running.decrementAndGet()
        }
        assertEquals(60, seen.size)
        assertTrue("ran ${most.get()} at once", most.get() in 2..Api.PARALLEL)
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 60 * 50)   // faster than one by one
    }

    @Test fun stopping_skips_the_rest() {
        val stop = AtomicBoolean(false); val n = AtomicInteger()
        Api.each((1..200).toList(), stop) { if (n.incrementAndGet() >= 10) stop.set(true); Thread.sleep(5) }
        assertTrue("did ${n.get()}", n.get() < 200)
    }
}
