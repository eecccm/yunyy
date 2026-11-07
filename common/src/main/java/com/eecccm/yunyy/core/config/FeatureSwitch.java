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

package com.eecccm.yunyy.core.config;

/**
 * 运行时歌词显示开关（翻译 / 音译）。
 *
 * <p>由设置界面切换、歌词服务读取；用进程内静态位而非配置对象，
 * 保证 HUD 渲染线程读到的是最新值且无需加锁。</p>
 */
public final class FeatureSwitch {

   /** 是否显示歌词翻译。 */
   public static volatile boolean lyricTranslation = true;

   /** 是否显示歌词音译（罗马音 / 拼音）。 */
   public static volatile boolean lyricRomaji = true;

   /**
    * 仅免费 / 试听模式（T-02）：开启后出站请求不携带 Cookie / token / dfid 等凭证，
    * 仅解析免费公开音源，避免账号权益被用于受风控限制的请求；默认关闭。
    */
   public static volatile boolean freeOnly = false;

   private FeatureSwitch() {
   }
}
