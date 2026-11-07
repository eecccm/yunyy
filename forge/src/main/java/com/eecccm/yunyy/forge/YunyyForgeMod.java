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

package com.eecccm.yunyy.forge;

import com.eecccm.yunyy.core.config.RuntimeRegistry;
import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.mccore.log.Slf4jTraceSink;
import com.eecccm.yunyy.mccore.server.ServerCore;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge 加载器入口：依赖注入与事件总线接线。 */
@Mod(YunyyForgeMod.MOD_ID)
public final class YunyyForgeMod {
   public static final String MOD_ID = "yunyy";

   public YunyyForgeMod() {
      TraceLog.bind(new Slf4jTraceSink());
      com.eecccm.yunyy.core.path.StorageLocations.setConfigDir(FMLPaths.CONFIGDIR.get());
      RuntimeRegistry.metaResolver = ServerCore::currentSongEntryMeta;
      ServerPolicy.load();
      ServerCore.net = ServerNetwork.INSTANCE;
      // 26.2 的通道在构建期绑定全部 payload，必须早于任何网络收发建立
      ServerNetwork.channel();
      ServerNetwork.registerGameEvents();
      if (FMLEnvironment.dist == Dist.CLIENT) {
         // 仅在客户端分支内触碰客户端类，避免专用服务器加载 net.minecraft.client
         com.eecccm.yunyy.forge.client.YunyyForgeClient.init();
      }
   }
}
