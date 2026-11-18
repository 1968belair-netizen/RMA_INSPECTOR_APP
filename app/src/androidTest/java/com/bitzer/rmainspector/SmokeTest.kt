package com.bitzer.rmainspector

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator UI test, run in GitHub Actions against the mock server (reached at 10.0.2.2:8000 — the app's
 * debug default). Home → search 912345 → RMA → back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeToRmaAndBack() {
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 15_000)
        compose.onNode(hasSetTextAction()).performTextInput("912345")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE CONTEXT"), timeoutMillis = 15_000)
        compose.onNodeWithText("Example Refrigeration Co.").assertExists()
        Espresso.pressBack()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 10_000)
    }
}
