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
import com.eecccm.yunyy.core.config.LyricPreferences;
import com.eecccm.yunyy.core.lyrics.LyricTimeline;

import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class LyricOverlay {
   /** 歌词块固定逻辑宽度：保证播放时块大小稳定，不随歌词内容忽大忽小。 */
   private static final int WIDTH = 340;
   /** 行文本最大宽度（块宽 - 两侧内边距），超出自动换行保证完整显示。 */
   private static final int MAX_LINE_WIDTH = WIDTH - 12;
   /** MC 默认字体行高（逻辑像素）。 */
   private static final int FONT_HEIGHT = 9;
   /** 本帧渲染出的歌词块矩形（供调试拖动模式命中检测）。 */
   private static HudBox lastRect;

   private LyricOverlay() {
   }

   public static HudBox lastRect() {
      return lastRect;
   }

   public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaSongEntryer) {
      render(graphics, false, -1);
   }

   /** 当前播放歌词渲染（供 HUD 元素注册调用）。 */
   public static void render(GuiGraphicsExtractor graphics) {
      render(graphics, false, -1);
   }

   /** 预览渲染：无播放时也使用示例歌词，用于设置界面的实时预览。
    *  @param maxBottomY 预览界面中块的底部边界（面板上方），<=0 表示无限制 */
   public static void renderDemo(GuiGraphicsExtractor graphics, int maxBottomY) {
      render(graphics, true, maxBottomY);
   }

   private static void render(GuiGraphicsExtractor graphics, boolean demo, int maxBottomY) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!LyricPreferences.shown()) {
         return;
      }
      PlayPacket song = PlaybackEngine.currentSong();
      LyricTimeline.Viewport lyrics;
      if (demo) {
         lyrics = new LyricTimeline.Viewport(
            Component.translatable("yunyy.demo.lyric_prev").getString(),
            Component.translatable("yunyy.demo.lyric_current").getString(),
            Component.translatable("yunyy.demo.lyric_next").getString());
      } else {
         if (song == null || minecraft.player == null) {
            return;
         }
         long elapsed = PlaybackEngine.elapsedMillis();
         if (song.durationMillis() > 0L && elapsed >= song.durationMillis()) {
            return;
         }
         lyrics = LyricTimeline.viewport(elapsed);
      }
      if (lyrics == null) {
         return;
      }
      Font font = minecraft.font;
      int lines = LyricPreferences.lineCount();
      int lineSp = LyricPreferences.rowGap();
      int paraSp = LyricPreferences.blockGap();
      // 收集所有要绘制的句子（每句若干行，行内已按固定块宽换行）
      List<Sentence> sentences = new ArrayList<>();
      if (lines == 3) {
         addIfAny(sentences, build(font, lyrics.previous(), false));
         addIfAny(sentences, build(font, lyrics.current(), true));
         addIfAny(sentences, build(font, lyrics.next(), false));
      } else if (lines == 2) {
         addIfAny(sentences, build(font, lyrics.current(), true));
         addIfAny(sentences, build(font, lyrics.next(), false));
      } else {
         addIfAny(sentences, build(font, lyrics.current(), true));
      }
      String hintText = missHintText(lyrics.current());
      if (hintText != null) {
         addIfAny(sentences, new Sentence(wrap(font, hintText, MAX_LINE_WIDTH, 0xFFFFFF55, false)));
      }
      if (sentences.isEmpty()) {
         return;
      }
      // 块高 = 逐句累加：句内 (行数-1)*行距 + 最后一行文字高，句间加句距
      int contentHeight = 0;
      for (int i = 0; i < sentences.size(); i++) {
         int rows = sentences.get(i).rows().size();
         contentHeight += (rows - 1) * lineSp + FONT_HEIGHT;
         if (i < sentences.size() - 1) {
            contentHeight += paraSp;
         }
      }
      // 缩放：正常按用户设置的 scale（块宽固定，不会随歌词忽大忽小）；
      // 仅在内容超高导致可能超出窗口时才自动缩小，保证完整显示且不越界
      float scale = (float)LyricPreferences.scale();
      int guiW = graphics.guiWidth();
      int guiH = graphics.guiHeight();
      float wCap = WIDTH * scale > (guiW - 16) ? (guiW - 16) / (float)WIDTH : scale;
      float hCap = contentHeight * scale > (guiH - 16) ? (guiH - 16) / (float)Math.max(1, contentHeight) : scale;
      float effScale = Math.min(Math.min(scale, wCap), hCap);
      int scaledWidth = Math.round(WIDTH * effScale);
      int scaledHeight = Math.round(contentHeight * effScale);
      int availableX = guiW - scaledWidth - 16;
      int availBottom = maxBottomY > 0 ? maxBottomY : guiH - 8;
      int availableY = availBottom - 8 - scaledHeight;
      int x = 8 + (int)Math.round(availableX * LyricPreferences.anchorX());
      int y = 8 + (int)Math.round(availableY * LyricPreferences.anchorY());
      x = Math.max(8, Math.min(x, guiW - scaledWidth - 8));
      y = Math.max(8, Math.min(y, availBottom - scaledHeight));
      lastRect = new HudBox(x, y, scaledWidth, scaledHeight);
      if (LyricPreferences.tuningMode()) {
         graphics.outline(x - 2, y - 2, x + scaledWidth + 2, y + scaledHeight + 2, -65536);
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      graphics.pose().scale(effScale);
      int y0 = 0;
      for (int i = 0; i < sentences.size(); i++) {
         List<Row> rows = sentences.get(i).rows();
         for (int j = 0; j < rows.size(); j++) {
            Row row = rows.get(j);
            int width = font.width(row.text());
            graphics.text(font, row.text(), (WIDTH - width) / 2, y0, row.color(), row.shadow());
            y0 += lineSp;
         }
         if (i < sentences.size() - 1) {
            y0 += paraSp;
         }
      }
      graphics.pose().popMatrix();
   }

   private static void addIfAny(List<Sentence> target, Sentence sentence) {
      if (!sentence.rows().isEmpty()) {
         target.add(sentence);
      }
   }

   /** 一句歌词展开为若干行，超宽换行。
    *  段语义：3 段格式 = 音译+原文+翻译（首段为音译）；2 段格式 = 原文+翻译（无独立音译段）；
    *  1 段 = 纯原文。显示开关只隐藏对应段：关音译只隐藏 3 段格式的首段，其余段不受影响。 */
   private static Sentence build(Font font, String text, boolean isCurrent) {
      if (text == null || text.isBlank()) {
         return new Sentence(List.of());
      }
      String[] segs = text.split("\u0001", -1);
      List<Row> rows = new ArrayList<>();
      int shown = 0;
      for (int i = 0; i < segs.length && shown < 3; i++) {
         String part = segs[i].trim();
         if (part.isBlank()) {
            continue;
         }
         boolean isRoma = segs.length >= 3 && i == 0;          // 仅 3 段格式有音译段
         boolean isTrans = segs.length > 1 && i == segs.length - 1;  // 末段为翻译段
         if (isRoma && !FeatureSwitch.lyricRomaji) {
            continue;   // 关闭音译：只隐藏 3 段格式的音译段
         }
         if (isTrans && !FeatureSwitch.lyricTranslation) {
            continue;   // 关闭翻译：隐藏末段翻译
         }
         int color;
         if (!isCurrent) {
            color = -1426063361;   // 上下文句（上一/下一）：统一灰
         } else if (segs.length <= 1) {
            color = -9574913;      // 纯文本：原文（亮蓝）
         } else if (isRoma) {
            color = -1426063361;   // 音译（灰）
         } else if (isTrans) {
            color = -6710887;      // 翻译（浅灰）
         } else {
            color = -9574913;      // 原文（亮蓝）
         }
         rows.addAll(wrap(font, part, MAX_LINE_WIDTH, color, shown == 1));
         shown++;
      }
      // 过滤后若一句都不剩（极端情况），回退显示首段，保证 3 句模式始终稳定显示 3 句
      if (rows.isEmpty() && segs.length > 0) {
         String fallback = segs[0].trim();
         if (!fallback.isBlank()) {
            rows.addAll(wrap(font, fallback, MAX_LINE_WIDTH, -9574913, false));
         }
      }
      return new Sentence(rows);
   }

   /** 把一段文本按最大宽度拆成多行，保证不截断、完整显示。 */
   private static List<Row> wrap(Font font, String text, int maxWidth, int color, boolean shadow) {
      List<Row> rows = new ArrayList<>();
      if (text.isEmpty()) {
         return rows;
      }
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         line.append(c);
         if (font.width(line.toString()) > maxWidth) {
            line.deleteCharAt(line.length() - 1);
            if (line.length() > 0) {
               rows.add(new Row(line.toString(), color, shadow));
            }
            line.setLength(0);
            line.append(c);
         }
      }
      if (line.length() > 0) {
         rows.add(new Row(line.toString(), color, shadow));
      }
      return rows;
   }

   /** 缺失提示（无音译/无翻译）：仅当整首歌完全没有对应段落时才提示。 */
   private static String missHintText(String text) {
      if (!(FeatureSwitch.lyricRomaji || FeatureSwitch.lyricTranslation) || text == null || text.isBlank()) {
         return null;
      }
      List<String> miss = new ArrayList<>();
      if (FeatureSwitch.lyricRomaji && LyricTimeline.missingRomaji()) {
         miss.add(Component.translatable("yunyy.lyrics.no_roma").getString());
      }
      if (FeatureSwitch.lyricTranslation && LyricTimeline.missingTranslation()) {
         miss.add(Component.translatable("yunyy.lyrics.no_translation").getString());
      }
      if (miss.isEmpty()) {
         return null;
      }
      return "— " + String.join("/", miss) + " —";
   }

   private record Row(String text, int color, boolean shadow) {
   }

   private record Sentence(List<Row> rows) {
   }
}
