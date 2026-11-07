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
import com.eecccm.yunyy.core.geom.HudBox;
import com.eecccm.yunyy.core.config.HudPreferences;
import com.eecccm.yunyy.core.config.LyricPreferences;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 统一布局调整预览界面：歌词 HUD 与播放信息 HUD 的布局调整全部融合到一个界面。
 * <ul>
 *   <li>直接拖动歌词块 / 播放信息块 = 移动位置；拖动右下角白色手柄 = 调整大小；</li>
 *   <li>底部面板：滑块实时调整歌词大小 / 行距 / 句距、信息大小；</li>
 *   <li>一键位置预设（歌词：中间居中/中间居左/中间居右；信息：右上角/顶部居中/左下角）；</li>
 *   <li>歌词内容开关（显示 / 句数 / 翻译 / 音译）；恢复默认布局。</li>
 * </ul>
 * 预览渲染与真实 HUD 使用同一渲染管线（无播放时用示例内容），所见即所得；
 * 进入时记录快照，Esc/关闭时若检测到布局修改则弹窗确认保存。
 */
public final class LayoutStudioScreen extends Screen {
   private static final int TARGET_NONE = 0;
   private static final int TARGET_LYRICS = 1;
   private static final int TARGET_NOW_PLAYING = 2;
   private static final int MODE_NONE = 0;
   private static final int MODE_MOVE = 1;
   private static final int MODE_RESIZE = 2;
   private static final int HANDLE = 14;
   private static final int PANEL_H = 154;

   private final Screen parent;
   private int dragTarget;
   private int dragMode;
   private double dragStartX;
   private double dragStartY;
   private double dragStartH;
   private double dragStartV;
   private double dragStartScale;
   private int dragWidth;
   private int dragHeight;
   private int dragLeft;
   private int dragTop;
   private int panelY;
   /** 键盘调整时当前选中的块：进入界面默认选中歌词块，空格在歌词/播放信息之间切换。 */
   private int selected = TARGET_LYRICS;
   private static final int MOVE_STEP = 10;

   public LayoutStudioScreen(Screen parent) {
      super(Component.translatable("yunyy.layout.title"));
      this.parent = parent;
   }

   protected void init() {
      HudPreferences.snapshot();
      LyricPreferences.snapshot();
      int panelWidth = Math.min(920, this.width - 20);
      int x = (this.width - panelWidth) / 2;
      this.panelY = this.height - PANEL_H - 4;
      // 行1：四个滑块（歌词大小 / 行距 / 句距 / 信息大小）
      int sliderW = (panelWidth - 12) / 4;
      this.addRenderableWidget(new PreviewSlider(x, this.panelY + 22, sliderW, PreviewSlider.Kind.LYR_SCALE));
      this.addRenderableWidget(new PreviewSlider(x + sliderW + 4, this.panelY + 22, sliderW, PreviewSlider.Kind.LINE_SP));
      this.addRenderableWidget(new PreviewSlider(x + 2 * (sliderW + 4), this.panelY + 22, sliderW, PreviewSlider.Kind.PARA_SP));
      this.addRenderableWidget(new PreviewSlider(x + 3 * (sliderW + 4), this.panelY + 22, sliderW, PreviewSlider.Kind.NOW_SCALE));
      // 行2：歌词一键位置预设（v 按预览面板边界换算，保证保存后位置与预览所见一致）
      int btn6 = (panelWidth - 20) / 6;
      String[] lyrKeys = {"yunyy.layout.lyr_center", "yunyy.layout.lyr_left", "yunyy.layout.lyr_right"};
      double[] lyrH = {0.5, 0.0, 1.0};
      double[] lyrV = {0.5, 0.5, 0.5};
      for (int i = 0; i < 3; i++) {
         int bx = x + i * (btn6 + 4);
         double h = lyrH[i];
         double v = lyrV[i];
         this.addRenderableWidget(Button.builder(Component.translatable(lyrKeys[i]), button -> {
            LyricPreferences.setAnchorX(h);
            LyricPreferences.setAnchorY(v);
         }).bounds(bx, this.panelY + 46, btn6, 20).build());
      }
      // 行3：信息一键位置预设（6 个：左上/顶中/右上/左下/底中/右下）
      String[] nowKeys = {"yunyy.layout.now_tl", "yunyy.layout.now_tc", "yunyy.layout.now_tr",
         "yunyy.layout.now_bl", "yunyy.layout.now_bc", "yunyy.layout.now_br"};
      double[] nowH = {0.0, 0.5, 1.0, 0.0, 0.5, 1.0};
      double[] nowV = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
      for (int i = 0; i < 6; i++) {
         int bx = x + i * (btn6 + 4);
         double h = nowH[i];
         double v = nowV[i];
         this.addRenderableWidget(Button.builder(Component.translatable(nowKeys[i]), button -> {
            HudPreferences.setAnchorX(h);
            HudPreferences.setAnchorY(v);
         }).bounds(bx, this.panelY + 70, btn6, 20).build());
      }
      // 行4：歌词内容开关
      int btn4 = (panelWidth - 12) / 4;
      this.addRenderableWidget(Button.builder(visibilityText(), button -> {
         LyricPreferences.flipShown();
         this.rebuildWidgets();
      }).bounds(x, this.panelY + 94, btn4, 20).build());
      this.addRenderableWidget(Button.builder(linesText(), button -> {
         LyricPreferences.cycleLineCount();
         this.rebuildWidgets();
      }).bounds(x + btn4 + 4, this.panelY + 94, btn4, 20).build());
      this.addRenderableWidget(Button.builder(translateText(), button -> {
         LyricPreferences.flipTranslation();
         this.rebuildWidgets();
      }).bounds(x + 2 * (btn4 + 4), this.panelY + 94, btn4, 20).build());
      this.addRenderableWidget(Button.builder(romaText(), button -> {
         LyricPreferences.flipRomaji();
         this.rebuildWidgets();
      }).bounds(x + 3 * (btn4 + 4), this.panelY + 94, btn4, 20).build());
      // 行5：恢复默认 / 完成
      int btn2 = (panelWidth - 4) / 2;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.layout.reset"), button -> {
         HudPreferences.restoreDefaults();
         LyricPreferences.restoreDefaults();
         this.rebuildWidgets();
      }).bounds(x, this.panelY + 118, btn2, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.layout.done"), button -> this.onClose()).bounds(x + btn2 + 4, this.panelY + 118, btn2, 20).build());
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      // 预览渲染：与真实 HUD 同一管线、同一全屏位置公式（所见即所得）；
      // 无播放时使用示例内容。若块被底部面板盖住，用键盘（选中 + WASD）移出即可。
      LyricOverlay.renderDemo(graphics, -1);
      NowPlayingOverlay.renderDemo(graphics, -1);
      this.renderTarget(graphics, LyricOverlay.lastRect(), Component.translatable("yunyy.layout.label_lyrics"), this.selected == TARGET_LYRICS);
      this.renderTarget(graphics, NowPlayingOverlay.lastRect(), Component.translatable("yunyy.layout.label_now"), this.selected == TARGET_NOW_PLAYING);
      // 底部调整面板
      int panelWidth = Math.min(920, this.width - 20);
      int x = (this.width - panelWidth) / 2;
      graphics.fill(x - 8, this.panelY - 6, x + panelWidth + 8, this.height, -1056964609);
      graphics.outline(x - 8, this.panelY - 6, x + panelWidth + 8, this.height, -1437808144);
      graphics.centeredText(this.font, Component.translatable("yunyy.layout.hint"), this.width / 2, this.panelY - 18, -1);
      super.extractRenderState(graphics, mouseX, mouseY, delta);
   }

   private void renderTarget(GuiGraphicsExtractor graphics, HudBox rect, Component label, boolean selected) {
      if (rect == null) {
         return;
      }
      if (selected) {
         // 当前选中块：红色粗边框高亮 + 白色手柄
         graphics.outline(rect.x() - 3, rect.y() - 3, rect.x() + rect.width() + 3, rect.y() + rect.height() + 3, -65536);
         int labelY = rect.y() - 11;
         if (labelY < 2) {
            labelY = rect.y() + rect.height() + 2;
         }
         graphics.text(this.font, Component.translatable("yunyy.layout.label_selected", label), rect.x() - 2, labelY, -65536, true);
         int hx = rect.x() + rect.width() - HANDLE;
         int hy = rect.y() + rect.height() - HANDLE;
         graphics.fill(hx, hy, hx + HANDLE, hy + HANDLE, -1);
         graphics.outline(hx - 1, hy - 1, hx + HANDLE + 1, hy + HANDLE + 1, -16777216);
      } else {
         // 未选中块：灰色细框，便于定位
         graphics.outline(rect.x() - 2, rect.y() - 2, rect.x() + rect.width() + 2, rect.y() + rect.height() + 2, -6710887);
         int labelY = rect.y() - 11;
         if (labelY < 2) {
            labelY = rect.y() + rect.height() + 2;
         }
         graphics.text(this.font, label, rect.x() - 2, labelY, -6710887, false);
      }
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
      double mx = event.x();
      double my = event.y();
      this.dragTarget = TARGET_NONE;
      this.dragMode = MODE_NONE;
      // 面板区域内的点击只交给控件处理，不启动拖动
      if (my < this.panelY - 6) {
         HudBox lyrics = LyricOverlay.lastRect();
         if (lyrics != null && inside(mx, my, lyrics)) {
            this.dragTarget = TARGET_LYRICS;
            this.dragStartH = LyricPreferences.anchorX();
            this.dragStartV = LyricPreferences.anchorY();
            this.dragStartScale = LyricPreferences.scale();
            this.dragWidth = lyrics.width();
            this.dragHeight = lyrics.height();
            this.dragLeft = lyrics.x();
            this.dragTop = lyrics.y();
         } else {
            HudBox now = NowPlayingOverlay.lastRect();
            if (now != null && inside(mx, my, now)) {
               this.dragTarget = TARGET_NOW_PLAYING;
               this.dragStartH = HudPreferences.anchorX();
               this.dragStartV = HudPreferences.anchorY();
               this.dragStartScale = HudPreferences.scale();
               this.dragWidth = now.width();
               this.dragHeight = now.height();
               this.dragLeft = now.x();
               this.dragTop = now.y();
            }
         }
         if (this.dragTarget != TARGET_NONE) {
            this.dragMode = onHandle(mx, my) ? MODE_RESIZE : MODE_MOVE;
         }
      }
      this.dragStartX = mx;
      this.dragStartY = my;
      return super.mouseClicked(event, doubleClicked) || this.dragTarget != TARGET_NONE;
   }

   public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
      if (this.dragTarget != TARGET_NONE && this.dragMode != MODE_NONE) {
         double dx = event.x() - this.dragStartX;
         double dy = event.y() - this.dragStartY;
         if (this.dragMode == MODE_RESIZE) {
            double newScale = this.dragStartScale * (this.dragWidth + dx) / (double)Math.max(1, this.dragWidth);
            newScale = Math.max(0.5, Math.min(2.0, newScale));
            double newScreenW = this.dragWidth * newScale / Math.max(0.001, this.dragStartScale);
            double newScreenH = this.dragHeight * newScale / Math.max(0.001, this.dragStartScale);
            double nh = clamp((this.dragLeft - 8) / Math.max(1, this.width - newScreenW - 16), 0.0, 1.0);
            double nv = clamp((this.dragTop - 8) / Math.max(1, this.height - newScreenH - 16), 0.0, 1.0);
            if (this.dragTarget == TARGET_LYRICS) {
               LyricPreferences.setScale(newScale);
               LyricPreferences.setAnchorX(nh);
               LyricPreferences.setAnchorY(nv);
            } else {
               HudPreferences.setScale(newScale);
               HudPreferences.setAnchorX(nh);
               HudPreferences.setAnchorY(nv);
            }
         } else {
            double availX = Math.max(1.0, this.width - this.dragWidth - 16);
            double availY = Math.max(1.0, this.height - this.dragHeight - 16);
            double nh = clamp(this.dragStartH + dx / availX, 0.0, 1.0);
            double nv = clamp(this.dragStartV + dy / availY, 0.0, 1.0);
            if (this.dragTarget == TARGET_LYRICS) {
               LyricPreferences.setAnchorX(nh);
               LyricPreferences.setAnchorY(nv);
            } else {
               HudPreferences.setAnchorX(nh);
               HudPreferences.setAnchorY(nv);
            }
         }
         return true;
      }
      return super.mouseDragged(event, deltaX, deltaY);
   }

   public boolean mouseReleased(MouseButtonEvent event) {
      this.dragTarget = TARGET_NONE;
      this.dragMode = MODE_NONE;
      return super.mouseReleased(event);
   }

   /** 键盘微调：空格切换选中块，W/A/S/D 按步长移动选中块（按住可连续移动）。 */
   public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
      int keyCode = event.key();
      if (keyCode == 32) {   // 空格：歌词 <-> 播放信息
         this.selected = this.selected == TARGET_LYRICS ? TARGET_NOW_PLAYING : TARGET_LYRICS;
         return true;
      }
      int dx = 0;
      int dy = 0;
      switch (keyCode) {
         case 87 -> dy = -MOVE_STEP;   // W 上
         case 83 -> dy = MOVE_STEP;    // S 下
         case 65 -> dx = -MOVE_STEP;   // A 左
         case 68 -> dx = MOVE_STEP;    // D 右
         default -> {
         }
      }
      if (dx != 0 || dy != 0) {
         this.moveSelected(dx, dy);
         return true;
      }
      return super.keyPressed(event);
   }

   private void moveSelected(int dx, int dy) {
      HudBox rect = this.selected == TARGET_LYRICS ? LyricOverlay.lastRect() : NowPlayingOverlay.lastRect();
      if (rect == null) {
         return;
      }
      // 全屏范围移动（与鼠标拖动一致），不限制在面板上方
      int nx = (int)Math.round(clamp(rect.x() + dx, 8, Math.max(8, this.width - rect.width() - 8)));
      int ny = (int)Math.round(clamp(rect.y() + dy, 8, Math.max(8, this.height - rect.height() - 8)));
      if (this.selected == TARGET_LYRICS) {
         LyricPreferences.setAnchorX((nx - 8) / (double)Math.max(1, this.width - rect.width() - 16));
         LyricPreferences.setAnchorY((ny - 8) / (double)Math.max(1, this.height - rect.height() - 16));
      } else {
         HudPreferences.setAnchorX((nx - 8) / (double)Math.max(1, this.width - rect.width() - 16));
         HudPreferences.setAnchorY((ny - 8) / (double)Math.max(1, this.height - rect.height() - 16));
      }
   }

   public void onClose() {
      if (HudPreferences.changed() || LyricPreferences.changed()) {
         ScreenRegistry.set(this.minecraft, new SaveConfirmScreen(this.parent, () -> {
            HudPreferences.save();
            LyricPreferences.save();
         }));
      } else {
         ScreenRegistry.set(this.minecraft, this.parent);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   private boolean onHandle(double mx, double my) {
      return mx >= this.dragLeft + this.dragWidth - HANDLE && my >= this.dragTop + this.dragHeight - HANDLE;
   }

   private static boolean inside(double mx, double my, HudBox rect) {
      return mx >= rect.x() && mx <= rect.x() + rect.width() && my >= rect.y() && my <= rect.y() + rect.height();
   }

   private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
   }

   private static Component visibilityText() {
      return Component.translatable(LyricPreferences.shown() ? "yunyy.layout.lyr_visible_on" : "yunyy.layout.lyr_visible_off");
   }

   private static Component linesText() {
      return Component.translatable("yunyy.layout.lyr_lines", LyricPreferences.lineCount());
   }

   private static Component translateText() {
      return Component.translatable(LyricPreferences.showTranslation() ? "yunyy.layout.lyr_translate_on" : "yunyy.layout.lyr_translate_off");
   }

   private static Component romaText() {
      return Component.translatable(LyricPreferences.showRomaji() ? "yunyy.layout.lyr_roma_on" : "yunyy.layout.lyr_roma_off");
   }

   private static final class PreviewSlider extends AbstractSliderButton {
      private final Kind kind;

      PreviewSlider(int x, int y, int width, Kind kind) {
         super(x, y, width, 20, Component.empty(), initial(kind));
         this.kind = kind;
         this.updateMessage();
      }

      private static double initial(Kind kind) {
         return switch (kind) {
            case LYR_SCALE -> (LyricPreferences.scale() - 0.5) / 1.5;
            case LINE_SP -> (LyricPreferences.rowGap() - 14) / 34.0;
            case PARA_SP -> (LyricPreferences.blockGap() - 6) / 34.0;
            case NOW_SCALE -> (HudPreferences.scale() - 0.5) / 1.5;
         };
      }

      protected void updateMessage() {
         this.setMessage(Component.translatable(switch (this.kind) {
            case LYR_SCALE -> "yunyy.layout.slider_lyr";
            case LINE_SP -> "yunyy.layout.slider_line";
            case PARA_SP -> "yunyy.layout.slider_para";
            case NOW_SCALE -> "yunyy.layout.slider_now";
         }, this.kind == Kind.LINE_SP || this.kind == Kind.PARA_SP
            ? Math.round(switch (this.kind) {
               case LINE_SP -> 14.0 + this.value * 34.0;
               case PARA_SP -> 6.0 + this.value * 34.0;
               default -> 0.0;
            })
            : Math.round((0.5 + this.value * 1.5) * 100.0)));
      }

      protected void applyValue() {
         switch (this.kind) {
            case LYR_SCALE -> LyricPreferences.setScale(0.5 + this.value * 1.5);
            case LINE_SP -> LyricPreferences.setRowGap((int)Math.round(14.0 + this.value * 34.0));
            case PARA_SP -> LyricPreferences.setBlockGap((int)Math.round(6.0 + this.value * 34.0));
            case NOW_SCALE -> HudPreferences.setScale(0.5 + this.value * 1.5);
         }
      }

      private enum Kind {
         LYR_SCALE,
         LINE_SP,
         PARA_SP,
         NOW_SCALE;
      }
   }
}
