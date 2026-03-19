package com.ibbie.catrec_screenrecorcer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object CatRecIcons {
    val Settings = Icons.Default.Settings
    val Recordings = Icons.Default.VideoLibrary
    val Tools = Icons.Default.Build
    val Support = Icons.Default.Favorite

    val Paw: ImageVector
        get() = if (_paw != null) _paw!! else {
            _paw = ImageVector.Builder(
                name = "Paw",
                defaultWidth = 24.0.dp,
                defaultHeight = 24.0.dp,
                viewportWidth = 24.0f,
                viewportHeight = 24.0f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1.0f,
                    stroke = null,
                    strokeAlpha = 1.0f,
                    strokeLineWidth = 1.0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 1.0f,
                    pathFillType = PathFillType.NonZero
                ) {
                    // Simple paw representation
                    moveTo(12f, 2f)
                    curveTo(10f, 2f, 8f, 4f, 8f, 6f)
                    curveTo(8f, 8f, 10f, 10f, 12f, 10f)
                    curveTo(14f, 10f, 16f, 8f, 16f, 6f)
                    curveTo(16f, 4f, 14f, 2f, 12f, 2f)
                    close()
                    
                    moveTo(4f, 8f)
                    curveTo(2f, 8f, 1f, 10f, 1f, 12f)
                    curveTo(1f, 14f, 2f, 16f, 4f, 16f)
                    curveTo(6f, 16f, 7f, 14f, 7f, 12f)
                    curveTo(7f, 10f, 6f, 8f, 4f, 8f)
                    close()

                    moveTo(20f, 8f)
                    curveTo(18f, 8f, 17f, 10f, 17f, 12f)
                    curveTo(17f, 14f, 18f, 16f, 20f, 16f)
                    curveTo(22f, 16f, 23f, 14f, 23f, 12f)
                    curveTo(23f, 10f, 22f, 8f, 20f, 8f)
                    close()

                    moveTo(12f, 12f)
                    curveTo(8f, 12f, 5f, 15f, 5f, 19f)
                    curveTo(5f, 22f, 8f, 23f, 12f, 23f)
                    curveTo(16f, 23f, 19f, 22f, 19f, 19f)
                    curveTo(19f, 15f, 16f, 12f, 12f, 12f)
                    close()
                }
            }.build()
            _paw!!
        }

    private var _paw: ImageVector? = null
}
