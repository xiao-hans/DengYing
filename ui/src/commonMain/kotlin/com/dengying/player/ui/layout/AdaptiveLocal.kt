package com.dengying.player.ui.layout

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 整个应用只读一次窗口尺寸（`NavGraph` 根部的 `BoxWithConstraints`），
 * 由这里下发给页面。页面不要各自再包 `BoxWithConstraints`：重复 subcompose，
 * 而且多处以不同层级测到的尺寸会互相打架。
 *
 * 默认值取手机尺寸，保证预览与未提供时行为等于改前。
 */
internal val LocalAdaptiveSpec = staticCompositionLocalOf { AdaptiveSpec.compact() }
