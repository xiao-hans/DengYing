package com.dengying.player.ui.pages

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 详情页播放源折叠规则（FR-634）。
 *
 * 当前源就是候选里的普通一条（用选中态区分），不再额外占位：
 * 20 个上限 → 最多铺 20 个候选，第 21 个起收进「更多」。
 */
class SourceChipSplitTest {

    @Test
    fun `候选不足上限时全部铺出`() {
        assertEquals(0 to 0, splitSourceChips(0))
        assertEquals(6 to 0, splitSourceChips(6))
        assertEquals(20 to 0, splitSourceChips(20))
    }

    @Test
    fun `超过上限时余下的收进更多`() {
        assertEquals(20 to 0, splitSourceChips(20))
        assertEquals(20 to 1, splitSourceChips(21))
        assertEquals(20 to 80, splitSourceChips(100))
    }

    @Test
    fun `上限可注入且退化时不出负数`() {
        assertEquals(3 to 0, splitSourceChips(3, max = 3))
        assertEquals(1 to 2, splitSourceChips(3, max = 1))
        assertEquals(0 to 3, splitSourceChips(3, max = 0))
    }
}
