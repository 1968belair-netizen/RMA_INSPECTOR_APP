package com.bitzer.rmainspector.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.ui.theme.BitzerColors
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.GuidedButton
import com.bitzer.rmainspector.ui.workspace.ReportHeaderCallbacks
import com.bitzer.rmainspector.ui.workspace.ReportHeaderContent
import com.bitzer.rmainspector.ui.workspace.ReportHeaderState
import com.bitzer.rmainspector.ui.workspace.reportHeaderState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Slim Report-tab header (issue #81) at window widths 600 / 800 / 960 / 1280 dp, at font scale 100% and 130% with a
 * long customer name and a long failure description, and with Details expanded. FAKE data. Own file so the viewer
 * and layout issues' catalogs don't collide with it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ReportHeaderScreenshots {
    @get:Rule val compose = createComposeRule()

    private val normal: ReportHeaderState = reportHeaderState(ScreenshotData.rma, expanded = false, guided = GuidedButton.Unavailable("Recip"))
    private val long: ReportHeaderState = normal.copy(
        customer = "Example Refrigeration & Cold Storage Company International",
        failureCode = "9-Oil Issue",
        failureDescription = "Example: low oil pressure trips reported repeatedly after the oil pump was replaced twice in the field",
    )

    private fun shot(name: String, widthDp: Int, state: ReportHeaderState, fontScale: Float = 1f) {
        compose.setContent {
            RmaInspectorTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.width(widthDp.dp), color = BitzerColors.White) {
                            ReportHeaderContent(state, ReportHeaderCallbacks())
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tablet_$name.png")
    }

    @Test fun w600() = shot("report_header_w600", 600, normal)
    @Test fun w800() = shot("report_header_w800", 800, normal)
    @Test fun w960() = shot("report_header_w960", 960, normal)
    @Test fun w1280() = shot("report_header_w1280", 1280, normal)

    @Test fun w600_font130_long() = shot("report_header_w600_font130_long", 600, long, 1.3f)
    @Test fun w800_font130_long() = shot("report_header_w800_font130_long", 800, long, 1.3f)
    @Test fun w960_font130_long() = shot("report_header_w960_font130_long", 960, long, 1.3f)
    @Test fun w1280_font130_long() = shot("report_header_w1280_font130_long", 1280, long, 1.3f)

    @Test fun w800_long() = shot("report_header_w800_long", 800, long)
    @Test fun w1280_long() = shot("report_header_w1280_long", 1280, long)

    @Test fun expanded_w1280() = shot("report_header_expanded_w1280", 1280, long.copy(expanded = true))
    @Test fun expanded_w600_font130() = shot("report_header_expanded_w600_font130", 600, long.copy(expanded = true), 1.3f)
}
