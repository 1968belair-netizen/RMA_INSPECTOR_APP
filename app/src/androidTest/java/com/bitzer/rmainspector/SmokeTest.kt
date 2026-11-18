package com.bitzer.rmainspector

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitzer.rmainspector.ui.signin.SignInTags
import org.junit.Rule
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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun signInSearchRmaAndSignOut() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)

        // Wrong password → exact message.
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("wrong")
        compose.onNodeWithTag(SignInTags.SUBMIT).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Wrong username or password."), timeoutMillis = 15_000)

        // Demo user signs in.
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.SUBMIT).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 15_000)
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
}
