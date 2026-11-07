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
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.util.ErrorMessages;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** 账号密码登录（酷狗/概念版）。账号密码只在游戏内输入，模组直接调官方登录接口。 */
public final class PasswordSignInScreen extends ScrollFrame {
   private final Screen parent;
   private EditBox accountField;
   private EditBox passwordField;
   private Button loginButton;
   private String status = "";
   private int statusColor = -5592406;
   private boolean loggingIn;

   public PasswordSignInScreen(Screen parent) {
      super(Component.translatable("yunyy.password.title_with", VendorRegistry.activeVendor().label));
      this.parent = parent;
   }

   protected void init() {
      int panelWidth = Math.min(380, this.width - 30);
      int x = (this.width - panelWidth) / 2;
      int y = this.height / 2 - 22;
      int contentBottom = y + 76;
      this.scrollPanel.setViewport(x - 4, y - 10, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 10));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      this.accountField = new EditBox(this.font, x, y, panelWidth, 20, Component.translatable("yunyy.password.account"));
      this.accountField.setMaxLength(128);
      this.accountField.setHint(Component.translatable("yunyy.password.hint", VendorRegistry.activeVendor().label));
      this.passwordField = new EditBox(this.font, x, y + 26, panelWidth, 20, Component.translatable("yunyy.password.password"));
      this.passwordField.setMaxLength(64);
      this.passwordField.addFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
      this.addRenderableWidget(this.accountField);
      this.addRenderableWidget(this.passwordField);
      this.loginButton =  this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.password.login"), button -> this.doTraceLogin()).bounds(x, y + 56, (panelWidth - 10) / 2, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.password.cancel"), button -> this.onClose()).bounds(x + (panelWidth - 10) / 2 + 10, y + 56, (panelWidth - 10) / 2, 20).build());
      this.setInitialFocus(this.accountField);
   }

   private void doTraceLogin() {
      if (this.loggingIn) {
         return;
      }
      String account = this.accountField.getValue().trim();
      String password = this.passwordField.getValue();
      if (account.isBlank() || password.isBlank()) {
         this.status = Component.translatable("yunyy.password.fill_both").getString();
         this.statusColor = -34953;
         return;
      }
      this.loggingIn = true;
      this.loginButton.active = false;
      this.status = Component.translatable("yunyy.password.checking", VendorRegistry.activeVendor().label).getString();
      this.statusColor = -137;
      VendorRegistry.signInByPassword(account, password).whenComplete((state, error) -> this.minecraft.execute(() -> {
         this.loggingIn = false;
         this.loginButton.active = true;
         if (error != null) {
            this.status = Component.translatable("yunyy.password.login_fail", ErrorMessages.text(error)).getString();
            this.statusColor = -34953;
         } else if (state == null || !state.active()) {
            String err = state == null ? Component.translatable("yunyy.password.no_return").getString()
                  : ErrorMessages.text(state);
            this.status = Component.translatable("yunyy.password.login_fail", err).getString();
            this.statusColor = -34953;
         } else {
            this.accountField.setValue("");
            this.passwordField.setValue("");
            ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.password.login_ok", VendorRegistry.activeVendor().label, DisplayNames.accountComponent(state.nickname())));
            this.onClose();
         }
      }));
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 76, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.password.local_hint"), this.width / 2, this.height / 2 - 58, -13210);
      graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 + 42, this.statusColor);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      if (this.accountField != null) {
         this.accountField.setValue("");
      }
      if (this.passwordField != null) {
         this.passwordField.setValue("");
      }
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
