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

package com.eecccm.yunyy.mccore.util;

import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.Redactor;
import com.eecccm.yunyy.core.model.AccountProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 错误码到本地化文案的映射入口（显示层唯一上屏出口）。
 *
 * <p>core 层只携带中性 {@link VendorError} 与结构化参数；玩家可见文案在此按当前界面语言
 * 渲染为 {@code yunyy.error.*} 模板。原始平台消息 / 堆栈只写开发者日志，不整段上屏。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link VendorException} 链内可被解包；找不到错误码时回退 {@link VendorError#UNKNOWN}。</li>
 *   <li>参数槽以 {@link VendorError} 枚举注释为准；模板无占位符时多余参数自动忽略。</li>
 *   <li>底层非 Vendor 异常（网络、解码、IO 等）原始描述仅记日志，界面只显示未知错误。</li>
 * </ul>
 */
public final class ErrorMessages {

   private ErrorMessages() {
   }

   // ---------------- Component（可嵌入聊天 / 界面模板） ----------------

   /** 异常对象上屏：解包错误码映射语言模板；无法识别时回退未知错误并把原始堆栈写日志。 */
   public static MutableComponent component(Throwable throwable) {
      VendorException ve = unwrapVendor(throwable);
      if (ve != null) {
         return component(ve.code(), ve.args());
      }
      TraceLog.warn("Unrecognized error surfaced to UI: {}", describeUnknown(throwable));
      return Component.translatable(VendorError.UNKNOWN.key());
   }

   /** 错误码异常上屏。 */
   public static MutableComponent component(VendorException ve) {
      if (ve == null) {
         return Component.translatable(VendorError.UNKNOWN.key());
      }
      return component(ve.code(), ve.args());
   }

   /** 错误码 + 结构化参数上屏。 */
   public static MutableComponent component(VendorError code, Object... args) {
      VendorError safe = code == null ? VendorError.UNKNOWN : code;
      Object[] safeArgs = args == null ? new Object[0] : args;
      return Component.translatable(safe.key(), safeArgs);
   }

   /** 失败账号档案上屏：读取档案错误码；成功态或无错误码时回退未知错误。 */
   public static MutableComponent component(AccountProfile profile) {
      if (profile != null && profile.hasError()) {
         return component(profile.errorCode(), profile.errorArgs());
      }
      TraceLog.warn("AccountProfile without error code surfaced to UI: active={}",
            profile == null ? "null" : profile.active());
      return Component.translatable(VendorError.UNKNOWN.key());
   }

   // ---------------- 纯文本（需要 getString() 的界面拼接场景） ----------------

   /** 异常对象本地化为纯文本。 */
   public static String text(Throwable throwable) {
      return component(throwable).getString();
   }

   /** 错误码 + 参数本地化为纯文本。 */
   public static String text(VendorError code, Object... args) {
      return component(code, args).getString();
   }

   /** 失败账号档案本地化为纯文本。 */
   public static String text(AccountProfile profile) {
      return component(profile).getString();
   }

   // ---------------- 内部实现 ----------------

   /** 沿 cause 链查找最外层 {@link VendorException}。 */
   private static VendorException unwrapVendor(Throwable throwable) {
      Throwable current = throwable;
      while (current != null) {
         if (current instanceof VendorException ve) {
            return ve;
         }
         current = current.getCause();
      }
      return null;
   }

   /** 提取根因类名与脱敏消息，截断后只写日志，不携带回上屏组件。 */
   private static String describeUnknown(Throwable throwable) {
      return Redactor.throwableMessage(throwable);
   }
}
