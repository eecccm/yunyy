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

public final class ScrollPane {
   private int x0;
   private int y0;
   private int x1;
   private int y1;
   private int contentHeight;
   private int scrollY;
   private boolean dragging;
   private int trackX;
   private static final int TRACK_W = 6;

   public ScrollPane() {
   }

   void setViewport(int x0, int y0, int x1, int y1) {
      this.x0 = x0;
      this.y0 = y0;
      this.x1 = x1;
      this.y1 = y1;
   }

   void setContentHeight(int height) {
      this.contentHeight = Math.max(0, height);
      if (this.scrollY > this.maxScroll()) {
         this.scrollY = this.maxScroll();
      }
   }

   void setSongEntryX(int x) {
      this.trackX = x;
   }

   void reset() {
      this.scrollY = 0;
      this.dragging = false;
   }

   int maxScroll() {
      return Math.max(0, this.contentHeight - (this.y1 - this.y0));
   }

   boolean enabled() {
      return this.maxScroll() > 0;
   }

   int scrollY() {
      return this.scrollY;
   }

   void setScrollY(int v) {
      this.scrollY = Math.max(0, Math.min(v, this.maxScroll()));
   }

   boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
      if (!this.enabled()) {
         return false;
      }
      if (mouseX >= (double)this.x0 && mouseX <= (double)this.x1 && mouseY >= (double)this.y0 && mouseY <= (double)this.y1) {
         this.setScrollY(this.scrollY - (int)Math.round(deltaY * 16.0));
         return true;
      }
      return false;
   }

   private boolean overSongEntry(double mouseX, double mouseY) {
      return this.enabled() && mouseX >= (double)this.trackX && mouseX <= (double)(this.trackX + TRACK_W) && mouseY >= (double)this.y0 && mouseY <= (double)this.y1;
   }

   boolean onMouseDown(double mouseX, double mouseY) {
      if (this.overSongEntry(mouseX, mouseY)) {
         this.dragging = true;
         this.updateFromMouse(mouseY);
         return true;
      }
      return false;
   }

   boolean onMouseDrag(double mouseY) {
      if (this.dragging) {
         this.updateFromMouse(mouseY);
         return true;
      }
      return false;
   }

   void onMouseUp() {
      this.dragging = false;
   }

   private void updateFromMouse(double mouseY) {
      int trackH = this.y1 - this.y0;
      int barH = this.barHeight();
      double t = (mouseY - (double)this.y0 - (double)barH / 2.0) / (double)Math.max(1, trackH - barH);
      this.setScrollY((int)Math.round(t * (double)this.maxScroll()));
   }

   private int barHeight() {
      int trackH = this.y1 - this.y0;
      return Math.max(18, trackH * trackH / Math.max(1, this.contentHeight));
   }

   void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      if (!this.enabled()) {
         return;
      }
      int trackH = this.y1 - this.y0;
      int barH = this.barHeight();
      int barY = this.y0 + (int)Math.round((double)(trackH - barH) * (double)this.scrollY / (double)this.maxScroll());
      graphics.fill(this.trackX, this.y0, this.trackX + TRACK_W, this.y1, -1598066786);
      int color = this.overSongEntry(mouseX, mouseY) || this.dragging ? -3355444 : -1549556728;
      graphics.fill(this.trackX, barY, this.trackX + TRACK_W, barY + barH, color);
   }
}
