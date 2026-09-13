package com.hazia.emivita.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The app's own language (spec §9 #28; Appendix F HM-35 / MT-19). One value:
 * a BCP-47 tag, or null for "follow the system".
 *
 * Two mechanisms, chosen by SDK because minSdk is 30:
 * - **Android 13+**: [LocaleManager.setApplicationLocales]. The framework
 *   persists it, recreates the activities, and every context the process
 *   gets afterwards -- receivers, notification builders, Glance -- already
 *   speaks the language; Android's own per-app language page shows the same
 *   value, which is why the app registers its locales (`generateLocaleConfig`).
 * - **Android 11-12**: the app wraps its own contexts ([wrap]) from a
 *   synchronous [SharedPreferences][android.content.SharedPreferences] --
 *   synchronous because `attachBaseContext` runs before DataStore exists.
 *   Activities and the Application are wrapped, so screens and notifications
 *   follow; Glance widgets render in the system language (a known limit,
 *   named in R67).
 *
 * The store is written on every version so the value survives a downgrade
 * of the mechanism, and so `current` has one answer on 11-12.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** The languages the app ships, as `values-*` folders on disk; the two
     *  pseudolocales join on a debuggable build (`isPseudoLocalesEnabled`).
     *  `AppLocaleTest` pins this list to the resource folders, so a new
     *  translation is one folder plus one entry here. */
    val SHIPPED: List<String> = listOf("en")
    fun supported(context: Context): List<String> =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) SHIPPED + listOf("en-XA", "ar-XB") else SHIPPED

    /** The chosen tag, or null for the system language. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (list != null && !list.isEmpty) return list[0].toLanguageTag()
            return null
        }
        return stored(context)
    }

    fun set(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** Whether choosing a language needs the caller to recreate the activity
     *  (11-12); on 13+ the framework does it. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** Android 11-12 only: [base] with the stored language as its
     *  configuration locale, and the JVM default locale moved with it so
     *  `Locale.getDefault()` formatting follows. Identity otherwise. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    internal fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }
}
