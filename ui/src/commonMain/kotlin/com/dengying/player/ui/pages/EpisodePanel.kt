package com.dengying.player.ui.pages

/**
 * 全屏选集抽屉的纯逻辑。与 Composable 分离，便于 commonTest 直接验证宽度夹取、
 * 排序映射和选中态，不需要启动播放器或平台窗口。
 */
internal data class EpisodeCell(
    val label: String,
    val realIndex: Int,
    val selected: Boolean,
)

/** 抽屉宽度按屏宽取 38%，夹在 280–360dp；无效尺寸退回 320dp。 */
internal fun episodeDrawerWidth(widthDp: Float): Float {
    if (!widthDp.isFinite() || widthDp <= 0f) return 320f
    return (widthDp * 0.38f).coerceIn(280f, 360f)
}

/**
 * 按显示顺序生成格子。realIndices 已经由详情页按 descOrder 排好，
 * 这里只负责补空名与计算选中态，避免抽屉里再复制一次排序逻辑。
 */
internal fun episodeCells(
    names: List<String>,
    realIndices: List<Int>,
    selectedReal: Int,
): List<EpisodeCell> = names.indices.map { displayIdx ->
    val real = realIndices.getOrNull(displayIdx) ?: displayIdx
    EpisodeCell(
        label = names[displayIdx].ifBlank { "第${real + 1}集" },
        realIndex = real,
        selected = real == selectedReal,
    )
}
