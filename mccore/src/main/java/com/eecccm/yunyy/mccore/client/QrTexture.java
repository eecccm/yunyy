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
import com.eecccm.yunyy.mccore.util.PacketIds;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class QrTexture {
   private static final int SIZE = 512;
   private static final Identifier TEXTURE = PacketIds.id("qr_login");
   private static boolean ready;

   private QrTexture() {
   }

   public static Identifier texture() {
      return ready ? TEXTURE : null;
   }

   public static void show(String content) {
      try {
         NativeImage image;
         if (content != null && content.startsWith("data:image")) {
            // 服务端直接返回二维码图片（如 QQ/酷狗 ptqrshow），解码 base64 显示
            String b64 = content.substring(content.indexOf(',') + 1).trim();
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            image = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
         } else {
            BitMatrix matrix = new QRCodeWriter()
               .encode(
                  content,
                  BarcodeFormat.QR_CODE,
                  SIZE,
                  SIZE,
                  Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 2)
               );
            image = new NativeImage(SIZE, SIZE, false);

            for (int y = 0; y < SIZE; y++) {
               for (int x = 0; x < SIZE; x++) {
                  image.setPixel(x, y, matrix.get(x, y) ? -16777216 : -1);
               }
            }
         }

         Minecraft client = Minecraft.getInstance();
         client.getTextureManager().release(TEXTURE);
         client.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "yunyy qr login", image));
         ready = true;
      } catch (Exception var5) {
         clear();
         throw new IllegalStateException("生成登录二维码失败", var5);
      }
   }

   public static void clear() {
      ready = false;
      Minecraft.getInstance().getTextureManager().release(TEXTURE);
   }
}
