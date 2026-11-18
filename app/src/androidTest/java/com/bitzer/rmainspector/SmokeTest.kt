package com.bitzer.rmainspector

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import java.net.HttpURLConnection
import java.net.URL
import com.bitzer.rmainspector.ui.home.HomeTags
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.WorkspaceTab
import com.bitzer.rmainspector.ui.workspace.WorkspaceTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator UI test, run in GitHub Actions against the mock server (reached at 10.0.2.2:8000 — the app's
 * debug default). Sign in as the mock's FAKE demo user (demo/demo) → search 912345 → workspace → each tab →
 * back → work list 912348 (scroll, read only) and 912346 (locked) → back → Settings → Sign out.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
// Sign in is pressed with the semantics click action, not a screen tap: after the wrong-password message appears
// the layout (and keyboard) shifts, and a tap could land on the server "Retry" button instead (seen on CI).
class SmokeTest {
    /** Each test starts signed out, whatever order JUnit runs them in (runs before the activity starts). */
    @get:Rule(order = 0) val signedOut = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() } // server-default chips
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
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("Wrong username or password."), timeoutMillis = 30_000)

        // Demo user signs in.
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)
        compose.onNodeWithText("Inspector: Demo Inspector (DI)", substring = true).assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("912345")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.onNodeWithText("Example Refrigeration Co.").assertExists()
        compose.onNodeWithText("No guided list for Recip yet").assertExists()

        // Workspace (A5): failure info expands; each tab shows its panes.
        compose.onNodeWithTag(WorkspaceTags.FAILURE_TOGGLE).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Example: low oil pressure trips reported"), timeoutMillis = 5_000)
        for (tab in WorkspaceTab.entries) {
            compose.onNodeWithTag(WorkspaceTags.tab(tab)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(WorkspaceTags.listPane(tab)), timeoutMillis = 5_000)
        }
        // App-level back (Espresso's pressBack waits for window focus, which the keyboard can hold on CI emulators).
        back()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 10_000)

        // Open from the work list: scroll RMA 912348 is read only.
        openFromHomeList("912348")
        compose.waitUntilAtLeastOneExists(hasText("Handled in the repair database", substring = true), timeoutMillis = 15_000)
        back()
        // Home keeps its scroll position, so the card is on screen again (the headline may be scrolled away).
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.workItem("912348")), timeoutMillis = 10_000)

        // 912346 is open elsewhere → lock banner.
        openFromHomeList("912346")
        compose.waitUntilAtLeastOneExists(hasText("Open elsewhere by JDOE2", substring = true), timeoutMillis = 15_000)
        back()
        // Home keeps its scroll position, so the card is on screen again (the headline may be scrolled away).
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.workItem("912346")), timeoutMillis = 10_000)

        // Settings → Sign out returns to sign-in.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
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
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)

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
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.onNodeWithText("Example Refrigeration Co.").assertExists()
    }

    /**
     * Issue #17: the default work list (Received; Recip/Scroll/Screw/Part; Inspection Only/Repair & Return/Warranty)
     * is oldest first; unticking a chip refilters and is remembered when Home is opened again after sign-out/in.
     */
    @Test
    fun workListDefaultOrderAndRememberedChips() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        signInAsDemo()
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.COUNT) and hasText("6 RMAs"), timeoutMillis = 30_000)
        assertWorkListOrder(listOf("912346", "912348", "912345", "912352", "912355", "912357"))

        // Untick Scroll → 912348 drops out.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasText("Scroll") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.COUNT) and hasText("5 RMAs"), timeoutMillis = 15_000)

        // Sign out and in again: a new Home reads the remembered chips from the device.
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sign out", substring = true), timeoutMillis = 10_000)
        compose.onNode(hasText("Sign out", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 10_000)
        signInAsDemo()
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.COUNT) and hasText("5 RMAs"), timeoutMillis = 30_000)
        assertWorkListOrder(listOf("912346", "912345", "912352", "912355", "912357"))
    }

    /** Each card appears below the one before it (scrolls the lazy list as needed). */
    private fun assertWorkListOrder(ids: List<String>) {
        val list = compose.onNode(hasScrollToNodeAction())
        ids.zipWithNext().forEach { (a, b) ->
            list.performScrollToNode(hasTestTag(HomeTags.workItem(a)))
            list.performScrollToNode(hasTestTag(HomeTags.workItem(b)))
            compose.waitForIdle()
            val top = { id: String -> compose.onAllNodesWithTag(HomeTags.workItem(id)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top }
            val ta = top(a)
            val tb = top(b)
            assertTrue("RMA $a should be listed before $b (tops: $ta, $tb)", ta != null && tb != null && ta < tb)
        }
    }

    /** Scrolls Home's lazy list to the work-list card and opens it. */
    private fun openFromHomeList(rmaId: String) {
        val card = hasTestTag(HomeTags.workItem(rmaId))
        compose.waitUntil(timeoutMillis = 20_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(card) }.isSuccess
        }
        compose.onNode(card).performClick()
    }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun signInAsDemo() {
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
    }
}
