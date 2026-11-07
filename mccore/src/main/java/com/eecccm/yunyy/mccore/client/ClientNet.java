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

package com.eecccm.yunyy.mccore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;

/** 客户端网络抽象：各加载器在客户端初始化时注入自己的实现。 */
public final class ClientNet {
   private static volatile Impl impl;

   /**
    * 未注入时的兜底实现：注册与发送均为空操作，能力查询返回 false。
    *
    * <p>客户端 entrypoint 与界面代码的初始化顺序由加载器决定，界面在极早期被打开
    * （例如模组菜单预览）时 {@code impl} 可能尚未注入。此处兜底可避免整块 UI 直接 NPE，
    * 同时由首次访问打一条警告留下证据。</p>
    */
   private static final Impl UNBOUND = new Impl() {
      private boolean warned;

      @Override
      public <T extends CustomPacketPayload> void register(Type<T> type, Handler<T> handler) {
         warnOnce();
      }

      @Override
      public void send(Object payload) {
         warnOnce();
      }

      @Override
      public boolean supportsListeningModes() {
         return false;
      }

      private synchronized void warnOnce() {
         if (!warned) {
            warned = true;
            com.eecccm.yunyy.core.log.TraceLog.warn("客户端网络尚未初始化，本次下行注册 / 上行发送被忽略");
         }
      }
   };

   private ClientNet() {
   }

   public static Impl get() {
      Impl value = impl;
      return value == null ? UNBOUND : value;
   }

   public static void set(Impl value) {
      impl = value;
   }

   public interface Impl {
      <T extends CustomPacketPayload> void register(Type<T> type, Handler<T> handler);

      void send(Object payload);

      boolean supportsListeningModes();
   }

   @FunctionalInterface
   public interface Handler<T> {
      /**
       * 处理一条下行数据包。
       *
       * @param payload 已解码的 payload 实例
       * @param client  当前客户端实例，由加载器在接收回调中提供
       */
      void receive(T payload, Minecraft client);
   }
}
