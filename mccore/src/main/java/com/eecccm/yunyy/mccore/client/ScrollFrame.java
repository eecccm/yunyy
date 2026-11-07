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

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

abstract class ScrollFrame extends Screen {
   protected final ScrollPane scrollPanel = new ScrollPane();

   protected ScrollFrame(Component title) {
      super(title);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
      if (this.scrollPanel.mouseScrolled(mouseX, mouseY, deltaY)) {
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
      if (this.scrollPanel.onMouseDown(event.x(), event.y())) {
         return true;
      }
      return super.mouseClicked(this.shift(event), doubleClicked);
   }

   public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
      if (this.scrollPanel.onMouseDrag(event.y())) {
         return true;
      }
      return super.mouseDragged(this.shift(event), deltaX, deltaY);
   }

   public boolean mouseReleased(MouseButtonEvent event) {
      this.scrollPanel.onMouseUp();
      return super.mouseReleased(this.shift(event));
   }

   private MouseButtonEvent shift(MouseButtonEvent event) {
      return new MouseButtonEvent(event.x(), event.y() + (double)this.scrollPanel.scrollY(), event.buttonInfo());
   }

   protected void renderScrolled(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      graphics.pose().pushMatrix();
      graphics.pose().translate(0.0F, (float)(-this.scrollPanel.scrollY()));
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.pose().popMatrix();
      this.scrollPanel.render(graphics, mouseX, mouseY);
   }
}
