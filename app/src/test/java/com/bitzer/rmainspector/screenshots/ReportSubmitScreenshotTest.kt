package com.bitzer.rmainspector.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.SpellIssue
import com.bitzer.rmainspector.data.SpellItem
import com.bitzer.rmainspector.data.StatusWarning
import com.bitzer.rmainspector.data.SubmitError
import com.bitzer.rmainspector.data.SubmitErrorKind
import com.bitzer.rmainspector.data.SubmitStep
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.PdfPreviewState
import com.bitzer.rmainspector.ui.workspace.ReportSubmitCallbacks
import com.bitzer.rmainspector.ui.workspace.ReportSubmitOverlay
import com.bitzer.rmainspector.ui.workspace.ReportSubmitState
import com.bitzer.rmainspector.ui.workspace.SubmitPhase
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Submit report (C2) screens: status warnings, progress, spell check, error, PDF preview (page bitmaps are generated placeholders). */
abstract class SubmitCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, phase: SubmitPhase) {
        compose.setContent {
            RmaInspectorTheme { Box(Modifier.fillMaxSize()) { ReportSubmitOverlay(ReportSubmitState(phase), ReportSubmitCallbacks()) } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    private val issue = { w: String, s: List<String> -> SpellItem("1", "Report summary", SpellIssue(w, 0, w.length, s)) }

    private fun page(n: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.rgb(0x3A, 0xAA, 0x35) }
        c.drawRect(0f, 0f, 850f, 120f, p)
        p.color = Color.DKGRAY
        for (i in 0 until 14) c.drawRect(60f, 200f + i * 50f, 790f - (i % 3) * 120f, 220f + i * 50f, p)
        p.textSize = 40f
        c.drawText("Example Report 912345 - page $n", 60f, 80f, p)
        return bmp.asImageBitmap()
    }

    @Test fun submit_status_complete() = shot("submit_status_complete", SubmitPhase.Confirm(StatusWarning.ALREADY_COMPLETE))
    @Test fun submit_status_sent() = shot("submit_status_sent", SubmitPhase.Confirm(StatusWarning.ALREADY_SENT))
    @Test fun submit_building() = shot("submit_building", SubmitPhase.Working(SubmitStep.BUILD))
    @Test fun submit_spell_check() = shot("submit_spell_check", SubmitPhase.SpellReview(listOf(
        issue("scorring", listOf("scoring")), issue("bearring", listOf("bearing", "bearings")), issue("slinger", emptyList()))))
    @Test fun submit_spell_done() = shot("submit_spell_done", SubmitPhase.SpellReview(emptyList()))
    @Test fun submit_error_file_in_use() = shot("submit_error_file_in_use", SubmitPhase.Error(SubmitError(SubmitErrorKind.FILE_IN_USE,
        "Report file is open", "912345 Report.pdf is open on someone's PC and can't be replaced. Ask them to close it, then try again. The old report was not changed.", true)))
    @Test fun submit_preview() = shot("submit_preview", SubmitPhase.Preview(PdfPreviewState(
        title = "912345 Report.pdf", pageCount = 6, pages = mapOf(0 to page(1), 1 to page(2)), notes = listOf("Status set to Report Complete."))))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletSubmitScreenshots : SubmitCatalog("tablet")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class PhoneSubmitScreenshots : SubmitCatalog("phone")
