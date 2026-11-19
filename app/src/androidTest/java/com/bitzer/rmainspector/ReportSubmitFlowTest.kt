package com.bitzer.rmainspector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ReportSubmitRules
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.ReportSubmitTags
import com.bitzer.rmainspector.ui.workspace.ReportTags
import com.bitzer.rmainspector.ui.workspace.WorkspaceTab
import com.bitzer.rmainspector.ui.workspace.WorkspaceTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Issue #11 (C2), emulator against the mock server: sign in → RMA 912347 (Inspected) → Report tab → a misspelled
 * word in the summary → Submit report → spell check (fix) → build → PDF preview shows "Page 1 of N" → Done.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReportSubmitFlowTest {
    @get:Rule(order = 0) val clean = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.reportDrafts(context).clear("912347")
            ServiceLocator.reportDrafts(context).clear("912359")
            mockReset()
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun submitReportSpellCheckBuildAndPreview() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

        compose.onNode(hasSetTextAction()).performTextInput("912347")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)

        compose.onNodeWithTag(WorkspaceTags.tab(WorkspaceTab.REPORT)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportTags.SUMMARY), timeoutMillis = 15_000)
        compose.onNodeWithTag(ReportTags.SUMMARY).performTextReplacement("Example summary text with scorring.")

        compose.onNodeWithTag(ReportSubmitTags.SUBMIT).performScrollTo()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(ReportSubmitTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)

        // Spell check lists "scorring" → Fix: scoring → Continue.
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.SPELL), timeoutMillis = 30_000)
        compose.onNodeWithTag(ReportSubmitTags.fix(0, 0)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.SPELL_CONTINUE) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(ReportSubmitTags.SPELL_CONTINUE).performSemanticsAction(SemanticsActions.OnClick)

        // Build → preview of the mock's PDF.
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.PREVIEW), timeoutMillis = 90_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.PAGE_COUNTER) and hasText("Page 1 of", substring = true), timeoutMillis = 15_000)

        compose.onNodeWithTag(ReportSubmitTags.DONE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(ReportSubmitTags.PREVIEW), timeoutMillis = 10_000)
        // First build set Report Complete; the workspace reloaded and the header shows it.
        compose.waitUntilAtLeastOneExists(hasText("Report Complete", substring = true, ignoreCase = true), timeoutMillis = 15_000)
    }

    /** Second submit on the same RMA: "Report already complete" first; after Update report the status stays. */
    @Test
    fun secondSubmitAsksFirstAndKeepsReportComplete() {
        signInAndOpenReportTab("912347")
        clickSubmit()
        waitForPreviewAndDone()
        compose.waitUntilAtLeastOneExists(hasText("Report Complete", substring = true, ignoreCase = true), timeoutMillis = 15_000)

        clickSubmit()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.CONFIRM) and hasText(ReportSubmitRules.COMPLETE_NOTE), timeoutMillis = 15_000)
        compose.onNode(hasText("Update report") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitForPreviewAndDone()
        compose.waitUntilAtLeastOneExists(hasText("Report Complete", substring = true, ignoreCase = true), timeoutMillis = 15_000)
        compose.onAllNodes(hasText("Sent to RSM", substring = true)).assertCountEquals(0)
    }

    /** 912359 is Sent to RSM: the exact warning shows before anything is sent; Cancel sends nothing. */
    @Test
    fun alreadySentWarningShowsBeforeAnythingIsSent() {
        signInAndOpenReportTab("912359")
        clickSubmit()
        compose.waitUntilAtLeastOneExists(
            hasTestTag(ReportSubmitTags.CONFIRM) and
                hasText("This report was already sent. Updating will regenerate it; you may need to resend it."), timeoutMillis = 15_000)
        compose.onNodeWithTag(ReportSubmitTags.CANCEL).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(ReportSubmitTags.OVERLAY), timeoutMillis = 10_000)
        compose.onAllNodes(hasTestTag(ReportSubmitTags.PREVIEW)).assertCountEquals(0)
    }

    private fun signInAndOpenReportTab(rma: String) {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)
        compose.onNode(hasSetTextAction()).performTextInput(rma)
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.onNodeWithTag(WorkspaceTags.tab(WorkspaceTab.REPORT)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.SUBMIT), timeoutMillis = 15_000)
    }

    private fun clickSubmit() {
        compose.onNodeWithTag(ReportSubmitTags.SUBMIT).performScrollTo()
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(ReportSubmitTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForPreviewAndDone() {
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportSubmitTags.PREVIEW), timeoutMillis = 90_000)
        compose.onNodeWithTag(ReportSubmitTags.DONE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(ReportSubmitTags.PREVIEW), timeoutMillis = 10_000)
    }

    /** Puts the mock server's data back to its start state (each test starts from the same RMAs). */
    private fun mockReset() {
        val t = Thread {
            val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + "/_mock/reset").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
        t.start(); t.join()
    }
}
