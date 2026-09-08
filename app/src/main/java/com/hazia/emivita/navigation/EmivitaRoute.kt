package com.hazia.emivita.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import androidx.annotation.StringRes
import com.hazia.emivita.R

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

    // Same forms as Add (spec §9 item 1), reached from MedicineDetail rather
    // than the bottom-nav shell. Delete needs no route of its own -- it's a
    // confirm dialog on MedicineDetail, not a screen.
    @Serializable data class EditMedicine(val medicineId: Long) : EmivitaRoute
    @Serializable data class EditReminder(val reminderId: Long) : EmivitaRoute

    // Stock & Expiration (spec §6/§9 #1) -- one entry point for
    // Medicine.stockInfo plus the medicine's LowStockAlert/ExpirationAlert
    // reminders, reached from MedicineDetailScreen.
    @Serializable data class StockExpiration(val medicineId: Long) : EmivitaRoute

    // The three Settings destinations complex enough to warrant a real route
    // rather than a sheet: Backup & Sync (spec §9 #9 -- export/restore/
    // auto-sync, each its own SAF flow), Notifications & Alerts, and
    // Appearance. Alarms & Snooze, Weekend Mode, and the biometric-lock
    // toggle (spec §9 #11) all live inline/as bottom sheets on
    // SettingsScreen itself instead -- see its own doc comment for why.
    @Serializable data object DataBackupSettings : EmivitaRoute

    @Serializable data object NotificationsAlertsSettings : EmivitaRoute
    @Serializable data object AppearanceSettings : EmivitaRoute

    /** Medical ID (spec §7 R48). A real route rather than a bottom sheet,
     *  unlike Alarms & Snooze and Weekend Mode: it is eight fields plus a SAF
     *  export flow, which is the complexity SettingsScreen's own doc comment
     *  says still warrants a destination. */
    @Serializable data object MedicalIdSettings : EmivitaRoute
}

data class BottomNavItem(
    val route: EmivitaRoute,
    /** @StringRes: `bottomNavItems` below is a top-level val, not a
     *  composable. These four are the app's most-read strings and were the
     *  ones a sink-based scan missed, because they reach Text() through a
     *  data class rather than as a literal argument (spec §9 #19). */
    @StringRes val label: Int,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(EmivitaRoute.Today, R.string.nav_today, Icons.Filled.CalendarToday),
    BottomNavItem(EmivitaRoute.Medicines, R.string.nav_medicines, Icons.Filled.Medication),
    BottomNavItem(EmivitaRoute.History, R.string.nav_history, Icons.Filled.BarChart),
    BottomNavItem(EmivitaRoute.Settings, R.string.nav_settings, Icons.Filled.Settings),
)
