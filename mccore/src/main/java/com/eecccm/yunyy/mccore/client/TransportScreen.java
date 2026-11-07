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
import com.eecccm.yunyy.mccore.net.ClearQueuePacket;
import com.eecccm.yunyy.mccore.net.QueueActionPacket;
import com.eecccm.yunyy.mccore.client.AccessControl;
import com.eecccm.yunyy.mccore.util.DisplayNames;

import com.eecccm.yunyy.mccore.net.ClearQueuePacket;
import com.eecccm.yunyy.mccore.net.QueueActionPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TransportScreen extends ScrollFrame {
   private static final int PAGE_SIZE = 4;
   private final Screen parent;
   private boolean confirmingClear;
   private int page;
   private final List<TransportScreen.QueueArrows> arrows = new ArrayList<>();
   private static TransportScreen active;

   public TransportScreen(Screen parent) {
      super(Component.translatable("yunyy.queue.title"));
      this.parent = parent;
   }

   protected void init() {
      active = this;
      this.arrows.clear();
      List<PlayQueue.Song> songs = PlayQueue.songs();
      int pages = Math.max(1, (songs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
      this.page = Math.max(0, Math.min(this.page, pages - 1));
      int panelWidth = Math.min(440, this.width - 24);
      int x = (this.width - panelWidth) / 2;
      int y = 65;
      int controlsY = y + 96 + 4;
      this.scrollPanel.setViewport(x - 4, 58, x + panelWidth + 4, Math.min(controlsY + 50, this.height - 8));
      this.scrollPanel.setContentHeight(controlsY + 50 - 58);
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      int start = this.page * PAGE_SIZE;
      int end = Math.min(start + PAGE_SIZE, songs.size());

      for (int i = start; i < end; i++) {
         PlayQueue.Song song = songs.get(i);
         int index = i;
         String label = i + 1 + ". " + trim(song.title(), 20) + " - " + trim(DisplayNames.artist(song.artist()), 12) + " [" + format(song.duration()) + "]";
         this.addRenderableWidget(
            Button.builder(Component.literal(label), b -> this.playNow(index)).bounds(x, y + (i - start) * 24, panelWidth - 114, 20).build()
         );
         int rowY = y + (i - start) * 24;
         Button up = this.addRenderableWidget(
            Button.builder(Component.literal("↑"), b -> this.move(index, true)).bounds(x + panelWidth - 110, rowY, 20, 20).build()
         );
         Button down = this.addRenderableWidget(
            Button.builder(Component.literal("↓"), b -> this.move(index, false)).bounds(x + panelWidth - 88, rowY, 20, 20).build()
         );
         up.active = index > 0;
         down.active = index + 1 < songs.size();
         up.visible = down.visible = false;
         this.arrows.add(new TransportScreen.QueueArrows(x, rowY, panelWidth, up, down));
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.queue.remove"), b -> this.remove(index)).bounds(x + panelWidth - 60, y + (i - start) * 24, 60, 20).build()
         );
      }

      Button previous = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.prev"), b -> {
         this.page--;
         this.rebuildWidgets();
      }).bounds(x, controlsY, 70, 20).build());
      previous.active = this.page > 0;
      Button nextPage = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.next"), b -> {
         this.page++;
         this.rebuildWidgets();
      }).bounds(x + 76, controlsY, 70, 20).build());
      nextPage.active = this.page + 1 < pages;
      // 权限隔离：清空公共播放队列需要管理员权限；个人播放模式可清空自己的队列
      boolean canClear = SyncSnapshot.personal() || AccessControl.isAdminClient(this.minecraft.player);
      if (canClear) {
         Button clear = this.addRenderableWidget(
            Button.builder(Component.translatable(this.confirmingClear ? "yunyy.queue.clear_confirm" : "yunyy.queue.clear"), b -> this.clearAll())
               .bounds(x, controlsY + 26, panelWidth / 2 - 3, 20)
               .build()
         );
         clear.active = PlaybackEngine.currentSong() != null || PlayQueue.queuedSongs() > 0;
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.queue.back"), b -> this.onClose()).bounds(x + panelWidth / 2 + 3, controlsY + 26, panelWidth / 2 - 3, 20).build()
         );
      } else {
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.queue.back"), b -> this.onClose()).bounds(x, controlsY + 26, panelWidth, 20).build()
         );
      }
   }

   private void playNow(int index) {
      if (SyncSnapshot.personal()) {
         SoloPlayback.queueAction(1, index);
      } else {
         ClientNet.get().send(new QueueActionPacket(1, index));
      }
   }

   private void remove(int index) {
      if (SyncSnapshot.personal()) {
         SoloPlayback.queueAction(0, index);
      } else {
         ClientNet.get().send(new QueueActionPacket(0, index));
      }
   }

   private void move(int index, boolean up) {
      int action = up ? 2 : 3;
      if (SyncSnapshot.personal()) {
         SoloPlayback.queueAction(action, index);
      } else {
         ClientNet.get().send(new QueueActionPacket(action, index));
      }
   }

   private void clearAll() {
      if (!this.confirmingClear) {
         this.confirmingClear = true;
         this.rebuildWidgets();
      } else {
         if (SyncSnapshot.personal()) {
            SoloPlayback.clear();
         } else {
            ClientNet.get().send(new ClearQueuePacket());
         }

         this.onClose();
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      int hoverY = mouseY + this.scrollPanel.scrollY();

      for (TransportScreen.QueueArrows row : this.arrows) {
         boolean hover = mouseX >= row.x && mouseX < row.x + row.width && hoverY >= row.y && hoverY < row.y + 20;
         row.up.visible = row.down.visible = hover || row.up.isFocused() || row.down.isFocused();
      }

      graphics.centeredText(this.font, this.title.getString() + " · " + SyncSnapshot.modeName(), this.width / 2, 12, -1);
      String current = PlaybackEngine.currentSong() == null ? Component.translatable("yunyy.queue.none").getString() : PlaybackEngine.currentSong().title() + " - " + DisplayNames.artist(PlaybackEngine.currentSong().artist());
      graphics.centeredText(this.font, Component.translatable("yunyy.queue.playing", trim(current, 55)).getString(), this.width / 2, 31, -6366488);
      int pages = Math.max(1, (PlayQueue.queuedSongs() + PAGE_SIZE - 1) / PAGE_SIZE);
      graphics.centeredText(
         this.font,
         Component.translatable("yunyy.queue.info", PlayQueue.queuedSongs(), this.page + 1, pages).getString(),
         this.width / 2,
         52,
         -5592406
      );
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public static void refresh() {
      if (active != null) {
         active.rebuildWidgets();
      }
   }

   public void removed() {
      if (active == this) {
         active = null;
      }

      super.removed();
   }

   public boolean isPauseScreen() {
      return false;
   }

   private static String trim(String text, int max) {
      return text.length() <= max ? text : text.substring(0, max - 1) + "…";
   }

   private static String format(long millis) {
      long seconds = Math.max(0L, millis / 1000L);
      return "%d:%02d".formatted(seconds / 60L, seconds % 60L);
   }

   private record QueueArrows(int x, int y, int width, Button up, Button down) {
   }
}
