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
)
