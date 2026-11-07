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

import com.eecccm.yunyy.mccore.net.AuthPacket;
import com.eecccm.yunyy.mccore.net.ClearQueuePacket;
import com.eecccm.yunyy.mccore.net.HudPacket;
import com.eecccm.yunyy.mccore.net.ListeningActionPacket;
import com.eecccm.yunyy.mccore.net.ListeningStatePacket;
import com.eecccm.yunyy.mccore.net.MenuOpenPacket;
import com.eecccm.yunyy.mccore.net.NextSongPacket;
import com.eecccm.yunyy.mccore.net.PauseActionPacket;
import com.eecccm.yunyy.mccore.net.PauseStatePacket;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.net.PlaybackModePacket;
import com.eecccm.yunyy.mccore.net.PlaylistImportPacket;
import com.eecccm.yunyy.mccore.net.PlaylistOpenPacket;
import com.eecccm.yunyy.mccore.net.QueueActionPacket;
import com.eecccm.yunyy.mccore.net.QueuePacket;
import com.eecccm.yunyy.mccore.net.SearchOpenPacket;
import com.eecccm.yunyy.mccore.net.SelectSongPacket;
import com.eecccm.yunyy.mccore.net.StopPacket;
import com.eecccm.yunyy.mccore.net.VolumePacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;

/** payload 编解码查找表：forge/neoforge 客户端注册时需要显式 codec。 */
public final class PacketCodecs {
   private PacketCodecs() {
   }

   /**
    * 按 payload 类型取回对应的 StreamCodec。
    *
    * <p>返回类型必须与入参 Type&lt;T&gt; 的 T 精确绑定：NeoForge / Forge 的
    * playToClient / playToServer 形参为 {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}，
    * 若这里返回通配符类型，调用点的类型推断会把 T 推成捕获变量而报“找不到合适的方法”。
    */
   @SuppressWarnings("unchecked")
   public static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> codec(Type<T> type) {
      return (StreamCodec<RegistryFriendlyByteBuf, T>) lookup(type);
   }

   /** 实际查找表：类型擦除后由调用方 {@link #codec} 完成一次受检转换。 */
   private static StreamCodec<RegistryFriendlyByteBuf, ?> lookup(Type<? extends CustomPacketPayload> type) {
      if (type == PlayPacket.TYPE) {
         return PlayPacket.CODEC;
      } else if (type == StopPacket.TYPE) {
         return StopPacket.CODEC;
      } else if (type == VolumePacket.TYPE) {
         return VolumePacket.CODEC;
      } else if (type == AuthPacket.TYPE) {
         return AuthPacket.CODEC;
      } else if (type == HudPacket.TYPE) {
         return HudPacket.CODEC;
      } else if (type == QueuePacket.TYPE) {
         return QueuePacket.CODEC;
      } else if (type == PlaylistOpenPacket.TYPE) {
         return PlaylistOpenPacket.CODEC;
      } else if (type == MenuOpenPacket.TYPE) {
         return MenuOpenPacket.CODEC;
      } else if (type == SearchOpenPacket.TYPE) {
         return SearchOpenPacket.CODEC;
      } else if (type == ListeningStatePacket.TYPE) {
         return ListeningStatePacket.CODEC;
      } else if (type == PauseStatePacket.TYPE) {
         return PauseStatePacket.CODEC;
      } else if (type == PlaylistImportPacket.TYPE) {
         return PlaylistImportPacket.CODEC;
      } else if (type == SelectSongPacket.TYPE) {
         return SelectSongPacket.CODEC;
      } else if (type == NextSongPacket.TYPE) {
         return NextSongPacket.CODEC;
      } else if (type == ClearQueuePacket.TYPE) {
         return ClearQueuePacket.CODEC;
      } else if (type == QueueActionPacket.TYPE) {
         return QueueActionPacket.CODEC;
      } else if (type == PlaybackModePacket.TYPE) {
         return PlaybackModePacket.CODEC;
      } else if (type == ListeningActionPacket.TYPE) {
         return ListeningActionPacket.CODEC;
      } else if (type == PauseActionPacket.TYPE) {
         return PauseActionPacket.CODEC;
      } else {
         throw new IllegalArgumentException("未知 payload 类型: " + type);
      }
   }
}
