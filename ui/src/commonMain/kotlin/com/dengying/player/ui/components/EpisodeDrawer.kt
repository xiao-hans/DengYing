package com.dengying.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dengying.player.ui.pages.EpisodeCell
import com.dengying.player.ui.theme.Glass
import com.dengying.player.ui.theme.Palette

/**
 * 全屏选集右侧抽屉：遮罩消费点击，面板只显示当前线路。
 *
 * 网格用普通 Row/Box + 外层 verticalScroll：详情页的 LazyVerticalGrid 嵌在
 * verticalScroll 里曾导致每项点不动，抽屉沿用已经验证过的手动分行规则。
 */
@Composable
internal fun EpisodeDrawer(
    visible: Boolean,
    cells: List<EpisodeCell>,
    columns: Int,
    widthDp: Float,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val selectedRow = remember(cells, columns) {
        val selected = cells.indexOfFirst { it.selected }
        if (selected < 0 || columns <= 0) 0 else selected / columns
    }
    val rowOffset: Dp = with(density) { (56.dp + 8.dp) * selectedRow }
    val targetScrollPx = with(density) { (rowOffset - 8.dp - 12.dp).toPx().toInt() }
    LaunchedEffect(visible, targetScrollPx, cells.size) {
        if (visible && selectedRow > 0) scrollState.animateScrollTo(targetScrollPx.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable { onDismiss() },
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(widthDp.dp),
            enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(120)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(120)),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    bottomStart = 20.dp,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp,
                ),
                fill = Glass.FillStrong,
                elevation = 12.dp,
                liquid = false,
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "选集 · 共 ${cells.size} 集",
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onDismiss() }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            DyIcon(
                                icon = DyIcons.Close,
                                tint = Palette.TextPrimary,
                                size = 18.dp,
                                contentDescription = "关闭选集",
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val perRow = columns.coerceAtLeast(1)
                        val rows = (cells.size + perRow - 1) / perRow
                        for (row in 0 until rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (column in 0 until perRow) {
                                    val index = row * perRow + column
                                    val cell = cells.getOrNull(index)
                                    if (cell == null) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                // 选中底是 Palette.Brand；其文字色在下方与它成对读取 Palette.OnBrand
                                                .background(
                                                    if (cell.selected) Palette.Brand else Palette.SearchBg,
                                                    RoundedCornerShape(6.dp),
                                                )
                                                .clickable { onPick(cell.realIndex) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = cell.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (cell.selected) Palette.OnBrand else Palette.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
