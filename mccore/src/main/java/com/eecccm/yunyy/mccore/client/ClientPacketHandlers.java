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

import com.eecccm.yunyy.core.config.GainPreferences;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.net.AuthPacket;
import com.eecccm.yunyy.mccore.net.HudPacket;
import com.eecccm.yunyy.mccore.net.ListeningStatePacket;
import com.eecccm.yunyy.mccore.net.MenuOpenPacket;
import com.eecccm.yunyy.mccore.net.PauseStatePacket;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.net.PlaylistOpenPacket;
import com.eecccm.yunyy.mccore.net.QueuePacket;
import com.eecccm.yunyy.mccore.net.SearchOpenPacket;
import com.eecccm.yunyy.mccore.net.StopPacket;
import com.eecccm.yunyy.mccore.net.VolumePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端 payload 接收处理（三加载器共用）：注册全部服务端→客户端包的响应逻辑。 */
public final class ClientPacketHandlers {
   private ClientPacketHandlers() {
   }

   public static void registerAll(ClientNet.Impl net) {
      net.register(
         PlayPacket.TYPE,
         (payload, context) -> {
            if (context.level != null && context.player != null) {
               PlaybackEngine.play(payload);
            }
         }
      );
      net.register(
         StopPacket.TYPE,
         (payload, context) -> {
            if (SyncSnapshot.personal()) {
               SoloPlayback.beforeExternalStop();
            }
            PlaybackEngine.stop();
            PlayQueue.clear();
         }
      );
      net.register(
         QueuePacket.TYPE,
         (payload, context) -> {
            if (!SyncSnapshot.personal()) {
               PlayQueue.update(payload);
               TransportScreen.refresh();
            }
         }
      );
      net.register(ListeningStatePacket.TYPE, (payload, context) -> SyncSnapshot.update(payload));
      net.register(
         PauseStatePacket.TYPE,
         (payload, context) -> {
            PlaybackEngine.setPaused(payload.paused());
            TransportScreen.refresh();
         }
      );
      net.register(
         VolumePacket.TYPE,
         (payload, client) -> {
            switch (payload.action()) {
               case 0:
                  ScreenRegistry.set(client, new GainScreen(ScreenRegistry.current(client)));
                  break;
               case 1:
                  setVolume(client, payload.value());
                  break;
               case 2:
                  toggleMute(client);
            }
         }
      );
      net.register(
         AuthPacket.TYPE,
         (payload, client) -> {
            VendorRegistry.activate(MusicVendor.byTag(payload.provider()));
            switch (payload.action()) {
               case 0:
                  ScreenRegistry.set(client, new QrSignInScreen(ScreenRegistry.current(client)));
                  break;
               case 1:
                  if (!VendorRegistry.hasSession()) {
                     ClientI18n.send(client.player, Component.translatable("yunyy.msg.not_logged_in", VendorRegistry.activeVendor().label, payload.provider()));
                  } else {
                     ClientI18n.send(client.player, Component.translatable("yunyy.msg.checking_account", VendorRegistry.activeVendor().label));
                     VendorRegistry.verifyAccount().whenComplete((account, error) -> client.execute(() -> {
                        if (client.player != null) {
                           if (error == null && account.active()) {
                              ClientI18n.send(
                                 client.player,
                                 Component.translatable(
                                    "yunyy.msg.account_valid",
                                    VendorRegistry.activeVendor().label,
                                    DisplayNames.accountComponent(account.nickname()),
                                    DisplayNames.vipSuffixComponent(account.note(), VendorRegistry.vipProbeEnabled())
                                 )
                              );
                           } else {
                              ClientI18n.send(client.player, Component.translatable("yunyy.msg.account_invalid", VendorRegistry.activeVendor().label, payload.provider()));
                           }
                        }
                     }));
                  }
                  break;
               case 2:
                  VendorRegistry.signOut();
                  ClientI18n.send(client.player, Component.translatable("yunyy.msg.logged_out", VendorRegistry.activeVendor().label));
            }
         }
      );
      net.register(
         HudPacket.TYPE,
         (payload, client) -> ScreenRegistry.set(client, new LayoutStudioScreen(ScreenRegistry.current(client)))
      );
      net.register(
         PlaylistOpenPacket.TYPE,
         (payload, client) -> {
            VendorRegistry.activate(MusicVendor.byTag(payload.provider()));
            if (!VendorRegistry.hasSession()) {
               ClientI18n.send(client.player, Component.translatable("yunyy.msg.login_first_playlist", payload.provider(), VendorRegistry.activeVendor().label));
            } else {
               ScreenRegistry.set(client, new PlaylistBrowserScreen(ScreenRegistry.current(client)));
            }
         }
      );
      net.register(
         MenuOpenPacket.TYPE,
         (payload, client) -> ScreenRegistry.set(client, new MainMenuScreen(ScreenRegistry.current(client)))
      );
      net.register(
         SearchOpenPacket.TYPE,
         (payload, client) -> {
            VendorRegistry.activate(MusicVendor.byTag(payload.provider()));
            ScreenRegistry.set(client, new SearchScreen(ScreenRegistry.current(client), payload.query()));
         }
      );
   }

   private static void setVolume(Minecraft client, int value) {
      int volume = GainPreferences.adjustLevel(value);
      PlaybackEngine.refreshVolume();
      ClientI18n.send(client.player, Component.translatable(volume == 0 ? "yunyy.msg.volume_muted" : "yunyy.msg.volume_set", volume));
   }

   private static void toggleMute(Minecraft client) {
      int volume = GainPreferences.flipMute();
      PlaybackEngine.refreshVolume();
      ClientI18n.send(client.player, Component.translatable(volume == 0 ? "yunyy.msg.volume_mute_again" : "yunyy.msg.volume_unmute", volume));
   }
}
