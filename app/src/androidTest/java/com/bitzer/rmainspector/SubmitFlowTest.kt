package com.bitzer.rmainspector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.FindingsTags
import com.bitzer.rmainspector.ui.workspace.SubmitTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Issue #8 (B3), emulator, against the mock server's default dataset: sign in → open RMA 912354 (Received, not on the
 * work list, used by no other test, so the other tests' work-list counts stay as they are) → add a part → Submit →
 * review → SUBMIT → "Saved. Status is now Inspected." → the header shows Inspected.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubmitFlowTest {
    /** Each test starts signed out and without drafts (runs before the activity starts). */
    @get:Rule(order = 0) val signedOut = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun addPartSubmitAndTheStatusBecomesInspected() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

        compose.onNode(hasSetTextAction()).performTextReplacement("912354")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.ADD_PART) and isEnabled(), timeoutMillis = 15_000)

        // Add one part: 04 Bearing cap, issue 50 Normal (pinned first, so it is on screen without scrolling).
        compose.onNodeWithTag(FindingsTags.ADD_PART).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        pickCode("Part", "Bearing cap")
        pickCode("Issue", "Normal")
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DRAFT), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)

        // Submit → review screen shows the status that will be set.
        compose.onNodeWithTag(SubmitTags.OPEN).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.REVIEW), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.STATUS).assertTextEquals("Status changes from Received to Inspected.")
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.CONFIRM) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.CONFIRM).performSemanticsAction(SemanticsActions.OnClick)

        // Saved: the done screen names the new status; closing it shows Inspected in the header.
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.DONE), timeoutMillis = 30_000)
        compose.onNodeWithTag(SubmitTags.STATUS).assertTextEquals("Saved. Status is now Inspected.")
        compose.onNodeWithTag(SubmitTags.DONE_CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(SubmitTags.DONE), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Inspected"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasText("Bearing cap", substring = true), timeoutMillis = 15_000)
    }

    /** Opens the dropdown labelled [label] in the part sheet and picks the code with [description]. */
    private fun pickCode(label: String, description: String) {
        compose.onNode(hasContentDescription("$label:", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText(description) and hasClickAction(), timeoutMillis = 10_000)
        // A "recently used" chip and the list row can both match: take the first.
        compose.onAllNodes(hasText(description) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasContentDescription("$label: ", substring = true) and
            hasContentDescription(description, substring = true), timeoutMillis = 10_000)
    }
}
