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

package com.eecccm.yunyy.core.platform;

/**
 * 支持的云音乐服务商枚举。
 */
public enum MusicVendor {

   NETEASE("netease", "网易云音乐"),
   KUGOU("kugou", "酷狗音乐/酷狗音乐概念版"),
   QQ("qq", "QQ音乐");

   /** 命令前缀与配置键使用的短标识（wyy / kg / qq）。 */
   public final String tag;

   /** 面向用户的展示名。 */
   public final String label;

   MusicVendor(String tag, String label) {
      this.tag = tag;
      this.label = label;
   }

   /**
    * 按短标识或命令前缀解析；未知或空回退到网易云。
    *
    * <p>指令里写作 {@code /music wyy|kg|qq}，会话与配置键里用的是 {@code netease / kugou / qq}
    * 两个体系，历史上只认后者：{@code /music kg} 会把玩家带到网易云通道上去搜索。
    * 前缀一并识别，指令与界面两条路径从源头对齐。</p>
    */
   public static MusicVendor byTag(String tag) {
      if (tag != null) {
         if (tag.equalsIgnoreCase("kugou") || tag.equalsIgnoreCase("kg")) {
            return KUGOU;
         }
         if (tag.equalsIgnoreCase("qq")) {
            return QQ;
         }
      }
      return NETEASE;
   }
}
