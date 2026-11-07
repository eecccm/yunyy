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

package com.eecccm.yunyy.fabric;

import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.config.RuntimeRegistry;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.path.StorageLocations;
import com.eecccm.yunyy.mccore.log.Slf4jTraceSink;
import com.eecccm.yunyy.mccore.server.ServerCore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 加载器入口：接入命令注册、生命周期事件、网络通道与共享核心。
 *
 * <p>此入口（{@code main}）在客户端与专用服务器上都会执行，因此**不得**引用任何客户端类型：
 * 客户端网络实现由 {@code YunyyModClient}（仅客户端 entrypoint）注入
 * {@link com.eecccm.yunyy.mccore.client.ClientNet}。</p>
 */
public final class YunyyMod implements ModInitializer {
   public static final String MOD_ID = "yunyy";

   public void onInitialize() {
      TraceLog.bind(new Slf4jTraceSink());
      StorageLocations.setConfigDir(FabricLoader.getInstance().getConfigDir());
      RuntimeRegistry.metaResolver = ServerCore::currentSongEntryMeta;
      ServerPolicy.load();
      ServerCore.net = FabricServerNet.INSTANCE;
      FabricServerNet.initialize();
      ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerCore.reset());
      CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(ServerCore.commandTree()));
      ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerCore.onPlayerJoin(server, handler.getPlayer()));
      ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerCore.onPlayerLeave(server, handler.getPlayer()));
      ServerTickEvents.END_SERVER_TICK.register(server -> ServerCore.onTick(server));
   }
}
