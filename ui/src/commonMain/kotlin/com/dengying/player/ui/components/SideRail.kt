package com.dengying.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dengying.player.ui.theme.Glass
import com.dengying.player.ui.theme.Palette

/**
 * 大屏左侧导航 rail（宽屏专用，手机仍用 [BottomDock]）。
 *
 * 【为什么不用 Material3 NavigationRail】它的 80dp 宽度、indicator 与自带 inset
 * 语义跟本工程的玻璃体系对不上；这里沿用 [BottomDock] 的玻璃面板与高光语言，
 * 只把排布从横排换成竖排。
 *
 * 【为什么不跟 chrome 一起隐藏】底部 dock 不隐藏会挡住内容底边，侧栏不挡内容；
 * 大屏常驻可预期，避免用户下滑看书时导航消失。
 *
 * 【避让】顶部只用 statusBars；底部沿用 [BottomDock] 的 56dp 钳制，不用
 * `navigationBarsPadding()` —— ohos compose-arkui 可能回报异常大的 nav inset。
 */
@Composable
fun SideRail(
    items: List<DockSpec>,
    selectedKey: String,
    onSelect: (DockSpec) -> Unit,
    modifier: Modifier = Modifier,
    /** 侧栏为内容让位的总宽度，与 `AdaptiveSpec.railWidthDp` 保持一致。 */
    reservedWidth: Dp = 88.dp,
) {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
        .let { with(density) { it.toDp() } }
        .coerceAtMost(56.dp)
    val panelWidth = reservedWidth - 12.dp
    GlassSurface(
        modifier = modifier
            .padding(start = 6.dp, top = 8.dp, bottom = 8.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = navBottom)
            .width(panelWidth)
            .fillMaxHeight(),
        shape = RoundedCornerShape(28.dp),
        elevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val shape = RoundedCornerShape(20.dp)
                val interaction = remember(item.key) { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 56dp ≥ 大屏 48dp 触控下限；图标 + 文字的竖排本身也需要这个高度。
                        .heightIn(min = 56.dp)
                        .clip(shape)
                        .background(if (selected) Glass.DockHighlight else Color.Transparent)
                        .then(if (selected) Modifier.border(1.dp, Glass.Border, shape) else Modifier)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(item) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    DyIcon(
                        icon = item.icon,
                        tint = if (selected) Palette.TextPrimary else Palette.TextSecondary,
                        size = 22.dp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Palette.TextPrimary else Palette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
