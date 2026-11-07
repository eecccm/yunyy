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

import com.eecccm.yunyy.core.crypto.SecretCipher;
import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.Redactor;
import com.eecccm.yunyy.core.net.ReservedAddressGuard;
import com.eecccm.yunyy.core.net.SafeHttpClient;
import com.eecccm.yunyy.core.path.StorageLocations;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云账号会话引擎（静态）：凭证持久化、账号探测、搜索 / 歌单 / 歌词 / 音源解析。
 *
 * <p>安全设计：凭证 AES-GCM 加密落盘（密钥与凭证分文件存放，兼容旧 ECB/明文并自动迁移）；
 * 音源请求走安全重定向通道（同源保留 Cookie、跨源剥离且仅放行 https 或网易 CDN）；日志脱敏。</p>
 */
public final class AccountSessionStore {

   private static final Path SESSIONS_FILE =
         StorageLocations.configDir().resolve("yunyy-account.properties");
   private static final Path DEVICE_KEY =
         StorageLocations.configDir().resolve("yunyy-account.key");
   private static final HttpClient DIRECT_HTTP = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(10L))
         .followRedirects(HttpClient.Redirect.NEVER)
         .build();

   private static volatile String credential = "";
   private static volatile String displayName = "";
   /** 本地 AES 密钥（32 hex=16 字节 或 64 hex=32 字节），与凭证文件分离。 */
   private static volatile String localKey = "";

   private static final long STREAM_CACHE_MILLIS = Duration.ofMinutes(5L).toMillis();
   private static final Map<String, CachedStream> STREAM_CACHE = new ConcurrentHashMap<>();

   private AccountSessionStore() {
   }

   public static void load() {
      localKey = loadKey();
      if (!Files.isRegularFile(SESSIONS_FILE)) {
         return;
      }
      Properties p = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(SESSIONS_FILE, StandardCharsets.UTF_8)) {
         p.load(reader);
         displayName = p.getProperty("nickname", "");
         String encrypted = p.getProperty("cookieEnc", "");
         if (!encrypted.isBlank()) {
            try {
               credential = SecretCipher.gcmDecryptBase64(encrypted, localKey);
               persist();
               TraceLog.info("本地登录凭证已升级为 AES-GCM 加密存储");
            } catch (Exception gcmFail) {
               try {
                  credential = SecretCipher.ecbDecryptHex(encrypted, localKey);
                  persist();
                  TraceLog.info("本地登录凭证已从旧格式迁移为 AES-GCM 加密存储");
               } catch (Exception ecbFail) {
                  credential = "";
                  TraceLog.warn("本地登录凭证解密失败（密钥丢失或文件损坏），请重新登录");
               }
            }
         } else {
            credential = p.getProperty("cookie", "");
            if (!credential.isBlank() && !localKey.isBlank()) {
               try {
                  persist();
                  TraceLog.info("旧版明文凭证已迁移为 AES-GCM 加密存储");
               } catch (IOException e) {
                  TraceLog.warn("旧版凭证迁移加密失败");
               }
            }
         }
      } catch (Exception e) {
         TraceLog.warn("无法读取本地网易云登录状态");
      }
   }

   /** 读取/生成本机凭证加密密钥（与凭证分离存放，避免明文 Cookie 落盘）。 */
   private static String loadKey() {
      if (Files.isRegularFile(DEVICE_KEY)) {
         try {
            String key = Files.readString(DEVICE_KEY, StandardCharsets.UTF_8).trim();
            if (key.length() == 32 || key.length() == 64) {
               return key;
            }
         } catch (Exception ignored) {
         }
      }
      String key = randomToken(64);
      try {
         Files.createDirectories(DEVICE_KEY.getParent());
         Files.writeString(DEVICE_KEY, key, StandardCharsets.UTF_8);
         hardenKeyFile();
      } catch (Exception e) {
         TraceLog.warn("无法保存本地凭证加密密钥");
      }
      return key;
   }

   /** 跨平台收紧密钥文件权限：POSIX rw-------；Windows ACL 仅当前用户可读（T-06）。 */
   private static void hardenKeyFile() {
      FileSecurity.hardenFile(DEVICE_KEY);
      FileSecurity.hardenDirectory(DEVICE_KEY.getParent());
   }

   private static String randomToken(int length) {
      StringBuilder sb = new StringBuilder();
      SecureRandom random = new SecureRandom();
      String alphabet = "0123456789abcdef";
      for (int i = 0; i < length; i++) {
         sb.append(alphabet.charAt(random.nextInt(16)));
      }
      return sb.toString();
   }

   private static String encryptCredential(String plain) {
      if (plain.isBlank() || localKey.isBlank()) {
         return "";
      }
      return SecretCipher.gcmEncryptBase64(plain, localKey);
   }

   public static boolean hasSession() {
      return !credential.isBlank();
   }

   public static String displayName() {
      // 空串表示"无昵称"，面向玩家的兜底文案由 mccore 显示层按语言键渲染，core 不携带中文。
      return displayName == null ? "" : displayName;
   }

   public static String credentialHeader() {
      return credential;
   }

   // ---------------------------------------------------------------- 音源解析

   /**
    * 解析歌曲播放地址。未登录返回兜底；已登录走 EAPI 权益接口，
    * 5 分钟内缓存同一歌曲的结果，避免重复请求。
    */
   public static String resolveStreamUrl(String songId, String publicFallback) {
      if (songId == null || !songId.matches("[1-9][0-9]{0,18}")) {
         return publicFallback;
      }
      // 仅免费 / 试听模式（T-02）：不携带凭证请求权益接口，直接走公开兜底音源。
      if (FeatureSwitch.freeOnly || !hasSession()) {
         return publicFallback;
      }
      CachedStream cached = STREAM_CACHE.get(songId);
      long now = System.currentTimeMillis();
      if (cached != null && now - cached.resolvedAtMillis() < STREAM_CACHE_MILLIS) {
         return cached.url();
      }
      try {
         String musicU = partOf(credential, "MUSIC_U");
         if (musicU.isBlank()) {
            throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
         }
         String buildVersion = Long.toString(now / 1000L);
         String requestId = now + "_0001";
         JsonObject header = new JsonObject();
         header.addProperty("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
         header.addProperty("deviceId", "");
         header.addProperty("os", "pc");
         header.addProperty("appver", "3.1.17.204416");
         header.addProperty("versioncode", "140");
         header.addProperty("mobilename", "");
         header.addProperty("buildver", buildVersion);
         header.addProperty("resolution", "1920x1080");
         header.addProperty("__csrf", partOf(credential, "__csrf"));
         header.addProperty("channel", "netease");
         header.addProperty("requestId", requestId);
         header.addProperty("MUSIC_U", musicU);
         JsonObject data = new JsonObject();
         data.addProperty("ids", "[" + songId + "]");
         data.addProperty("level", "exhigh");
         data.addProperty("encodeType", "mp3");
         data.add("header", header);
         String apiPath = "/api/song/enhance/player/url/v1";
         String params = eapiEncode(apiPath, data.toString());
         String clientCookie = credential + "; os=pc; appver=3.1.17.204416; channel=netease;"
               + " buildver=" + buildVersion + "; requestId=" + requestId;
         HttpRequest request = HttpRequest.newBuilder(
                     URI.create("https://interface.music.163.com/eapi/song/enhance/player/url/v1"))
               .timeout(Duration.ofSeconds(15L))
               .header("User-Agent",
                     "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 NeteaseMusicDesktop/3.1.29.205117")
               .header("Cookie", clientCookie)
               .header("Content-Type", "application/x-www-form-urlencoded")
               .POST(HttpRequest.BodyPublishers.ofString(
                     "params=" + URLEncoder.encode(params, StandardCharsets.UTF_8)))
               .build();
         HttpResponse<String> response = safeSend(request);
         if (response.statusCode() != 200) {
            throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
         }
         JsonArray dataArr = JsonParser.parseString(response.body())
               .getAsJsonObject().getAsJsonArray("data");
         if (dataArr == null || dataArr.isEmpty()) {
            return publicFallback;
         }
         JsonObject item = dataArr.get(0).getAsJsonObject();
         boolean hasUrlField = item.has("url") && !item.get("url").isJsonNull();
         String resolved = hasUrlField ? item.get("url").getAsString().trim() : "";
         if (hasUrlField && !resolved.isEmpty() && !"null".equalsIgnoreCase(resolved)) {
            // 很多免费歌曲也带 freeTrialInfo 标记但 url 完整可播，不能据此误判为 VIP
            STREAM_CACHE.put(songId, new CachedStream(resolved, System.currentTimeMillis()));
            return resolved;
         }
         if (item.has("freeTrialInfo") && !item.get("freeTrialInfo").isJsonNull()) {
            TraceLog.warn("网易云账号对该歌曲仅有试听权益：songId={}", songId);
            throw new VendorException(VendorError.TRIAL_ONLY);
         }
         int code = item.has("code") ? item.get("code").getAsInt() : 0;
         String reason = item.has("message") && !item.get("message").isJsonNull()
               ? item.get("message").getAsString() : "";
         // 8821 为网易云风控 / 登录态拦截码：识别后停止重试，抛中性风控错误，界面不再循环请求。
         if (code == 8821) {
            TraceLog.warn("网易云风控拦截（8821），停止后续重试：songId={}", songId);
            throw new VendorException(VendorError.RISK_CONTROLLED, MusicVendor.NETEASE.label, code);
         }
         TraceLog.warn("网易云歌曲无播放权益：songId={} code={} reason={}", songId, code, reason);
         throw new VendorException(VendorError.NO_STREAM_PERMISSION, code);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new CancellationException("Stream request cancelled by a newer playback task");
      } catch (VendorException ve) {
         throw ve;
      } catch (Exception e) {
         Throwable root = e;
         while (root.getCause() != null) {
            root = root.getCause();
         }
         String message = root.getMessage() == null
               ? root.getClass().getSimpleName() : Redactor.message(root.getMessage());
         TraceLog.warn("使用本地网易云账号解析歌曲失败: {}", message);
         throw new VendorException(VendorError.STREAM_RESOLVE_FAILED, e);
      }
   }

   /** 预取音源缓存（登录且歌曲 ID 合法时），供播放前预热。 */
   public static CompletableFuture<Void> warmStreamUrl(String songId) {
      if (!hasSession() || songId == null || !songId.matches("[1-9][0-9]{0,18}")) {
         return CompletableFuture.completedFuture(null);
      }
      CachedStream cached = STREAM_CACHE.get(songId);
      if (cached != null && System.currentTimeMillis() - cached.resolvedAtMillis() < STREAM_CACHE_MILLIS) {
         return CompletableFuture.completedFuture(null);
      }
      String fallback = "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
      return CompletableFuture.runAsync(() -> resolveStreamUrl(songId, fallback));
   }

   // ---------------------------------------------------------------- 账号

   /** 异步校验账号状态；成功后刷新昵称并持久化。 */
   public static CompletableFuture<Profile> verifyAccount() {
      String currentCookie = credential;
      return CompletableFuture.supplyAsync(() -> {
         Profile state = probeAccount(currentCookie);
         if (state.active()) {
            displayName = state.nickname();
            try {
               persist();
            } catch (IOException ignored) {
            }
         }
         return state;
      });
   }

   /** 导入 Cookie 并校验；有效则持久化为当前会话。 */
   public static CompletableFuture<Profile> importCredential(String rawCookie) {
      return CompletableFuture.supplyAsync(() -> {
         String sanitized = filterAllowed(rawCookie);
         if (sanitized.isBlank()) {
            throw new VendorException(VendorError.COOKIE_MISSING_FIELD);
         }
         Profile state = probeAccount(sanitized);
         if (!state.active()) {
            throw new VendorException(VendorError.COOKIE_INVALID_EXPIRED);
         }
         STREAM_CACHE.clear();
         credential = sanitized;
         displayName = state.nickname();
         try {
            persist();
            return state;
         } catch (IOException e) {
            credential = "";
            displayName = "";
            throw new VendorException(VendorError.UNKNOWN, e);
         }
      });
   }

   public static void signOut() {
      credential = "";
      displayName = "";
      STREAM_CACHE.clear();
      try {
         Files.deleteIfExists(SESSIONS_FILE);
      } catch (IOException ignored) {
      }
      // 注意：不删除 DEVICE_KEY——该密钥为本地设备级密钥，酷狗凭证加密共用同一把
   }

   private static Profile probeAccount(String authCookie) {
      if (FeatureSwitch.freeOnly || authCookie.isBlank()) {
         return new Profile(false, "", 0);
      }
      try {
         HttpRequest request = newRequest("https://music.163.com/api/nuser/account/get")
               .header("Cookie", authCookie).GET().build();
         JsonObject json = JsonParser.parseString(safeSend(request).body()).getAsJsonObject();
         JsonObject profile = json.getAsJsonObject("profile");
         JsonObject account = json.getAsJsonObject("account");
         if (profile != null && account != null) {
            int vipType = profile.has("vipType") ? profile.get("vipType").getAsInt() : 0;
            return new Profile(true, profile.get("nickname").getAsString(), vipType);
         }
         return new Profile(false, "", 0);
      } catch (Exception e) {
         return new Profile(false, "", 0);
      }
   }

   // ---------------------------------------------------------------- 歌单 / 搜索 / 歌词

   public static CompletableFuture<List<FolderInfo>> fetchPlaylists() {
      String currentCookie = credential;
      return CompletableFuture.supplyAsync(() -> {
         if (FeatureSwitch.freeOnly) {
            throw new VendorException(VendorError.FREE_MODE_ONLY, MusicVendor.NETEASE.label);
         }
         if (currentCookie.isBlank()) {
            throw new VendorException(VendorError.NOT_LOGGED_IN, MusicVendor.NETEASE.label);
         }
         try {
            HttpRequest accountRequest = newRequest("https://music.163.com/api/nuser/account/get")
                  .header("Cookie", currentCookie).GET().build();
            JsonObject account = JsonParser.parseString(safeSend(accountRequest).body()).getAsJsonObject();
            JsonObject profile = account.getAsJsonObject("profile");
            if (profile == null) {
               throw new VendorException(VendorError.NOT_LOGGED_IN, MusicVendor.NETEASE.label);
            }
            long uid = profile.get("userId").getAsLong();
            List<FolderInfo> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int pageSize = 100;
            // 分页循环：每页 100，最多 5 页（500 个歌单），返回页不足一页即停止；按歌单 ID 去重保持平台原序。
            for (int offset = 0; offset < 500; offset += pageSize) {
               URI uri = URI.create("https://music.163.com/api/user/playlist?uid=" + uid
                     + "&limit=" + pageSize + "&offset=" + offset + "&includeVideo=true");
               HttpRequest request = newRequest(uri.toString()).header("Cookie", currentCookie).GET().build();
               HttpResponse<String> response = safeSend(request);
               if (response.statusCode() != 200) {
                  throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
               }
               JsonArray array = JsonParser.parseString(response.body())
                     .getAsJsonObject().getAsJsonArray("playlist");
               if (array == null || array.isEmpty()) {
                  break;
               }
               for (JsonElement value : array) {
                  if (!value.isJsonObject()) {
                     continue;
                  }
                  JsonObject item = value.getAsJsonObject();
                  String folderId = hasText(item, "id") ? item.get("id").getAsString() : "";
                  if (folderId.isBlank() || !seen.add(folderId)) {
                     continue;
                  }
                  result.add(new FolderInfo(
                        folderId,
                        hasText(item, "name") ? item.get("name").getAsString() : "",
                        item.has("trackCount") && item.get("trackCount").isJsonPrimitive()
                              ? item.get("trackCount").getAsInt() : 0));
               }
               if (array.size() < pageSize) {
                  break;
               }
            }
            return List.copyOf(result);
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_LOAD_FAILED, e, MusicVendor.NETEASE.label);
         }
      });
   }

   public static CompletableFuture<List<SongMeta>> searchSongs(String query, int page) {
      String normalized = query == null ? "" : query.trim();
      return CompletableFuture.supplyAsync(() -> {
         if (normalized.isBlank()) {
            throw new VendorException(VendorError.QUERY_EMPTY);
         }
         if (normalized.length() > 80) {
            throw new VendorException(VendorError.QUERY_TOO_LONG);
         }
         try {
            URI uri = URI.create("https://music.163.com/api/search/get/web?s="
                  + URLEncoder.encode(normalized, StandardCharsets.UTF_8)
                  + "&type=1&offset=" + Math.max(0, page) * 10
                  + "&total=true&limit=10");
            HttpRequest.Builder builder = newRequest(uri.toString());
            String currentCookie = credential;
            if (!FeatureSwitch.freeOnly && !currentCookie.isBlank()) {
               builder.header("Cookie", currentCookie);
            }
            HttpResponse<String> response = safeSend(builder.GET().build());
            if (response.statusCode() != 200) {
               throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject result = root.getAsJsonObject("result");
            JsonArray songs = result == null ? null : result.getAsJsonArray("songs");
            if (songs == null || songs.isEmpty()) {
               return List.of();
            }
            List<String> orderedIds = new ArrayList<>();
            List<SongMeta> fallback = new ArrayList<>();
            for (JsonElement value : songs) {
               if (orderedIds.size() >= 10 || !value.isJsonObject()) {
                  break;
               }
               SongMeta raw = parseSongMeta(value.getAsJsonObject());
               if (raw != null) {
                  orderedIds.add(raw.id());
                  fallback.add(raw);
               }
            }
            if (orderedIds.isEmpty()) {
               return List.of();
            }
            // 搜索 web 接口的 album 仅含 picId、不含 picUrl，需走歌曲详情接口批量补全封面；
            // 详情补全失败时回退搜索原始结果（封面可能为空，由客户端降级为 DEBUG 提示，不中断搜索）。
            try {
               List<SongMeta> detailed = fetchSongMeta(orderedIds, currentCookie);
               if (!detailed.isEmpty()) {
                  return List.copyOf(detailed);
               }
            } catch (Exception detailErr) {
               TraceLog.debug("网易云搜索详情补全失败，回退搜索原始结果: {}", detailErr.toString());
            }
            return List.copyOf(fallback);
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.SEARCH_FAILED, e, MusicVendor.NETEASE.label);
         }
      });
   }

   public static CompletableFuture<Contents> fetchPlaylistSongs(String playlistId) {
      String currentCookie = credential;
      return CompletableFuture.supplyAsync(() -> {
         if (FeatureSwitch.freeOnly) {
            throw new VendorException(VendorError.FREE_MODE_ONLY, MusicVendor.NETEASE.label);
         }
         if (currentCookie.isBlank()) {
            throw new VendorException(VendorError.NOT_LOGGED_IN, MusicVendor.NETEASE.label);
         }
         if (playlistId == null || !playlistId.matches("[1-9][0-9]{0,18}")) {
            throw new VendorException(VendorError.PLAYLIST_ID_INVALID);
         }
         try {
            URI uri = URI.create("https://music.163.com/api/v6/playlist/detail?id="
                  + playlistId + "&n=100000&s=8");
            HttpRequest request = newRequest(uri.toString()).header("Cookie", currentCookie).GET().build();
            HttpResponse<String> response = safeSend(request);
            if (response.statusCode() != 200) {
               throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject playlist = root.getAsJsonObject("playlist");
            if (playlist == null) {
               throw new VendorException(VendorError.PLAYLIST_FORBIDDEN);
            }
            JsonArray trackIds = playlist.getAsJsonArray("trackIds");
            List<String> ids = new ArrayList<>();
            if (trackIds != null) {
               for (JsonElement value : trackIds) {
                  if (ids.size() >= 5000) {
                     break;
                  }
                  JsonObject item = value.getAsJsonObject();
                  if (item.has("id")) {
                     ids.add(item.get("id").getAsString());
                  }
               }
            }
            List<SongMeta> tracks = fetchSongMeta(ids, currentCookie);
            return new Contents(playlist.get("name").getAsString(), tracks);
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_TRACKS_FAILED, e, MusicVendor.NETEASE.label);
         }
      });
   }

   /** 按 ID 批量拉取歌曲元数据（每 100 首一批，保持原始顺序）。 */
   private static List<SongMeta> fetchSongMeta(List<String> ids, String currentCookie) throws Exception {
      Map<String, SongMeta> byId = new HashMap<>();
      for (int start = 0; start < ids.size(); start += 100) {
         List<String> batch = ids.subList(start, Math.min(start + 100, ids.size()));
         String joined = String.join(",", batch);
         URI uri = URI.create("https://music.163.com/api/song/detail/?ids="
               + URLEncoder.encode("[" + joined + "]", StandardCharsets.UTF_8));
         HttpRequest request = newRequest(uri.toString()).header("Cookie", currentCookie).GET().build();
         HttpResponse<String> response = safeSend(request);
         if (response.statusCode() != 200) {
            throw new VendorException(VendorError.HTTP_STATUS, MusicVendor.NETEASE.label, response.statusCode());
         }
         JsonArray songs = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("songs");
         if (songs != null) {
            for (JsonElement value : songs) {
               try {
                  SongMeta meta = parseSongMeta(value.getAsJsonObject());
                  if (meta != null) {
                     byId.put(meta.id(), meta);
                  }
               } catch (RuntimeException e) {
                  TraceLog.debug("跳过网易云返回的异常歌单歌曲元数据", e);
               }
            }
         }
      }
      List<SongMeta> ordered = new ArrayList<>();
      for (String idx : ids) {
         SongMeta info = byId.get(idx);
         if (info != null) {
            ordered.add(info);
         }
      }
      return List.copyOf(ordered);
   }

   private static SongMeta parseSongMeta(JsonObject song) {
      try {
         if (!hasText(song, "id") || !hasText(song, "name")) {
            return null;
         }
         String id = song.get("id").getAsString();
         if (!id.matches("[1-9][0-9]{0,18}")) {
            return null;
         }
         JsonArray artistArray = song.has("artists") && song.get("artists").isJsonArray()
               ? song.getAsJsonArray("artists")
               : (song.has("ar") && song.get("ar").isJsonArray() ? song.getAsJsonArray("ar") : null);
         List<String> artists = new ArrayList<>();
         if (artistArray != null) {
            for (JsonElement artistValue : artistArray) {
               if (artistValue.isJsonObject() && hasText(artistValue.getAsJsonObject(), "name")) {
                  artists.add(artistValue.getAsJsonObject().get("name").getAsString());
               }
            }
         }
         JsonObject album = song.has("album") && song.get("album").isJsonObject()
               ? song.getAsJsonObject("album")
               : (song.has("al") && song.get("al").isJsonObject() ? song.getAsJsonObject("al") : null);
         String cover = album != null && hasText(album, "picUrl") ? album.get("picUrl").getAsString() : "";
         if (cover.startsWith("http://")) {
            cover = "https://" + cover.substring(7);
         }
         long duration = hasNumber(song, "duration") ? song.get("duration").getAsLong()
               : (hasNumber(song, "dt") ? song.get("dt").getAsLong() : 0L);
         return new SongMeta(id, song.get("name").getAsString(),
               artists.isEmpty() ? "" : String.join(" / ", artists), duration, cover);
      } catch (RuntimeException e) {
         TraceLog.debug("跳过网易云返回的异常搜索结果", e);
         return null;
      }
   }

   public static CompletableFuture<String> fetchLyrics(String songId) {
      return CompletableFuture.supplyAsync(() -> {
         if (songId == null || !songId.matches("[1-9][0-9]{0,18}")) {
            return "";
         }
         try {
            URI uri = URI.create("https://music.163.com/api/song/lyric?id=" + songId
                  + "&lv=-1&kv=-1&tv=-1");
            HttpRequest.Builder builder = newRequest(uri.toString());
            String currentCookie = credential;
            if (!FeatureSwitch.freeOnly && !currentCookie.isBlank()) {
               builder.header("Cookie", currentCookie);
            }
            HttpResponse<String> response = safeSend(builder.GET().build());
            if (response.statusCode() != 200) {
               return "";
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String mainLrc = lyricText(root, "lrc");
            if (mainLrc.isBlank()) {
               return "";
            }
            // klyric=音译（注音），tlyric=翻译；缺失时对应段留空，不补占位文字。
            String romaLrc = lyricText(root, "klyric");
            String transLrc = lyricText(root, "tlyric");
            return mergeThreeLyrics(mainLrc, romaLrc, transLrc);
         } catch (Exception e) {
            return "";
         }
      });
   }

   /** 读取歌词分块（lrc/klyric/tlyric）内的 lyric 字段；缺失返回空串。 */
   private static String lyricText(JsonObject root, String section) {
      if (root == null || !root.has(section) || !root.get(section).isJsonObject()) {
         return "";
      }
      JsonObject block = root.getAsJsonObject(section);
      return hasText(block, "lyric") ? block.get("lyric").getAsString() : "";
   }

   private static final Pattern LYRIC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

   /** 解析单轨 LRC 为 时间戳(毫秒) → 文本；同一行多个时间戳映射同一文本。 */
   private static Map<Long, String> parseLrcMap(String text) {
      Map<Long, String> result = new LinkedHashMap<>();
      if (text == null || text.isBlank()) {
         return result;
      }
      for (String row : text.split("\\R")) {
         Matcher matcher = LYRIC_TIME.matcher(row);
         List<Long> times = new ArrayList<>();
         int lastEnd = -1;
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
            String content = row.substring(lastEnd).trim();
            for (long time : times) {
               result.put(time, content);
            }
         }
      }
      return result;
   }

   /**
    * 三轨对齐合并：以主歌词时间轴为基准，音译 / 翻译按同毫秒优先、最近一行且时间差 ≤300ms 匹配；
    * 段序固定为 音译\u0001原文\u0001翻译，与酷狗 KRC 合并口径一致，供 HUD 三段式渲染。
    */
   private static String mergeThreeLyrics(String mainLrc, String romaLrc, String transLrc) {
      Map<Long, String> main = parseLrcMap(mainLrc);
      Map<Long, String> roma = parseLrcMap(romaLrc);
      Map<Long, String> trans = parseLrcMap(transLrc);
      if (main.isEmpty()) {
         return "";
      }
      List<Long> mainTimes = new ArrayList<>(main.keySet());
      mainTimes.sort(Long::compare);
      StringBuilder sb = new StringBuilder();
      for (long time : mainTimes) {
         String mainText = main.getOrDefault(time, "");
         String romaText = matchNear(roma, time);
         String transText = matchNear(trans, time);
         if (mainText.isBlank() && romaText.isBlank() && transText.isBlank()) {
            continue;
         }
         sb.append(formatLyricTime(time))
               .append(romaText).append('\u0001').append(mainText).append('\u0001').append(transText)
               .append('\n');
      }
      return sb.toString();
   }

   /** 最近时间戳匹配：同毫秒直接命中；否则在 300ms 窗口内取最近一行，无匹配返回空串。 */
   private static String matchNear(Map<Long, String> map, long time) {
      String exact = map.get(time);
      if (exact != null) {
         return exact;
      }
      Long best = null;
      long bestDiff = Long.MAX_VALUE;
      for (Long candidate : map.keySet()) {
         long diff = Math.abs(candidate - time);
         if (diff <= 300L && diff < bestDiff) {
            best = candidate;
            bestDiff = diff;
         }
      }
      return best == null ? "" : map.getOrDefault(best, "");
   }

   /** 毫秒时间戳格式化为 LRC 的 [mm:ss.xx]。 */
   private static String formatLyricTime(long millis) {
      long totalSeconds = millis / 1000L;
      long minutes = totalSeconds / 60L;
      long seconds = totalSeconds % 60L;
      long fraction = millis % 1000L;
      return String.format("[%02d:%02d.%02d]", minutes, seconds, fraction / 10L);
   }

   private static boolean hasText(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull()
            && object.get(key).isJsonPrimitive()
            && !object.get(key).getAsString().isBlank();
   }

   private static boolean hasNumber(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull()
            && object.get(key).isJsonPrimitive()
            && object.get(key).getAsJsonPrimitive().isNumber();
   }

   // ---------------------------------------------------------------- HTTP 通道

   private static HttpRequest.Builder newRequest(String uri) {
      return HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(15L))
            .header("User-Agent", "Mozilla/5.0 Yunyy/1.0.0")
            .header("Referer", "https://music.163.com/");
   }

   /** 凭证通道固定直连：带 Cookie 的网易云 API 绝不经第三方代理中转。 */
   private static HttpClient proxyAwareClient() {
      return DIRECT_HTTP;
   }

   /**
    * 网易云 API 安全发送通道：禁用自动跟随，GET 3xx 手动处理——
    * 同源（scheme+host）保留 Cookie；跨源剥离 Cookie，仅放行 https 或网易 CDN 白名单的 http。
    * POST 等非 GET 收到 3xx 不跟随（网易云业务接口不依赖 3xx）。最多 3 跳。
    */
   private static HttpResponse<String> safeSend(HttpRequest request)
         throws IOException, InterruptedException {
      URI origin = request.uri();
      if (!ReservedAddressGuard.isPublicReachable(origin.getHost())) {
         TraceLog.warn("拒绝访问内网 / 保留段或 DNS 解析到内网的目标：{}", redactUri(origin));
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      HttpRequest current = request;
      HttpClient client = proxyAwareClient();
      for (int hop = 0; hop < 3; hop++) {
         HttpResponse<byte[]> resp = client.send(current, HttpResponse.BodyHandlers.ofByteArray());
         byte[] body = resp.body();
         if (body.length > MAX_BODY_BYTES) {
            TraceLog.warn("网易云响应体超出 {} 字节上限：{}", MAX_BODY_BYTES, redactUri(current.uri()));
            MemoryWipe.wipe(body);
            throw new VendorException(VendorError.NETWORK_FAILURE);
         }
         if (resp.statusCode() >= 300 && resp.statusCode() < 400) {
            if (!"GET".equalsIgnoreCase(current.method())) {
               MemoryWipe.wipe(body);
               return SafeHttpClient.wrapString(resp, "");
            }
            String loc = resp.headers().firstValue("Location").orElse(null);
            if (loc == null || loc.isBlank()) {
               MemoryWipe.wipe(body);
               return SafeHttpClient.wrapString(resp, "");
            }
            URI next;
            try {
               // 相对 Location 相对「当前这一跳」解析：用最初 origin 作基准会在多跳后
               // 把 /a/b/c 下的相对路径错误解析到 /a 层级，请求打到不存在的地址。
               next = current.uri().resolve(loc);
            } catch (IllegalArgumentException invalidLoc) {
               MemoryWipe.wipe(body);
               return SafeHttpClient.wrapString(resp, "");
            }
            if (!allowRedirect(next, current)) {
               TraceLog.warn("拒绝不安全的重定向：{} -> {}（跨源剥离 Cookie，仅限 https 或音频 CDN）",
                     redactUri(origin), redactUri(next));
               MemoryWipe.wipe(body);
               return SafeHttpClient.wrapString(resp, "");
            }
            HttpRequest.Builder nb = HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(15));
            boolean sameOrigin = origin.getScheme() != null && next.getScheme() != null
                  && origin.getHost() != null && next.getHost() != null
                  && origin.getScheme().equalsIgnoreCase(next.getScheme())
                  && origin.getHost().equalsIgnoreCase(next.getHost());
            current.headers().map().forEach((n, vs) -> vs.forEach(v -> {
               if (sameOrigin || !n.equalsIgnoreCase("Cookie")) {
                  nb.header(n, v);
               }
            }));
            MemoryWipe.wipe(body);
            current = nb.GET().build();
            continue;
         }
         String text = SafeHttpClient.decodeText(resp, body);
         MemoryWipe.wipe(body);
         return SafeHttpClient.wrapString(resp, text);
      }
      HttpResponse<byte[]> last = client.send(current, HttpResponse.BodyHandlers.ofByteArray());
      byte[] lastBody = last.body();
      if (lastBody.length > MAX_BODY_BYTES) {
         TraceLog.warn("网易云响应体超出 {} 字节上限：{}", MAX_BODY_BYTES, redactUri(current.uri()));
         MemoryWipe.wipe(lastBody);
         throw new VendorException(VendorError.NETWORK_FAILURE);
      }
      String lastText = SafeHttpClient.decodeText(last, lastBody);
      MemoryWipe.wipe(lastBody);
      return SafeHttpClient.wrapString(last, lastText);
   }

   /** 网易云凭证 / JSON 通道响应体上限（2MB）。 */
   private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

   /** 跨源重定向目标校验：https 允许（DNS 解析后非保留段）；http 仅限网易 CDN 且请求不带 Cookie。 */
   private static boolean allowRedirect(URI target, HttpRequest request) {
      String scheme = target.getScheme();
      String host = target.getHost();
      if (scheme == null || host == null || !ReservedAddressGuard.isPublicReachable(host)) {
         return false;
      }
      if ("https".equalsIgnoreCase(scheme)) {
         return true;
      }
      if ("http".equalsIgnoreCase(scheme)) {
         boolean hasCookie = request.headers().map().keySet().stream()
               .anyMatch(n -> n.equalsIgnoreCase("Cookie"));
         return !hasCookie && isCdnHost(host);
      }
      return false;
   }

   private static boolean isCdnHost(String host) {
      String h = host.toLowerCase();
      return h.endsWith(".music.126.net") || h.endsWith(".music.163.com");
   }

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

   // ---------------------------------------------------------------- EAPI 与工具

   private static String eapiEncode(String apiPath, String json) throws Exception {
      String message = "nobody" + apiPath + "use" + json + "md5forencrypt";
      byte[] digest = MessageDigest.getInstance("MD5")
            .digest(message.getBytes(StandardCharsets.UTF_8));
      String plain = apiPath + "-36cd479b6b5-" + json + "-36cd479b6b5-" + hex(digest).toLowerCase();
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8), "AES"));
      return hex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
   }

   private static String hex(byte[] bytes) {
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
         result.append("%02X".formatted(value & 0xFF));
      }
      return result.toString();
   }

   private static String partOf(String cookies, String name) {
      for (String part : cookies.split(";")) {
         String trimmed = part.trim();
         if (trimmed.startsWith(name + "=")) {
            return trimmed.substring(name.length() + 1);
         }
      }
      return "";
   }

   /** 只保留网易云登录需要的 Cookie 字段（白名单过滤）。 */
   private static String filterAllowed(String raw) {
      String normalized = CookieCredential.normalize(raw);
      Set<String> allowed = Set.of("MUSIC_U", "MUSIC_A", "__csrf", "NMTID", "__remember_me");
      Properties values = new Properties();
      for (String part : normalized.split(";")) {
         int eq = part.indexOf('=');
         if (eq > 0) {
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (allowed.contains(name) && !value.isBlank()) {
               values.setProperty(name, value);
            }
         }
      }
      List<String> result = new ArrayList<>();
      values.forEach((key, valuex) -> result.add(key + "=" + valuex));
      return String.join("; ", result);
   }

   private static void persist() throws IOException {
      Files.createDirectories(SESSIONS_FILE.getParent());
      Properties p = new Properties();
      String encrypted = encryptCredential(credential);
      if (!encrypted.isBlank()) {
         p.setProperty("cookieEnc", encrypted);
      } else if (!credential.isBlank()) {
         // 密钥缺失时 encryptCredential 返回空串，凭证会被静默丢弃、重启后登录态凭空消失。
         // 显式留痕，便于把"重启掉登录"定位到密钥问题而不是平台问题。
         TraceLog.warn("本地加密密钥缺失，网易云登录凭证不会写入磁盘（重启后需重新登录）");
      }
      p.setProperty("nickname", displayName);
      try (BufferedWriter writer = Files.newBufferedWriter(SESSIONS_FILE, StandardCharsets.UTF_8)) {
         p.store(writer, "yunyy netease session - encrypted with local key, keep private");
      }
      hardenSessionFile();
   }

   /** 跨平台收紧凭证文件权限：POSIX rw-------；Windows ACL 仅当前用户可读（T-06）。 */
   private static void hardenSessionFile() {
      FileSecurity.hardenFile(SESSIONS_FILE);
      FileSecurity.hardenDirectory(SESSIONS_FILE.getParent());
   }

   /** 账号探测结果（引擎内部使用；对外由网关转 {@code AccountProfile}）。 */
   public record Profile(boolean active, String nickname, int vipType) {
   }

   private record CachedStream(String url, long resolvedAtMillis) {
   }

   /** 歌单概要（引擎内部）。 */
   public record FolderInfo(String id, String name, int trackCount) {
   }

   /** 歌单内容（引擎内部）。 */
   public record Contents(String name, List<SongMeta> tracks) {
   }

   /** 歌曲元数据（引擎内部）。 */
   public record SongMeta(String id, String title, String artist, long duration, String coverUrl) {
   }
}
