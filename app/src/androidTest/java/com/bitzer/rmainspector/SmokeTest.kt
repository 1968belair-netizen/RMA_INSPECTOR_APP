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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.bitzer.rmainspector.ui.workspace.FindingsTags
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
            ServiceLocator.drafts(context).clearAll() // no drafts left over from another test
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

    /**
     * Issue #5 (B1): add a part, edit it and a desk row, reorder, delete with undo — and the local draft is still
     * there when the RMA is opened again (a new screen reads it back from the device).
     */
    @Test
    fun findingsAddEditReorderDeleteAndDraftKept() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        signInAsDemo()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)
        openBySearch("912345")
        compose.waitUntilAtLeastOneExists(findingRow("Winding resistance"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.ADD_PART) and isEnabled(), timeoutMillis = 10_000)

        // Add: new row → sheet → part 04 Bearing cap, issue 13 Heavy wear, a note.
        compose.onNodeWithTag(FindingsTags.ADD_PART).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        pickCode("Part", "Bearing cap")
        pickCode("Issue", "Heavy wear")
        compose.onNodeWithTag(FindingsTags.NOTE).performTextReplacement("Example note from the emulator test")
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DRAFT), timeoutMillis = 10_000)

        // Reorder: the new row (last) moves up one, above 48a.
        compose.onNodeWithTag(FindingsTags.MOVE_UP).performSemanticsAction(SemanticsActions.OnClick)
        closeSheet()
        assertAbove(findingRow("Bearing cap"), findingRow("Winding resistance"))
        assertAbove(findingRow("Terminal box"), findingRow("Bearing cap"))

        // Edit a desk row (from the server): 07a issue → 10 Dirty.
        compose.onNode(findingRow("Terminal box")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        pickCode("Issue", "Dirty")
        closeSheet()
        compose.waitUntilAtLeastOneExists(findingRow("Terminal box") and hasText("Dirty", substring = true), timeoutMillis = 10_000)

        // Delete with undo.
        compose.onNode(findingRow("Terminal box")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DELETE), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.DELETE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.UNDO), timeoutMillis = 10_000)
        compose.waitUntilDoesNotExist(findingRow("Terminal box"), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.UNDO).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(findingRow("Terminal box"), timeoutMillis = 10_000)
        // …and delete it for good.
        compose.onNode(findingRow("Terminal box")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DELETE), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.DELETE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(findingRow("Terminal box"), timeoutMillis = 10_000)

        // Leave and open the RMA again: the draft comes back from the device, in the same order.
        back()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 10_000)
        openBySearch("912345")
        compose.waitUntilAtLeastOneExists(findingRow("Bearing cap"), timeoutMillis = 15_000)
        compose.onNodeWithTag(FindingsTags.DRAFT).assertExists()
        assertAbove(findingRow("Bearing cap"), findingRow("Winding resistance"))
        assertTrue(compose.onAllNodes(findingRow("Terminal box")).fetchSemanticsNodes().isEmpty())
        compose.onNode(findingRow("Bearing cap") and hasText("Heavy wear", substring = true)).assertExists()
    }

    /** A Findings list row containing [text]. */
    private fun findingRow(text: String) =
        SemanticsMatcher("finding row") { n ->
            n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("finding_row_") == true
        } and hasText(text, substring = true)

    /** Opens the dropdown labelled [label] in the part sheet and picks the code with [description]. */
    private fun pickCode(label: String, description: String) {
        compose.onNode(hasContentDescription("$label:", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText(description) and hasClickAction(), timeoutMillis = 10_000)
        compose.onNode(hasText(description) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasContentDescription("$label: ", substring = true) and
            hasContentDescription(description, substring = true), timeoutMillis = 10_000)
    }

    private fun closeSheet() {
        compose.onNodeWithTag(FindingsTags.CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
    }

    private fun assertAbove(upper: SemanticsMatcher, lower: SemanticsMatcher) {
        compose.waitForIdle()
        val top = { m: SemanticsMatcher -> compose.onAllNodes(m).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top }
        val a = top(upper)
        val b = top(lower)
        assertTrue("Expected $upper above $lower (tops: $a, $b)", a != null && b != null && a < b)
    }

    private fun openBySearch(rma: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(rma)
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
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
