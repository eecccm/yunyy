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

package com.eecccm.yunyy.core.error;

/**
 * 带中性错误码的运行时异常。core 层抛出本类型，携带结构化参数；
 * 原始平台消息 / 堆栈通过 {@link #getCause()} 保留，仅写开发者日志，不整段上屏。
 */
public class VendorException extends RuntimeException {
   /** 版本号：本异常仅在同一进程内传递，序列化兼容性无需跨版本演进。 */
   private static final long serialVersionUID = 1L;

   private final VendorError code;
   /** 结构化参数：仅承载 String / Number / Enum 等不可变值，且不做跨进程序列化，故标记 transient。 */
   private final transient Object[] args;

   public VendorException(VendorError code, Object... args) {
      super((code == null ? VendorError.UNKNOWN : code).name());
      this.code = code == null ? VendorError.UNKNOWN : code;
      this.args = args == null ? new Object[0] : args;
   }

   public VendorException(VendorError code, Throwable cause, Object... args) {
      super((code == null ? VendorError.UNKNOWN : code).name(), cause);
      this.code = code == null ? VendorError.UNKNOWN : code;
      this.args = args == null ? new Object[0] : args;
   }

   public VendorError code() {
      return this.code;
   }

   public Object[] args() {
      return this.args;
   }
}
