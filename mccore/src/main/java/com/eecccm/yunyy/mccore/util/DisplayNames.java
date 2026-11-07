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

package com.eecccm.yunyy.mccore.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 面向玩家显示的歌曲元数据兜底。
 * core / server 只保存原始空串表示"无歌手"，本地化文案统一在 mccore 显示层按当前界面语言渲染，
 * 避免纯 JDK 核心层携带面向玩家的中文文案、英文界面出现中文兜底。
 */
public final class DisplayNames {
   private DisplayNames() {
   }

   /** 空歌手返回"未知歌手"本地化组件（可嵌入服务端 translatable 模板，由客户端按自身语言解析）；否则返回字面组件。 */
   public static MutableComponent artistComponent(String artist) {
      if (artist == null || artist.isBlank()) {
         return Component.translatable("yunyy.ui.unknown_artist");
      }
      return Component.literal(artist.trim());
   }

   /** 纯文本兜底：空歌手返回当前界面语言的"未知歌手"字符串；否则返回去首尾空白后的原始文本。仅供客户端界面拼接使用。 */
   public static String artist(String artist) {
      if (artist == null || artist.isBlank()) {
         return Component.translatable("yunyy.ui.unknown_artist").getString();
      }
      return artist.trim();
   }

   /** 空昵称返回"已登录账号"本地化组件；否则返回字面组件。 */
   public static MutableComponent accountComponent(String nickname) {
      if (nickname == null || nickname.isBlank()) {
         return Component.translatable("yunyy.ui.unknown_account");
      }
      return Component.literal(nickname.trim());
   }

   /** 纯文本昵称兜底：空昵称返回当前界面语言的"已登录账号"；否则返回去首尾空白后的原始文本。 */
   public static String account(String nickname) {
      if (nickname == null || nickname.isBlank()) {
         return Component.translatable("yunyy.ui.unknown_account").getString();
      }
      return nickname.trim();
   }

   /**
    * VIP 后缀组件（含前置分隔符与 "VIP " 前缀，均由语言模板组织）。
    * probe 关闭 → 空组件；probe 开启且 note 为空 → "VIP 未知"；note 非空 → "，VIP <note>"。
    * note 仅承载中性标识 "0" / "1" / ""，不携带中文文案。
    */
   public static MutableComponent vipSuffixComponent(String note, boolean probeEnabled) {
      if (!probeEnabled) {
         return Component.empty();
      }
      if (note == null || note.isBlank()) {
         return Component.translatable("yunyy.ui.vip_sep_unknown");
      }
      return Component.translatable("yunyy.ui.vip_sep_value", note.trim());
   }

   /** 歌单文件夹名兜底：收藏歌单（folderId 以 collection_3_ 开头）回退"我喜欢的音乐"，其余回退"歌单"；非空返回字面组件。 */
   public static MutableComponent playlistFolderComponent(String folderId, String folderName) {
      if (folderName == null || folderName.isBlank()) {
         boolean favorite = folderId != null && folderId.startsWith("collection_3_");
         return Component.translatable(favorite ? "yunyy.ui.playlist_favorite" : "yunyy.ui.playlist_unknown");
      }
      return Component.literal(folderName.trim());
   }

   /** 歌单内容页标题兜底：空名回退"歌单"；非空返回字面组件。 */
   public static MutableComponent playlistTitleComponent(String name) {
      if (name == null || name.isBlank()) {
         return Component.translatable("yunyy.ui.playlist_unknown");
      }
      return Component.literal(name.trim());
   }

   /** 歌单文件夹名纯文本兜底：收藏歌单回退"我喜欢的音乐"，其余回退"歌单"；非空返回去空白文本。 */
   public static String playlistFolder(String folderId, String folderName) {
      if (folderName == null || folderName.isBlank()) {
         boolean favorite = folderId != null && folderId.startsWith("collection_3_");
         return Component.translatable(favorite ? "yunyy.ui.playlist_favorite" : "yunyy.ui.playlist_unknown").getString();
      }
      return folderName.trim();
   }

   /** 歌单内容页标题纯文本兜底：空名回退"歌单"；非空返回去空白文本。 */
   public static String playlistTitle(String name) {
      if (name == null || name.isBlank()) {
         return Component.translatable("yunyy.ui.playlist_unknown").getString();
      }
      return name.trim();
   }
}
