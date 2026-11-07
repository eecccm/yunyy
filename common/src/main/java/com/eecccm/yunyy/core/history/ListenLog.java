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

package com.eecccm.yunyy.core.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.function.Function;

/**
 * 播放历史（栈式回溯）。
 *
 * <p>实现要点：容量上限 500，超出时淘汰最旧；{@link #advance} 在切歌时记录上一首；
 * {@link #rewind} 从队尾弹出上一首并把当前歌放回队首；{@link #reorder} 用于队列内
 * 上移/下移（仅相邻交换）。</p>
 *
 * @param <T> 歌曲条目类型
 */
public final class ListenLog<T> {

   private static final int CAPACITY = 500;

   private final ArrayDeque<T> trail = new ArrayDeque<>();
   private final Function<T, String> keyOf;

   public ListenLog(Function<T, String> keyOf) {
      this.keyOf = keyOf;
   }

   /** 是否存在可回溯的历史。 */
   public boolean canRecall() {
      return !trail.isEmpty();
   }

   public void wipe() {
      trail.clear();
   }

   /** 记录一首歌（追加到末尾，超容量淘汰最旧）。 */
   public void note(T song) {
      if (song == null) {
         return;
      }
      if (trail.size() == CAPACITY) {
         trail.removeFirst();
      }
      trail.addLast(song);
   }

   /** 切歌过渡：当前歌与下一首歌不同源时，把当前歌记入历史。 */
   public void advance(T current, T next) {
      if (current != null && !keyOf.apply(current).equals(keyOf.apply(next))) {
         note(current);
      }
   }

   /**
    * 取上一首：弹出一首历史，并把当前歌（若存在）放回队列头部。
    * 规则与旧行为保持一致：历史歌或当前歌已在队列中时可自由回溯；
    * 二者都不在队列且队列已满时拒绝回溯。
    *
    * @return 回溯到的歌曲；无历史返回 null
    */
   public T rewind(T current, Deque<T> queue, int cap) {
      T previous = trail.peekLast();
      if (previous == null) {
         return null;
      }
      boolean previousQueued = queue.stream().anyMatch(t -> keyOf.apply(t).equals(keyOf.apply(previous)));
      boolean currentQueued = current != null
            && queue.stream().anyMatch(t -> keyOf.apply(t).equals(keyOf.apply(current)));
      if (current != null && !currentQueued && !previousQueued && queue.size() >= cap) {
         return null;
      }
      trail.removeLast();
      queue.removeIf(t -> keyOf.apply(t).equals(keyOf.apply(previous))
            || (current != null && keyOf.apply(t).equals(keyOf.apply(current))));
      if (current != null) {
         queue.addFirst(current);
      }
      return previous;
   }

   /** 队列内相邻移动：把 index 位置的条目按 direction（±1）移动一格。 */
   public static <T> boolean reorder(Deque<T> queue, int index, int step) {
      int target = index + step;
      if (Math.abs(step) != 1 || index < 0 || index >= queue.size()
            || target < 0 || target >= queue.size()) {
         return false;
      }
      ArrayList<T> songs = new ArrayList<>(queue);
      Collections.swap(songs, index, target);
      queue.clear();
      queue.addAll(songs);
      return true;
   }
}
