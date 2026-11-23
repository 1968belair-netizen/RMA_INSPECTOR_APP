package com.bitzer.rmainspector.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.data.ReportPick
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.ReportPhotoViewerCallbacks
import com.bitzer.rmainspector.ui.workspace.ReportPhotoViewerContent
import com.bitzer.rmainspector.ui.workspace.ReportPhotoViewerState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the Report-tab photo viewer (#82): with photos, RMA-level photo, empty set, long caption, offline
 * placeholder. Stateless composable, FAKE names, generated colour pictures. Own file so other catalogs don't collide.
 */
abstract class ReportPhotoViewerCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, state: ReportPhotoViewerState, pictures: Boolean = true) {
        val cb = ReportPhotoViewerCallbacks(loadImage = { _, filename, _ -> if (pictures) fake(filename) else null })
        compose.setContent {
            RmaInspectorTheme { Surface(Modifier.fillMaxSize()) { ReportPhotoViewerContent(state, cb) } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    private fun fake(filename: String): ImageBitmap {
        val bmp = ImageBitmap(640, 480)
        val hue = (filename.hashCode() and 0x7fffffff) % 360
        val paint = Paint().apply { color = Color.hsv(hue.toFloat(), 0.35f, 0.75f) }
        Canvas(bmp).drawRect(0f, 0f, 640f, 480f, paint)
        return bmp
    }

    private fun p(name: String) = PhotoEntry("$name.jpg")
    private val longName = "04-3 Connecting rod main bearing with a very long part description that wraps onto two lines - 13 " +
        "Heavy wear on the bearing surface near the oil groove with scoring"
    private val photos = listOf(
        p("00-1 As received"), p("00-2 Nameplate"), p("01-1 Terminal box - 50 Normal"),
        p("02-1 Winding resistance - 39 Winding fault"), p("03-1 Piston assy, skirt - 34 Scored, Scratched"),
        p("03-2 Piston assy, skirt - 34 Scored, Scratched"), p("04-1 Connecting rod main bearing - 13 Heavy wear"),
        p(longName), p("05-1 Oil pump - 50 Normal"), p("06-1 Valve plate - 48a Broken reed"),
    )
    private val picks = listOf(
        ReportPick("all_pics", "00-2 Nameplate.jpg", "00-2 Nameplate"),
        ReportPick("all_pics", "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg", "03-1 Piston assy, skirt"),
        ReportPick("all_pics", "04-1 Connecting rod main bearing - 13 Heavy wear.jpg", "04-1 Connecting rod main bearing"),
    )
    private fun key(name: String) = "all/$name.jpg"

    @Test fun viewer_with_photos() = shot("viewer_with_photos",
        ReportPhotoViewerState(photos, picks, selected = key("03-1 Piston assy, skirt - 34 Scored, Scratched")))

    @Test fun viewer_pick_button_add() = shot("viewer_pick_button_add",
        ReportPhotoViewerState(photos, picks, selected = key("01-1 Terminal box - 50 Normal"), canPick = true))

    @Test fun viewer_pick_button_remove() = shot("viewer_pick_button_remove",
        ReportPhotoViewerState(photos, picks, selected = key("03-1 Piston assy, skirt - 34 Scored, Scratched"), canPick = true))

    @Test fun viewer_rma_level_photo() = shot("viewer_rma_level_photo",
        ReportPhotoViewerState(photos, picks, selected = key("00-2 Nameplate")))

    @Test fun viewer_report_photos() = shot("viewer_report_photos",
        ReportPhotoViewerState(photos, picks, reportOnly = true, selected = key("03-1 Piston assy, skirt - 34 Scored, Scratched")))

    @Test fun viewer_empty_all() = shot("viewer_empty_all", ReportPhotoViewerState(emptyList(), picks))

    @Test fun viewer_empty_report_set() = shot("viewer_empty_report_set", ReportPhotoViewerState(photos, emptyList(), reportOnly = true))

    @Test fun viewer_long_caption() = shot("viewer_long_caption", ReportPhotoViewerState(photos, picks, selected = key(longName)))

    @Test fun viewer_offline_placeholder() = shot("viewer_offline_placeholder",
        ReportPhotoViewerState(photos, picks, selected = key("01-1 Terminal box - 50 Normal")), pictures = false)
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ReportPhotoViewerTabletScreenshots : ReportPhotoViewerCatalog("tablet")

/** Phone, portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class ReportPhotoViewerPhoneScreenshots : ReportPhotoViewerCatalog("phone")
