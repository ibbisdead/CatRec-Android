package com.ibbie.catrec_screenrecorcer.service

internal enum class OverlayDockEdge {
    LEFT,
    RIGHT,
}

internal data class OverlayDockPosition(
    val x: Int,
    val y: Int,
    val edge: OverlayDockEdge,
)

internal object OverlayDocking {
    fun snapToNearestHorizontalEdge(
        currentX: Int,
        currentY: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        safeTop: Int,
        safeBottom: Int,
        requestedDockOffset: Int,
        fullyVisibleContentWidth: Int,
    ): OverlayDockPosition {
        val cleanScreenWidth = screenWidth.coerceAtLeast(0)
        val cleanScreenHeight = screenHeight.coerceAtLeast(0)
        val width = overlayWidth.coerceAtLeast(0)
        val height = overlayHeight.coerceAtLeast(0)
        val edge =
            if (currentX + width / 2f < cleanScreenWidth / 2f) {
                OverlayDockEdge.LEFT
            } else {
                OverlayDockEdge.RIGHT
            }

        val contentWidth = fullyVisibleContentWidth.coerceIn(0, width)
        val maxDockOffset = ((width - contentWidth) / 2).coerceAtLeast(0)
        val dockOffset = requestedDockOffset.coerceAtLeast(0).coerceAtMost(maxDockOffset)
        val targetX =
            when (edge) {
                OverlayDockEdge.LEFT -> -dockOffset
                OverlayDockEdge.RIGHT -> (cleanScreenWidth - width).coerceAtLeast(0) + dockOffset
            }

        val minY = safeTop.coerceAtLeast(0).coerceAtMost(cleanScreenHeight)
        val maxY =
            (cleanScreenHeight - safeBottom.coerceAtLeast(0) - height)
                .coerceAtLeast(minY)
        val targetY = currentY.coerceIn(minY, maxY)

        return OverlayDockPosition(
            x = targetX,
            y = targetY,
            edge = edge,
        )
    }
}
