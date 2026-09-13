package com.hazia.emivita.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.di.LocalCurrentProfileId
import com.hazia.emivita.feature.history.HistoryScreen
import com.hazia.emivita.feature.medicines.AddMedicineScreen
import com.hazia.emivita.feature.medicines.EditMedicineScreen
import com.hazia.emivita.feature.medicines.MedicineDetailScreen
import com.hazia.emivita.feature.medicines.MedicinesScreen
import com.hazia.emivita.feature.medicines.StockExpirationScreen
import com.hazia.emivita.feature.reminders.AddReminderScreen
import com.hazia.emivita.feature.reminders.EditReminderScreen
import com.hazia.emivita.feature.settings.AppearanceScreen
import com.hazia.emivita.feature.settings.BackupSyncScreen
import com.hazia.emivita.feature.settings.MedicalIdScreen
import com.hazia.emivita.feature.settings.NotificationsAlertsScreen
import com.hazia.emivita.feature.settings.SettingsDestination
import com.hazia.emivita.feature.settings.SettingsScreen
import com.hazia.emivita.feature.today.TodayScreen
import androidx.compose.ui.res.stringResource

@Composable
fun EmivitaNavHost() {
    val navController: NavHostController = rememberNavController()
    val container = LocalAppContainer.current
    val profileId = LocalCurrentProfileId.current

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            NavigationBar {
                bottomNavItems.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(item.route::class)
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = stringResource(item.label)) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = EmivitaRoute.Today,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<EmivitaRoute.Today> {
                TodayScreen(
                    profileId = profileId,
                    medicineRepository = container.medicineRepository,
                    doseEventRepository = container.doseEventRepository,
                    occurrenceProjectionCache = container.occurrenceProjectionCache,
                    settingsRepository = container.settingsRepository,
                )
            }
            composable<EmivitaRoute.Medicines> {
                MedicinesScreen(
                    profileId = profileId,
                    medicineRepository = container.medicineRepository,
                    onAddMedicine = { navController.navigate(EmivitaRoute.AddMedicine()) },
                    onAddMedicineWithBarcode = { barcode -> navController.navigate(EmivitaRoute.AddMedicine(prefilledBarcode = barcode)) },
                    onOpenMedicine = { medicineId -> navController.navigate(EmivitaRoute.MedicineDetail(medicineId)) },
                )
            }
            composable<EmivitaRoute.History> {
                HistoryScreen(
                    profileId = profileId,
                    doseEventRepository = container.doseEventRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
            composable<EmivitaRoute.Settings> {
                SettingsScreen(
                    settingsRepository = container.settingsRepository,
                    onOpenDetail = { destination ->
                        when (destination) {
                            // AlarmsSnooze/WeekendMode open as bottom sheets and
                            // PrivacySecurity's biometric toggle is inline, both
                            // handled inside SettingsScreen itself (fewer
                            // clicks/pages for content that's just a few fields
                            // or one switch); the rest still push a real route.
                            SettingsDestination.DataBackup -> navController.navigate(EmivitaRoute.DataBackupSettings)
                            SettingsDestination.NotificationsAlerts -> navController.navigate(EmivitaRoute.NotificationsAlertsSettings)
                            SettingsDestination.Appearance -> navController.navigate(EmivitaRoute.AppearanceSettings)
                            SettingsDestination.MedicalId -> navController.navigate(EmivitaRoute.MedicalIdSettings)
                            else -> Unit
                        }
                    },
                )
            }
            composable<EmivitaRoute.DataBackupSettings> {
                BackupSyncScreen(
                    backupRepository = container.backupRepository,
                    settingsRepository = container.settingsRepository,
                    autoSyncManager = container.autoSyncManager,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.NotificationsAlertsSettings> {
                NotificationsAlertsScreen(onBack = { navController.popBackStack() })
            }
            composable<EmivitaRoute.AppearanceSettings> {
                AppearanceScreen(
                    settingsRepository = container.settingsRepository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.MedicalIdSettings> {
                MedicalIdScreen(
                    profileId = profileId,
                    profileRepository = container.profileRepository,
                    medicineRepository = container.medicineRepository,
                    onBack = { navController.popBackStack() },
                )
            }

            composable<EmivitaRoute.AddMedicine> { entry ->
                val route: EmivitaRoute.AddMedicine = entry.toRoute()
                AddMedicineScreen(
                    profileId = profileId,
                    medicineRepository = container.medicineRepository,
                    prefilledBarcode = route.prefilledBarcode,
                    onSaved = { medicineId ->
                        navController.navigate(EmivitaRoute.MedicineDetail(medicineId)) {
                            popUpTo(EmivitaRoute.Medicines)
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.MedicineDetail> { entry ->
                val route: EmivitaRoute.MedicineDetail = entry.toRoute()
                MedicineDetailScreen(
                    medicineId = route.medicineId,
                    medicineRepository = container.medicineRepository,
                    reminderRepository = container.reminderRepository,
                    onAddReminder = { navController.navigate(EmivitaRoute.AddReminder(route.medicineId)) },
                    onEditMedicine = { navController.navigate(EmivitaRoute.EditMedicine(route.medicineId)) },
                    onEditReminder = { reminderId -> navController.navigate(EmivitaRoute.EditReminder(reminderId)) },
                    onOpenStockExpiration = { navController.navigate(EmivitaRoute.StockExpiration(route.medicineId)) },
                    onDeleted = { navController.popBackStack(EmivitaRoute.Medicines, inclusive = false) },
                    onDuplicated = { newId ->
                        navController.navigate(EmivitaRoute.MedicineDetail(newId)) {
                            popUpTo(EmivitaRoute.Medicines) { inclusive = false }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.StockExpiration> { entry ->
                val route: EmivitaRoute.StockExpiration = entry.toRoute()
                StockExpirationScreen(
                    medicineId = route.medicineId,
                    medicineRepository = container.medicineRepository,
                    reminderRepository = container.reminderRepository,
                    doseEventRepository = container.doseEventRepository,
                    occurrenceProjectionCache = container.occurrenceProjectionCache,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.AddReminder> { entry ->
                val route: EmivitaRoute.AddReminder = entry.toRoute()
                AddReminderScreen(
                    medicineId = route.medicineId,
                    reminderRepository = container.reminderRepository,
                    medicineRepository = container.medicineRepository,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.EditMedicine> { entry ->
                val route: EmivitaRoute.EditMedicine = entry.toRoute()
                EditMedicineScreen(
                    medicineId = route.medicineId,
                    medicineRepository = container.medicineRepository,
                    onSaved = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<EmivitaRoute.EditReminder> { entry ->
                val route: EmivitaRoute.EditReminder = entry.toRoute()
                EditReminderScreen(
                    reminderId = route.reminderId,
                    reminderRepository = container.reminderRepository,
                    medicineRepository = container.medicineRepository,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
        }
    }
}
