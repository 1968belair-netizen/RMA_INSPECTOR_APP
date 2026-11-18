package com.bitzer.rmainspector

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.guided.GuidedTags
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.CameraTags
import com.bitzer.rmainspector.ui.workspace.CameraTestHook
import com.bitzer.rmainspector.ui.workspace.WorkspaceTab
import com.bitzer.rmainspector.ui.workspace.WorkspaceTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Issue #56 (D2b), emulator against the mock server's DEMO dataset: sign in → RMA 912361 (Recip) → Start guided
 * inspection → step 1 with an Overall photo → Next → Exit → "Continue (step 2 of 9)" → continue and finish all steps →
 * "Inspection complete" → the rows are on Findings. The camera is replaced by [CameraTestHook] (CI has no camera);
 * everything after the shutter (scaling, storage, rules) runs for real.
 *
 * The mock is switched to the demo dataset for this test only and put back afterwards, so the other emulator tests keep
 * the default data (and the default "no guided list" code lists).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GuidedFlowTest {
    @get:Rule(order = 0) val demoData = object : ExternalResource() {
        override fun before() {
            clean()
            mockDataset("demo")
            CameraTestHook.provider = { fakeJpeg() }
        }

        override fun after() {
            CameraTestHook.provider = null
            mockDataset(null)   // back to the server's own dataset (default in CI)
            clean()
        }

        private fun clean() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.guidedProgress(context).clearAll()
            ServiceLocator.photos(context).clearAll()
            ServiceLocator.codeLists(context).clear()   // the demo code lists carry checklists; don't leak them
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun guidedRunOnRecipRmaWritesRowsToFindings() {
        compose.waitUntilAtLeastOneExists(hasText("SIGN IN"), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.USERNAME).performTextReplacement("demo")
        compose.onNodeWithTag(SignInTags.PASSWORD).performTextReplacement("demo")
        compose.waitUntilAtLeastOneExists(hasTestTag(SignInTags.SUBMIT) and isEnabled(), timeoutMillis = 15_000)
        compose.onNodeWithTag(SignInTags.SUBMIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("FIND AN RMA"), timeoutMillis = 30_000)

        compose.onNode(hasSetTextAction()).performTextReplacement("912361")
        compose.onNode(hasText("Search", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAILURE INFO"), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasText("Start guided inspection"), timeoutMillis = 15_000)

        // Step 1: Overall photo is required, so Next stays locked until the (fake) camera delivered one.
        compose.onNodeWithTag(WorkspaceTags.GUIDED).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(GuidedTags.SCREEN), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Step 1 of 9"), timeoutMillis = 10_000)
        compose.onNodeWithTag(GuidedTags.NEXT).assertIsNotEnabled()
        takeSlotPhoto("overall")
        compose.waitUntilAtLeastOneExists(hasTestTag(GuidedTags.NEXT) and isEnabled(), timeoutMillis = 20_000)
        compose.onNodeWithTag(GuidedTags.NEXT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("Step 2 of 9"), timeoutMillis = 10_000)

        // Exit keeps the progress: the header button now offers Continue.
        compose.onNodeWithTag(GuidedTags.EXIT).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(GuidedTags.SCREEN), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Continue (step 2 of 9)"), timeoutMillis = 10_000)
        compose.onNodeWithTag(WorkspaceTags.GUIDED).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("Step 2 of 9"), timeoutMillis = 10_000)

        // Steps 2..9: Overall photo, Next (the last one reads "Finish").
        for (step in 2..9) {
            compose.waitUntilAtLeastOneExists(hasText("Step $step of 9"), timeoutMillis = 10_000)
            takeSlotPhoto("overall")
            compose.waitUntilAtLeastOneExists(hasTestTag(GuidedTags.NEXT) and isEnabled(), timeoutMillis = 20_000)
            compose.onNodeWithTag(GuidedTags.NEXT).performSemanticsAction(SemanticsActions.OnClick)
        }

        // Done: the completion card, then back on the workspace with the rows on Findings.
        compose.waitUntilAtLeastOneExists(hasTestTag(GuidedTags.COMPLETE), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("INSPECTION COMPLETE"), timeoutMillis = 5_000)
        compose.onNodeWithTag(GuidedTags.DONE).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(GuidedTags.SCREEN), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Guided inspection complete"), timeoutMillis = 10_000)
        compose.onNodeWithTag(WorkspaceTags.tab(WorkspaceTab.FINDINGS)).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Terminal plate", substring = true), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasText("Winding resistance", substring = true), timeoutMillis = 15_000)
        compose.waitUntilAtLeastOneExists(hasText("Piston assy / skirt", substring = true), timeoutMillis = 15_000)
    }

    /** Taps "Take photo" for [slot], then the fake camera's shutter (a slot photo closes the camera by itself). */
    private fun takeSlotPhoto(slot: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(GuidedTags.take(slot)) and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithTag(GuidedTags.take(slot)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasTestTag(CameraTags.OVERLAY), timeoutMillis = 10_000)
        compose.onNodeWithTag(CameraTags.SHUTTER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasTestTag(CameraTags.OVERLAY), timeoutMillis = 20_000)
    }

    /** A small real JPEG (the app scales, rotates and stores it like a camera shot). */
    private fun fakeJpeg(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("fake_capture", ".jpg", context.cacheDir)
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 90)) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        return file
    }

    /** Mock test hook: switch the dataset ("demo") or back to the server's own (null) — also resets the data. */
    private fun mockDataset(name: String?) {
        val t = Thread {
            val c = URL(BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + "/_mock/dataset").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write("""{"name": ${if (name == null) "null" else "\"$name\""}}""".toByteArray()) }
            check(c.responseCode == 200) { "mock dataset switch failed: ${c.responseCode}" }
            c.disconnect()
        }
        t.start(); t.join()
    }
}
