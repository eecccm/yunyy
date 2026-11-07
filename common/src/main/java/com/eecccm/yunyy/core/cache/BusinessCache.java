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

package com.eecccm.yunyy.core.cache;

import com.eecccm.yunyy.core.platform.MusicVendor;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台业务结果分组 TTL 内存缓存（core 纯 JDK，无 MC 依赖）。
 *
 * <p>只缓存公开业务结果：搜索歌曲条目、歌单元信息、歌单歌曲、账号昵称与 VIP 标识；
 * 流地址由各网关按自身时效单独管理，不进入本缓存。本缓存不保存 Cookie、token、userid、
 * dfid、mid、MAC、GUID、webglHash、device_token 等凭证字段；失败结果、风控结果、
 * 空列表、账号失败档案一律不入缓存，避免缓存错误掩盖登录态与风控变化。</p>
 *
 * <p>分组语义对齐上游音乐平台客户端的分层缓存思路：搜索结果短 TTL、歌单类中等 TTL、
 * 账号档案最短 TTL；登出或切换账号时按平台整组清除，不跨平台污染。容量超限按分组整体
 * 清空，保持实现简单、不引入 LRU 第三方依赖。</p>
 */
public final class BusinessCache {

   /** 缓存分组：TTL（毫秒）与单组容量上限。 */
   public enum Group {
      /** 搜索结果：{@code vendor:search:<normalizedKeyword>:<page>}，5 分钟 TTL。 */
      SEARCH(5L * 60_000L, 64),
      /** 我的歌单列表：{@code vendor:playlists}，10 分钟 TTL。 */
      PLAYLISTS(10L * 60_000L, 8),
      /** 指定歌单歌曲：{@code vendor:songs:<folderId>}，10 分钟 TTL。 */
      PLAYLIST_SONGS(10L * 60_000L, 32),
      /** 账号档案（仅成功态）：{@code vendor:profile}，2 分钟 TTL。 */
      PROFILE(2L * 60_000L, 4);

      final long ttlMillis;
      final int capacity;

      Group(long ttlMillis, int capacity) {
         this.ttlMillis = ttlMillis;
         this.capacity = capacity;
      }
   }

   private record Entry<T>(T value, long expiresAtMillis) {
   }

   private static final Map<Group, ConcurrentHashMap<String, Entry<?>>> STORES = new EnumMap<>(Group.class);

   static {
      for (Group group : Group.values()) {
         STORES.put(group, new ConcurrentHashMap<>());
      }
   }

   private BusinessCache() {
   }

   /** 读取未过期条目；过期条目惰性剔除；未命中返回 null。 */
   @SuppressWarnings("unchecked")
   public static <T> T get(Group group, String key, Class<T> type) {
      Entry<?> entry = STORES.get(group).get(key);
      if (entry == null) {
         return null;
      }
      if (System.currentTimeMillis() >= entry.expiresAtMillis()) {
         STORES.get(group).remove(key);
         return null;
      }
      Object value = entry.value();
      return value == null ? null : (T) value;
   }

   /** 写入成功结果；null 与空集合由调用方判定后再决定是否写入；容量超限整组清空。 */
   public static <T> void put(Group group, String key, T value) {
      if (value == null) {
         return;
      }
      ConcurrentHashMap<String, Entry<?>> store = STORES.get(group);
      if (store.size() >= group.capacity) {
         store.clear();
      }
      store.put(key, new Entry<>(value, System.currentTimeMillis() + group.ttlMillis));
   }

   /** 清除指定平台在全部分组中的缓存（退出登录时调用）。 */
   public static void clearVendor(MusicVendor vendor) {
      String prefix = vendor.tag + ":";
      for (ConcurrentHashMap<String, Entry<?>> store : STORES.values()) {
         store.keySet().removeIf(key -> key.startsWith(prefix));
      }
   }

   /** 清除指定平台指定分组（导入新凭证 / 密码登录成功后，清旧歌单与旧档案）。 */
   public static void clearGroup(Group group, MusicVendor vendor) {
      String prefix = vendor.tag + ":";
      STORES.get(group).keySet().removeIf(key -> key.startsWith(prefix));
   }
}
