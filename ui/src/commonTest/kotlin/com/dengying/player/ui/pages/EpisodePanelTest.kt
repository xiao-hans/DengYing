package com.dengying.player.ui.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodePanelTest {

    @Test
    fun `drawer width clamps phone and tablet and rejects invalid sizes`() {
        assertEquals(280f, episodeDrawerWidth(640f))
        assertEquals(360f, episodeDrawerWidth(1280f))
        assertEquals(320f, episodeDrawerWidth(Float.NaN))
        assertEquals(320f, episodeDrawerWidth(0f))
    }

    @Test
    fun `reversed display order keeps real episode indices`() {
        val cells = episodeCells(
            names = listOf("第3集", "第2集", "第1集"),
            realIndices = listOf(2, 1, 0),
            selectedReal = -1,
        )
        assertEquals(listOf(2, 1, 0), cells.map { it.realIndex })
    }

    @Test
    fun `selected episode is marked in both orders`() {
        val ascending = episodeCells(
            names = listOf("第1集", "第2集", "第3集"),
            realIndices = listOf(0, 1, 2),
            selectedReal = 1,
        )
        val descending = episodeCells(
            names = listOf("第3集", "第2集", "第1集"),
            realIndices = listOf(2, 1, 0),
            selectedReal = 1,
        )
        assertEquals(listOf(false, true, false), ascending.map { it.selected })
        assertEquals(listOf(false, true, false), descending.map { it.selected })
        assertTrue(ascending[1].selected)
        assertFalse(descending[0].selected)
    }

    @Test
    fun `blank episode name falls back to real index`() {
        val cells = episodeCells(
            names = listOf("", "   ", "第3集"),
            realIndices = listOf(4, 5, 6),
            selectedReal = -1,
        )
        assertEquals("第5集", cells[0].label)
        assertEquals("第6集", cells[1].label)
        assertEquals("第3集", cells[2].label)
    }
}
