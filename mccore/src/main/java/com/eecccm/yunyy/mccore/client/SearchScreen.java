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
import com.eecccm.yunyy.mccore.net.SelectSongPacket;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.util.ErrorMessages;

import com.eecccm.yunyy.mccore.net.SelectSongPacket;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.model.SongEntry;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SearchScreen extends ScrollFrame {
   private final Screen parent;
   private final String initialQuery;
   private EditBox queryBox;
   private List<SongEntry> results = List.of();
   private int page;
   private boolean hasMore;
   private String searchedQuery = "";
   private boolean searching;
   private boolean autoSearch;
   private int requestSerial;
   private int choiceSerial;
   private String status = Component.translatable("yunyy.search.status_initial").getString();
   private int statusColor = -5592406;

   public SearchScreen(Screen parent) {
      this(parent, "");
   }

   public SearchScreen(Screen parent, String initialQuery) {
      super(Component.translatable("yunyy.search.title_with", VendorRegistry.activeVendor().label));
      this.parent = parent;
      this.initialQuery = initialQuery == null ? "" : initialQuery.trim();
      this.autoSearch = !this.initialQuery.isBlank();
   }

   protected void init() {
      int panelWidth = Math.min(380, this.width - 30);
      int x = (this.width - panelWidth) / 2;
      int y = 42;
      int contentBottom = y + 178;
      this.scrollPanel.setViewport(x - 4, y - 12, x + panelWidth + 4, Math.min(contentBottom + 4, this.height - 8));
      this.scrollPanel.setContentHeight(contentBottom + 4 - (y - 12));
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      String previous = this.queryBox == null ? this.initialQuery : this.queryBox.getValue();
      this.queryBox = new EditBox(this.font, x, y, panelWidth - 82, 20, Component.translatable("yunyy.search.placeholder"));
      this.queryBox.setMaxLength(80);
      this.queryBox.setHint(Component.translatable("yunyy.search.hint"));
      this.queryBox.setValue(previous);
      this.addRenderableWidget(this.queryBox);
      Button search = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.search.search"), button -> this.search()).bounds(x + panelWidth - 76, y, 76, 20).build()
      );
      search.active = !this.searching;

      for (int i = 0; i < this.results.size(); i++) {
         SongEntry result = this.results.get(i);
         int selection = i;
         int column = i / 5;
         int row = i % 5;
         int gap = 6;
         int itemWidth = (panelWidth - gap) / 2;
         String label = this.page * 10 + i + 1 + ". " + trim(result.name(), 13) + " - " + trim(DisplayNames.artist(result.performer()), 8) + " [" + format(result.lengthMs()) + "]";
         this.addRenderableWidget(
            Button.builder(Component.literal(label), button -> this.choose(selection))
               .bounds(x + column * (itemWidth + gap), y + 31 + row * 24, itemWidth, 20)
               .build()
         );
      }

      Button previousPage = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.search.prev"), button -> this.requestPage(this.page - 1)).bounds(x, y + 154, 82, 20).build()
      );
      previousPage.active = this.page > 0 && !this.searching;
      Button nextPage = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.search.next"), button -> this.requestPage(this.page + 1)).bounds(x + panelWidth - 82, y + 154, 82, 20).build()
      );
      nextPage.active = this.hasMore && !this.searching;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.search.back"), button -> this.onClose()).bounds(this.width / 2 - 55, y + 154, 110, 20).build());
      this.setInitialFocus(this.queryBox);
      this.queryBox.setFocused(true);
      if (this.autoSearch) {
         this.autoSearch = false;
         this.minecraft.execute(this::search);
      }
   }

   private void search() {
      String query = this.queryBox.getValue().trim();
      if (query.isEmpty()) {
         this.status = Component.translatable("yunyy.search.status_empty").getString();
         this.statusColor = -34953;
      } else {
         this.searchedQuery = query;
         this.requestPage(0);
      }
   }

   private void requestPage(int targetPage) {
      if (targetPage >= 0 && !this.searchedQuery.isBlank()) {
         this.searching = true;
         this.results = List.of();
         this.status = Component.translatable("yunyy.search.status_loading", VendorRegistry.activeVendor().label, targetPage + 1).getString();
         this.statusColor = -137;
         int serial = ++this.requestSerial;
         this.rebuildWidgets();
         VendorRegistry.search(this.searchedQuery, targetPage)
            .whenComplete((found, error) -> this.minecraft.execute(() -> this.acceptResult(serial, targetPage, found, error)));
      }
   }

   private void acceptResult(int serial, int targetPage, List<SongEntry> found, Throwable error) {
      if (serial == this.requestSerial) {
         this.searching = false;
         if (error != null) {
            this.results = List.of();
            this.hasMore = false;
            this.status = ErrorMessages.text(error);
            this.statusColor = -34953;
         } else {
            this.results = found == null ? List.of() : List.copyOf(found);
            this.page = targetPage;
            this.hasMore = this.results.size() == 10;
            this.status = this.results.isEmpty()
               ? (targetPage > 0 ? Component.translatable("yunyy.search.status_last_page").getString() : Component.translatable("yunyy.search.status_none").getString())
               : Component.translatable("yunyy.search.status_click").getString();
            this.statusColor = this.results.isEmpty() ? -137 : -5592406;
         }

         this.rebuildWidgets();
      }
   }

   private void choose(int selection) {
      if (!this.searching && selection >= 0 && selection < this.results.size()) {
         SongEntry song = this.results.get(selection);
         if (PlaybackEngine.currentSong() == null) {
            this.searching = true;
            this.status = Component.translatable("yunyy.search.status_warmup").getString();
            this.statusColor = -137;
            int serial = ++this.choiceSerial;
            this.rebuildWidgets();
            warmUp(song).whenComplete((unused, error) -> this.minecraft.execute(() -> {
               if (serial == this.choiceSerial) {
                  this.submit(song);
               }
            }));
         } else {
            warmUp(song);
            this.submit(song);
         }
      }
   }

   private static java.util.concurrent.CompletableFuture<String> warmUp(SongEntry song) {
      return java.util.concurrent.CompletableFuture.supplyAsync(
         () -> VendorRegistry.resolveStreamUrl(song.vendor(), song.songId(), publicFallback(song.vendor(), song.songId())));
   }

   private static String publicFallback(MusicVendor provider, String id) {
      return switch (provider) {
         case KUGOU -> "https://trackerlite.kugou.com/i/ipredirect?hash=" + id;
         case QQ -> "";
         default -> "https://music.163.com/song/media/outer/url?id=" + id + ".mp3";
      };
   }

   private void submit(SongEntry song) {
      JsonObject json = new JsonObject();
      json.addProperty("id", song.songId());
      json.addProperty("title", song.name());
      json.addProperty("artist", song.performer());
      json.addProperty("duration", song.lengthMs());
      json.addProperty("cover", song.coverUri());
      json.addProperty("provider", song.vendor().tag);
      if (SyncSnapshot.personal()) {
         SoloPlayback.add(song);
         ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.search.personal_added"));
      } else {
         ClientNet.get().send(new SelectSongPacket(json.toString()));
         ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.search.submitted"));
      }

      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, 18, -1);
      graphics.centeredText(this.font, this.status, this.width / 2, 67, this.statusColor);
      if (!this.results.isEmpty()) {
         graphics.centeredText(this.font, Component.translatable("yunyy.search.page", this.page + 1), this.width / 2, 221, -7829368);
      }
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      this.choiceSerial++;
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }

   private static String trim(String text, int max) {
      return text.length() <= max ? text : text.substring(0, max - 1) + "…";
   }

   private static String format(long millis) {
      long seconds = Math.max(0L, millis / 1000L);
      return "%d:%02d".formatted(seconds / 60L, seconds % 60L);
   }
}
