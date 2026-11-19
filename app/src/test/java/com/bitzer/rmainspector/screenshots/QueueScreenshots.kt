package com.bitzer.rmainspector.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.DraftInspector
import com.bitzer.rmainspector.data.QueueItem
import com.bitzer.rmainspector.data.QueueState
import com.bitzer.rmainspector.data.SignOutPrompt
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.home.HomeContent
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.settings.UploadQueueSection
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the queue screens (#67, spec §2, §6.6): sign-out prompt (online, offline, sending, problem),
 * the "earlier session" banner on Home, and Settings → Upload queue grouped by inspector. FAKE data. Own file so other
 * issues' catalogs don't collide with it.
 */
abstract class QueueCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { RmaInspectorTheme { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    private val me = DraftInspector("demo", "Demo Inspector", "DI")
    private val other = DraftInspector("demo2", "Second Inspector", "D2")

    private fun item(id: String, rma: String, who: DraftInspector = me, state: QueueState = QueueState.WAITING,
                     reason: String? = null, code: String? = null, photos: List<String> = emptyList(), attempts: Int = 0) =
        QueueItem(id = id, rmaId = rma, displayNumber = rma, inspector = who, payload = "{}", photoIds = photos, state = state,
            reason = reason, reasonCode = code, attempts = attempts)

    private val queue = listOf(
        item("q1", "912345", reason = "Can't reach the server.", photos = listOf("p1", "p2", "p3"), attempts = 2),
        item("q2", "912346", state = QueueState.BLOCKED, reason = "Changed elsewhere. Review it before sending.", code = "RMA_CHANGED"),
        item("q3", "912357", state = QueueState.SENDING),
        item("q4", "912348", who = other, reason = "Can't reach the server."),
        item("q5", "912360", who = other),
    )

    @Composable
    private fun settings(app: AppUiState) =
        SettingsScreen(app, onBack = {}, onSaveServer = { null }, onResetServer = {}, onTest = {}, onSignOut = {}, now = 1_000_000L)

    private val withQueue get() = ScreenshotData.appOk.copy(queue = queue)

    @Test fun queue_signout_prompt_online() = shot("queue_signout_prompt_online") {
        settings(withQueue.copy(signOutPrompt = SignOutPrompt.Ask(2)))
    }
    @Test fun queue_signout_prompt_offline() = shot("queue_signout_prompt_offline") {
        settings(withQueue.copy(connection = ScreenshotData.appOffline.connection, signOutPrompt = SignOutPrompt.Offline(2)))
    }
    @Test fun queue_signout_prompt_sending() = shot("queue_signout_prompt_sending") {
        settings(withQueue.copy(signOutPrompt = SignOutPrompt.Sending(2)))
    }
    @Test fun queue_signout_prompt_problem() = shot("queue_signout_prompt_problem") {
        settings(withQueue.copy(signOutPrompt = SignOutPrompt.Problem(1, "Open in Macola. Will retry.")))
    }
    // The queue section on its own (on the Settings screen it sits below Server and Signed in).
    @Composable
    private fun section(items: List<QueueItem>) = Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            UploadQueueSection(items, "demo", onRetry = {}, onCancel = {}, now = 1_000_000L)
        }
    }

    @Test fun queue_by_inspector() = shot("queue_by_inspector") { section(queue) }
    @Test fun queue_empty() = shot("queue_empty") { section(emptyList()) }

    @Test fun home_earlier_uploads_banner() = shot("home_earlier_uploads_banner") {
        HomeContent(ScreenshotData.appOk.copy(earlierUploads = 2), ScreenshotData.homeDefault, query = "",
            onQueryChange = {}, onSearch = {}, onClearSearch = {}, onToggleProductType = {}, onToggleReturnCode = {},
            onRefreshWorkList = {}, onOpenSettings = {}, onRetryServer = {}, onOpenRma = {}, today = ScreenshotData.today)
    }
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class QueueTabletScreenshots : QueueCatalog("tablet")

/** Phone, portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class QueuePhoneScreenshots : QueueCatalog("phone")
