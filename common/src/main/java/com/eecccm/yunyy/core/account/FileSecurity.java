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

package com.eecccm.yunyy.core.account;

import com.eecccm.yunyy.core.log.TraceLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 凭证 / 密钥文件权限收紧（跨平台、纯 JDK，不引入原生依赖）。
 *
 * <p>覆盖策略（T-06）：</p>
 * <ul>
 *   <li>POSIX 系（Linux / macOS）：文件 {@code rw-------}、目录 {@code rwx------}，
 *       即仅属主可读可写；</li>
 *   <li>Windows：通过 {@link AclFileAttributeView} 把 ACL 收紧为仅当前用户（Owner）拥有
 *       读取 / 写入权限，移除 Users / Authenticated Users / Everyone 等其他主体条目，
 *       其他本地用户进程无法读取凭证与密钥；</li>
 *   <li>任一平台能力不可用（非管理员 / 文件不存在 / 属性视图缺失）时静默降级，
 *       不阻断模组启动；降级情况仅写开发者日志，不向玩家上屏。</li>
 * </ul>
 *
 * <p>OS 凭据存储（Windows Credential Manager / macOS Keychain / Linux Secret Service）
 * 需要原生桥接或第三方库，受"纯 JDK、不打包原生依赖"约束不强制接入；当前以
 * "本地 AES-GCM 加密落盘 + 文件 ACL 收紧"作为最小可行缓解，OS 密钥环作为后续可选增强项
 * 记录在安全报告残余风险中。</p>
 */
public final class FileSecurity {

   private FileSecurity() {
   }

   /** 收紧单个凭证 / 密钥文件权限（POSIX 0600；Windows 仅当前用户）。 */
   public static void hardenFile(Path file) {
      if (file == null || !Files.isRegularFile(file)) {
         return;
      }
      try {
         Files.setPosixFilePermissions(file,
               PosixFilePermissions.fromString("rw-------"));
      } catch (Exception ignored) {
         // 非 POSIX 平台，走 Windows ACL 分支。
      }
      hardenWindowsAcl(file, false);
   }

   /** 收紧配置目录权限（POSIX 0700；Windows 仅当前用户）。 */
   public static void hardenDirectory(Path dir) {
      if (dir == null || !Files.isDirectory(dir)) {
         return;
      }
      try {
         Files.setPosixFilePermissions(dir,
               PosixFilePermissions.fromString("rwx------"));
      } catch (Exception ignored) {
         // 非 POSIX 平台。
      }
      hardenWindowsAcl(dir, true);
   }

   /** Windows ACL 收紧：清空继承条目，仅保留当前用户的读 / 写（目录另加遍历 / 进入权限）。 */
   private static void hardenWindowsAcl(Path path, boolean directory) {
      AclFileAttributeView view;
      try {
         view = Files.getFileAttributeView(path, AclFileAttributeView.class);
      } catch (Exception unsupported) {
         return; // 非 Windows / 视图不可用。
      }
      if (view == null) {
         return;
      }
      try {
         UserPrincipal owner = view.getOwner();
         if (owner == null) {
            owner = Files.getOwner(path);
         }
         Set<AclEntryPermission> permissions = EnumSet.of(
               AclEntryPermission.READ_DATA,
               AclEntryPermission.WRITE_DATA,
               AclEntryPermission.READ_ATTRIBUTES,
               AclEntryPermission.WRITE_ATTRIBUTES,
               AclEntryPermission.READ_NAMED_ATTRS,
               AclEntryPermission.READ_ACL);
         if (directory) {
            permissions.add(AclEntryPermission.EXECUTE);
            permissions.add(AclEntryPermission.WRITE_NAMED_ATTRS);
            permissions.add(AclEntryPermission.DELETE);
         }
         AclEntry entry = AclEntry.newBuilder()
               .setType(AclEntryType.ALLOW)
               .setPrincipal(owner)
               .setPermissions(permissions)
               .setFlags(directory
                     ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                     : EnumSet.noneOf(AclEntryFlag.class))
               .build();
         view.setAcl(List.of(entry));
      } catch (Exception e) {
         TraceLog.debug("Windows 凭证文件 ACL 收紧失败（已降级为文件系统默认权限）：{}",
               e.getClass().getSimpleName());
      }
   }
}
