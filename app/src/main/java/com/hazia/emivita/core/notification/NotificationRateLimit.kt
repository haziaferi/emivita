package com.hazia.emivita.core.notification

/**
 * Whether a notification posted now should be silent because another one
 * sounded within [WINDOW_MILLIS] (§9 #25; Appendix E B-06). Three medicines
 * at 08:00 used to be three chimes in a row; the second and third still show,
 * still vibrate the channel's pattern, but do not alert again. Idea from
 * medTimer's `NotificationFactory.shouldBeSilent` (MIT); written from the
 * description. Process-scoped on purpose: the window is ten seconds, and a
 * value that survived a restart would only ever be stale.
 */
object NotificationRateLimit {
    const val WINDOW_MILLIS = 10_000L

    @Volatile
    private var lastAlertedEpochMillis: Long = 0L

    /** Records this post as alerting unless one alerted inside the window. */
    @Synchronized
    fun shouldBeSilent(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val silent = nowEpochMillis - lastAlertedEpochMillis < WINDOW_MILLIS
        if (!silent) lastAlertedEpochMillis = nowEpochMillis
        return silent
    }

    /** Tests only. */
    @Synchronized
    fun reset() { lastAlertedEpochMillis = 0L }
}
