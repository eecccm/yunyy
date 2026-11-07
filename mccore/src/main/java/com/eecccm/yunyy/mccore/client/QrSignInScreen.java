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
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.platform.VendorGateway;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.account.QrFlow;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.util.ErrorMessages;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

public final class QrSignInScreen extends ScrollFrame {
   private static final ExecutorService LOGIN_THREAD = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "yunyy-qr-login");
      thread.setDaemon(true);
      return thread;
   });
   /** 扫码本地兜底超时：平台未回过期状态时，120 秒后本地判定过期并提示刷新。 */
   private static final long QR_TIMEOUT_MILLIS = 120_000L;
   private final Screen parent;
   private volatile int generation;
   private volatile boolean closed;
   private Future<?> task;
   private String status;
   private int statusColor = -137;
   private String prompt = "";
   private int qrX;
   private int qrY;
   private int qrSize;
   private int statusY;
   /** T-02：登录前阻断性风险确认；未确认前不发起扫码、不创建二维码会话。 */
   private boolean agreed;

   public QrSignInScreen(Screen parent) {
      super(Component.translatable("yunyy.qr.title_with", VendorRegistry.activeVendor().label));
      this.parent = parent;
   }

   protected void init() {
      this.scrollPanel.setViewport(8, 30, this.width - 8, this.height - 8);
      this.scrollPanel.setSongEntryX(this.width - 8);
      this.scrollPanel.reset();
      // T-02：登录前阻断性风险确认；玩家确认后才创建二维码并进入扫码流程。
      if (!this.agreed) {
         int bw = Math.min(220, Math.max(140, (this.width - 30) / 2));
         int bx = (this.width - bw * 2 - 8) / 2;
         int by = this.height / 2 + 40;
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
      int buttonY = Math.max(74, this.height - 26);
      this.statusY = buttonY - 14;
      int qrTop = 34;
      int availableHeight = Math.max(40, this.statusY - qrTop - 4);
      this.qrSize = Math.min(144, availableHeight);
      this.qrX = (this.width - this.qrSize) / 2;
      this.qrY = qrTop + Math.max(0, (availableHeight - this.qrSize) / 2);
      this.scrollPanel.setViewport(8, 30, this.width - 8, Math.min(buttonY + 26, this.height - 8));
      this.scrollPanel.setContentHeight(buttonY + 26 - 30);
      int gap = 6;
      int buttonWidth = Math.min(96, Math.max(60, (this.width - 20 - gap * 2) / 3));
      int groupWidth = buttonWidth * 3 + gap * 2;
      int startX = (this.width - groupWidth) / 2;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.qr.refresh"), button -> this.startTraceLogin()).bounds(startX, buttonY, buttonWidth, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.qr.import"), button -> this.openManualImport()).bounds(startX + buttonWidth + gap, buttonY, buttonWidth, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.qr.cancel"), button -> this.onClose()).bounds(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build()
      );
      if (this.task == null) {
         this.startTraceLogin();
      }
   }

   private void startTraceLogin() {
      this.stopTask();
      this.closed = false;
      int ticket = ++this.generation;
      this.status = Component.translatable("yunyy.qr.creating", VendorRegistry.activeVendor().label).getString();
      this.statusColor = -137;
      QrTexture.clear();
      this.task = LOGIN_THREAD.submit(() -> this.runTraceLogin(ticket));
   }

   private void runTraceLogin(int ticket) {
      try {
         MusicVendor provider = VendorRegistry.activeVendor();
         if (provider == MusicVendor.NETEASE) {
            this.runNeteaseTraceLogin(ticket);
            return;
         }
         VendorGateway.QrInit data = VendorRegistry.createQr();
         this.minecraft.execute(() -> {
            if (this.active(ticket)) {
               QrTexture.show(data.url());
               this.prompt = Component.translatable("yunyy.qr.scan_prompt", provider.label).getString();
               this.statusColor = -1118482;
               this.status = Component.translatable("yunyy.qr.wait_scan").getString();
            }
         });

         long deadline = System.currentTimeMillis() + QR_TIMEOUT_MILLIS;
         while (this.active(ticket) && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000L);
            if (!this.active(ticket)) {
               return;
            }
            VendorGateway.QrPoll qrStatus = VendorRegistry.pollQr(data.sessionKey());
            if (!this.active(ticket)) {
               return;
            }
            switch (qrStatus.stage()) {
               case SUCCESS:
                  this.update(ticket, Component.translatable("yunyy.qr.verifying").getString(), -137);
                  AccountProfile account = VendorRegistry.importCredential(qrStatus.loginToken()).join();
                  this.minecraft.execute(() -> this.finishSuccess(ticket, account));
                  return;
               case SCANNED:
                  this.update(ticket, Component.translatable("yunyy.qr.scanned").getString(), -137);
                  break;
               case WAIT_SCAN:
                  this.update(ticket, Component.translatable("yunyy.qr.wait_scan").getString(), -5592406);
                  break;
               case EXPIRED:
                  this.update(ticket, Component.translatable("yunyy.qr.failed_expired").getString(), -34953);
                  return;
               case FAILED:
                  this.update(ticket, ErrorMessages.text(qrStatus.error(), qrStatus.args()), -34953);
                  return;
            }
         }
         if (this.active(ticket)) {
            this.update(ticket, Component.translatable("yunyy.qr.failed_expired").getString(), -34953);
         }
      } catch (InterruptedException var6) {
         Thread.currentThread().interrupt();
      } catch (VendorException ve) {
         this.update(ticket, Component.translatable("yunyy.qr.login_failed", ErrorMessages.text(ve)).getString(), -34953);
      } catch (Exception var7) {
         this.update(ticket, Component.translatable("yunyy.qr.login_failed", ErrorMessages.text(var7)).getString(), -34953);
      }
   }

   private void runNeteaseTraceLogin(int ticket) {
      try {
         QrFlow session = new QrFlow();
         QrFlow.BeginResult data = session.begin();
         this.minecraft.execute(() -> {
            if (this.active(ticket)) {
               QrTexture.show(data.url());
               this.prompt = Component.translatable("yunyy.qr.scan_prompt", VendorRegistry.activeVendor().label).getString();
               this.statusColor = -1118482;
            }
         });

         long deadline = System.currentTimeMillis() + QR_TIMEOUT_MILLIS;
         while (this.active(ticket) && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000L);
            if (!this.active(ticket)) {
               return;
            }

            QrFlow.PollResult qrStatus = session.poll();
            if (!this.active(ticket)) {
               return;
            }

            switch (qrStatus.stage()) {
               case EXPIRED:
                  this.update(ticket, Component.translatable("yunyy.qr.expired_refresh").getString(), -34953);
                  return;
               case WAIT_SCAN:
                  this.update(ticket, Component.translatable("yunyy.qr.wait_scan").getString(), -5592406);
                  break;
               case SCANNED:
                  this.update(ticket, Component.translatable("yunyy.qr.scanned").getString(), -137);
                  break;
               case SUCCESS:
                  this.update(ticket, Component.translatable("yunyy.qr.verifying").getString(), -137);
                  if (!hasCookie(qrStatus.cookie(), "MUSIC_U")) {
                     throw new VendorException(VendorError.CREDENTIAL_INVALID, MusicVendor.NETEASE.label);
                  }

                  AccountSessionStore.Profile account = AccountSessionStore.importCredential(qrStatus.cookie()).join();
                  this.minecraft.execute(() -> this.finishSuccess(ticket, toProviderAccount(account)));
                  return;
               case FAILED:
                  this.update(ticket, ErrorMessages.text(qrStatus.error(), qrStatus.args()), -34953);
                  return;
            }
         }
         if (this.active(ticket)) {
            this.update(ticket, Component.translatable("yunyy.qr.expired_refresh").getString(), -34953);
         }
      } catch (InterruptedException var6) {
         Thread.currentThread().interrupt();
      } catch (VendorException ve) {
         this.update(ticket, Component.translatable("yunyy.qr.login_failed", ErrorMessages.text(ve)).getString(), -34953);
      } catch (Exception var7) {
         this.update(ticket, Component.translatable("yunyy.qr.login_failed", ErrorMessages.text(var7)).getString(), -34953);
      }
   }

   private static AccountProfile toProviderAccount(AccountSessionStore.Profile account) {
      // note 只存中性会员标识 "0"/"1"，"VIP " 前缀与分隔符由显示层按语言组织。
      String extra = VendorRegistry.vipProbeEnabled() ? (account.vipType() > 0 ? "1" : "0") : "";
      return AccountProfile.ok(account.nickname(), extra);
   }

   private void finishSuccess(int ticket, AccountProfile account) {
      if (this.active(ticket)) {
         this.closed = true;
         this.generation++;
         this.task = null;
         QrTexture.clear();
         MutableComponent message = Component.translatable(
               "yunyy.cookie.login_ok",
               VendorRegistry.activeVendor().label,
               DisplayNames.accountComponent(account.nickname()));
         message.append(DisplayNames.vipSuffixComponent(account.note(), VendorRegistry.vipProbeEnabled()));
         ClientI18n.send(this.minecraft.player, message);
         ScreenRegistry.set(this.minecraft, this.parent);
      }
   }

   private void update(int ticket, String message, int color) {
      this.minecraft.execute(() -> {
         if (this.active(ticket)) {
            this.status = message;
            this.statusColor = color;
         }
      });
   }

   private void openManualImport() {
      this.closeSession();
      ScreenRegistry.set(this.minecraft, new CookiePasteScreen(this.parent));
   }

   private void closeSession() {
      this.closed = true;
      this.generation++;
      this.stopTask();
      QrTexture.clear();
   }

   private void stopTask() {
      Future<?> current = this.task;
      this.task = null;
      if (current != null) {
         current.cancel(true);
      }
   }

   private boolean active(int ticket) {
      return !this.closed && this.generation == ticket && !Thread.currentThread().isInterrupted();
   }

   private static boolean hasCookie(String cookies, String name) {
      for (String part : cookies.split(";")) {
         String value = part.trim();
         if (value.startsWith(name + "=") && value.length() > name.length() + 1) {
            return true;
         }
      }

      return false;
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      // T-02：风险确认态仅展示提示与确认按钮，不渲染二维码 / 状态。
      if (!this.agreed) {
         graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 64, -1);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_title"), this.width / 2, this.height / 2 - 36, -13210);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_line1"), this.width / 2, this.height / 2 - 16, -5592406);
         graphics.centeredText(this.font, Component.translatable("yunyy.login.risk_line2"), this.width / 2, this.height / 2 - 2, -5592406);
         this.renderScrolled(graphics, mouseX, mouseY, delta);
         return;
      }
      Identifier texture = QrTexture.texture();
      if (texture != null) {
         graphics.fill(this.qrX - 2, this.qrY - 2, this.qrX + this.qrSize + 2, this.qrY + this.qrSize + 2, -1);
         graphics.blit(texture, this.qrX, this.qrY, this.qrX + this.qrSize, this.qrY + this.qrSize, 0.0F, 1.0F, 0.0F, 1.0F);
      }

      graphics.centeredText(this.font, this.title, this.width / 2, 9, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.qr.local_hint"), this.width / 2, 21, -5592406);
      if (this.prompt != null && !this.prompt.isBlank()) {
         graphics.centeredText(this.font, this.prompt, this.width / 2, this.statusY - 14, this.statusColor);
      }
      graphics.centeredText(this.font, this.status, this.width / 2, this.statusY, this.statusColor);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      this.closeSession();
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
