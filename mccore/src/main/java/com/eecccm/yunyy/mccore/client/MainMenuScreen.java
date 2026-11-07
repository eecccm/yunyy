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
import com.eecccm.yunyy.mccore.net.NextSongPacket;
import com.eecccm.yunyy.mccore.net.PauseActionPacket;
import com.eecccm.yunyy.mccore.net.PlaybackModePacket;
import com.eecccm.yunyy.mccore.net.QueueActionPacket;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MainMenuScreen extends ScrollFrame {
   private final Screen parent;
   private Button previousButton;
   private Button pauseButton;
   private Button nextButton;
   private Button modeButton;

   public MainMenuScreen(Screen parent) {
      super(Component.translatable("yunyy.menu.title"));
      this.parent = parent;
   }

   protected void init() {
      int width = Math.min(260, this.width - 30);
      int x = (this.width - width) / 2;
      int y = Math.max(28, this.height / 2 - 98);
      boolean isAdmin = AccessControl.isAdminClient(this.minecraft.player);
      boolean personal = SyncSnapshot.personal();
      int rowY = y;
      // 权限隔离：播放模式按钮仅 OP 或在个人播放模式下显示；调试模式按钮仅 OP 显示
      boolean showMode = isAdmin || personal;
      boolean showDebug = isAdmin;
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.menu.search"), button -> ScreenRegistry.set(this.minecraft, new SearchScreen(this))).bounds(x, rowY, width, 20).build()
      );
      rowY += 25;
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.menu.my_playlist", VendorRegistry.activeVendor().label), button -> this.openPlaylistFolder()).bounds(x, rowY, width, 20).build()
      );
      rowY += 25;
      int w3 = (width - 10) / 3;
      this.previousButton = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.previous"), button -> {
         if (SyncSnapshot.personal()) {
            SoloPlayback.queueAction(4, 0);
         } else {
            ClientNet.get().send(new QueueActionPacket(4, 0));
         }
      }).bounds(x, rowY, w3, 20).build());
      this.pauseButton = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.pause"), button -> {
         if (SyncSnapshot.personal()) {
            SoloPlayback.togglePause();
         } else {
            ClientNet.get().send(new PauseActionPacket());
         }
      }).bounds(x + w3 + 5, rowY, w3, 20).build());
      this.nextButton = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.next"), button -> {
         if (SyncSnapshot.personal()) {
            SoloPlayback.next();
         } else {
            ClientNet.get().send(new NextSongPacket());
         }
      }).bounds(x + w3 * 2 + 10, rowY, w3, 20).build());
      rowY += 25;
      this.modeButton = null;
      if (showMode) {
         this.modeButton = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.mode"), button -> {
            int next = (PlayQueue.mode() + 1) % 4;
            if (SyncSnapshot.personal()) {
               SoloPlayback.setMode(next);
            } else {
               ClientNet.get().send(new PlaybackModePacket(next));
            }
         }).bounds(x, rowY, width, 20).build());
         rowY += 25;
      }
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.menu.queue"), button -> ScreenRegistry.set(this.minecraft, new TransportScreen(this)))
            .bounds(x, rowY, width, 20)
            .build()
      );
      rowY += 25;
      int w2 = (width - 8) / 2;
      if (showDebug) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.menu.volume"), button -> ScreenRegistry.set(this.minecraft, new GainScreen(this))).bounds(x, rowY, w2, 20).build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.menu.hud"), button -> ScreenRegistry.set(this.minecraft, new LayoutStudioScreen(this)))
               .bounds(x + w2 + 8, rowY, w2, 20)
               .build()
         );
      } else {
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.menu.volume"), button -> ScreenRegistry.set(this.minecraft, new GainScreen(this))).bounds(x, rowY, width, 20).build()
         );
      }
      rowY += 25;
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.menu.account"), button -> ScreenRegistry.set(this.minecraft, new ProfileScreen(this))).bounds(x, rowY, w2, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(
            Component.translatable("yunyy.menu.listening", SyncSnapshot.modeName()),
            button -> ScreenRegistry.set(this.minecraft, new ModeSelectScreen(this))
         )
            .bounds(x + w2 + 8, rowY, w2, 20)
            .build()
      );
      rowY += 25;
      if (showDebug) {
         this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.debug"), button -> ScreenRegistry.set(this.minecraft, new TuningScreen(this)))
            .bounds(x, rowY, width, 20).build());
         rowY += 25;
      }
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.menu.close"), button -> this.onClose()).bounds(x, rowY, width, 20).build());
      int contentBottom = rowY + 20;
      this.scrollPanel.setViewport(x - 4, y - 27, x + width + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 27));
      this.scrollPanel.setSongEntryX(x + width + 6);
      this.scrollPanel.reset();
      this.tick();
   }

   public void tick() {
      this.previousButton.active = PlayQueue.hasPrevious();
      this.pauseButton.active = PlaybackEngine.currentSong() != null;
      this.pauseButton.setMessage(Component.translatable(PlaybackEngine.isPaused() ? "yunyy.menu.resume" : "yunyy.menu.pause"));
      this.nextButton.active = PlayQueue.hasNext() || PlaybackEngine.currentSong() != null;
      if (this.modeButton != null) {
         this.modeButton.setMessage(Component.translatable("yunyy.menu.mode_with", PlayQueue.modeName()));
      }
   }

   private void openPlaylistFolder() {
      if (!VendorRegistry.hasSession()) {
         ScreenRegistry.set(this.minecraft, new QrSignInScreen(this));
      } else {
         ScreenRegistry.set(this.minecraft, new PlaylistBrowserScreen(this));
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      int menuY = Math.max(28, this.height / 2 - 98);
      graphics.centeredText(this.font, this.title, this.width / 2, menuY - 27, -1);
      graphics.centeredText(
         this.font,
         PlaybackEngine.currentSong() == null
            ? Component.translatable("yunyy.menu.now_playing_none")
            : Component.translatable("yunyy.menu.now_playing", PlaybackEngine.currentSong().title()),
         this.width / 2,
         menuY - 14,
         -6366488
      );
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
      PlaybackEngine.reapplyPause();
   }

   public boolean isPauseScreen() {
      return false;
   }
}
