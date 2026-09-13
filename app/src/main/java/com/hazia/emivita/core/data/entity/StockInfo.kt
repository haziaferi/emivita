package com.hazia.emivita.core.data.entity

import kotlinx.serialization.Serializable

/** Embedded (not a separate table) since it's always 1:1 with a Medicine and
 *  always read/written together with it -- current stock quantity, refill
 *  bookkeeping, and expiration dates. The LOW_STOCK_ALERT / EXPIRATION_ALERT
 *  Reminder types define *when/how often* to notify about crossing these
 *  values; this struct is just the persistent data they read. */
@Serializable
data class StockInfo(
    val quantity: Double = 0.0,
    val unit: String? = null,
    val refillQuantity: Double? = null,
    val productionDateEpochDay: Long? = null,
    val expirationDateEpochDay: Long? = null,
    /** Package opened on this day, and usable for this many days after
     *  (§9 #27; HM-10 stored the date and did nothing with it -- here it
     *  feeds [effectiveExpirationEpochDay], so eye drops opened in March
     *  expire in April whatever the box says). Either alone does nothing. */
    val openedDateEpochDay: Long? = null,
    val useWithinDaysAfterOpening: Int? = null,
) {
    /** The earlier of the printed expiry and opened + shelf life; the one
     *  date the expiry alert, the card and the sort read. */
    val effectiveExpirationEpochDay: Long?
        get() {
            val afterOpening = if (openedDateEpochDay != null && useWithinDaysAfterOpening != null) openedDateEpochDay + useWithinDaysAfterOpening else null
            return listOfNotNull(expirationDateEpochDay, afterOpening).minOrNull()
        }
}
