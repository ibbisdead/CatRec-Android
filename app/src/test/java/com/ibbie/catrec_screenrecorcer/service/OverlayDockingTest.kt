package com.ibbie.catrec_screenrecorcer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDockingTest {
    @Test
    fun `snaps from left side with dock offset`() {
        val position =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 24,
                currentY = 120,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 24,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertEquals(OverlayDockEdge.LEFT, position.edge)
        assertEquals(-13, position.x)
        assertEquals(120, position.y)
    }

    @Test
    fun `snaps from right side with dock offset`() {
        val position =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 300,
                currentY = 120,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 24,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertEquals(OverlayDockEdge.RIGHT, position.edge)
        assertEquals(353, position.x)
        assertEquals(120, position.y)
    }

    @Test
    fun `starting near top-left corner docks and clamps y`() {
        val position =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 2,
                currentY = -60,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertEquals(OverlayDockEdge.LEFT, position.edge)
        assertEquals(-13, position.x)
        assertEquals(28, position.y)
    }

    @Test
    fun `starting near bottom-right corner docks and clamps y`() {
        val position =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 396,
                currentY = 790,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertEquals(OverlayDockEdge.RIGHT, position.edge)
        assertEquals(353, position.x)
        assertEquals(720, position.y)
    }

    @Test
    fun `y stays inside usable vertical bounds`() {
        val top =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 160,
                currentY = -1_000,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )
        val bottom =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 160,
                currentY = 1_000,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertEquals(28, top.y)
        assertEquals(720, bottom.y)
    }

    @Test
    fun `recording dock tucks pill offscreen while timer stays visible`() {
        val left =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 0,
                currentY = 120,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )
        val right =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 340,
                currentY = 120,
                overlayWidth = 60,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 24,
                fullyVisibleContentWidth = 34,
            )

        assertTrue("left.x=${left.x}", left.x < 0)
        assertTrue("right.x=${right.x}", right.x + 60 > 400)
        assertContentVisible(positionX = left.x, overlayWidth = 60, contentWidth = 34, screenWidth = 400)
        assertContentVisible(positionX = right.x, overlayWidth = 60, contentWidth = 34, screenWidth = 400)
    }

    @Test
    fun `dock offset is capped so timer text stays visible`() {
        val left =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 0,
                currentY = 120,
                overlayWidth = 72,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 50,
                fullyVisibleContentWidth = 54,
            )
        val right =
            OverlayDocking.snapToNearestHorizontalEdge(
                currentX = 340,
                currentY = 120,
                overlayWidth = 72,
                overlayHeight = 32,
                screenWidth = 400,
                screenHeight = 800,
                safeTop = 28,
                safeBottom = 48,
                requestedDockOffset = 50,
                fullyVisibleContentWidth = 54,
            )

        assertContentVisible(positionX = left.x, overlayWidth = 72, contentWidth = 54, screenWidth = 400)
        assertContentVisible(positionX = right.x, overlayWidth = 72, contentWidth = 54, screenWidth = 400)
    }

    private fun assertContentVisible(
        positionX: Int,
        overlayWidth: Int,
        contentWidth: Int,
        screenWidth: Int,
    ) {
        val contentLeft = positionX + (overlayWidth - contentWidth) / 2
        val contentRight = positionX + (overlayWidth + contentWidth) / 2

        assertTrue("contentLeft=$contentLeft", contentLeft >= 0)
        assertTrue("contentRight=$contentRight", contentRight <= screenWidth)
    }
}
