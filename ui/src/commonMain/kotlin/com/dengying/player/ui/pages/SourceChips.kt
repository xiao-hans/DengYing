package com.dengying.player.ui.pages

import com.dengying.player.ui.viewmodel.AltItem

/** 详情页一次最多展示的播放源条数 */
internal const val MAX_VISIBLE_SOURCES = 20

/**
 * 一枚播放源 chip 的展示模型。
 *
 * [selected] 表示这条就是当前正在用的源：亮底 + 「· 当前」，点了不发请求。
 * 以前用一枚独立的黑底 Box 表示当前源，既占位置又不可点，还和候选列表
 * 里的同一条重复出现。
 */
internal data class SourceChipModel(
    val item: AltItem,
    val label: String,
    val selected: Boolean,
)

/**
 * 候选 chip 文案：集数已后台探测到就带上「全 N 集」。
 *
 * 换源前用户最想知道的就是「哪条更全」——以前要点进去才发现是 1 集试看线。
 * [AltItem.epCount] 为 -1 表示还没探测到，只显示源名，不写猜的数字。
 */
internal fun altLabel(a: AltItem): String =
    if (a.epCount > 0) "${a.sourceName} · 全${a.epCount}集" else a.sourceName

/**
 * 播放源 chip 切分：返回 (直接铺出的候选数, 折叠进「更多」的候选数)。
 *
 * 当前源本身就是列表里的一条，不再额外占位，所以候选可以铺满 [max] 个；
 * 候选不多于上限时全部铺出，隐藏数为 0（不出现「更多」）。
 */
internal fun splitSourceChips(
    totalAlternatives: Int,
    max: Int = MAX_VISIBLE_SOURCES,
): Pair<Int, Int> {
    val room = max.coerceAtLeast(0)
    val visible = totalAlternatives.coerceIn(0, room)
    return visible to (totalAlternatives - visible)
}

/**
 * 把候选源列表切成「直接展示的 chip」与「收进更多弹窗的数量」。
 *
 * 顺序沿用仓库给的 tier→speed 顺序，当前源**不置顶**：置顶会让每次换源后
 * chip 顺序整体跳一次，用户刚记住的位置就没了。当前源恰好排在前 [max] 个
 * 之外时，可见区一个都不选中——这也是真实状态，不该临时插入一条伪造顺序。
 */
internal fun sourceChips(
    alts: List<AltItem>,
    currentKey: String,
    max: Int = MAX_VISIBLE_SOURCES,
): Pair<List<SourceChipModel>, Int> {
    val (visibleCount, hidden) = splitSourceChips(alts.size, max)
    val chips = alts.take(visibleCount).map { a ->
        val isCurrent = a.sourceKey == currentKey
        SourceChipModel(
            item = a,
            label = altLabel(a) + if (isCurrent) " · 当前" else "",
            selected = isCurrent,
        )
    }
    return chips to hidden
}
