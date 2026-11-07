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

import com.eecccm.yunyy.core.error.VendorError;

/**
 * 账号登录状态档案。
 *
 * <p>语义分离：成功态 {@code active=true}，{@code nickname}=昵称、{@code note}=VIP 附加文本，
 * {@code errorCode} 为 null；失败态 {@code active=false}，{@code nickname}/{@code note} 留空，
 * 原因由 {@code errorCode} + {@code errorArgs} 表达，不再把错误句子塞进昵称字段。</p>
 *
 * @param active   凭证是否有效
 * @param nickname 账号昵称（失败态为空串）
 * @param note     VIP 等级附加文本（失败态为空串）
 * @param errorCode 失败错误码；成功态为 null
 * @param errorArgs 错误码结构化参数；成功态为空数组
 */
public record AccountProfile(boolean active, String nickname, String note,
                             VendorError errorCode, Object[] errorArgs) {

   public AccountProfile {
      if (nickname == null) {
         nickname = "";
      }
      if (note == null) {
         note = "";
      }
      if (errorArgs == null) {
         errorArgs = new Object[0];
      }
   }

   /** 成功档案：昵称 + VIP 附加文本。 */
   public static AccountProfile ok(String nickname, String note) {
      return new AccountProfile(true, nickname == null ? "" : nickname,
            note == null ? "" : note, null, new Object[0]);
   }

   /** 失败档案：携带错误码与结构化参数。 */
   public static AccountProfile fail(VendorError code, Object... args) {
      return new AccountProfile(false, "", "", code == null ? VendorError.UNKNOWN : code,
            args == null ? new Object[0] : args);
   }

   /** 复制档案但清空 VIP 附加文本（VIP 探测关闭时使用），保留错误码。 */
   public static AccountProfile copyWithoutNote(AccountProfile profile) {
      return new AccountProfile(profile.active(), profile.nickname(), "",
            profile.errorCode(), profile.errorArgs());
   }

   /** 是否携带可上屏映射的失败错误码。 */
   public boolean hasError() {
      return this.errorCode != null;
   }
}
