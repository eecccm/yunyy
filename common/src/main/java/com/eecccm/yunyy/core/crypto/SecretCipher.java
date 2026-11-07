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

package com.eecccm.yunyy.core.crypto;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 纯 JDK 加密与编解码原语（跨服务商复用，不依赖任何外部库）。
 *
 * <p>凭证加密统一走 AES-GCM（随机 12 字节 IV，Base64(IV‖密文)）；
 * 酷狗协议另有 CBC/ECB/RSA 变体与「6 位随机 key + MD5 派生」的云歌单加密。</p>
 */
public final class SecretCipher {

   private SecretCipher() {
   }

   // ---------- 摘要 ----------

   public static String md5Hex(String data) {
      try {
         byte[] digest = MessageDigest.getInstance("MD5")
               .digest(data.getBytes(StandardCharsets.UTF_8));
         return bytesToHex(digest);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   public static String md5HexUpper(String data) {
      return md5Hex(data).toUpperCase();
   }

   // ---------- AES-CBC（酷狗协议） ----------

   public static String cbcEncryptHex(String data, String key, String iv) {
      return bytesToHex(cbcEncryptBytes(data, key, iv));
   }

   public static String cbcEncryptBase64(String data, String key, String iv) {
      return Base64.getEncoder().encodeToString(cbcEncryptBytes(data, key, iv));
   }

   private static byte[] cbcEncryptBytes(String data, String key, String iv) {
      try {
         Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
         cipher.init(Cipher.ENCRYPT_MODE,
               new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
               new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
         return cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /**
    * 酷狗云歌单请求加密：随机 6 位小写 key，md5(key) 前 16 位做 AES key、后 16 位做 IV。
    * @return [随机key, base64密文]
    */
   public static String[] kgPayloadEncrypt(String json) {
      String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
      StringBuilder sb = new StringBuilder(6);
      for (int i = 0; i < 6; i++) {
         sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
      }
      String key = sb.toString().toLowerCase();
      String dig = md5Hex(key);
      String encKey = dig.substring(0, 16);
      String iv = dig.substring(16, 32);
      return new String[]{key, cbcEncryptBase64(json, encKey, iv)};
   }

   /** 酷狗云歌单响应解密：key 为 6 位随机串，md5(key) 派生 AES key/iv，输入 base64 密文，返回明文。 */
   public static String kgPayloadDecrypt(String base64Str, String key) {
      String dig = md5Hex(key.toLowerCase());
      String encKey = dig.substring(0, 16);
      String iv = dig.substring(16, 32);
      try {
         Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
         cipher.init(Cipher.DECRYPT_MODE,
               new SecretKeySpec(encKey.getBytes(StandardCharsets.UTF_8), "AES"),
               new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
         byte[] dec = cipher.doFinal(Base64.getDecoder().decode(base64Str));
         return new String(dec, StandardCharsets.UTF_8);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   // ---------- AES-ECB ----------

   public static String ecbEncryptHex(String data, String key) {
      try {
         Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
         cipher.init(Cipher.ENCRYPT_MODE,
               new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
         return bytesToHex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /** AES-ECB 解密（hex 输入），用于旧版本地凭证向后兼容（新数据一律 GCM）。 */
   public static String ecbDecryptHex(String hexData, String key) {
      try {
         Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
         cipher.init(Cipher.DECRYPT_MODE,
               new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
         return new String(cipher.doFinal(hexToBytes(hexData)), StandardCharsets.UTF_8);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   // ---------- AES-GCM（凭证加密主方案） ----------

   /**
    * AES-GCM 加密：随机 12 字节 IV，输出 Base64(IV ‖ 密文)。
    * key 为 hex（32 hex=16 字节 / 64 hex=32 字节）。
    */
   public static String gcmEncryptBase64(String plain, String keyHex) {
      try {
         byte[] key = hexToBytes(keyHex);
         byte[] iv = new byte[12];
         new SecureRandom().nextBytes(iv);
         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
               new GCMParameterSpec(128, iv));
         byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
         byte[] out = new byte[iv.length + ct.length];
         System.arraycopy(iv, 0, out, 0, iv.length);
         System.arraycopy(ct, 0, out, iv.length, ct.length);
         return Base64.getEncoder().encodeToString(out);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /** AES-GCM 解密：输入 Base64(IV ‖ 密文)。 */
   public static String gcmDecryptBase64(String data, String keyHex) {
      try {
         byte[] key = hexToBytes(keyHex);
         byte[] all = Base64.getDecoder().decode(data);
         if (all.length < 12 + 16) {
            throw new IllegalArgumentException("invalid gcm blob");
         }
         byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
         byte[] ct = java.util.Arrays.copyOfRange(all, 12, all.length);
         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
               new GCMParameterSpec(128, iv));
         return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   // ---------- RSA ----------

   /** RSA-ECB-PKCS1 加密（hex 大写），用于酷狗云歌单 portrait。 */
   public static String rsaEncryptHexUpper(String data, PublicKey key) {
      try {
         Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
         cipher.init(Cipher.ENCRYPT_MODE, key);
         return bytesToHex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8))).toUpperCase();
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /** 解析 DER 编码的 RSA SubjectPublicKeyInfo（X.509）。 */
   public static PublicKey rsaPublicKeyFromBase64(String base64PublicKey) {
      try {
         return java.security.KeyFactory.getInstance("RSA")
               .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /** 酷狗登录用「原始 RSA 模幂加密」：m^e mod n，结果 hex 大写、左补零到密钥长度。 */
   public static String rsaRawPowEncryptUpper(byte[] data, PublicKey pub) {
      try {
         RSAPublicKey r = (RSAPublicKey) pub;
         BigInteger n = r.getModulus();
         BigInteger e = r.getPublicExponent();
         int keyLength = (n.bitLength() + 7) / 8;
         BigInteger m = new BigInteger(1, data);
         BigInteger c = m.modPow(e, n);
         String hex = c.toString(16);
         int pad = keyLength * 2 - hex.length();
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < pad; i++) {
            sb.append('0');
         }
         sb.append(hex);
         return sb.toString().toUpperCase();
      } catch (Exception ex) {
         throw new IllegalStateException(ex);
      }
   }

   // ---------- 编码 ----------

   public static String urlEncode(String value) {
      return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
   }

   /** 参数按 key 排序后拼 k=v&…（签名用）。 */
   public static String sortedQuery(Map<String, String> params) {
      TreeMap<String, String> sorted = new TreeMap<>(params);
      return sorted.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&"));
   }

   /** 参数按原始顺序拼 k=v&…（不要求排序的接口）。 */
   public static String rawQuery(Map<String, String> params) {
      return params.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&"));
   }

   /** 按 encodeURIComponent 规则编码后拼 query（签名仍用未编码原始值）。 */
   public static String encodedQuery(Map<String, String> params) {
      return params.entrySet().stream()
            .map(e -> e.getKey() + "=" + jsEncode(e.getValue()))
            .collect(Collectors.joining("&"));
   }

   // ---------- 十六进制 ----------

   public static byte[] hexToBytes(String hex) {
      int len = hex.length() / 2;
      byte[] out = new byte[len];
      for (int i = 0; i < len; i++) {
         out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
   }

   public static String bytesToHex(byte[] data) {
      StringBuilder sb = new StringBuilder(data.length * 2);
      for (byte b : data) {
         sb.append(String.format("%02x", b & 0xFF));
      }
      return sb.toString();
   }

   private static String jsEncode(String s) {
      StringBuilder sb = new StringBuilder();
      for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
         int c = b & 0xFF;
         if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
               || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
               || c == '*' || c == '\'' || c == '(' || c == ')') {
            sb.append((char) c);
         } else {
            sb.append('%').append(String.format("%02X", c));
         }
      }
      return sb.toString();
   }
}
