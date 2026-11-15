package com.musicd.migrate

/**
 * How fast a SpotifyClient may ask, shared by every worker using it.
 *
 * Two mechanisms, both learned from real libraries:
 *
 * ONE WORKER'S 429 HOLDS ALL OF THEM. Lookups run four at a time, and four
 * workers each backing off privately all resume at the same instant and earn
 * the next 429 together — which is how a single rate-limit turns into a run
 * that never gets going again. Spotify counts requests per APPLICATION, so the
 * hold is per client.
 *
 * AND A THROTTLED RUN IS SPACED OUT afterwards — see the constants below.
 *
 * Split out of SpotifyClient.kt.
 */
class SpotifyPacer(private val sleeper: (Long) -> Unit) {

    /** When every worker may fire again. */
    @Volatile private var holdUntil = 0L

    /** The gap enforced between requests, 0 until the service complains. */
    @Volatile var paceMs = 0L
        private set

    private val lock = Any()
    private var nextSlot = 0L
    private var sinceThrottle = 0

    internal fun nextSlotForTest(): Long = synchronized(lock) { nextSlot }
    internal fun setPaceForTest(ms: Long) { synchronized(lock) { paceMs = ms } }

    /** Before a request: sit out any hold, then take a paced slot. */
    fun waitTurn() {
        val hold = holdUntil - System.currentTimeMillis()
        if (hold > 0) sleeper(hold)

        // The slot is claimed UNDER THE LOCK, before the sleep, so four
        // workers get four different slots rather than all reading the same
        // one. A gap of 0 — a run that has never been throttled — makes this
        // a no-op, and the guard keeps such a run out of here altogether.
        if (paceMs > 0) {
            val wait = synchronized(lock) {
                val now = System.currentTimeMillis()
                val slot = maxOf(now, nextSlot)
                nextSlot = slot + paceMs
                slot - now
            }
            if (wait > 0) sleeper(wait)
        }
    }

    /**
     * A 429 asked for [waitMs]: hold everyone that long, and ask more slowly
     * from now on. Doubling rather than a fixed step: the gap that works is
     * unknown and the Client ID is shared, so converge on it instead of
     * guessing.
     */
    fun throttled(waitMs: Long) {
        synchronized(lock) {
            paceMs = minOf(maxOf(paceMs * 2, PACE_START_MS), PACE_MAX_MS)
            sinceThrottle = 0
        }
        holdUntil = System.currentTimeMillis() + waitMs
    }

    /** A long run of successes eases the gap back, so one bad patch does not
     *  slow the rest of a two-hour migration for ever. */
    fun succeeded() {
        if (paceMs <= 0) return
        synchronized(lock) {
            if (++sinceThrottle >= PACE_DECAY_AFTER) {
                sinceThrottle = 0
                paceMs = if (paceMs <= PACE_START_MS) 0L else paceMs * 3 / 4
            }
        }
    }

    companion object {
        /*
         * PACING, so a run stops earning 429s in the first place.
         *
         * Measured on a real 9,635-album Roon library into Spotify: four
         * workers burst past Spotify's rolling window within the first few
         * requests, earn a 429 with a twenty second Retry-After, wait it out,
         * burst again. The progress counter sat at zero and the page said
         * "rate limiting this app — waiting 20s (3 so far)". Obeying the delay
         * is necessary but not sufficient: something has to stop us asking too
         * fast.
         *
         * So after the FIRST 429 the client spaces its requests, and adapts:
         * double the gap on each further 429, decay back towards none after a
         * long run of successes. A healthy run is never slowed — the gap
         * starts at zero and nothing paces until the service complains — so
         * Qobuz, small libraries and the tests are all untouched.
         *
         * The arithmetic is why this is worth it. That library is one search
         * plus up to two track-listing reads per album, so about 29,000
         * requests. Paced just under the limit that is a couple of hours and
         * it finishes; bursting into a 20s wait every few requests, it never
         * does.
         */
        const val PACE_START_MS = 250L
        const val PACE_MAX_MS = 2_000L

        /** Consecutive successes before the gap is eased back. */
        const val PACE_DECAY_AFTER = 50
    }
}
