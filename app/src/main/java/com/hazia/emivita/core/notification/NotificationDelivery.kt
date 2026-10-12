package com.hazia.emivita.core.notification

import androidx.annotation.StringRes
import com.hazia.emivita.R

/**
 * What, if anything, stands between a reminder and the user actually seeing
 * it (spec §7 R58), shown as a passive status card beside
 * `FullScreenAlarmAccessCard` and `BatteryOptimizationCard`, never a prompt.
 *
 * [postIfPermitted] fails silently and `EmivitaApp` asks for the permission
 * only once (PERM-023/024), so without this card a user who declined, or who
 * switched notifications off later, has armed alarms that never post. Pure
 * functions over plain values, so the decision is unit-testable without
 * Robolectric or a NotificationManager.
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
     * Only `NONE` counts: a tier dropped to silent or min is a preference,
     * and warning about it would be the nagging PERM-024 names. `NONE` is the
     * silent failure no permission check can see, an Alarm-tier medicine
     * posting to a channel nobody will ever look at.
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
 * the composable (spec §9 #19), so the decision stays pure and the words live
 * in resources, as with EmergencyCardExporter.CardLabels. The hardcoded-text
 * gate cannot see a returned `when` branch later handed to Text(), so only
 * en-XA on the device catches a literal slipping back in here.
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
