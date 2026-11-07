# 云音乐（yunyy）

在《我的世界》里直接听歌的模组：支持网易云音乐、酷狗音乐（含酷狗音乐概念版）、QQ 音乐点歌、歌单与账号 VIP 状态，可全服同步聆听，也可自由独享。

- 模组 ID：`yunyy`
- 版本：1.0.0
- 作者：eecccm
- 协议：GPL-3.0（GNU 通用公共许可证 v3.0）
- 兼容加载器：Fabric / Forge / NeoForge

> 本模组由 eecccm 与 AI 协作开发，部分内容由 AI 辅助生成。

## 功能特性

- **多平台点歌**：网易云音乐、酷狗音乐（标准版与概念版共用一套接入，参数自动区分）、QQ 音乐，搜索即点即播；支持 `/music wyy 歌曲名`、`/music kg 歌曲名`、`/music qq 歌曲名` 指令点歌。
- **歌单导入**：登录账号后可直接拉取自己的歌单，一键把整张歌单加入播放队列。
- **账号状态**：可查看账号有效性与 VIP 等级；酷狗支持账号密码 / 扫码 / Cookie 导入，网易云与 QQ 音乐支持扫码 / Cookie 导入。
- **三种歌词**：歌词按"原文 / 翻译 / 音译"三段式显示，可自由开关翻译与音译；歌词与播放信息 HUD 的位置、大小可自由调整，支持拖动与预设布局。
- **自由听歌**：顺序、列表循环、单曲循环、随机四种播放模式可随时切换；可切换游客模式（出站请求不携带登录凭证，仅播放免费公开歌曲）；随时切入"个人聆听"独自收听，不打扰他人。
- **全服同步 / 个人聆听**：服务端玩家可共同聆听同一首歌，也可以切换为个人模式独自听歌；全服同步带时间戳校准，保证节奏一致。
- **服务端可控**：队列上限、点歌冷却均可由服主配置；管理员拥有清空队列、切换播放模式等专属权限。
- **开箱即用**：模组完全自包含，不依赖任何外部软件，跨电脑直接使用。

## 支持环境

| 项目 | 说明 |
| --- | --- |
| 游戏版本 | Minecraft 26.2（当前构建目标；分层架构支持向其他正式版移植，见"版本移植"） |
| Fabric | Fabric Loader 0.19.5+ + Fabric API 0.161.0+26.2 |
| Forge | Forge 26.2-65.1.5 |
| NeoForge | NeoForge 26.2.0.90 |
| Java | 25 及以上（MC 26.x 运行时要求；构建使用 JDK 25） |

## 安装

1. 安装对应加载器（Fabric / Forge / NeoForge）与匹配的 Minecraft 版本。
2. 下载与你的加载器对应的 yunyy 版本 jar 文件。
3. 将 jar 放入游戏目录的 `mods` 文件夹。
4. 启动游戏，模组即生效。

## 使用

- 按 **U 键**，或在聊天框输入 **`/music`**，打开云音乐主界面。
- 点歌指令：
  - 网易云：`/music wyy 歌曲名`
  - 酷狗：`/music kg 歌曲名`
  - QQ 音乐：`/music qq 歌曲名`

## 配置

模组会在配置目录生成 `yunyy-*.properties` 系列文件，用于调节音量、HUD 布局、歌词显示、音频代理等。一般无需手动修改即可正常使用；如需个性化，可参照文件内注释调整。

## 构建

本项目为多模块 Gradle 工程，已内置 Gradle Wrapper（Gradle 9.7.0）。

```bash
./gradlew build                 # 构建全部模块
./gradlew :fabric:build         # 仅构建 Fabric 模块
./gradlew :forge:build          # 仅构建 Forge 模块
./gradlew :neoforge:build       # 仅构建 NeoForge 模块
```

构建要求 JDK 25（由 `build.gradle` 的 Java toolchain 指定，Gradle 会自动探测本机已安装的 JDK 25）。

### 模块结构

```
yunyy/
├── common/      # 纯 JDK 核心层：HTTP / JSON / 加密 / 配置 / 队列 / 歌词解析 / 平台网关（不依赖 Minecraft）
├── mccore/      # MC 耦合共享层：GUI / HUD / 播放 / 网络 payload / 服务端状态机 / 权限判定（不依赖任何加载器）
├── fabric/      # Fabric 入口 + 网络 / HUD 适配
├── forge/       # Forge 入口 + 网络 / HUD 适配
└── neoforge/    # NeoForge 入口 + 网络 / HUD 适配
```

依赖方向严格单向：`加载器模块 → mccore → common`。

## 版本移植（Forge / NeoForge / Fabric）

分层架构为跨版本移植而设计，全部版本与加载器参数集中在 `gradle.properties`：

- **common 层零 MC 依赖**：任何 Minecraft 正式版可直接复用，移植时无需改动。
- **mccore 层只依赖 Minecraft 稳定 API**（`CustomPacketPayload` / `StreamCodec` / `Screen` / `GuiGraphics` 等 1.20.5+ 体系）：同一大版本线内（1.20.5–1.21.x、26.x）基本零改动；跨 API 断代（如 1.20.5 之前、GUI 抽取重写等）仅调整本层。
- **加载器模块是薄适配层**：只负责入口注解、事件接线、payload 收发实现；Forge / NeoForge / Fabric 各自独立，一个加载器换版本不影响其余两个。

移植步骤（以适配新 Minecraft 正式版为例）：

1. `gradle.properties` 更新 `minecraft_version` 与对应加载器版本坐标；
2. 各加载器模块的 `mods.toml` / `neoforge.mods.toml` / `fabric.mod.json` 依赖区间同步；
3. 如遇 MC 网络/HUD API 断代，只改 `mccore` 对应封装点；加载器入口无需变动；
4. `./gradlew :forge:build :neoforge:build`（Fabric 视映射可用性），或推送后由 GitHub Actions 自动构建。

## 许可

本模组采用 **GPL-3.0（GNU 通用公共许可证 v3.0）** 许可分发，并附加一条“链接例外”（依据 GPL-3.0 第 7 条）：允许本模组与 Minecraft 及 Forge / NeoForge / Fabric 等运行时链接，以此化解“遵守 GPL 则触及 Mojang 版权、遵守 Mojang EULA 则违反 GPL”的两难。`LICENSE` 文件随模组一同分发。你可以自由使用、修改与再分发；但凡修改后分发，必须以相同许可（GPL-3.0）开源你的修改部分，并保留原许可与版权声明。
