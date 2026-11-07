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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 设备指纹输入界面：酷狗用户信息/VIP 接口（get_my_info/get_union_vip）会校验设备完整性，
 * 当返回 20006/20018/20017 时，可手动粘贴酷狗客户端完整设备指纹恢复。
 * 支持从剪贴板粘贴设备指纹 JSON 或抓包 Cookie（dfid/kg_mid/KUGOU_API_MID/
 * KUGOU_API_WEBGL/GUID/MAC）。
 */
public final class FingerprintScreen extends ScrollFrame {
   private final Screen parent;
   private EditBox dfidField;
   private EditBox midField;
   private EditBox guidField;
   private EditBox macField;
   private EditBox webglField;
   private Button saveButton;
   private Component status = Component.translatable("yunyy.device.status_default");
   private int statusColor = -5592406;
   private boolean saving;

   public FingerprintScreen(Screen parent) {
      super(Component.translatable("yunyy.device.title"));
      this.parent = parent;
   }

   protected void init() {
      int panelWidth = Math.min(430, this.width - 30);
      int x = (this.width - panelWidth) / 2;
      int y = this.height / 2 - 96;
      int contentBottom = y + 8 + 5 * 26 + 34 + 26;
      this.scrollPanel.setViewport(x - 4, y - 14, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 14));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      // 确保本机设备指纹完整（模组自包含自动生成，酷狗 VIP/用户信息校验需要配套设备）
      VendorRegistry.ensureLocalFingerprint();
      String[] cur = VendorRegistry.fingerprint();
      this.dfidField = new EditBox(this.font, x, y, panelWidth, 20, Component.translatable("yunyy.device.dfid"));
      this.dfidField.setValue(cur[0]);
      this.dfidField.setHint(Component.translatable("yunyy.device.dfid"));
      this.midField = new EditBox(this.font, x, y + 26, panelWidth, 20, Component.translatable("yunyy.device.mid"));
      this.midField.setValue(cur[1]);
      this.midField.setHint(Component.translatable("yunyy.device.mid"));
      this.guidField = new EditBox(this.font, x, y + 52, panelWidth, 20, Component.translatable("yunyy.device.guid"));
      this.guidField.setValue(cur[2]);
      this.guidField.setHint(Component.translatable("yunyy.device.guid"));
      this.macField = new EditBox(this.font, x, y + 78, panelWidth, 20, Component.translatable("yunyy.device.mac"));
      this.macField.setValue(cur[3]);
      this.macField.setHint(Component.translatable("yunyy.device.mac"));
      this.webglField = new EditBox(this.font, x, y + 104, panelWidth, 20, Component.translatable("yunyy.device.webgl"));
      this.webglField.setValue(cur[4]);
      this.webglField.setHint(Component.translatable("yunyy.device.webgl"));
      this.addRenderableWidget(this.dfidField);
      this.addRenderableWidget(this.midField);
      this.addRenderableWidget(this.guidField);
      this.addRenderableWidget(this.macField);
      this.addRenderableWidget(this.webglField);
      int bw = (panelWidth - 6) / 2;
      // 第一行：获取设备指纹（自动生成完整指纹填入并保存） / 从剪贴板粘贴
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.device.fetch"), button -> this.fetchFingerprint())
         .bounds(x, y + 130, bw, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.device.paste"), button -> {
         String clipboard = this.minecraft.keyboardHandler.getClipboard();
         if (clipboard == null || clipboard.isBlank()) {
            this.status = Component.translatable("yunyy.device.clipboard_empty");
            this.statusColor = -34953;
            return;
         }
         if (parseFingerprint(clipboard.trim())) {
            this.status = Component.translatable("yunyy.device.clipboard_parsed");
            this.statusColor = -8921720;
         } else {
            this.status = Component.translatable("yunyy.device.clipboard_unparsable");
            this.statusColor = -34953;
         }
      }).bounds(x + bw + 6, y + 130, bw, 20).build());
      // 第二行：保存并验证VIP / 返回
      this.saveButton = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.device.save_verify"), button -> this.saveAndVerify()).bounds(x, y + 156, bw, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.device.back"), button -> this.onClose()).bounds(x + bw + 6, y + 156, bw, 20).build());
   }

   /** 一键获取/生成本机完整设备指纹：自动补齐缺失字段并保存到配置，再刷新输入框显示。 */
   private void fetchFingerprint() {
      boolean ok = VendorRegistry.ensureLocalFingerprint();
      String[] cur = VendorRegistry.fingerprint();
      this.dfidField.setValue(cur[0]);
      this.midField.setValue(cur[1]);
      this.guidField.setValue(cur[2]);
      this.macField.setValue(cur[3]);
      this.webglField.setValue(cur[4]);
      this.status = ok ? Component.translatable("yunyy.device.fetch_ok") : Component.translatable("yunyy.device.fetch_fail");
      this.statusColor = ok ? -8921720 : -34953;
   }

   /** 解析剪贴板：JSON（pinia:device 结构）或 key=value / key:value 混合串。 */
   private boolean parseFingerprint(String raw) {
      boolean hit = false;
      try {
         if (raw.trim().startsWith("{")) {
            JsonElement el = JsonParser.parseString(raw);
            JsonObject root = el.getAsJsonObject();
            JsonObject info = root.has("info") && root.get("info").isJsonObject() ? root.getAsJsonObject("info") : root;
            if (info.has("dfid") && info.get("dfid").isJsonPrimitive()) {
               this.dfidField.setValue(info.get("dfid").getAsString());
               hit = true;
            }
            if (info.has("mid") && info.get("mid").isJsonPrimitive()) {
               this.midField.setValue(info.get("mid").getAsString());
               hit = true;
            }
            if (info.has("guid") && info.get("guid").isJsonPrimitive()) {
               this.guidField.setValue(info.get("guid").getAsString());
               hit = true;
            }
            if (info.has("mac") && info.get("mac").isJsonPrimitive()) {
               this.macField.setValue(info.get("mac").getAsString());
               hit = true;
            }
            for (String k : new String[] { "KUGOU_API_WEBGL", "webgl", "webglHash" }) {
               if (info.has(k) && info.get(k).isJsonPrimitive()) {
                  this.webglField.setValue(info.get(k).getAsString());
                  hit = true;
               }
            }
            return hit;
         }
      } catch (Exception ignored) {
      }
      // key=value / key:value 混合
      for (String token : raw.split("[;\\n,]")) {
         String t = token.trim();
         if (t.isEmpty()) {
            continue;
         }
         int eq = t.indexOf('=');
         if (eq < 0) {
            eq = t.indexOf(':');
         }
         if (eq <= 0) {
            continue;
         }
         String k = t.substring(0, eq).trim().toLowerCase();
         String v = t.substring(eq + 1).trim();
         if (v.isEmpty()) {
            continue;
         }
         if (k.equals("dfid")) {
            this.dfidField.setValue(v);
            hit = true;
         } else if (k.equals("mid") || k.equals("kugou_api_mid") || k.equals("kg_mid")) {
            this.midField.setValue(v);
            hit = true;
         } else if (k.equals("guid")) {
            this.guidField.setValue(v);
            hit = true;
         } else if (k.equals("mac")) {
            this.macField.setValue(v);
            hit = true;
         } else if (k.equals("kugou_api_webgl") || k.equals("webgl")) {
            this.webglField.setValue(v);
            hit = true;
         }
      }
      return hit;
   }

   private void saveAndVerify() {
      if (this.saving) {
         return;
      }
      this.saving = true;
      this.saveButton.active = false;
      this.status = Component.translatable("yunyy.device.saving");
      this.statusColor = -137;
      VendorRegistry.setFingerprint(
            this.dfidField.getValue().trim(),
            this.midField.getValue().trim(),
            this.guidField.getValue().trim(),
            this.macField.getValue().trim(),
            this.webglField.getValue().trim());
      VendorRegistry.verifyAccount().whenComplete((account, error) -> this.minecraft.execute(() -> {
         this.saving = false;
         this.saveButton.active = true;
         if (error == null && account.active()) {
            boolean hasVip = VendorRegistry.vipProbeEnabled()
                  && account.note() != null && !account.note().isBlank();
            this.status = Component.translatable(
                  "yunyy.device.saved_valid",
                  DisplayNames.accountComponent(account.nickname()),
                  DisplayNames.vipSuffixComponent(account.note(), VendorRegistry.vipProbeEnabled()));
            this.statusColor = hasVip ? -8921720 : -137;
         } else {
            Component reason = error != null ? ErrorMessages.component(error) : ErrorMessages.component(account);
            this.status = Component.translatable("yunyy.device.verify_failed", reason);
            this.statusColor = -34953;
         }
      }));
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 116, -1);
      graphics.centeredText(this.font, Component.translatable("yunyy.device.hint"), this.width / 2, this.height / 2 - 100, -5592406);
      graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 + 88, this.statusColor);
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
