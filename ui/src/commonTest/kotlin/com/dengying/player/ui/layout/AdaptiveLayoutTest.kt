package com.dengying.player.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveLayoutTest {

    // ---- 宽度分档 ----

    @Test
    fun widthClassBoundaries() {
        assertEquals(WidthClass.Compact, AdaptiveSpec.from(599.9f, 800f).widthClass)
        assertEquals(WidthClass.Medium, AdaptiveSpec.from(600f, 800f).widthClass)
        assertEquals(WidthClass.Medium, AdaptiveSpec.from(839.9f, 800f).widthClass)
        assertEquals(WidthClass.Expanded, AdaptiveSpec.from(840f, 800f).widthClass)
        assertEquals(WidthClass.Expanded, AdaptiveSpec.from(1199.9f, 800f).widthClass)
        assertEquals(WidthClass.Wide, AdaptiveSpec.from(1200f, 800f).widthClass)
    }

    @Test
    fun phoneKeepsFrozenColumns() {
        val phone = AdaptiveSpec.from(347f, 753f)
        assertEquals(WidthClass.Compact, phone.widthClass)
        assertEquals(3, phone.gridColumns)
        assertEquals(2, phone.feedColumns)
        assertEquals(0f, phone.contentSideDp)
        assertEquals(0f, phone.railWidthDp)
        assertFalse(phone.showsRail)
    }

    @Test
    fun columnsPerWidthClass() {
        assertEquals(4, AdaptiveSpec.from(600f, 960f).gridColumns)
        assertEquals(3, AdaptiveSpec.from(600f, 960f).feedColumns)
        assertEquals(5, AdaptiveSpec.from(840f, 800f).gridColumns)
        assertEquals(4, AdaptiveSpec.from(840f, 800f).feedColumns)
        assertEquals(6, AdaptiveSpec.from(1280f, 800f).gridColumns)
        assertEquals(4, AdaptiveSpec.from(1280f, 800f).feedColumns)
    }

    // ---- 大屏判定：宽 + 短边 ----

    @Test
    fun landscapePhoneIsNotLargeScreen() {
        // 915×412 的横屏手机宽度已过 840dp，但短边不够。
        val spec = AdaptiveSpec.from(915f, 412f)
        assertFalse(spec.isLargeScreen)
        assertFalse(spec.showsRail)
        assertEquals(0f, spec.railWidthDp)
        assertEquals(5, spec.gridColumns)
    }

    @Test
    fun tabletLandscapeIsLargeScreen() {
        val spec = AdaptiveSpec.from(1280f, 800f)
        assertTrue(spec.isLargeScreen)
        assertTrue(spec.showsRail)
        assertEquals(88f, spec.railWidthDp)
        assertEquals(48f, spec.minTouchDp)
    }

    @Test
    fun tabletPortraitUsesShortSideWidth() {
        // 竖屏 800×1280：宽度只有 800，不进 rail 档。
        assertFalse(AdaptiveSpec.from(800f, 1280f).isLargeScreen)
        assertEquals(4, AdaptiveSpec.from(800f, 1280f).gridColumns)
    }

    @Test
    fun largePortraitTabletIsLargeScreen() {
        val spec = AdaptiveSpec.from(1024f, 1366f)
        assertTrue(spec.isLargeScreen)
        assertEquals(5, spec.gridColumns)
    }

    @Test
    fun invalidSizesFallBackToCompact() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            val spec = AdaptiveSpec.from(bad, 800f)
            assertEquals(WidthClass.Compact, spec.widthClass, "width=$bad")
            assertEquals(3, spec.gridColumns, "width=$bad")
        }
        val noHeight = AdaptiveSpec.from(1280f, Float.NaN)
        assertEquals(WidthClass.Compact, noHeight.widthClass)
        assertFalse(noHeight.isLargeScreen)
    }

    // ---- 居中留白 ----

    @Test
    fun sidePaddingOnlyGrowsBeyondMaxContent() {
        assertEquals(12f, AdaptiveSpec.sidePadding(400f, AdaptiveSpec.MAX_CONTENT_DP), 0.001f)
        assertEquals(12f, AdaptiveSpec.sidePadding(1280f, AdaptiveSpec.MAX_CONTENT_DP), 0.001f)
        assertEquals(172f, AdaptiveSpec.sidePadding(1600f, AdaptiveSpec.MAX_CONTENT_DP), 0.001f)
    }

    @Test
    fun contentSideTracksMaxContentWidth() {
        assertEquals(0f, AdaptiveSpec.from(1280f, 800f).contentSideDp)
        assertEquals(160f, AdaptiveSpec.from(1600f, 900f).contentSideDp, 0.001f)
        assertEquals(0f, AdaptiveSpec.from(900f, 800f).feedSideDp)
        assertEquals(190f, AdaptiveSpec.from(1280f, 800f).feedSideDp, 0.001f)
    }

    // ---- 海报高 / 剧集列 ----

    @Test
    fun posterHeightKeepsPhoneCardUnchanged() {
        // 手机卡宽约 105dp → 1.30 倍后仍被夹到 140，等于改前的固定高。
        assertEquals(140f, AdaptiveSpec.posterHeight(105f), 0.001f)
        assertEquals(140f, AdaptiveSpec.posterHeight(60f), 0.001f)
        assertEquals(240f, AdaptiveSpec.posterHeight(400f), 0.001f)
        assertEquals(140f, AdaptiveSpec.posterHeight(0f), 0.001f)
        assertEquals(140f, AdaptiveSpec.posterHeight(Float.NaN), 0.001f)
        // 平板宽卡按比例变高，但不超过上限。
        assertEquals(195f, AdaptiveSpec.posterHeight(150f), 0.001f)
    }

    @Test
    fun episodeColumnsFollowPaneWidth() {
        assertEquals(4, AdaptiveSpec.episodeColumns(360f))
        assertEquals(4, AdaptiveSpec.episodeColumns(440f))
        assertEquals(6, AdaptiveSpec.episodeColumns(600f))
        assertEquals(8, AdaptiveSpec.episodeColumns(2000f))
        assertEquals(4, AdaptiveSpec.episodeColumns(0f))
        assertEquals(4, AdaptiveSpec.episodeColumns(Float.NaN))
    }

    // ---- 全屏控件尺寸 ----

    @Test
    fun fullscreenControlsOnlyGrowOnLargeScreen() {
        val phone = AdaptiveSpec.from(347f, 753f)
        assertEquals(46f, phone.ctrlPrimaryDp, 0.001f)
        assertEquals(40f, phone.ctrlSecondaryDp, 0.001f)
        val tablet = AdaptiveSpec.from(1280f, 800f)
        assertEquals(56f, tablet.ctrlPrimaryDp, 0.001f)
        assertEquals(48f, tablet.ctrlSecondaryDp, 0.001f)
    }
}
