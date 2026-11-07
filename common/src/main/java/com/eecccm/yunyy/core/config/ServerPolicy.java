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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 服务端可配置策略（config/yunyy-server.properties），服主无需改源码即可调整。
 *
 * <p>权限模型：内置 OP 等级校验（默认 2 级），跨电脑开箱即用，不强制依赖外部权限库。</p>
 */
public final class ServerPolicy {

   public static volatile int queueCap = 500;
   public static volatile long searchCooldown = 750L;
   public static volatile long importCooldown = 5000L;
   /** 管理操作所需 OP 等级（0~4，默认 2）。 */
   public static volatile int adminRank = 2;
   /** 切换播放模式是否需要管理员权限。 */
   public static volatile boolean modeSwitchNeedsAdmin = true;
   /** 调试面板是否需要管理员权限。 */
   public static volatile boolean tuningNeedsAdmin = true;
   /** 全局播放控制（下一首 / 暂停恢复 / 队列插队排序 / 上一首）是否需要管理员权限。 */
   public static volatile boolean controlNeedsAdmin = true;
   /** 单个玩家在公共队列中允许同时存在的曲目上限（含当前播放）。 */
   public static volatile int maxPerPlayer = 20;
   /** VIP 鉴权 / 权益使用审计开关（T-08）。 */
   public static volatile boolean auditEnabled = true;
   /** 审计日志保留天数（T-08），超出天数的记录在写入时滚动剔除。 */
   public static volatile int auditRetentionDays = 30;
   /** DNS 解析结果缓存秒数（T-07，5~300 秒）。 */
   public static volatile long dnsCacheTtlSeconds = 60L;

   private ServerPolicy() {
   }

   public static void load() {
      Path file = StorageLocations.configDir().resolve("yunyy-server.properties");
      if (!Files.isRegularFile(file)) {
         writeDefaults(file);
         return;
      }
      Properties p = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
         p.load(reader);
      } catch (Exception e) {
         TraceLog.warn("无法读取服务端配置，使用默认值", e);
      }
      queueCap = clampInt(p.getProperty("queueCap", "500"), 1, 5000, 500);
      searchCooldown = clampLong(p.getProperty("searchCooldownMs", "750"), 0L, 60000L, 750L);
      importCooldown = clampLong(p.getProperty("importCooldownMs", "5000"), 0L, 600000L, 5000L);
      adminRank = clampInt(p.getProperty("adminRank", "2"), 0, 4, 2);
      modeSwitchNeedsAdmin = parseBool(p.getProperty("modeSwitchNeedsAdmin", "true"), true);
      tuningNeedsAdmin = parseBool(p.getProperty("tuningNeedsAdmin", "true"), true);
      controlNeedsAdmin = parseBool(p.getProperty("controlNeedsAdmin", "true"), true);
      maxPerPlayer = clampInt(p.getProperty("maxPerPlayer", "20"), 1, 5000, 20);
      auditEnabled = parseBool(p.getProperty("auditEnabled", "true"), true);
      auditRetentionDays = clampInt(p.getProperty("auditRetentionDays", "30"), 1, 3650, 30);
      dnsCacheTtlSeconds = clampLong(p.getProperty("dnsCacheTtlSeconds", "60"), 5L, 300L, 60L);
   }

   private static void writeDefaults(Path file) {
      try {
         Files.createDirectories(file.getParent());
         Properties p = new Properties();
         p.setProperty("queueCap", "500");
         p.setProperty("searchCooldownMs", "750");
         p.setProperty("importCooldownMs", "5000");
         p.setProperty("adminRank", "2");
         p.setProperty("modeSwitchNeedsAdmin", "true");
         p.setProperty("tuningNeedsAdmin", "true");
         p.setProperty("controlNeedsAdmin", "true");
         p.setProperty("maxPerPlayer", "20");
         p.setProperty("auditEnabled", "true");
         p.setProperty("auditRetentionDays", "30");
         p.setProperty("dnsCacheTtlSeconds", "60");
         try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy server policy - edit and restart");
         }
      } catch (Exception e) {
         TraceLog.warn("无法写入服务端默认配置", e);
      }
   }

   private static int clampInt(String raw, int min, int max, int fallback) {
      try {
         int v = Integer.parseInt(raw.trim());
         return Math.max(min, Math.min(max, v));
      } catch (Exception e) {
         return fallback;
      }
   }

   private static long clampLong(String raw, long min, long max, long fallback) {
      try {
         long v = Long.parseLong(raw.trim());
         return Math.max(min, Math.min(max, v));
      } catch (Exception e) {
         return fallback;
      }
   }

   private static boolean parseBool(String raw, boolean fallback) {
      if (raw == null) {
         return fallback;
      }
      String v = raw.trim().toLowerCase();
      if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1")) {
         return true;
      }
      if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) {
         return false;
      }
      return fallback;
   }
}
