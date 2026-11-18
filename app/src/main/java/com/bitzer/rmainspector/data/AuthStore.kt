package com.bitzer.rmainspector.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in session: the server's opaque token and the user it belongs to (api §3). */
@Serializable
data class Session(val token: String, val user: User)

/** Encrypts small secrets. The real one is backed by the Android Keystore; tests can use a fake. */
interface SecretBox {
    fun encrypt(plain: ByteArray): String
    fun decrypt(sealed: String): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (no extra library needed). If the key is
 * gone (e.g. app data restored to another device) decryption fails and the user simply signs in again.
 */
class KeystoreSecretBox(private val alias: String = "rma_inspector_session_key") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain)
        return b64(cipher.iv) + ":" + b64(sealed)
    }

    override fun decrypt(sealed: String): ByteArray {
        val (iv, data) = sealed.split(':', limit = 2).let { it[0] to it[1] }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * Keeps the session between launches (spec §2: signed in until Sign out). The token is only ever stored
 * encrypted; the last username (not secret) and the random per-install device id are stored in the clear.
 * The session is read once at start-up so the first screen is known straight away.
 */
class AuthStore(private val prefs: SharedPreferences, private val box: SecretBox) {
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val token: String? get() = _session.value?.token

    /** Random per install, not a hardware id (api §1 `X-Device-Id`). */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    }

    val lastUsername: String get() = prefs.getString(KEY_LAST_USER, null).orEmpty()

    fun save(session: Session) {
        val plain = ApiClient.writeJson.encodeToString(Session.serializer(), session).toByteArray()
        prefs.edit()
            .putString(KEY_SESSION, box.encrypt(plain))
            .putString(KEY_LAST_USER, session.user.username)
            .apply()
        _session.value = session
    }

    /** Sign out: forget the token. Nothing else (queued work, settings) is touched. */
    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    /** The server rejected [token] (401). Only forget it if it is still the current one. */
    fun expire(token: String) {
        if (_session.value?.token == token) clear()
    }

    private fun load(): Session? {
        val sealed = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            ApiClient.defaultJson.decodeFromString(Session.serializer(), String(box.decrypt(sealed)))
        }.getOrElse {
            prefs.edit().remove(KEY_SESSION).apply()
            null
        }
    }

    companion object {
        private const val KEY_SESSION = "session_sealed"
        private const val KEY_LAST_USER = "last_username"
        private const val KEY_DEVICE = "device_id"

        fun create(context: Context): AuthStore =
            AuthStore(context.getSharedPreferences("auth", Context.MODE_PRIVATE), KeystoreSecretBox())
    }
}
