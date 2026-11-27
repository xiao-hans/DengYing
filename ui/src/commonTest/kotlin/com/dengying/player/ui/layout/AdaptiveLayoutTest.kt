package com.dengying.player.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveLayoutTest {

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
    fun phoneKeepsFrozenValues() {
        val phone = AdaptiveSpec.from(347f, 753f)
        assertEquals(WidthClass.Compact, phone.widthClass)
        assertEquals(3, phone.gridColumns)
        assertEquals(2, phone.feedColumns)
        assertTrue(phone.contentMaxDp.isInfinite())
        assertEquals(12f, phone.sidePaddingDp)
        assertTrue(phone.heroMaxDp.isInfinite())
        assertEquals(112f, phone.railCardDp)
        assertEquals(34f, phone.embeddedControlDp)
        assertEquals(44f, phone.fullscreenPrimaryDp)
        assertEquals(40f, phone.fullscreenSecondaryDp)
        assertFalse(phone.detailTwoPane)
    }

    @Test
    fun columnsAndLimitsPerWidthClass() {
        val medium = AdaptiveSpec.from(600f, 960f)
        assertEquals(4, medium.gridColumns)
        assertEquals(3, medium.feedColumns)
        assertEquals(720f, medium.contentMaxDp)
        assertEquals(24f, medium.sidePaddingDp)
        assertEquals(720f, medium.heroMaxDp)
        assertEquals(128f, medium.railCardDp)
        assertEquals(44f, medium.embeddedControlDp)
        assertEquals(56f, medium.fullscreenPrimaryDp)
        assertEquals(48f, medium.fullscreenSecondaryDp)
        assertFalse(medium.detailTwoPane)

        val expanded = AdaptiveSpec.from(840f, 800f)
        assertEquals(5, expanded.gridColumns)
        assertEquals(4, expanded.feedColumns)
        assertEquals(1120f, expanded.contentMaxDp)
        assertEquals(32f, expanded.sidePaddingDp)
        assertEquals(960f, expanded.heroMaxDp)
        assertEquals(144f, expanded.railCardDp)
        assertTrue(expanded.detailTwoPane)

        val wide = AdaptiveSpec.from(1280f, 800f)
        assertEquals(6, wide.gridColumns)
        assertEquals(4, wide.feedColumns)
        assertEquals(1280f, wide.contentMaxDp)
        assertEquals(32f, wide.sidePaddingDp)
        assertEquals(960f, wide.heroMaxDp)
        assertEquals(160f, wide.railCardDp)
        assertTrue(wide.detailTwoPane)
    }

    @Test
    fun landscapePhoneIsNotLargeScreen() {
        val spec = AdaptiveSpec.from(915f, 412f)
        assertEquals(WidthClass.Expanded, spec.widthClass)
        assertFalse(spec.isLargeScreen)
        assertFalse(spec.detailTwoPane)
        assertEquals(5, spec.gridColumns)
    }

    @Test
    fun tabletPortraitUsesShortSideWidth() {
        val portrait = AdaptiveSpec.from(800f, 1280f)
        assertEquals(WidthClass.Medium, portrait.widthClass)
        assertFalse(portrait.detailTwoPane)
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
        assertFalse(noHeight.detailTwoPane)
    }

    @Test
    fun posterHeightClampsToDesignRange() {
        assertEquals(140f, AdaptiveSpec.posterHeight(60f), 0.001f)
        assertEquals(147f, AdaptiveSpec.posterHeight(105f), 0.001f)
        assertEquals(220f, AdaptiveSpec.posterHeight(400f), 0.001f)
        assertEquals(140f, AdaptiveSpec.posterHeight(0f), 0.001f)
        assertEquals(140f, AdaptiveSpec.posterHeight(Float.NaN), 0.001f)
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
}
