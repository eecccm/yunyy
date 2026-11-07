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

package com.eecccm.yunyy.mccore.log;

import com.eecccm.yunyy.core.log.LogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 SLF4J 的日志接收器。
 *
 * <p>由 MC 耦合层在模组初始化最早阶段注册到 {@link com.eecccm.yunyy.core.log.TraceLog}，
 * 使 core 层日志进入游戏统一的 SLF4J / Log4j2 体系：时间戳、线程名、级别与着色由日志
 * 框架 pattern 负责，文件落盘与控制台输出均按框架配置的 UTF-8 处理，跨平台不再出现
 * 直写 {@code System.out} 导致的中文乱码。异常对象作为 SLF4J 的末位 Throwable 参数传入，
 * 由框架输出完整堆栈。</p>
 */
public final class Slf4jTraceSink implements LogSink {

   private final Logger logger = LoggerFactory.getLogger("yunyy");

   @Override
   public void emit(int level, String message, Throwable thrown) {
      switch (level) {
         case DEBUG:
            logger.debug(message, thrown);
            break;
         case INFO:
            logger.info(message, thrown);
            break;
         case WARN:
            logger.warn(message, thrown);
            break;
         case ERROR:
            logger.error(message, thrown);
            break;
         default:
            logger.info(message, thrown);
      }
   }
}
