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

package com.eecccm.yunyy.core.error;

import java.util.Locale;

/**
 * 中性平台错误码。core 层只抛出 / 返回本枚举标识，不携带面向玩家的句子；
 * 玩家可见文案由 mccore 显示层按 {@link #key()} 映射语言模板。
 *
 * <p>参数槽约定（与 mccore 错误映射工具对齐）：</p>
 * <ul>
 *   <li>{@link #NOT_LOGGED_IN}：槽 1 = 平台展示名。</li>
 *   <li>{@link #HTTP_STATUS}：槽 1 = 平台展示名，槽 2 = HTTP 状态码。</li>
 *   <li>{@link #NO_STREAM_PERMISSION}：槽 1 = 平台原始 code（仅写日志，不上屏）。</li>
 *   <li>{@link #SEARCH_FAILED} / {@link #PLAYLIST_LOAD_FAILED} / {@link #PLAYLIST_TRACKS_FAILED}：槽 1 = 平台展示名。</li>
 *   <li>{@link #QR_KEY_MISSING} / {@link #QR_BAD_PAYLOAD}：槽 1 = 平台展示名。</li>
 *   <li>{@link #CREDENTIAL_INVALID}：槽 1 = 平台展示名。</li>
 *   <li>其余错误码无参数槽。</li>
 * </ul>
 */
public enum VendorError {
   NOT_LOGGED_IN,
   COOKIE_MISSING_FIELD,
   COOKIE_INVALID_EXPIRED,
   HTTP_STATUS,
   NETWORK_FAILURE,
   NO_STREAM_PERMISSION,
   TRIAL_ONLY,
   STREAM_RESOLVE_FAILED,
   QUERY_EMPTY,
   QUERY_TOO_LONG,
   SEARCH_FAILED,
   PLAYLIST_ID_INVALID,
   PLAYLIST_FORBIDDEN,
   PLAYLIST_LOAD_FAILED,
   PLAYLIST_TRACKS_FAILED,
   QR_KEY_MISSING,
   QR_SESSION_MISSING,
   QR_BAD_PAYLOAD,
   QR_CAPTCHA_REQUIRED,
   TOKEN_MISSING,
   CREDENTIAL_INVALID,
   /** 平台风控拦截（T-02）：识别到风控 / 封禁类状态码后停止自动重试，界面中性提示。槽 1 = 平台展示名，槽 2 = 平台原始风控码（仅写日志，不上屏）。 */
   RISK_CONTROLLED,
   /** 仅免费 / 试听模式（T-02）：当前开关下拒绝携带凭证请求权益资源。 */
   FREE_MODE_ONLY,
   /** VIP 状态无法验证（T-01B）：设备指纹不可信时不伪造通过，界面明示"VIP 状态无法验证"。 */
   VIP_UNVERIFIABLE,
   UNKNOWN;

   /** 对应语言模板键后缀，如 {@code yunyy.error.http_status}。 */
   public String key() {
      return "yunyy.error." + this.name().toLowerCase(Locale.ROOT);
   }
}
