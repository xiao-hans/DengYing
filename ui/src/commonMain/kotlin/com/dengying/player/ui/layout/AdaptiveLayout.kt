package com.dengying.player.ui.layout

/**
 * 大屏自适应布局的纯规则。抽成普通 Kotlin，是为了在 JVM 单测里覆盖边界，
 * Compose 侧只读结果、不在这里依赖任何平台 API。
 *
 * 为什么不用 `WindowSizeClass`：当前 CPF 毕昇线（CMP 1.9.2-1.2.0）没有
 * `material3-window-size-class`，`material3-adaptive` / `androidx.window` 也不在
 * 类路径上。新增依赖会同时影响三端，而这里只需要按窗口宽度分档。
 *
 * 为什么详情双栏要同时看短边：横屏手机的宽度也能过 840dp
 * （例如 915×412dp），只按宽度分档会把手机当平板。
 */
internal enum class WidthClass { Compact, Medium, Expanded, Wide }

/**
 * @property widthDp 当前窗口可用宽度（dp）。
 * @property heightDp 当前窗口可用高度（dp）。
 * @property isLargeScreen 大屏形态：宽度 ≥ 840dp 且短边 ≥ 600dp。详情双栏只认它。
 * @property gridColumns 聚合分类网格列数。
 * @property feedColumns 首页热点的每行卡片数。
 * @property contentMaxDp 普通内容限宽；无穷大表示不限制。
 * @property sidePaddingDp 普通内容的左右基础内边距。
 * @property heroMaxDp 首页 Hero 限宽；无穷大表示不限制。
 * @property railCardDp 首页横滑 rail 卡宽度。
 * @property embeddedControlDp 内嵌播放器控制键边长。
 * @property fullscreenPrimaryDp 全屏主控键边长。
 * @property fullscreenSecondaryDp 全屏次控键边长。
 * @property detailTwoPane 详情页是否使用双栏。
 */
internal data class AdaptiveSpec(
    val widthDp: Float,
    val heightDp: Float,
    val widthClass: WidthClass,
    val isLargeScreen: Boolean,
    val gridColumns: Int,
    val feedColumns: Int,
    val contentMaxDp: Float,
    val sidePaddingDp: Float,
    val heroMaxDp: Float,
    val railCardDp: Float,
    val embeddedControlDp: Float,
    val fullscreenPrimaryDp: Float,
    val fullscreenSecondaryDp: Float,
    val detailTwoPane: Boolean,
) {
    val isCompact: Boolean get() = widthClass == WidthClass.Compact

    companion object {
        const val BREAKPOINT_MEDIUM = 600f
        const val BREAKPOINT_EXPANDED = 840f
        const val BREAKPOINT_WIDE = 1200f
        const val LARGE_MIN_SIDE_DP = 600f
        const val MAX_FULLSCREEN_DP = 1280f
        const val DETAIL_TWO_PANE_MAX_DP = 1280f
        const val DETAIL_OUTER_MARGIN_DP = 24f
        const val DETAIL_GAP_DP = 20f
        const val DETAIL_LEFT_WEIGHT = 0.55f
        const val DETAIL_RIGHT_WEIGHT = 0.45f
        const val DETAIL_PLAYER_MAX_HEIGHT_DP = 460f

        /** 手机（compact）的冻结值。改这里等于改手机布局，必须同步截图对比。 */
        const val COMPACT_GRID_COLUMNS = 3
        const val COMPACT_FEED_COLUMNS = 2
        const val COMPACT_DETAIL_COLUMNS = 4
        const val POSTER_MIN_DP = 140f
        const val POSTER_MAX_DP = 220f
        const val EPISODE_TARGET_DP = 96f

        /** 上游给不出有效尺寸时，一律按手机处理，保证不会出现“零列网格”。 */
        fun compact(widthDp: Float = 360f, heightDp: Float = 800f): AdaptiveSpec = AdaptiveSpec(
            widthDp = widthDp,
            heightDp = heightDp,
            widthClass = WidthClass.Compact,
            isLargeScreen = false,
            gridColumns = COMPACT_GRID_COLUMNS,
            feedColumns = COMPACT_FEED_COLUMNS,
            contentMaxDp = Float.POSITIVE_INFINITY,
            sidePaddingDp = 12f,
            heroMaxDp = Float.POSITIVE_INFINITY,
            railCardDp = 112f,
            embeddedControlDp = 34f,
            fullscreenPrimaryDp = 44f,
            fullscreenSecondaryDp = 40f,
            detailTwoPane = false,
        )

        fun from(widthDp: Float, heightDp: Float): AdaptiveSpec {
            if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) {
                return compact()
            }
            val widthClass = when {
                widthDp >= BREAKPOINT_WIDE -> WidthClass.Wide
                widthDp >= BREAKPOINT_EXPANDED -> WidthClass.Expanded
                widthDp >= BREAKPOINT_MEDIUM -> WidthClass.Medium
                else -> WidthClass.Compact
            }
            val large = widthDp >= BREAKPOINT_EXPANDED &&
                minOf(widthDp, heightDp) >= LARGE_MIN_SIDE_DP
            val grid = when (widthClass) {
                WidthClass.Compact -> COMPACT_GRID_COLUMNS
                WidthClass.Medium -> 4
                WidthClass.Expanded -> 5
                WidthClass.Wide -> 6
            }
            val feed = when (widthClass) {
                WidthClass.Compact -> COMPACT_FEED_COLUMNS
                WidthClass.Medium -> 3
                WidthClass.Expanded, WidthClass.Wide -> 4
            }
            val contentMaxDp = when (widthClass) {
                WidthClass.Compact -> Float.POSITIVE_INFINITY
                WidthClass.Medium -> 720f
                WidthClass.Expanded -> 1120f
                WidthClass.Wide -> 1280f
            }
            val sidePaddingDp = when (widthClass) {
                WidthClass.Compact -> 12f
                WidthClass.Medium -> 24f
                WidthClass.Expanded, WidthClass.Wide -> 32f
            }
            val heroMaxDp = when (widthClass) {
                WidthClass.Compact -> Float.POSITIVE_INFINITY
                WidthClass.Medium -> 720f
                WidthClass.Expanded, WidthClass.Wide -> 960f
            }
            val railCardDp = when (widthClass) {
                WidthClass.Compact -> 112f
                WidthClass.Medium -> 128f
                WidthClass.Expanded -> 144f
                WidthClass.Wide -> 160f
            }
            val embeddedControlDp = if (widthClass == WidthClass.Compact) 34f else 44f
            val fullscreenPrimaryDp = if (widthClass == WidthClass.Compact) 44f else 56f
            val fullscreenSecondaryDp = if (widthClass == WidthClass.Compact) 40f else 48f
            return AdaptiveSpec(
                widthDp = widthDp,
                heightDp = heightDp,
                widthClass = widthClass,
                isLargeScreen = large,
                gridColumns = grid,
                feedColumns = feed,
                contentMaxDp = contentMaxDp,
                sidePaddingDp = sidePaddingDp,
                heroMaxDp = heroMaxDp,
                railCardDp = railCardDp,
                embeddedControlDp = embeddedControlDp,
                fullscreenPrimaryDp = fullscreenPrimaryDp,
                fullscreenSecondaryDp = fullscreenSecondaryDp,
                detailTwoPane = large,
            )
        }

        /**
         * 网格海报高：按卡片宽度取 1.40 倍（竖版比例），并夹在 140–220dp。
         * 手机卡宽约 105dp → 147，需与真机截图对比后确认是否保留原观感。
         */
        fun posterHeight(cardWidthDp: Float): Float {
            if (!cardWidthDp.isFinite() || cardWidthDp <= 0f) return POSTER_MIN_DP
            return (cardWidthDp * 1.40f).coerceIn(POSTER_MIN_DP, POSTER_MAX_DP)
        }

        /**
         * 剧集每行格数：按容器宽度和 96dp 目标格宽算，夹在 4–8。
         * 手机单列容器沿用 4；平板双栏右栏约半宽时也不会挤出 8 列。
         */
        fun episodeColumns(paneWidthDp: Float): Int {
            if (!paneWidthDp.isFinite() || paneWidthDp <= 0f) return COMPACT_DETAIL_COLUMNS
            return (paneWidthDp / EPISODE_TARGET_DP).toInt().coerceIn(COMPACT_DETAIL_COLUMNS, 8)
        }
    }
}
