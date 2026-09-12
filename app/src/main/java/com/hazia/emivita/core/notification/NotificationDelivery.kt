package com.hazia.emivita.core.notification

import androidx.annotation.StringRes
import com.hazia.emivita.R

/**
 * What, if anything, stands between a reminder and the user actually seeing
 * it (spec §7 R58).
 *
 * [postIfPermitted] already refuses to post without POST_NOTIFICATIONS, which
 * correctly avoids a crash and just as correctly says nothing: it writes one
 * `Log.w` and returns. `EmivitaApp` asks for the permission exactly once and
 * deliberately never re-prompts, which is the right call about *pressure* --
 * PERM-023/024 say respect the decision -- but it quietly became "and never
 * mention it again", which is a different thing. A user who declined once, or
 * who turned notifications off in system Settings a month later, then has an
 * app whose Today screen is populated, whose alarms are genuinely armed in
 * `dumpsys alarm`, and which will never post a reminder again.
 *
 * The app already solved this twice for weaker failures -- `FullScreenAlarmAccessCard`
 * for a degraded Alarm tier, `BatteryOptimizationCard` for late delivery. This is
 * the same shape for the strongest one, so it is a passive status card in the
 * same block, not a prompt.
 *
 * Split out as pure functions over plain values so the whole decision is unit
 * testable without Robolectric or a NotificationManager -- the same reason
 * [com.hazia.emivita.core.data.export.EmergencyCardExporter.buildLines] exists
 * apart from its PDF writer.
 */
sealed interface NotificationDelivery {

    /** Nothing in the way. The card renders nothing. */
    data object Fine : NotificationDelivery

    /** Notifications are off for the whole app: either POST_NOTIFICATIONS was
     *  never granted, or the user switched the app off in system Settings.
     *  `areNotificationsEnabled()` covers both, which is why it is the check
     *  rather than `checkSelfPermission`. */
    data object AllBlocked : NotificationDelivery

    /**
     * Notifications work, but one or more tiers sit at `IMPORTANCE_NONE` --
     * blocked outright, so nothing posted to them appears at all.
     *
     * Only `NONE` counts. A user who drops the Standard tier to silent or
     * min is expressing a preference and is not owed a warning about it;
     * flagging that would be nagging, which is the failure PERM-024 names.
     * `NONE` is different in kind: it is the third silent-failure mode and
     * the one no permission check can see, because an Alarm-tier medicine
     * "that must not be skipped" can be posting to a channel the user
     * silenced entirely and the app would never know.
     */
    data class TiersBlocked(val tiers: List<String>) : NotificationDelivery
}

/**
 * [notificationsEnabled] from `NotificationManagerCompat.areNotificationsEnabled()`,
 * [blockedTiers] from [ReminderNotificationChannels.blockedTiers].
 *
 * App-level blocking wins over blocked tiers: when nothing posts at all,
 * naming which tiers are blocked is noise on top of a bigger problem.
 */
fun notificationDelivery(
    notificationsEnabled: Boolean,
    blockedTiers: List<String>,
): NotificationDelivery = when {
    !notificationsEnabled -> NotificationDelivery.AllBlocked
    blockedTiers.isNotEmpty() -> NotificationDelivery.TiersBlocked(blockedTiers)
    else -> NotificationDelivery.Fine
}

/**
 * A piece of card text as a resource id plus its format arguments, resolved by
 * the composable (spec §9 #19). These two functions used to return English
 * strings, for JVM testability -- and stayed English through Stage A because
 * a `when` branch that is returned and later handed to Text() sits at no
 * sink the gate can see. en-XA on the device, with POST_NOTIFICATIONS
 * revoked, is what showed the card plain amid accented text. Same fix as
 * EmergencyCardExporter.CardLabels: the decision stays pure, the words move.
 */
data class CardText(@StringRes val res: Int, val args: List<Any> = emptyList())

/** Card heading, or null when there is no card to show. Says what is happening
 *  now rather than what to do about it -- the button below it does that. */
fun NotificationDelivery.cardTitle(): CardText? = when (this) {
    NotificationDelivery.Fine -> null
    NotificationDelivery.AllBlocked -> CardText(R.string.notif_card_all_blocked_title)
    is NotificationDelivery.TiersBlocked ->
        CardText(if (tiers.size == 1) R.string.notif_card_one_tier_title else R.string.notif_card_some_tiers_title)
}

/** Card body, or null when there is no card to show. */
fun NotificationDelivery.cardBody(): CardText? = when (this) {
    NotificationDelivery.Fine -> null

    // Deliberately concrete about the consequence. "Notifications are
    // disabled" is a settings fact; "no reminder will appear" is what it
    // means for someone relying on this app to take a medicine.
    NotificationDelivery.AllBlocked -> CardText(R.string.notif_card_all_blocked_body)

    // The tier names arrive already localised (ReminderNotificationChannels
    // resolves them); only the sentence around them is a resource here.
    is NotificationDelivery.TiersBlocked -> CardText(
        if (tiers.size == 1) R.string.notif_card_one_tier_body else R.string.notif_card_some_tiers_body,
        listOf(tiers.joinToString(", ")),
    )
}
