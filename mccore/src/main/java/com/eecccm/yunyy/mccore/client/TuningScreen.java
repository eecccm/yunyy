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

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 调试模式界面（云音乐主菜单"关闭"上方入口）。
 * <p>集中放置调试类功能：显示VIP状态开关、设备指纹（VIP增强）、播放信息 HUD 布局设置、歌词 HUD 设置。
 * 打开"显示VIP状态"时自动生成并保存完整设备指纹，保证跨电脑开箱即用。</p>
 */
public final class TuningScreen extends ScrollFrame {
   private final Screen parent;
   private Button vipButton;
   private Component status = Component.empty();
   private int statusColor = -5592406;
   /** true=用户点了"显示VIP状态"开关且尚未确认，界面展示确认区，等待用户决定是否开启。 */
   private boolean vipPendingConfirm;

   public TuningScreen(Screen parent) {
      super(Component.translatable("yunyy.debug.title"));
      this.parent = parent;
   }

   protected void init() {
      int panelWidth = Math.min(360, this.width - 30);
      int col = (panelWidth - 10) / 2;
      int x = (this.width - panelWidth) / 2;
      int y = Math.max(24, this.height / 2 - 76);
      // 待确认时增加 2 行确认区（说明 + 确认/取消），其余按钮下移；否则 4 行基础按钮
      int rows = this.vipPendingConfirm ? 6 : 4;
      int contentBottom = y + rows * 28 + 4;
      this.scrollPanel.setViewport(x - 4, y - 16, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 16));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      this.vipButton = this.addRenderableWidget(Button.builder(vipText(), button -> this.toggleVip())
         .bounds(x, y, panelWidth, 20).build());
      int rowY = y + 28;
      if (this.vipPendingConfirm) {
         this.addRenderableWidget(Button.builder(Component.translatable("yunyy.debug.confirm_vip"),
            button -> this.confirmVipOn()).bounds(x, rowY, panelWidth, 20).build());
         this.addRenderableWidget(Button.builder(Component.translatable("yunyy.debug.cancel_vip"),
            button -> {
               this.vipPendingConfirm = false;
               this.status = Component.empty();
               this.rebuildWidgets();
            }).bounds(x, rowY + 28, panelWidth, 20).build());
         rowY += 56;
      }
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.debug.fingerprint"),
         button -> ScreenRegistry.set(this.minecraft, new FingerprintScreen(this)))
         .bounds(x, rowY, panelWidth, 20).build());
      // 播放信息 HUD 设置与歌词 HUD 设置合并为统一的 HUD 设置（布局/滑块/拖动 一体化预览界面）
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.debug.hud"),
         button -> ScreenRegistry.set(this.minecraft, new LayoutStudioScreen(this)))
         .bounds(x, rowY + 28, panelWidth, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.debug.back"), button -> this.onClose())
         .bounds(x, rowY + 56, panelWidth, 20).build());
   }

   private static Component vipText() {
      return Component.translatable("yunyy.debug.vip", VendorRegistry.vipProbeEnabled() ? Component.translatable("yunyy.debug.on") : Component.translatable("yunyy.debug.off"));
   }

   /** 切换显示VIP状态：开→关 直接切换；关→开 先展示确认区，由用户决定是否开启（开启会自动生成设备指纹）。 */
   private void toggleVip() {
      if (VendorRegistry.vipProbeEnabled()) {
         // 开→关：直接关闭
         this.vipPendingConfirm = false;
         this.status = Component.translatable("yunyy.debug.vip_closed");
         this.statusColor = -5592406;
         VendorRegistry.setVipProbeEnabled(false);
         this.vipButton.setMessage(vipText());
         this.rebuildWidgets();
         return;
      }
      // 关→开：先进入确认状态，让用户决定，不直接开启
      this.vipPendingConfirm = true;
      this.status = Component.translatable("yunyy.debug.vip_confirm_hint");
      this.statusColor = -137;
      this.rebuildWidgets();
   }

   /** 用户确认开启 VIP 显示：自动生成指纹后开启。 */
   private void confirmVipOn() {
      if (VendorRegistry.ensureLocalFingerprint()) {
         VendorRegistry.setVipProbeEnabled(true);
         this.status = Component.translatable("yunyy.debug.vip_on_ok");
         this.statusColor = -8921720;
      } else {
         this.status = Component.translatable("yunyy.debug.vip_init_fail");
         this.statusColor = -13210;
      }
      this.vipPendingConfirm = false;
      this.vipButton.setMessage(vipText());
      this.rebuildWidgets();
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 106, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.debug.hint"), this.width / 2, this.height / 2 - 90, -5592406);
      if (!this.status.getString().isBlank()) {
         graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 + 84, this.statusColor);
      }
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
