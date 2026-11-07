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

import com.eecccm.yunyy.core.log.TraceLog;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNS rebinding 防护：出站目标在 scheme / 域名白名单校验之外，再做"解析后 IP"复核。
 *
 * <p>判定口径（fail-closed）：</p>
 * <ul>
 *   <li>host 为 IP 字面量：直接复用 {@link SafeHttpClient#isReservedPrivateHost(String)} 判定；</li>
 *   <li>host 为域名：解析全部 A/AAAA 记录，任一地址属于环回 / 内网 / 链路本地 / 元数据 /
 *       组播 / 保留段即拒绝；全部为公网地址才放行；</li>
 *   <li>解析失败（DNS 不存在 / 超时 / 安全管理器拒绝）一律拒绝，不回退到"放行"。</li>
 * </ul>
 *
 * <p>解析结果按短 TTL 缓存（默认 60 秒，可由 {@link com.eecccm.yunyy.core.config.ServerPolicy}
 * 调整），避免每次出站都触发 DNS 查询；缓存只记录"是否允许"，不记录原始 IP 列表，防止指纹侧信道。</p>
 */
public final class ReservedAddressGuard {

   private ReservedAddressGuard() {
   }

   /** 缓存条目：判定结果 + 过期时间戳。 */
   private record Entry(boolean allowed, long expiresAtMillis) {
   }

   private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

   /** 默认解析结果缓存 TTL（毫秒）。 */
   private static final long DEFAULT_TTL_MILLIS = 60_000L;

   /**
    * 判定目标 host 是否解析到公网可达地址（非保留 / 内网 / 环回段）。
    * 供初始请求、重定向目标、下载目标三处统一调用。
    */
   public static boolean isPublicReachable(String host) {
      if (host == null || host.isBlank()) {
         return false;
      }
      String key = host.trim().toLowerCase();
      long now = System.currentTimeMillis();
      Entry cached = CACHE.get(key);
      if (cached != null && cached.expiresAtMillis() > now) {
         return cached.allowed();
      }
      boolean allowed = resolveAndCheck(key);
      CACHE.put(key, new Entry(allowed, now + ttlMillis()));
      return allowed;
   }

   private static long ttlMillis() {
      try {
         long seconds = com.eecccm.yunyy.core.config.ServerPolicy.dnsCacheTtlSeconds;
         if (seconds < 5L) {
            seconds = 5L;
         }
         if (seconds > 300L) {
            seconds = 300L;
         }
         return seconds * 1000L;
      } catch (Throwable ignored) {
         return DEFAULT_TTL_MILLIS;
      }
   }

   private static boolean resolveAndCheck(String host) {
      // IP 字面量：本地判定，不发起 DNS 查询。
      if (SafeHttpClient.isReservedPrivateHost(host)) {
         return false;
      }
      if (isIpLiteral(host)) {
         // 字面量必须真实解析后再判定。仅按字符串前缀识别保留段会漏掉 IPv4 映射地址：
         // ::ffff:127.0.0.1 / ::ffff:169.254.169.254 含冒号，会被 isIpLiteral 认作字面量，
         // 又匹配不上 isReservedPrivateHost 的 IPv6 前缀规则，最终被当成公网地址放行。
         // InetAddress.getByName 对字面量不触发 DNS，解析结果仍是 Inet4Address，交由保留段判定收口。
         try {
            return !isReservedAddress(InetAddress.getByName(host));
         } catch (UnknownHostException | SecurityException notLiteral) {
            TraceLog.warn("IP 字面量解析失败，拒绝出站目标：{}", host);
            return false;
         }
      }
      InetAddress[] addresses;
      try {
         addresses = InetAddress.getAllByName(host);
      } catch (UnknownHostException unknown) {
         TraceLog.warn("DNS 解析失败，拒绝出站目标：{}", host);
         return false;
      } catch (SecurityException denied) {
         TraceLog.warn("DNS 解析被安全策略拒绝：{}", host);
         return false;
      }
      if (addresses == null || addresses.length == 0) {
         return false;
      }
      for (InetAddress address : addresses) {
         if (isReservedAddress(address)) {
            TraceLog.warn("DNS rebinding 防护：{} 解析到保留 / 内网段，拒绝", host);
            return false;
         }
      }
      return true;
   }

   /** 解析后的 InetAddress 保留段 / 内网 / 环回 / 组播判定。 */
   private static boolean isReservedAddress(InetAddress address) {
      if (address == null) {
         return true;
      }
      if (address.isLoopbackAddress() || address.isSiteLocalAddress()
            || address.isLinkLocalAddress() || address.isAnyLocalAddress()
            || address.isMulticastAddress()) {
         return true;
      }
      byte[] raw = address.getAddress();
      // IPv4: 0.0.0.0/8、10/8、172.16/12、192.168/16、100.64/10、192.0.0/24、
      // 169.254/16、198.18/15、198.51.100/24、203.0.113/24、224/4 组播、240/4 保留。
      if (raw.length == 4) {
         long a = raw[0] & 0xff;
         long b = raw[1] & 0xff;
         long c = raw[2] & 0xff;
         if (a == 0L || a == 10L || a == 127L) {
            return true;
         }
         if (a == 169L && b >= 254L) {
            return true;
         }
         if (a == 172L && b >= 16L && b <= 31L) {
            return true;
         }
         if (a == 192L && b == 168L) {
            return true;
         }
         if (a == 100L && b >= 64L && b <= 127L) {
            return true;
         }
         if (a == 192L && b == 0L && c == 0L) {
            return true;
         }
         if (a == 198L && (b == 18L || b == 19L)) {
            return true;
         }
         if (a == 198L && b == 51L && c == 100L) {
            return true;
         }
         if (a == 203L && b == 0L && c == 113L) {
            return true;
         }
         if (a >= 224L) {
            return true;
         }
         return false;
      }
      // IPv6：环回 ::1、链路本地 fe80::/10、唯一本地 fc/fd、组播 ff。
      String hostAddress = address.getHostAddress();
      if (hostAddress != null) {
         String lower = hostAddress.toLowerCase();
         if ("::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd")
               || lower.startsWith("fe80") || lower.startsWith("ff")) {
            return true;
         }
      }
      return false;
   }

   private static boolean isIpLiteral(String host) {
      return host.indexOf(':') >= 0 || host.split("\\.").length == 4;
   }

   /** 清空解析缓存（测试 / 配置热更时使用）。 */
   public static void clearCache() {
      CACHE.clear();
   }
}
