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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.signin.SignInTags
import com.bitzer.rmainspector.ui.workspace.ReportPhotoViewerTags
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
 * Issue #83, emulator against the mock server: RMA 912347 has 3 fake photos (1 in the report). Report tab -> swipe to
 * the next photo -> tap a thumbnail -> toggle Report photos -> expand and collapse the header Details.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReportLayoutFlowTest {
    @get:Rule(order = 0) val clean = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.auth(context).clear()
            runBlocking { ServiceLocator.settings(context).clearWorkListFilter() }
            ServiceLocator.drafts(context).clearAll()
            ServiceLocator.reportDrafts(context).clear("912347")
            context.getSharedPreferences("report_photo_viewer", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            mockReset()
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun viewerAndDetailsOnReportTab() {
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

        // Make sure the soft keyboard from the search field is gone (an open keyboard collapses the viewer by design).
        compose.runOnUiThread {
            val w = compose.activity.window
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        // Viewer with all 3 photos.
        try {
            compose.waitUntilAtLeastOneExists(hasText("1 / 3"), timeoutMillis = 30_000)
        } catch (e: Throwable) {
            throw AssertionError("viewer counter missing; tree: " + compose.onRoot().printToString(maxDepth = 12).replace("\n", " | ").take(6000), e)
        }
        compose.waitUntilAtLeastOneExists(hasTestTag(ReportTags.SUMMARY), timeoutMillis = 15_000)

        // Swipe to the next photo.
        compose.onNode(hasTestTag(ReportPhotoViewerTags.PAGER)).performTouchInput { swipeLeft() }
        compose.waitUntilAtLeastOneExists(hasText("2 / 3"), timeoutMillis = 15_000)

        // Tap the third thumbnail.
        compose.onNode(hasContentDescription("Photo 3") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("3 / 3"), timeoutMillis = 15_000)

        // Toggle to Report photos (1 photo is in the report).
        compose.onNode(hasText("Report photos (", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("1 / 1"), timeoutMillis = 15_000)

        // Back to all photos.
        compose.onNode(hasText("All photos (", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("/ 3", substring = true), timeoutMillis = 15_000)

        // Details: expand shows the full header (Credit status is only in the panel), collapse hides it.
        compose.onNode(hasText("DETAILS") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("Credit status", ignoreCase = true), timeoutMillis = 15_000)
        compose.onNode(hasText("DETAILS") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilDoesNotExist(hasText("Credit status", ignoreCase = true), timeoutMillis = 15_000)
    }

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
