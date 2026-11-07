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

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;

/**
 * Fabric 侧 play 阶段 payload 类型注册。
 *
 * <p>直连 Fabric API 的公开静态入口：{@code PayloadTypeRegistry.clientboundPlay()} /
 * {@code serverboundPlay()} 在本工程依赖的 fabric-networking-api-v1 中签名稳定，
 * 类型参数 {@code RegistryFriendlyByteBuf} 与工程内全部 payload 的 {@code CODEC} 一致。</p>
 *
 * <p>旧实现按方法名反射探测注册器（{@code clientboundPlay} / {@code playS2C} 两个候选），
 * 一旦 API 改名就会退到 {@code NoSuchMethodException} 再抛 {@code IllegalStateException}，
 * 把编译期即可发现的签名不匹配推迟到运行期；同时反射调用绕过了泛型校验，
 * 参数传错也不会在编译时报错。</p>
 */
final class ChannelRegistrar {
   private ChannelRegistrar() {
   }

   static <T extends CustomPacketPayload> void registerClientbound(
         Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
      PayloadTypeRegistry.clientboundPlay().register(type, codec);
   }

   static <T extends CustomPacketPayload> void registerServerbound(
         Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
      PayloadTypeRegistry.serverboundPlay().register(type, codec);
   }
}
