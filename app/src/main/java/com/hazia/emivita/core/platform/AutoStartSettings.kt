package com.hazia.emivita.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

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
        // ColorOS 13+ / OxygenOS 13+ (OnePlus LE2123 on Android 14, §7 R81): the safecenter
        // packages above are gone and the startup list lives in the battery app. Exported, but
        // on that phone guarded by `oplus.permission.OPLUS_COMPONENT_SAFE` (a signature
        // permission), so [intentFor] skips it there and the button is not drawn; kept for
        // ColorOS builds that leave it open.
        ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
    )

    /**
     * The first page that exists on this phone *and that this app may start*, or null.
     * Resolving is not enough: an exported activity can still require a permission
     * (`activityInfo.permission`), and `startActivity` then throws `SecurityException`
     * (§7 R81, the LE2123). A button that throws is worse than no button.
     */
    fun intentFor(context: Context): Intent? = COMPONENTS.asSequence()
        .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { intent ->
            val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@firstOrNull false
            val permission = info.permission
            permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
}
