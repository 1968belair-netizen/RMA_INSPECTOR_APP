package com.bitzer.rmainspector.data

import android.content.Context
import com.bitzer.rmainspector.BuildConfig

/** One settings store and one HTTP client for the whole app. */
object ServiceLocator {
    @Volatile private var store: SettingsStore? = null
    val api: ApiClient by lazy { ApiClient() }

    fun settings(context: Context): SettingsStore =
        store ?: synchronized(this) {
            store ?: SettingsStore(context.applicationContext, BuildConfig.DEFAULT_SERVER_URL).also { store = it }
        }
}
