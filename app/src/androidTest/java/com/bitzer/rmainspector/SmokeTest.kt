package com.bitzer.rmainspector

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import java.net.HttpURLConnection
import java.net.URL
import com.bitzer.rmainspector.ui.signin.SignInTags
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator UI test, run in GitHub Actions against the mock server (reached at 10.0.2.2:8000 — the app's
 * debug default). Sign in as the mock's FAKE demo user (demo/demo) → search 912345 → RMA → back →
 * Settings → Sign out.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    /** Each test starts signed out, whatever order JUnit runs them in (runs before the activity starts). */
    @get:Rule(order = 0) val signedOut = object : ExternalResource() {
        override fun before() {
            ServiceLocator.auth(InstrumentationRegistry.getInstrumentation().targetContext).clear()
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun signInSearchRmaAndSignOut() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)

        // Wrong password → exact message.
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("wrong")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Wrong username or password."), timeoutMillis = 30_000)

        // Demo user signs in.
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)
        compose.onNodeWithText("Inspector: Demo Inspector (DI)", substring = true).assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("912345")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE CONTEXT"), timeoutMillis = 15_000)
        compose.onNodeWithText("Example Refrigeration Co.").assertExists()
        // App-level back (Espresso's pressBack waits for window focus, which the keyboard can hold on CI emulators).
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 10_000)

        // Settings → Sign out returns to sign-in.
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sign out", substring = true), timeoutMillis = 10_000)
        compose.onNode(hasText("Sign out", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 10_000)
    }

    /**
     * Decision 2026-11-18: if the server rejects the token mid-session, signing in again returns the user to the
     * screen and RMA they were on. The token is revoked here by calling the mock's /auth/logout directly.
     */
    @Test
    fun reSignInReturnsToTheSameRma() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        signInAsDemo()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

        compose.onNode(hasSetTextAction()).performTextInput("912345")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE CONTEXT"), timeoutMillis = 15_000)

        // Revoke the token on the server, then make the screen call the server again → 401 → sign-in.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = checkNotNull(ServiceLocator.auth(context).token)
        val revoke = Thread {
            val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + "/auth/logout").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", "Bearer $token")
            c.doOutput = true
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
        revoke.start(); revoke.join()
        compose.onNode(hasContentDescription("Refresh") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)

        signInAsDemo()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE CONTEXT"), timeoutMillis = 15_000)
        compose.onNodeWithText("Example Refrigeration Co.").assertExists()
    }

    private fun signInAsDemo() {
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performClick()
    }
}
