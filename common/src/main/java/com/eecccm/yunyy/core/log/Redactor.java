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

package com.eecccm.yunyy.core.log;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 全模组统一凭证 / 指纹脱敏工具（纯 JDK，core 层唯一口径）。
 *
 * <p>所有进入日志、聊天组件或崩溃报告的 URL、异常 message、键值文本，在落盘前都应经过
 * {@link #message(String)}；URL 推荐直接使用 {@link #url(String)} / {@link #uri(URI)}，
 * 只保留 host 与 path、剥离整段 query，从根上避免签名参数外泄。</p>
 *
 * <p>脱敏字段覆盖：账号会话（MUSIC_U / MUSIC_A / __csrf / NMTID / __remember_me / cookie /
 * access_token / token / skey / p_skey）、签名（sig / signature / key / musickey / secret）、
 * 设备指纹（dfid / mid / guid / mac / webgl / webglhash）。</p>
 */
public final class Redactor {

   /** 敏感键名（大小写不敏感），URL query、cookie、裸键值对统一命中。 */
   private static final String[] SENSITIVE_KEYS = {
         "token", "access_token", "skey", "p_skey", "sig", "signature", "key",
         "musickey", "music_key", "secret", "cookie", "set-cookie", "authorization",
         "proxy-authorization", "MUSIC_U", "MUSIC_A", "__csrf", "NMTID", "__remember_me",
         "dfid", "mid", "guid", "mac", "webgl", "webglhash", "webgl_hash", "device_token"
   };

   private static final String KEY_GROUP = String.join("|", SENSITIVE_KEYS);

   /** URL query / cookie 形式：?key=value、&key=value、;key=value。 */
   private static final Pattern QUERY_PAIR = Pattern.compile(
         "(?i)([?&;](" + KEY_GROUP + ")=)[^&\\s;]+");

   /** 裸键值对文本：日志中 `dfid=xxx`、`token: xxx` 等。 */
   private static final Pattern BARE_PAIR = Pattern.compile(
         "(?i)\\b(" + KEY_GROUP + ")\\s*[=:]\\s*[\\w.\\-+/=]{6,}");

   /** 凭证请求头整行脱敏。 */
   private static final Pattern HEADER_LINE = Pattern.compile(
         "(?i)(Cookie|Set-Cookie|Authorization|Proxy-Authorization)\\s*:[^\\r\\n]*");

   private Redactor() {
   }

   /** 对整段已组装日志消息做兜底脱敏；null 返回 "null"。 */
   public static String message(String raw) {
      if (raw == null || raw.isBlank()) {
         return raw == null ? "null" : raw;
      }
      String out = HEADER_LINE.matcher(raw).replaceAll("$1: ***");
      out = QUERY_PAIR.matcher(out).replaceAll("$1***");
      out = BARE_PAIR.matcher(out).replaceAll("$1=***");
      return out;
   }

   /** 脱敏 URL：只保留 scheme://host:port/path，剥离 query 与 fragment（签名参数不进日志）。 */
   public static String url(String raw) {
      if (raw == null || raw.isBlank()) {
         return "null";
      }
      try {
         return uri(URI.create(raw));
      } catch (Exception ignored) {
         return message(raw);
      }
   }

   /** 脱敏 URI：只保留 host 与 path（与历史 AccountSessionStore.redactUri 口径一致）。 */
   public static String uri(URI uri) {
      if (uri == null) {
         return "null";
      }
      String host = uri.getHost();
      String path = uri.getPath();
      if (host == null) {
         host = "";
      }
      if (path == null || path.isBlank()) {
         path = "/";
      }
      return host + path;
   }

   /** 提取根因类名 + 脱敏后的异常 message，截断 300 字符，供日志调用点使用。 */
   public static String throwableMessage(Throwable throwable) {
      if (throwable == null) {
         return "null";
      }
      Throwable root = throwable;
      while (root.getCause() != null) {
         root = root.getCause();
      }
      String text = root.getMessage();
      if (text == null || text.isBlank()) {
         return root.getClass().getSimpleName();
      }
      text = message(text);
      if (text.length() > 300) {
         text = text.substring(0, 300) + "…";
      }
      return root.getClass().getSimpleName() + ": " + text;
   }
}
