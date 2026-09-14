package com.hazia.emivita

import android.app.Application
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import android.content.Context
import com.hazia.emivita.core.locale.AppLocale

// `open` for one reason: the receiver tests (§9 #30) subclass it so that
// `applicationContext as? EmivitaApplication` holds on the JVM, with the two
// members below swapped for a container over in-memory Room and no native
// library. Nothing in production extends it.
open class EmivitaApplication : Application() {
    lateinit var container: AppContainer
        private set

    // The app's own language on Android 11-12 (§9 #28): every context the
    // container hands out -- receivers, notification builders -- descends
    // from this one, so wrapping here is what makes a notification Italian.
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate() {
        super.onCreate()
        // net.zetetic:sqlcipher-android doesn't auto-load its native library the way
        // the old net.sqlcipher artifact's SQLiteDatabase.loadLibs() used to -- every
        // process that might open the (SQLCipher-encrypted) database, including a
        // BroadcastReceiver process reached without MainActivity ever running, needs
        // this called once before any net.zetetic.database.sqlcipher class touches
        // JNI, hence here rather than lazily in EmivitaDatabase.
        loadNativeLibraries()
        container = createContainer()
        ReminderNotificationChannels.createAll(this)
        container.observeAlarmClockSetting()
        container.observeTimeFormatSetting()
    }

    protected open fun loadNativeLibraries() = System.loadLibrary("sqlcipher")
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
