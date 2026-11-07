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

package com.eecccm.yunyy.core.net;

import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.Redactor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSession;

/**
 * 共享 HTTP 凭证 / 账号通道（一律直连，不挂代理）。
 *
 * <p>安全策略（跨平台、纯 JDK）：
 * <ul>
 *   <li>初始请求与重定向目标均做校验：https 放行；http 明文仅允许音频 CDN 白名单
 *       {@link #isMediaCdnHost}——防任意明文外联与 SSRF；</li>
 *   <li>{@link #send} 禁用自动跟随重定向（NEVER），GET 3xx 手动处理，最多 6 跳（H2/H1.1 交替）；</li>
 *   <li>同源（scheme+host 相同）重定向保留全部请求头；跨源一律剥离凭证头
 *       （Cookie/Authorization/Proxy-Authorization），且仅放行 https 目标或音频 CDN
 *       白名单内的 http 目标——防止带凭证请求被重定向到第三方域；</li>
 *   <li>{@link #downloadBytes} 供无凭证的 byte 下载使用（歌词/封面等），手动跟随、逐跳校验，
 *       调用方不得携带凭证。</li>
 * </ul>
 * 凭证 / 账号通道一律直连，不挂代理；代理仅由音频 CDN 通道与无凭证封面下载各自显式挂载。</p>
 */
public final class SafeHttpClient {

   private SafeHttpClient() {
   }

   /**
    * 凭证 / 账号通道一律直连，不挂全局代理：Cookie、token、dfid 不得经第三方代理中转。
    * 代理仅由音频 CDN 通道（PlaybackEngine 403 重试）与无凭证封面下载各自显式挂载。
    */
   private static HttpClient buildClient(boolean http2) {
      HttpClient.Builder b = HttpClient.newBuilder()
            .version(http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);
      return b.build();
   }

   /**
    * 两条常驻客户端：{@link HttpClient} 线程安全且自带连接池与 selector 线程，
    * 复用后重试与并发下载共享受限的连接数。
    *
    * <p>旧实现每次请求都 {@code newBuilder().build()}，一次重定向下载最多产生 3 个、
    * 六次重试最多产生 6 个独立客户端，每个都持有自己的 selector 线程与连接池，
    * 只能等 GC 回收——歌词/封面属于高频调用，会持续堆积线程与文件描述符。</p>
    */
   private static final HttpClient CLIENT_HTTP2 = buildClient(true);
   private static final HttpClient CLIENT_HTTP1 = buildClient(false);

   /**
    * 无凭证 byte 下载通道（HTTP/1.1）。初始目标与每跳重定向目标都做 scheme + 内网段 +
    * 明文 CDN 白名单校验，手动跟随最多 3 跳；不携带 Cookie / Authorization。
    * 响应体上限 {@code maxBytes}，超出判定为异常响应。
    */
   public static HttpResponse<byte[]> downloadBytes(HttpRequest request, int maxBytes)
         throws IOException, InterruptedException {
      permitDownloadTarget(request.uri());
      HttpRequest current = request;
      for (int hop = 0; hop < 3; hop++) {
         HttpResponse<byte[]> resp = CLIENT_HTTP1.send(current, HttpResponse.BodyHandlers.ofByteArray());
         if (resp.body().length > maxBytes) {
            TraceLog.warn("Download body exceeds {} bytes: {}", maxBytes, redactUri(current.uri()));
            throw new VendorException(VendorError.NETWORK_FAILURE);
         }
         if (resp.statusCode() >= 300 && resp.statusCode() < 400
               && "GET".equalsIgnoreCase(current.method())) {
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
               return resp;
            }
            URI next;
            try {
               next = current.uri().resolve(location);
            } catch (IllegalArgumentException invalidLoc) {
               return resp;
            }
            if (!permitDownloadTarget(next)) {
               TraceLog.warn("拒绝不安全的下载重定向：{} -> {}（https 或音频 CDN 白名单，内网除外）",
                     redactUri(current.uri()), redactUri(next));
               return resp;
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(15));
            current.headers().map().forEach((name, values) -> values.forEach(value -> {
               if (!isCredentialHeader(name)) {
                  builder.header(name, value);
               }
            }));
            current = builder.GET().build();
            continue;
         }
         return resp;
      }
      throw new VendorException(VendorError.NETWORK_FAILURE);
   }

   /** 下载通道目标校验：https 放行（DNS 解析后非保留段）；http 明文仅音频 CDN 白名单域且非内网。 */
   private static boolean permitDownloadTarget(URI uri) {
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null || isReservedPrivateHost(host)) {
         return false;
      }
      if (!ReservedAddressGuard.isPublicReachable(host)) {
         TraceLog.warn("拒绝解析到保留 / 内网段的下载目标：{}", redactUri(uri));
         return false;
      }
      if ("https".equalsIgnoreCase(scheme)) {
         return true;
      }
      return "http".equalsIgnoreCase(scheme) && isMediaCdnHost(host);
   }

   /** JSON / 凭证 / 下载通道响应体上限（2MB），超出判定为异常响应，避免恶意平台响应撑爆堆内存。 */
   public static final int DOWNLOAD_BODY_BYTES = 2 * 1024 * 1024;

   /** HTTP/2 与 HTTP/1.1 共存：优先 HTTP/2，连接失败换 HTTP/1.1，交替重试，上限 6 次。 */
   public static HttpResponse<String> send(HttpRequest request)
         throws IOException, InterruptedException {
      permitInitial(request.uri());
      IOException lastIo = null;
      HttpRequest current = request;
      URI origin = request.uri();
      for (int i = 0; i < 6; i++) {
         try {
            HttpResponse<byte[]> resp = (i % 2 == 0 ? CLIENT_HTTP2 : CLIENT_HTTP1)
                  .send(current, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.body().length > DOWNLOAD_BODY_BYTES) {
               TraceLog.warn("HTTP response body exceeds {} bytes: {}", DOWNLOAD_BODY_BYTES, redactUri(current.uri()));
               throw new VendorException(VendorError.NETWORK_FAILURE);
            }
            if (resp.statusCode() >= 300 && resp.statusCode() < 400) {
               if (!"GET".equalsIgnoreCase(current.method())) {
                  return wrapString(resp, "");
               }
               String location = resp.headers().firstValue("Location").orElse(null);
               if (location == null || location.isBlank()) {
                  return wrapString(resp, "");
               }
               URI next;
               try {
                  // 相对 Location 必须相对「当前这一跳」解析：多跳重定向时用最初 origin 作基准
                  // 会把 /a/b/c 下的相对路径错误解析到 /a 层级，跳错目标。
                  next = current.uri().resolve(location);
               } catch (IllegalArgumentException invalidLoc) {
                  return wrapString(resp, "");
               }
               if (!permitRedirect(next, current)) {
                  TraceLog.warn("拒绝不安全的重定向：{} -> {}（仅同源保留凭证；跨源仅限 https 或音频 CDN）",
                        redactUri(origin), redactUri(next));
                  return wrapString(resp, "");
               }
               HttpRequest.Builder builder = HttpRequest.newBuilder(next)
                     .timeout(Duration.ofSeconds(15));
               boolean sameOrigin = sameOrigin(origin, next);
               current.headers().map().forEach((name, values) -> values.forEach(value -> {
                  if (sameOrigin || !isCredentialHeader(name)) {
                     builder.header(name, value);
                  }
               }));
               current = builder.GET().build();
               continue;
            }
            return wrapString(resp, decodeText(resp, resp.body()));
         } catch (IOException e) {
            lastIo = e;
         }
      }
      if (lastIo == null) {
         TraceLog.warn("HTTP redirect limit exceeded (6 hops)");
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      throw lastIo;
   }

   // ---------------- 响应文本解码（跨平台编码探测） ----------------

   private static final Pattern CHARSET_PATTERN =
         Pattern.compile("charset\\s*=\\s*([A-Za-z0-9_\\-.:]+)");

   /**
    * 把已完成字节响应的请求结果解码为文本：
    * <ul>
    *   <li>响应头 {@code Content-Type} 显式声明 charset 时优先采用；</li>
    *   <li>未声明时按 UTF-8 严格解码，出现非法序列再回落 GBK（覆盖酷狗等老接口返回 GBK 的场景）；</li>
    *   <li>剥离 UTF-8 BOM。</li>
    * </ul>
    * 纯 JDK 实现，不依赖平台默认字符集，跨 Windows / Linux / macOS 行为一致。
    */
   public static String decodeText(HttpResponse<?> resp, byte[] raw) {
      if (raw == null || raw.length == 0) {
         return "";
      }
      Charset declared = declaredCharset(resp);
      String text;
      if (declared != null) {
         text = new String(raw, declared);
      } else {
         try {
            text = strictDecode(raw, StandardCharsets.UTF_8);
         } catch (CharacterCodingException notUtf8) {
            text = new String(raw, Charset.forName("GBK"));
         }
      }
      if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
         text = text.substring(1);
      }
      return text;
   }

   /** 将字节响应包装为 {@link HttpResponse String}，状态码 / 头 / URI / 版本 / 请求保持原值。 */
   public static HttpResponse<String> wrapString(HttpResponse<byte[]> raw, String text) {
      return new StringResponse(raw, text == null ? "" : text);
   }

   private static Charset declaredCharset(HttpResponse<?> resp) {
      if (resp == null) {
         return null;
      }
      String contentType = resp.headers().firstValue("Content-Type").orElse(null);
      if (contentType == null) {
         return null;
      }
      Matcher matcher = CHARSET_PATTERN.matcher(contentType);
      if (!matcher.find()) {
         return null;
      }
      try {
         return Charset.forName(matcher.group(1));
      } catch (IllegalArgumentException unknown) {
         return StandardCharsets.UTF_8;
      }
   }

   private static String strictDecode(byte[] raw, Charset charset) throws CharacterCodingException {
      return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString();
   }

   /** 字节响应的 String 包装：仅替换正文为解码后的文本，其余元数据透传原始响应。 */
   private static final class StringResponse implements HttpResponse<String> {
      private final HttpResponse<byte[]> raw;
      private final String text;

      private StringResponse(HttpResponse<byte[]> raw, String text) {
         this.raw = raw;
         this.text = text;
      }

      @Override
      public int statusCode() {
         return raw.statusCode();
      }

      @Override
      public HttpHeaders headers() {
         return raw.headers();
      }

      @Override
      public String body() {
         return text;
      }

      @Override
      public URI uri() {
         return raw.uri();
      }

      @Override
      public HttpClient.Version version() {
         return raw.version();
      }

      @Override
      public HttpRequest request() {
         return raw.request();
      }

      @Override
      public Optional<HttpResponse<String>> previousResponse() {
         return Optional.empty();
      }

      @Override
      public Optional<SSLSession> sslSession() {
         return raw.sslSession();
      }
   }

   // ---------------- 初始请求白名单校验 ----------------

   /** 初始请求目标校验：https 一律允许（内网 / 保留段字面量除外）；http 明文仅允许音频 CDN 白名单域——防任意明文外联与 SSRF。 */
   private static void permitInitial(URI uri) {
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null) {
         TraceLog.warn("Request target missing scheme or host: {}", redactUri(uri));
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      if (isReservedPrivateHost(host)) {
         TraceLog.warn("Rejected internal/reserved address target: {}", redactUri(uri));
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      if (!ReservedAddressGuard.isPublicReachable(host)) {
         TraceLog.warn("Rejected DNS-resolved internal/reserved target: {}", redactUri(uri));
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      if ("https".equalsIgnoreCase(scheme)) {
         return;
      }
      if ("http".equalsIgnoreCase(scheme) && isMediaCdnHost(host)) {
         return;
      }
      TraceLog.warn("Rejected non-whitelisted plaintext target: {}", redactUri(uri));
      throw new VendorException(VendorError.NETWORK_FAILURE);
   }

   public static HttpRequest.Builder builder(String url) {
      return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
   }

   // ---------------- 重定向安全判定 ----------------

   private static boolean sameOrigin(URI a, URI b) {
      if (a.getScheme() == null || b.getScheme() == null
            || a.getHost() == null || b.getHost() == null) {
         return false;
      }
      return a.getScheme().equalsIgnoreCase(b.getScheme())
            && a.getHost().equalsIgnoreCase(b.getHost());
   }

   private static boolean isCredentialHeader(String name) {
      return name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Authorization")
            || name.equalsIgnoreCase("Proxy-Authorization");
   }

   private static boolean hasCredentialHeader(HttpRequest request) {
      for (String name : request.headers().map().keySet()) {
         if (isCredentialHeader(name)) {
            return true;
         }
      }
      return false;
   }

   /** 跨源重定向目标校验：https 允许（DNS 解析后非保留段，凭证已在调用方剥离）；http 仅允许音频 CDN 白名单且请求无凭证。 */
   private static boolean permitRedirect(URI target, HttpRequest request) {
      String scheme = target.getScheme();
      String host = target.getHost();
      if (scheme == null || host == null || isReservedPrivateHost(host)) {
         return false;
      }
      if (!ReservedAddressGuard.isPublicReachable(host)) {
         return false;
      }
      if ("https".equalsIgnoreCase(scheme)) {
         return true;
      }
      if ("http".equalsIgnoreCase(scheme)) {
         return !hasCredentialHeader(request) && isMediaCdnHost(host);
      }
      return false;
   }

   /** 音频 CDN 域名白名单（网易云/酷狗/QQ 音乐 CDN 域；http 明文仅允许这些）。 */
   private static boolean isMediaCdnHost(String host) {
      if (host == null) {
         return false;
      }
      String h = host.toLowerCase();
      return h.endsWith(".kugou.com") || h.endsWith(".kqcdn.com")
            || h.endsWith(".music.126.net") || h.equals("music.163.com") || h.endsWith(".music.163.com")
            || h.endsWith(".gtimg.cn") || h.endsWith(".qq.com") || h.endsWith(".y.qq.com");
   }

   /**
    * 判定 host 是否为内网 / 环回 / 链路本地 / 元数据 / 组播 / 保留段字面量地址（SSRF 防护）。
    * 仅识别 IP 字面量；域名不做 DNS 解析（残余风险见安全报告：需攻击者控制白名单域 DNS 才能触发）。
    */
   public static boolean isReservedPrivateHost(String host) {
      if (host == null || host.isBlank()) {
         return false;
      }
      String h = host.trim();
      if (h.startsWith("[") && h.endsWith("]")) {
         h = h.substring(1, h.length() - 1);
      }
      String lower = h.toLowerCase();
      // IPv6 字面量一定含冒号，据此把前缀判定限定在 IPv6 上。此前无此守卫，
      // 任何以 fc / fd / fe80 开头的域名（如 fcdn.kugou.com）都会被误判为内网地址，
      // 在 permitInitial / permitDownloadTarget 里被直接拒绝，造成正常音源域名不可达。
      if (lower.indexOf(':') >= 0
            && ("::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd")
                  || lower.startsWith("fe80"))) {
         return true;
      }
      String[] octets = h.split("\\.");
      if (octets.length != 4) {
         return false;
      }
      long[] v = new long[4];
      try {
         for (int i = 0; i < 4; i++) {
            v[i] = Long.parseLong(octets[i]);
            if (v[i] < 0 || v[i] > 255) {
               return false;
            }
         }
      } catch (NumberFormatException notIp) {
         return false;
      }
      long a = v[0];
      if (a == 0L || a == 10L || a == 127L) {
         return true;
      }
      if (a == 169L && v[1] >= 254L) {
         return true;
      }
      if (a == 172L && v[1] >= 16L && v[1] <= 31L) {
         return true;
      }
      if (a == 192L && v[1] == 168L) {
         return true;
      }
      if (a == 100L && v[1] >= 64L && v[1] <= 127L) {
         return true;
      }
      if (a == 192L && v[1] == 0L && v[2] == 0L) {
         return true;
      }
      if (a == 198L && (v[1] == 18L || v[1] == 19L)) {
         return true;
      }
      if (a == 198L && v[1] == 51L && v[2] == 100L) {
         return true;
      }
      if (a == 203L && v[1] == 0L && v[2] == 113L) {
         return true;
      }
      if (a >= 224L) {
         return true;
      }
      return false;
   }

   /** 日志脱敏：只显示 host+path，绝不打印含 token 的查询参数。 */
   private static String redactUri(URI uri) {
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

   /**
    * 把第三方原始响应体 / 平台 msg 整理成适合写入开发者日志的短文本：
    * 去除换行与控制字符、截断到 300 字符；不用于玩家上屏。
    */
   public static String describeBody(String raw) {
      if (raw == null || raw.isBlank()) {
         return "";
      }
      String cleaned = raw.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").trim();
      cleaned = Redactor.message(cleaned);
      return cleaned.length() > 300 ? cleaned.substring(0, 300) + "…" : cleaned;
   }
}
