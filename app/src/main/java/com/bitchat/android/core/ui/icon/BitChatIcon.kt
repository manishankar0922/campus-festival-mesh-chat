package com.bitchat.android.core.ui.icon

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BluChatIcon: ImageVector
    get() {
        _BluChatIcon?.let { return it }

        return ImageVector.Builder(
            name = "BluChatIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF0066FF)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 3f)
                lineTo(18f, 3f)
                quadTo(21f, 3f, 21f, 6f)
                lineTo(21f, 14f)
                quadTo(21f, 17f, 18f, 17f)
                lineTo(8f, 17f)
                lineTo(4f, 21f)
                lineTo(4f, 17f)
                quadTo(3f, 17f, 3f, 14f)
                lineTo(3f, 6f)
                quadTo(3f, 3f, 6f, 3f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF0066FF)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 6f)
                lineTo(11.5f, 8.5f)
                lineTo(8.5f, 11f)
                lineTo(8.5f, 5.5f)
                lineTo(11.5f, 13.5f)
                lineTo(8.5f, 16f)
                lineTo(8.5f, 11f)
            }
            path(fill = SolidColor(Color(0xFF0066FF))) {
                // Dot 1
                moveTo(14f, 11f)
                lineTo(15.2f, 11f)
                lineTo(15.2f, 12.2f)
                lineTo(14f, 12.2f)
                close()

                // Dot 2
                moveTo(16.2f, 11f)
                lineTo(17.4f, 11f)
                lineTo(17.4f, 12.2f)
                lineTo(16.2f, 12.2f)
                close()

                // Dot 3
                moveTo(18.4f, 11f)
                lineTo(19.6f, 11f)
                lineTo(19.6f, 12.2f)
                lineTo(18.4f, 12.2f)
                close()
            }
        }.build().also { _BluChatIcon = it }
    }

val BitChatIcon: ImageVector get() = BluChatIcon

private var _BluChatIcon: ImageVector? = null
