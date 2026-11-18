package com.bitzer.rmainspector.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/** Session persistence (spec §2: signed in until Sign out). The Keystore cipher is replaced by a fake here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthStoreTest {
    /** Reversible fake "encryption" — proves the store never writes the token in the clear. */
    private object FakeBox : SecretBox {
        override fun encrypt(plain: ByteArray) = Base64.getEncoder().encodeToString(plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        override fun decrypt(sealed: String) = Base64.getDecoder().decode(sealed).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("auth_test", Context.MODE_PRIVATE)
    private val demo = Session("tok-1", User("demo", "Demo Inspector", "DI", "DEMO_SQL", listOf("all")))

    @Before fun wipe() { prefs.edit().clear().commit() }

    @Test
    fun sessionSurvivesRestartAndIsNotStoredInClear() {
        AuthStore(prefs, FakeBox).save(demo)
        val restarted = AuthStore(prefs, FakeBox)
        assertEquals(demo, restarted.session.value)
        assertEquals("tok-1", restarted.token)
        assertEquals("demo", restarted.lastUsername)
        assertFalse(prefs.all.values.any { it.toString().contains("tok-1") })
    }

    @Test
    fun signOutForgetsTokenButKeepsUsernameAndDeviceId() {
        val store = AuthStore(prefs, FakeBox)
        val device = store.deviceId
        store.save(demo)
        store.clear()
        assertNull(store.session.value)
        val restarted = AuthStore(prefs, FakeBox)
        assertNull(restarted.session.value)
        assertEquals("demo", restarted.lastUsername)
        assertEquals(device, restarted.deviceId)
    }

    @Test
    fun only401ForCurrentTokenSignsOut() {
        val store = AuthStore(prefs, FakeBox)
        store.save(demo)
        store.expire("some-older-token")
        assertEquals(demo, store.session.value)
        store.expire("tok-1")
        assertNull(store.session.value)
    }

    @Test
    fun unreadableStoredSessionMeansSignedOut() {
        prefs.edit().putString("session_sealed", "not-decryptable").commit()
        assertNull(AuthStore(prefs, FakeBox).session.value)
    }

    @Test
    fun expiredIsSetBy401AndClearedBySignInOrSignOut() {
        val store = AuthStore(prefs, FakeBox)
        store.save(demo)
        assertFalse(store.expired.value)
        store.expire("tok-1")
        assertTrue(store.expired.value)
        store.save(demo.copy(token = "tok-2"))
        assertFalse(store.expired.value)
        store.expire("tok-2")
        store.clear()
        assertFalse(store.expired.value)
    }

    @Test
    fun passwordIsNeverStored() {
        val store = AuthStore(prefs, FakeBox)
        store.save(demo)
        // Only the sealed session, last username and device id are written; the session holds token + user only.
        assertEquals(setOf("session_sealed", "last_username"), prefs.all.keys - "device_id")
        val sealed = prefs.getString("session_sealed", null)!!
        val plain = String(FakeBox.decrypt(sealed))
        assertFalse(plain.contains("password", ignoreCase = true))
    }
}
