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

package com.eecccm.yunyy.core.platform.kugou;
import com.eecccm.yunyy.core.crypto.SecretCipher;
import com.eecccm.yunyy.core.net.SafeHttpClient;
import com.eecccm.yunyy.core.config.RuntimeRegistry;
import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.core.account.MemoryWipe;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.Redactor;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.platform.VendorGateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗音乐 API（标准版 / 概念版共用，通过配置区分）。
 */
public final class KugouGateway implements VendorGateway {
   private static final String GATEWAY = "https://gateway.kugou.com";
   /** 云歌单接口直连域（gateway 转发 cloudlist 会返回 20017；直连 HTTP 才能通过设备校验；https 证书不可信只能用 http）。 */
   private static final String CLOUDLIST = "http://cloudlist.service.kugou.com";
   private static final String TRACKERCDN = "https://trackercdngz.kugou.com";
   private static final String LOGIN_USER = "https://login-user.kugou.com";
   private static final String TRACKER = "https://tracker.kugou.com";
   // 上游仅提供明文 HTTP 的接口（实测 443 证书不含该域名，无法 https）：
   // relation.user.kugou.com（用户信息/VIP，含 token——依赖 SafeHttpClient 重定向防护）
   // mobilecdn.kugou.com（搜索/歌单/歌曲信息，不含 token）

   // 标准版
   private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
   private static final String STD_APPID = "1005";
   private static final String STD_CLIENTVER = "20489";
   private static final String STD_SALT = "OIlwieks28dk2k092lksi2UIkp";
   private static final String STD_APPKEY = "OIlwieks28dk2k092lksi2UIkp";
   private static final String STD_SIGNKEY_SALT = "57ae12eb6890223e355ccfcb74edf70d";
   // 概念版 (lite)
   private static final String LITE_APPID = "3116";
   private static final String LITE_CLIENTVER = "11440";
   private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
   private static final String LITE_APPKEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
   private static final String LITE_SIGNKEY_SALT = "185672dd44712f60bb1736df5a377e82";

   private static final String STD_PUBLIC_KEY_B64 =
         "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB";
   private static final String LITE_PUBLIC_KEY_B64 =
         "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB";

   private static final SecureRandom RANDOM = new SecureRandom();

   private final MusicVendor vendor;
   private final String appid;
   private final String clientver;
   private final String salt;
   private final String appkey;
   private final String signKeySalt;
   private final java.security.PublicKey publicKey;

   // 登录态
   private volatile String token = "";
   private volatile String userid = "";
   private volatile String authCache = "";
   private volatile String nicknameName = "";
   // 设备标识（模组自包含：首次运行随机生成完整指纹；扫码登录后 register_dev 会用服务端返回的真实 dfid 覆盖。
   // VIP/用户信息接口会校验设备完整性，字段缺失时可能返回 20006/20018/20017）
   private String dfid = randomString(24);
   private String mid = "";
   private String devGuid = "";
   private String devMac = "";
   private String devName = "SM-G9910";
   // WebGL 指纹哈希（32 位 hex，设备完整性校验的一部分）
   private String webglHash = "";
   // 显示VIP状态开关（默认开；关闭时检查账号状态只返回昵称，不请求 VIP 接口）
   private volatile boolean vipProbe = true;

   /** 流地址短 TTL 缓存（5 分钟，对齐网易云 STREAM_CACHE）；仅缓存成功非空、非 fallback 的最终播放地址；登出清空。 */
   private static final long STREAM_CACHE_MILLIS = Duration.ofMinutes(5L).toMillis();
   private final Map<String, CachedStream> streamCache = new ConcurrentHashMap<>();

   private record CachedStream(String url, long resolvedAtMillis) {
   }

   /**
    * 指纹可信度标记（T-01B）：仅当指纹来自用户手动粘贴（{@link #setFingerprint}）或平台
    * register_dev 服务端下发时才置 true；构造时随机生成、{@link #ensureDeviceFingerprint()}
    * 随机补齐均不可信。不可信时 VIP 探测不携带这套伪造指纹，也不据此伪造会员结论。
    */
   private volatile boolean fingerprintTrusted = false;

   /** 平台风控 / 设备完整性拦截错误码（命中后停止变体重试，上抛 RISK_CONTROLLED）。 */
   private static final Set<Integer> RISK_CODES = Set.of(20017, 20018, 20006, 8821);

   /** freeOnly（游客模式，T-02）：开启后不发起登录态 / 权益类接口，出站不携带 token / dfid。 */
   private static boolean freeOnly() {
      return FeatureSwitch.freeOnly;
   }

   /** freeOnly 下出站凭证一律置空（本地会话保留，仅请求不携带）。 */
   private String activeToken() {
      return freeOnly() ? "" : token;
   }

   private String activeUserid() {
      return freeOnly() ? "" : userid;
   }

   /** 权益 / 账号 / 歌单 / VIP 接口在 freeOnly 下直接拒绝，仅公开音源可用。 */
   private void requireNotFreeOnly() {
      if (freeOnly()) {
         throw new VendorException(VendorError.FREE_MODE_ONLY);
      }
   }

   /** 命中风控 / 设备拦截码时抛 RISK_CONTROLLED（原始码仅写入开发者日志，不上屏）。 */
   private void throwIfRiskControl(JsonObject root, String tag) {
      if (root == null || !root.has("error_code") || !root.get("error_code").isJsonPrimitive()) {
         return;
      }
      long code;
      try {
         code = root.get("error_code").getAsLong();
      } catch (Exception ignored) {
         return;
      }
      if (RISK_CODES.contains((int) code)) {
         TraceLog.warn("KG {} 风控拦截 error_code={}", tag, code);
         throw new VendorException(VendorError.RISK_CONTROLLED, vendor.label, String.valueOf(code));
      }
   }

   private static String calculateMid(String guid) {
      String digest = SecretCipher.md5Hex(guid);
      java.math.BigInteger bi = new java.math.BigInteger(digest, 16);
      return bi.toString(10);
   }

   /** 生成随机 32 位十六进制串（guid/webgl 指纹用），使用 SecureRandom 保证熵一致。 */
   private static String randomHex(int len) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
         sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
      }
      return sb.toString();
   }

   /** 生成随机 MAC 地址（6 组两位十六进制，冒号分隔），使用 SecureRandom。 */
   private static String randomMac() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 6; i++) {
         if (i > 0) sb.append(":");
         sb.append(String.format("%02X", RANDOM.nextInt(256)));
      }
      return sb.toString();
   }

   /** 首次运行随机生成完整设备指纹（不依赖任何外部软件，每台机器独立）。 */
   private void initDeviceFingerprint() {
      devGuid = randomHex(32);
      mid = calculateMid(devGuid);
      devMac = randomMac();
      webglHash = randomHex(32);
   }

   public KugouGateway(MusicVendor vendor) {
      boolean lite = false; // 酷狗/概念版合并后统一使用标准版参数（与客户端取值完全一致：appid=1005/OIlwieks）
      this.vendor = vendor;
      this.appid = lite ? LITE_APPID : STD_APPID;
      this.clientver = lite ? LITE_CLIENTVER : STD_CLIENTVER;
      this.salt = lite ? LITE_SALT : STD_SALT;
      this.appkey = lite ? LITE_APPKEY : STD_APPKEY;
      this.signKeySalt = lite ? LITE_SIGNKEY_SALT : STD_SIGNKEY_SALT;
      this.publicKey = SecretCipher.rsaPublicKeyFromBase64(lite ? LITE_PUBLIC_KEY_B64 : STD_PUBLIC_KEY_B64);
      initDeviceFingerprint();
   }

   public void setSession(String token, String userid, String nicknameName) {
      this.token = token == null ? "" : token;
      this.userid = userid == null ? "" : userid;
      this.nicknameName = nicknameName == null ? "" : nicknameName;
      registerDevice();
   }

   /** 恢复本地保存的会话：总是用当前 token 重新注册设备（register_dev 以 token 绑定新 dfid，
    *  确保云歌单 20017 设备校验通过）；注册失败才回退保存的 dfid。 */
   public void restoreSession(String token, String userid, String nicknameName, String savedDfid) {
      this.token = token == null ? "" : token;
      this.userid = userid == null ? "" : userid;
      this.nicknameName = nicknameName == null ? "" : nicknameName;
      try {
         registerDevice();
      } catch (Exception e) {
         TraceLog.warn("KG restoreSession 设备注册失败，回退保存 dfid", e);
         if (savedDfid != null && !savedDfid.isBlank() && savedDfid.length() >= 8) {
            this.dfid = savedDfid;
         }
      }
   }

   /** 设置完整设备指纹（可手动粘贴酷狗客户端 Cookie/指纹覆盖，解决用户信息/VIP 接口 20006/20018/20017 校验失败）。
    *  空值保留原值；只要任一字段被手动填入，即标记指纹可信（T-01B：手动粘贴是唯一可信来源）。 */
   public void setFingerprint(String dfid, String mid, String guid, String mac, String webgl) {
      boolean trusted = false;
      if (dfid != null && !dfid.isBlank() && dfid.length() >= 8) { this.dfid = dfid.trim(); trusted = true; }
      if (mid != null && !mid.isBlank() && mid.length() >= 8) { this.mid = mid.trim(); trusted = true; }
      if (guid != null && !guid.isBlank()) { this.devGuid = guid.trim(); trusted = true; }
      if (mac != null && !mac.isBlank()) { this.devMac = mac.trim(); trusted = true; }
      if (webgl != null && !webgl.isBlank()) { this.webglHash = webgl.trim(); trusted = true; }
      if (trusted) {
         this.fingerprintTrusted = true;
      }
   }

   /** 确保设备指纹完整（模组自包含：字段缺失时随机补齐，不依赖任何外部软件）。 */
   public void ensureDeviceFingerprint() {
      if (dfid == null || dfid.isBlank() || dfid.length() < 8) dfid = randomString(24);
      if (devGuid == null || devGuid.isBlank()) devGuid = randomHex(32);
      if (mid == null || mid.isBlank() || mid.length() < 8) mid = calculateMid(devGuid);
      if (devMac == null || devMac.isBlank()) devMac = randomMac();
      if (webglHash == null || webglHash.isBlank()) webglHash = randomHex(32);
   }

   public String mid() {
      return mid;
   }

   public String guid() {
      return devGuid;
   }

   public String mac() {
      return devMac;
   }

   public String webglHash() {
      return webglHash;
   }

   /** 显示VIP状态开关：打开时检查账号状态会请求 VIP 接口（get_union_vip + 指纹组合兜底）；关闭时只返回昵称。 */
   public void setVipProbe(boolean on) {
      this.vipProbe = on;
   }

   public boolean vipProbe() {
      return vipProbe;
   }

   /** 酷狗 register_dev：注册设备拿真实 dfid（未注册设备会被 userinfo/播放接口拒绝）。失败不阻塞登录。 */
   private void registerDevice() {
      // T-02：游客模式不发起带 token 的设备注册，出站不携带账号凭证。
      if (freeOnly()) {
         return;
      }
      try {
         long clienttimeMs = System.currentTimeMillis();
         JsonObject dataMap = new JsonObject();
         dataMap.addProperty("availableRamSize", 4983533568L);
         dataMap.addProperty("availableRomSize", 48114719);
         dataMap.addProperty("availableSDSize", 48114717);
         dataMap.addProperty("basebandVer", "");
         dataMap.addProperty("batteryLevel", 100);
         dataMap.addProperty("batteryStatus", 3);
         dataMap.addProperty("brand", "Redmi");
         dataMap.addProperty("buildSerial", "unknown");
         dataMap.addProperty("device", "marble");
         dataMap.addProperty("imei", devGuid);
         dataMap.addProperty("imsi", "");
         dataMap.addProperty("manufacturer", "Xiaomi");
         dataMap.addProperty("uuid", devGuid);
         dataMap.addProperty("accelerometer", false);
         dataMap.addProperty("accelerometerValue", "");
         dataMap.addProperty("gravity", false);
         dataMap.addProperty("gravityValue", "");
         dataMap.addProperty("gyroscope", false);
         dataMap.addProperty("gyroscopeValue", "");
         dataMap.addProperty("light", false);
         dataMap.addProperty("lightValue", "");
         dataMap.addProperty("magnetic", false);
         dataMap.addProperty("magneticValue", "");
         dataMap.addProperty("orientation", false);
         dataMap.addProperty("orientationValue", "");
         dataMap.addProperty("pressure", false);
         dataMap.addProperty("pressureValue", "");
         dataMap.addProperty("step_counter", false);
         dataMap.addProperty("step_counterValue", "");
         dataMap.addProperty("temperature", false);
         dataMap.addProperty("temperatureValue", "");
         String[] aes = SecretCipher.kgPayloadEncrypt(dataMap.toString());
         String p = SecretCipher.rsaEncryptHexUpper(
               "{\"aes\":\"" + aes[0] + "\",\"uid\":" + (userid.isBlank() ? "0" : userid)
                     + ",\"token\":\"" + token + "\"}", publicKey).toUpperCase();
         long ct = clienttimeMs / 1000;
         Map<String, String> params = new LinkedHashMap<>();
         params.put("part", "1");
         params.put("platid", "1");
         // 客户端默认参数（dfid/mid/uuid/appid/clientver/clienttime/token/userid）也要进 URL 并参与签名
         params.put("dfid", dfid);
         params.put("mid", mid);
         params.put("uuid", "-");
         params.put("appid", appid);
         params.put("clientver", clientver);
         params.put("clienttime", String.valueOf(ct));
         if (!token.isBlank()) params.put("token", token);
         if (!userid.isBlank()) params.put("userid", userid);
         params.put("p", p);
         params.put("signature", androidSignature(params, aes[1]));
         HttpRequest.Builder b = SafeHttpClient.builder("https://userservice.kugou.com/risk/v2/r_register_dev?"
               + SecretCipher.encodedQuery(params));
         b.header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ct));
         b.header("mid", mid);
         b.header("kg-rc", "1");
         b.header("kg-thash", "5d816a0");
         b.header("kg-rec", "1");
         b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
         b.header("Content-Type", "application/json");
         b.POST(HttpRequest.BodyPublishers.ofString(aes[1]));
         HttpResponse<byte[]> resp = SafeHttpClient.downloadBytes(b.build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         byte[] respBody = resp.body();
         String bodySafe = respBody == null ? "null" : java.util.Base64.getEncoder().encodeToString(
               java.util.Arrays.copyOf(respBody, Math.min(48, respBody.length)));
         TraceLog.warn("KG registerDev http=" + resp.statusCode() + " uidLen=" + userid.length() + " body=" + bodySafe);
         try {
         } catch (Exception ignored) {
         }
         if (resp.statusCode() == 200 && respBody != null && respBody.length > 0) {
            try {
               // 与客户端一致：响应为 AES 密文（base64），用本次请求的随机 key 解密
               String dec = SecretCipher.kgPayloadDecrypt(
                     java.util.Base64.getEncoder().encodeToString(respBody), aes[0]);
               JsonObject root = JsonParser.parseString(dec).getAsJsonObject();
               if (root.has("data")) {
                  JsonObject data = root.getAsJsonObject("data");
                  // 服务端返回的完整设备三元组：dfid/mid/guid/mac/webgl 全部采用，
                  // 保证后续云歌单/VIP 接口的设备校验通过（缺失时保留本地值）
                  String nd = str(data, "dfid");
                  if (nd != null && !nd.isBlank() && nd.length() >= 8) {
                     this.dfid = nd;
                  }
                  String nm = str(data, "mid");
                  if (nm != null && !nm.isBlank() && nm.length() >= 8) {
                     this.mid = nm;
                  }
                  String ng = str(data, "guid");
                  if (ng != null && !ng.isBlank()) {
                     this.devGuid = ng;
                  }
                  String nma = str(data, "mac");
                  if (nma != null && !nma.isBlank()) {
                     this.devMac = nma;
                  }
                  String nw = str(data, "webgl");
                  if (nw != null && !nw.isBlank()) {
                     this.webglHash = nw;
                  }
                  // 平台 register_dev 服务端下发三元组，指纹视为可信（T-01B）
                  this.fingerprintTrusted = true;
               }
            } catch (Exception ignored) {
               // 解密失败则保留原 dfid
            } finally {
               // R-01：响应临时缓冲用完即覆写，缩短明文驻留窗口
               MemoryWipe.wipe(respBody);
            }
         }
      } catch (Exception e) {
         TraceLog.warn("KG registerDev fail", e);
      }
   }

   public String token() {
      return token;
   }

   public String dfid() {
      return dfid;
   }

   public String userId() {
      return userid;
   }

   @Override
   public MusicVendor vendor() {
      return vendor;
   }

   @Override
   public boolean authenticated() {
      return !token.isBlank() && !userid.isBlank();
   }

   @Override
   public String accountName() {
      // 空串表示"无昵称"，兜底文案由 mccore 显示层按语言键渲染。
      return nicknameName == null ? "" : nicknameName;
   }

   private static String randomString(int len) {
      String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
         sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
      }
      return sb.toString();
   }

   /** android 签名：盐 + 排序参数串(k=v) + data + 盐，MD5。 */
   private String androidSignature(Map<String, String> params, String data) {
      TreeMap<String, String> sorted = new TreeMap<>(params);
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, String> e : sorted.entrySet()) {
         sb.append(e.getKey()).append('=').append(e.getValue());
      }
      return SecretCipher.md5Hex(salt + sb + (data == null ? "" : data) + salt);
   }

   /** 标准版平台的 Android 签名（客户端默认 encryptType=android，用标准版盐 OIlwieks）。 */
   private String stdAndroidSignature(Map<String, String> params, String data) {
      TreeMap<String, String> sorted = new TreeMap<>(params);
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, String> e : sorted.entrySet()) {
         sb.append(e.getKey()).append('=').append(e.getValue());
      }
      return SecretCipher.md5Hex(STD_SALT + sb + (data == null ? "" : data) + STD_SALT);
   }

   /** 概念版（lite）平台的 Android 签名（盐 LnT6xp...）。 */
   private String liteAndroidSignature(Map<String, String> params, String data) {
      TreeMap<String, String> sorted = new TreeMap<>(params);
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, String> e : sorted.entrySet()) {
         sb.append(e.getKey()).append('=').append(e.getValue());
      }
      return SecretCipher.md5Hex(LITE_SALT + sb + (data == null ? "" : data) + LITE_SALT);
   }

   /** 网关 GET（带签名与设备头）。 */
   private JsonObject gatewayGet(String path, Map<String, String> extra, Map<String, String> extraHeaders) throws Exception {
      long clienttime = System.currentTimeMillis() / 1000;
      Map<String, String> params = new LinkedHashMap<>();
      params.put("dfid", dfid);
      params.put("mid", mid);
      params.put("uuid", "-");
      params.put("appid", appid);
      params.put("clientver", clientver);
      params.put("clienttime", String.valueOf(clienttime));
      String tk = activeToken();
      String auid = activeUserid();
      if (!tk.isBlank()) {
         params.put("token", tk);
      }
      if (!auid.isBlank() && !auid.equals("0")) {
         params.put("userid", auid);
      }
      if (extra != null) {
         params.putAll(extra);
      }
      params.put("signature", androidSignature(params, ""));

      HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + path + "?" + SecretCipher.encodedQuery(params));
      b.header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
      b.header("dfid", dfid);
      b.header("clienttime", String.valueOf(clienttime));
      b.header("mid", mid);
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      if (extraHeaders != null) {
         extraHeaders.forEach(b::header);
      }
      HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
      if (resp.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      return JsonParser.parseString(resp.body()).getAsJsonObject();
   }

   /** 明文 android 签名 POST（云歌单直连 cloudlist 域，HTTP），用于 v7/v4 云歌单接口。 */
   private JsonObject androidPostVariant(String path, JsonObject dataMap, Map<String, String> extra, String xRouter,
         boolean lite) throws Exception {
      long ct = System.currentTimeMillis() / 1000;
      String body = dataMap.toString();
      String useAppid = lite ? LITE_APPID : appid;
      String useClientver = lite ? LITE_CLIENTVER : clientver;
      Map<String, String> prm = new LinkedHashMap<>();
      prm.put("dfid", dfid);
      prm.put("mid", mid);
      prm.put("uuid", "-");
      prm.put("appid", useAppid);
      prm.put("clientver", useClientver);
      prm.put("clienttime", String.valueOf(ct));
      if (extra != null) {
         prm.putAll(extra);
      }
      if (!activeToken().isBlank()) {
         prm.put("token", activeToken());
      }
      if (!activeUserid().isBlank() && !activeUserid().equals("0")) {
         prm.put("userid", activeUserid());
      }
      prm.put("signature", lite ? liteAndroidSignature(prm, body) : androidSignature(prm, body));
      // 直连 cloudlist 域（gateway 转发会 20017）；http 目标经 SafeHttpClient 白名单（*.kugou.com）放行
      HttpRequest.Builder b = SafeHttpClient.builder(CLOUDLIST + path + "?" + SecretCipher.encodedQuery(prm));
      b.header("User-Agent", lite ? "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
            : "Android16-1070-11440-130-0-LOGIN-wifi");
      b.header("dfid", dfid);
      b.header("clienttime", String.valueOf(ct));
      b.header("mid", mid);
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      b.header("Content-Type", "application/json");
      b.header("Cookie", deviceCookie());
      b.POST(HttpRequest.BodyPublishers.ofString(body));
      HttpResponse<String> resp = SafeHttpClient.send(b.build());
      if (resp.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      return JsonParser.parseString(resp.body()).getAsJsonObject();
   }

   /** 设备指纹 Cookie（与客户端一致）：云歌单/VIP 接口做设备完整性校验，缺字段会 20017/20006。
    *  token/userid 同时进 Cookie 与 URL 参数是酷狗 Android 客户端行为；SafeHttpClient 已做跨域重定向剥离。 */
   private String deviceCookie() {
      StringBuilder c = new StringBuilder();
      c.append("kg_mid=").append(mid).append("; dfid=").append(dfid)
            .append("; KUGOU_API_MID=").append(mid)
            .append("; KUGOU_API_GUID=").append(devGuid)
            .append("; KUGOU_API_MAC=").append(devMac)
            .append("; KUGOU_API_DEV=").append(devName);
      String tk = activeToken();
      String auid = activeUserid();
      if (!tk.isBlank()) {
         c.append("; token=").append(tk);
      }
      if (!auid.isBlank() && !auid.equals("0")) {
         c.append("; userid=").append(auid);
      }
      return c.toString();
   }

   /** 日志脱敏辅助：只打印响应关键字段，截断长内容，绝不输出 token 等敏感参数。 */
   private void logResp(String tag, JsonObject root) {
      try {
         if (root == null) {
            TraceLog.info(vendor.label + " " + tag + " -> null");
            return;
         }
         JsonElement st = root.get("status");
         JsonElement err = root.get("error_code");
         StringBuilder sb = new StringBuilder();
         if (st != null) {
            sb.append("status=").append(st);
         }
         if (err != null) {
            sb.append(" error_code=").append(err);
         }
         JsonElement data = root.get("data");
         if (data != null && data.isJsonObject()) {
            JsonObject d = data.getAsJsonObject();
            // 打印服务端业务错误说明（20017=params invalid 等），帮助定位设备校验问题
            JsonElement em = d.get("errmsg");
            if (em != null && em.isJsonPrimitive()) {
               sb.append(" errmsg=").append(em.getAsString());
            }
            JsonElement count = d.get("count");
            JsonElement info = d.get("info");
            if (count != null) {
               sb.append(" count=").append(count);
            }
            if (info != null && info.isJsonArray()) {
               sb.append(" infoSize=").append(info.getAsJsonArray().size());
            }
         }
         TraceLog.info(vendor.label + " " + tag + " -> " + sb);
      } catch (Exception ignored) {
      }
   }

   /** 静默等待，供重试间隔使用（不抛异常）。 */
   private static void sleepQuiet(long millis) {
      try {
         Thread.sleep(millis);
      } catch (InterruptedException ignored) {
         Thread.currentThread().interrupt();
      }
   }

   /** 十六进制字符串转字节数组（加密歌单请求体解码用）。 */
   private static byte[] hexToBytes(String hex) {
      int len = hex.length() / 2;
      byte[] out = new byte[len];
      for (int i = 0; i < len; i++) {
         out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
   }

   private static String decryptAes(byte[] ciphertext, String key, String iv) {
      try {
         javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
         c.init(javax.crypto.Cipher.DECRYPT_MODE,
               new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
               new javax.crypto.spec.IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
         byte[] plain = c.doFinal(ciphertext);
         // PKCS7 可能产生无意义的字节，尝试按 UTF-8 解码并去掉尾部 NUL
         String s = new String(plain, StandardCharsets.UTF_8);
         int end = s.indexOf('\u0000');
         return end >= 0 ? s.substring(0, end) : s;
      } catch (Exception e) {
         return "";
      }
   }

   @Override
   public CompletableFuture<List<SongEntry>> search(String query, int page) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            // gateway /v2/search/song 需设备注册(mid/dfid)，改用免登录的 mobilecdn 公开接口
            String url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword="
                  + SecretCipher.urlEncode(query) + "&page=" + (page + 1) + "&pagesize=10&showtype=1";
            HttpRequest.Builder b = SafeHttpClient.builder(url);
            b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
            HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
            if (resp.statusCode() != 200) {
               throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            JsonArray lists = data != null && data.has("info") && data.get("info").isJsonArray()
                  ? data.getAsJsonArray("info") : null;
            List<SongEntry> tracks = new ArrayList<>();
            if (lists != null) {
               for (JsonElement e : lists) {
                  if (tracks.size() >= 10 || !e.isJsonObject()) {
                     break;
                  }
                  JsonObject s = e.getAsJsonObject();
                  String hash = str(s, "hash");
                  if (hash.isBlank()) {
                     continue;
                  }
                  long dur = s.has("duration") && s.get("duration").isJsonPrimitive()
                        ? s.get("duration").getAsLong() * 1000L : 0L;
                  tracks.add(new SongEntry(vendor, hash, str(s, "songname"), str(s, "singername"), dur, kugouCover(s), addTimeOf(s)));
               }
            }
            return backfillCovers(tracks);
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.SEARCH_FAILED, e, vendor.label);
         }
      });
   }

   @Override
   public CompletableFuture<List<PlaylistFolder>> fetchPlaylists() {
      return CompletableFuture.supplyAsync(() -> {
         requireNotFreeOnly();
         if (!authenticated()) {
            throw new VendorException(VendorError.NOT_LOGGED_IN, vendor.label);
         }
         try {
            // 先刷新用户信息，拿到服务端绑定的 dfid（云歌单接口要求 dfid 与 token 匹配，否则 20017）
            try {
               userInfo();
            } catch (Exception ex) {
               TraceLog.warn(vendor.label + " playlist v3 请求异常", ex);
            }
            List<PlaylistFolder> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // 1) LITE（概念版）v7/get_all_list：沿用上游公开 H5 客户端参数（appid=3116/clientver=11440/LITE 盐/total_ver=979/type=2/plat=1/x-router）
            try {
               for (int page = 1; page <= 5; page++) {
                  JsonObject dataMap = new JsonObject();
                  dataMap.addProperty("userid", userid);
                  dataMap.addProperty("token", token);
                  dataMap.addProperty("total_ver", 979);
                  dataMap.addProperty("type", 2);
                  dataMap.addProperty("page", page);
                  dataMap.addProperty("pagesize", 30);
                  Map<String, String> extra = new LinkedHashMap<>();
                  extra.put("plat", "1");
                  extra.put("userid", userid);
                  extra.put("token", token);
                  JsonObject root = androidPostVariant("/v7/get_all_list", dataMap, extra, "cloudlist.service.kugou.com", true);
                  logResp("KG playlist list LITE", root);
                  int before = out.size();
                  addPlaylistsFromV7(root, out, seen);
                  JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
                        ? root.getAsJsonObject("data") : null;
                  JsonArray arr = dataObj != null && dataObj.has("info") && dataObj.get("info").isJsonArray()
                        ? dataObj.getAsJsonArray("info") : null;
                  if (arr == null || arr.size() < 30 || out.size() == before) {
                     break;
                  }
               }
            } catch (Exception ex) {
               TraceLog.warn(vendor.label + " playlist v4 请求异常", ex);
            }
            // 2) STD（标准版）v7/get_all_list：标准版 token 环境使用
            if (out.isEmpty()) {
               try {
                  for (int page = 1; page <= 5; page++) {
                     JsonObject dataMap = new JsonObject();
                     dataMap.addProperty("userid", userid);
                     dataMap.addProperty("token", token);
                     dataMap.addProperty("total_ver", 979);
                     dataMap.addProperty("type", 2);
                     dataMap.addProperty("page", page);
                     dataMap.addProperty("pagesize", 30);
                     Map<String, String> extra = new LinkedHashMap<>();
                     extra.put("plat", "1");
                     extra.put("userid", userid);
                     extra.put("token", token);
                     JsonObject root = androidPostVariant("/v7/get_all_list", dataMap, extra, "cloudlist.service.kugou.com", false);
                     logResp("KG playlist list STD", root);
                     int before = out.size();
                     addPlaylistsFromV7(root, out, seen);
                     JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
                           ? root.getAsJsonObject("data") : null;
                     JsonArray arr = dataObj != null && dataObj.has("info") && dataObj.get("info").isJsonArray()
                           ? dataObj.getAsJsonArray("info") : null;
                     if (arr == null || arr.size() < 30 || out.size() == before) {
                        break;
                     }
                  }
               } catch (Exception ignored) {
               }
            }
            // 3) 兜底：默认收藏歌单（collection_3_{uid}_2_0）（/pubsongs/v2/get_other_list_file_nofilt）
            try {
               String gid = "collection_3_" + userid + "_2_0";
               Map<String, String> extra = new LinkedHashMap<>();
               extra.put("area_code", "1");
               extra.put("begin_idx", "0");
               extra.put("plat", "1");
               extra.put("type", "1");
               extra.put("mode", "1");
               extra.put("personal_switch", "1");
               extra.put("extend_fields", "abtags,hot_cmt,popularization");
               extra.put("pagesize", "1");
               extra.put("global_collection_id", gid);
               JsonObject root = gatewayGet("/pubsongs/v2/get_other_list_file_nofilt", extra, null);
               JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
                     ? root.getAsJsonObject("data") : null;
               if (dataObj != null) {
                  int cnt = dataObj.has("count") && dataObj.get("count").isJsonPrimitive()
                        ? dataObj.get("count").getAsInt() : 0;
                  String nm = "";
                  if (dataObj.has("list_info") && dataObj.get("list_info").isJsonObject()) {
                     JsonObject li = dataObj.getAsJsonObject("list_info");
                     if (li.has("name") && li.get("name").isJsonPrimitive()
                           && !li.get("name").getAsString().isBlank()) {
                        nm = li.get("name").getAsString();
                     }
                  }
                  if (seen.add(gid)) {
                     out.add(new PlaylistFolder(gid, nm, cnt));
                  }
               }
            } catch (Exception ignored) {
            }
            if (out.isEmpty()) {
               throw new VendorException(VendorError.PLAYLIST_LOAD_FAILED, vendor.label);
            }
            return out;
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_LOAD_FAILED, e, vendor.label);
         }
      });
   }

   /** 解析 v7/get_all_list 响应中的歌单列表（data.info 数组），并按 id 去重追加。 */
   private void addPlaylistsFromV7(JsonObject root, List<PlaylistFolder> out, Set<String> seen) {
      JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
            ? root.getAsJsonObject("data") : null;
      JsonArray arr = dataObj != null && dataObj.has("info") && dataObj.get("info").isJsonArray()
            ? dataObj.getAsJsonArray("info") : null;
      if (arr == null) {
         return;
      }
      for (JsonElement e : arr) {
         if (!e.isJsonObject()) {
            continue;
         }
         JsonObject p = e.getAsJsonObject();
         String id = str(p, "listid");
         if (id.isBlank()) {
            id = str(p, "list_create_listid");
         }
         if (id.isBlank()) {
            id = str(p, "specialid");
         }
         if (id.isBlank()) {
            continue;
         }
         // 编码附加 id：musiclib_id | global_collection_id，供歌曲端点多级兜底
         String musiclibId = str(p, "musiclib_id");
         String gid = str(p, "global_collection_id");
         String fullId = id;
         if (!musiclibId.isBlank()) {
            fullId = id + "|" + musiclibId;
            if (!gid.isBlank()) {
               fullId += "|" + gid;
            }
         } else if (!gid.isBlank()) {
            fullId = id + "||" + gid;
         }
         String name = str(p, "name");
         if (name.isBlank()) {
            name = str(p, "specialname");
         }
         if (name.isBlank()) {
            name = "";
         }
         // 追加 list_create_gid 与歌单名（收藏歌单兜底：搜索匹配创建者 gid → specialid → 网页取歌）
         String createGid = str(p, "list_create_gid");
         String safeName = name.replace("|", "｜");
         fullId = fullId + "|" + createGid + "|" + safeName;
         int cnt = p.has("count") && p.get("count").isJsonPrimitive()
               ? p.get("count").getAsInt()
               : (p.has("m_count") && p.get("m_count").isJsonPrimitive() ? p.get("m_count").getAsInt() : 0);
         if (seen.add(fullId)) {
            out.add(new PlaylistFolder(fullId, name, cnt));
         }
      }
   }

   @Override
   public CompletableFuture<PlaylistContents> fetchPlaylistSongs(String playlistId) {
      return CompletableFuture.supplyAsync(() -> {
         requireNotFreeOnly();
         if (!authenticated()) {
            throw new VendorException(VendorError.NOT_LOGGED_IN, vendor.label);
         }
         // 解码 id：listid | musiclib_id | global_collection_id | list_create_gid | 歌单名
         String listid = playlistId;
         String musiclibId = "";
         String gid = "";
         String createGid = "";
         String pName = "";
         if (playlistId.contains("|")) {
            String[] parts = playlistId.split("\\|", -1);
            listid = parts[0];
            if (parts.length > 1) {
               musiclibId = parts[1];
            }
            if (parts.length > 2) {
               gid = parts[2];
            }
            if (parts.length > 3) {
               createGid = parts[3];
            }
            if (parts.length > 4) {
               pName = parts[4].replace("｜", "|");
            }
         }
         try {
            // 0) collection gid（收藏视角歌单，如 collection_3_{uid}_2_0）不是 listid，云歌单接口必然 20017：
            //    直接跳过 v3/v4，走下方收藏歌单专用兜底链（搜索创建者 gid → special/song → pubsongs）
            boolean collectionGid = listid.startsWith("collection_");
            // 1) /v4/get_list_all_file_v3：STD（标准版）优先——v7 实测 STD 通、LITE 20017；失败再 LITE 概念版
            for (int variant = 0; variant < 2 && !collectionGid; variant++) {
               boolean lite = variant == 1;
               try {
                  PlaylistContents pt = playlistTracksViaCloudlist("/v4/get_list_all_file_v3", lite, listid, false);
                  if (pt != null && !pt.tracks().isEmpty()) {
                     return pt;
                  }
                  TraceLog.warn(vendor.label + " playlist v3 解析为空" + (lite ? " LITE" : " STD"));
               } catch (Exception ignored) {
               }
            }
            // 2) /v4/get_list_all_file（上游公开端点）：同样 STD 优先，失败再 LITE
            for (int variant = 0; variant < 2 && !collectionGid; variant++) {
               boolean lite = variant == 1;
               try {
                  PlaylistContents pt = playlistTracksViaCloudlist("/v4/get_list_all_file", lite, listid, true);
                  if (pt != null && !pt.tracks().isEmpty()) {
                     return pt;
                  }
                  TraceLog.warn(vendor.label + " playlist v4 解析为空" + (lite ? " LITE" : " STD"));
               } catch (Exception ignored) {
               }
            }
            // 3) 网页版歌单 API：musiclib_id 作为 specialid（阅读学习/纯音乐等音乐库歌单）
            if (!musiclibId.isBlank()) {
               try {
                  String url = "https://mobilecdn.kugou.com/api/v3/special/song?specialid=" + musiclibId
                        + "&page=1&pagesize=300&filter=0&plat=0&version=8990";
                  HttpRequest.Builder b = SafeHttpClient.builder(url);
                  b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
                  HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
                  if (resp.statusCode() == 200) {
                     JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                     logResp("KG playlist web-special", root);
                     PlaylistContents pt = parseListAllFile(root);
                     if (pt != null && !pt.tracks().isEmpty()) {
                        return decoratePlaylist(pt);
                     }
                  }
                  TraceLog.warn(vendor.label + " playlist web-special 解析为空, id=" + musiclibId);
               } catch (Exception ignored) {
               }
               // 4) 网页版专辑 API：musiclib_id 作为 albumid（魔杰座等音乐号歌单）
               try {
                  String url = "https://mobilecdn.kugou.com/api/v3/album/song?albumid=" + musiclibId
                        + "&page=1&pagesize=300&plat=0&version=8990";
                  HttpRequest.Builder b = SafeHttpClient.builder(url);
                  b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
                  HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
                  if (resp.statusCode() == 200) {
                     JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                     logResp("KG playlist web-album", root);
                     PlaylistContents pt = parseListAllFile(root);
                     if (pt != null && !pt.tracks().isEmpty()) {
                        return decoratePlaylist(pt);
                     }
                  }
                  TraceLog.warn(vendor.label + " playlist web-album 解析为空, id=" + musiclibId);
               } catch (Exception ignored) {
               }
            }
            // 5) 收藏歌单兜底：v4 无数据（收藏他人歌单酷狗不开放 listid 取歌）+ 有创建者 gid + 歌单名
            //    → 搜索匹配创建者 gid → specialid → 网页版 special/song 取歌（HTTP）
            if (!createGid.isBlank() && !pName.isBlank()) {
               try {
                  String specialid = searchSpecialByGid(pName, createGid);
                  if (specialid != null) {
                     PlaylistContents pt = webSpecialSongsHttp(specialid);
                     if (pt != null && !pt.tracks().isEmpty()) {
                        TraceLog.info(vendor.label + " 收藏歌单兜底成功 specialid=" + specialid
                              + " 歌曲数=" + pt.tracks().size());
                        return decoratePlaylist(pt);
                     }
                  }
                  TraceLog.warn(vendor.label + " 收藏歌单兜底无结果, gid=" + createGid + " name=" + pName);
               } catch (Exception e) {
                  TraceLog.warn(vendor.label + " 收藏歌单兜底异常", e);
               }
            }
            // 6) 回退：pubsongs（global_collection_id，默认收藏歌单）
            List<SongEntry> tracks = new ArrayList<>();
            String name = "";
            long total = 0;
            long begin = 0;
            int pagesize = 100;
            while (true) {
               Map<String, String> extra = new LinkedHashMap<>();
               extra.put("area_code", "1");
               extra.put("begin_idx", String.valueOf(begin));
               extra.put("plat", "1");
               extra.put("type", "1");
               extra.put("mode", "1");
               extra.put("personal_switch", "1");
               extra.put("extend_fields", "abtags,hot_cmt,popularization");
               extra.put("pagesize", String.valueOf(pagesize));
               // 收藏歌单取歌必须用"创建者 gid"（list_create_gid），收藏视角 gid 返回 count=0
               String pubGid = !createGid.isBlank() ? createGid : (!gid.isBlank() ? gid : listid);
               extra.put("global_collection_id", pubGid);
               JsonObject root = gatewayGet("/pubsongs/v2/get_other_list_file_nofilt", extra, null);
               JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
                     ? root.getAsJsonObject("data") : null;
               if (dataObj == null) {
                  break;
               }
               if (dataObj.has("count") && dataObj.get("count").isJsonPrimitive()) {
                  total = dataObj.get("count").getAsLong();
               }
               if (dataObj.has("list_info") && dataObj.get("list_info").isJsonObject()) {
                  JsonObject li = dataObj.getAsJsonObject("list_info");
                  if (li.has("name") && li.get("name").isJsonPrimitive()
                        && !li.get("name").getAsString().isBlank()) {
                     name = li.get("name").getAsString();
                  }
               }
               JsonArray songs = dataObj.has("songs") && dataObj.get("songs").isJsonArray()
                     ? dataObj.getAsJsonArray("songs") : null;
               if (songs == null || songs.size() == 0) {
                  break;
               }
               for (JsonElement e : songs) {
                  if (!e.isJsonObject()) {
                     continue;
                  }
                  JsonObject s = e.getAsJsonObject();
                  String hash = str(s, "hash");
                  if (hash.isBlank()) {
                     hash = str(s, "file_hash");
                  }
                  if (hash.isBlank()) {
                     continue;
                  }
                  // 歌手：拼接 singerinfo 全部歌手（用"、"分隔）
                  StringBuilder singers = new StringBuilder();
                  if (s.has("singerinfo") && s.get("singerinfo").isJsonArray()) {
                     JsonArray siArr = s.getAsJsonArray("singerinfo");
                     for (JsonElement si : siArr) {
                        if (si.isJsonObject()) {
                           String n = str(si.getAsJsonObject(), "name");
                           if (!n.isBlank()) {
                              if (singers.length() > 0) {
                                 singers.append("、");
                              }
                              singers.append(n);
                           }
                        }
                     }
                  }
                  // 歌名：酷狗 pubsongs 的 name 形如 "歌手 - 歌名"；先取 singerinfo，再按 " - " 拆分
                  String title = str(s, "name");
                  if (title.contains(" - ")) {
                     int idx = title.indexOf(" - ");
                     if (singers.length() == 0) {
                        singers.append(title.substring(0, idx).trim());
                     }
                     title = title.substring(idx + 3).trim();
                  }
                  String artist = singers.toString();
                  long dur = durationOf(s);
                  tracks.add(new SongEntry(vendor, hash, title, artist, dur, kugouCover(s), addTimeOf(s)));
               }
               if (tracks.size() >= total || songs.size() < pagesize) {
                  break;
               }
               begin += pagesize;
               if (begin > 20000) {
                  break;
               }
            }
            return decoratePlaylist(new PlaylistContents(name, tracks));
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_TRACKS_FAILED, e, vendor.label);
         }
      });
   }

   /** 云歌单取歌（/v4/get_list_all_file(_v3)）翻页拉取：page 从 1 递增，直到累计达到服务端 count 或返回页不足一页。
    *  单页 pagesize=300（服务端上限），大歌单（500+ 首）必须翻页，否则只取到第一页。 */
   private PlaylistContents playlistTracksViaCloudlist(String path, boolean lite, String listid, boolean v4Style)
         throws Exception {
      List<SongEntry> all = new ArrayList<>();
      String name = "";
      int total = -1;
      int pagesize = 300;
      for (int page = 1; page <= 20; page++) {
         JsonObject dataMap = new JsonObject();
         dataMap.addProperty("listid", listid);
         dataMap.addProperty("userid", userid);
         dataMap.addProperty("type", 0);
         dataMap.addProperty("page", page);
         dataMap.addProperty("pagesize", pagesize);
         dataMap.addProperty("area_code", 1);
         dataMap.addProperty("allplatform", 1);
         dataMap.addProperty("show_cover", 1);
         if (v4Style) {
            dataMap.addProperty("show_relate_goods", 0);
         }
         dataMap.addProperty("token", token);
         Map<String, String> extra = new LinkedHashMap<>();
         extra.put("plat", "1");
         extra.put("userid", userid);
         extra.put("token", token);
         JsonObject root = androidPostVariant(path, dataMap, extra, "cloudlist.service.kugou.com", lite);
         logResp("KG playlist " + path + (lite ? " LITE" : " STD"), root);
         JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
               ? root.getAsJsonObject("data") : null;
         if (total < 0 && dataObj != null && dataObj.has("count") && dataObj.get("count").isJsonPrimitive()) {
            try {
               total = dataObj.get("count").getAsInt();
            } catch (Exception ignored) {
            }
         }
         PlaylistContents pt = parseListAllFile(root);
         if (pt == null || pt.tracks().isEmpty()) {
            break;
         }
         if (page == 1) {
            name = pt.name();
         }
         all.addAll(pt.tracks());
         // 累计达到总数（count 精确）或本页不足一页（最后一页）时停止
         if (all.size() >= total || pt.tracks().size() < pagesize) {
            break;
         }
      }
      // 歌单内歌曲按添加时间从新到旧排序（酷狗 App 惯例：最新添加在最前）。
      // 有 addtime 字段按字段降序；无字段时服务端原序=添加时间正序（旧→新），直接反转成新→旧
      boolean hasTime = false;
      for (SongEntry t : all) {
         if (t.addedAtMs() > 0L) {
            hasTime = true;
            break;
         }
      }
      if (hasTime) {
         all.sort((a, b) -> Long.compare(b.addedAtMs(), a.addedAtMs()));
      } else {
         java.util.Collections.reverse(all);
      }
      TraceLog.info(vendor.label + " playlist sort: hasAddTime=" + hasTime
            + " total=" + all.size()
            + (all.isEmpty() ? "" : " first=" + all.get(0).name() + " addTime=" + all.get(0).addedAtMs()));
      return all.isEmpty() ? null : new PlaylistContents(name, backfillCovers(all));
   }

   /** 解析 /v4/get_list_all_file(_v3) 响应：兼容 data.info.list / data.songs / data.list 结构。 */
   private PlaylistContents parseListAllFile(JsonObject root) {
      List<SongEntry> tracks = new ArrayList<>();
      String name = "";
      JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
            ? root.getAsJsonObject("data") : null;
      if (dataObj == null) {
         return null;
      }
      if (dataObj.has("info") && dataObj.get("info").isJsonObject()) {
         JsonObject info = dataObj.getAsJsonObject("info");
         if (info.has("listname") && info.get("listname").isJsonPrimitive()
               && !info.get("listname").getAsString().isBlank()) {
            name = info.get("listname").getAsString();
         }
      }
      JsonArray songs = null;
      if (dataObj.has("info") && dataObj.get("info").isJsonArray()) {
         // /v4/get_list_all_file(_v3) 响应结构：data.info 为歌曲数组
         songs = dataObj.getAsJsonArray("info");
      } else if (dataObj.has("songs") && dataObj.get("songs").isJsonArray()) {
         songs = dataObj.getAsJsonArray("songs");
      } else if (dataObj.has("list") && dataObj.get("list").isJsonArray()) {
         songs = dataObj.getAsJsonArray("list");
      } else if (dataObj.has("info") && dataObj.get("info").isJsonObject()) {
         JsonObject info = dataObj.getAsJsonObject("info");
         if (info.has("list") && info.get("list").isJsonArray()) {
            songs = info.getAsJsonArray("list");
         }
      }
      if (songs == null) {
         return null;
      }
      for (JsonElement e : songs) {
         if (!e.isJsonObject()) {
            continue;
         }
         JsonObject s = e.getAsJsonObject();
         String hash = str(s, "hash");
         if (hash.isBlank()) {
            hash = str(s, "file_hash");
         }
         if (hash.isBlank()) {
            continue;
         }
         StringBuilder singers = new StringBuilder();
         if (s.has("singerinfo") && s.get("singerinfo").isJsonArray()) {
            JsonArray siArr = s.getAsJsonArray("singerinfo");
            for (JsonElement si : siArr) {
               if (si.isJsonObject()) {
                  String n = str(si.getAsJsonObject(), "name");
                  if (!n.isBlank()) {
                     if (singers.length() > 0) {
                        singers.append("、");
                     }
                     singers.append(n);
                  }
               }
            }
         }
         String title = str(s, "name");
         if (title.isBlank()) {
            title = str(s, "filename");
         }
         if (title.isBlank()) {
            title = str(s, "songname");
         }
         // 酷狗 name 形如 "歌手 - 歌名.mp3"（含扩展名）；去掉扩展名后按 " - " 拆分
         int dot = title.lastIndexOf('.');
         if (dot > 0) {
            title = title.substring(0, dot);
         }
         int sep = title.indexOf(" - ");
         if (sep > 0) {
            if (singers.length() == 0) {
               singers.append(title.substring(0, sep).trim());
            }
            title = title.substring(sep + 3).trim();
         }
         long dur = durationOf(s);
         tracks.add(new SongEntry(vendor, hash, title, singers.toString(), dur, kugouCover(s), addTimeOf(s)));
      }
      return tracks.isEmpty() ? null : new PlaylistContents(name, tracks);
   }

   /** 收藏歌单兜底①：搜索歌单名（/v1/search/special），按创建者 list_create_gid 精确匹配，返回 specialid。
    *   complexsearch 偶发无响应，重试 3 次。 */
   private String searchSpecialByGid(String keyword, String targetGid) throws Exception {
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("albumhide", "0");
      extra.put("iscorrection", "1");
      extra.put("keyword", keyword);
      extra.put("nocollect", "0");
      extra.put("page", "1");
      extra.put("pagesize", "30");
      extra.put("platform", "AndroidFilter");
      Map<String, String> hdrs = new LinkedHashMap<>();
      hdrs.put("x-router", "complexsearch.kugou.com");
      Exception last = null;
      for (int i = 0; i < 3; i++) {
         if (i > 0) {
            sleepQuiet(500L);
         }
         try {
            JsonObject root = gatewayGet("/v1/search/special", extra, hdrs);
            if (root.has("data") && root.get("data").isJsonObject()) {
               JsonObject data = root.getAsJsonObject("data");
               if (data.has("lists") && data.get("lists").isJsonArray()) {
                  JsonArray lists = data.getAsJsonArray("lists");
                  for (JsonElement e : lists) {
                     if (!e.isJsonObject()) {
                        continue;
                     }
                     JsonObject l = e.getAsJsonObject();
                     String gid = str(l, "gid");
                     if (!gid.isBlank() && gid.equals(targetGid)) {
                        String sid = str(l, "specialid");
                        if (!sid.isBlank() && !sid.equals("0")) {
                           return sid;
                        }
                     }
                  }
               }
            }
            return null; // 有响应但未匹配 → 不再重试
         } catch (Exception e) {
            last = e;
         }
      }
      if (last != null) {
         throw last;
      }
      return null;
   }

   /** 收藏歌单兜底②：网页版 special/song（HTTP，HTTPS 在本机网络不可达），分页拉取全部歌曲。
    *  响应 info[] 结构与 /v4 一致（hash/filename/duration），直接构造 SongEntry。 */
   private PlaylistContents webSpecialSongsHttp(String specialid) throws Exception {
      List<SongEntry> tracks = new ArrayList<>();
      String name = "";
      long total = 0;
      int page = 1;
      int pagesize = 30;
      while (page <= 200) {
         String url = "http://mobilecdn.kugou.com/api/v3/special/song?specialid=" + specialid
               + "&page=" + page + "&pagesize=" + pagesize;
         HttpRequest.Builder b = SafeHttpClient.builder(url);
         b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
         b.header("Referer", "https://www.kugou.com/");
         HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
         if (resp.statusCode() != 200) {
            break;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         if (!root.has("data") || !root.get("data").isJsonObject()) {
            break;
         }
         JsonObject data = root.getAsJsonObject("data");
         if (data.has("total") && data.get("total").isJsonPrimitive()) {
            total = data.get("total").getAsLong();
         }
         JsonArray info = data.has("info") && data.get("info").isJsonArray()
               ? data.getAsJsonArray("info") : null;
         if (info == null || info.size() == 0) {
            break;
         }
         for (JsonElement e : info) {
            if (!e.isJsonObject()) {
               continue;
            }
            JsonObject s = e.getAsJsonObject();
            String hash = str(s, "hash");
            if (hash.isBlank()) {
               continue;
            }
            String title = str(s, "filename");
            if (title.isBlank()) {
               title = str(s, "name");
            }
            StringBuilder singers = new StringBuilder();
            int dot = title.lastIndexOf('.');
            if (dot > 0) {
               title = title.substring(0, dot);
            }
            int sep = title.indexOf(" - ");
            if (sep > 0) {
               singers.append(title.substring(0, sep).trim());
               title = title.substring(sep + 3).trim();
            }
            long dur = durationOf(s);
            tracks.add(new SongEntry(vendor, hash, title, singers.toString(), dur, kugouCover(s), addTimeOf(s)));
         }
         if (info.size() < pagesize || (page * pagesize) >= total) {
            break;
         }
         page++;
         sleepQuiet(200L);
      }
      return tracks.isEmpty() ? null : new PlaylistContents(name, tracks);
   }

   /** 从播放 URL 响应提取可用地址：url 数组/字符串 -> play_url -> backupUrl/backup_url 数组。
    *  trackerlite.kugou.com 当前网络不可达（DNS 无法解析），优先跳过；全部命中时替换 host 为 trackercdn 兜底。 */
   private String extractPlayUrl(JsonObject data) {
      try {
         java.util.List<String> cands = new ArrayList<>();
         if (data.has("url") && data.get("url").isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray("url")) {
               if (e.isJsonPrimitive()) {
                  cands.add(e.getAsString());
               }
            }
         }
         String u = str(data, "url");
         if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
            cands.add(u);
         }
         u = str(data, "play_url");
         if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
            cands.add(u);
         }
         for (String k : new String[] { "backupUrl", "backup_url" }) {
            if (data.has(k) && data.get(k).isJsonArray()) {
               for (JsonElement e : data.getAsJsonArray(k)) {
                  if (e.isJsonPrimitive()) {
                     cands.add(e.getAsString());
                  }
               }
            }
         }
         String picked = null;
         for (String c : cands) {
            if (!c.toLowerCase().contains("trackerlite")) {
               picked = c;
               break;
            }
         }
         if (picked == null && !cands.isEmpty()) {
            // 全部命中 trackerlite：host 替换为 trackercdn（酷狗 CDN 共享签名，路径/参数不变）
            picked = cands.get(0).replace("trackerlite.kugou.com", "trackercdn.kugou.com")
                  .replace("trackerlite", "trackercdn");
         }
         return picked == null ? "" : picked;
      } catch (Exception ignored) {
      }
      return "";
   }

   /** Auth 播放链 0：GET trackercdngz/v1/user_verify——拿全局 auth。 */
   private String userVerify() {
      if (!authCache.isBlank()) {
         return authCache;
      }
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", appid);
         qp.put("clientver", clientver);
         qp.put("clienttime", String.valueOf(ctSec));
         if (!token.isBlank()) qp.put("token", token);
         if (!userid.isBlank()) qp.put("userid", userid);
         qp.put("module_id", "51");
         qp.put("signature", androidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder(TRACKERCDN + "/v1/user_verify?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         String respBody = resp.body();
         try {
         } catch (Exception ignoredDiag) {
         }
         if (resp.statusCode() != 200) {
            return "";
         }
         JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
         if (data != null) {
            String a = str(data, "auth");
            if (!a.isBlank()) {
               authCache = a;
               TraceLog.info("KG userVerify OK");
               return a;
            }
         }
         String safe = Redactor.message(respBody.length() > 160 ? respBody.substring(0, 160) : respBody);
         TraceLog.warn("KG userVerify empty {} -> {}", resp.statusCode(), safe);
      } catch (Exception e) {
         TraceLog.warn("KG userVerify fail", e);
      }
      return "";
   }

   /** Auth 播放链 1：GET trackercdngz/v1/authorization——返回 {auth, open_time}。 */
   private JsonObject songAuth(String hash) {
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", appid);
         qp.put("clientver", "11561");
         qp.put("clienttime", String.valueOf(ctSec));
         if (!token.isBlank()) qp.put("token", token);
         if (!userid.isBlank()) qp.put("userid", userid);
         qp.put("authorization", authCache);
         qp.put("module_id", "51");
         qp.put("album_audio_id", "0");
         qp.put("hash", hash.toLowerCase());
         qp.put("signature", androidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder(TRACKERCDN + "/v1/authorization?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         String respBody = resp.body();
         try {
         } catch (Exception ignoredDiag) {
         }
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
         if (data == null || str(data, "auth").isBlank()) {
            return null;
         }
         TraceLog.info("KG songAuth OK hash={}", hash.toLowerCase());
         return data;
      } catch (Exception e) {
         TraceLog.warn("KG songAuth fail", e);
      }
      return null;
   }

   /** Auth 播放链 2：GET gateway/tracker/v5/url（song_url_auth.js 全参，非会员完整 128）。 */
   private String songUrlAuth(String hash, JsonObject authData) {
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         String openTime = authData.has("open_time") && authData.get("open_time").isJsonPrimitive()
               ? authData.get("open_time").getAsString() : "0";
         String auth = str(authData, "auth");
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", appid);
         qp.put("clientver", "11561");
         qp.put("clienttime", String.valueOf(ctSec));
         if (!token.isBlank()) qp.put("token", token);
         if (!userid.isBlank()) qp.put("userid", userid);
         qp.put("album_id", "0");
         qp.put("area_code", "1");
         qp.put("module", "");
         qp.put("hash", hash.toLowerCase());
         qp.put("need_m", "0");
         qp.put("ssa_flag", "is_fromtrack");
         qp.put("version", "11430");
         qp.put("open_time", openTime);
         qp.put("ptype", "0");
         qp.put("need_ogg", "1");
         qp.put("page_id", "151369488");
         qp.put("auth", auth);
         qp.put("mtype", "0");
         qp.put("quality", "128");
         qp.put("album_audio_id", "0");
         qp.put("behavior", "play");
         qp.put("pid", "2");
         qp.put("module_id", "51");
         qp.put("cmd", "26");
         qp.put("ppage_id", "463467626,350369493,788954147");
         qp.put("pidversion", "3001");
         qp.put("cdnBackup", "1");
         qp.put("key", SecretCipher.md5Hex(hash.toLowerCase() + "57ae12eb6890223e355ccfcb74edf70d" + appid + mid
               + (userid.isBlank() ? "0" : userid)));
         qp.put("signature", androidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + "/tracker/v5/url?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         String respBody = resp.body();
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
         String u = extractPlayUrl(data);
         if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
            TraceLog.info("KG playurl auth OK hash={}", hash.toLowerCase());
            return u;
         }
         String safe = Redactor.message(respBody.length() > 160 ? respBody.substring(0, 160) : respBody);
         TraceLog.warn("KG playurl auth empty {} -> {}", resp.statusCode(), safe);
      } catch (Exception e) {
         TraceLog.warn("KG playurl auth fail", e);
      }
      return null;
   }

   /** 概念版播放兜底（lite 分支）——概念版每日领取 VIP 权益可放 VIP 歌。 */
   private String songUrlV5Lite(String hash) {
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", LITE_APPID);
         qp.put("clientver", "11430");
         qp.put("clienttime", String.valueOf(ctSec));
         String tk = activeToken();
         String auid = activeUserid();
         if (!tk.isBlank()) qp.put("token", tk);
         if (!auid.isBlank()) qp.put("userid", auid);
         qp.put("album_id", "0");
         qp.put("area_code", "1");
         qp.put("hash", hash.toLowerCase());
         qp.put("ssa_flag", "is_fromtrack");
         qp.put("version", "11430");
         qp.put("page_id", "967177915");
         qp.put("quality", "128");
         qp.put("album_audio_id", "0");
         qp.put("behavior", "play");
         qp.put("pid", "411");
         qp.put("cmd", "26");
         qp.put("pidversion", "3001");
         qp.put("IsFreePart", "0");
         qp.put("ppage_id", "356753938,823673182,967485191");
         qp.put("cdnBackup", "1");
         qp.put("module", "");
         qp.put("key", SecretCipher.md5Hex(hash.toLowerCase() + "185672dd44712f60bb1736df5a377e82" + LITE_APPID + mid
               + (auid.isBlank() ? "0" : auid)));
         qp.put("signature", liteAndroidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + "/v5/url?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         b.header("x-router", "trackercdn.kugou.com");
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         String respBody = resp.body();
         try {
         } catch (Exception ignored) {
         }
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
         String u = extractPlayUrl(data);
         if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
            TraceLog.info("KG playurl lite OK hash={}", hash.toLowerCase());
            return u;
         }
         String safe = Redactor.message(respBody.length() > 160 ? respBody.substring(0, 160) : respBody);
         TraceLog.warn("KG playurl lite empty {} -> {}", resp.statusCode(), safe);
      } catch (Exception e) {
         TraceLog.warn("KG playurl lite fail", e);
      }
      return null;
   }

   /** 主路径：GET gateway/v5/url + x-router trackercdn（标准版 signKey）。 */
   private String songUrlV5(String hash) {
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", appid);
         qp.put("clientver", "11430");
         qp.put("clienttime", String.valueOf(ctSec));
         String tk = activeToken();
         String auid = activeUserid();
         if (!tk.isBlank()) qp.put("token", tk);
         if (!auid.isBlank()) qp.put("userid", auid);
         qp.put("album_id", "0");
         qp.put("area_code", "1");
         qp.put("hash", hash.toLowerCase());
         qp.put("ssa_flag", "is_fromtrack");
         qp.put("version", "11430");
         qp.put("page_id", "151369488");
         qp.put("quality", "128");
         qp.put("album_audio_id", "0");
         qp.put("behavior", "play");
         qp.put("pid", "2");
         qp.put("cmd", "26");
         qp.put("pidversion", "3001");
         qp.put("IsFreePart", "0");
         qp.put("ppage_id", "463467626,350369493,788954147");
         qp.put("cdnBackup", "1");
         qp.put("module", "");
         qp.put("key", SecretCipher.md5Hex(hash.toLowerCase() + "57ae12eb6890223e355ccfcb74edf70d" + appid + mid
               + (auid.isBlank() ? "0" : auid)));
         qp.put("signature", androidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + "/v5/url?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         b.header("x-router", "trackercdn.kugou.com");
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         String respBody = resp.body();
         try {
         } catch (Exception ignored) {
         }
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
         String u = extractPlayUrl(data);
         if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
            TraceLog.info("KG playurl v5 OK hash={}", hash.toLowerCase());
            return u;
         }
         String safe = Redactor.message(respBody.length() > 160 ? respBody.substring(0, 160) : respBody);
         TraceLog.warn("KG playurl v5 empty {} -> {}", resp.statusCode(), safe);
      } catch (Exception e) {
         TraceLog.warn("KG playurl v5 fail", e);
      }
      return null;
   }

   @Override
   public String resolveStreamUrl(String trackId, String fallback) {
      if (trackId == null || trackId.isBlank()) {
         return fallback;
      }
      // 免费模式（游客）下地址时效短且不携带登录态，不读不写缓存。
      if (!freeOnly()) {
         CachedStream cached = streamCache.get(trackId);
         if (cached != null) {
            if (System.currentTimeMillis() - cached.resolvedAtMillis() < STREAM_CACHE_MILLIS) {
               return cached.url();
            }
            streamCache.remove(trackId);
         }
      }
      String resolved = resolveStreamUrlUncached(trackId, fallback);
      if (!freeOnly() && resolved != null && !resolved.isBlank() && !resolved.equals(fallback)) {
         streamCache.put(trackId, new CachedStream(resolved, System.currentTimeMillis()));
      }
      return resolved;
   }

   private String resolveStreamUrlUncached(String trackId, String fallback) {
      if (trackId == null || trackId.isBlank()) {
         return fallback;
      }
      // 6 套组合兜底：任一方案成功即返回该播放地址
      // 主路径：/v5/url（gateway.kugou.com 可达，tracker/trackerlite 域名当前网络不可达）
      String u = songUrlV5(trackId);
      if (u != null) {
         return u;
      }
      // 概念版兜底：概念版每日领取 VIP 权益，VIP 歌可放（lite 参数 + LITE 盐签名）
      u = songUrlV5Lite(trackId);
      if (u != null) {
         return u;
      }
      // Auth 播放链（userVerify -> /v1/authorization -> /tracker/v5/url -> priv_url）携带 token，
      // 游客模式（freeOnly）跳过，仅走公开签名与公开 web 音源。
      if (!freeOnly()) {
         userVerify();
         JsonObject authData = songAuth(trackId);
         if (authData != null) {
            u = songUrlAuth(trackId, authData);
            if (u != null) {
               return u;
            }
         }
         u = privUrl(trackId, false);
         if (u != null) {
            return u;
         }
         u = privUrl(trackId, true);
         if (u != null) {
            return u;
         }
      }
      u = mobileThenWeb(trackId);
      if (u != null) {
         return u;
      }
      u = webPlay(trackId, 0);
      if (u != null) {
         return u;
      }
      u = ipRedirect(trackId);
      if (u != null) {
         return u;
      }
      return fallback;
   }

   /** 兜底 1/2：酷狗官方 tracker priv_url（android 签名）。variant=true 时用小写 hash、扩展字段、随机 dfid。 */
   private String privUrl(String hash, boolean variant) {
      try {
         long clienttimeMs = System.currentTimeMillis();
         String h = variant ? hash.toLowerCase() : hash;
         String useDfid = variant ? randomString(24) : dfid;
         JsonObject resource = new JsonObject();
         resource.addProperty("album_audio_id", 0);
         resource.addProperty("collect_list_id", "3");
         resource.addProperty("collect_time", clienttimeMs);
         resource.addProperty("hash", h);
         resource.addProperty("id", 0);
         resource.addProperty("page_id", 1);
         resource.addProperty("type", "audio");
         String trackerKey = SecretCipher.md5Hex(h + "185672dd44712f60bb1736df5a377e82" + appid + mid
               + (userid.isBlank() ? "0" : userid));
         JsonObject tracker = new JsonObject();
         tracker.addProperty("all_m", 1);
         tracker.addProperty("auth", "");
         tracker.addProperty("is_free_part", 0);
         tracker.addProperty("key", trackerKey);
         tracker.addProperty("module_id", 0);
         tracker.addProperty("need_climax", 1);
         tracker.addProperty("need_xcdn", 1);
         tracker.addProperty("open_time", "");
         tracker.addProperty("pid", "411");
         tracker.addProperty("pidversion", "3001");
         tracker.addProperty("priv_vip_type", "6");
         tracker.addProperty("viptoken", "");
         JsonObject data = new JsonObject();
         data.addProperty("area_code", "1");
         data.addProperty("behavior", "play");
         JsonArray quals = new JsonArray();
         for (String q : new String[]{"128","320","flac","high","multitrack","viper_atmos","viper_tape","viper_clear","super"}) {
            quals.add(q);
         }
         data.add("qualities", quals);
         data.add("resource", resource);
         data.addProperty("token", token);
         data.add("tracker_param", tracker);
         data.addProperty("userid", userid.isBlank() ? "0" : userid);
         data.addProperty("vip", 0);
         if (variant) {
            data.addProperty("plat", 1);
            data.addProperty("source", 1);
            data.addProperty("cid", 0);
         }
         long clienttime = clienttimeMs / 1000;
         Map<String, String> params = new LinkedHashMap<>();
         params.put("dfid", useDfid);
         params.put("mid", mid);
         params.put("uuid", "-");
         params.put("appid", appid);
         params.put("clientver", clientver);
         params.put("clienttime", String.valueOf(clienttime));
         if (!token.isBlank()) {
            params.put("token", token);
         }
         if (!userid.isBlank() && !userid.equals("0")) {
            params.put("userid", userid);
         }
         params.put("signature", androidSignature(params, data.toString()));
         HttpRequest.Builder b = SafeHttpClient.builder(TRACKER + "/v6/priv_url?" + SecretCipher.encodedQuery(params));
         b.header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
         b.header("dfid", useDfid);
         b.header("clienttime", String.valueOf(clienttime));
         b.header("mid", mid);
         b.header("kg-rc", "1");
         b.header("kg-thash", "5d816a0");
         b.header("kg-rec", "1");
         b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
         String cookie = "kg_mid=" + mid + "; dfid=" + dfid + "; KUGOU_API_MID=" + mid
               + "; KUGOU_API_GUID=" + devGuid + "; KUGOU_API_MAC=" + devMac + "; KUGOU_API_DEV=" + devName;
         String wk = activeToken();
         String wuid = activeUserid();
         if (!wk.isBlank()) {
            cookie += "; token=" + wk;
         }
         if (!wuid.isBlank() && !wuid.equals("0")) {
            cookie += "; userid=" + wuid;
         }
         b.header("Cookie", cookie);
         b.header("Content-Type", "application/json");
         b.POST(HttpRequest.BodyPublishers.ofString(data.toString()));
         HttpResponse<String> resp = SafeHttpClient.send(b.build());
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         if (root.has("error_code") && root.get("error_code").isJsonPrimitive()
               && root.get("error_code").getAsInt() != 0) {
            return null;
         }
         JsonArray dataArr = root.has("data") && root.get("data").isJsonArray()
               ? root.getAsJsonArray("data") : null;
         if (dataArr != null && dataArr.size() > 0 && dataArr.get(0).isJsonObject()) {
            JsonObject urlData = dataArr.get(0).getAsJsonObject();
            String playUrl = str(urlData, "play_url");
            if (!playUrl.isBlank() && !"null".equalsIgnoreCase(playUrl)) {
               return playUrl;
            }
            JsonArray trackerUrls = urlData.has("en_tracker_url") && urlData.get("en_tracker_url").isJsonArray()
                  ? urlData.getAsJsonArray("en_tracker_url") : null;
            if (trackerUrls == null || trackerUrls.size() == 0) {
               trackerUrls = urlData.has("tracker_url") && urlData.get("tracker_url").isJsonArray()
                     ? urlData.getAsJsonArray("tracker_url") : null;
            }
            if (trackerUrls != null && trackerUrls.size() > 0 && trackerUrls.get(0).isJsonPrimitive()) {
               String u = trackerUrls.get(0).getAsString();
               if (!u.isBlank() && !"null".equalsIgnoreCase(u)) {
                  return u;
               }
            }
         }
         return null;
      } catch (Exception e) {
         return null;
      }
   }

   /** 兜底 3：mobilecdn 歌曲信息拿 album_audio_id → 网页 getdata。 */
   private String mobileThenWeb(String hash) {
      try {
         long albumId = 0L;
         String infoUrl = "http://mobilecdn.kugou.com/api/v3/song/info?hash=" + hash + "&format=json";
         HttpRequest.Builder bi = SafeHttpClient.builder(infoUrl);
         bi.header("User-Agent", "Mozilla/5.0 Chrome/122");
         bi.header("Cookie", "kg_mid=" + mid);
         HttpResponse<String> infoResp = SafeHttpClient.send(bi.GET().build());
         if (infoResp.statusCode() == 200) {
            JsonObject root = JsonParser.parseString(infoResp.body()).getAsJsonObject();
            if (root.has("data") && root.get("data").isJsonObject()) {
               JsonObject d = root.getAsJsonObject("data");
               if (d.has("album") && d.get("album").isJsonArray()
                     && d.getAsJsonArray("album").size() > 0
                     && d.getAsJsonArray("album").get(0).isJsonObject()) {
                  JsonObject a0 = d.getAsJsonArray("album").get(0).getAsJsonObject();
                  albumId = a0.has("album_audio_id") ? a0.get("album_audio_id").getAsLong() : 0L;
               }
            }
         }
         return webPlay(hash, albumId);
      } catch (Exception e) {
         return null;
      }
   }

   /** 兜底 4：酷狗网页播放接口 r=play/getdata（需 kg_mid/dfid/mid）。 */
   private String webPlay(String hash, long albumId) {
      try {
         String url = "https://www.kugou.com/yy/index.php?r=play/getdata&hash=" + hash
               + "&album_id=" + albumId + "&dfid=" + dfid + "&mid=" + mid + "&plat=0";
         HttpRequest.Builder b = SafeHttpClient.builder(url);
         b.header("User-Agent", "Mozilla/5.0 Chrome/122");
         b.header("Referer", "https://www.kugou.com/");
         String cookie = "kg_mid=" + mid + "; dfid=" + dfid + "; KUGOU_API_MID=" + mid
               + "; KUGOU_API_GUID=" + devGuid + "; KUGOU_API_MAC=" + devMac + "; KUGOU_API_DEV=" + devName;
         String wk = activeToken();
         String wuid = activeUserid();
         if (!wk.isBlank()) {
            cookie += "; token=" + wk;
         }
         if (!wuid.isBlank() && !wuid.equals("0")) {
            cookie += "; userid=" + wuid;
         }
         b.header("Cookie", cookie);
         HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         if (root.has("data") && root.get("data").isJsonObject()) {
            String playUrl = str(root.getAsJsonObject("data"), "play_url");
            if (!playUrl.isBlank() && !"null".equalsIgnoreCase(playUrl)) {
               return playUrl;
            }
         }
         return null;
      } catch (Exception e) {
         return null;
      }
   }

   /** 兜底 5：tracker ipredirect 直链。 */
   private String ipRedirect(String hash) {
      try {
         String url = TRACKER + "/i/ipredirect?hash=" + hash + "&plat=0&version=8990&album_audio_id=0&mid=" + mid + "&dfid=" + dfid;
         HttpRequest.Builder b = SafeHttpClient.builder(url);
         b.header("User-Agent", "Mozilla/5.0 Chrome/122");
         String cookie = "kg_mid=" + mid + "; dfid=" + dfid + "; KUGOU_API_MID=" + mid
               + "; KUGOU_API_GUID=" + devGuid + "; KUGOU_API_MAC=" + devMac + "; KUGOU_API_DEV=" + devName;
         String wk = activeToken();
         String wuid = activeUserid();
         if (!wk.isBlank()) {
            cookie += "; token=" + wk;
         }
         if (!wuid.isBlank() && !wuid.equals("0")) {
            cookie += "; userid=" + wuid;
         }
         b.header("Cookie", cookie);
         HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
         if (resp.statusCode() != 200) {
            return null;
         }
         String body = resp.body();
         if (body == null || body.isBlank() || body.contains("\"error\"") || body.contains("\"Error\"")) {
            return null;
         }
         if (body.trim().startsWith("http")) {
            return body.trim();
         }
         return null;
      } catch (Exception e) {
         return null;
      }
   }

   @Override
   public CompletableFuture<String> fetchLyrics(String trackId) {
      return CompletableFuture.supplyAsync(() -> {
         if (trackId == null || trackId.isBlank()) {
            return "";
         }
         try {
            // 诊断：lyrics 入口
            try {
            } catch (Exception ignoredDiag) {
            }
            // KRC 逐字歌词优先（lyrics.kugou.com /v1/search + /download fmt=krc，含音译/翻译）
            if (FeatureSwitch.lyricTranslation || FeatureSwitch.lyricRomaji) {
               JsonObject sl = searchLyric(trackId);
               if (sl != null) {
                  String lid = str(sl, "id");
                  String ak = str(sl, "accesskey");
                  if (!lid.isBlank() && !ak.isBlank()) {
                     String krcText = fetchKrcLyric(lid, ak);
                     if (!krcText.isBlank()) {
                        try {
                           String safeKrc = krcText.length() > 900 ? krcText.substring(0, 900) : krcText;
                        } catch (Exception ignoredDiag) {
                        }
                        return mergeKrc(krcText);
                     }
                  }
               }
            }
            String lrc = fetchKrc(trackId, 100);
            if (lrc == null || lrc.isBlank()) {
               return "";
            }
            // 翻译歌词：酷狗 cmd=200 已废弃（Bad cmd），改用网易云 tlyric 兜底，按时间行对齐追加
            if (FeatureSwitch.lyricTranslation) {
               String trc = "";
               String[] info = RuntimeRegistry.currentSongInfo();
               if (info != null && !info[0].isBlank()) {
                  trc = neteaseTranslation(info[0], info[1]);
               }
               if (trc != null && !trc.isBlank()) {
               // 网易云 tlyric 时间戳与酷狗 LRC 常存在几十毫秒偏差：转毫秒做 ±150ms 容差匹配；
               // 同时跳过"翻译行自身"，避免同一时间戳的翻译行被当作独立歌词行输出。
               java.util.Map<Long, String> trans = new java.util.TreeMap<>();
               for (String line : trc.split("\\R")) {
                  String t = line.trim();
                  java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\[(\\d{1,3}:\\d{1,2}(?:\\.\\d+)?)](.*)$").matcher(t);
                  if (m.matches()) {
                     long ms = lrcMs(m.group(1));
                     if (ms >= 0) {
                        trans.put(ms, m.group(2).trim());
                     }
                  }
               }
               StringBuilder sb = new StringBuilder();
               for (String line : lrc.split("\\R")) {
                  String t = line.trim();
                  java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\[(\\d{1,3}:\\d{1,2}(?:\\.\\d+)?)](.*)$").matcher(t);
                  if (m.matches()) {
                     long ms = lrcMs(m.group(1));
                     String transText = ms >= 0 ? nearestTranslation(trans, ms) : null;
                     String origin = m.group(2).trim();
                     if (transText != null && !transText.isBlank()) {
                        // 跳过翻译行自身：行文本与翻译文本相同（该行就是翻译行，原文行已输出）
                        if (origin.equals(transText)) {
                           continue;
                        }
                        // 三段式：空音译段\u0001原文\u0001翻译（HUD 分行渲染）
                        sb.append('[').append(m.group(1)).append("]\u0001").append(origin)
                              .append('\u0001').append(transText).append('\n');
                        continue;
                     }
                  }
                  sb.append(line).append('\n');
               }
               return sb.toString();
            }
            }
            return lrc;
         } catch (Exception e) {
            return "";
         }
      });
   }

   /** 网易云 tlyric 时间行转毫秒（[mm:ss(.xx)]），非法返回 -1。 */
   private static long lrcMs(String tag) {
      try {
         int c = tag.indexOf(':');
         if (c < 0) {
            return -1L;
         }
         long min = Long.parseLong(tag.substring(0, c).trim());
         double sec = Double.parseDouble(tag.substring(c + 1).trim());
         return min * 60000L + Math.round(sec * 1000.0);
      } catch (Exception e) {
         return -1L;
      }
   }

   /** 在翻译时间戳映射中找 ±150ms 内最近的一条。 */
   private static String nearestTranslation(java.util.Map<Long, String> trans, long ms) {
      String best = null;
      long bestDiff = 150L;
      for (java.util.Map.Entry<Long, String> e : trans.entrySet()) {
         long d = Math.abs(e.getKey() - ms);
         if (d <= bestDiff) {
            bestDiff = d;
            best = e.getValue();
         }
      }
      return best;
   }

   /** KRC 歌词链 1：GET lyrics.kugou.com/v1/search（notSign 无签名）——拿 id/accesskey。 */
   private JsonObject searchLyric(String hash) {
      try {
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("album_audio_id", "0");
         qp.put("appid", STD_APPID);
         qp.put("clientver", STD_CLIENTVER);
         qp.put("duration", "0");
         qp.put("hash", hash);
         qp.put("keyword", "");
         qp.put("lrctxt", "1");
         qp.put("man", "no");
         qp.put("signature", stdAndroidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder("https://lyrics.kugou.com/v1/search?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("Accept", "application/json, text/plain, */*");
         HttpResponse<byte[]> raw = SafeHttpClient.downloadBytes(b.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         HttpResponse<String> resp = SafeHttpClient.wrapString(raw, SafeHttpClient.decodeText(raw, raw.body()));
         // 诊断：searchLyric 响应
         try {
         } catch (Exception ignoredDiag) {
         }
         if (resp.statusCode() != 200) {
            return null;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         // 响应结构：candidates 在根级（{"status":200,...,"candidates":[{"id":...,"accesskey":...}]}）
         JsonArray cands = root.has("candidates") && root.get("candidates").isJsonArray()
               ? root.getAsJsonArray("candidates") : null;
         if (cands != null && cands.size() > 0 && cands.get(0).isJsonObject()) {
            return cands.get(0).getAsJsonObject();
         }
         // 兼容：个别场景包装在 data.lists
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
         if (data != null && data.has("lists") && data.get("lists").isJsonArray()
               && data.getAsJsonArray("lists").size() > 0
               && data.getAsJsonArray("lists").get(0).isJsonObject()) {
            return data.getAsJsonArray("lists").get(0).getAsJsonObject();
         }
      } catch (Exception e) {
         TraceLog.warn("KG searchLyric fail", e);
      }
      return null;
   }

   /** KRC 歌词链 2：GET lyrics.kugou.com/download fmt=krc（android 签名）——解码后文本。 */
   private String fetchKrcLyric(String id, String accesskey) {
      try {
         long ctSec = System.currentTimeMillis() / 1000;
         Map<String, String> qp = new LinkedHashMap<>();
         qp.put("dfid", dfid);
         qp.put("mid", mid);
         qp.put("uuid", "-");
         qp.put("appid", STD_APPID);
         qp.put("clientver", STD_CLIENTVER);
         qp.put("clienttime", String.valueOf(ctSec));
         qp.put("ver", "1");
         qp.put("client", "android");
         qp.put("id", id);
         qp.put("accesskey", accesskey);
         qp.put("fmt", "krc");
         qp.put("charset", "utf8");
         qp.put("signature", stdAndroidSignature(qp, ""));
         HttpRequest.Builder b = SafeHttpClient.builder("https://lyrics.kugou.com/download?" + SecretCipher.encodedQuery(qp));
         b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
         b.header("Accept", "application/json, text/plain, */*");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ctSec));
         b.header("mid", mid);
         b.header("kg-rc", "1");
         b.header("kg-thash", "5d816a0");
         b.header("kg-rec", "1");
         b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
         HttpResponse<byte[]> rawResp = SafeHttpClient.downloadBytes(b.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         HttpResponse<String> resp = SafeHttpClient.wrapString(rawResp, SafeHttpClient.decodeText(rawResp, rawResp.body()));
         if (resp.statusCode() != 200) {
            try {
            } catch (Exception ignoredDiag) {
            }
            return "";
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
         String content = str(data, "content");
         if (content.isBlank()) {
            try {
            } catch (Exception ignoredDiag) {
            }
            return "";
         }
         long contentType = data.has("contenttype") && data.get("contenttype").isJsonPrimitive()
               ? data.get("contenttype").getAsLong() : 1L;
         byte[] raw = java.util.Base64.getDecoder().decode(content);
         if (contentType == 0L) {
            return decodeKrc(raw);
         }
         return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
      } catch (Exception e) {
         TraceLog.warn("KG fetchKrcLyric fail", e);
      }
      return "";
   }

   /** KRC 合并：解析 [language:] 段（base64 JSON，type0=音译 type1=翻译），按行并入原文（原文（音译） / 翻译）。 */
   private String mergeKrc(String krcText) {
      try {
         java.util.List<String> roma = new java.util.ArrayList<>();
         java.util.List<String> trans = new java.util.ArrayList<>();
         java.util.regex.Matcher lm = java.util.regex.Pattern.compile("\\[language:([^\\]]+)]").matcher(krcText);
         if (lm.find()) {
            String langJson = new String(java.util.Base64.getDecoder().decode(lm.group(1)), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject lang = JsonParser.parseString(langJson).getAsJsonObject();
            if (lang.has("content") && lang.get("content").isJsonArray()) {
               for (com.google.gson.JsonElement ce : lang.getAsJsonArray("content")) {
                  if (!ce.isJsonObject()) continue;
                  JsonObject c = ce.getAsJsonObject();
                  boolean isTrans = c.has("type") && c.get("type").isJsonPrimitive() && c.get("type").getAsInt() == 1;
                  java.util.List<String> rows = new java.util.ArrayList<>();
                  if (c.has("lyricContent") && c.get("lyricContent").isJsonArray()) {
                     for (com.google.gson.JsonElement re : c.getAsJsonArray("lyricContent")) {
                        StringBuilder row = new StringBuilder();
                        if (re.isJsonArray()) {
                           for (com.google.gson.JsonElement p : re.getAsJsonArray()) {
                              row.append(p.isJsonPrimitive() ? p.getAsString() : "");
                           }
                        } else if (re.isJsonPrimitive()) {
                           row.append(re.getAsString());
                        }
                        rows.add(row.toString().trim());
                     }
                  }
                  if (isTrans) trans = rows; else roma = rows;
               }
            }
         }
         // 时间行列表（KRC 格式：[起始毫秒,持续]<偏移,时长,类型>字符——转 LRC [mm:ss.xx]）
         java.util.List<String> timeTags = new java.util.ArrayList<>();
         java.util.List<String> timeTexts = new java.util.ArrayList<>();
         java.util.regex.Pattern timeLine = java.util.regex.Pattern.compile("^\\[(\\d{1,8}),(\\d{1,8})](.*)$");
         for (String line : krcText.split("\\R")) {
            java.util.regex.Matcher m = timeLine.matcher(line.trim());
            if (m.matches()) {
               long ms = Long.parseLong(m.group(1));
               String tag = String.format("%02d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10);
               String text = m.group(3).replaceAll("<[^>]*>", "").trim();
               timeTags.add(tag);
               timeTexts.add(text);
            }
         }
         boolean showTrans = FeatureSwitch.lyricTranslation;
         boolean showRoma = FeatureSwitch.lyricRomaji;
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < timeTexts.size(); i++) {
            String original = timeTexts.get(i);
            if (original.isBlank()) continue;
            String r = i < roma.size() ? roma.get(i) : "";
            String t = i < trans.size() ? trans.get(i) : "";
            // 三段式：音译\u0001原文\u0001翻译（空段保留，HUD 分行渲染）
            StringBuilder out = new StringBuilder();
            if (showRoma && !r.isBlank() && !r.equals(original)) out.append(r);
            out.append('\u0001').append(original);
            if (showTrans && !t.isBlank() && !t.equals(original)) out.append('\u0001').append(t);
            sb.append('[').append(timeTags.get(i)).append(']').append(out).append('\n');
         }
         String merged = sb.toString();
         if (merged.isBlank()) return krcText;
         return merged;
      } catch (Exception e) {
         TraceLog.warn("KG mergeKrc fail", e);
         return krcText;
      }
   }

   /** KRC 歌词解码：跳过 4 字节头 -> XOR(16字节密钥) -> zlib inflate。 */
   private String decodeKrc(byte[] raw) {      byte[] key = { 64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105 };
      for (boolean rawMode : new boolean[] { false, true }) {
         try {
            byte[] krc = java.util.Arrays.copyOfRange(raw, Math.min(4, raw.length), raw.length);
            for (int i = 0; i < krc.length; i++) {
               krc[i] = (byte) (krc[i] ^ key[i % key.length]);
            }
            java.util.zip.Inflater inf = new java.util.zip.Inflater(rawMode);
            inf.setInput(krc);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
               int n = inf.inflate(buf);
               if (n == 0) {
                  break;
               }
               out.write(buf, 0, n);
            }
            inf.end();
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }
      }
      TraceLog.warn("KG decodeKrc fail len=" + (raw == null ? 0 : raw.length));
      return "";
   }

   /** 网易云翻译兜底：按 标题+歌手 在网易云搜索匹配歌曲，取 tlyric 翻译文本。 */
   private String neteaseTranslation(String title, String artist) {
      try {
         String s = java.net.URLEncoder.encode(title + " " + artist, "UTF-8");
         HttpRequest.Builder b = SafeHttpClient.builder("https://music.163.com/api/search/get/web?s=" + s + "&type=1&limit=3");
         b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
         b.header("Referer", "https://music.163.com/");
         HttpResponse<byte[]> raw = SafeHttpClient.downloadBytes(b.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         HttpResponse<String> resp = SafeHttpClient.wrapString(raw, SafeHttpClient.decodeText(raw, raw.body()));
         if (resp.statusCode() != 200) {
            return "";
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         JsonObject result = root.has("result") && root.get("result").isJsonObject() ? root.getAsJsonObject("result") : null;
         if (result == null || !result.has("songs") || !result.get("songs").isJsonArray()
               || result.getAsJsonArray("songs").size() == 0) {
            return "";
         }
         JsonObject song = result.getAsJsonArray("songs").get(0).getAsJsonObject();
         String songId = song.get("id").getAsString();
         HttpRequest.Builder b2 = SafeHttpClient.builder("https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1");
         b2.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
         b2.header("Referer", "https://music.163.com/");
         HttpResponse<byte[]> raw2 = SafeHttpClient.downloadBytes(b2.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         HttpResponse<String> resp2 = SafeHttpClient.wrapString(raw2, SafeHttpClient.decodeText(raw2, raw2.body()));
         if (resp2.statusCode() != 200) {
            return "";
         }
         JsonObject root2 = JsonParser.parseString(resp2.body()).getAsJsonObject();
         if (root2.has("tlyric") && root2.get("tlyric").isJsonObject()
               && root2.getAsJsonObject("tlyric").has("lyric")
               && root2.getAsJsonObject("tlyric").get("lyric").isJsonPrimitive()) {
            return root2.getAsJsonObject("tlyric").get("lyric").getAsString();
         }
      } catch (Exception e) {
         TraceLog.warn("KG neteaseTranslation fail", e);
      }
      return "";
   }

   /** 拉取酷狗歌词原始文本：cmd=100 原文，cmd=200 翻译（编码自动检测 UTF-8/GBK）。 */
   private String fetchKrc(String trackId, int cmd) {      try {
         String url = "https://m.kugou.com/app/i/krc.php?cmd=" + cmd + "&hash=" + trackId + "&timelength=600000&d=" + Math.random();
         HttpRequest.Builder b = SafeHttpClient.builder(url);
         b.header("User-Agent", "Mozilla/5.0");
         b.header("Referer", "https://www.kugou.com/");
         HttpResponse<byte[]> resp = SafeHttpClient.downloadBytes(b.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
         if (resp.statusCode() != 200) {
            return "";
         }
         byte[] raw = resp.body();
         String u8 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
         String lrc = u8.contains("\uFFFD")
               ? new String(raw, java.nio.charset.Charset.forName("GBK"))
               : u8;
         if (lrc.startsWith("\uFEFF")) {
            lrc = lrc.substring(1);
         }
         // 去掉 [id:...]/[hash:...]/[total:...] 等元信息头，只保留时间行
         if (lrc != null && !lrc.isBlank()) {
            StringBuilder sb = new StringBuilder();
            for (String line : lrc.split("\\R")) {
               String t = line.trim();
               if (!t.isEmpty() && t.startsWith("[") && !t.matches("^\\[\\d{1,3}:\\d{1,2}.*")) {
                  continue; // 丢弃元信息行 [id:]/[ar:]/[ti:]/[hash:]/[total:] 等
               }
               sb.append(line).append('\n');
            }
            return sb.toString();
         }
         return "";
      } catch (Exception e) {
         return "";
      }
   }

   @Override
   public CompletableFuture<AccountProfile> verifyAccount() {
      return CompletableFuture.supplyAsync(() -> {
         // T-02：游客模式（freeOnly）不发起账号 / VIP 权益接口。
         requireNotFreeOnly();
         if (!authenticated()) {
            return AccountProfile.fail(VendorError.NOT_LOGGED_IN);
         }
         // 随机补齐仅保证签名参数完整，不代表指纹可信（T-01B）。
         try {
            ensureDeviceFingerprint();
         } catch (Exception ignoredFp) {
         }
         // token 为酷狗官方返回的有效凭证；用户信息接口可能因平台/设备维度失败，
         // 不因验证接口失败而误判"登录失效"；风控拦截码则直接上抛、不静默降级。
         try {
            JsonObject root = userInfo();
            JsonObject user = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
            String vip = user == null ? "" : vipDesc(user);
            // VIP 独立接口（kugouvip.kugou.com/v1/get_union_vip）：仅在开关打开且指纹可信时探测。
            // 指纹不可信（纯随机生成、未手动粘贴、平台未下发）时不携带这套伪造指纹探测，note 置未知。
            if (vipProbe && fingerprintTrusted) {
               try {
                  JsonObject v = vipInfo();
                  String v2 = vipDescV2(v);
                  if (!v2.isBlank()) {
                     vip = v2;
                  } else if (v.has("error_code") && v.get("error_code").isJsonPrimitive()
                        && v.get("error_code").getAsLong() != 0L) {
                     TraceLog.info("KG vip error_code={}（VIP 接口临时不可用，VIP 置未知）",
                           v.get("error_code").getAsLong());
                     vip = "";
                  }
               } catch (VendorException risk) {
                  // 风控 / 设备拦截码穿透上抛，不沿用主信息伪造结论。
                  throw risk;
               } catch (Exception ignoredV) {
                  TraceLog.info("KG vip 查询失败（VIP 置未知，不伪造会员结论）");
                  vip = "";
               }
            } else if (vipProbe) {
               TraceLog.info("KG 指纹不可信，跳过 VIP 探测（VIP 置未知）");
               vip = "";
            }
            if (user != null) {
               String nick = str(user, "nickname");
               // note 只存中性会员标识 "0" / "1" / ""（未知）；指纹不可信时强制未知，不伪造 "1"。
               String extra = vipProbe && fingerprintTrusted ? normalizeVip(vip) : "";
               if (!nick.isBlank()) {
                  nicknameName = nick;
                  return AccountProfile.ok(nick, extra);
               }
               return AccountProfile.ok("", extra);
            }
         } catch (VendorException risk) {
            throw risk;
         } catch (Exception ignored) {
            TraceLog.info("KG 账号信息查询失败（沿用本地昵称），VIP 状态未知");
         }
         // 昵称空串由显示层按语言键兜底；note 留空表示未知，由显示层渲染"VIP 未知"。
         return AccountProfile.ok(nicknameName, "");
      });
   }

   /** 归一化会员标识（T-01B）：空 → ""（未知）；"0"/"1" 原样；其余非空一律 → ""（未知），不再兜底伪造 "1"。 */
   private static String normalizeVip(String vip) {
      if (vip == null || vip.isBlank()) {
         return "";
      }
      if (vip.equals("0") || vip.equals("1")) {
         return vip;
      }
      return "";
   }

   /** 从 get_my_userinfo 响应 data 提取会员标识，统一返回 "0"（无会员）/ "1"（有会员）/ ""（未检测到）。 */
   private String vipDesc(JsonObject user) {
      try {
         if (user.has("vip_type")) {
            return user.get("vip_type").getAsLong() > 0 ? "1" : "0";
         }
         if (user.has("vip") && user.get("vip").getAsLong() > 0) {
            return "1";
         }
         if (user.has("is_vip") && user.get("is_vip").getAsLong() > 0) {
            return "1";
         }
         if (user.has("user_vip_type")) {
            if (user.get("user_vip_type").getAsLong() > 0) {
               return "1";
            }
         }
         if (user.has("vip_name") && user.get("vip_name").isJsonPrimitive()) {
            String n = user.get("vip_name").getAsString();
            if (!n.isBlank() && !"无".equals(n)) {
               return "1";
            }
         }
      } catch (Exception ignored) {
      }
      return "";
   }

   /** 从 get_union_vip 响应提取 VIP 状态，统一返回 "0"（无会员）/ "1"（有会员）/ ""（未检测到）。
    *  优先解析 busi_vip 数组（畅听/概念/音乐包等子会员），再兜底 vip_type 等主字段。 */
   private String vipDescV2(JsonObject root) {
      try {
         JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
         if (data == null) {
            return "";
         }
         boolean vipFieldSeen = false;
         // busi_vip 数组：[{is_vip:1, product_type:"svip"/"tvip", busi_type:"concept", vip_end_time:...}, ...]
         // 酷狗概念版 VIP（畅听会员 svip / 概念会员 tvip）只出现在这里，主字段 is_vip/vip_type 均为 0
         if (data.has("busi_vip") && data.get("busi_vip").isJsonArray()) {
            JsonArray arr = data.getAsJsonArray("busi_vip");
            for (JsonElement el : arr) {
               if (!el.isJsonObject()) {
                  continue;
               }
               JsonObject o = el.getAsJsonObject();
               long isVip = o.has("is_vip") && o.get("is_vip").isJsonPrimitive() ? o.get("is_vip").getAsLong() : 0L;
               if (isVip > 0L) {
                  return "1";
               }
            }
            if (arr.size() > 0) {
               return "0";
            }
         }
         // busi_vip: { userid: { vip_type... } } 嵌套对象（旧结构兜底）
         if (data.has("busi_vip") && data.get("busi_vip").isJsonObject()) {
            JsonObject bv = data.getAsJsonObject("busi_vip");
            for (String k : bv.keySet()) {
               JsonElement el = bv.get(k);
               if (el.isJsonObject()) {
                  String nested = vipDescV2(el.getAsJsonObject());
                  if (!nested.isBlank()) {
                     return nested;
                  }
               }
            }
         }
         for (String k : new String[] { "vip_type", "vip_typeid", "is_vip", "vip", "member_type", "user_vip_type" }) {
            if (data.has(k) && data.get(k).isJsonPrimitive()) {
               try {
                  vipFieldSeen = true;
                  if (data.get(k).getAsLong() > 0) {
                     return "1";
                  }
               } catch (Exception ignoredNum) {
               }
            }
         }
         // 检测到 VIP 字段但均为 0 → 普通用户（而非"未知"）
         if (vipFieldSeen) {
            return "0";
         }
         if (data.has("vip_name") && data.get("vip_name").isJsonPrimitive()) {
            return "1";
         }
         if (data.has("product_name") && data.get("product_name").isJsonPrimitive()) {
            return "1";
         }
      } catch (Exception ignored) {
      }
      return "";
   }

   /** 用户信息主路径：/v3/get_my_info（usercenter 路由）；失败兜底 get_my_userinfo。
    *  标准版失败后依次尝试 LITE（概念版）参数，最后 legacy 双平台。 */
   private JsonObject userInfo() throws Exception {
      try {
         JsonObject r = userInfoV3(false);
         // T-02：风控 / 设备拦截码命中即上抛，不再切换变体重试。
         throwIfRiskControl(r, "userinfo v3");
         if (bizErrorCode(r) != 0L) {
            throw new RuntimeException("userinfo v3 error_code=" + bizErrorCode(r));
         }
         return r;
      } catch (VendorException risk) {
         throw risk;
      } catch (Exception e) {
         TraceLog.warn("KG userinfo v3 fallback lite", e);
         try {
            JsonObject r2 = userInfoV3(true);
            throwIfRiskControl(r2, "userinfo v3 lite");
            if (bizErrorCode(r2) != 0L) {
               throw new RuntimeException("userinfo v3 lite error_code=" + bizErrorCode(r2));
            }
            return r2;
         } catch (VendorException risk) {
            throw risk;
         } catch (Exception e2) {
            TraceLog.warn("KG userinfo v3 lite fallback legacy", e2);
            try {
               JsonObject r3 = userInfoLegacy(false);
               throwIfRiskControl(r3, "userinfo legacy");
               return r3;
            } catch (VendorException risk) {
               throw risk;
            } catch (Exception e3) {
               JsonObject r4 = userInfoLegacy(true);
               throwIfRiskControl(r4, "userinfo legacy lite");
               return r4;
            }
         }
      }
   }

   /** 读取业务错误码；无该字段或非数字返回 0。 */
   private static long bizErrorCode(JsonObject root) {
      if (root == null || !root.has("error_code") || !root.get("error_code").isJsonPrimitive()) {
         return 0L;
      }
      try {
         return root.get("error_code").getAsLong();
      } catch (Exception ignored) {
         return 0L;
      }
   }

   /**
    * 会话 userid 的数字形态。
    *
    * <p>userid 由酷狗下发或玩家手工粘贴 Cookie 得到，可能为空串（未登录）也可能含非数字字符
    * （Cookie 被改坏、粘贴了带引号的值）。此处统一回落 0：这两处原先直接
    * {@code Long.parseLong(userid)}，空串会抛 NumberFormatException 把整次账号/VIP 查询打断，
    * 而其余所有 userid 使用点都写作 {@code userid.isBlank() ? "0" : userid}，语义本身也应当容忍。</p>
    */
   private static long uidOf(String raw) {
      if (raw == null || raw.isBlank()) {
         return 0L;
      }
      try {
         return Long.parseLong(raw.trim());
      } catch (NumberFormatException notNumeric) {
         return 0L;
      }
   }

   /** gateway /v3/get_my_info + x-router:usercenter.kugou.com。
    *  lite=true 时用概念版（LITE）平台参数。 */
   private JsonObject userInfoV3(boolean lite) throws Exception {
      long clienttimeMs = System.currentTimeMillis();
      long ctSec = clienttimeMs / 1000;
      String useAppid = lite ? LITE_APPID : STD_APPID;
      String useClientver = lite ? LITE_CLIENTVER : STD_CLIENTVER;
      // p = cryptoRSAEncrypt({token, clienttime: 秒}).toUpperCase()（裸 RSA，对应平台公钥）
      String p = SecretCipher.rsaRawPowEncryptUpper(
            ("{\"token\":\"" + token + "\",\"clienttime\":" + ctSec + "}").getBytes(StandardCharsets.UTF_8),
            SecretCipher.rsaPublicKeyFromBase64(lite ? LITE_PUBLIC_KEY_B64 : STD_PUBLIC_KEY_B64)).toUpperCase();
      JsonObject map = new JsonObject();
      map.addProperty("visit_time", ctSec);
      map.addProperty("usertype", 1);
      map.addProperty("p", p);
      map.addProperty("userid", uidOf(userid));
      String body = map.toString();
      Map<String, String> qp = new LinkedHashMap<>();
      qp.put("dfid", dfid);
      qp.put("mid", mid);
      qp.put("uuid", "-");
      qp.put("appid", useAppid);
      qp.put("clientver", useClientver);
      qp.put("clienttime", String.valueOf(ctSec));
      if (!token.isBlank()) qp.put("token", token);
      if (!userid.isBlank()) qp.put("userid", userid);
      qp.put("plat", "1");
      qp.put("signature", lite ? liteAndroidSignature(qp, body) : stdAndroidSignature(qp, body));
      HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + "/v3/get_my_info?" + SecretCipher.encodedQuery(qp));
      b.header("User-Agent", lite ? "Android16-1070-11440-130-0-LOGIN-wifi" : "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
      b.header("dfid", dfid);
      b.header("clienttime", String.valueOf(ctSec));
      b.header("mid", mid);
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      b.header("x-router", "usercenter.kugou.com");
      b.header("Content-Type", "application/json");
      b.POST(HttpRequest.BodyPublishers.ofString(body));
      HttpResponse<String> resp = SafeHttpClient.send(b.build());
      String respBody = resp.body();
      if (resp.statusCode() != 200) {
         throw new RuntimeException("userinfo v3 http=" + resp.statusCode());
      }
      String safe = Redactor.message(respBody.length() > 180 ? respBody.substring(0, 180) : respBody);
      TraceLog.info("KG userinfo v3{} {} -> {}", lite ? " lite" : "", resp.statusCode(), safe);
      JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
      // 同步服务端绑定 dfid（云歌单接口要求 dfid 与 token/账号一致，否则 20017）
      try {
         if (root.has("data") && root.get("data").isJsonObject()) {
            JsonObject d = root.getAsJsonObject("data");
            String nd = str(d, "dfid");
            if (nd != null && !nd.isBlank() && nd.length() >= 8) {
               this.dfid = nd;
            }
         }
      } catch (Exception ignored) {
      }
      return root;
   }

   /** 酷狗 VIP 状态：kugouvip.kugou.com/v1/get_union_vip。
    *  多指纹组合兜底：完整设备指纹(用户导入) → 无webgl → 最小参数 → LITE(概念版)参数，返回第一套成功响应。 */
   private JsonObject vipInfo() throws Exception {
      Throwable last = null;
      for (int variant = 0; variant < 4; variant++) {
         try {
            JsonObject r = vipInfoVariant(variant);
            // T-02：风控 / 设备拦截码命中即停止后续变体重试并上抛。
            throwIfRiskControl(r, "vip v" + variant);
            boolean errorOk = r.has("error_code") && r.get("error_code").isJsonPrimitive()
                  && r.get("error_code").getAsLong() == 0L;
            // data 非空且不含 errmsg（20017 失败响应带 data.errmsg="params invalid"，不算成功）
            boolean dataReal = r.has("data") && !r.get("data").isJsonNull() && r.get("data").isJsonObject()
                  && !r.getAsJsonObject("data").has("errmsg");
            if (errorOk || dataReal) {
               return r;
            }
         } catch (VendorException risk) {
            throw risk;
         } catch (Exception e) {
            last = e;
         }
      }
      if (last != null) {
         throw new RuntimeException("vip all variants failed: " + last.getMessage());
      }
      return new JsonObject();
   }

   private JsonObject vipInfoVariant(int variant) throws Exception {
      long ctSec = System.currentTimeMillis() / 1000;
      Map<String, String> qp = new LinkedHashMap<>();
      qp.put("dfid", dfid);
      qp.put("mid", mid);
      qp.put("uuid", "-");
      String useAppid = appid;
      String useClientver = clientver;
      if (variant == 3) { // LITE（概念版）参数
         useAppid = LITE_APPID;
         useClientver = LITE_CLIENTVER;
      }
      qp.put("appid", useAppid);
      qp.put("clientver", useClientver);
      qp.put("clienttime", String.valueOf(ctSec));
      if (!token.isBlank()) qp.put("token", token);
      if (!userid.isBlank()) qp.put("userid", userid);
      qp.put("busi_type", "concept");
      if (variant == 0) { // 完整设备指纹
         qp.put("guid", devGuid);
         qp.put("mac", devMac);
         if (!webglHash.isBlank()) qp.put("webgl", webglHash);
      }
      if (variant == 1 && !webglHash.isBlank()) {
         qp.put("webgl", webglHash);
      }
      qp.put("signature", variant == 3 ? liteAndroidSignature(qp, "") : stdAndroidSignature(qp, ""));
      HttpRequest.Builder b = SafeHttpClient.builder("https://kugouvip.kugou.com/v1/get_union_vip?"
            + SecretCipher.encodedQuery(qp));
      b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
      b.header("dfid", dfid);
      b.header("clienttime", String.valueOf(ctSec));
      b.header("mid", mid);
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      // 完整设备 Cookie（酷狗服务端据此识别客户端完整性）
      String cookie = "kg_mid=" + mid + "; dfid=" + dfid + "; KUGOU_API_MID=" + mid;
      if (!webglHash.isBlank()) {
         cookie += "; KUGOU_API_WEBGL=" + webglHash;
      }
      cookie += "; GUID=" + devGuid + "; MAC=" + devMac + "; KUGOU_DEV=" + devName;
      b.header("Cookie", cookie);
      HttpResponse<String> resp = SafeHttpClient.send(b.build());
      String respBody = resp.body();
      if (resp.statusCode() != 200) {
         throw new RuntimeException("vip http=" + resp.statusCode());
      }
      String safe = Redactor.message(respBody.length() > 180 ? respBody.substring(0, 180) : respBody);
      TraceLog.info("KG vip v{} {} -> {}", variant, resp.statusCode(), safe);
      return JsonParser.parseString(respBody).getAsJsonObject();
   }

   /** 兜底：get_my_userinfo（relation.user.kugou.com）。lite=true 时用概念版（LITE）平台参数。 */
   private JsonObject userInfoLegacy(boolean lite) throws Exception {
      long clienttimeMs = System.currentTimeMillis();
      String useAppid = lite ? LITE_APPID : STD_APPID;
      String useClientver = lite ? LITE_CLIENTVER : STD_CLIENTVER;
      String useSalt = lite ? LITE_SALT : STD_SALT;
      String p = SecretCipher.rsaRawPowEncryptUpper(
            ("{\"clienttime\":" + clienttimeMs + ",\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8),
            SecretCipher.rsaPublicKeyFromBase64(lite ? LITE_PUBLIC_KEY_B64 : STD_PUBLIC_KEY_B64)).toUpperCase();
      // signParamsKey = md5(appid + salt + clientver + data)
      String key = SecretCipher.md5Hex(useAppid + useSalt + useClientver + clienttimeMs);
      JsonObject map = new JsonObject();
      map.addProperty("p", p);
      map.addProperty("appid", Integer.parseInt(useAppid));
      map.addProperty("mid", mid);
      map.addProperty("clientver", Integer.parseInt(useClientver));
      map.addProperty("source", 0);
      map.addProperty("clienttime", clienttimeMs);
      map.addProperty("uuid", "-");
      map.addProperty("userid", uidOf(userid));
      map.addProperty("key", key);
      String body = map.toString();
      long ct = clienttimeMs / 1000;
      // 对齐客户端：URL query 带默认参数(dfid/mid/uuid/appid/clientver/clienttime/token/userid)+signature，
      // body 才是 dataMap(p/key/source/uuid/userid)，signature = md5(盐 + 排序参数 + body + 盐)
      Map<String, String> qp = new LinkedHashMap<>();
      qp.put("dfid", dfid);
      qp.put("mid", mid);
      qp.put("uuid", "-");
      qp.put("appid", useAppid);
      qp.put("clientver", useClientver);
      qp.put("clienttime", String.valueOf(ct));
      if (!token.isBlank()) qp.put("token", token);
      if (!userid.isBlank()) qp.put("userid", userid);
      qp.put("signature", lite ? liteAndroidSignature(qp, body) : stdAndroidSignature(qp, body));
      HttpRequest.Builder b = SafeHttpClient.builder("http://relation.user.kugou.com/v1/get_my_userinfo?"
            + SecretCipher.encodedQuery(qp));
      try {
         b.header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
         b.header("dfid", dfid);
         b.header("clienttime", String.valueOf(ct));
         b.header("mid", mid);
         b.header("kg-rc", "1");
         b.header("kg-thash", "5d816a0");
         b.header("kg-rec", "1");
         b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
         b.header("Content-Type", "application/json");
         b.header("Cookie", "kg_mid=" + mid + "; dfid=" + dfid + "; KUGOU_API_MID=" + mid
               + "; KUGOU_API_GUID=" + devGuid + "; KUGOU_API_MAC=" + devMac + "; KUGOU_API_DEV=" + devName
               + (token.isBlank() ? "" : "; token=" + token)
               + (userid.isBlank() ? "" : "; userid=" + userid));
         b.POST(HttpRequest.BodyPublishers.ofString(body));
      } catch (Throwable he) {
         try {
         } catch (Exception ignored) {
         }
         throw he instanceof Exception ? (Exception) he : new RuntimeException(he);
      }
      try {
      } catch (Exception ignored) {
      }
      HttpResponse<String> resp;
      try {
         resp = SafeHttpClient.send(b.build());
      } catch (Throwable se) {
         try {
         } catch (Exception ignored) {
         }
         if (se instanceof Exception) {
            throw (Exception) se;
         }
         throw new RuntimeException(se);
      }
      String respBody;
      try {
         respBody = resp.body();
      } catch (Throwable be) {
         try {
         } catch (Exception ignored) {
         }
         throw be instanceof Exception ? (Exception) be : new RuntimeException(be);
      }
      try {
      } catch (Exception ignored) {
      }
      if (resp.statusCode() != 200) {
         TraceLog.info("KG userinfo http={} (denied, body not logged)", resp.statusCode());
         try {
         } catch (Exception ignored) {
         }
         throw new RuntimeException("userinfo http=" + resp.statusCode());
      }
      // 脱敏日志：只打印状态码 + 响应体截断前缀，凭证 / 指纹统一由 Redactor 替换为 ***
      String safe = Redactor.message(respBody.length() > 180 ? respBody.substring(0, 180) : respBody);
      TraceLog.info("KG userinfo {} -> {}", resp.statusCode(), safe);
      try {
         JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
         // T-02：风控 / 设备拦截码命中即上抛，不再继续后续变体。
         throwIfRiskControl(root, "userinfo legacy" + (lite ? " lite" : ""));
         JsonObject user = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
         if (user != null && user.has("dfid") && !user.get("dfid").isJsonNull()) {
            String nd = user.get("dfid").getAsString();
            if (nd != null && !nd.isBlank() && nd.length() >= 8 && !nd.equals(dfid)) {
               TraceLog.info("KG userinfo dfid 更新为服务端绑定值（云歌单接口需匹配）");
               this.dfid = nd;
            }
         }
         StringBuilder diag = new StringBuilder();
         if (user != null) {
            for (Map.Entry<String, JsonElement> e : user.entrySet()) {
               String v = e.getValue().isJsonNull() ? "null"
                     : (e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
               v = v.replaceAll("(?i)(token|access_token|auth)[\"']?\\s*[:=]\\s*[\"']?[^,\"'}{]{6,}", "$1=***");
               diag.append(e.getKey()).append("=").append(v.substring(0, Math.min(40, v.length()))).append("\n");
            }
         }
         diag.append("ALL_KEYS=").append(user == null ? "null" : user.keySet().toString()).append("\n");
      } catch (Exception diagEx) {
         TraceLog.warn("KG userinfo diag fail", diagEx);
      }
      return JsonParser.parseString(respBody).getAsJsonObject();
   }

   @Override
   public CompletableFuture<AccountProfile> importCredential(String raw) {
      return CompletableFuture.supplyAsync(() -> {
         requireNotFreeOnly();
         // raw 形如 token=xxx;userid=yyy 或直接 JSON
         try {
            String t = "";
            String uid = "";
            String nick = "";
            String r = raw == null ? "" : raw.trim();
            if (r.startsWith("{")) {
               JsonObject o = JsonParser.parseString(r).getAsJsonObject();
               t = str(o, "token");
               uid = str(o, "userid");
               nick = str(o, "nickname");
            } else {
               for (String part : r.split("[;,]")) {
                  int eq = part.indexOf('=');
                  if (eq > 0) {
                     String k = part.substring(0, eq).trim();
                     String v = part.substring(eq + 1).trim();
                     if (k.equals("token")) {
                        t = v;
                     } else if (k.equals("userid") || k.equals("uid")) {
                        uid = v;
                     } else if (k.equals("nickname")) {
                        nick = v;
                     }
                  }
               }
            }
            if (t.isBlank() || uid.isBlank()) {
               throw new VendorException(VendorError.TOKEN_MISSING);
            }
            setSession(t, uid, nick);
            // token 为酷狗官方扫码/授权返回的有效凭证，验证接口可能因平台/设备维度失败，
            // 不因 checkAccount 失败而阻止登录成功。
            try {
               AccountProfile state = verifyAccount().join();
               if (state.active()) {
                  return state;
               }
            } catch (Exception ignored) {
            }
            return AccountProfile.ok(nick, "");
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.CREDENTIAL_INVALID, e, vendor.label);
         }
      });
   }

   @Override
   public void signOut() {
      // R-01：退出登录主动清空账号凭证驻留；token/userid 为 String 进入字符串池后无法强制覆写，
      // 属 JVM 残余风险，在安全报告残余项记录；此处先把字段引用置空、停止后续请求携带。
      token = "";
      userid = "";
      authCache = "";
      nicknameName = "";
      streamCache.clear();
   }

   @Override
   public CompletableFuture<AccountProfile> signInByPassword(String account, String password) {
      requireNotFreeOnly();
      try {
         return CompletableFuture.completedFuture(loginByPasswordSync(account, password));
      } catch (Exception e) {
         return CompletableFuture.failedFuture(e);
      }
   }

   private AccountProfile loginByPasswordSync(String account, String password) throws Exception {
      long dateNow = System.currentTimeMillis();
      String tempKey = randomString(16).toLowerCase();
      String aesKey = SecretCipher.md5Hex(tempKey).substring(0, 32);
      String aesIv = aesKey.substring(16);
      String pwdJson = "{\"pwd\":\"" + jsonEscape(password) + "\",\"code\":\"\",\"clienttime_ms\":" + dateNow + "}";
      String params = SecretCipher.cbcEncryptHex(pwdJson, aesKey, aesIv);
      String pkData = "{\"clienttime_ms\":" + dateNow + ",\"key\":\"" + tempKey + "\"}";
      String pk = SecretCipher.rsaRawPowEncryptUpper(pkData.getBytes(StandardCharsets.UTF_8), publicKey);
      String dataMap = "{\"plat\":1,\"support_multi\":1,\"clienttime_ms\":" + dateNow
            + ",\"t1\":\"562a6f12a6e803453647d16a08f5f0c2ff7eee692cba2ab74cc4c8ab47fc467561a7c6b586ce7dc46a63613b246737c03a1dc8f8d162d8ce1d2c71893d19f1d4b797685a4c6d3d81341cbde65e488c4829a9b4d42ef2df470eb102979fa5adcdd9b4eecfea8b909ff7599abeb49867640f10c3c70fc444effca9d15db44a9a6c907731e2bb0f22cd9b3536380169995693e5f0e2424e3378097d3813186e3fe96bbe7023808a0981b4e2b6135a76faac\""
            + ",\"t2\":\"31c4daf4cf480169ccea1cb7d4a209295865a9d2b788510301694db229b87807469ea0d41b4d4b9173c2151da7294aeebfc9738df154bbdf11a4e117bb5dff6a3af8ce5ce333e681c1f29a44038f27567d58992eb81283e080778ac77db1400fdf49b7cf7e26be2e5af4da7830cc3be4\""
            + ",\"t3\":\"MCwwLDAsMCwwLDAsMCwwLDA=\""
            + ",\"username\":\"" + jsonEscape(account) + "\",\"params\":\"" + params + "\",\"pk\":\"" + pk + "\"}";
      long clienttime = dateNow / 1000;
      Map<String, String> prm = new LinkedHashMap<>();
      prm.put("dfid", dfid);
      prm.put("mid", mid);
      prm.put("uuid", "-");
      prm.put("appid", appid);
      prm.put("clientver", clientver);
      prm.put("clienttime", String.valueOf(clienttime));
      prm.put("signature", androidSignature(prm, dataMap));

      HttpRequest.Builder b = SafeHttpClient.builder(GATEWAY + "/v9/login_by_pwd?" + SecretCipher.encodedQuery(prm));
      b.header("Content-Type", "application/json;charset=utf-8");
      b.header("x-router", "login.user.kugou.com");
      b.header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
      b.header("dfid", dfid);
      b.header("clienttime", String.valueOf(clienttime));
      b.header("mid", mid);
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      b.POST(HttpRequest.BodyPublishers.ofString(dataMap));
      HttpResponse<String> resp = SafeHttpClient.send(b.build());
      String ssa = resp.headers().firstValue("ssa-code").orElse("").trim();
      JsonObject root;
      try {
         root = JsonParser.parseString(resp.body()).getAsJsonObject();
      } catch (Exception e) {
         TraceLog.warn("KG 密码登录响应解析失败，原始响应已记录：{}", SafeHttpClient.describeBody(resp.body()));
         return AccountProfile.fail(VendorError.CREDENTIAL_INVALID, vendor.label);
      }
      if (!ssa.isBlank()) {
         return AccountProfile.fail(VendorError.QR_CAPTCHA_REQUIRED);
      }
      int status = root.has("status") ? root.get("status").getAsInt() : 0;
      if (status == 1 && root.has("data") && root.getAsJsonObject("data").has("secu_params")) {
         String secu = root.getAsJsonObject("data").get("secu_params").getAsString();
         String dec = decryptAes(hexToBytes(secu), aesKey, aesIv);
         JsonObject tok = JsonParser.parseString(dec).getAsJsonObject();
         String t = str(tok, "token");
         String uid = str(tok, "userid");
         String nick = str(tok, "nickname");
         this.token = t;
         this.userid = uid;
         this.nicknameName = nick;
         if (t.isBlank()) {
            return AccountProfile.fail(VendorError.TOKEN_MISSING);
         }
         return AccountProfile.ok(nick.isBlank() ? "" : nick, "");
      }
      if (root.has("msg")) {
         TraceLog.warn("KG 密码登录返回平台错误 status={} msg：{}", status, SafeHttpClient.describeBody(root.get("msg").getAsString()));
      }
      return AccountProfile.fail(VendorError.CREDENTIAL_INVALID, vendor.label);
   }

   private static String jsonEscape(String s) {
      return s.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   @Override
   public QrInit createQr() throws Exception {
      requireNotFreeOnly();
      String webAppid = "1014"; // web 端签名
      String clienttime = String.valueOf(System.currentTimeMillis() / 1000);
      String qrcodeTxt = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=" + appid + "&";
      Map<String, String> params = new LinkedHashMap<>();
      params.put("dfid", "-");
      params.put("mid", "undefined");
      params.put("uuid", "-");
      params.put("appid", webAppid);
      params.put("clientver", clientver);
      params.put("clienttime", clienttime);
      params.put("type", "1");
      params.put("plat", "4");
      params.put("srcappid", "2919");
      params.put("qrcode_txt", qrcodeTxt);
      params.put("signature", webSignature(params));
      Map<String, String> urlParams = new LinkedHashMap<>(params);
      urlParams.put("qrcode_txt", SecretCipher.urlEncode(qrcodeTxt));
      HttpRequest.Builder b = SafeHttpClient.builder(LOGIN_USER + "/v2/qrcode?" + SecretCipher.rawQuery(urlParams));
      b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
      b.header("dfid", "-");
      b.header("clienttime", clienttime);
      b.header("mid", "undefined");
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
      if (resp.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
      if (root.has("error_code") && root.get("error_code").getAsInt() != 0) {
         TraceLog.warn("{} 二维码创建失败：{}", vendor.label, SafeHttpClient.describeBody(root.toString()));
         throw new VendorException(VendorError.QR_BAD_PAYLOAD, vendor.label);
      }
      JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
      String qrCode = str(data, "qrcode");
      String url = str(data, "url");
      if (qrCode.isBlank() && url != null && !url.isBlank()) {
         // 部分版本直接给 url 作为扫码 key
         qrCode = url;
      }
      if (qrCode.isBlank()) {
         throw new VendorException(VendorError.QR_KEY_MISSING, vendor.label);
      }
      return new QrInit("https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=" + appid + "&qrcode="
            + SecretCipher.urlEncode(qrCode), qrCode, "");
   }

   private String webSignature(Map<String, String> params) {
      TreeMap<String, String> sorted = new TreeMap<>(params);
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, String> e : sorted.entrySet()) {
         sb.append(e.getKey()).append('=').append(e.getValue());
      }
      return SecretCipher.md5Hex(WEB_SALT + sb + WEB_SALT);
   }

   @Override
   public QrPoll pollQr(String sessionKey) throws Exception {
      requireNotFreeOnly();
      long clienttime = System.currentTimeMillis() / 1000;
      Map<String, String> params = new LinkedHashMap<>();
      params.put("dfid", "-");
      params.put("mid", "undefined");
      params.put("uuid", "-");
      params.put("appid", appid);
      params.put("clientver", clientver);
      params.put("clienttime", String.valueOf(clienttime));
      params.put("plat", "4");
      params.put("srcappid", "2919");
      params.put("qrcode", sessionKey);
      params.put("signature", webSignature(params));
      HttpRequest.Builder b = SafeHttpClient.builder(LOGIN_USER + "/v2/get_userinfo_qrcode?" + SecretCipher.rawQuery(params));
      b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
      b.header("dfid", "-");
      b.header("clienttime", String.valueOf(clienttime));
      b.header("mid", "undefined");
      b.header("kg-rc", "1");
      b.header("kg-thash", "5d816a0");
      b.header("kg-rec", "1");
      b.header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
      HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
      if (resp.statusCode() != 200) {
         return QrPoll.failed(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
      JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
      int status = data.has("status") && data.get("status").isJsonPrimitive() ? data.get("status").getAsInt() : -1;
      // 酷狗 get_userinfo_qrcode data.status: 1=等待扫码, 2=已扫码待确认, 4=登录成功(返回 token/userid), 0=过期
      if (status == 4) {
         String token = str(data, "token");
         String userid = str(data, "userid");
         String nick = str(data, "nickname");
         if (token.isBlank() || userid.isBlank()) {
            return QrPoll.failed(VendorError.TOKEN_MISSING);
         }
         // 带上昵称，importLogin 会解析并保存，checkAccount 回退时显示真实昵称
         return QrPoll.success(
               "token=" + token + ";userid=" + userid + (nick.isBlank() ? "" : ";nickname=" + nick));
      } else if (status == 2) {
         return QrPoll.scanned();
      } else if (status == 1) {
         return QrPoll.waiting();
      } else if (status == 0) {
         return QrPoll.expired();
      }
      return QrPoll.waiting();
   }

   private static String str(JsonObject o, String key) {
      if (o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
         return o.get(key).getAsString();
      }
      return "";
   }

   /** 统一时长解析：timelen 优先（毫秒）；duration 兜底（部分接口如 special/song 返回秒，<1000 视为秒并转毫秒）。 */
   private static long durationOf(JsonObject s) {
      long dur = s.has("timelen") && s.get("timelen").isJsonPrimitive()
            ? s.get("timelen").getAsLong() : 0L;
      if (dur <= 0L && s.has("duration") && s.get("duration").isJsonPrimitive()) {
         dur = s.get("duration").getAsLong();
         if (dur > 0L && dur < 1000L) {
            dur *= 1000L;
         }
      }
      return dur;
   }

   /** 歌单内歌曲添加时间（毫秒，0=未知）。兼容 addtime/add_time/uptime/createtime；秒级自动转毫秒。 */
   private static long addTimeOf(JsonObject s) {
      for (String k : new String[]{"addtime", "add_time", "uptime", "createtime", "create_time"}) {
         if (s.has(k) && s.get(k).isJsonPrimitive()) {
            try {
               long v = s.get(k).getAsLong();
               if (v > 0L) {
                  // 10 位为秒级时间戳，13 位为毫秒级
                  return v < 10_000_000_000L ? v * 1000L : v;
               }
            } catch (Exception ignored) {
            }
         }
      }
      return 0L;
   }

   /**
    * 提取酷狗歌曲封面地址。
    * 优先 {@code trans_param.union_cover}（搜索 / special / v4 歌单常见，内含 {@code {size}} 占位），
    * 再兜底常见直链字段；归一化与域名白名单校验见 {@link #normalizeCoverUrl}，
    * 缺失或不可信时返回空串（由上层按"无封面"处理，不构造非法地址）。
    */
   private static String kugouCover(JsonObject s) {
      if (s == null) {
         return "";
      }
      String raw = "";
      if (s.has("trans_param") && s.get("trans_param").isJsonObject()) {
         raw = str(s.getAsJsonObject("trans_param"), "union_cover");
      }
      if (raw.isBlank()) {
         for (String key : new String[]{"img", "album_img", "album_picture", "cover", "picture", "album_cover", "imgurl"}) {
            raw = str(s, key);
            if (!raw.isBlank()) {
               break;
            }
         }
      }
      return normalizeCoverUrl(raw);
   }

   /** 封面 URL 归一化：{@code {size}} 替换为缩略尺寸、http 升级 https、封面域名白名单校验；非法返回空串。 */
   private static String normalizeCoverUrl(String raw) {
      if (raw == null || raw.isBlank()) {
         return "";
      }
      raw = raw.trim().replace("{size}", "120");
      if (raw.startsWith("http://")) {
         raw = "https://" + raw.substring(7);
      }
      try {
         URI uri = URI.create(raw);
         String host = uri.getHost();
         if (host == null) {
            return "";
         }
         String h = host.toLowerCase();
         boolean trusted = h.equals("imge.kugou.com") || h.endsWith(".kugou.com")
               || h.endsWith(".kqcdn.com") || h.endsWith(".gtimg.cn") || h.endsWith(".qq.com");
         if (!trusted) {
            return "";
         }
         return uri.toASCIIString();
      } catch (Exception e) {
         return "";
      }
   }

   /**
    * 封面兜底补全：仅对封面为空且 hash 非空的条目，按 hash 批量请求独立图片接口；
    * 分组 ≤10 个，整批失败静默跳过（不阻断搜索 / 歌单加载），仍缺封面的条目保持空串由上层告警。
    * 返回补全后的新歌曲列表；原列表条目不变更。
    */
   private List<SongEntry> backfillCovers(List<SongEntry> tracks) {
      if (tracks == null || tracks.isEmpty()) {
         return tracks;
      }
      List<SongEntry> result = new ArrayList<>(tracks.size());
      Map<String, String> pending = new LinkedHashMap<>();
      for (SongEntry t : tracks) {
         if ((t.coverUri() == null || t.coverUri().isBlank())
               && t.songId() != null && !t.songId().isBlank()) {
            pending.put(t.songId(), "");
         }
      }
      if (pending.isEmpty()) {
         return tracks;
      }
      List<String> hashes = new ArrayList<>(pending.keySet());
      for (int start = 0; start < hashes.size(); start += 10) {
         List<String> batch = hashes.subList(start, Math.min(start + 10, hashes.size()));
         try {
            pending.putAll(fetchCoverByHashes(batch));
         } catch (Exception e) {
            TraceLog.debug("KG 封面兜底整批请求失败，跳过本批: {}", e.toString());
         }
      }
      for (SongEntry t : tracks) {
         String cover = pending.get(t.songId());
         if (cover != null && !cover.isBlank()) {
            result.add(new SongEntry(t.vendor(), t.songId(), t.name(), t.performer(),
                  t.lengthMs(), cover, t.addedAtMs()));
         } else {
            result.add(t);
         }
      }
      return result;
   }

   /** 歌单 / 搜索结果包装：兜底补封面后重建不可变 {@link PlaylistContents}。 */
   private PlaylistContents decoratePlaylist(PlaylistContents playlist) {
      if (playlist == null) {
         return null;
      }
      return new PlaylistContents(playlist.name(), backfillCovers(playlist.tracks()));
   }

   /**
    * 按 hash 批量请求独立图片接口（album_id / album_audio_id 缺省填 0，以 hash 为主键）；
    * 响应字段宽松兼容多形态；返回 hash → 归一化封面 URL；无可用封面返回空 Map。
    */
   private Map<String, String> fetchCoverByHashes(List<String> hashes) throws Exception {
      JsonArray data = new JsonArray();
      for (String hash : hashes) {
         JsonObject item = new JsonObject();
         item.addProperty("album_id", 0);
         item.addProperty("hash", hash);
         item.addProperty("album_audio_id", 0);
         data.add(item);
      }
      Map<String, String> params = new LinkedHashMap<>();
      params.put("appid", STD_APPID);
      params.put("clientver", STD_CLIENTVER);
      params.put("album_image_type", "-3");
      params.put("author_image_type", "3,4,5");
      params.put("count", "5");
      params.put("isCdn", "1");
      params.put("publish_time", "1");
      params.put("data", data.toString());
      params.put("signature", stdAndroidSignature(params, ""));

      String url = "https://expendablekmr.kugou.com/container/v2/image?"
            + SecretCipher.encodedQuery(params);
      HttpRequest.Builder b = SafeHttpClient.builder(url);
      b.header("User-Agent", "Android16-1070-20489-130-0-LOGIN-wifi");
      b.header("Content-Type", "application/json");
      HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
      if (resp.statusCode() != 200) {
         return Map.of();
      }
      JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
      JsonArray items = extractCoverArray(root);
      Map<String, String> result = new LinkedHashMap<>();
      if (items == null) {
         return result;
      }
      for (JsonElement element : items) {
         if (!element.isJsonObject()) {
            continue;
         }
         JsonObject o = element.getAsJsonObject();
         String hash = firstText(o, "hash", "album_audio_id", "album_id");
         String cover = firstCoverUrl(o);
         String normalized = normalizeCoverUrl(cover);
         if (!hash.isBlank() && !normalized.isBlank()) {
            result.put(hash, normalized);
         }
      }
      return result;
   }

   /** 宽松提取响应中的封面条目数组（data 数组 / data.list / data.images / data.songs / images）。 */
   private static JsonArray extractCoverArray(JsonObject root) {
      if (root == null) {
         return null;
      }
      JsonArray direct = arrayOrNull(root, "data");
      if (direct != null) {
         return direct;
      }
      JsonObject dataObj = root.has("data") && root.get("data").isJsonObject()
            ? root.getAsJsonObject("data") : null;
      if (dataObj != null) {
         for (String key : new String[]{"list", "images", "songs", "albums"}) {
            JsonArray nested = arrayOrNull(dataObj, key);
            if (nested != null) {
               return nested;
            }
         }
      }
      return arrayOrNull(root, "images");
   }

   private static JsonArray arrayOrNull(JsonObject parent, String key) {
      if (parent != null && parent.has(key) && parent.get(key).isJsonArray()) {
         return parent.getAsJsonArray(key);
      }
      return null;
   }

   private static String firstText(JsonObject o, String... keys) {
      for (String key : keys) {
         String value = str(o, key);
         if (!value.isBlank()) {
            return value;
         }
      }
      return "";
   }

   /** 封面字段候选顺序：专辑封面 / 专辑图 / 通用图 / 封面图。 */
   private static String firstCoverUrl(JsonObject o) {
      for (String key : new String[]{"album_cover", "album_picture", "album_img", "album_cover_url",
            "img", "cover", "picture", "imgurl", "author_image"}) {
         String value = str(o, key);
         if (!value.isBlank()) {
            return value;
         }
      }
      return "";
   }
}
