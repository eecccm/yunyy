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

package com.eecccm.yunyy.core.platform;

import com.eecccm.yunyy.core.account.FileSecurity;
import com.eecccm.yunyy.core.cache.BusinessCache;
import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.core.crypto.SecretCipher;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.core.path.StorageLocations;
import com.eecccm.yunyy.core.platform.kugou.KugouGateway;
import com.eecccm.yunyy.core.platform.netease.NeteaseGateway;
import com.eecccm.yunyy.core.platform.qqmusic.QQMusicGateway;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台总调度：当前服务商切换 + 各平台登录凭证持久化 + 能力路由。
 *
 * <p>凭证加密存储：本地密钥文件 + AES-GCM；读取时兼容旧 ECB/明文并自动迁移。
 * 酷狗设备指纹（dfid/mid/guid/mac/webgl）随会话一并加密持久化。</p>
 */
public final class VendorRegistry {

   private static final Path SESSIONS_FILE =
         StorageLocations.configDir().resolve("yunyy-providers.properties");
   /** 凭证加密密钥（本地生成，64 hex 字符 = 32 字节；兼容旧 32 hex）。 */
   private static final Path LOCAL_KEY =
         StorageLocations.configDir().resolve("yunyy-account.key");

   /**
    * 网关实例表。并发访问（搜索、歌词、歌单各自在不同 CompletableFuture 线程上取网关），
    * 必须用并发容器：EnumMap 的 get 与 put 并发会重分配底层数组，既可能丢条目，
    * 也可能让两个线程各自 new 一份网关——网关持有 token / userid / dfid 等会话语义状态，
    * 后建实例覆盖先建实例等于把刚登录的会话静默丢弃。
    */
   private static final Map<MusicVendor, VendorGateway> GATEWAYS = new ConcurrentHashMap<>();

   private static volatile MusicVendor current = MusicVendor.NETEASE;
   /** 显示 VIP 状态开关（默认开；关闭时账号检查只返回昵称）。 */
   private static volatile boolean vipProbe = true;
   /** 本地加密密钥；由 {@link #load()} 写入，其余线程在持久化路径读取。 */
   private static volatile String localKey = "";

   private VendorRegistry() {
   }

   static {
      load();
   }

   private static VendorGateway gatewayFor(MusicVendor vendor) {
      return GATEWAYS.computeIfAbsent(vendor, v -> switch (v) {
         case NETEASE -> new NeteaseGateway();
         case KUGOU -> new KugouGateway(MusicVendor.KUGOU);
         case QQ -> new QQMusicGateway(MusicVendor.QQ);
      });
   }

   /** 当前激活服务商的网关。 */
   public static VendorGateway gateway() {
      return gatewayFor(current);
   }

   public static MusicVendor activeVendor() {
      return current;
   }

   /** 切换当前服务商并持久化。 */
   public static void activate(MusicVendor vendor) {
      current = vendor;
      persist();
   }

   public static boolean hasSession() {
      return gateway().authenticated();
   }

   public static String accountName() {
      return gateway().accountName();
   }

   public static VendorGateway gateway(MusicVendor vendor) {
      return gatewayFor(vendor);
   }

   // ---- 能力路由（转发到当前网关；公开业务结果按分组 TTL 缓存）----

   @SuppressWarnings("unchecked")
   public static CompletableFuture<List<SongEntry>> search(String keyword, int page) {
      MusicVendor vendor = current;
      String normalized = keyword == null ? "" : keyword.trim();
      String cacheKey = vendor.tag + ":search:" + normalized.toLowerCase() + ":" + page;
      // 搜索公开结果在免费模式下同样允许缓存；仅缓存非空成功结果。
      List<SongEntry> cached = BusinessCache.get(BusinessCache.Group.SEARCH, cacheKey, List.class);
      if (cached != null) {
         return CompletableFuture.completedFuture(cached);
      }
      return gateway().search(keyword, page).thenApply(list -> {
         if (list != null && !list.isEmpty()) {
            BusinessCache.put(BusinessCache.Group.SEARCH, cacheKey, List.copyOf(list));
         }
         return list;
      });
   }

   @SuppressWarnings("unchecked")
   public static CompletableFuture<List<PlaylistFolder>> fetchPlaylists() {
      MusicVendor vendor = current;
      String cacheKey = vendor.tag + ":playlists";
      if (!FeatureSwitch.freeOnly) {
         List<PlaylistFolder> cached = BusinessCache.get(BusinessCache.Group.PLAYLISTS, cacheKey, List.class);
         if (cached != null) {
            return CompletableFuture.completedFuture(cached);
         }
      }
      return gateway().fetchPlaylists().thenApply(list -> {
         if (!FeatureSwitch.freeOnly && list != null && !list.isEmpty()) {
            BusinessCache.put(BusinessCache.Group.PLAYLISTS, cacheKey, List.copyOf(list));
         }
         return list;
      });
   }

   @SuppressWarnings("unchecked")
   public static CompletableFuture<PlaylistContents> fetchPlaylistSongs(String folderId) {
      MusicVendor vendor = current;
      String cacheKey = vendor.tag + ":songs:" + folderId;
      if (!FeatureSwitch.freeOnly) {
         PlaylistContents cached = BusinessCache.get(BusinessCache.Group.PLAYLIST_SONGS, cacheKey,
               PlaylistContents.class);
         if (cached != null && !cached.tracks().isEmpty()) {
            return CompletableFuture.completedFuture(cached);
         }
      }
      return gateway().fetchPlaylistSongs(folderId).thenApply(contents -> {
         if (!FeatureSwitch.freeOnly && contents != null && !contents.tracks().isEmpty()) {
            BusinessCache.put(BusinessCache.Group.PLAYLIST_SONGS, cacheKey, contents);
         }
         return contents;
      });
   }

   public static String resolveStreamUrl(MusicVendor vendor, String songId, String fallback) {
      return gatewayFor(vendor).resolveStreamUrl(songId, fallback);
   }

   public static CompletableFuture<String> fetchLyrics(MusicVendor vendor, String songId) {
      return gatewayFor(vendor).fetchLyrics(songId);
   }

   public static CompletableFuture<AccountProfile> verifyAccount() {
      MusicVendor vendor = current;
      String cacheKey = vendor.tag + ":profile";
      if (!FeatureSwitch.freeOnly) {
         AccountProfile cached = BusinessCache.get(BusinessCache.Group.PROFILE, cacheKey, AccountProfile.class);
         if (cached != null && cached.active()) {
            return CompletableFuture.completedFuture(
                  vipProbe ? cached : AccountProfile.copyWithoutNote(cached));
         }
      }
      return gateway().verifyAccount().thenApply(p -> {
         // 只缓存成功登录档案；失败 / 未登录 / 风控结果不缓存，避免掩盖登录态变化。
         if (!FeatureSwitch.freeOnly && p != null && p.active()) {
            BusinessCache.put(BusinessCache.Group.PROFILE, cacheKey, p);
         }
         return vipProbe ? p : AccountProfile.copyWithoutNote(p);
      });
   }

   public static CompletableFuture<AccountProfile> importCredential(String raw) {
      MusicVendor vendor = current;
      return gateway().importCredential(raw).thenApply(p -> {
         // 新凭证成功后旧账号下的歌单、歌曲与档案全部失效，整组清除。
         BusinessCache.clearGroup(BusinessCache.Group.PLAYLISTS, vendor);
         BusinessCache.clearGroup(BusinessCache.Group.PLAYLIST_SONGS, vendor);
         BusinessCache.clearGroup(BusinessCache.Group.PROFILE, vendor);
         return vipProbe ? p : AccountProfile.copyWithoutNote(p);
      });
   }

   public static CompletableFuture<AccountProfile> signInByPassword(String account, String password) {
      MusicVendor vendor = current;
      return gateway().signInByPassword(account, password).thenApply(p -> {
         BusinessCache.clearGroup(BusinessCache.Group.PLAYLISTS, vendor);
         BusinessCache.clearGroup(BusinessCache.Group.PLAYLIST_SONGS, vendor);
         BusinessCache.clearGroup(BusinessCache.Group.PROFILE, vendor);
         return vipProbe ? p : AccountProfile.copyWithoutNote(p);
      });
   }

   public static void signOut() {
      BusinessCache.clearVendor(current);
      gateway().signOut();
      persist();
   }

   public static VendorGateway.QrInit createQr() throws Exception {
      return gateway().createQr();
   }

   public static VendorGateway.QrPoll pollQr(String sessionKey) throws Exception {
      return gateway().pollQr(sessionKey);
   }

   /**
    * 代理刷新占位（三端客户端启动时调用，保持接口兼容）。
    * 凭证 / 账号通道始终直连；音频 CDN 与封面下载由各自通道按 AudioBridgeOptions 显式挂代理。
    */
   public static void syncProxy() {
   }

   public static boolean vipProbeEnabled() {
      return vipProbe;
   }

   public static void setVipProbeEnabled(boolean on) {
      vipProbe = on;
      VendorGateway g = gatewayFor(MusicVendor.KUGOU);
      if (g instanceof KugouGateway kg) {
         kg.setVipProbe(on);
      }
      persist();
   }

   /** 保存酷狗完整设备指纹（可手动粘贴酷狗客户端指纹覆盖；空值保留原值）。 */
   public static void setFingerprint(String dfid, String mid, String guid, String mac, String webgl) {
      VendorGateway g = gatewayFor(MusicVendor.KUGOU);
      if (g instanceof KugouGateway kg) {
         kg.setFingerprint(dfid, mid, guid, mac, webgl);
         persist();
      }
   }

   /** 当前酷狗设备指纹：[dfid, mid, guid, mac, webgl]。 */
   public static String[] fingerprint() {
      VendorGateway g = gatewayFor(MusicVendor.KUGOU);
      if (g instanceof KugouGateway kg) {
         return new String[]{kg.dfid(), kg.mid(), kg.guid(), kg.mac(), kg.webglHash()};
      }
      return new String[]{"", "", "", "", ""};
   }

   /**
    * 确保酷狗设备指纹完整（模组自包含：字段缺失自动随机补齐）。
    * 酷狗用户信息/VIP 接口会校验设备完整性；扫码登录使用自生成指纹，
    * register_dev 返回的真实 dfid 会覆盖并随 token 一起持久化。
    */
   public static boolean ensureLocalFingerprint() {
      VendorGateway g = gatewayFor(MusicVendor.KUGOU);
      if (!(g instanceof KugouGateway kg)) {
         return false;
      }
      try {
         kg.ensureDeviceFingerprint();
         persist();
         return true;
      } catch (Exception e) {
         return false;
      }
   }

   // ---------------- 持久化 ----------------

   private static void load() {
      localKey = loadKey();
      boolean migrated = false;
      if (!Files.isRegularFile(SESSIONS_FILE)) {
         return;
      }
      try (BufferedReader reader = Files.newBufferedReader(SESSIONS_FILE, StandardCharsets.UTF_8)) {
         Properties p = new Properties();
         p.load(reader);
         current = MusicVendor.byTag(p.getProperty("active", "netease"));
         String vc = p.getProperty("vipcheck", "on");
         vipProbe = !"off".equalsIgnoreCase(vc);
         VendorGateway ka = gatewayFor(MusicVendor.KUGOU);
         if (ka instanceof KugouGateway kg0) {
            kg0.setVipProbe(vipProbe);
         }
         for (MusicVendor t : MusicVendor.values()) {
            String cred = decryptProperty(p, t.tag + ".credentialEnc", t.tag + ".credential");
            if (cred.isBlank()) {
               continue;
            }
            try {
               VendorGateway a = gatewayFor(t);
               if (a instanceof KugouGateway kg) {
                  String[] parts = cred.split(";");
                  String tk = "";
                  String uid = "";
                  String nick = "";
                  String dfid = "";
                  for (String part : parts) {
                     int eq = part.indexOf('=');
                     if (eq > 0) {
                        String k = part.substring(0, eq).trim();
                        String v = part.substring(eq + 1).trim();
                        if (k.equals("token")) {
                           tk = v;
                        } else if (k.equals("userid")) {
                           uid = v;
                        } else if (k.equals("nickname")) {
                           nick = v;
                        } else if (k.equals("dfid")) {
                           dfid = v;
                        }
                     }
                  }
                  kg.restoreSession(tk, uid, nick, dfid);
                  String fpMid = decryptProperty(p, t.tag + ".fp_midEnc", t.tag + ".fp_mid");
                  String fpGuid = decryptProperty(p, t.tag + ".fp_guidEnc", t.tag + ".fp_guid");
                  String fpMac = decryptProperty(p, t.tag + ".fp_macEnc", t.tag + ".fp_mac");
                  String fpWebgl = decryptProperty(p, t.tag + ".fp_webglEnc", t.tag + ".fp_webgl");
                  if (!fpMid.isBlank() || !fpGuid.isBlank() || !fpMac.isBlank() || !fpWebgl.isBlank()) {
                     kg.setFingerprint(dfid, fpMid, fpGuid, fpMac, fpWebgl);
                  }
                  kg.setVipProbe(vipProbe);
                  if (p.getProperty(t.tag + ".credential") != null
                        || p.getProperty(t.tag + ".fp_mid") != null) {
                     migrated = true;
                  }
               } else if (a instanceof QQMusicGateway qq) {
                  qq.restoreSession(cred);
                  if (p.getProperty(t.tag + ".credential") != null) {
                     migrated = true;
                  }
               }
            } catch (Exception e) {
               TraceLog.warn("恢复平台会话失败：{}", t.tag, e);
            }
         }
      } catch (Exception e) {
         TraceLog.warn("无法读取平台登录状态", e);
      }
      if (migrated) {
         persist();
      }
   }

   /** 读取/生成本地加密密钥（POSIX 权限尽力收紧；Windows 静默忽略）。 */
   private static String loadKey() {
      if (Files.isRegularFile(LOCAL_KEY)) {
         try {
            String key = Files.readString(LOCAL_KEY, StandardCharsets.UTF_8).trim();
            if (key.length() == 32 || key.length() == 64) {
               return key;
            }
         } catch (Exception ignored) {
         }
      }
      String key = randomHexKey(64);
      try {
         Files.createDirectories(LOCAL_KEY.getParent());
         Files.writeString(LOCAL_KEY, key, StandardCharsets.UTF_8);
         FileSecurity.hardenFile(LOCAL_KEY);
         FileSecurity.hardenDirectory(LOCAL_KEY.getParent());
      } catch (Exception e) {
         TraceLog.warn("无法保存本地凭证加密密钥");
      }
      return key;
   }

   private static String randomHexKey(int length) {
      StringBuilder sb = new StringBuilder();
      SecureRandom random = new SecureRandom();
      String alphabet = "0123456789abcdef";
      for (int i = 0; i < length; i++) {
         sb.append(alphabet.charAt(random.nextInt(16)));
      }
      return sb.toString();
   }

   /** 优先读 GCM 加密字段；失败回退旧 ECB；再无则回退旧明文（自动迁移）。 */
   private static String decryptProperty(Properties p, String encKey, String plainKey) {
      String enc = p.getProperty(encKey, "");
      if (!enc.isBlank()) {
         try {
            return SecretCipher.gcmDecryptBase64(enc, localKey);
         } catch (Exception gcmFail) {
            try {
               return SecretCipher.ecbDecryptHex(enc, localKey);
            } catch (Exception ecbFail) {
               return "";
            }
         }
      }
      return p.getProperty(plainKey, "");
   }

   private static String encrypt(String plain) {
      if (plain.isBlank() || localKey.isBlank()) {
         return "";
      }
      try {
         return SecretCipher.gcmEncryptBase64(plain, localKey);
      } catch (Exception ignored) {
         return "";
      }
   }

   private static void persist() {
      try {
         Files.createDirectories(SESSIONS_FILE.getParent());
         // 密钥缺失时 encrypt() 一律返回空串，凭证与指纹会被静默丢弃、重启后登录态凭空消失。
         // 这里显式留痕，让"重启掉登录"有可查的日志证据，而不是无声降级。
         if (localKey.isBlank()) {
            TraceLog.warn("本地加密密钥缺失，平台会话与设备指纹不会写入磁盘（重启后需重新登录）");
         }
         Properties p = new Properties();
         p.setProperty("active", current.tag);
         p.setProperty("vipcheck", vipProbe ? "on" : "off");
         for (MusicVendor t : MusicVendor.values()) {
            VendorGateway a = gatewayFor(t);
            String cred = "";
            if (a instanceof KugouGateway kg) {
               cred = "token=" + kg.token() + ";userid=" + kg.userId()
                     + ";nickname=" + kg.accountName() + ";dfid=" + kg.dfid();
               p.setProperty(t.tag + ".fp_midEnc", encrypt(kg.mid()));
               p.setProperty(t.tag + ".fp_guidEnc", encrypt(kg.guid()));
               p.setProperty(t.tag + ".fp_macEnc", encrypt(kg.mac()));
               p.setProperty(t.tag + ".fp_webglEnc", encrypt(kg.webglHash()));
            } else if (a instanceof QQMusicGateway qq) {
               // QQ 凭证即原始 Cookie 字符串（含 uin / qm_keyst / qqmusic_key 等），本地密钥加密存储
               cred = qq.cookieSnapshot();
            }
            p.setProperty(t.tag + ".credentialEnc", encrypt(cred));
         }
         try (BufferedWriter writer = Files.newBufferedWriter(SESSIONS_FILE, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy platform sessions - encrypted with local key, keep private");
         }
         FileSecurity.hardenFile(SESSIONS_FILE);
         FileSecurity.hardenDirectory(SESSIONS_FILE.getParent());
      } catch (IOException e) {
         TraceLog.warn("保存平台登录状态失败", e);
      }
   }
}
