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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ModeSelectScreen extends ScrollFrame {
   private final Screen parent;
   private static ModeSelectScreen active;

   public ModeSelectScreen(Screen parent) {
      super(Component.translatable("yunyy.listening.title"));
      this.parent = parent;
   }

   protected void init() {
      active = this;
      int panelWidth = Math.min(360, this.width - 24);
      int x = (this.width - panelWidth) / 2;
      int y = this.height / 2 - 48;
      int contentBottom = y + 122;
      this.scrollPanel.setViewport(x - 4, y - 20, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 20));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      Button publicMode = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.listening.public"), b -> this.action(0)).bounds(x, y, panelWidth, 20).build()
      );
      publicMode.active = ClientNet.get().supportsListeningModes() && SyncSnapshot.mode() != 0;
      Button personal = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.listening.personal"), b -> this.action(1)).bounds(x, y + 48, panelWidth, 20).build()
      );
      personal.active = SyncSnapshot.mode() != 1;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.listening.back"), b -> this.onClose()).bounds(x, y + 102, panelWidth, 20).build());
   }

   private void action(int action) {
      SyncSnapshot.select(action);
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 82, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.listening.current", SyncSnapshot.modeName()), this.width / 2, this.height / 2 - 66, -10036753);
      graphics.centeredText(
         this.font,
         ClientNet.get().supportsListeningModes()
            ? Component.translatable("yunyy.listening.public_hint")
            : Component.translatable("yunyy.listening.public_unavailable"),
         this.width / 2,
         this.height / 2 - 20,
         -5592406
      );
      graphics.centeredText(this.font, Component.translatable("yunyy.listening.personal_hint"), this.width / 2, this.height / 2 + 28, -5592406);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public static void refresh() {
      if (active != null) {
         active.rebuildWidgets();
      }
   }

   public void removed() {
      if (active == this) {
         active = null;
      }

      super.removed();
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
