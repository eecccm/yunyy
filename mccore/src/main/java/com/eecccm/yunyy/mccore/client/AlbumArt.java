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
import com.eecccm.yunyy.core.config.AudioBridgeOptions;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.net.SafeHttpClient;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class AlbumArt {
   private static final Identifier TEXTURE = PacketIds.id("current_cover");
   private static volatile String requestedUrl = "";
   private static volatile boolean ready;
   /**
    * 封面下载客户端缓存。
    *
    * <p>{@link HttpClient} 自带连接池与 selector 线程，逐首歌新建会把线程与文件描述符
    * 堆到 GC 才回收；封面属于每首歌一次的调用，必须复用。缓存键是「当前是否挂代理」，
    * 代理开关变化时重建一次。</p>
    */
   private static volatile HttpClient clientCache;
   private static volatile boolean clientCacheProxied;

   private AlbumArt() {
   }

   /** 取封面下载客户端：按当前代理开关复用，配置切换时重建。 */
   private static HttpClient httpClient() {
      boolean proxied = AudioBridgeOptions.on();
      HttpClient cached = clientCache;
      if (cached != null && clientCacheProxied == proxied) {
         return cached;
      }
      HttpClient.Builder builder = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(8L))
         .followRedirects(HttpClient.Redirect.NEVER);
      // 封面无凭证，代理用户场景下封面可经代理可达；重定向目标逐跳复核 scheme 与白名单 host。
      if (proxied) {
         builder.proxy(AudioBridgeOptions.proxySelector());
      }
      cached = builder.build();
      clientCacheProxied = proxied;
      clientCache = cached;
      return cached;
   }

   public static Identifier texture() {
      return ready ? TEXTURE : null;
   }

   public static void load(String rawUrl, String provider, String title, String artist) {
      clear();
      String label = songLabel(provider, title, artist);
      if (rawUrl != null && !rawUrl.isBlank()) {
         URI uri;
         try {
            uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !trustedHost(uri.getHost())) {
               TraceLog.warn("拒绝加载不受信任的歌曲封面：host={}，{}", uri.getHost(), label);
               return;
            }
         } catch (Exception var3) {
            TraceLog.warn("歌曲封面地址格式无效，{}", label);
            return;
         }

         URI downloadUri = thumbnailUri(uri);
         requestedUrl = rawUrl;
         CompletableFuture.runAsync(() -> download(downloadUri, rawUrl, label));
      } else {
         // 每首歌都应有封面；封面地址缺失属于异常（取数/解析/链路丢字段），保留 WARN 便于定位具体歌曲
         TraceLog.warn("当前歌曲没有可用的封面地址：{}", label);
      }
   }

   private static String songLabel(String provider, String title, String artist) {
      String p = provider == null || provider.isBlank() ? "?" : provider;
      String t = title == null || title.isBlank() ? "?" : title;
      String a = artist == null || artist.isBlank() ? "?" : artist;
      return "平台=" + p + "，歌曲《" + t + "》-" + a;
   }

   public static void clear() {
      requestedUrl = "";
      ready = false;
      Minecraft client = Minecraft.getInstance();
      client.execute(() -> {
         if (requestedUrl.isEmpty()) {
            client.getTextureManager().release(TEXTURE);
         }
      });
   }

   private static void download(URI uri, String expectedUrl, String label) {
      try {
         HttpClient client = httpClient();

         HttpResponse<byte[]> response = null;
         URI current = uri;
         for (int hop = 0; hop < 3; hop++) {
            HttpRequest request = HttpRequest.newBuilder(current)
               .timeout(Duration.ofSeconds(12L))
               .header("User-Agent", "Mozilla/5.0 Yunyy/1.0.0")
               .header("Referer", "https://music.163.com/")
               .GET()
               .build();
            response = client.send(request, BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
               String loc = response.headers().firstValue("Location").orElse(null);
               if (loc == null || loc.isBlank()) {
                  TraceLog.warn("封面重定向缺少 Location，{}", label);
                  return;
               }
               URI next;
               try {
                  next = current.resolve(loc);
               } catch (Exception invalid) {
                  TraceLog.warn("封面重定向地址格式无效，{}", label);
                  return;
               }
               if (!"https".equalsIgnoreCase(next.getScheme()) || !trustedHost(next.getHost())) {
                  TraceLog.warn("拒绝封面重定向到不受信任目标：host={}，{}", next.getHost(), label);
                  return;
               }
               current = next;
               continue;
            }
            break;
         }
         int status = response == null ? 0 : response.statusCode();
         int size = response == null ? 0 : response.body().length;
         if (status != 200 || size == 0 || size > 5242880) {
            TraceLog.warn("歌曲封面下载失败：HTTP {}，大小 {} 字节，{}", status, size, label);
            return;
         }

         NativeImage image = decodeAndScale(response.body());
         if (image.getWidth() > 4096 || image.getHeight() > 4096) {
            image.close();
            return;
         }

         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (!expectedUrl.equals(requestedUrl)) {
               image.close();
            } else {
               mc.getTextureManager().release(TEXTURE);
               mc.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "yunyy current cover", image));
               ready = true;
            }
         });
      } catch (Exception var7) {
         TraceLog.warn("无法加载歌曲封面：{}", label, var7);
      }
   }

   private static NativeImage decodeAndScale(byte[] bytes) throws Exception {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
      if (source == null) {
         throw new IllegalArgumentException("不支持的封面图片格式");
      } else if (source.getWidth() <= 4096 && source.getHeight() <= 4096) {
         int side = Math.min(source.getWidth(), source.getHeight());
         int sourceX = (source.getWidth() - side) / 2;
         int sourceY = (source.getHeight() - side) / 2;
         BufferedImage scaled = new BufferedImage(64, 64, 2);
         Graphics2D graphics = scaled.createGraphics();

         try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, 64, 64, sourceX, sourceY, sourceX + side, sourceY + side, null);
         } finally {
            graphics.dispose();
         }

         NativeImage result = new NativeImage(64, 64, false);

         for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
               result.setPixel(x, y, scaled.getRGB(x, y));
            }
         }

         scaled.flush();
         source.flush();
         return result;
      } else {
         throw new IllegalArgumentException("封面图片尺寸过大");
      }
   }

   private static boolean trustedHost(String host) {
      if (host == null || SafeHttpClient.isReservedPrivateHost(host)) {
         return false;
      } else {
         String lower = host.toLowerCase();
         return lower.equals("music.126.net")
            || lower.endsWith(".music.126.net")
            || lower.equals("music.163.com")
            || lower.endsWith(".music.163.com")
            || lower.equals("y.gtimg.cn")
            || lower.endsWith(".y.gtimg.cn")
            || lower.endsWith(".gtimg.cn")
            || lower.equals("imge.kugou.com")
            || lower.endsWith(".kugou.com")
            || lower.endsWith(".kqcdn.com")
            || lower.endsWith(".qq.com");
      }
   }

   private static URI thumbnailUri(URI uri) {
      String query = uri.getQuery();
      String thumbnailQuery = query != null && !query.isBlank() ? query + "&param=128y128" : "param=128y128";

      try {
         return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), thumbnailQuery, null);
      } catch (Exception var4) {
         return uri;
      }
   }
}
