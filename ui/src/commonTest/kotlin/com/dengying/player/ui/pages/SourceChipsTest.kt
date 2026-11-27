package com.dengying.player.ui.pages

import com.dengying.player.ui.viewmodel.AltItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 详情页播放源 chip 模型（FR-634）：当前源由选中态表达，不再另起一枚黑底 chip。 */
class SourceChipsTest {

    private fun item(key: String, eps: Int = -1) =
        AltItem(sourceKey = key, sourceName = key, vodId = 1L, vodName = "片", epCount = eps)

    @Test
    fun `当前源选中其余不选中`() {
        val (chips, hidden) = sourceChips(
            alts = listOf(item("a"), item("b"), item("c")),
            currentKey = "b",
        )
        assertEquals(0, hidden)
        assertEquals(listOf(false, true, false), chips.map { it.selected })
        assertEquals(listOf("a", "b · 当前", "c"), chips.map { it.label })
    }

    @Test
    fun `当前源在可见区之外时不伪造选中`() {
        val alts = (1..25).map { item("s$it") }
        val (chips, hidden) = sourceChips(alts, currentKey = "s25", max = 20)
        assertEquals(20, chips.size)
        assertEquals(5, hidden)
        assertTrue(chips.none { it.selected })
        assertFalse(chips.any { it.label.endsWith(" · 当前") })
    }

    @Test
    fun `当前标记只出现在选中项上`() {
        val alts = listOf(item("a"), item("b"), item("c"))
        val (chips, _) = sourceChips(alts, currentKey = "a")
        assertEquals(listOf("a · 当前"), chips.filter { it.selected }.map { it.label })
        assertFalse(chips.filterNot { it.selected }.any { it.label.contains("当前") })
    }
}
