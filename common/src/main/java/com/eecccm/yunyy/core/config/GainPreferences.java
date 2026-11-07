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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 音量偏好：0~100 整数，含静音前记忆（静音/恢复不丢原音量）。
 */
public final class GainPreferences {

   private static final Path FILE = StorageLocations.configDir().resolve("yunyy.properties");

   private static volatile int level = 70;
   private static volatile int preMuteLevel = 70;

   private GainPreferences() {
   }

   public static void load() {
      if (!Files.isRegularFile(FILE)) {
         return;
      }
      Properties p = new Properties();
      try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
         p.load(reader);
         level = clamp(Integer.parseInt(p.getProperty("volume", "70").trim()));
         preMuteLevel = clamp(Integer.parseInt(p.getProperty("volumeBeforeMute", "70").trim()));
         if (preMuteLevel == 0) {
            preMuteLevel = 70;
         }
      } catch (Exception e) {
         TraceLog.warn("无法读取音量配置，将使用默认音量", e);
      }
   }

   public static int level() {
      return level;
   }

   /** 归一化增益（0.0 ~ 1.0），供音频设备换算分贝。 */
   public static float gain() {
      return level / 100.0F;
   }

   public static int adjustLevel(int value) {
      level = clamp(value);
      if (level > 0) {
         preMuteLevel = level;
      }
      persist();
      return level;
   }

   /** 静音 / 恢复切换，返回切换后的音量。 */
   public static int flipMute() {
      if (level == 0) {
         level = preMuteLevel > 0 ? preMuteLevel : 70;
      } else {
         preMuteLevel = level;
         level = 0;
      }
      persist();
      return level;
   }

   private static int clamp(int v) {
      return Math.max(0, Math.min(100, v));
   }

   private static void persist() {
      Properties p = new Properties();
      p.setProperty("volume", Integer.toString(level));
      p.setProperty("volumeBeforeMute", Integer.toString(preMuteLevel));
      try {
         Files.createDirectories(FILE.getParent());
         try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy client settings");
         }
      } catch (IOException e) {
         TraceLog.warn("无法保存音量配置", e);
      }
   }
}
