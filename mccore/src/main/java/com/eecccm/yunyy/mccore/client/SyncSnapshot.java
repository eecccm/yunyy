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

package com.eecccm.yunyy.mccore.client;
import com.eecccm.yunyy.mccore.net.ListeningActionPacket;
import com.eecccm.yunyy.mccore.net.ListeningStatePacket;

import com.eecccm.yunyy.mccore.net.ListeningActionPacket;
import com.eecccm.yunyy.mccore.net.ListeningStatePacket;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

public final class SyncSnapshot {
   public static final int PUBLIC = 0;
   public static final int PERSONAL = 1;
   private static volatile int mode = 1;

   private SyncSnapshot() {
   }

   public static void connected() {
      if (!ClientNet.get().supportsListeningModes()) {
         mode = 1;
         SoloPlayback.activate();
         ModeSelectScreen.refresh();
      } else if (mode == 1) {
         // 支持收听模式的服务器：确保个人播放已激活（幂等），否则只入队不播放
         SoloPlayback.activate();
         ModeSelectScreen.refresh();
      }
   }

   public static void select(int requested) {
      if (ClientNet.get().supportsListeningModes()) {
         ClientNet.get().send(new ListeningActionPacket(requested, ""));
      } else if (requested == 1 && mode != 1) {
         PlaybackEngine.stop();
         mode = 1;
         SoloPlayback.activate();
         ModeSelectScreen.refresh();
      }
   }

   public static void update(ListeningStatePacket payload) {
      int previous = mode;

      try {
         JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
         int received = root.has("mode") ? root.get("mode").getAsInt() : 0;
         mode = received == 1 ? 1 : 0;
      } catch (Exception var4) {
         reset();
      }

      if (previous != 1 && mode != 1) {
         SoloPlayback.deactivateAfterServerStop();
      }

      if (mode == 1) {
         // 无论 previous 是否为 1，收到个人播放模式都确保激活（幂等），修复"只入队不播放"
         SoloPlayback.activate();
      }

      ModeSelectScreen.refresh();
   }

   public static void reset() {
      if (mode == 1) {
         SoloPlayback.deactivate();
      }

      mode = 1;
   }

   public static int mode() {
      return mode;
   }

   public static boolean personal() {
      return mode == 1;
   }

   public static String modeKey() {
      return mode == 1 ? "yunyy.listening.personal_short" : "yunyy.listening.public";
   }

   public static String modeName() {
      return Component.translatable(modeKey()).getString();
   }
}
