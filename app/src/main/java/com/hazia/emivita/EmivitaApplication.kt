package com.hazia.emivita

import android.app.Application
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.notification.ReminderNotificationChannels

class EmivitaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // net.zetetic:sqlcipher-android doesn't auto-load its native library the way
        // the old net.sqlcipher artifact's SQLiteDatabase.loadLibs() used to -- every
        // process that might open the (SQLCipher-encrypted) database, including a
        // BroadcastReceiver process reached without MainActivity ever running, needs
        // this called once before any net.zetetic.database.sqlcipher class touches
        // JNI, hence here rather than lazily in EmivitaDatabase.
        System.loadLibrary("sqlcipher")
        container = AppContainer(this)
        ReminderNotificationChannels.createAll(this)
    }
}
