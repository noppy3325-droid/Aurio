package jp.noppy.lumamusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AurioTabIcons {
    val Home = tabGlyph("Aurio home") {
        moveTo(3.5f, 10.3f)
        lineTo(10.5f, 4.2f)
        quadTo(12f, 2.9f, 13.5f, 4.2f)
        lineTo(20.5f, 10.3f)
        lineTo(20.5f, 19f)
        quadTo(20.5f, 20.4f, 19.1f, 20.4f)
        horizontalLineTo(4.9f)
        quadTo(3.5f, 20.4f, 3.5f, 19f)
        close()
        moveTo(9.2f, 20.3f)
        verticalLineTo(15.9f)
        quadTo(9.2f, 14.3f, 10.8f, 14.3f)
        horizontalLineTo(13.2f)
        quadTo(14.8f, 14.3f, 14.8f, 15.9f)
        verticalLineTo(20.3f)
    }

    val Library = tabGlyph("Aurio library") {
        moveTo(4.4f, 4.4f)
        verticalLineTo(20f)
        moveTo(9.5f, 4.4f)
        verticalLineTo(20f)
        moveTo(14.2f, 4.6f)
        lineTo(19.7f, 19.8f)
    }

    val Search = tabGlyph("Aurio search") {
        moveTo(16.4f, 16.3f)
        lineTo(21f, 21f)
        moveTo(18.1f, 10.6f)
        curveTo(18.1f, 14.7f, 14.7f, 18.1f, 10.6f, 18.1f)
        curveTo(6.4f, 18.1f, 3.1f, 14.7f, 3.1f, 10.6f)
        curveTo(3.1f, 6.4f, 6.4f, 3.1f, 10.6f, 3.1f)
        curveTo(14.7f, 3.1f, 18.1f, 6.4f, 18.1f, 10.6f)
        close()
    }

    val Playlists = tabGlyph("Aurio playlists") {
        moveTo(3f, 5.1f)
        horizontalLineTo(14.2f)
        moveTo(3f, 9.8f)
        horizontalLineTo(12.7f)
        moveTo(3f, 14.5f)
        horizontalLineTo(9.1f)
        moveTo(17.5f, 17.2f)
        verticalLineTo(7.5f)
        lineTo(21f, 8.8f)
        moveTo(17.5f, 17.2f)
        curveTo(17.5f, 18.8f, 15.7f, 20.1f, 13.8f, 20.1f)
        curveTo(12.5f, 20.1f, 11.5f, 19.4f, 11.5f, 18.3f)
        curveTo(11.5f, 16.7f, 13.4f, 15.4f, 15.2f, 15.4f)
        curveTo(16.6f, 15.4f, 17.5f, 16.1f, 17.5f, 17.2f)
        close()
    }
}

private fun tabGlyph(name: String, drawing: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { drawing() }
    }.build()
