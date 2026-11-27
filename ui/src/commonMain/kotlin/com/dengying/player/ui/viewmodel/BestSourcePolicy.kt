package com.dengying.player.ui.viewmodel

/**
 * 自动换源口径（FR-635）：进详情页后**直接选中播放最完整的源**。
 *
 * ## 为什么单独成文件
 *
 * 判定涉及三方事实（当前源集数、当前源是否 playGap、各候选源集数）与两条
 * 取舍规则，塞进 `DetailViewModel` 就只能靠真机试。这里做成纯函数，
 * 让「什么时候该换、换到哪一条、同级怎么排」能被单测钉死。
 *
 * ## 规则
 *
 * 1. 候选必须**已探测到集数**（`epCount > 0`）：未探测的候选换过去可能只有 1 集，
 *    比当前源更差，不能作为自动目标。
 * 2. 当前源声明了 `playGap`（token 型源，客户端解不开地址）时，任何有集数的候选
 *    都比它强——即使集数更少。
 * 3. 否则只在候选**集数严格多于当前源**时才换：集数持平说明内容一样全，
 *    没有理由打断用户当前所在的源。
 * 4. 排序是「集数降序，同集数保持候选顺序」。候选顺序来自 `findAltSources`
 *    的源速排序，所以同集数时先换更快的源。
 */
internal data class SourceFacts(val key: String, val epCount: Int)

/**
 * 返回按优先级排好的换源目标 key 列表；空列表 = 保持当前源。
 *
 * 调用方拿到列表后还要做「首集真的能解析出地址」的校验（候选集数多不等于能播），
 * 所以这里给的是**候选序列**而不是单个结果。
 */
internal fun rankedSwitchTargets(
    currentKey: String,
    currentEpCount: Int,
    currentHasGap: Boolean,
    candidates: List<SourceFacts>,
): List<String> {
    // 稳定排序：sortedByDescending 在 Kotlin/JVM 上是稳定排序，同集数保留候选原序
    val usable = candidates
        .filter { it.key != currentKey && it.epCount > 0 }
        .sortedByDescending { it.epCount }
    if (usable.isEmpty()) return emptyList()
    if (currentHasGap) return usable.map { it.key }
    return usable.filter { it.epCount > currentEpCount }.map { it.key }
}

/**
 * playGap 源的兜底目标：集数还没探测到的候选。
 *
 * 【为什么需要】声明了 `playGap` 的源（如「蓝光4k」的加密 token）对本片必然
 * 播不了，此时「集数未知」也比「确定不能播」强。真机上出现过这一类死局：
 * 候选详情全部拉取超时（`alt-prefetch` 一条都没有），`rankedSwitchTargets`
 * 因为没有 `epCount > 0` 的候选返回空，自动选源静默失效，页面锁在不能播的
 * 源上。兜底只按原序（源速）给出未探测候选，仍然要过「首集能解析」校验。
 */
internal fun unprobedGapFallback(
    currentKey: String,
    candidates: List<SourceFacts>,
): List<String> = candidates
    .filter { it.key != currentKey && it.epCount <= 0 }
    .map { it.key }

/**
 * 早选之后是否还值得再跳一次源（FR-635）。
 *
 * 早选发生在候选刚上屏、多数源还没探测完的时候，选到次优是常态。允许**一次**
 * 自动升级把用户带到更完整的源，但必须满足四个条件，否则宁可不打扰：
 *
 * - 用户还没起播（`played = false`）：已经看上了就不该换台；
 * - 用户没手动选过源：手动选择优先于任何自动判定；
 * - 已经升级过一次就不再升：避免在多个「差不多全」的源之间来回跳；
 * - 集数至少是当前的 1.5 倍才算「明显更优」：多一两集不值得整页重载。
 */
internal fun shouldUpgradeAutoPick(
    fromEps: Int,
    toEps: Int,
    played: Boolean,
    userPicked: Boolean,
    upgrades: Int,
): Boolean {
    if (played || userPicked) return false
    if (upgrades >= 1) return false
    if (fromEps <= 0 || toEps <= 0) return false
    return toEps * 2 >= fromEps * 3
}
