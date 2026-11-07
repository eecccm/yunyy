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
import com.eecccm.yunyy.core.geom.HudBox;
import com.eecccm.yunyy.core.config.HudPreferences;
import com.eecccm.yunyy.core.config.LyricPreferences;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.util.DisplayNames;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class NowPlayingOverlay {
   private static final int WIDTH = 210;
   private static final int HEIGHT = 56;
   private static final int HEIGHT_WITH_NEXT = 67;
   private static final int COVER = 42;
   /** 预览界面无播放时的示例歌曲；标题/歌手由演示语言键在渲染分支覆盖，core 占位不携带文案。 */
   private static final PlayPacket DEMO_SONG = new PlayPacket("demo", "", "", "", "", 0L, 0L, 228000L, "demo");
   /** 本帧渲染出的播放信息块矩形（供调试拖动模式命中检测）。 */
   private static HudBox lastRect;

   private NowPlayingOverlay() {
   }

   public static HudBox lastRect() {
      return lastRect;
   }

   public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaSongEntryer) {
      render(graphics, false, -1);
   }

   /** 当前播放信息渲染（供 HUD 元素注册调用）。 */
   public static void render(GuiGraphicsExtractor graphics) {
      render(graphics, false, -1);
   }

   /** 预览渲染：无播放时也使用示例歌曲，用于设置界面的实时预览。
    *  @param maxBottomY 预览界面中块的底部边界（面板上方），<=0 表示无限制 */
   public static void renderDemo(GuiGraphicsExtractor graphics, int maxBottomY) {
      render(graphics, true, maxBottomY);
   }

   private static void render(GuiGraphicsExtractor graphics, boolean demo, int maxBottomY) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!HudPreferences.shown() || minecraft.player == null) {
         return;
      }
      PlayPacket song;
      long elapsed;
      boolean hasNext;
      String nextTitle;
      String nextArtist;
      int queued;
      String demoTitle = null;
      String demoArtist = null;
      if (demo) {
         song = DEMO_SONG;
         elapsed = 90000L;
         hasNext = true;
         demoTitle = Component.translatable("yunyy.demo.song").getString();
         demoArtist = Component.translatable("yunyy.demo.artist").getString();
         nextTitle = Component.translatable("yunyy.demo.next").getString();
         nextArtist = demoArtist;
         queued = 2;
      } else {
         song = PlaybackEngine.currentSong();
         if (song == null) {
            return;
         }
         long duration = song.durationMillis();
         elapsed = PlaybackEngine.elapsedMillis();
         if (duration > 0L && elapsed >= duration) {
            return;
         }
         hasNext = PlayQueue.hasNext();
         nextTitle = PlayQueue.nextTitle();
         nextArtist = PlayQueue.nextArtist();
         queued = PlayQueue.queuedSongs();
      }
      float scale = (float)HudPreferences.scale();
      int scaledWidth = Math.round(210.0F * scale);
      int panelHeight = hasNext ? HEIGHT_WITH_NEXT : HEIGHT;
      int scaledHeight = Math.round(panelHeight * scale);
      int availableX = graphics.guiWidth() - scaledWidth - 16;
      int availBottom = maxBottomY > 0 ? maxBottomY : graphics.guiHeight() - 8;
      int availableY = availBottom - 8 - scaledHeight;
      int x = 8 + (int)Math.round(availableX * HudPreferences.anchorX());
      int y = 8 + (int)Math.round(availableY * HudPreferences.anchorY());
      lastRect = new HudBox(x, y, scaledWidth, scaledHeight);
      if (LyricPreferences.tuningMode()) {
         graphics.outline(x - 2, y - 2, x + scaledWidth + 2, y + scaledHeight + 2, -65536);
      }
      Font font = minecraft.font;
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      graphics.pose().scale(scale);
      graphics.fill(0, 0, 210, panelHeight, -1156573152);
      graphics.outline(0, 0, 210, panelHeight, -1437808144);
      Identifier cover = AlbumArt.texture();
      if (cover != null) {
         graphics.blit(RenderPipelines.GUI_TEXTURED, cover, 7, 7, 0.0F, 0.0F, COVER, COVER, 64, 64);
      } else {
         graphics.fill(7, 7, 49, 49, -14272960);
         graphics.centeredText(font, "♪", 28, 24, -8790539);
      }
      int contentX = 56;
      int contentWidth = 210 - contentX - 7;
      String title = trim(font, (PlaybackEngine.isPaused() ? "▶ " : "♫ ") + (demo ? demoTitle : song.title()) + "  [" + SyncSnapshot.modeName() + "]", contentWidth);
      String artist = trim(font, demo ? demoArtist : DisplayNames.artist(song.artist()), contentWidth);
      graphics.text(font, title, contentX, 7, -1, true);
      graphics.text(font, artist, contentX, 19, -4602161, false);
      int barY = 33;
      graphics.fill(contentX, barY, contentX + contentWidth, barY + 4, -13352630);
      double ratio = song.durationMillis() > 0L ? Math.min(1.0, (double)elapsed / song.durationMillis()) : 0.0;
      graphics.fill(contentX, barY, contentX + (int)Math.round(contentWidth * ratio), barY + 4, -13187600);
      String time = format(elapsed) + " / " + (song.durationMillis() > 0L ? format(song.durationMillis()) : "--:--");
      graphics.text(font, time, 203 - font.width(time), 41, -4602161, false);
      if (hasNext) {
         String queueSuffix = queued > 1
            ? "  (" + Component.translatable("yunyy.playlist.song_suffix", queued).getString() + ")"
            : "";
         String next = PlayQueue.mode() == 3
            ? Component.translatable("yunyy.msg.next_random").getString() + queueSuffix
            : Component.translatable("yunyy.msg.next_song", nextTitle + " - " + DisplayNames.artist(nextArtist) + queueSuffix).getString();
         next = trim(font, next, 196);
         graphics.text(font, next, 7, 54, -6366488, false);
      }
      graphics.pose().popMatrix();
   }

   private static String trim(Font font, String text, int maxWidth) {
      return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
   }

   private static String format(long millis) {
      long seconds = Math.max(0L, millis / 1000L);
      return "%d:%02d".formatted(seconds / 60L, seconds % 60L);
   }
}
