package com.bitzer.rmainspector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.ConflictTags
import com.bitzer.rmainspector.ui.workspace.FindingsTags
import com.bitzer.rmainspector.ui.workspace.SubmitTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Issue #65 (B4b), emulator against the mock server: open RMA 912358 (Report Complete, used by no other test) → the
 * desk edits the summary and the credit status → add a part → Submit → the conflict screen lists the changes (the
 * credit status as information) and nothing is saved → "Reload and keep my changes" → Review → submit → saved.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConflictFlowTest {
    @get:Rule(order = 0) val clean = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.reportDrafts(context).clear("912358")
            ServiceLocator.uploadQueue(context).clearAll()
            mockPost("/_mock/reset", "")
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deskEditThenSubmitShowsConflictAndKeepMineSaves() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

        compose.onNode(hasSetTextAction()).performTextReplacement("912358")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.ADD_PART) and isEnabled(), timeoutMillis = 15_000)

        // Someone edits the summary and the credit status at the desk after the tablet loaded the RMA.
        mockPost("/_mock/rmas/912358/desk-edit",
            """{"header":{"credit_status":"P - Partial Warranty Credit"},"summary":{"blocks":{"1":"Typed at the desk."}}}""")

        compose.onNodeWithTag(FindingsTags.ADD_PART).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        pickCode("Part", "Bearing cap")
        pickCode("Issue", "Normal")
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DRAFT), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)

        submitFromReview()

        // 409 → the conflict screen: the credit status change is shown, as information only; nothing was saved.
        compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.SCREEN), timeoutMillis = 30_000)
        compose.waitUntilAtLeastOneExists(hasText("Credit status", substring = true), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.TEAM_NOTE), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.KEEP) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(ConflictTags.KEEP).performSemanticsAction(SemanticsActions.OnClick)

        // Back on the Review screen with my row re-applied on top of the current RMA; submit it.
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.REVIEW), timeoutMillis = 30_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.CONFIRM) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.WARNING_CONFIRM), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.WARNING_CONFIRM).performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.DONE), timeoutMillis = 30_000)
        compose.onNodeWithTag(SubmitTags.STATUS).assertTextEquals("Saved. Status stays Report Complete.")
        compose.onNodeWithTag(SubmitTags.DONE_CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("Bearing cap", substring = true), timeoutMillis = 15_000)
    }

    /** Submit → Review → UPDATE FINDINGS → the "Report already complete" dialog → Update inspection findings. */
    private fun submitFromReview() {
        compose.onNodeWithTag(SubmitTags.OPEN).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.REVIEW), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.CONFIRM) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.WARNING_CONFIRM), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.WARNING_CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun pickCode(label: String, description: String) {
        compose.onNode(hasContentDescription("$label:", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText(description) and hasClickAction(), timeoutMillis = 10_000)
        compose.onAllNodes(hasText(description) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasContentDescription("$label: ", substring = true) and
            hasContentDescription(description, substring = true), timeoutMillis = 10_000)
    }

    /** POSTs [json] to a mock-server test hook (same address the app uses). */
    private fun mockPost(path: String, json: String) {
        val t = Thread {
            val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            c.responseCode
            c.disconnect()
        }
        t.start(); t.join()
    }
}
