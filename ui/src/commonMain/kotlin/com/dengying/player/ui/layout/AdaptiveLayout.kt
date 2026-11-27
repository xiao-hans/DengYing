package com.dengying.player.ui.layout

/**
 * 大屏自适应布局的纯规则。抽成普通 Kotlin，是为了在 JVM 单测里覆盖边界，
 * Compose 侧只读结果、不在这里依赖任何平台 API。
 *
 * 为什么不用 `WindowSizeClass`：当前 CPF 毕昇线（CMP 1.9.2-1.2.0）没有
 * `material3-window-size-class`，`material3-adaptive` / `androidx.window` 也不在
 * 类路径上。新增依赖会同时影响三端，而这里只需要按窗口尺寸分档。
 *
 * 分档只看窗口宽度，横屏与竖屏共用同一套响应式布局；竖屏手机额外做两处
 * 收口，不改横屏已经调好的间距（见 `feedSidePaddingDp`）。
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
 * @property feedSidePaddingDp 首页内容区的左右内边距。窄屏为 0，让首页沿用
 *   各区块自带的 14/18dp 内边距；宽屏再叠加窗口留白。
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
    val feedSidePaddingDp: Float,
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
        /** 竖屏手机宽度上限；超过它按平板处理，正常叠加窗口留白。 */
        const val FEED_INSET_MIN_WIDTH_DP = 720f
        /** 全屏/内嵌控件按短边判定平板形态，短边不到 720dp 一律按手机给尺寸。 */
        const val CONTROL_LARGE_SIDE_DP = 720f
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
            feedSidePaddingDp = 0f,
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
            val shortSideDp = minOf(widthDp, heightDp)
            val large = widthDp >= BREAKPOINT_EXPANDED && shortSideDp >= LARGE_MIN_SIDE_DP
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
            // 竖屏手机保持首页自带内边距；横屏（heightDp < widthDp）沿用宽度分档。
            val portraitPhone = heightDp > widthDp && widthDp < FEED_INSET_MIN_WIDTH_DP
            val feedSidePaddingDp = if (portraitPhone) 0f else sidePaddingDp
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
            // 控件尺寸改看短边：横屏手机宽度能过 840dp，只看宽度会把按键从 44dp 放大到 56dp。
            val largeControls = shortSideDp >= CONTROL_LARGE_SIDE_DP
            val embeddedControlDp = if (largeControls) 44f else 34f
            val fullscreenPrimaryDp = if (largeControls) 56f else 44f
            val fullscreenSecondaryDp = if (largeControls) 48f else 40f
            return AdaptiveSpec(
                widthDp = widthDp,
                heightDp = heightDp,
                widthClass = widthClass,
                isLargeScreen = large,
                gridColumns = grid,
                feedColumns = feed,
                contentMaxDp = contentMaxDp,
                sidePaddingDp = sidePaddingDp,
                feedSidePaddingDp = feedSidePaddingDp,
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
