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

package com.eecccm.yunyy.core.platform.qqmusic;

import com.eecccm.yunyy.core.config.FeatureSwitch;
import com.eecccm.yunyy.core.config.RuntimeRegistry;
import com.eecccm.yunyy.core.crypto.SecretCipher;
import com.eecccm.yunyy.core.net.SafeHttpClient;
import com.eecccm.yunyy.core.account.MemoryWipe;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.model.AccountProfile;
import com.eecccm.yunyy.core.model.PlaylistContents;
import com.eecccm.yunyy.core.model.PlaylistFolder;
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.core.platform.MusicVendor;
import com.eecccm.yunyy.core.platform.VendorGateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QQMusicGateway implements VendorGateway {

   private static final String MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg";
   private static final String LYRIC_FCG = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
   private static final String HOME = "https://y.qq.com/";

   /** LRC 行：行首可连续多个时间标签，标签内支持 . 与 : 两种毫秒分隔符。 */
   private static final Pattern LRC_LINE =
         Pattern.compile("^((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?])+)(.*)$");
   private static final Pattern LRC_TAG =
         Pattern.compile("\\[(\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?)]");

   private static final int[] PART_1_INDEXES = {23, 14, 6, 36, 16, 40, 7, 19};
   private static final int[] PART_2_INDEXES = {16, 1, 32, 12, 19, 27, 8, 5};
   private static final int[] SCRAMBLE_VALUES = {89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
         186, 123, 120, 64, 242, 133, 143, 161, 121, 179};
   private static final String GUID = randomHex(32);

   private volatile String rawCookie = "";
   private volatile String uin = "";
   private volatile String qmKeyst = "";
   private volatile String qqmusicKey = "";
   private volatile String playbackKey = "";
   private volatile String pSkey = "";
   private volatile String nickname = "";

   private volatile String qrSig = "";
   private volatile long qrToken = 0L;

   private final MusicVendor vendor;

   public QQMusicGateway(MusicVendor vendor) {
      this.vendor = vendor;
   }

   @Override
   public MusicVendor vendor() {
      return vendor;
   }

   @Override
   public boolean authenticated() {
      return !rawCookie.isBlank() && !uin.isBlank()
            && (!qmKeyst.isBlank() || !qqmusicKey.isBlank());
   }

   @Override
   public String accountName() {
      return nickname == null ? "" : nickname;
   }

   @Override
   public CompletableFuture<List<SongEntry>> search(String keyword, int page) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            JsonObject body = new JsonObject();
            JsonObject inner = new JsonObject();
            inner.addProperty("module", "music.search.SearchCgiService");
            inner.addProperty("method", "DoSearchForQQMusicDesktop");
            JsonObject param = new JsonObject();
            param.addProperty("search_type", 0);
            param.addProperty("query", keyword);
            param.addProperty("page_num", page <= 0 ? 1 : page);
            param.addProperty("num_per_page", 20);
            inner.add("param", param);

            body.add("music.search.SearchCgiService", inner);

            String bodyStr = body.toString();
            HttpRequest.Builder b = SafeHttpClient.builder(MUSICU + "?sign=" + zzcSign(bodyStr));
            b.header("Referer", HOME);
            b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofString(bodyStr));
            HttpResponse<String> resp = SafeHttpClient.send(b.build());
            if (resp.statusCode() != 200) {
               throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject svc = root.has("music.search.SearchCgiService")
                  && root.get("music.search.SearchCgiService").isJsonObject()
                  ? root.getAsJsonObject("music.search.SearchCgiService") : null;
            if (svc == null) {
               throw new VendorException(VendorError.SEARCH_FAILED, vendor.label);
            }
            JsonObject data = svc.has("data") && svc.get("data").isJsonObject()
                  ? svc.getAsJsonObject("data") : null;
            JsonObject bodyObj = data != null && data.has("body") && data.get("body").isJsonObject()
                  ? data.getAsJsonObject("body") : null;
            JsonObject song = bodyObj != null && bodyObj.has("song") && bodyObj.get("song").isJsonObject()
                  ? bodyObj.getAsJsonObject("song") : null;
            JsonArray list = song != null && song.has("list") && song.get("list").isJsonArray()
                  ? song.getAsJsonArray("list") : null;

            List<SongEntry> tracks = new ArrayList<>();
            if (list != null) {
               for (JsonElement e : list) {
                  if (tracks.size() >= 20 || !e.isJsonObject()) {
                     break;
                  }
                  JsonObject s = e.getAsJsonObject();
                  String mid = songIdOf(s);
                  if (mid.isBlank()) {
                     continue;
                  }
                  tracks.add(new SongEntry(vendor, mid, titleOf(s), artistOf(s),
                        durationOf(s), qqCover(s), addTimeOf(s)));
               }
            }
            return tracks;
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.SEARCH_FAILED, e, vendor.label);
         }
      });
   }

   @Override
   public CompletableFuture<List<PlaylistFolder>> fetchPlaylists() {
      return CompletableFuture.supplyAsync(() -> {
         requireLogin();
         try {
            JsonObject comm = buildComm();
            JsonObject req = new JsonObject();
            req.add("comm", comm);
            JsonObject req0 = new JsonObject();
            req0.addProperty("module", "music.musicasset.PlaylistBaseRead");
            req0.addProperty("method", "GetPlaylistList");
            JsonObject p = new JsonObject();
            p.addProperty("uin", uin);
            p.addProperty("ownerUin", uin);
            p.addProperty("start", 0);
            p.addProperty("size", 30);
            p.addProperty("dirname", "");
            p.addProperty("order", 1);
            p.addProperty("resType", 1);
            req0.add("param", p);
            req.add("req_0", req0);

            JsonObject data = musicu(req.toString());
            JsonObject r0 = data.has("req_0") && data.get("req_0").isJsonObject()
                  ? data.getAsJsonObject("req_0") : null;
            JsonObject r0d = r0 != null && r0.has("data") && r0.get("data").isJsonObject()
                  ? r0.getAsJsonObject("data") : null;
            JsonArray dirs = r0d != null && r0d.has("dirlist") && r0d.get("dirlist").isJsonArray()
                  ? r0d.getAsJsonArray("dirlist") : null;
            List<PlaylistFolder> out = new ArrayList<>();
            if (dirs != null) {
               for (JsonElement e : dirs) {
                  if (!e.isJsonObject()) {
                     continue;
                  }
                  JsonObject d = e.getAsJsonObject();
                  String id = str(d, "dirid");
                  if (id.isBlank()) {
                     id = str(d, "dissid");
                  }
                  if (id.isBlank()) {
                     id = str(d, "tid");
                  }
                  if (id.isBlank()) {
                     continue;
                  }
                  String folderName = str(d, "dirname");
                  if (folderName.isBlank()) {
                     folderName = str(d, "name");
                  }
                  int count = (int) numOf(d, "songnum");
                  if (count <= 0) {
                     count = (int) numOf(d, "song_count");
                  }
                  if (count <= 0) {
                     count = (int) numOf(d, "songNum");
                  }
                  out.add(new PlaylistFolder(id, folderName, count));
               }
            }
            return out;
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_LOAD_FAILED, e, vendor.label);
         }
      });
   }

   @Override
   public CompletableFuture<PlaylistContents> fetchPlaylistSongs(String folderId) {
      return CompletableFuture.supplyAsync(() -> {
         requireLogin();
         if (folderId == null || folderId.isBlank()) {
            throw new VendorException(VendorError.PLAYLIST_ID_INVALID, vendor.label);
         }
         try {
            JsonObject comm = buildComm();
            JsonObject req = new JsonObject();
            req.add("comm", comm);
            JsonObject req0 = new JsonObject();
            req0.addProperty("module", "music.musicasset.PlaylistDetailRead");
            req0.addProperty("method", "GetPlaylistDetail");
            JsonObject p = new JsonObject();
            p.addProperty("uin", uin);
            p.addProperty("dirid", folderId);
            p.addProperty("start", 0);
            p.addProperty("size", 100);
            p.addProperty("songlist", 1);
            req0.add("param", p);
            req.add("req_0", req0);

            JsonObject data = musicu(req.toString());
            JsonObject r0 = data.has("req_0") && data.get("req_0").isJsonObject()
                  ? data.getAsJsonObject("req_0") : null;
            JsonObject r0d = r0 != null && r0.has("data") && r0.get("data").isJsonObject()
                  ? r0.getAsJsonObject("data") : null;
            JsonArray songs = r0d != null && r0d.has("songlist") && r0d.get("songlist").isJsonArray()
                  ? r0d.getAsJsonArray("songlist") : null;
            List<SongEntry> tracks = new ArrayList<>();
            if (songs != null) {
               for (JsonElement e : songs) {
                  if (!e.isJsonObject()) {
                     continue;
                  }
                  JsonObject s = e.getAsJsonObject();
                  JsonObject songInfo = s.has("songinfo") && s.get("songinfo").isJsonObject()
                        ? s.getAsJsonObject("songinfo") : s;
                  String mid = songIdOf(songInfo);
                  if (mid.isBlank()) {
                     mid = songIdOf(s);
                  }
                  if (mid.isBlank()) {
                     continue;
                  }
                  long added = addTimeOf(s);
                  if (added <= 0L) {
                     added = addTimeOf(songInfo);
                  }
                  tracks.add(new SongEntry(vendor, mid, titleOf(songInfo), artistOf(songInfo),
                        durationOf(songInfo), qqCover(songInfo), added));
               }
            }
            boolean hasTime = false;
            for (SongEntry t : tracks) {
               if (t.addedAtMs() > 0L) {
                  hasTime = true;
                  break;
               }
            }
            if (hasTime) {
               tracks.sort((a, b) -> Long.compare(b.addedAtMs(), a.addedAtMs()));
            }
            String name = r0d != null ? str(r0d, "dirname") : "";
            if (name.isBlank() && r0d != null) {
               name = str(r0d, "name");
            }
            if (name.isBlank()) {
               name = "QQ音乐歌单";
            }
            return new PlaylistContents(name, tracks);
         } catch (VendorException e) {
            throw e;
         } catch (Exception e) {
            throw new VendorException(VendorError.PLAYLIST_TRACKS_FAILED, e, vendor.label);
         }
      });
   }

   @Override
   public String resolveStreamUrl(String songId, String fallback) {
      if (songId == null || songId.isBlank()) {
         return fallback;
      }
      try {

         JsonObject comm = buildComm();
         JsonObject req = new JsonObject();
         req.add("comm", comm);
         JsonObject req0 = new JsonObject();
         req0.addProperty("module", "vkey.GetVkeyServer");
         req0.addProperty("method", "CgiGetVkey");
         JsonObject p = new JsonObject();
         JsonArray filenames = new JsonArray();
         JsonArray mids = new JsonArray();
         JsonArray types = new JsonArray();
         for (String prefix : new String[]{"M800", "M500", "C400", "M300"}) {
            String ext = prefix.startsWith("C") ? ".m4a" : (prefix.startsWith("M800") ? ".flac" : ".mp3");
            filenames.add(prefix + songId + ext);
            mids.add(songId);
            types.add(0);
         }
         p.add("filename", filenames);
         p.add("songmid", mids);
         p.add("songtype", types);
         p.addProperty("guid", GUID);
         p.addProperty("uin", uin);
         p.addProperty("loginflag", authenticated() ? 1 : 0);
         p.addProperty("platform", "20");
         req0.add("param", p);
         req.add("req_0", req0);

         JsonObject data = musicu(req.toString());
         JsonObject r0 = data.has("req_0") && data.get("req_0").isJsonObject()
               ? data.getAsJsonObject("req_0") : null;
         JsonObject r0d = r0 != null && r0.has("data") && r0.get("data").isJsonObject()
               ? r0.getAsJsonObject("data") : null;
         if (r0d == null) {
            return fallback;
         }
         JsonArray sip = r0d.has("sip") && r0d.get("sip").isJsonArray() ? r0d.getAsJsonArray("sip") : null;
         JsonArray info = r0d.has("midurlinfo") && r0d.get("midurlinfo").isJsonArray()
               ? r0d.getAsJsonArray("midurlinfo") : null;
         if (sip == null || sip.size() == 0 || info == null) {
            return fallback;
         }
         String base = sip.get(0).getAsString();
         for (JsonElement e : info) {
            if (!e.isJsonObject()) {
               continue;
            }
            String purl = str(e.getAsJsonObject(), "purl");
            if (!purl.isBlank() && !"null".equalsIgnoreCase(purl)) {
               return base + purl;
            }
         }
         return fallback;
      } catch (Exception e) {
         TraceLog.warn("{} 解析播放地址失败：{}", vendor.label, e.getMessage());
         return fallback;
      }
   }

   @Override
   public CompletableFuture<String> fetchLyrics(String songId) {
      return CompletableFuture.supplyAsync(() -> {
         if (songId == null || songId.isBlank()) {
            return "";
         }
         LyricBundle bundle = lyricBundle(songId);
         if (bundle.lyric().isBlank()) {
            return "";
         }
         LyricBundle filled = fillAux(bundle);
         return mergeLyrics(filled.lyric(), filled.trans(), filled.roma());
      });
   }

   /** 原生歌词接口：一次取回原文、翻译、音译；主歌词缺失时退回旧版 fcgi。 */
   private LyricBundle lyricBundle(String songId) {
      try {
         JsonObject req0 = new JsonObject();
         req0.addProperty("module", "music.musichallSong.PlayLyricInfo");
         req0.addProperty("method", "GetPlayLyricInfo");
         JsonObject p = new JsonObject();
         p.addProperty("songMID", songId);
         p.addProperty("songID", 0);
         p.addProperty("type", 0);
         p.addProperty("trans", 1);
         p.addProperty("roma", 1);
         req0.add("param", p);
         JsonObject req = new JsonObject();
         req.add("comm", buildComm());
         req.add("req_0", req0);

         JsonObject data = musicu(req.toString());
         JsonObject r0 = data.has("req_0") && data.get("req_0").isJsonObject()
               ? data.getAsJsonObject("req_0") : null;
         JsonObject r0d = r0 != null && r0.has("data") && r0.get("data").isJsonObject()
               ? r0.getAsJsonObject("data") : null;
         if (r0d != null) {
            LyricBundle bundle = new LyricBundle(lyricText(r0d, "lyric", "lrc"),
                  lyricText(r0d, "trans"), lyricText(r0d, "roma"), !str(r0d, "roma").isBlank());
            if (!bundle.lyric().isBlank()) {
               return bundle;
            }
         }
      } catch (Exception e) {
         TraceLog.warn("{} 歌词获取失败：{}", vendor.label, e.getMessage());
      }
      return lyricBundleLegacy(songId);
   }

   private LyricBundle lyricBundleLegacy(String songId) {
      try {
         String url = LYRIC_FCG + "?songmid=" + SecretCipher.urlEncode(songId)
               + "&g_tk=" + gTk() + "&format=json&loginflag=1&nobase64=0";
         HttpRequest.Builder b = SafeHttpClient.builder(url);
         b.header("Referer", HOME);
         b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
         b.header("Cookie", cookieHeader());
         HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
         if (resp.statusCode() != 200) {
            return new LyricBundle("", "", "", false);
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         if (numOf(root, "code") != 0) {
            return new LyricBundle("", "", "", false);
         }
         return new LyricBundle(lyricText(root, "lyric"), lyricText(root, "trans"),
               lyricText(root, "roma"), !str(root, "roma").isBlank());
      } catch (Exception e) {
         return new LyricBundle("", "", "", false);
      }
   }

   /** 补齐缺失的翻译/音译：音译字段已下发却解不出（加密）时，按标题+歌手取同款字段。 */
   private static LyricBundle fillAux(LyricBundle bundle) {
      if (!bundle.romaSeen()) {
         return bundle;
      }
      boolean needTrans = FeatureSwitch.lyricTranslation && timedLines(bundle.trans()).isEmpty();
      boolean needRoma = FeatureSwitch.lyricRomaji && timedLines(bundle.roma()).isEmpty();
      if (!needTrans && !needRoma) {
         return bundle;
      }
      String[] info = RuntimeRegistry.currentSongInfo();
      if (info == null || info.length == 0 || info[0] == null || info[0].isBlank()) {
         return bundle;
      }
      String artist = info.length > 1 && info[1] != null ? info[1] : "";
      String trans = bundle.trans();
      String roma = bundle.roma();
      String[] extra = auxLyrics(info[0], artist);
      if (needTrans && trans.isBlank()) {
         trans = extra[0];
      }
      if (needRoma && roma.isBlank()) {
         roma = extra[1];
      }
      return new LyricBundle(bundle.lyric(), trans, roma, true);
   }

   /** 按标题+歌手取首个匹配歌曲的翻译与音译，取不到返回空串。 */
   private static String[] auxLyrics(String title, String artist) {
      String[] out = { "", "" };
      try {
         String query = SecretCipher.urlEncode(artist.isBlank() ? title : title + " " + artist);
         HttpRequest.Builder b = SafeHttpClient.builder(
               "https://music.163.com/api/search/get/web?s=" + query + "&type=1&limit=3");
         b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0");
         b.header("Referer", "https://music.163.com/");
         HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
         if (resp.statusCode() != 200) {
            return out;
         }
         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         JsonObject result = root.has("result") && root.get("result").isJsonObject()
               ? root.getAsJsonObject("result") : null;
         if (result == null || !result.has("songs") || !result.get("songs").isJsonArray()) {
            return out;
         }
         String id = "";
         for (JsonElement e : result.getAsJsonArray("songs")) {
            if (e.isJsonObject()) {
               id = str(e.getAsJsonObject(), "id");
               if (!id.isBlank()) {
                  break;
               }
            }
         }
         if (id.isBlank()) {
            return out;
         }
         HttpRequest.Builder lb = SafeHttpClient.builder(
               "https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1&rv=-1");
         lb.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0");
         lb.header("Referer", "https://music.163.com/");
         HttpResponse<String> ly = SafeHttpClient.send(lb.GET().build());
         if (ly.statusCode() != 200) {
            return out;
         }
         JsonObject lroot = JsonParser.parseString(ly.body()).getAsJsonObject();
         out[0] = lyricBody(lroot, "tlyric");
         out[1] = lyricBody(lroot, "romalrc");
      } catch (Exception e) {
         TraceLog.warn("补充歌词失败：{}", e.getMessage());
      }
      return out;
   }

   /** 取 tlyric / romalrc 这类子对象里的歌词文本。 */
   private static String lyricBody(JsonObject root, String key) {
      if (root == null || !root.has(key) || !root.get(key).isJsonObject()) {
         return "";
      }
      String text = str(root.getAsJsonObject(key), "lyric");
      return text.contains("[") && text.contains(":") ? text : "";
   }

   /** musicu 与旧版 fcgi 共用的歌词载荷：原文、翻译、音译文本，及音译字段是否下发。 */
   private record LyricBundle(String lyric, String trans, String roma, boolean romaSeen) {
   }

   /** 依次尝试多个字段名，返回第一个能解出时间行的歌词文本；解不出视为缺失。 */
   private static String lyricText(JsonObject o, String... keys) {
      for (String key : keys) {
         String field = str(o, key);
         String text = decodeLyricField(field);
         if (text.contains("[") && text.contains(":")) {
            return text;
         }
         text = hexLyric(field);
         if (!text.isBlank()) {
            return text;
         }
      }
      return "";
   }

   /** 十六进制载荷转文本（音译字段按 hex 下发）；非纯 hex 或解不出时间行返回空串。 */
   private static String hexLyric(String field) {
      if (field.isEmpty() || (field.length() & 1) != 0) {
         return "";
      }
      for (int i = 0; i < field.length(); i++) {
         if (Character.digit(field.charAt(i), 16) < 0) {
            return "";
         }
      }
      try {
         String text = new String(SecretCipher.hexToBytes(field), StandardCharsets.UTF_8);
         return text.contains("[") && text.contains(":") ? text : "";
      } catch (Exception e) {
         return "";
      }
   }

   /** 歌词合并：原文行序不变，按时间戳并入音译与翻译（音译\u0001原文\u0001翻译，空段保留）。 */
   private static String mergeLyrics(String lrc, String transLrc, String romaLrc) {
      boolean wantTrans = FeatureSwitch.lyricTranslation;
      boolean wantRoma = FeatureSwitch.lyricRomaji;
      if (!wantTrans && !wantRoma) {
         return lrc;
      }
      Map<Long, String> trans = timedLines(transLrc);
      Map<Long, String> roma = timedLines(romaLrc);
      if (trans.isEmpty() && roma.isEmpty()) {
         return lrc;
      }
      StringBuilder sb = new StringBuilder();
      for (String raw : lrc.split("\\R")) {
         Matcher head = LRC_LINE.matcher(raw.trim());
         if (!head.matches()) {
            sb.append(raw).append('\n');
            continue;
         }
         String origin = head.group(2).trim();
         Matcher tag = LRC_TAG.matcher(head.group(1));
         while (tag.find()) {
            long ms = lyricMillis(tag.group(1));
            String r = wantRoma && ms >= 0 ? nearestLine(roma, ms, origin) : "";
            String t = wantTrans && ms >= 0 ? nearestLine(trans, ms, origin) : "";
            sb.append('[').append(tag.group(1)).append(']');
            if (r.isBlank() && t.isBlank()) {
               sb.append(origin).append('\n');
            } else if (t.isBlank()) {
               sb.append(r).append('\u0001').append(origin).append('\u0001').append('\n');
            } else {
               sb.append(r).append('\u0001').append(origin).append('\u0001').append(t).append('\n');
            }
         }
      }
      String merged = sb.toString();
      return merged.isBlank() ? lrc : merged;
   }

   /** 时间标签（mm:ss(.xx) 或 mm:ss:xxx）转毫秒，非法返回 -1。 */
   private static long lyricMillis(String tag) {
      try {
         int colon = tag.indexOf(':');
         if (colon < 0) {
            return -1L;
         }
         long minutes = Long.parseLong(tag.substring(0, colon).trim());
         String rest = tag.substring(colon + 1).trim();
         int dot = rest.indexOf('.');
         if (dot < 0) {
            dot = rest.indexOf(':');
         }
         if (dot < 0) {
            return minutes * 60000L + Long.parseLong(rest) * 1000L;
         }
         long seconds = Long.parseLong(rest.substring(0, dot).trim());
         String frac = rest.substring(dot + 1).trim();
         long value = Long.parseLong(frac);
         long millis = frac.length() == 1 ? value * 100L : frac.length() == 2 ? value * 10L : value;
         return minutes * 60000L + seconds * 1000L + millis;
      } catch (Exception e) {
         return -1L;
      }
   }

   /** 时间行归集：同一毫秒后出现的文本覆盖先前文本，保持时间升序。 */
   private static Map<Long, String> timedLines(String text) {
      Map<Long, String> lines = new TreeMap<>();
      if (text == null || text.isBlank()) {
         return lines;
      }
      for (String raw : text.split("\\R")) {
         Matcher head = LRC_LINE.matcher(raw.trim());
         if (!head.matches()) {
            continue;
         }
         String content = head.group(2).trim();
         if (content.isBlank() || content.equals("//")) {
            continue;
         }
         Matcher tag = LRC_TAG.matcher(head.group(1));
         while (tag.find()) {
            long ms = lyricMillis(tag.group(1));
            if (ms >= 0) {
               lines.put(ms, content);
            }
         }
      }
      return lines;
   }

   /** 在 ±150ms 容差内取最近的一条配文；与原文相同视为无配文。 */
   private static String nearestLine(Map<Long, String> lines, long ms, String origin) {
      String best = "";
      long bestDiff = 150L;
      for (Map.Entry<Long, String> e : lines.entrySet()) {
         long diff = Math.abs(e.getKey() - ms);
         if (diff <= bestDiff) {
            bestDiff = diff;
            best = e.getValue();
         }
      }
      return best.equals(origin) ? "" : best;
   }

   private static String decodeLyricField(String field) {
      if (field.isBlank()) {
         return "";
      }
      byte[] raw;
      try {
         raw = Base64.getDecoder().decode(field);
      } catch (Exception e) {

         try {
            raw = SecretCipher.hexToBytes(field);
         } catch (Exception e2) {
            return field;
         }
      }
      String text = new String(raw, StandardCharsets.UTF_8);
      if (text.contains("[") && text.contains(":")) {
         return text;
      }

      String qrc = tryQrcInflate(raw);
      if (!qrc.isBlank()) {
         return qrc;
      }
      return text;
   }

   private static String tryQrcInflate(byte[] raw) {
      try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(raw);
           java.util.zip.InflaterInputStream iis =
                 new java.util.zip.InflaterInputStream(bais, new java.util.zip.Inflater(true))) {
         byte[] buf = new byte[8192];
         StringBuilder sb = new StringBuilder();
         int n;
         while ((n = iis.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
         }
         String s = sb.toString();
         if (s.contains("[") && s.contains(":")) {
            return s;
         }
      } catch (Exception ignored) {
      }
      return "";
   }

   @Override
   public CompletableFuture<AccountProfile> verifyAccount() {
      return CompletableFuture.supplyAsync(() -> {
         if (!authenticated()) {
            return AccountProfile.fail(VendorError.NOT_LOGGED_IN, vendor.label);
         }
         try {
            JsonObject comm = buildComm();
            JsonObject req = new JsonObject();
            req.add("comm", comm);
            JsonObject req0 = new JsonObject();
            req0.addProperty("module", "music.homepage.PersonalHomepage");
            req0.addProperty("method", "GetHomepage");
            JsonObject p = new JsonObject();
            p.addProperty("hostUin", uin);
            req0.add("param", p);
            req.add("req_0", req0);

            JsonObject data = musicu(req.toString());
            JsonObject r0 = data.has("req_0") && data.get("req_0").isJsonObject()
                  ? data.getAsJsonObject("req_0") : null;
            JsonObject r0d = r0 != null && r0.has("data") && r0.get("data").isJsonObject()
                  ? r0.getAsJsonObject("data") : null;
            if (r0d != null) {
               String name = str(r0d, "name");
               if (!name.isBlank()) {
                  this.nickname = name;
                  return AccountProfile.ok(name, "");
               }
            }

            return AccountProfile.ok(nickname.isBlank() ? uin : nickname, "");
         } catch (VendorException e) {
            return AccountProfile.fail(e.code(), e.args());
         } catch (Exception e) {
            TraceLog.warn("{} 账号校验失败：{}", vendor.label, e.getMessage());
            return AccountProfile.ok(nickname.isBlank() ? uin : nickname, "");
         }
      });
   }

   @Override
   public CompletableFuture<AccountProfile> importCredential(String raw) {
      return CompletableFuture.supplyAsync(() -> {
         String cookie = raw == null ? "" : raw.trim();
         if (cookie.isBlank()) {
            return AccountProfile.fail(VendorError.CREDENTIAL_INVALID, vendor.label);
         }
         Map<String, String> kv = parseCookie(cookie);
         String u = firstNonBlank(kv, "uin", "wxuin");
         String k1 = firstNonBlank(kv, "qm_keyst", "qqmusic_key");
         if (u.isBlank() || k1.isBlank()) {
            return AccountProfile.fail(VendorError.COOKIE_MISSING_FIELD, vendor.label);
         }
         this.uin = u;
         this.qmKeyst = str(kv, "qm_keyst");
         this.qqmusicKey = firstNonBlank(kv, "qqmusic_key", "qm_keyst");
         this.playbackKey = str(kv, "qqCookiePlaybackKey");
         this.pSkey = firstNonBlank(kv, "p_skey", "p_uin");
         this.rawCookie = cookie;

         AccountProfile checked = verifyAccount().join();
         return checked.active() ? checked : AccountProfile.ok(nickname.isBlank() ? uin : nickname, "");
      });
   }

   @Override
   public void signOut() {
      String old = rawCookie;
      rawCookie = "";
      uin = "";
      qmKeyst = "";
      qqmusicKey = "";
      playbackKey = "";
      pSkey = "";
      nickname = "";
      qrSig = "";
      qrToken = 0L;
      if (old != null && !old.isBlank()) {
         MemoryWipe.wipe(old.toCharArray());
      }
   }

   public String cookieSnapshot() {
      return rawCookie;
   }

   public void restoreSession(String raw) {
      String cookie = raw == null ? "" : raw.trim();
      if (cookie.isBlank()) {
         return;
      }
      Map<String, String> kv = parseCookie(cookie);
      this.uin = firstNonBlank(kv, "uin", "wxuin");
      this.qmKeyst = str(kv, "qm_keyst");
      this.qqmusicKey = firstNonBlank(kv, "qqmusic_key", "qm_keyst");
      this.playbackKey = str(kv, "qqCookiePlaybackKey");
      this.pSkey = firstNonBlank(kv, "p_skey", "p_uin");
      this.rawCookie = cookie;
   }

   @Override
   public CompletableFuture<AccountProfile> signInByPassword(String account, String password) {
      return CompletableFuture.failedFuture(
            new UnsupportedOperationException(vendor.label + " 不支持账号密码登录，请使用扫码或导入 Cookie"));
   }

   @Override
   public QrInit createQr() throws Exception {
      String url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&daid=383"
            + "&ptqrtoken=0&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
            + "&js_ver=240119&js_type=1&login_sig=&pt_uistyle=40&aid=716027609&daid=383";
      HttpRequest.Builder b = SafeHttpClient.builder(url);
      b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");

      HttpResponse<byte[]> resp = SafeHttpClient.downloadBytes(b.GET().build(), SafeHttpClient.DOWNLOAD_BODY_BYTES);
      if (resp.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }

      String qrsig = "";
      for (String c : resp.headers().allValues("Set-Cookie")) {
         int i = c.indexOf("qrsig=");
         if (i >= 0) {
            int end = c.indexOf(';', i);
            qrsig = c.substring(i + 6, end < 0 ? c.length() : end);
         }
      }
      if (qrsig.isBlank()) {
         throw new VendorException(VendorError.QR_KEY_MISSING, vendor.label);
      }
      this.qrSig = qrsig;
      this.qrToken = hash33(qrsig);
      String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(resp.body());
      return new QrInit(dataUri, "qq", "");
   }

   @Override
   public QrPoll pollQr(String sessionKey) throws Exception {
      if (qrSig.isBlank()) {
         return QrPoll.failed(VendorError.QR_SESSION_MISSING, vendor.label);
      }
      long ts = System.currentTimeMillis();
      String url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1="
            + SecretCipher.urlEncode("https://y.qq.com/")
            + "&ptqrtoken=" + qrToken
            + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
            + "&action=0-0-" + ts + "&js_ver=240119&js_type=1&login_sig=&pt_uistyle=40"
            + "&aid=716027609&daid=383";
      HttpRequest.Builder b = SafeHttpClient.builder(url);
      b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
      b.header("Referer", "https://xui.ptlogin2.qq.com/");
      b.header("Cookie", "qrsig=" + qrSig);
      HttpResponse<String> resp = SafeHttpClient.send(b.GET().build());
      if (resp.statusCode() != 200) {
         return QrPoll.failed(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      String body = resp.body();

      String status = extractPtuiCbArg(body, 0);
      String third = extractPtuiCbArg(body, 2);
      if ("登录成功".equals(status) || "Login Success".equalsIgnoreCase(status)) {

         String cookie = chaseQmKeyst(third);
         if (cookie.isBlank()) {
            return QrPoll.failed(VendorError.CREDENTIAL_INVALID, vendor.label);
         }
         return QrPoll.success(cookie);
      } else if ("已失效".equals(status)) {
         return QrPoll.expired();
      } else if ("已扫描".equals(status) || "扫描过".equals(status) || "Waiting for scan".equalsIgnoreCase(status)) {
         return QrPoll.scanned();
      }
      return QrPoll.waiting();
   }

   /**
    * 扫码换票专用直连客户端：单例复用（{@link HttpClient} 自带连接池与 selector 线程，
    * 逐次扫码会话各建一个会持续堆积线程），重定向手工逐跳跟踪，只收集同域 Set-Cookie。
    */
   private static final HttpClient CHASE_CLIENT = HttpClient.newBuilder()
         .connectTimeout(java.time.Duration.ofSeconds(15))
         .followRedirects(HttpClient.Redirect.NEVER)
         .build();

   private String chaseQmKeyst(String u1) {
      if (u1 == null || u1.isBlank()) {
         return "";
      }
      try {
         HttpClient client = CHASE_CLIENT;
         String current = u1;
         Map<String, String> cookies = new java.util.HashMap<>();
         for (int hop = 0; hop < 6; hop++) {
            URI uri = URI.create(current);
            if (!hostAllowed(uri.getHost())) {
               break;
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                  .timeout(java.time.Duration.ofSeconds(15))
                  .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                  .header("Referer", HOME);
            if (!cookies.isEmpty()) {
               rb.header("Cookie", joinCookies(cookies));
            }
            HttpResponse<String> r = client.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            for (String c : r.headers().allValues("Set-Cookie")) {
               int eq = c.indexOf('=');
               if (eq > 0) {
                  int sc = c.indexOf(';', eq);
                  cookies.put(c.substring(0, eq).trim(), c.substring(eq + 1, sc < 0 ? c.length() : sc));
               }
            }
            if (r.statusCode() >= 300 && r.statusCode() < 400) {
               String loc = r.headers().firstValue("Location").orElse(null);
               if (loc == null || loc.isBlank()) {
                  break;
               }
               current = uri.resolve(loc).toString();
               continue;
            }
            break;
         }
         String u = firstNonBlank(cookies, "uin", "wxuin");
         String k = firstNonBlank(cookies, "qm_keyst", "qqmusic_key");
         if (u.isBlank() || k.isBlank()) {
            return "";
         }
         StringBuilder sb = new StringBuilder();
         sb.append("uin=").append(u);
         if (!cookies.getOrDefault("qm_keyst", "").isBlank()) {
            sb.append("; qm_keyst=").append(cookies.get("qm_keyst"));
         }
         if (!cookies.getOrDefault("qqmusic_key", "").isBlank()) {
            sb.append("; qqmusic_key=").append(cookies.get("qqmusic_key"));
         }
         if (!cookies.getOrDefault("qqCookiePlaybackKey", "").isBlank()) {
            sb.append("; qqCookiePlaybackKey=").append(cookies.get("qqCookiePlaybackKey"));
         }
         return sb.toString();
      } catch (Exception e) {
         TraceLog.warn("{} 扫码换票失败：{}", vendor.label, e.getMessage());
         return "";
      }
   }

   private static boolean hostAllowed(String host) {
      if (host == null) {
         return false;
      }
      String h = host.toLowerCase();
      return h.endsWith(".qq.com") || h.equals("qq.com");
   }

   private static String joinCookies(Map<String, String> cookies) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Map.Entry<String, String> e : cookies.entrySet()) {
         if (!first) {
            sb.append("; ");
         }
         sb.append(e.getKey()).append('=').append(e.getValue());
         first = false;
      }
      return sb.toString();
   }

   private JsonObject buildComm() {
      JsonObject comm = new JsonObject();
      comm.addProperty("ct", 24);
      comm.addProperty("cv", 4747474);
      comm.addProperty("platform", "yqq.json");
      comm.addProperty("uin", uin.isBlank() ? "0" : uin);
      comm.addProperty("format", "json");
      comm.addProperty("notice", 0);
      return comm;
   }

   private JsonObject musicu(String bodyStr) throws Exception {
      HttpRequest.Builder b = SafeHttpClient.builder(MUSICU + "?sign=" + zzcSign(bodyStr));
      b.header("Referer", HOME);
      b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0");
      b.header("Content-Type", "application/json");
      b.header("Cookie", cookieHeader());
      b.POST(HttpRequest.BodyPublishers.ofString(bodyStr));
      HttpResponse<String> resp = SafeHttpClient.send(b.build());
      if (resp.statusCode() != 200) {
         throw new VendorException(VendorError.HTTP_STATUS, vendor.label, resp.statusCode());
      }
      return JsonParser.parseString(resp.body()).getAsJsonObject();
   }

   private static String zzcSign(String payload) {
      try {
         MessageDigest md = MessageDigest.getInstance("SHA-1");
         byte[] hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));

         char[] hex = new char[40];
         for (int i = 0; i < hash.length; i++) {
            hex[i * 2] = HEX[(hash[i] >>> 4) & 0xF];
            hex[i * 2 + 1] = HEX[hash[i] & 0xF];
         }
         String hexStr = new String(hex);
         String part1 = pick(hexStr, PART_1_INDEXES);
         String part2 = pick(hexStr, PART_2_INDEXES);
         byte[] part3 = new byte[20];
         for (int i = 0; i < 20; i++) {
            part3[i] = (byte) (hash[i] ^ (SCRAMBLE_VALUES[i] & 0xFF));
         }
         String b64 = Base64.getEncoder().encodeToString(part3);
         StringBuilder b64p = new StringBuilder();
         for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == '\\' || c == '/' || c == '+' || c == '=') {
               continue;
            }
            b64p.append(c);
         }
         return ("zzc" + part1 + b64p + part2).toLowerCase();
      } catch (Exception e) {
         throw new IllegalStateException("zzc_sign 失败", e);
      }
   }

   private static String pick(String hex, int[] indexes) {
      StringBuilder sb = new StringBuilder(indexes.length);
      for (int idx : indexes) {
         if (idx >= 0 && idx < hex.length()) {
            sb.append(hex.charAt(idx));
         }
      }
      return sb.toString();
   }

   private static long hash33(String s) {
      long h = 0;
      for (int i = 0; i < s.length(); i++) {
         h = (h * 33 + (s.charAt(i) & 0xFF)) & 0x7FFFFFFF;
      }
      return h;
   }

   private long gTk() {
      String src = pSkey.isBlank() ? (qmKeyst.isBlank() ? "" : qmKeyst) : pSkey;
      if (src.isBlank()) {
         return 5381L;
      }
      long h = 5381;
      for (int i = 0; i < src.length(); i++) {
         h = (h * 33 + (src.charAt(i) & 0xFF)) & 0x7FFFFFFF;
      }
      return h;
   }

   private void requireLogin() {
      if (!authenticated()) {
         throw new VendorException(VendorError.NOT_LOGGED_IN, vendor.label);
      }
   }

   private String cookieHeader() {
      if (rawCookie.isBlank()) {
         return "";
      }
      return rawCookie;
   }

   private static Map<String, String> parseCookie(String cookie) {
      Map<String, String> kv = new LinkedHashMap<>();
      for (String part : cookie.split(";")) {
         int eq = part.indexOf('=');
         if (eq > 0) {
            kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
         }
      }
      return kv;
   }

   private static String firstNonBlank(Map<String, String> kv, String... keys) {
      for (String k : keys) {
         String v = kv.get(k);
         if (v != null && !v.isBlank()) {
            return v;
         }
      }
      return "";
   }

   private static String str(Map<String, String> kv, String key) {
      String v = kv.get(key);
      return v == null ? "" : v;
   }

   private static String str(JsonObject o, String key) {
      if (o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
         return o.get(key).getAsString();
      }
      return "";
   }

   /** 数值字段读取：容忍数字、数字字符串、小数与空串/布尔等异形值，无法解析统一返回 0。 */
   private static long numOf(JsonObject o, String key) {
      if (o == null || !o.has(key)) {
         return 0L;
      }
      JsonElement e = o.get(key);
      if (e == null || !e.isJsonPrimitive()) {
         return 0L;
      }
      try {
         return e.getAsLong();
      } catch (Exception ignored) {
      }
      try {
         return Math.round(e.getAsDouble());
      } catch (Exception ignored) {
         return 0L;
      }
   }

   /** 歌曲唯一标识：songmid 优先，兜底 mid / strMediaMid。 */
   private static String songIdOf(JsonObject s) {
      if (s == null) {
         return "";
      }
      String id = str(s, "songmid");
      if (id.isBlank()) {
         id = str(s, "mid");
      }
      if (id.isBlank()) {
         id = str(s, "strMediaMid");
      }
      return id;
   }

   /** 歌曲名：songname → name → title → filename（来自 filename 时去扩展名）。 */
   private static String titleOf(JsonObject s) {
      if (s == null) {
         return "";
      }
      String title = str(s, "songname");
      if (title.isBlank()) {
         title = str(s, "name");
      }
      if (title.isBlank()) {
         title = str(s, "title");
      }
      if (title.isBlank()) {
         String filename = str(s, "filename");
         if (!filename.isBlank()) {
            int dot = filename.lastIndexOf('.');
            title = dot > 0 ? filename.substring(0, dot) : filename;
         }
      }
      return title;
   }

   /** 歌手：singer[].name → singer[].title，多歌手以 / 连接；兜底 singername。 */
   private static String artistOf(JsonObject s) {
      if (s == null) {
         return "";
      }
      StringBuilder sb = new StringBuilder();
      if (s.has("singer") && s.get("singer").isJsonArray()) {
         for (JsonElement se : s.getAsJsonArray("singer")) {
            if (!se.isJsonObject()) {
               continue;
            }
            JsonObject singer = se.getAsJsonObject();
            String n = str(singer, "name");
            if (n.isBlank()) {
               n = str(singer, "title");
            }
            if (!n.isBlank()) {
               if (sb.length() > 0) {
                  sb.append('/');
               }
               sb.append(n);
            }
         }
      }
      if (sb.length() == 0) {
         sb.append(str(s, "singername"));
      }
      return sb.toString();
   }

   /** 时长：timelen 优先（毫秒）；interval / duration 兜底，秒级自动转毫秒。 */
   private static long durationOf(JsonObject s) {
      if (s == null) {
         return 0L;
      }
      if (s.has("timelen") && s.get("timelen").isJsonPrimitive()) {
         long ms = numOf(s, "timelen");
         if (ms > 0L) {
            return ms;
         }
      }
      if (s.has("interval") && s.get("interval").isJsonPrimitive()) {
         long sec = numOf(s, "interval");
         return sec > 0L ? sec * 1000L : 0L;
      }
      if (s.has("duration") && s.get("duration").isJsonPrimitive()) {
         long dur = numOf(s, "duration");
         return dur > 0L && dur < 1000L ? dur * 1000L : dur;
      }
      return 0L;
   }

   /** 加入歌单时间（毫秒，0=未知）：addtime / add_time / uptime / createtime，秒级自动转毫秒。 */
   private static long addTimeOf(JsonObject s) {
      if (s == null) {
         return 0L;
      }
      for (String k : new String[]{"addtime", "add_time", "uptime", "createtime", "create_time"}) {
         if (s.has(k) && s.get(k).isJsonPrimitive()) {
            long v = numOf(s, k);
            if (v > 0L) {
               return v < 10_000_000_000L ? v * 1000L : v;
            }
         }
      }
      return 0L;
   }

   /** 封面：albummid / album.mid 拼 300x300 直链，兜底常见封面字段；归一化与白名单校验后返回。 */
   private static String qqCover(JsonObject s) {
      if (s == null) {
         return "";
      }
      String albumMid = str(s, "albummid");
      if (albumMid.isBlank() && s.has("album") && s.get("album").isJsonObject()) {
         albumMid = str(s.getAsJsonObject("album"), "mid");
      }
      if (!albumMid.isBlank()) {
         return normalizeCoverUrl("https://y.qq.com/music/photo_new/T002R300x300M000"
               + albumMid + ".jpg?max_age=2592000");
      }
      for (String key : new String[]{"cover", "pic", "img", "album_pic"}) {
         String raw = str(s, key);
         if (!raw.isBlank()) {
            return normalizeCoverUrl(raw);
         }
      }
      return "";
   }

   /** 封面 URL 归一化：{size} 替换、http 升级 https、仅放行 QQ 音乐可信域；非法返回空串。 */
   private static String normalizeCoverUrl(String raw) {
      if (raw == null || raw.isBlank()) {
         return "";
      }
      raw = raw.trim().replace("{size}", "120");
      if (raw.startsWith("http://")) {
         raw = "https://" + raw.substring(7);
      }
      try {
         URI uri = URI.create(raw);
         String host = uri.getHost();
         if (host == null) {
            return "";
         }
         String h = host.toLowerCase();
         boolean trusted = h.endsWith(".qq.com") || h.endsWith(".gtimg.cn");
         return trusted ? uri.toASCIIString() : "";
      } catch (Exception e) {
         return "";
      }
   }

   private static String randomHex(int len) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
         sb.append("0123456789abcdef".charAt(ThreadLocalRandom.current().nextInt(16)));
      }
      return sb.toString();
   }

   private static final char[] HEX = "0123456789ABCDEF".toCharArray();

   private static String extractPtuiCbArg(String text, int index) {
      if (text == null) {
         return "";
      }
      int start = text.indexOf("ptuiCB(");
      if (start < 0) {
         return "";
      }
      int open = start + "ptuiCB(".length();

      int depth = 0;
      int end = -1;
      for (int i = open; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '(') {
            depth++;
         } else if (c == ')') {
            depth--;
            if (depth < 0) {
               end = i;
               break;
            }
         }
      }
      if (end < 0) {
         return "";
      }
      String[] args = splitTopLevel(text.substring(open, end));
      if (index < 0 || index >= args.length) {
         return "";
      }
      String a = args[index].trim();
      if (a.length() >= 2 && a.charAt(0) == '\'' && a.charAt(a.length() - 1) == '\'') {
         return a.substring(1, a.length() - 1);
      }
      return a;
   }

   private static String[] splitTopLevel(String s) {
      List<String> out = new ArrayList<>();
      int depth = 0;
      StringBuilder cur = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if (c == '\'') {
            depth ^= 1;
            cur.append(c);
         } else if (c == ',' && depth == 0) {
            out.add(cur.toString());
            cur.setLength(0);
         } else {
            cur.append(c);
         }
      }
      out.add(cur.toString());
      return out.toArray(new String[0]);
   }
}
