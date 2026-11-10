package app.carnet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's stroke icon set (24-unit grid), matching the design. */
object Ic {
    private fun icon(name: String, vararg paths: String, fill: Boolean = false, stroke: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            if (fill) b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            else b.addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} a$r,$r 0 0 1 ${-r},$r h${-(w - 2 * r)} a$r,$r 0 0 1 ${-r},${-r} v${-(h - 2 * r)} a$r,$r 0 0 1 $r,${-r}z"

    val Logo = icon("logo", "M6 4h9a3 3 0 0 1 3 3v13H9a3 3 0 0 1-3-3z", "M10 10h5", "M10 14h3", stroke = 2f)
    val Translate = icon("translate", "M4 5h9", "M8.5 3v2", "M6 5c.8 3 2.8 5.5 6 7", "M11 5c-.8 3-2.8 5.5-6 7", "M13 21l4-9 4 9", "M14.4 18h5.2")
    val File = icon("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    val Camera = icon("camera", "M4 8h3l2-3h6l2 3h3v11H4z", circle(12f, 13f, 3.5f))
    val Library = icon("library", "M3 12a9 9 0 1 0 3-6.7", "M3 4v4h4", "M12 8v4l3 2")
    val Mic = icon("mic", rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val Settings = icon("settings", "M4 7h9", "M18 7h2", circle(15.5f, 7f, 2.3f), "M4 17h3", "M12 17h8", circle(9.5f, 17f, 2.3f))
    val Tools = icon("tools", rect(4f, 4f, 7f, 7f, 1.5f), rect(13f, 4f, 7f, 7f, 1.5f), rect(4f, 13f, 7f, 7f, 1.5f), rect(13f, 13f, 7f, 7f, 1.5f))
    val Glossary = icon("glossary", "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z", "M5 17a3 3 0 0 1 3-3h11")
    val Paste = icon("paste", rect(7f, 4f, 10f, 4f, 1f), "M7 6H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-1")
    val Copy = icon("copy", rect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3")
    val Listen = icon("listen", "M4 10v4h4l5 4V6L8 10z", "M16.5 9a4 4 0 0 1 0 6")
    val Share = icon("share", "M12 4v11", "M8 8l4-4 4 4", "M5 14v5h14v-5")
    val Save = icon("save", "M12 4v11", "M8 11l4 4 4-4", "M5 20h14")
    val Search = icon("search", circle(11f, 11f, 6.5f), "M16 16l4 4")
    val Add = icon("add", "M12 5v14", "M5 12h14", stroke = 2f)
    val Delete = icon("delete", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val Flash = icon("flash", "M13 3L5 14h6l-1 7 8-11h-6z")
    val Gallery = icon("gallery", rect(3f, 5f, 18f, 14f, 2f), circle(9f, 10f, 1.5f), "M21 16l-5-5-9 8")
    val Rotate = icon("rotate", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5")
    val Crop = icon("crop", "M6 2v14a2 2 0 0 0 2 2h14", "M18 22V8a2 2 0 0 0-2-2H2")
    val Arrow = icon("arrow", "M5 12h14", "M13 6l6 6-6 6", stroke = 2f)
    val Check = icon("check", "M5 12l5 5 9-10", stroke = 2.2f)
    val Back = icon("back", "M15 5l-7 7 7 7", stroke = 2f)
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18", stroke = 2f)
    val Chevron = icon("chevron", "M9 5l7 7-7 7")
    val More = icon("more", circle(12f, 5.5f, 1.7f), circle(12f, 12f, 1.7f), circle(12f, 18.5f, 1.7f), fill = true)
    val Play = icon("play", "M8 5v14l11-7z", fill = true)
    val Pause = icon("pause", rect(6f, 5f, 4f, 14f, 1f), rect(14f, 5f, 4f, 14f, 1f), fill = true)
    val Stop = icon("stop", rect(6f, 6f, 12f, 12f, 2f), fill = true)
    val Mark = icon("mark", "M6 4h12v17l-6-4-6 4z")
    val Merge = icon("merge", "M8 4h8v6H8z", "M8 14h8v6H8z", "M12 10v4")
    val Split = icon("split", "M5 4h14v6H5z", "M5 14h14v6H5z", "M3 12h3", "M9 12h2", "M14 12h2", "M19 12h2")
    val Compress = icon("compress", "M8 3l4 4 4-4", "M12 7V3", "M8 21l4-4 4 4", "M12 17v4", "M5 12h14")
    val Numbers = icon("numbers", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M10 17h4")
    val Wifi = icon("wifi", "M2 9a15 15 0 0 1 20 0", "M5.5 12.5a10 10 0 0 1 13 0", "M9 16a5 5 0 0 1 6 0", circle(12f, 19.5f, 0.8f))
    val Qr = icon("qr", rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f), "M14 14h3v3h-3z", "M20 14v1", "M14 20h1", "M18 18h3v3")
    val Download = Save
    val Model = icon("model", rect(4f, 4f, 16f, 16f, 3f), "M9 9h6v6H9z", "M9 2v2", "M15 2v2", "M9 20v2", "M15 20v2", "M2 9h2", "M2 15h2", "M20 9h2", "M20 15h2")
    val Pen = icon("pen", "M4 20c2-1 3-3 4-5s3-6 5-6 1 4 3 4 2-3 4-3", "M4 20h3")
    val Bolt = icon("bolt", "M13 3L5 14h6l-1 7 8-11h-6z")
    val Shield = icon("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val Swap = icon("swap", "M7 4l-3 3 3 3", "M4 7h13", "M17 20l3-3-3-3", "M20 17H7")
    val Info = icon("info", circle(12f, 12f, 9f), "M12 11v5", "M12 8v.01")
    val Text = icon("text", "M5 6h14", "M12 6v13", "M9 19h6")
}
