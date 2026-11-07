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

package com.eecccm.yunyy.mccore.net;
import com.eecccm.yunyy.mccore.util.PacketIds;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;

public record PlayPacket(
   String songId, String title, String artist, String audioUrl, String coverUrl, long startAtMillis, long serverSentAtMillis, long durationMillis, String provider
) implements CustomPacketPayload {
   public static final Type<PlayPacket> TYPE = new Type<>(PacketIds.id("play"));
   public static final StreamCodec<RegistryFriendlyByteBuf, PlayPacket> CODEC = StreamCodec.ofMember(PlayPacket::encode, PlayPacket::decode);

   public PlayPacket(
      String songId, String title, String artist, String audioUrl, String coverUrl, long startAtMillis, long serverSentAtMillis, long durationMillis
   ) {
      this(songId, title, artist, audioUrl, coverUrl, startAtMillis, serverSentAtMillis, durationMillis, "netease");
   }

   private void encode(RegistryFriendlyByteBuf buffer) {
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.songId);
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.title);
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.artist);
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.audioUrl);
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.coverUrl);
      ByteBufCodecs.VAR_LONG.encode(buffer, this.startAtMillis);
      ByteBufCodecs.VAR_LONG.encode(buffer, this.serverSentAtMillis);
      ByteBufCodecs.VAR_LONG.encode(buffer, this.durationMillis);
      ByteBufCodecs.STRING_UTF8.encode(buffer, this.provider);
   }

   private static PlayPacket decode(RegistryFriendlyByteBuf buffer) {
      return new PlayPacket(
         ByteBufCodecs.STRING_UTF8.decode(buffer),
         ByteBufCodecs.STRING_UTF8.decode(buffer),
         ByteBufCodecs.STRING_UTF8.decode(buffer),
         ByteBufCodecs.STRING_UTF8.decode(buffer),
         ByteBufCodecs.STRING_UTF8.decode(buffer),
         ByteBufCodecs.VAR_LONG.decode(buffer),
         ByteBufCodecs.VAR_LONG.decode(buffer),
         ByteBufCodecs.VAR_LONG.decode(buffer),
         ByteBufCodecs.STRING_UTF8.decode(buffer)
      );
   }

   public PlayPacket sentAt(long millis) {
      return new PlayPacket(this.songId, this.title, this.artist, this.audioUrl, this.coverUrl, this.startAtMillis, millis, this.durationMillis, this.provider);
   }

   public PlayPacket shiftedBy(long millis) {
      return new PlayPacket(
         this.songId,
         this.title,
         this.artist,
         this.audioUrl,
         this.coverUrl,
         this.startAtMillis + Math.max(0L, millis),
         this.serverSentAtMillis,
         this.durationMillis,
         this.provider
      );
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
