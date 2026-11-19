package com.bitzer.rmainspector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.home.HomeTags
import com.bitzer.rmainspector.ui.settings.QueueTags
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.FindingsTags
import com.bitzer.rmainspector.ui.workspace.SubmitTags
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Issue #67 (B4d), emulator against the mock server: held uploads (spec §2, decision 2026-11-18).
 * User A (demo) submits RMA 912356 while the mock's lock check fails (the tablet queues it) → Sign out → the prompt
 * "You have 1 upload waiting" → Sign out anyway. The mock then works again, but user B (demo2) signs in: the upload
 * stays held — not sent, still A's. B's Settings show it read-only. B signs out without a prompt (B has nothing waiting).
 * A signs in again: the Home banner says so; Settings → Retry now sends it and the server shows Inspected.
 * 912356 (Received, Pressure Vessel, not on the work list) is used by no other emulator test.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HeldUploadsFlowTest {
    private val rma = "912356"

    @get:Rule(order = 0) val clean = object : ExternalResource() {
        override fun before() {
            reset()
            mock("POST", "/_mock/rmas/$rma/lock-check", """{"fails":true}""")
        }

        override fun after() = reset()

        private fun reset() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.uploadQueue(context).clearAll()
            mock("POST", "/_mock/reset", null)
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val queue get() = ServiceLocator.uploadQueue(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun heldUploadWaitsForItsOwnerAndSendsAfterTheyReturn() {
        // --- A queues an upload (the lock check fails → "Queued").
        signIn("demo", "demo")
        compose.onNode(hasSetTextAction()).performTextReplacement(rma)
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.ADD_PART) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(FindingsTags.ADD_PART).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        pickCode("Part", "Bearing cap")
        pickCode("Issue", "Normal")
        compose.waitUntilAtLeastOneExists(hasTestTag(FindingsTags.DRAFT), timeoutMillis = 10_000)
        compose.onNodeWithTag(FindingsTags.CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(FindingsTags.SHEET), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.OPEN).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.REVIEW), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.CONFIRM) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.QUEUED), timeoutMillis = 30_000)
        compose.onNodeWithTag(SubmitTags.QUEUED_CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        val id = queue.snapshot().single().id
        assertEquals("demo", queue.snapshot().single().inspector.username)

        // --- A signs out (Home → Settings): the prompt asks; Sign out anyway keeps the upload.
        back()
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 15_000)
        openSettings()
        compose.onNode(hasText("Sign out", substring = true) and hasClickAction()).performScrollTo().performClick()
        try {
            compose.waitUntilAtLeastOneExists(hasTestTag(QueueTags.SIGN_OUT_PROMPT), timeoutMillis = 10_000)
        } catch (e: Throwable) {
            android.util.Log.e("rmainspector-held", compose.onRoot().printToString(maxDepth = 14).replace("\n", " | ").replace(Regex("\\s+"), " ").take(3500))
            throw AssertionError("No sign-out prompt. Queue: ${queue.snapshot().map { it.state.name + "/" + it.inspector.username }}\n" +
                compose.onRoot().printToString(maxDepth = 12).replace("\n", " | ").replace(Regex("\\s+"), " ").take(2500), e)
        }
        compose.waitUntilAtLeastOneExists(hasText("You have 1 upload waiting."), timeoutMillis = 10_000)
        compose.onNodeWithTag(QueueTags.SIGN_OUT_ANYWAY).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        assertEquals(1, queue.snapshot().size)

        // --- The server works again; B signs in. The upload must NOT be sent (it is A's) and must stay A's.
        mock("POST", "/_mock/rmas/$rma/lock-check", """{"fails":false}""")
        signIn("demo2", "demo2")
        compose.onAllNodes(hasTestTag(HomeTags.EARLIER_UPLOADS)).assertCountEquals(0) // B has nothing held
        Thread.sleep(12_000) // longer than one runner tick; the queue also runs right after sign-in
        assertEquals(1, queue.snapshot().size)
        assertEquals("demo", queue.snapshot().single().inspector.username)
        assertEquals("Received", serverStatus())

        // --- B sees it read-only in Settings (count + RMA, no buttons) and signs out without a prompt.
        openSettings()
        compose.onNodeWithTag(QueueTags.SECTION).performScrollTo()
        compose.waitUntilAtLeastOneExists(hasTestTag(QueueTags.group("demo")), timeoutMillis = 10_000)
        compose.onNodeWithTag(QueueTags.item(id)).assertExists()
        compose.onAllNodes(hasTestTag(QueueTags.retry(id))).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(QueueTags.cancel(id))).assertCountEquals(0)
        compose.onNode(hasText("Sign out", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        assertEquals(1, queue.snapshot().size)

        // --- A signs in again (the server still can't check the lock): the banner says it is waiting.
        mock("POST", "/_mock/rmas/$rma/lock-check", """{"fails":true}""")
        signIn("demo", "demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(HomeTags.EARLIER_UPLOADS), timeoutMillis = 15_000)
        compose.onNodeWithTag(HomeTags.EARLIER_UPLOADS).assertExists()
        assertEquals("Received", serverStatus())

        // --- The server works: Retry now (own item) sends it; the server now shows Inspected.
        mock("POST", "/_mock/rmas/$rma/lock-check", """{"fails":false}""")
        openSettings()
        compose.onNodeWithTag(QueueTags.SECTION).performScrollTo()
        // (If the queue's own timer got there first the item is already gone; then there is nothing to retry.)
        val retry = compose.onAllNodes(hasTestTag(QueueTags.retry(id)))
        if (retry.fetchSemanticsNodes().isNotEmpty()) retry.onFirst().performScrollTo().performClick()
        waitFor(60_000) { queue.snapshot().isEmpty() }
        assertEquals("Inspected", serverStatus())
    }

    private fun signIn(user: String, password: String) {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement(user)
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement(password)
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)
    }

    /** App-level back (Espresso's pressBack waits for window focus, which the keyboard can hold on CI emulators). */
    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun openSettings() {
        compose.onAllNodes(hasContentDescription("Settings") and hasClickAction()).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sign out", substring = true), timeoutMillis = 10_000)
    }

    private fun pickCode(label: String, description: String) {
        compose.onNode(hasContentDescription("$label:", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText(description) and hasClickAction(), timeoutMillis = 10_000)
        compose.onAllNodes(hasText(description) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasContentDescription("$label: ", substring = true) and
            hasContentDescription(description, substring = true), timeoutMillis = 10_000)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out waiting", System.currentTimeMillis() < end)
            Thread.sleep(250)
        }
    }

    private fun serverStatus(): String = JSONObject(mock("GET", "/_mock/rmas/$rma/state", null)).getString("status")
}

/** Calls a mock-only test hook (`/api/v1/_mock/...`, no sign-in needed) and returns the body. */
private fun mock(method: String, path: String, body: String?): String {
    var out = ""
    val t = Thread {
        val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write((body ?: "{}").toByteArray()) }
        }
        out = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        c.disconnect()
    }
    t.start(); t.join()
    return out
}
