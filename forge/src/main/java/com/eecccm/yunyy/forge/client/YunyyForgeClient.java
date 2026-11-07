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

package com.eecccm.yunyy.forge.client;

import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.core.config.AudioBridgeOptions;
import com.eecccm.yunyy.core.config.GainPreferences;
import com.eecccm.yunyy.core.config.HudPreferences;
import com.eecccm.yunyy.core.config.LyricPreferences;
import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.mccore.client.ClientNet;
import com.eecccm.yunyy.mccore.client.ClientPacketHandlers;
import com.eecccm.yunyy.mccore.client.GainDevice;
import com.eecccm.yunyy.mccore.client.MainMenuScreen;
import com.eecccm.yunyy.mccore.client.PlayQueue;
import com.eecccm.yunyy.mccore.client.PlaybackEngine;
import com.eecccm.yunyy.mccore.client.ScreenRegistry;
import com.eecccm.yunyy.mccore.client.SoloPlayback;
import com.eecccm.yunyy.mccore.client.SyncSnapshot;
import com.eecccm.yunyy.mccore.log.Slf4jTraceSink;
import com.eecccm.yunyy.mccore.util.PacketIds;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge 客户端入口：按键、HUD、客户端网络接收与播放器 tick。
 *
 * <p>Forge 26.2 的 EventBus 7 不再支持 {@code @Mod.EventBusSubscriber} 那种「注解一把梭」的
 * 静态订阅（事件已改为 record 且各自持有独立 {@code BUS}），因此本类改为由
 * {@link #init()} 显式订阅，并由模组入口在 {@code Dist.CLIENT} 分支内调用。
 */
public final class YunyyForgeClient {
   private static boolean hadWorld;
   private static boolean menuKeyWasDown;
   private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(PacketIds.id("music"));
   // 默认键 U（KEYSYM 85）：打开音乐菜单仅支持 U 键或 /music 命令
   private static final KeyMapping OPEN_MENU = new KeyMapping("key.yunyy.open_menu", InputConstants.Type.KEYSYM, 85, KEY_CATEGORY);

   private YunyyForgeClient() {
   }

   /** 客户端初始化与事件订阅（客户端分发器调用，专用服务器永不进入）。 */
   public static void init() {
      TraceLog.bind(new Slf4jTraceSink());
      com.eecccm.yunyy.core.path.StorageLocations.setConfigDir(FMLPaths.CONFIGDIR.get());
      ServerPolicy.load();
      GainPreferences.load();
      AccountSessionStore.load();
      HudPreferences.load();
      LyricPreferences.load();
      AudioBridgeOptions.ensureDefaults();
      AudioBridgeOptions.load();
      VendorRegistry.syncProxy();
      ClientNet.set(ClientNetwork.INSTANCE);
      ClientNetwork.INSTANCE.attach();
      ClientPacketHandlers.registerAll(ClientNetwork.INSTANCE);
      RegisterKeyMappingsEvent.BUS.addListener(YunyyForgeClient::registerKeyMappings);
      AddGuiOverlayLayersEvent.BUS.addListener(YunyyForgeClient::registerGuiLayers);
      // 26.2 的 ClientTickEvent 拆为 Pre/Post 两个具体事件，取 Post 保持每 tick 一次
      TickEvent.ClientTickEvent.Post.BUS.addListener(YunyyForgeClient::onClientTick);
      ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(YunyyForgeClient::onClientJoin);
      ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(YunyyForgeClient::onClientLeave);
   }

   /** 按键注册（MOD 总线阶段）。 */
   public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
      event.register(OPEN_MENU);
   }

   /** HUD 图层注册：附加到原版图层栈末尾，保证绘制在其他 HUD 之上。 */
   public static void registerGuiLayers(AddGuiOverlayLayersEvent event) {
      ForgeLayer nowPlaying = (graphics, partialTick) -> com.eecccm.yunyy.mccore.client.NowPlayingOverlay.extract(graphics, partialTick);
      ForgeLayer lyrics = (graphics, partialTick) -> com.eecccm.yunyy.mccore.client.LyricOverlay.extract(graphics, partialTick);
      event.getLayeredDraw().add(PacketIds.id("now_playing"), nowPlaying);
      event.getLayeredDraw().add(PacketIds.id("lyrics"), lyrics);
   }

   private static void onClientJoin(ClientPlayerNetworkEvent.LoggingIn event) {
      SyncSnapshot.connected();
   }

   private static void onClientLeave(ClientPlayerNetworkEvent.LoggingOut event) {
      resetPlayback();
   }

   private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
      final Minecraft client = Minecraft.getInstance();
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
         // GLFW 物理按键兜底
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
