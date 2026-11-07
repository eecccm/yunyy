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
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.core.history.VipAuditLog;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.util.ErrorMessages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProfileScreen extends ScrollFrame {
   private final Screen parent;
   private Component status = Component.translatable("yunyy.account.status_default");
   private int statusColor = -5592406;
   private boolean checking;

   public ProfileScreen(Screen parent) {
      super(Component.translatable("yunyy.account.title"));
      this.parent = parent;
   }

   protected void init() {
      int cx = this.width / 2 - 110;
      // 服务商切换：宽度自适应最长平台名，防止文字被按钮边缘遮挡
      int bw = 110;
      for (MusicVendor t : MusicVendor.values()) {
         int w = 12 * t.label.length() + 18;
         if (w > bw) bw = w;
      }
      int gap = 6;
      int x = this.width / 2 - (bw * 2 + gap) / 2;
      int y = this.height / 2 - 92;
      // 7 行按钮：扫码/密码/导入/检查/游客模式/退出/返回（VIP 开关与设备指纹已并入 HUD 设置的调试模式区域）
      int rows = 7;
      int contentBottom = y + 58 + rows * 26 + 20;
      this.scrollPanel.setViewport(x - 8, y - 10, x + bw * 2 + gap + 8, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 10));
      this.scrollPanel.setSongEntryX(x + bw * 2 + gap + 10);
      this.scrollPanel.reset();
      for (MusicVendor t : MusicVendor.values()) {
         // 两列排布、超出的服务商换行（行高 26，与下方按钮区间不重叠）
         int col = t.ordinal() % 2;
         int row = t.ordinal() / 2;
         Button b = this.addRenderableWidget(
            Button.builder(
               Component.literal(t.label + (t == VendorRegistry.activeVendor() ? " ✓" : "")),
               btn -> {
                  VendorRegistry.activate(t);
                  this.status = Component.translatable("yunyy.account.switched", t.label,
                     VendorRegistry.hasSession() ? Component.translatable("yunyy.account.logged_in") : Component.translatable("yunyy.account.not_logged_in"));
                  this.statusColor = -8921720;
                  this.rebuildWidgets();
               }
            ).bounds(x + col * (bw + gap), y + row * 26, bw, 20).build()
         );
      }

      int gy = y + 58;
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.account.qr"), button -> ScreenRegistry.set(this.minecraft, new QrSignInScreen(this)))
            .bounds(cx, gy, 220, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.account.password"), button -> {
            MusicVendor t = VendorRegistry.activeVendor();
            // 仅酷狗提供账号密码登录接口，其余平台直取只会收到"不支持"异常，先行中性提示
            if (t != MusicVendor.KUGOU) {
               this.status = Component.translatable("yunyy.account.no_password", VendorRegistry.activeVendor().label);
               this.statusColor = -34953;
            } else {
               ScreenRegistry.set(this.minecraft, new PasswordSignInScreen(this));
            }
         }).bounds(cx, gy + 26, 220, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.account.import"), button -> ScreenRegistry.set(this.minecraft, new CookiePasteScreen(this)))
            .bounds(cx, gy + 52, 220, 20).build()
      );
      Button check = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.account.check"), button -> this.check()).bounds(cx, gy + 78, 220, 20).build()
      );
      check.active = VendorRegistry.hasSession() && !this.checking;
      Button logout = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.account.logout"), button -> {
         VendorRegistry.signOut();
         this.status = Component.translatable("yunyy.account.logged_out", VendorRegistry.activeVendor().label);
         this.statusColor = -5592406;
         this.rebuildWidgets();
      }).bounds(cx, gy + 104, 220, 20).build());
      logout.active = VendorRegistry.hasSession();
      this.addRenderableWidget(Button.builder(Component.translatable(
            FeatureSwitch.freeOnly ? "yunyy.account.guest_mode_on" : "yunyy.account.guest_mode_off"),
            button -> {
               FeatureSwitch.freeOnly = !FeatureSwitch.freeOnly;
               this.status = Component.translatable(
                     FeatureSwitch.freeOnly ? "yunyy.account.guest_on_ok" : "yunyy.account.guest_off_ok");
               this.statusColor = -8921720;
               this.rebuildWidgets();
            }).bounds(cx, gy + 130, 220, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.account.back"), button -> this.onClose()).bounds(cx, gy + 156, 220, 20).build());
   }

   private void check() {
      this.checking = true;
      this.status = Component.translatable("yunyy.account.checking", VendorRegistry.activeVendor().label);
      this.statusColor = -137;
      this.rebuildWidgets();
      VendorRegistry.verifyAccount().whenComplete((account, error) -> this.minecraft.execute(() -> {
         this.checking = false;
         String platform = VendorRegistry.activeVendor().tag;
         if (error == null && account.active()) {
            this.status = Component.translatable(
                  "yunyy.account.valid",
                  DisplayNames.accountComponent(account.nickname()),
                  DisplayNames.vipSuffixComponent(account.note(), VendorRegistry.vipProbeEnabled()));
            this.statusColor = -8921720;
            VipAuditLog.record(platform, account.nickname(), "",
                  VipAuditLog.SOURCE_PROBE, VipAuditLog.RESULT_ALLOWED, "verify ok");
         } else {
            Component reason = error != null ? ErrorMessages.component(error) : ErrorMessages.component(account);
            this.status = Component.translatable("yunyy.account.invalid", reason);
            this.statusColor = -34953;
            String accountRef = account == null ? "offline" : account.nickname();
            String note = error != null ? ErrorMessages.text(error) : ErrorMessages.text(account);
            VipAuditLog.record(platform, accountRef, "",
                  VipAuditLog.SOURCE_PROBE, VipAuditLog.RESULT_DENIED, note);
         }
         this.rebuildWidgets();
      }));
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 116, -1);
      graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 - 60, this.statusColor);
      graphics.centeredText(this.font, Component.translatable("yunyy.account.provider", VendorRegistry.activeVendor().label), this.width / 2, this.height / 2 + 148, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.account.credential_hint"), this.width / 2, this.height / 2 + 166, -13210);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
