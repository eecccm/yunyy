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

package com.eecccm.yunyy.core.log;

import java.nio.charset.StandardCharsets;

/**
 * 全模组统一日志入口。
 *
 * <p>分层约定：core 层只负责级别过滤、占位符替换与消息组装，不直接接触 {@code System.out}、
 * {@code System.err} 或任何日志框架；成品消息交给由 MC 耦合层注册的 {@link LogSink}。
 * 运行时日志通道（SLF4J / Log4j2）负责时间戳、级别与 UTF-8 编码落盘，跨 Windows /
 * Linux / macOS 与 Fabric / Forge / NeoForge 行为一致，规避直写标准输出在不同平台
 * 控制台代码页下产生的中文乱码。</p>
 *
 * <p>API 风格仿照 slf4j 的 {@code {}} 占位符；可变参数末尾的 {@link Throwable} 会被识别为
 * 异常对象单独透传给 sink（由日志框架输出完整堆栈），其余参数参与占位符替换。未注册 sink
 * 时回退到 UTF-8 字节直写 {@code System.err} 的兜底实现，保证脱离游戏运行（独立测试）时
 * 日志不丢失。</p>
 */
public final class TraceLog {

   /** 当前输出阈值：INFO 及以上才下发到 sink；DEBUG 默认过滤。 */
   private static volatile int threshold = LogSink.INFO;

   private static volatile LogSink sink;

   private TraceLog() {
   }

   /** 注册运行时日志接收器（MC 入口启动最早阶段调用，幂等覆盖）。 */
   public static void bind(LogSink target) {
      sink = target;
   }

   /** 解绑接收器，恢复内置兜底通道。 */
   public static void unbind() {
      sink = null;
   }

   /** 兜底 sink：UTF-8 字节直写 {@code System.err}，不经过平台默认字符集转换。 */
   private static final LogSink FALLBACK = (level, message, thrown) -> {
      try {
         System.err.write((message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
         if (thrown != null) {
            thrown.printStackTrace(System.err);
         }
      } catch (Exception ignored) {
         // 兜底通道自身异常不得影响业务
      }
   };

   private static LogSink current() {
      LogSink s = sink;
      return s == null ? FALLBACK : s;
   }

   private static void emit(int level, String message, Throwable thrown) {
      if (level < threshold) {
         return;
      }
      try {
         // 统一兜底：组装后的消息在落盘前再过一遍凭证 / 指纹脱敏，防止调用点遗漏。
         current().emit(level, Redactor.message(message), thrown);
      } catch (Exception ignored) {
         // 日志通道异常不得抛回业务调用方
      }
   }

   public static void debug(String fmt, Object... args) {
      Throwable t = trailingThrowable(args);
      emit(LogSink.DEBUG, fill(fmt, bodyArgs(args, t)), t);
   }

   public static void debug(String msg, Throwable t) {
      emit(LogSink.DEBUG, msg, t);
   }

   public static void info(String fmt, Object... args) {
      Throwable t = trailingThrowable(args);
      emit(LogSink.INFO, fill(fmt, bodyArgs(args, t)), t);
   }

   public static void warn(String fmt, Object... args) {
      Throwable t = trailingThrowable(args);
      emit(LogSink.WARN, fill(fmt, bodyArgs(args, t)), t);
   }

   public static void warn(String msg, Throwable t) {
      emit(LogSink.WARN, msg, t);
   }

   public static void error(String fmt, Object... args) {
      Throwable t = trailingThrowable(args);
      emit(LogSink.ERROR, fill(fmt, bodyArgs(args, t)), t);
   }

   public static void error(String msg, Throwable t) {
      emit(LogSink.ERROR, msg, t);
   }

   /** 识别可变参数末尾的异常对象，用于单独透传完整堆栈。 */
   private static Throwable trailingThrowable(Object[] args) {
      if (args == null || args.length == 0) {
         return null;
      }
      Object last = args[args.length - 1];
      return last instanceof Throwable ? (Throwable) last : null;
   }

   /** 去掉末尾异常对象后剩余的占位符参数。 */
   private static Object[] bodyArgs(Object[] args, Throwable thrown) {
      if (thrown == null) {
         return args == null ? new Object[0] : args;
      }
      if (args.length == 1) {
         return new Object[0];
      }
      Object[] rest = new Object[args.length - 1];
      System.arraycopy(args, 0, rest, 0, rest.length);
      return rest;
   }

   /** 顺序替换模板中的 {} 占位符；参数耗尽后保留剩余模板原文。 */
   private static String fill(String fmt, Object... args) {
      if (fmt == null) {
         return "null";
      }
      if (args == null || args.length == 0) {
         return fmt;
      }
      StringBuilder sb = new StringBuilder(fmt.length() + 32);
      int cursor = 0;
      for (Object arg : args) {
         int at = fmt.indexOf("{}", cursor);
         if (at < 0) {
            sb.append(fmt, cursor, fmt.length());
            return sb.toString();
         }
         sb.append(fmt, cursor, at).append(arg == null ? "null" : String.valueOf(arg));
         cursor = at + 2;
      }
      sb.append(fmt, cursor, fmt.length());
      return sb.toString();
   }
}
