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

package com.eecccm.yunyy.core.config;

import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.path.StorageLocations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 音频桥代理选项（仅作用于音频 CDN 请求；账号凭证请求绝不走代理）。
 */
public final class AudioBridgeOptions {

   private static final Path FILE = StorageLocations.configDir().resolve("yunyy-audio.properties");

   private static volatile boolean on = false;
   private static volatile String host = "127.0.0.1";
   private static volatile int port = 7897;

   private AudioBridgeOptions() {
   }

   public static void load() {
      if (!Files.isRegularFile(FILE)) {
         return;
      }
      Properties p = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
         p.load(reader);
         on = Boolean.parseBoolean(p.getProperty("enabled", "false"));
         host = p.getProperty("host", "127.0.0.1").trim();
         port = Integer.parseInt(p.getProperty("port", "7897").trim());
         if (host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid proxy address");
         }
         if (on) {
            TraceLog.info("音频代理已启用：{}:{}（仅用于音频 CDN 请求）", host, port);
         }
      } catch (Exception e) {
         on = false;
         TraceLog.warn("无法读取音频代理配置，已使用直连", e);
      }
   }

   public static boolean on() {
      return on;
   }

   public static Proxy proxy() {
      return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
   }

   public static ProxySelector proxySelector() {
      return ProxySelector.of(InetSocketAddress.createUnresolved(host, port));
   }

   /** 首次运行写入默认配置（直连）。 */
   public static void ensureDefaults() {
      if (Files.exists(FILE)) {
         return;
      }
      Properties p = new Properties();
      p.setProperty("enabled", "false");
      p.setProperty("host", "127.0.0.1");
      p.setProperty("port", "7897");
      try {
         Files.createDirectories(FILE.getParent());
         try (BufferedWriter writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(writer, "yunyy audio bridge (audio CDN only; credentials never proxied)");
         }
      } catch (IOException e) {
         TraceLog.warn("无法创建音频代理默认配置", e);
      }
   }
}
