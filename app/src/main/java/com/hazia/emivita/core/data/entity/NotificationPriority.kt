package com.hazia.emivita.core.data.entity

import kotlinx.serialization.Serializable

/** Standard = default channel, High = high-priority channel, Alarm = full-screen
 *  alarm activity (bypasses silent/DND), per the "triangle" alarm screen design.
 *  A DoseGroup's priority overrides its member reminders' medicine-level priority
 *  while the grouping is active -- see DoseGroup.priority. */
@Serializable
enum class NotificationPriority { STANDARD, HIGH, ALARM }
