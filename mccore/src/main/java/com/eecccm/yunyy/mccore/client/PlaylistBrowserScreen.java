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
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.mccore.net.PlaylistImportPacket;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.util.ErrorMessages;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PlaylistBrowserScreen extends ScrollFrame {
   private static final int PLAYLIST_PAGE_SIZE = 7;
   private static final int TRACK_PAGE_SIZE = 5;
   private static final int MAX_IMPORT = 500;
   private final Screen parent;
   private List<PlaylistFolder> playlists = List.of();
   private PlaylistContents selected;
   private List<SongEntry> filtered = List.of();
   private final Set<String> checked = new HashSet<>();
   private EditBox filterBox;
   private int page;
   private String status = Component.translatable("yunyy.playlist.loading", VendorRegistry.activeVendor().label).getString();
   private int statusColor = -137;
   private boolean loading = true;
   private boolean confirmingAll;
   private int submitSerial;

   public PlaylistBrowserScreen(Screen parent) {
      super(Component.translatable("yunyy.playlist.title_import", VendorRegistry.activeVendor().label));
      this.parent = parent;
   }

   protected void init() {
      if (this.loading) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("yunyy.playlist.cancel"), b -> this.onClose()).bounds(this.width / 2 - 50, this.height / 2 + 62, 100, 20).build()
         );
         if (this.playlists.isEmpty() && this.selected == null && this.loading) {
            this.loadPlaylistFolders();
         }
      } else {
         if (this.selected != null) {
            this.buildSongEntrys();
         } else if (!this.playlists.isEmpty()) {
            this.buildPlaylistFolderPage();
         } else {
            this.addRenderableWidget(
               Button.builder(Component.translatable("yunyy.playlist.retry"), b -> this.retry()).bounds(this.width / 2 - 105, this.height / 2 + 48, 100, 20).build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("yunyy.playlist.back"), b -> this.onClose()).bounds(this.width / 2 + 5, this.height / 2 + 48, 100, 20).build()
            );
         }
      }
   }

   private void loadPlaylistFolders() {
      VendorRegistry.fetchPlaylists().whenComplete((result, error) -> this.minecraft.execute(() -> {
         this.loading = false;
         if (error != null) {
            this.status = ErrorMessages.text(error);
            this.statusColor = -34953;
         } else if (result.isEmpty()) {
            this.status = Component.translatable("yunyy.playlist.none").getString();
            this.statusColor = -13210;
         } else {
            this.playlists = result;
            this.status = Component.translatable("yunyy.playlist.click_hint").getString();
            this.statusColor = -5592406;
         }

         this.rebuildWidgets();
      }));
   }

   private void buildPlaylistFolderPage() {
      int pages = Math.max(1, (this.playlists.size() + PLAYLIST_PAGE_SIZE - 1) / PLAYLIST_PAGE_SIZE);
      this.page = Math.max(0, Math.min(this.page, pages - 1));
      int panelWidth = Math.min(380, this.width - 30);
      int x = (this.width - panelWidth) / 2;
      int y = 42;
      int start = this.page * PLAYLIST_PAGE_SIZE;
      int end = Math.min(start + PLAYLIST_PAGE_SIZE, this.playlists.size());
      int navY = y + 161 + 4;
      this.scrollPanel.setViewport(x - 4, 30, x + panelWidth + 4, Math.min(navY + 26, this.height - 8));
      this.scrollPanel.setContentHeight(navY + 26 - 30);
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();

      for (int i = start; i < end; i++) {
         PlaylistFolder playlist = this.playlists.get(i);
         this.addRenderableWidget(
            Button.builder(Component.literal(trim(DisplayNames.playlistFolder(playlist.folderId(), playlist.folderName()), 32) + " · " + Component.translatable("yunyy.playlist.song_suffix", playlist.songCount()).getString()), b -> this.openPlaylistFolder(playlist))
               .bounds(x, y + (i - start) * 23, panelWidth, 20)
               .build()
         );
      }

      Button previous = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.prev"), b -> {
         this.page--;
         this.rebuildWidgets();
      }).bounds(x, navY, 90, 20).build());
      previous.active = this.page > 0;
      Button next = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.next"), b -> {
         this.page++;
         this.rebuildWidgets();
      }).bounds(x + panelWidth - 90, navY, 90, 20).build());
      next.active = this.page + 1 < pages;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.close"), b -> this.onClose()).bounds(this.width / 2 - 45, navY, 90, 20).build());
   }

   private void openPlaylistFolder(PlaylistFolder playlist) {
      this.loading = true;
      this.status = Component.translatable("yunyy.playlist.loading_tracks").getString();
      this.statusColor = -137;
      this.rebuildWidgets();
      VendorRegistry.fetchPlaylistSongs(playlist.folderId()).whenComplete((tracks, error) -> this.minecraft.execute(() -> {
         this.loading = false;
         if (error != null) {
            this.selected = null;
            this.status = ErrorMessages.text(error);
            this.statusColor = -34953;
         } else {
            this.selected = tracks;
            this.filtered = tracks.tracks();
            this.checked.clear();
            this.page = 0;
            this.status = tracks.tracks().isEmpty()
                  ? Component.translatable("yunyy.playlist.empty_hint").getString()
                  : Component.translatable("yunyy.playlist.track_hint").getString();
            this.statusColor = tracks.tracks().isEmpty() ? -34953 : -5592406;
         }

         this.rebuildWidgets();
      }));
   }

   private void buildSongEntrys() {
      int panelWidth = Math.min(460, this.width - 20);
      int x = (this.width - panelWidth) / 2;
      int y = 42;
      int navY = y + 25 + 105 + 3;
      this.scrollPanel.setViewport(x - 4, 38, x + panelWidth + 4, Math.min(navY + 52, this.height - 8));
      this.scrollPanel.setContentHeight(navY + 52 - 38);
      this.scrollPanel.setSongEntryX(x + panelWidth + 6);
      this.scrollPanel.reset();
      String prior = this.filterBox == null ? "" : this.filterBox.getValue();
      this.filterBox = new EditBox(this.font, x, y, panelWidth - 80, 20, Component.translatable("yunyy.playlist.search_placeholder"));
      this.filterBox.setMaxLength(80);
      this.filterBox.setHint(Component.translatable("yunyy.playlist.search_placeholder"));
      this.filterBox.setValue(prior);
      this.addRenderableWidget(this.filterBox);
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.filter"), b -> this.filter()).bounds(x + panelWidth - 74, y, 74, 20).build());
      int pages = Math.max(1, (this.filtered.size() + TRACK_PAGE_SIZE - 1) / TRACK_PAGE_SIZE);
      this.page = Math.max(0, Math.min(this.page, pages - 1));
      int start = this.page * TRACK_PAGE_SIZE;
      int end = Math.min(start + TRACK_PAGE_SIZE, this.filtered.size());

      for (int i = start; i < end; i++) {
         SongEntry song = this.filtered.get(i);
         String mark = this.checked.contains(song.songId()) ? "☑ " : (PlayQueue.contains(song.songId()) ? "▶ " : "☐ ");
         Button row = this.addRenderableWidget(
            Button.builder(
                  Component.literal(mark + trim(song.name(), 20) + " - " + trim(DisplayNames.artist(song.performer()), 11) + " [" + format(song.lengthMs()) + "]"),
                  b -> this.toggle(song.songId())
               )
               .bounds(x, y + 25 + (i - start) * 21, panelWidth, 20)
               .build()
         );
         row.active = !PlayQueue.contains(song.songId());
      }

      Button previous = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.prev"), b -> {
         this.page--;
         this.rebuildWidgets();
      }).bounds(x, navY, 70, 20).build());
      previous.active = this.page > 0;
      Button next = this.addRenderableWidget(Button.builder(Component.translatable("yunyy.queue.next"), b -> {
         this.page++;
         this.rebuildWidgets();
      }).bounds(x + 75, navY, 70, 20).build());
      next.active = this.page + 1 < pages;
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.add_page"), b -> this.addPage()).bounds(x + 150, navY, 85, 20).build());
      Button addSelected = this.addRenderableWidget(
         Button.builder(Component.translatable("yunyy.playlist.add_checked", this.checked.size()), b -> this.submit(new ArrayList<>(this.checked)))
            .bounds(x + 240, navY, 100, 20)
            .build()
      );
      addSelected.active = !this.checked.isEmpty();
      this.addRenderableWidget(
         Button.builder(Component.translatable(this.confirmingAll ? "yunyy.playlist.confirm_all" : "yunyy.playlist.add_all"), b -> this.addAll()).bounds(x + 345, navY, panelWidth - 345, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.random", 10), b -> this.addRandom(10)).bounds(x, navY + 25, 82, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.random", 50), b -> this.addRandom(50)).bounds(x + 87, navY + 25, 82, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.random", 100), b -> this.addRandom(100)).bounds(x + 174, navY + 25, 88, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.clear_checked"), b -> {
         this.checked.clear();
         this.rebuildWidgets();
      }).bounds(x + 267, navY + 25, 88, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("yunyy.playlist.back_to_list"), b -> this.backToList()).bounds(x + 360, navY + 25, panelWidth - 360, 20).build());
   }

   private void toggle(String id) {
      if (!this.checked.add(id)) {
         this.checked.remove(id);
      }

      this.rebuildWidgets();
   }

   private void filter() {
      String query = this.filterBox.getValue().trim().toLowerCase();
      this.filtered = query.isEmpty()
         ? this.selected.tracks()
         : this.selected.tracks().stream().filter(song -> song.name().toLowerCase().contains(query) || song.performer().toLowerCase().contains(query)).toList();
      this.page = 0;
      this.confirmingAll = false;
      this.rebuildWidgets();
   }

   private void addPage() {
      int start = this.page * TRACK_PAGE_SIZE;
      int end = Math.min(start + TRACK_PAGE_SIZE, this.filtered.size());
      this.submit(this.filtered.subList(start, end).stream().map(SongEntry::songId).toList());
   }

   private void addAll() {
      if (!this.confirmingAll) {
         this.confirmingAll = true;
         this.rebuildWidgets();
      } else {
         this.submit(this.selected.tracks().stream().map(SongEntry::songId).limit(MAX_IMPORT).toList());
      }
   }

   private void addRandom(int count) {
      List<String> ids = new ArrayList<>(this.selected.tracks().stream().map(SongEntry::songId).toList());
      Collections.shuffle(ids);
      this.submit(ids.subList(0, Math.min(count, ids.size())));
   }

   private void submit(List<String> input) {
      Map<String, SongEntry> byId = new LinkedHashMap<>();

      for (SongEntry track : this.selected.tracks()) {
         byId.putIfAbsent(track.songId(), track);
      }

      List<SongEntry> tracks = new ArrayList<>();
      Set<String> addedIds = new HashSet<>();

      for (String id : input) {
         SongEntry track = byId.get(id);
         if (track != null && addedIds.add(id) && !PlayQueue.contains(id)) {
            tracks.add(track);
         }

         if (tracks.size() >= MAX_IMPORT) {
            break;
         }
      }

      if (tracks.isEmpty()) {
         this.status = Component.translatable("yunyy.playlist.already_queued").getString();
         this.statusColor = -13210;
      } else if (PlaybackEngine.currentSong() == null) {
         this.loading = true;
         this.status = Component.translatable("yunyy.search.status_warmup").getString();
         this.statusColor = -137;
         int serial = ++this.submitSerial;
         this.rebuildWidgets();
         warmUp(tracks.getFirst()).whenComplete((unused, error) -> this.minecraft.execute(() -> {
            if (serial == this.submitSerial) {
               if (error != null) {
                  String msg = ErrorMessages.text(error);
                  this.loading = false;
                  this.status = Component.translatable("yunyy.playlist.first_fail", msg).getString();
                  this.statusColor = -65536;
                  this.rebuildWidgets();
                  PlaylistBrowserScreen.showMessage(Component.translatable("yunyy.playlist.first_fail_chat", tracks.getFirst().name(), msg).getString());
               } else {
                  this.sendSongEntrys(tracks);
               }
            }
         }));
      } else {
         warmUp(tracks.getFirst());
         this.sendSongEntrys(tracks);
      }
   }

   private static java.util.concurrent.CompletableFuture<String> warmUp(SongEntry track) {
      return java.util.concurrent.CompletableFuture.supplyAsync(
         () -> VendorRegistry.resolveStreamUrl(track.vendor(), track.songId(), ""));
   }

   private void sendSongEntrys(List<SongEntry> tracks) {
      JsonObject root = new JsonObject();
      JsonArray array = new JsonArray();

      for (SongEntry track : tracks) {
         JsonObject item = new JsonObject();
         item.addProperty("id", track.songId());
         item.addProperty("title", track.name());
         item.addProperty("artist", track.performer());
         item.addProperty("duration", track.lengthMs());
         item.addProperty("cover", track.coverUri());
         item.addProperty("provider", track.vendor().tag);
         array.add(item);
      }

      root.add("tracks", array);
      if (SyncSnapshot.personal()) {
         SoloPlayback.addAll(tracks.getFirst(), tracks.subList(1, tracks.size()));
         ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.playlist.personal_added", tracks.size()));
      } else {
         ClientNet.get().send(new PlaylistImportPacket(root.toString()));
         ClientI18n.send(this.minecraft.player, Component.translatable("yunyy.playlist.submitted_local", tracks.size()));
      }

      this.onClose();
   }

   private void retry() {
      this.playlists = List.of();
      this.selected = null;
      this.loading = true;
      this.status = Component.translatable("yunyy.playlist.loading", VendorRegistry.activeVendor().label).getString();
      this.rebuildWidgets();
   }

   private void backToList() {
      this.selected = null;
      this.filtered = List.of();
      this.checked.clear();
      this.filterBox = null;
      this.page = 0;
      this.status = Component.translatable("yunyy.playlist.click_hint").getString();
      this.rebuildWidgets();
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      this.extractTransparentBackground(graphics);
      graphics.centeredText(this.font, this.title, this.width / 2, 10, -1);
      if (this.selected != null) {
         graphics.centeredText(this.font, trim(DisplayNames.playlistTitle(this.selected.name()), 50) + " · " + Component.translatable("yunyy.playlist.song_suffix", this.selected.tracks().size()).getString(), this.width / 2, 25, -10036753);
      }

      graphics.centeredText(this.font, this.status, this.width / 2, this.selected == null ? 30 : this.height - 11, this.statusColor);
      if (this.selected != null) {
         int pages = Math.max(1, (this.filtered.size() + TRACK_PAGE_SIZE - 1) / TRACK_PAGE_SIZE);
         graphics.centeredText(this.font, Component.translatable("yunyy.playlist.filter_result", this.filtered.size(), this.page + 1, pages).getString(), this.width / 2, 33, -7829368);
      }
      this.renderScrolled(graphics, mouseX, mouseY, delta);
   }

   public void onClose() {
      this.submitSerial++;
      ScreenRegistry.set(this.minecraft, this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }

   private static String trim(String text, int max) {
      return text.length() <= max ? text : text.substring(0, max - 1) + "…";
   }

   private static String format(long millis) {
      long s = Math.max(0L, millis / 1000L);
      return "%d:%02d".formatted(s / 60L, s % 60L);
   }

   private static void showMessage(String text) {
      Minecraft client = Minecraft.getInstance();
      client.execute(() -> ClientI18n.send(client.player, Component.literal(text)));
   }
}
