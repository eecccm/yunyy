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

package com.eecccm.yunyy.neoforge.client;

import com.eecccm.yunyy.core.config.AudioBridgeOptions;
import com.eecccm.yunyy.core.config.HudPreferences;
import com.eecccm.yunyy.core.config.LyricPreferences;
import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.config.GainPreferences;
import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.platform.VendorRegistry;
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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge 客户端入口：按键、HUD、客户端网络接收与播放器 tick。 */
// NeoForge 26.2 的 @EventBusSubscriber 已移除 bus 属性：注册类事件（IModBusEvent）与游戏事件
// 由事件类型自动分流，因此这里只声明 modid 与客户端分发标记。
@EventBusSubscriber(modid = "yunyy", value = Dist.CLIENT)
public final class YunyyNeoForgeClient {
   private static boolean hadWorld;
   private static boolean menuKeyWasDown;
   private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(PacketIds.id("music"));
   // 默认键 U（KEYSYM 85）：打开音乐菜单仅支持 U 键或 /music 命令
   private static final KeyMapping OPEN_MENU = new KeyMapping("key.yunyy.open_menu", InputConstants.Type.KEYSYM, 85, KEY_CATEGORY);

   private YunyyNeoForgeClient() {
   }

   @SubscribeEvent
   public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
      init();
   }

   @SubscribeEvent
   public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
      event.register(OPEN_MENU);
   }

   @SubscribeEvent
   public static void registerGuiLayers(RegisterGuiLayersEvent event) {
      // 26.2 的 HUD 图层契约已由 IGuiLayer 更名为 GuiLayer，渲染入参为 GuiGraphicsExtractor + DeltaTracker
      GuiLayer nowPlaying = (graphics, partialTick) -> com.eecccm.yunyy.mccore.client.NowPlayingOverlay.extract(graphics, partialTick);
      GuiLayer lyrics = (graphics, partialTick) -> com.eecccm.yunyy.mccore.client.LyricOverlay.extract(graphics, partialTick);
      event.registerAboveAll(PacketIds.id("now_playing"), nowPlaying);
      event.registerAboveAll(PacketIds.id("lyrics"), lyrics);
   }

   /** 客户端初始化（客户端分发器加载时执行一次）。 */
   public static void init() {
      TraceLog.bind(new Slf4jTraceSink());
      com.eecccm.yunyy.core.path.StorageLocations.setConfigDir(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
      ServerPolicy.load();
      GainPreferences.load();
      AccountSessionStore.load();
      HudPreferences.load();
      LyricPreferences.load();
      AudioBridgeOptions.ensureDefaults();
      AudioBridgeOptions.load();
      VendorRegistry.syncProxy();
      // 下行处理器登记与 sink 安装放在客户端初始化阶段：payload 本体已由服务端注册期一次性完成
      ClientNet.set(ClientNetwork.INSTANCE);
      ClientNetwork.INSTANCE.attach();
      ClientPacketHandlers.registerAll(ClientNetwork.INSTANCE);
      NeoForge.EVENT_BUS.addListener(YunyyNeoForgeClient::onClientTick);
      NeoForge.EVENT_BUS.addListener(YunyyNeoForgeClient::onClientJoin);
      NeoForge.EVENT_BUS.addListener(YunyyNeoForgeClient::onClientLeave);
   }

   private static void onClientJoin(ClientPlayerNetworkEvent.LoggingIn event) {
      SyncSnapshot.connected();
   }

   private static void onClientLeave(ClientPlayerNetworkEvent.LoggingOut event) {
      resetPlayback();
   }

   // 26.2 的 ClientTickEvent 已不再携带 Minecraft 实例（拆分为 Pre/Post 两个事件类），
   // 因此这里固定监听 Post 保证每 tick 只执行一次，客户端实例改由 Minecraft.getInstance() 获取。
   private static void onClientTick(ClientTickEvent.Post event) {
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
