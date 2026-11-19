package com.bitzer.rmainspector.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.LocalPhoto
import com.bitzer.rmainspector.data.SubmitErrors
import com.bitzer.rmainspector.data.SubmitInput
import com.bitzer.rmainspector.data.SubmitReview
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.SubmitDone
import com.bitzer.rmainspector.ui.workspace.SubmitQueued
import com.bitzer.rmainspector.ui.workspace.InspectionSubmitPhase
import com.bitzer.rmainspector.ui.workspace.WorkspaceCallbacks
import com.bitzer.rmainspector.ui.workspace.WorkspaceContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the submit flow (B3, spec §6): review, the already-complete / already-sent dialogs, uploading,
 * success and the problem dialogs. FAKE data. Kept in its own file so other issues' catalogs don't collide with it.
 */
abstract class InspectionSubmitCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, phase: InspectionSubmitPhase) {
        compose.setContent {
            RmaInspectorTheme {
                WorkspaceContent(ScreenshotData.workspaceFindings.copy(submit = phase), WorkspaceCallbacks())
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    private fun c(code: String, desc: String = code) = Code(null, code, desc)

    private val rma = ScreenshotData.rma
    private val photos = listOf("s1", "s2", "s3").map {
        LocalPhoto(it, LocalPhoto.KIND_PART, rowLocalId = "new-1", capturedAt = "2026-11-17T10:49:02-05:00", file = "$it.jpg")
    }

    private fun review(status: String = "Received", rows: List<FindingRow> = ScreenshotData.findingRows.take(3)): SubmitReview {
        val r = rma.copy(header = rma.header.copy(inspectionStatus = c(status)))
        return SubmitReview.build(SubmitInput(r, r, r.signature, rows, photos, ScreenshotData.findingCodes, null, "demo", "device-1"))
    }

    @Test fun submit_review() = shot("submit_review", InspectionSubmitPhase.Review(review()))
    @Test fun submit_review_incomplete_row() = shot("submit_review_incomplete_row", InspectionSubmitPhase.Review(review(rows = ScreenshotData.findingRows)))
    @Test fun submit_review_report_complete() = shot("submit_review_report_complete", InspectionSubmitPhase.Review(review("Report Complete")))
    @Test fun submit_report_already_complete_dialog() =
        shot("submit_report_already_complete_dialog", InspectionSubmitPhase.Review(review("Report Complete"), warning = true))
    @Test fun submit_report_already_sent_warning() =
        shot("submit_report_already_sent_warning", InspectionSubmitPhase.Review(review("Sent to RSM"), warning = true))
    @Test fun submit_saving() = shot("submit_saving", InspectionSubmitPhase.Working("Saving findings…"))
    @Test fun submit_uploading() = shot("submit_uploading", InspectionSubmitPhase.Working("Uploading photos…", 2, 5))
    @Test fun submit_success() = shot("submit_success", InspectionSubmitPhase.Done(SubmitDone("Inspected", statusChanged = true, partsSaved = true, photosSent = 3)))
    @Test fun submit_success_report_rebuilt() = shot("submit_success_report_rebuilt",
        InspectionSubmitPhase.Done(SubmitDone("Report Complete", statusChanged = false, partsSaved = true, photosSent = 0, reportRebuilt = true)))
    @Test fun submit_success_photo_problem() = shot("submit_success_photo_problem",
        InspectionSubmitPhase.Done(SubmitDone("Inspected", statusChanged = true, partsSaved = true, photosSent = 1,
            photoProblems = listOf("Can't reach the server at http://rma-server.example.invalid.")))
    )
    @Test fun submit_queued() = shot("submit_queued", InspectionSubmitPhase.Queued(SubmitQueued(findingsSaved = false, photosQueued = 3)))
    @Test fun submit_queued_photos_only() = shot("submit_queued_photos_only",
        InspectionSubmitPhase.Queued(SubmitQueued(findingsSaved = true, photosQueued = 2)))
    @Test fun submit_offline() = shot("submit_offline",
        InspectionSubmitPhase.Failed(SubmitErrors.map(ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "Can't reach the server.", retryable = true))))
    @Test fun submit_changed_elsewhere() = shot("submit_changed_elsewhere",
        InspectionSubmitPhase.Failed(SubmitErrors.map(ApiResult.Failure(ApiResult.Kind.SERVER_ERROR,
            "Someone changed RMA 912345 after you loaded it. Nothing was saved.", code = "RMA_CHANGED", httpStatus = 409))))
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class InspectionSubmitTabletScreenshots : InspectionSubmitCatalog("tablet")

/** Phone, portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class InspectionSubmitPhoneScreenshots : InspectionSubmitCatalog("phone")
