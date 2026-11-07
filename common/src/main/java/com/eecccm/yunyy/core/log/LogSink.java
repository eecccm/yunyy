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

/**
 * 日志输出接收器（端口）。
 *
 * <p>core 层保持纯 JDK、不依赖任何 MC 或日志框架；具体由 MC 耦合层（mccore）实现，
 * 把 core 产生的日志接入运行时日志体系（SLF4J / Log4j2），从而复用游戏统一的
 * 时间戳、级别着色与 UTF-8 落盘编码，避免模组直写 {@code System.out} 时在不同平台
 * （Windows 控制台代码页、启动器捕获编码、各加载器接管流）下出现中文乱码。</p>
 */
public interface LogSink {

   /** 调试级别（默认被阈值过滤，不输出）。 */
   int DEBUG = 1;
   /** 信息级别。 */
   int INFO = 2;
   /** 警告级别。 */
   int WARN = 3;
   /** 错误级别。 */
   int ERROR = 4;

   /**
    * 输出一条日志。
    *
    * @param level   {@link #DEBUG} / {@link #INFO} / {@link #WARN} / {@link #ERROR}
    * @param message 已完成占位符替换、不含时间戳与级别前缀的纯消息文本
    * @param thrown  关联异常，可为 {@code null}；实现方应将其作为日志框架的异常参数传入，
    *                由框架负责输出完整堆栈
    */
   void emit(int level, String message, Throwable thrown);
}
