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

public final class CookiePasteScreen extends ScrollFrame {
   private final Screen parent;
   private EditBox cookieField;
   private Button importButton;
   private String status;
   private int statusColor = -5592406;
   private boolean importing;
   /** T-02：登录前阻断性风险确认；未确认前不展示凭证输入框、不发起校验。 */
   private boolean agreed;

   public CookiePasteScreen(Screen parent) {
      super(Component.translatable("yunyy.cookie.title_with", VendorRegistry.activeVendor().label));
      this.parent = parent;
   }

   protected void init() {
      int panelWidth = Math.min(380, this.width - 30);
      int x = (this.width - panelWidth) / 2;
      int y = this.height / 2 - 22;
      int contentBottom = y + 50;
      this.scrollPanel.setViewport(x - 4, y - 10, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 10));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      // T-02：风险确认态先不创建凭证输入框；玩家确认风险后再进入粘贴流程。
      if (!this.agreed) {
         int bw = Math.min(220, Math.max(140, (this.width - 30) / 2));
         int bx = (this.width - bw * 2 - 8) / 2;
         int by = this.height / 2 + 24;
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.login.continue"), button -> {
               this.agreed = true;
               this.rebuildWidgets();
            }).bounds(bx, by, bw, 20).build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.login.cancel"), button -> this.onClose())
               .bounds(bx + bw + 8, by, bw, 20).build()
         );
         return;
      }
      String provider = VendorRegistry.activeVendor().tag;
      this.cookieField = new EditBox(this.font, x, y, panelWidth, 20, Component.translatable("yunyy.cookie.field"));
      this.cookieField.setMaxLength(8192);
      String hint = switch (provider) {
         case "kugou" -> Component.translatable("yunyy.cookie.hint_kugou").getString();
         case "qq" -> Component.translatable("yunyy.cookie.hint_qq").getString();
         default -> Component.translatable("yunyy.cookie.hint_netease").getString();
      };
      this.cookieField.setHint(Component.literal(hint));
      this.cookieField.addFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
      this.addRenderableWidget(this.cookieField);
      int bw = (panelWidth - 12) / 3;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.cookie.paste"), button -> {
         String clipboard = this.minecraft.keyboardHandler.getClipboard();
         this.cookieField.setValue(clipboard == null ? "" : clipboard.trim());
         this.status = this.cookieField.getValue().isBlank() ? Component.translatable("yunyy.device.clipboard_empty").getString() : Component.translatable("yunyy.cookie.pasted").getString();
         this.statusColor = this.cookieField.getValue().isBlank() ? -34953 : -5592406;
      }).bounds(x, y + 30, bw, 20).build());
      this.importButton = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.cookie.save_verify"), button -> this.importCookie()).bounds(x + bw + 6, y + 30, bw, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.cookie.cancel"), button -> this.onClose()).bounds(x + bw * 2 + 12, y + 30, bw, 20).build());
      this.setInitialFocus(this.cookieField);
   }

   private void importCookie() {
      if (!this.importing) {
         String value = this.cookieField.getValue();
         if (value.isBlank()) {
            this.status = Component.translatable("yunyy.cookie.empty_first").getString();
            this.statusColor = -34953;
         } else {
            this.importing = true;
            this.importButton.active = false;
            this.status = Component.translatable("yunyy.cookie.checking", VendorRegistry.activeVendor().label).getString();
            this.statusColor = -137;
            VendorRegistry.importCredential(value).whenComplete((account, error) -> this.minecraft.execute(() -> {
               this.importing = false;
               if (error != null) {
                  this.importButton.active = true;
                  this.status = Component.translatable("yunyy.cookie.import_fail", ErrorMessages.text(error)).getString();
                  this.statusColor = -34953;
               } else if (account == null || !account.active()) {
                  this.importButton.active = true;
                  String err = account == null ? Component.translatable("yunyy.cookie.no_return").getString()
                        : ErrorMessages.text(account);
                  this.status = Component.translatable("yunyy.cookie.invalid", err).getString();
                  this.statusColor = -34953;
               } else {
                  this.cookieField.setValue("");
                  ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.cookie.login_ok", VendorRegistry.activeVendor().label, DisplayNames.accountComponent(account.nickname())));
                  this.onClose();
               }
            }));
         }
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      // T-02：风险确认态仅展示提示与确认按钮，不渲染凭证输入框与状态。
      if (!this.agreed) {
         graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 56, -1);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_title"), this.width / 2, this.height / 2 - 32, -13210);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_line1"), this.width / 2, this.height / 2 - 16, -5592406);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_line2"), this.width / 2, this.height / 2 - 2, -5592406);
         this.renderScrolled(graphics, mouseX, mouseY, delta);
         return;
      }
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 76, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.cookie.only_own"), this.width / 2, this.height / 2 - 58, -13210);
      graphics.centeredText(this.font, Component.translatable("yunyy.cookie.local_only"), this.width / 2, this.height / 2 - 44, -5592406);
      graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 + 42, this.statusColor);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      if (this.cookieField != null) {
         this.cookieField.setValue("");
      }

      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
