package com.bitzer.rmainspector.data

import android.content.Context
import com.bitzer.rmainspector.BuildConfig

/** One settings store, one session store and one HTTP client for the whole app. */
object ServiceLocator {
    @Volatile private var store: SettingsStore? = null
    @Volatile private var authStore: AuthStore? = null

    /** Sends the signed-in token on every call; a 401 for that token returns the app to sign-in. */
    val api: ApiClient by lazy {
        ApiClient(
            tokenProvider = { authStore?.token },
            onUnauthorized = { rejected -> authStore?.expire(rejected) },
            appVersion = BuildConfig.VERSION_NAME,
            deviceIdProvider = { authStore?.deviceId },
        )
    }

    fun settings(context: Context): SettingsStore =
        store ?: synchronized(this) {
            store ?: SettingsStore(context.applicationContext, BuildConfig.DEFAULT_SERVER_URL).also { store = it }
        }

    fun auth(context: Context): AuthStore =
        authStore ?: synchronized(this) {
            authStore ?: AuthStore.create(context.applicationContext).also { authStore = it }
        }
}
