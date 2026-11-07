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

package com.eecccm.yunyy.core.platform.netease;

import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.core.account.QrFlow;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.platform.VendorGateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云平台网关：实现 {@link VendorGateway}，委托 {@link AccountSessionStore} 引擎。
 */
public final class NeteaseGateway implements VendorGateway {

   @Override
   public MusicVendor vendor() {
      return MusicVendor.NETEASE;
   }

   @Override
   public boolean authenticated() {
      return AccountSessionStore.hasSession();
   }

   @Override
   public String accountName() {
      return AccountSessionStore.displayName();
   }

   @Override
   public CompletableFuture<List<SongEntry>> search(String keyword, int page) {
      return AccountSessionStore.searchSongs(keyword, page).thenApply(list ->
            list.stream().map(t -> new SongEntry(MusicVendor.NETEASE, t.id(), t.title(),
                  t.artist(), t.duration(), t.coverUrl(), 0L)).toList());
   }

   @Override
   public CompletableFuture<List<PlaylistFolder>> fetchPlaylists() {
      return AccountSessionStore.fetchPlaylists().thenApply(list ->
            list.stream().map(p -> new PlaylistFolder(p.id(), p.name(), p.trackCount())).toList());
   }

   @Override
   public CompletableFuture<PlaylistContents> fetchPlaylistSongs(String folderId) {
      return AccountSessionStore.fetchPlaylistSongs(folderId).thenApply(pt -> {
         List<SongEntry> entries = pt.tracks().stream()
               .map(t -> new SongEntry(MusicVendor.NETEASE, t.id(), t.title(),
                     t.artist(), t.duration(), t.coverUrl(), 0L))
               .toList();
         return new PlaylistContents(pt.name(), entries);
      });
   }

   @Override
   public String resolveStreamUrl(String songId, String fallback) {
      return AccountSessionStore.resolveStreamUrl(songId, fallback);
   }

   @Override
   public CompletableFuture<String> fetchLyrics(String songId) {
      return AccountSessionStore.fetchLyrics(songId);
   }

   @Override
   public CompletableFuture<AccountProfile> verifyAccount() {
      return AccountSessionStore.verifyAccount()
            .thenApply(a -> AccountProfile.ok(a.nickname(), normalizeVip(a.vipType())));
   }

   @Override
   public CompletableFuture<AccountProfile> importCredential(String raw) {
      return AccountSessionStore.importCredential(raw)
            .thenApply(a -> AccountProfile.ok(a.nickname(), normalizeVip(a.vipType())));
   }

   /** 归一化会员标识：vipType>0 → "1"，=0 → "0"；note 仅承载中性标识，前缀由显示层按语言组织。 */
   private static String normalizeVip(int vipType) {
      return vipType > 0 ? "1" : "0";
   }

   @Override
   public void signOut() {
      AccountSessionStore.signOut();
   }

   @Override
   public QrInit createQr() throws Exception {
      QrFlow flow = new QrFlow();
      QrFlow.BeginResult data = flow.begin();
      return new QrInit(data.url(), data.url(), "");
   }

   @Override
   public QrPoll pollQr(String sessionKey) throws Exception {
      // 网易云二维码登录需保持同一会话；轮询在二维码界面内完成，此处不允许单独轮询
      throw new VendorException(VendorError.NOT_LOGGED_IN, MusicVendor.NETEASE.label);
   }
}
