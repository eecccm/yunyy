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

package com.eecccm.yunyy.core.account;

import com.eecccm.yunyy.core.error.QrStage;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.net.SafeHttpClient;
import com.eecccm.yunyy.core.net.ReservedAddressGuard;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云桌面客户端扫码登录流程（EAPI 加密通道）。
 *
 * <p>流程：{@link #begin()} 换取 unikey → 界面渲染二维码 → {@link #poll()}
 * 轮询登录结果，成功后 cookie 已并入会话 jar，可直接作为登录凭证。</p>
 */
public final class QrFlow {

   // ---- 网易云桌面端协议常量（接口侧固定值，不可改动）----
   private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
         + " (KHTML, like Gecko) Safari/537.36 Chrome/122.0.0.0 NeteaseMusicDesktop/3.1.20.204558";
   private static final String APP_VER = "3.1.20.204558";
   private static final String OS_VER = "Microsoft-Windows-10-Professional-build-22631-64bit";
   private static final String EAPI_KEY = "e82ckenh8dichen8";
   private static final String EAPI_SEP = "-36cd479b6b5-";
   private static final SecureRandom RANDOM = new SecureRandom();

   /**
    * 扫码会话专用直连客户端：凭证通道不挂代理，重定向策略与全局 SafeHttpClient 一致（NEVER），
    * 避免凭证头被自动跟随到第三方域。
    *
    * <p>单例复用：{@link HttpClient} 自带连接池与 selector 线程，若每个扫码会话各建一个，
    * 每次打开扫码界面都会留下一个待 GC 的客户端与其线程。</p>
    */
   private static final HttpClient CLIENT = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(10L))
         .followRedirects(HttpClient.Redirect.NEVER)
         .build();

   private final HttpClient client = CLIENT;
   private final Map<String, String> jar = new LinkedHashMap<>();
   private final String deviceToken = randomToken(26).toUpperCase(Locale.ROOT);
   /** 扫码会话单次消耗标记（T-05）：成功 / 过期后置位，禁止同一会话重放换取凭证。 */
   private final AtomicBoolean finalized = new AtomicBoolean(false);
   private String sessionKey = "";

   public QrFlow() {
      // 目标域名由平台接口硬编码返回，白名单在 postEncrypted 校验。
      seedCookies();
   }

   /** 创建扫码会话，返回扫码 URL。 */
   public BeginResult begin() throws Exception {
      JsonObject data = new JsonObject();
      data.addProperty("type", 1);
      data.addProperty("header", signedHeader().toString());
      JsonObject resp = postEncrypted("/api/login/qrcode/unikey", data);
      int code = asInt(resp, "code", 0);
      String unikey = asText(resp, "unikey");
      if (unikey.isBlank() && resp.has("data") && resp.get("data").isJsonObject()) {
         unikey = asText(resp.getAsJsonObject("data"), "unikey");
      }
      if (code != 200 || unikey.isBlank()) {
         TraceLog.warn("网易云未返回二维码 key：code={}", code);
         throw new VendorException(VendorError.QR_KEY_MISSING, MusicVendor.NETEASE.label);
      }
      sessionKey = unikey;
      finalized.set(false);
      TraceLog.info("已创建网易云桌面客户端扫码登录会话");
      return new BeginResult("https://music.163.com/login?codekey=" + encode(sessionKey));
   }

   /** 轮询登录状态；成功后返回的 cookie 即登录凭证。 */
   public PollResult poll() throws Exception {
      if (sessionKey.isBlank() || finalized.get()) {
         throw new VendorException(VendorError.QR_SESSION_MISSING);
      }
      JsonObject data = new JsonObject();
      data.addProperty("key", sessionKey);
      data.addProperty("type", 1);
      data.addProperty("header", signedHeader().toString());
      JsonObject resp = postEncrypted("/api/login/qrcode/client/login", data);
      int code = asInt(resp, "code", 0);
      String message = asText(resp, "message");
      String returnedCookie = asText(resp, "cookie");
      if (!returnedCookie.isBlank()) {
         absorbCookieLine(returnedCookie);
      }
      TraceLog.info("网易云扫码登录状态：{}{}", code, message.isBlank() ? "" : "（" + message + "）");
      PollResult result = switch (code) {
         case 801 -> PollResult.waiting();
         case 802 -> PollResult.scanned();
         case 803 -> PollResult.success(cookieLine());
         case 800 -> PollResult.expired();
         default -> PollResult.failed(VendorError.UNKNOWN);
      };
      // 成功 / 过期 / 失败后原子消耗该会话，禁止同一二维码 key 被重复轮询换取凭证。
      if (result.stage() != QrStage.WAIT_SCAN && result.stage() != QrStage.SCANNED) {
         finalized.set(true);
      }
      return result;
   }

   private JsonObject postEncrypted(String apiPath, JsonObject data) throws Exception {
      String endpoint = "https://interface3.music.163.com/eapi" + apiPath.substring("/api".length());
      URI uri = URI.create(endpoint);
      // T-05：目标固定为网易云 EAPI 白名单域；DNS 解析后复核非内网 / 保留段，防 rebinding。
      if (!"interface3.music.163.com".equals(uri.getHost())
            || !ReservedAddressGuard.isPublicReachable(uri.getHost())) {
         TraceLog.warn("拒绝扫码登录非白名单 / 保留段目标：{}", uri.getHost());
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      String body = "params=" + wrapEapi(apiPath, data.toString());
      HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15L))
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieLine())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
      HttpResponse<byte[]> raw = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      absorbSetCookies(raw.headers().allValues("set-cookie"));
      byte[] rawBody = raw.body();
      String text = SafeHttpClient.decodeText(raw, rawBody);
      MemoryWipe.wipe(rawBody);
      HttpResponse<String> response = SafeHttpClient.wrapString(raw, text);
      if (response.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
      }
      try {
         return JsonParser.parseString(response.body()).getAsJsonObject();
      } catch (RuntimeException e) {
         throw new VendorException(VendorError.QR_BAD_PAYLOAD, e, MusicVendor.NETEASE.label);
      }
   }

   private void seedCookies() {
      jar.put("os", "pc");
      jar.put("appver", APP_VER);
      jar.put("osver", OS_VER);
      jar.put("deviceId", deviceToken);
   }

   private JsonObject signedHeader() {
      JsonObject header = new JsonObject();
      header.addProperty("os", "pc");
      header.addProperty("appver", APP_VER);
      header.addProperty("osver", OS_VER);
      header.addProperty("deviceId", deviceToken);
      header.addProperty("requestId",
            System.currentTimeMillis() + "_" + "%04d".formatted(RANDOM.nextInt(10000)));
      return header;
   }

   private void absorbSetCookies(List<String> setCookies) {
      for (String header : setCookies) {
         absorbCookiePart(header.split(";", 2)[0]);
      }
   }

   private void absorbCookieLine(String header) {
      for (String part : header.split(";")) {
         absorbCookiePart(part);
      }
   }

   private void absorbCookiePart(String part) {
      int eq = part.indexOf('=');
      if (eq > 0) {
         String name = part.substring(0, eq).trim();
         String value = part.substring(eq + 1).trim();
         if (!name.isBlank() && !value.isBlank()) {
            jar.put(name, value);
         }
      }
   }

   private String cookieLine() {
      return String.join("; ", jar.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).toList());
   }

   /** 网易云 EAPI 加密：MD5 摘要拼接后 AES/ECB 加密。 */
   private static String wrapEapi(String apiPath, String json) throws Exception {
      String digest = toHex(MessageDigest.getInstance("MD5")
            .digest(("nobody" + apiPath + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
      String value = apiPath + EAPI_SEP + json + EAPI_SEP + digest;
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
      return toHex(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
   }

   private static String randomToken(int bytes) {
      byte[] data = new byte[bytes];
      RANDOM.nextBytes(data);
      return toHex(data);
   }

   private static String toHex(byte[] data) {
      StringBuilder sb = new StringBuilder(data.length * 2);
      for (byte b : data) {
         sb.append("%02x".formatted(b & 0xFF));
      }
      return sb.toString();
   }

   private static String encode(String value) {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }

   private static int asInt(JsonObject obj, String key, int fallback) {
      try {
         return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
      } catch (RuntimeException e) {
         return fallback;
      }
   }

   private static String asText(JsonObject obj, String key) {
      try {
         return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
      } catch (RuntimeException e) {
         return "";
      }
   }

   /** 扫码 URL 创建结果。 */
   public record BeginResult(String url) {
   }

   /**
    * 轮询结果：阶段 + 中性错误码 + 结构化参数 + 登录 cookie。界面按 {@link QrStage}
    * 映射语言模板，不消费原始中文状态句子。
    */
   public record PollResult(QrStage stage, VendorError error, Object[] args, String cookie) {

      public static PollResult waiting() {
         return new PollResult(QrStage.WAIT_SCAN, null, new Object[0], "");
      }

      public static PollResult scanned() {
         return new PollResult(QrStage.SCANNED, null, new Object[0], "");
      }

      public static PollResult success(String cookie) {
         return new PollResult(QrStage.SUCCESS, null, new Object[0], cookie == null ? "" : cookie);
      }

      public static PollResult expired() {
         return new PollResult(QrStage.EXPIRED, null, new Object[0], "");
      }

      public static PollResult failed(VendorError error, Object... args) {
         return new PollResult(QrStage.FAILED, error == null ? VendorError.UNKNOWN : error,
               args == null ? new Object[0] : args, "");
      }
   }
}
