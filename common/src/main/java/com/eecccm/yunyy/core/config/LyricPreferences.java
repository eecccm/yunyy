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

package com.eecccm.yunyy.core.config;

import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.path.StorageLocations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 歌词 HUD 偏好：位置 / 缩放 / 行数 / 行距 / 翻译与音译开关。
 *
 * <p>翻译/音译开关写回 {@link FeatureSwitch}（渲染线程直接读），并随配置持久化。
 * 行数在 1~3 之间循环（1=当前行，2=当前+下一行，3=当前+下一+再下一行）。</p>
 */
public final class LyricPreferences {

   private static final Path FILE = StorageLocations.configDir().resolve("yunyy-lyrics.properties");

   private static double scale = 1.0;
   private static double anchorX = 0.5;
   private static double anchorY = 0.5;
   private static boolean shown = true;
   private static int lineCount = 3;
   private static int rowGap = 24;
   private static int blockGap = 14;
   private static boolean showTranslation = true;
   private static boolean showRomaji = true;
   private static boolean tuningMode = false;
   private static boolean dirty = false;

   private static double snapScale = 1.0;
   private static double snapAnchorX = 0.5;
   private static double snapAnchorY = 0.5;
   private static boolean snapShown = true;
   private static int snapLineCount = 3;
   private static int snapRowGap = 24;
   private static int snapBlockGap = 14;
   private static boolean snapTranslation = true;
   private static boolean snapRomaji = true;

   private LyricPreferences() {
   }

   public static void load() {
      if (!Files.isRegularFile(FILE)) {
         return;
      }
      Properties p = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
         p.load(reader);
         scale = clampDouble(p.getProperty("scale", "1.0"), 0.5, 2.0, 1.0);
         anchorX = clampDouble(p.getProperty("anchorX", "0.5"), 0.0, 1.0, 0.5);
         anchorY = clampDouble(p.getProperty("anchorY", "0.5"), 0.0, 1.0, 0.5);
         shown = Boolean.parseBoolean(p.getProperty("shown", "true"));
         lineCount = clampInt(p.getProperty("lineCount", "3"), 1, 3, 3);
         rowGap = clampInt(p.getProperty("rowGap", "24"), 14, 48, 24);
         blockGap = clampInt(p.getProperty("blockGap", "14"), 6, 40, 14);
         showTranslation = Boolean.parseBoolean(p.getProperty("showTranslation", "true"));
         showRomaji = Boolean.parseBoolean(p.getProperty("showRomaji", "true"));
         tuningMode = Boolean.parseBoolean(p.getProperty("tuningMode", "false"));
      } catch (Exception e) {
         TraceLog.warn("无法读取歌词 HUD 配置，已使用默认值", e);
      }
   }

   public static double scale() {
      return scale;
   }

   public static double anchorX() {
      return anchorX;
   }

   public static double anchorY() {
      return anchorY;
   }

   public static boolean shown() {
      return shown;
   }

   public static int lineCount() {
      return lineCount;
   }

   public static int rowGap() {
      return rowGap;
   }

   public static int blockGap() {
      return blockGap;
   }

   public static boolean showTranslation() {
      return showTranslation;
   }

   public static boolean showRomaji() {
      return showRomaji;
   }

   public static boolean tuningMode() {
      return tuningMode;
   }

   public static boolean dirty() {
      return dirty;
   }

   public static void snapshot() {
      snapScale = scale;
      snapAnchorX = anchorX;
      snapAnchorY = anchorY;
      snapShown = shown;
      snapLineCount = lineCount;
      snapRowGap = rowGap;
      snapBlockGap = blockGap;
      snapTranslation = showTranslation;
      snapRomaji = showRomaji;
   }

   public static boolean changed() {
      return snapScale != scale || snapAnchorX != anchorX || snapAnchorY != anchorY
            || snapShown != shown || snapLineCount != lineCount || snapRowGap != rowGap
            || snapBlockGap != blockGap || snapTranslation != showTranslation
            || snapRomaji != showRomaji;
   }

   public static void setScale(double value) {
      scale = clampDouble(value, 0.5, 2.0, scale);
      dirty = true;
   }

   public static void setAnchorX(double value) {
      anchorX = clampDouble(value, 0.0, 1.0, anchorX);
      dirty = true;
   }

   public static void setAnchorY(double value) {
      anchorY = clampDouble(value, 0.0, 1.0, anchorY);
      dirty = true;
   }

   public static void flipShown() {
      shown = !shown;
      dirty = true;
   }

   /** 行数在 1 → 2 → 3 → 1 间循环。 */
   public static void cycleLineCount() {
      lineCount = lineCount >= 3 ? 1 : lineCount + 1;
      dirty = true;
   }

   public static void setRowGap(int value) {
      rowGap = clampInt(value, 14, 48);
      dirty = true;
   }

   public static void setBlockGap(int value) {
      blockGap = clampInt(value, 6, 40);
      dirty = true;
   }

   public static void flipTranslation() {
      showTranslation = !showTranslation;
      FeatureSwitch.lyricTranslation = showTranslation;
      dirty = true;
   }

   public static void flipRomaji() {
      showRomaji = !showRomaji;
      FeatureSwitch.lyricRomaji = showRomaji;
      dirty = true;
   }

   /** 调试/调整模式：切换后立即持久化（该开关作为 UI 状态保存）。 */
   public static void flipTuningMode() {
      tuningMode = !tuningMode;
      save();
   }

   public static void restoreDefaults() {
      scale = 1.0;
      anchorX = 0.5;
      anchorY = 0.5;
      shown = true;
      lineCount = 3;
      rowGap = 24;
      blockGap = 14;
      showTranslation = true;
      showRomaji = true;
      FeatureSwitch.lyricTranslation = true;
      FeatureSwitch.lyricRomaji = true;
      dirty = true;
   }

   public static void save() {
      Properties p = new Properties();
      p.setProperty("scale", Double.toString(scale));
      p.setProperty("anchorX", Double.toString(anchorX));
      p.setProperty("anchorY", Double.toString(anchorY));
      p.setProperty("shown", Boolean.toString(shown));
      p.setProperty("lineCount", Integer.toString(lineCount));
      p.setProperty("rowGap", Integer.toString(rowGap));
      p.setProperty("blockGap", Integer.toString(blockGap));
      p.setProperty("showTranslation", Boolean.toString(showTranslation));
      p.setProperty("showRomaji", Boolean.toString(showRomaji));
      p.setProperty("tuningMode", Boolean.toString(tuningMode));
      try {
         Files.createDirectories(FILE.getParent());
         try (BufferedWriter writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy lyric HUD preferences");
         }
      } catch (IOException e) {
         TraceLog.warn("无法保存歌词 HUD 配置", e);
      }
      dirty = false;
      snapshot();
   }

   private static double clampDouble(String raw, double min, double max, double fallback) {
      try {
         return clampDouble(Double.parseDouble(raw.trim()), min, max, fallback);
      } catch (Exception e) {
         return fallback;
      }
   }

   private static double clampDouble(double v, double min, double max, double fallback) {
      if (Double.isNaN(v)) {
         return fallback;
      }
      return Math.max(min, Math.min(max, v));
   }

   private static int clampInt(String raw, int min, int max, int fallback) {
      try {
         return clampInt(Integer.parseInt(raw.trim()), min, max);
      } catch (Exception e) {
         return fallback;
      }
   }

   private static int clampInt(int v, int min, int max) {
      return Math.max(min, Math.min(max, v));
   }
}
