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

/** 退出设置时，若有未保存的布局修改，弹出确认。 */
public final class SaveConfirmScreen extends Screen {
   private final Screen parent;
   private final Runnable onSave;

   public SaveConfirmScreen(Screen parent, Runnable onSave) {
      super(Component.translatable("yunyy.save.title"));
      this.parent = parent;
      this.onSave = onSave;
   }

   protected void init() {
      int panelWidth = 280;
      int x = (this.width - panelWidth) / 2;
      int y = this.height / 2 - 40;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.save.save"), button -> {
         this.onSave.run();
         ScreenRegistry.set(this.minecraft, this.parent);
      }).bounds(x, y + 40, 132, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.save.discard"), button -> ScreenRegistry.set(this.minecraft, this.parent))
         .bounds(x + 148, y + 40, 132, 20)
         .build());
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, Component.translatable("yunyy.save.changed"), this.width / 2, this.height / 2 - 22, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.save.ask"), this.width / 2, this.height / 2 - 6, -5592406);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
