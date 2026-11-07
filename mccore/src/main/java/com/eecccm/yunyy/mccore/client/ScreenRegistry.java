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
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.TraceLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ScreenRegistry {
   private ScreenRegistry() {
   }

   public static Screen current(Minecraft client) {
      try {
         Field field = Minecraft.class.getField("screen");
         return (Screen)field.get(client);
      } catch (ReflectiveOperationException var4) {
         try {
            Object gui = Minecraft.class.getField("gui").get(client);
            return (Screen)gui.getClass().getMethod("screen").invoke(gui);
         } catch (ReflectiveOperationException var3) {
            TraceLog.warn("无法读取当前游戏界面", var3);
            return null;
         }
      }
   }

   public static void set(Minecraft client, Screen screen) {
      try {
         Method method = Minecraft.class.getMethod("setScreen", Screen.class);
         method.invoke(client, screen);
      } catch (ReflectiveOperationException var5) {
         try {
            Object gui = Minecraft.class.getField("gui").get(client);
            gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, screen);
         } catch (ReflectiveOperationException var4) {
            TraceLog.error("无法打开云音乐界面", var4);
         }
      }
   }
}
