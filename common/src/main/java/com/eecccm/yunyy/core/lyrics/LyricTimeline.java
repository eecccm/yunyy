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

package com.eecccm.yunyy.core.lyrics;

import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.platform.VendorRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词时间轴：拉取 → 解析 → 缓存 → 按播放位置取窗口。
 *
 * <p>歌词以「段」组织：单段=原文；多段用 \u0001 分隔（音译\u0001原文\u0001翻译），
 * HUD 层按该结构决定显示哪几段。缓存上限 128 首，防内存膨胀。</p>
 */
public final class LyricTimeline {

   /** 行内时间戳：分钟:秒(.毫秒) 或 分钟:秒:毫秒。 */
   private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

   /** 歌词缓存 TTL：12 小时（歌词内容低频变化）；容量上限 128 首，超限整体清空。 */
   private static final long CACHE_TTL_MILLIS = Duration.ofHours(12L).toMillis();
   private static final int CACHE_CAPACITY = 128;

   private record CachedLyrics(List<Verse> lines, long loadedAtMillis) {
   }

   private static final Map<String, CachedLyrics> cache = new ConcurrentHashMap<>();
   private static volatile String loadingKey = "";
   private static volatile List<Verse> currentLines = List.of();

   private LyricTimeline() {
   }

   /** 异步加载指定歌曲歌词；重复请求命中未过期缓存。 */
   public static void loadFor(String providerTag, String songId) {
      wipe();
      String key = (providerTag == null ? MusicVendor.NETEASE.tag : providerTag) + ":" + songId;
      if (songId == null || songId.isBlank()) {
         return;
      }
      loadingKey = key;
      CachedLyrics cached = cache.get(key);
      if (cached != null) {
         if (System.currentTimeMillis() - cached.loadedAtMillis() < CACHE_TTL_MILLIS) {
            currentLines = cached.lines();
            return;
         }
         cache.remove(key);
      }
      CompletableFuture.supplyAsync(() -> fetchRemote(providerTag, songId))
            .whenComplete((loaded, error) -> {
               if (!key.equals(loadingKey)) {
                  return;
               }
               if (error != null) {
                  TraceLog.warn("读取歌词失败：{}", key, error);
                  currentLines = List.of();
                  return;
               }
               if (loaded != null && !loaded.isEmpty()) {
                  if (cache.size() >= CACHE_CAPACITY) {
                     cache.clear();
                  }
                  cache.put(key, new CachedLyrics(loaded, System.currentTimeMillis()));
               }
               currentLines = loaded == null ? List.of() : loaded;
            });
   }

   /** 清空当前歌词与加载标记。 */
   public static void wipe() {
      loadingKey = "";
      currentLines = List.of();
   }

   /** 整首歌词是否完全没有音译段（仅 3 段格式含独立音译段，且首段为空才算无）。 */
   public static boolean missingRomaji() {
      List<Verse> snapshot = currentLines;
      if (snapshot.isEmpty()) {
         return true;
      }
      for (Verse v : snapshot) {
         String text = v.text();
         if (text == null || text.isBlank()) {
            continue;
         }
         String[] segs = text.split("\u0001", -1);
         if (segs.length >= 3 && !segs[0].trim().isBlank()) {
            return false;
         }
      }
      return true;
   }

   /** 整首歌词是否完全没有翻译段（末段为空才算无）。 */
   public static boolean missingTranslation() {
      List<Verse> snapshot = currentLines;
      if (snapshot.isEmpty()) {
         return true;
      }
      for (Verse v : snapshot) {
         String text = v.text();
         if (text == null || text.isBlank()) {
            continue;
         }
         String[] segs = text.split("\u0001", -1);
         if (segs.length > 1 && !segs[segs.length - 1].trim().isBlank()) {
            return false;
         }
      }
      return true;
   }

   /**
    * 取播放位置对应的歌词窗口（上一行 / 当前行 / 下一行）。
    * 当前行之前无行时 previous 为空；之后无行时 next 为空；无歌词返回 null。
    */
   public static Viewport viewport(long elapsedMillis) {
      List<Verse> snapshot = currentLines;
      if (snapshot.isEmpty()) {
         return null;
      }
      int current = -1;
      int i = 0;
      while (i < snapshot.size() && snapshot.get(i).atMs() <= elapsedMillis) {
         current = i++;
      }
      if (current < 0) {
         return new Viewport("", "♪", snapshot.get(0).text());
      }
      return new Viewport(
            current > 0 ? snapshot.get(current - 1).text() : "",
            snapshot.get(current).text(),
            current + 1 < snapshot.size() ? snapshot.get(current + 1).text() : "");
   }

   private static List<Verse> fetchRemote(String providerTag, String songId) {
      try {
         String lrc = VendorRegistry.fetchLyrics(MusicVendor.byTag(providerTag), songId).join();
         if (lrc == null || lrc.isBlank()) {
            throw new IllegalStateException("无歌词");
         }
         return assemble(lrc);
      } catch (Exception e) {
         throw new IllegalStateException("歌词请求失败", e);
      }
   }

   /** 解析 LRC：以主歌词时间轴为基准合并翻译（当前实现只使用主歌词）。 */
   private static List<Verse> assemble(String original) {
      Map<Long, String> main = parseTimedRows(original);
      List<Verse> result = new ArrayList<>();
      for (Map.Entry<Long, String> entry : main.entrySet()) {
         String text = sanitize(entry.getValue());
         if (!text.isBlank()) {
            result.add(new Verse(entry.getKey(), text));
         }
      }
      result.sort(Comparator.comparingLong(Verse::atMs));
      return List.copyOf(result);
   }

   /** 解析 LRC 文本行为时间→歌词映射；同一行多个时间戳映射到同一文本。 */
   private static Map<Long, String> parseTimedRows(String lrc) {
      Map<Long, String> result = new LinkedHashMap<>();
      if (lrc == null || lrc.isBlank()) {
         return result;
      }
      for (String row : lrc.split("\\R")) {
         Matcher matcher = TIME.matcher(row);
         int lastEnd = -1;
         List<Long> times = new ArrayList<>();
         while (matcher.find()) {
            long minutes = Long.parseLong(matcher.group(1));
            long seconds = Long.parseLong(matcher.group(2));
            String fraction = matcher.group(3);
            long millis;
            if (fraction == null) {
               millis = 0L;
            } else {
               millis = switch (fraction.length()) {
                  case 1 -> Long.parseLong(fraction) * 100L;
                  case 2 -> Long.parseLong(fraction) * 10L;
                  default -> Long.parseLong(fraction.substring(0, 3));
               };
            }
            times.add((minutes * 60L + seconds) * 1000L + millis);
            lastEnd = matcher.end();
         }
         if (lastEnd >= 0) {
            String text = row.substring(lastEnd).trim();
            for (long time : times) {
               result.put(time, text);
            }
         }
      }
      return result;
   }

   /** 清理歌词文本：去除控制字符与 §，压缩空白，单段截断 240 字符；段分隔符 \u0001 保留。 */
   private static String sanitize(String text) {
      if (text == null) {
         return "";
      }
      String[] segs = text.split("\u0001", -1);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < segs.length; i++) {
         String s = segs[i].replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").trim();
         if (s.length() > 240) {
            s = s.substring(0, 240);
         }
         if (i > 0) {
            sb.append('\u0001');
         }
         sb.append(s);
      }
      return sb.toString();
   }

   /** 歌词行：时间戳 + 段文本。 */
   private record Verse(long atMs, String text) {
   }

   /** 播放窗口：上一行 / 当前行 / 下一行。 */
   public record Viewport(String previous, String current, String next) {
   }
}
