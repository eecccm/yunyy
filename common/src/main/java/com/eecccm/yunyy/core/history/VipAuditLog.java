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

package com.eecccm.yunyy.core.history;

import com.eecccm.yunyy.core.account.FileSecurity;
import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.log.Redactor;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.path.StorageLocations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * VIP 鉴权 / 权益使用审计日志（T-08）。
 *
 * <p>记录范围：VIP 歌曲入队、发起播放、账号 VIP 探测三类动作；字段为平台、账号标识、
 * songId、鉴权时间戳、会话来源、结果与中性备注。日志经 {@link Redactor} 脱敏，
 * 不落 Cookie / token / dfid / 签名参数；账号标识只允许传入 userid 或昵称，不允许传入凭证串。</p>
 *
 * <p>保留周期由服主在 {@code yunyy-server.properties} 配置（{@code auditRetentionDays}，
 * 默认 30 天）；写入时按保留周期滚动剔除过期行。审计开关 {@code auditEnabled} 默认开启，
 * 关闭时所有记录调用直接返回，不写盘。</p>
 *
 * <p>纯 JDK 实现，core 层不接触 MC 组件；日志文件与凭证文件同目录，落盘后由
 * {@link FileSecurity} 收紧本地权限。</p>
 */
public final class VipAuditLog {

   private static final Path AUDIT_FILE =
         StorageLocations.configDir().resolve("yunyy-vip-audit.log");

   /** 单文件大小上限（5MB），超出时仅保留尾部记录，防止审计日志撑爆配置目录。 */
   private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

   private static final Object LOCK = new Object();

   private VipAuditLog() {
   }

   /** 会话来源：公共队列入队。 */
   public static final String SOURCE_QUEUE = "queue";
   /** 会话来源：发起播放。 */
   public static final String SOURCE_PLAY = "play";
   /** 会话来源：账号 VIP 探测 / 校验。 */
   public static final String SOURCE_PROBE = "probe";

   /** 结果：鉴权通过 / 权益放行。 */
   public static final String RESULT_ALLOWED = "allowed";
   /** 结果：鉴权拒绝 / 权益不足。 */
   public static final String RESULT_DENIED = "denied";
   /** 结果：仅探测记录，不代表放行或拒绝。 */
   public static final String RESULT_PROBE = "probe";

   /**
    * 写入一条审计记录。
    *
    * @param platformTag 平台短标识（wyy / kg），来自 {@code MusicVendor.tag}
    * @param accountRef 账号标识（userid 或昵称；禁止传入 Cookie / token）
    * @param songId      歌曲 ID；探测类记录可为空串
    * @param source      会话来源（{@link #SOURCE_QUEUE} / {@link #SOURCE_PLAY} / {@link #SOURCE_PROBE}）
    * @param result      结果（{@link #RESULT_ALLOWED} / {@link #RESULT_DENIED} / {@link #RESULT_PROBE}）
    * @param note        中性备注（错误码 / 权益类型等；不允许携带明文凭证）
    */
   public static void record(String platformTag, String accountRef, String songId,
                             String source, String result, String note) {
      if (!ServerPolicy.auditEnabled) {
         return;
      }
      try {
         synchronized (LOCK) {
            rotateIfNeeded();
            String line = formatLine(platformTag, accountRef, songId, source, result, note);
            Files.createDirectories(AUDIT_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(AUDIT_FILE, StandardCharsets.UTF_8,
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.APPEND)) {
               writer.write(line);
               writer.newLine();
            }
            FileSecurity.hardenFile(AUDIT_FILE);
            FileSecurity.hardenDirectory(AUDIT_FILE.getParent());
         }
      } catch (Exception e) {
         // 审计写盘失败不得阻断点歌 / 播放主流程；仅写开发者日志。
         TraceLog.warn("VIP 审计日志写入失败：{}", Redactor.throwableMessage(e));
      }
   }

   private static String formatLine(String platformTag, String accountRef, String songId,
                                    String source, String result, String note) {
      String platform = sanitize(platformTag, "unknown", 16);
      String account = sanitize(accountRef, "-", 32);
      String song = sanitize(songId, "-", 32);
      String origin = sanitize(source, "-", 12);
      String outcome = sanitize(result, "-", 12);
      String detail = Redactor.message(sanitize(note, "", 200));
      return "[" + Instant.now().toString() + "]"
            + " platform=" + platform
            + " account=" + account
            + " song=" + song
            + " source=" + origin
            + " result=" + outcome
            + (detail.isBlank() ? "" : " note=" + detail);
   }

   private static String sanitize(String raw, String fallback, int maxLen) {
      if (raw == null || raw.isBlank()) {
         return fallback;
      }
      String cleaned = raw.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").trim();
      if (cleaned.isBlank()) {
         return fallback;
      }
      return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
   }

   /** 按保留天数滚动剔除过期行；文件超上限时保留尾部记录。 */
   private static void rotateIfNeeded() {
      if (!Files.isRegularFile(AUDIT_FILE)) {
         return;
      }
      long retentionMillis = Math.max(1L, ServerPolicy.auditRetentionDays) * 24L * 3600L * 1000L;
      long cutoff = System.currentTimeMillis() - retentionMillis;
      List<String> kept = new ArrayList<>();
      try (BufferedReader reader = Files.newBufferedReader(AUDIT_FILE, StandardCharsets.UTF_8)) {
         String line;
         while ((line = reader.readLine()) != null) {
            if (!line.isBlank() && !isExpired(line, cutoff)) {
               kept.add(line);
            }
         }
      } catch (Exception e) {
         TraceLog.debug("VIP 审计日志滚动读取失败，保留原文件：{}", Redactor.throwableMessage(e));
         return;
      }
      // 文件超上限时保留尾部记录。
      long bytes = 0L;
      int start = 0;
      for (int i = kept.size() - 1; i >= 0; i--) {
         bytes += kept.get(i).getBytes(StandardCharsets.UTF_8).length;
         if (bytes > MAX_FILE_BYTES) {
            start = i + 1;
            break;
         }
      }
      if (start > 0) {
         kept = new ArrayList<>(kept.subList(start, kept.size()));
      }
      try (BufferedWriter writer = Files.newBufferedWriter(AUDIT_FILE, StandardCharsets.UTF_8)) {
         for (String line : kept) {
            writer.write(line);
            writer.newLine();
         }
      } catch (Exception e) {
         TraceLog.debug("VIP 审计日志滚动写回失败：{}", Redactor.throwableMessage(e));
      }
   }

   private static boolean isExpired(String line, long cutoff) {
      int open = line.indexOf('[');
      int close = line.indexOf(']');
      if (open < 0 || close <= open) {
         return false; // 无法解析时间戳的历史行予以保留。
      }
      try {
         Instant instant = Instant.parse(line.substring(open + 1, close));
         return instant.toEpochMilli() < cutoff;
      } catch (DateTimeParseException ignored) {
         return false;
      }
   }
}
