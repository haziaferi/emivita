package com.hazia.emivita.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The OEM "auto-start" page (§9 #29; Appendix F HM-36). On MIUI, EMUI,
 * ColorOS, Funtouch, OxygenOS and the rest, the battery-optimisation list
 * is not where alarms get killed -- this page is. The component names are
 * public knowledge (dontkillmyapp.com and every "auto-start" gist), re-typed
 * here; the manifest's `<queries>` block lists the packages so
 * `resolveActivity` can see them on API 30+. Tried in order regardless of
 * the reported manufacturer, since forks and re-brands lie about it.
 */
object AutoStartSettings {
    val COMPONENTS: List<ComponentName> = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
    )

    /** The first page that exists on this phone, or null. */
    fun intentFor(context: Context): Intent? = COMPONENTS.asSequence()
        .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
}
