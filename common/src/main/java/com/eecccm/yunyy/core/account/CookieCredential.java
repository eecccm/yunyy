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

package com.eecccm.yunyy.core.account;

/**
 * 网易云 Cookie 凭证规范化：去掉 "Cookie:" 前缀，裸值补上 MUSIC_U= 前缀。
 */
public final class CookieCredential {

   private CookieCredential() {
   }

   /**
    * 把用户粘贴的任意形式 Cookie 规范为可直接存证的形式。
    * @return 规范化后的凭证串；输入为空返回空串
    */
   public static String normalize(String raw) {
      if (raw == null) {
         return "";
      }
      String value = raw.trim();
      if (value.regionMatches(true, 0, "Cookie:", 0, 7)) {
         value = value.substring(7).trim();
      }
      // 形如纯 token 的输入补 MUSIC_U= 前缀；已带键值对则原样保留
      if (!value.isEmpty() && value.matches("[A-Za-z0-9_+/%.-]+={0,2}")) {
         return "MUSIC_U=" + value;
      }
      return value;
   }
}
