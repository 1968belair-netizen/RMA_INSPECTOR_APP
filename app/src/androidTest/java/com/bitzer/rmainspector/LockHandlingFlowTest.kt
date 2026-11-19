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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Issue #66 (B4c), emulator, mock server default dataset: RMA 912356 (Pressure Vessel, used by no other test) is
 * locked in the mock after it is opened → add a part → Submit → "Open in Macola by JDOE2 (EXAMPLE-PC) — will retry",
 * the item is queued and the draft is kept → unlock → the queue sends it (once) and the draft clears.
 * The mock lock on 912356 is always cleared afterwards (RMA 912346 is locked by default in the mock; this test
 * never touches it).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LockHandlingFlowTest {
    private val rma = "912356"

    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.uploadQueue(context).clearAll()
            mockLock(false)
        }

        override fun after() {
            mockLock(false) // never leave the lock on for other tests
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.uploadQueue(context).clearAll()
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun lockedSubmitIsKeptAndSentOnceAfterUnlock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

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

        // Someone opens the RMA in Macola after it was loaded here.
        mockLock(true)
        compose.onNodeWithTag(SubmitTags.OPEN).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.REVIEW), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.CONFIRM) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(SubmitTags.CONFIRM).performSemanticsAction(SemanticsActions.OnClick)

        // Locked: the lock message, nothing saved, the work kept and queued.
        compose.waitUntilAtLeastOneExists(hasTestTag(SubmitTags.QUEUED), timeoutMillis = 30_000)
        compose.waitUntilAtLeastOneExists(hasText("Open in Macola by JDOE2 (EXAMPLE-PC) — will retry"), timeoutMillis = 10_000)
        val queue = ServiceLocator.uploadQueue(context)
        val item = queue.snapshot().single { it.rmaId == rma }
        assertEquals("RMA_LOCKED", item.reasonCode)
        assertEquals(false, item.inspectionSaved)
        assertNotNull(ServiceLocator.drafts(context).read(item.inspector.username, rma))

        // Unlocked: the queue sends it (same submission id) and the draft clears.
        mockLock(false)
        queue.modify(item.id) { it.copy(nextTryAt = 0) }
        val deadline = System.currentTimeMillis() + 60_000
        while (queue.snapshot().any { it.rmaId == rma } && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertNull(queue.snapshot().firstOrNull { it.rmaId == rma })
        val drafted = ServiceLocator.drafts(context).read(item.inspector.username, rma)
        assertNull(drafted)
    }

    private fun mockLock(locked: Boolean) {
        val t = Thread {
            val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + "/_mock/rmas/$rma/lock").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = if (locked) """{"locked": true, "held_by": "JDOE2", "host": "EXAMPLE-PC", "possibly_stale": false}""" else """{"locked": false}"""
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
            c.disconnect()
        }
        t.start(); t.join()
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
