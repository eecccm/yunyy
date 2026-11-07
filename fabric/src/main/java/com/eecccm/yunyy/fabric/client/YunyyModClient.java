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

package com.eecccm.yunyy.fabric.client;

import com.eecccm.yunyy.core.config.AudioBridgeOptions;
import com.eecccm.yunyy.core.config.HudPreferences;
import com.eecccm.yunyy.core.config.LyricPreferences;
import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.config.GainPreferences;
import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.core.path.StorageLocations;
import com.eecccm.yunyy.mccore.client.ClientNet;
import com.eecccm.yunyy.mccore.client.ClientPacketHandlers;
import com.eecccm.yunyy.mccore.client.ScreenRegistry;
import com.eecccm.yunyy.mccore.client.SyncSnapshot;
import com.eecccm.yunyy.mccore.client.MainMenuScreen;
import com.eecccm.yunyy.mccore.client.PlaybackEngine;
import com.eecccm.yunyy.mccore.client.SoloPlayback;
import com.eecccm.yunyy.mccore.client.PlayQueue;
import com.eecccm.yunyy.mccore.client.GainDevice;
import com.eecccm.yunyy.mccore.log.Slf4jTraceSink;
import com.eecccm.yunyy.mccore.util.PacketIds;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/** Fabric 客户端入口：按键、客户端网络接收、HUD、播放器 tick。 */
public final class YunyyModClient implements ClientModInitializer {
   private static boolean hadWorld;
   private static boolean menuKeyWasDown;
   private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(PacketIds.id("music"));
   // 默认键 U（KEYSYM 85）：打开音乐菜单仅支持 U 键或 /music 命令
   private static final KeyMapping OPEN_MENU = new KeyMapping("key.yunyy.open_menu", InputConstants.Type.KEYSYM, 85, KEY_CATEGORY);

   public void onInitializeClient() {
      TraceLog.bind(new Slf4jTraceSink());
      StorageLocations.setConfigDir(FabricLoader.getInstance().getConfigDir());
      ServerPolicy.load();
      GainPreferences.load();
      AccountSessionStore.load();
      HudPreferences.load();
      LyricPreferences.load();
      AudioBridgeOptions.ensureDefaults();
      AudioBridgeOptions.load();
      VendorRegistry.syncProxy();
      // 客户端网络实现只在客户端 entrypoint 注入：YunyyMod（main）在专用服务器上同样执行，
      // 在那里触碰 FabricClientNet 会连带解析 Minecraft 客户端类，导致服务端启动失败。
      ClientNet.set(FabricClientNet.INSTANCE);
      FabricHud.register();
      KeyMappingHelper.registerKeyMapping(OPEN_MENU);
      ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SyncSnapshot.connected());
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         if (client.level != null && client.player != null) {
            hadWorld = true;
            PlaybackEngine.tick();
            SoloPlayback.tick();

            while (OPEN_MENU.consumeClick()) {
               if (client.player != null) {
                  var cur = ScreenRegistry.current(client);
                  String n = cur == null ? "" : cur.getClass().getSimpleName().toLowerCase();
                  if (cur == null || n.contains("friend") || n.contains("social")) {
                     ScreenRegistry.set(client, new MainMenuScreen(null));
                  }
               }
            }
            // GLFW 物理按键兜底：不依赖 KeyMappingHelper 注册（26.2 与游戏内置 O 键冲突时注册可能失效）
            try {
               long win = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
               boolean down = win != 0L && org.lwjgl.glfw.GLFW.glfwGetKey(win, 85) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
               if (down && !menuKeyWasDown) {
                  var cur = ScreenRegistry.current(client);
                  String n = cur == null ? "" : cur.getClass().getSimpleName().toLowerCase();
                  if (cur == null || n.contains("friend") || n.contains("social")) {
                     ScreenRegistry.set(client, new MainMenuScreen(null));
                  }
               }
               menuKeyWasDown = down;
            } catch (Exception ignored) {
            }
         } else {
            if (hadWorld || PlaybackEngine.currentSong() != null) {
               resetPlayback();
            }
         }
      });
      ClientPacketHandlers.registerAll(FabricClientNet.INSTANCE);
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetPlayback());
   }

   private static void resetPlayback() {
      hadWorld = false;
      SoloPlayback.reset();
      SyncSnapshot.reset();
      PlaybackEngine.stop();
      GainDevice.stopAll();
      PlayQueue.clear();
   }
}
