package com.dengying.player.ui.layout

/**
 * 大屏自适应布局的纯规则。抽成普通 Kotlin，是为了在 JVM 单测里覆盖边界，
 * Compose 侧只读结果、不在这里依赖任何平台 API。
 *
 * 为什么不用 `WindowSizeClass`：当前 CPF 毕昇线（CMP 1.9.2-1.2.0）没有
 * `material3-window-size-class`，`material3-adaptive` / `androidx.window` 也不在
 * 类路径上。新增依赖会同时影响三端，而这里只需要按窗口宽度分档。
 *
 * 为什么 rail / 双栏要同时看短边：横屏手机的宽度也能过 840dp
 * （例如 915×412dp），只按宽度分档会把手机当平板。
 */
internal enum class WidthClass { Compact, Medium, Expanded, Wide }

/**
 * @property widthDp 当前窗口可用宽度（dp）。
 * @property heightDp 当前窗口可用高度（dp）。
 * @property isLargeScreen 大屏形态：宽度 ≥ 840dp 且短边 ≥ 600dp。rail 与详情双栏只认它。
 * @property gridColumns 推荐页网格列数。
 * @property feedColumns 首页热点/信息流的每行卡片数。
 * @property contentSideDp 1280dp 内容上限之外、每侧需要追加的留白。
 * @property feedSideDp 900dp 内容上限之外、每侧需要追加的留白（Hero、发现页大卡）。
 * @property railWidthDp 侧栏占位宽度；0 表示仍用底部 dock。
 * @property minTouchDp 大屏主要可点元素的最小热区。
 * @property ctrlPrimaryDp 全屏主控键（暂停/全屏）边长。
 * @property ctrlSecondaryDp 全屏次控键边长。
 */
internal data class AdaptiveSpec(
    val widthDp: Float,
    val heightDp: Float,
    val widthClass: WidthClass,
    val isLargeScreen: Boolean,
    val gridColumns: Int,
    val feedColumns: Int,
    val contentSideDp: Float,
    val feedSideDp: Float,
    val railWidthDp: Float,
    val minTouchDp: Float,
    val ctrlPrimaryDp: Float,
    val ctrlSecondaryDp: Float,
) {
    val isCompact: Boolean get() = widthClass == WidthClass.Compact

    /** 是否走侧栏导航。与 [isLargeScreen] 同义，单独命名便于调用点表达意图。 */
    val showsRail: Boolean get() = isLargeScreen

    companion object {
        const val BREAKPOINT_MEDIUM = 600f
        const val BREAKPOINT_EXPANDED = 840f
        const val BREAKPOINT_WIDE = 1200f
        const val LARGE_MIN_SIDE_DP = 600f
        const val MAX_CONTENT_DP = 1280f
        const val MAX_FEED_DP = 900f
        const val RAIL_WIDTH_DP = 88f

        /** 手机（compact）的冻结值。改这里等于改手机布局，必须同步截图对比。 */
        const val COMPACT_GRID_COLUMNS = 3
        const val COMPACT_FEED_COLUMNS = 2
        const val COMPACT_DETAIL_COLUMNS = 4
        const val POSTER_MIN_DP = 140f
        const val POSTER_MAX_DP = 240f
        const val EPISODE_TARGET_DP = 96f

        /** 上游给不出有效尺寸时，一律按手机处理，保证不会出现“零列网格”。 */
        fun compact(widthDp: Float = 360f, heightDp: Float = 800f): AdaptiveSpec = AdaptiveSpec(
            widthDp = widthDp,
            heightDp = heightDp,
            widthClass = WidthClass.Compact,
            isLargeScreen = false,
            gridColumns = COMPACT_GRID_COLUMNS,
            feedColumns = COMPACT_FEED_COLUMNS,
            contentSideDp = 0f,
            feedSideDp = 0f,
            railWidthDp = 0f,
            minTouchDp = 44f,
            ctrlPrimaryDp = 46f,
            ctrlSecondaryDp = 40f,
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
            return AdaptiveSpec(
                widthDp = widthDp,
                heightDp = heightDp,
                widthClass = widthClass,
                isLargeScreen = large,
                gridColumns = grid,
                feedColumns = feed,
                contentSideDp = extraSide(widthDp, MAX_CONTENT_DP),
                feedSideDp = extraSide(widthDp, MAX_FEED_DP),
                railWidthDp = if (large) RAIL_WIDTH_DP else 0f,
                minTouchDp = if (large) 48f else 44f,
                ctrlPrimaryDp = if (large) 56f else 46f,
                ctrlSecondaryDp = if (large) 48f else 40f,
            )
        }

        /** 内容宽度上限之外每侧需要追加的留白；手机/未超上限时为 0。 */
        fun extraSide(widthDp: Float, maxContentDp: Float): Float {
            if (!widthDp.isFinite() || widthDp <= maxContentDp) return 0f
            return (widthDp - maxContentDp) / 2f
        }

        /** 页面实际使用的左右内边距 = 基础 12dp + 居中留白。 */
        fun sidePadding(widthDp: Float, maxContentDp: Float, baseDp: Float = 12f): Float =
            baseDp + extraSide(widthDp, maxContentDp)

        /**
         * 网格海报高：按卡片宽度取 1.30 倍（竖版比例），并夹在 140–240dp。
         * 手机卡宽约 105dp → 136.5 被夹到 140，与改前固定 `height(140.dp)` 一致。
         */
        fun posterHeight(cardWidthDp: Float): Float {
            if (!cardWidthDp.isFinite() || cardWidthDp <= 0f) return POSTER_MIN_DP
            return (cardWidthDp * 1.30f).coerceIn(POSTER_MIN_DP, POSTER_MAX_DP)
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
