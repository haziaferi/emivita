package com.hazia.emivita.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Type-safe Navigation-Compose routes. Each top-level destination is a plain
 *  object (no args); anything needing an argument (e.g. a specific medicine
 *  id) gets its own @Serializable data class as it's built. */
sealed interface EmivitaRoute {
    @Serializable data object Today : EmivitaRoute
    @Serializable data object Medicines : EmivitaRoute
    @Serializable data object History : EmivitaRoute
    @Serializable data object Settings : EmivitaRoute

    // Pushed on top of the bottom-nav shell, not part of it (spec §9 item 1).
    // prefilledBarcode: set when "Scan to find" (Medicines list) doesn't match
    // anything -- avoids making the user re-scan the same code on the Add form.
    @Serializable data class AddMedicine(val prefilledBarcode: String? = null) : EmivitaRoute
    @Serializable data class MedicineDetail(val medicineId: Long) : EmivitaRoute
    @Serializable data class AddReminder(val medicineId: Long) : EmivitaRoute

    // Edit is the "immediate follow-up" to add+list called out in spec §9
    // item 1 -- same forms as Add, reached from MedicineDetail rather than
    // the bottom-nav shell. Delete is not here yet (see EditMedicineScreen's
    // doc comment): the deleted-medicine cascade-vs-archive question is still
    // open in the spec.
    @Serializable data class EditMedicine(val medicineId: Long) : EmivitaRoute
    @Serializable data class EditReminder(val reminderId: Long) : EmivitaRoute

    // Stock & Expiration (spec §6/§9 #1) -- one entry point for
    // Medicine.stockInfo plus the medicine's LowStockAlert/ExpirationAlert
    // reminders, reached from MedicineDetailScreen.
    @Serializable data class StockExpiration(val medicineId: Long) : EmivitaRoute

    // Backup & Sync (spec §9 #9) -- the one Settings destination complex
    // enough (export/restore/auto-sync, each its own SAF flow) to still
    // warrant a real route. Alarms & Snooze, Weekend Mode, and the
    // biometric-lock toggle (spec §9 #11) all live inline/as bottom sheets
    // on SettingsScreen itself instead -- see its own doc comment for why.
    @Serializable data object DataBackupSettings : EmivitaRoute

    @Serializable data object NotificationsAlertsSettings : EmivitaRoute
    @Serializable data object AppearanceSettings : EmivitaRoute
}

data class BottomNavItem(
    val route: EmivitaRoute,
    val label: String,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(EmivitaRoute.Today, "Today", Icons.Filled.CalendarToday),
    BottomNavItem(EmivitaRoute.Medicines, "Medicines", Icons.Filled.Medication),
    BottomNavItem(EmivitaRoute.History, "History", Icons.Filled.BarChart),
    BottomNavItem(EmivitaRoute.Settings, "Settings", Icons.Filled.Settings),
)
