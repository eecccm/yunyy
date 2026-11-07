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

package com.eecccm.yunyy.core.platform;

import com.eecccm.yunyy.core.error.QrStage;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.core.model.SongEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 每个音乐服务商的统一能力网关。所有网络方法均可异步调用，
 * 上层（UI / 队列 / 指令）只依赖本接口，不感知平台差异。
 */
public interface VendorGateway {

   /** 本网关对应的平台。 */
   MusicVendor vendor();

   /** 是否已持有有效登录凭证。 */
   boolean authenticated();

   /** 当前账号昵称（未登录时为空串）。 */
   String accountName();

   /** 搜索歌曲（页码从 1 开始）。 */
   CompletableFuture<List<SongEntry>> search(String keyword, int page);

   /** 拉取我的歌单列表；未登录时抛出 {@link IllegalStateException}。 */
   CompletableFuture<List<PlaylistFolder>> fetchPlaylists();

   /** 拉取指定歌单内的歌曲（已按添加时间从新到旧排序）。 */
   CompletableFuture<PlaylistContents> fetchPlaylistSongs(String folderId);

   /** 把歌曲解析为可直接播放的流地址；失败回退到给定兜底值。 */
   String resolveStreamUrl(String songId, String fallback);

   /** 获取歌词（LRC 文本）。 */
   CompletableFuture<String> fetchLyrics(String songId);

   /** 校验当前登录状态（网络往返）。 */
   CompletableFuture<AccountProfile> verifyAccount();

   /** 导入登录凭证（Cookie / Token），返回账号状态。 */
   CompletableFuture<AccountProfile> importCredential(String raw);

   /** 账号密码登录（酷狗/概念版支持；网易云无此接口则保持扫码）。 */
   default CompletableFuture<AccountProfile> signInByPassword(String account, String password) {
      return CompletableFuture.failedFuture(
            new UnsupportedOperationException(vendor().label + " 不支持账号密码登录"));
   }

   /** 退出登录。 */
   void signOut();

   /** 创建二维码登录会话。 */
   QrInit createQr() throws Exception;

   /** 轮询二维码登录状态。 */
   QrPoll pollQr(String sessionKey) throws Exception;

   /** 扫码页提示文案（默认用平台展示名）。 */
   default String appLabel() {
      return vendor().label;
   }

   /** 二维码创建结果：扫码 URL + 会话键 + 状态文案。 */
   record QrInit(String url, String sessionKey, String statusText) {
   }

   /**
    * 二维码轮询结果。阶段由 {@link QrStage} 表达；失败时携带中性错误码与结构化参数，
    * 由显示层映射语言模板，不再向界面返回中文状态句子。
    *
    * @param stage      轮询阶段
    * @param error      失败错误码；非失败阶段为 null
    * @param args       错误码结构化参数
    * @param loginToken 成功阶段换取凭证所需的 token / cookie；其余阶段为空串
    */
   record QrPoll(QrStage stage, VendorError error, Object[] args, String loginToken) {

      public static QrPoll waiting() {
         return new QrPoll(QrStage.WAIT_SCAN, null, new Object[0], "");
      }

      public static QrPoll scanned() {
         return new QrPoll(QrStage.SCANNED, null, new Object[0], "");
      }

      public static QrPoll success(String token) {
         return new QrPoll(QrStage.SUCCESS, null, new Object[0], token == null ? "" : token);
      }

      public static QrPoll expired() {
         return new QrPoll(QrStage.EXPIRED, null, new Object[0], "");
      }

      public static QrPoll failed(VendorError error, Object... args) {
         return new QrPoll(QrStage.FAILED, error == null ? VendorError.UNKNOWN : error,
               args == null ? new Object[0] : args, "");
      }
   }
}
