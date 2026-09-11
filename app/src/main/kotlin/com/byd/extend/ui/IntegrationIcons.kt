package com.byd.extend.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object IntegrationIcons {
    val TurnSignals = ImageVector.Builder("TurnSignals", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(1f, 12f); lineTo(7f, 6f); lineTo(7f, 9f)
            lineTo(11f, 9f); lineTo(11f, 15f); lineTo(7f, 15f)
            lineTo(7f, 18f); close()
            moveTo(23f, 12f); lineTo(17f, 6f); lineTo(17f, 9f)
            lineTo(13f, 9f); lineTo(13f, 15f); lineTo(17f, 15f)
            lineTo(17f, 18f); close()
        }
    }.build()

    val Weather = ImageVector.Builder("PartlyCloudy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.8f, 10.3f); curveTo(3.3f, 8.1f, 4.6f, 5f, 7.3f, 5f)
            curveTo(9f, 5f, 10.4f, 6.2f, 10.6f, 7.7f)
            moveTo(7.3f, 1f); lineTo(7.3f, 2f)
            moveTo(2.4f, 3f); lineTo(3.1f, 3.7f)
            moveTo(1f, 8f); lineTo(2f, 8f)
            moveTo(11.5f, 3.7f); lineTo(12.2f, 3f)
            moveTo(7f, 20f); lineTo(18f, 20f)
            curveTo(20.2f, 20f, 22f, 18.2f, 22f, 16f)
            curveTo(22f, 13.8f, 20.2f, 12f, 18f, 12f)
            curveTo(17.6f, 9.6f, 15.7f, 8f, 13.5f, 8f)
            curveTo(11.3f, 8f, 9.4f, 9.5f, 9f, 11.5f)
            curveTo(8.2f, 11.1f, 7.3f, 11f, 6.5f, 11f)
            curveTo(4f, 11f, 2f, 13f, 2f, 15.5f)
            curveTo(2f, 18f, 4.2f, 20f, 7f, 20f)
            close()
        }
    }.build()
}
