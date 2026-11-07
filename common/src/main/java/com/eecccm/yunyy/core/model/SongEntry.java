/*
 * 云音乐（yunyy）— Minecraft 模组
 * Copyright (C) 2026 eecccm
 * 本文件由 eecccm 与 AI 协作完成，部分内容经由 AI 辅助生成。
 *
 * 本程序是自由软件：你可以根据自由软件基金会发布的 GNU 通用公共许可证
 * （版本 3，或（按你的选择）任何更高版本）的条款重新分发和/或修改它。
 *
 * 本程序分发的目的是希望它有用，但没有任何担保；甚至没有适销性或
 * 特定用途适用性的隐含担保。详见 GNU 通用公共许可证。
 *
 * 你应当已经收到一份 GNU 通用公共许可证的副本；如果没有，请见
 * <https://www.gnu.org/licenses/>。
 *
 * 附加许可（依据 GPL-3.0 第 7 条）：本模组适用“链接例外”，允许本模组与
 * Minecraft 及 Forge / Fabric / NeoForge 等运行时链接，而无需使这些运行时
 * 或任何组合作品整体受到 GPL 约束。GPL-3.0 仅约束本模组自身代码。
 */

package com.eecccm.yunyy.core.model;

import com.eecccm.yunyy.core.platform.MusicVendor;

/**
 * 跨服务商的统一歌曲条目（由平台网关与播放引擎共用）。
 *
 * @param vendor     来源平台
 * @param songId     平台内歌曲唯一标识
 * @param name       歌曲名
 * @param performer  表演者（歌手）
 * @param lengthMs   时长（毫秒，0=未知）
 * @param coverUri   封面图地址（可能为空）
 * @param addedAtMs  加入歌单的时间（毫秒，0=未知；歌单排序按该值从新到旧）
 */
public record SongEntry(MusicVendor vendor, String songId, String name, String performer,
      long lengthMs, String coverUri, long addedAtMs) {
}
