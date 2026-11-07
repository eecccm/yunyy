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

/**
 * 进程内存凭证最小化缓解（R-01）。
 *
 * <p>JVM 中 {@code String} 不可变且由 GC 管理，无法保证被即时覆写；本类提供可擦除的
 * {@code char[]} / {@code byte[]} 临时缓冲工具，用于：</p>
 * <ul>
 *   <li>导入 / 校验 / 持久化凭证的临时明文持有窗口尽量缩短，用完立即覆写；</li>
 *   <li>退出登录、导入失败、会话切换路径上主动擦除临时缓冲；</li>
 *   <li>不在静态字段长期持有明文 Cookie / token；错误路径只携带中性错误码，不把明文带入日志。</li>
 * </ul>
 *
 * <p>残余边界：已进入 {@code String} 池或静态字段的明文无法由本类强制擦除，
 * 该限制在安全报告残余风险中记录；本类只覆盖可控的临时缓冲生命周期。</p>
 */
public final class MemoryWipe {

   private MemoryWipe() {
   }

   /** 覆写字符数组全部元素为 0；null 安全。调用后调用方应把引用置为 null。 */
   public static void wipe(char[] chars) {
      if (chars == null) {
         return;
      }
      for (int i = 0; i < chars.length; i++) {
         chars[i] = '\0';
      }
   }

   /** 覆写字节数组全部元素为 0；null 安全。调用后调用方应把引用置为 null。 */
   public static void wipe(byte[] bytes) {
      if (bytes == null) {
         return;
      }
      for (int i = 0; i < bytes.length; i++) {
         bytes[i] = 0;
      }
   }

   /** 批量擦除字符数组（null 安全）。 */
   public static void wipeAll(char[]... arrays) {
      if (arrays == null) {
         return;
      }
      for (char[] array : arrays) {
         wipe(array);
      }
   }

   /** 批量擦除字节数组（null 安全）。 */
   public static void wipeAll(byte[]... arrays) {
      if (arrays == null) {
         return;
      }
      for (byte[] array : arrays) {
         wipe(array);
      }
   }

   /**
    * 把字符串转为可擦除字符数组；调用方用完必须 {@link #wipe(char[])}。
    * 仅用于需要临时持有明文凭证的短窗口，不要把返回值长期缓存。
    */
   public static char[] toChars(String plain) {
      return plain == null ? new char[0] : plain.toCharArray();
   }
}
