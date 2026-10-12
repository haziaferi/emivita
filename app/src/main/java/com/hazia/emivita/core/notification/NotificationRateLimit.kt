package com.hazia.emivita.core.notification

/**
 * Whether a notification posted now should be silent because another one
 * sounded within [WINDOW_MILLIS] (§9 #25, Appendix E B-06), so three medicines
 * at 08:00 chime once and the rest still show and vibrate without alerting
 * again (idea from medTimer's `NotificationFactory.shouldBeSilent`, MIT,
 * written from the description). Process-scoped on purpose, since the window
 * is ten seconds and a value that survived a restart would only be stale.
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
