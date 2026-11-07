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
import com.eecccm.yunyy.core.config.ServerPolicy;

import com.eecccm.yunyy.core.config.ServerPolicy;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric 层权限判定（对应 1.2 ServerPolicy 中 MC 耦合部分，重构后从 common 拆出）。
 * <p>MC 26.2 权限模型：PermissionLevel 0=ALL 1=MODERATORS 2=GAMEMASTERS 3=ADMINS 4=OWNERS。</p>
 */
public final class AccessControl {
   private AccessControl() {
   }

   public static boolean isAdmin(ServerPlayer player) {
      return hasPermissionLevel(player, ServerPolicy.adminRank);
   }

   /** 客户端判断当前玩家是否为管理员（客户端与服务端共用同一配置文件）。 */
   public static boolean isAdminClient(LocalPlayer player) {
      return hasPermissionLevel(player, ServerPolicy.adminRank);
   }

   private static boolean hasPermissionLevel(Player player, int level) {
      if (player == null) {
         return false;
      }
      if (level <= 0) {
         return true;
      }
      PermissionLevel need = PermissionLevel.byId(level);
      Permission perm = switch (need) {
         case MODERATORS -> Permissions.COMMANDS_MODERATOR;
         case GAMEMASTERS -> Permissions.COMMANDS_GAMEMASTER;
         case ADMINS -> Permissions.COMMANDS_ADMIN;
         case OWNERS -> Permissions.COMMANDS_OWNER;
         default -> null;
      };
      return perm == null || player.permissions().hasPermission(perm);
   }
}
