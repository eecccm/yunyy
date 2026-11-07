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
 * 播放信息 HUD 偏好（位置 / 缩放 / 可见性）。
 *
 * <p>修改只写内存并置脏标记，由界面显式保存；打开界面时做快照，
 * 退出时与快照比较以精确判断是否真的改动过（避免拖回原位仍提示未保存）。</p>
 */
public final class HudPreferences {

   private static final Path FILE = StorageLocations.configDir().resolve("yunyy-hud.properties");

   private static double scale = 1.0;
   private static double anchorX = 1.0;   // 水平锚点：0=靠左 … 1=靠右
   private static double anchorY = 0.0;   // 垂直锚点：0=顶部 … 1=底部
   private static boolean shown = true;
   private static boolean dirty = false;

   private static double snapScale = 1.0;
   private static double snapAnchorX = 1.0;
   private static double snapAnchorY = 0.0;
   private static boolean snapShown = true;

   private HudPreferences() {
   }

   public static void load() {
      if (!Files.isRegularFile(FILE)) {
         return;
      }
      Properties p = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
         p.load(reader);
         scale = clampDouble(p.getProperty("scale", "1.0"), 0.5, 2.0, 1.0);
         anchorX = clampDouble(p.getProperty("anchorX", "1.0"), 0.0, 1.0, 1.0);
         anchorY = clampDouble(p.getProperty("anchorY", "0.0"), 0.0, 1.0, 0.0);
         shown = Boolean.parseBoolean(p.getProperty("shown", "true"));
      } catch (Exception e) {
         TraceLog.warn("无法读取 HUD 配置，已使用默认值", e);
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

   public static boolean dirty() {
      return dirty;
   }

   /** 打开设置界面时记录初始值，供退出比较。 */
   public static void snapshot() {
      snapScale = scale;
      snapAnchorX = anchorX;
      snapAnchorY = anchorY;
      snapShown = shown;
   }

   /** 与打开时快照相比是否有真实变化。 */
   public static boolean changed() {
      return snapScale != scale || snapAnchorX != anchorX || snapAnchorY != anchorY || snapShown != shown;
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

   public static void restoreDefaults() {
      scale = 1.0;
      anchorX = 1.0;
      anchorY = 0.0;
      shown = true;
      dirty = true;
   }

   public static void save() {
      Properties p = new Properties();
      p.setProperty("scale", Double.toString(scale));
      p.setProperty("anchorX", Double.toString(anchorX));
      p.setProperty("anchorY", Double.toString(anchorY));
      p.setProperty("shown", Boolean.toString(shown));
      try {
         Files.createDirectories(FILE.getParent());
         try (BufferedWriter writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy HUD preferences");
         }
      } catch (IOException e) {
         TraceLog.warn("无法保存 HUD 配置", e);
      }
      dirty = false;
      snapshot();
   }

   private static double clampDouble(String raw, double min, double max, double fallback) {
      try {
         double v = Double.parseDouble(raw.trim());
         return Math.max(min, Math.min(max, v));
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
}
