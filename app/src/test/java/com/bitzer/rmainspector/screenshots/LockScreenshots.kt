package com.bitzer.rmainspector.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.LockHandling
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.InspectionSubmitPhase
import com.bitzer.rmainspector.ui.workspace.SubmitQueued
import com.bitzer.rmainspector.ui.workspace.WorkspaceCallbacks
import com.bitzer.rmainspector.ui.workspace.WorkspaceContent
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot tests of the Macola-lock results of a submit (#66, spec §6.4). FAKE data. Own file: no clash with other catalogs. */
abstract class LockScreenshotCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, failure: ApiResult.Failure) {
        val phase = InspectionSubmitPhase.Queued(SubmitQueued(findingsSaved = false, photosQueued = 2, offline = false,
            lock = LockHandling.noticeFor(failure)))
        compose.setContent {
            RmaInspectorTheme { WorkspaceContent(ScreenshotData.workspaceFindings.copy(submit = phase), WorkspaceCallbacks()) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    private fun fail(code: String, status: Int, details: Map<String, kotlinx.serialization.json.JsonElement>) =
        ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "Nothing was saved.", code = code, httpStatus = status,
            retryable = true, retryAfterSeconds = 120, details = JsonObject(details))

    @Test fun lock_open_in_macola() = shot("lock_open_in_macola", fail("RMA_LOCKED", 423, mapOf(
        "held_by" to JsonPrimitive("JDOE2"), "host" to JsonPrimitive("EXAMPLE-PC"),
        "since" to JsonPrimitive("2026-11-17T10:41:12-05:00"), "possibly_stale" to JsonPrimitive(false))))

    @Test fun lock_possible_stale() = shot("lock_possible_stale", fail("RMA_LOCKED", 423, mapOf(
        "held_by" to JsonPrimitive("JDOE2"), "host" to JsonPrimitive("EXAMPLE-PC"), "possibly_stale" to JsonPrimitive(true))))

    @Test fun lock_check_failed() = shot("lock_check_failed", fail("LOCK_CHECK_FAILED", 503, emptyMap()))
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class LockTabletScreenshots : LockScreenshotCatalog("tablet")

/** Phone, portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class LockPhoneScreenshots : LockScreenshotCatalog("phone")
