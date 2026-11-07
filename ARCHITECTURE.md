# yunyy（云音乐）1.0 重构架构设计（多加载器）

本文档是重构的规范依据。任何代码改动必须符合本架构与三加载器官方规范。

## 1. 目标

- 模组显示名：`云音乐`；mod id：`yunyy`
- 版本号：`1.0.0`（显示 1.0；SemVer 2.0.0 规范）
- 作者：`eecccm`（本模组由 eecccm 与 AI 协作完成，部分内容由 AI 辅助生成）
- 同时适配三个模组加载器：**Fabric / Forge / NeoForge**（Legacy Fabric 已于 2026-10 取消）
- 平台接入：网易云音乐、酷狗音乐（标准版与概念版共用一套接入，参数自动区分）、QQ 音乐
- 完全符合各加载器官方模组开发规范（见 §5）
- 模组自包含、跨电脑开箱即用，不依赖任何外部软件

## 2. 分层架构（多模块 Gradle 工程）

```
yunyy/
├── settings.gradle / build.gradle / gradle.properties / gradlew(.bat)
├── common/                     # 纯 Java 核心层：不依赖 Minecraft 与任何加载器 API
│   └── src/main/java/com/eecccm/yunyy/core/…
│   └── src/main/resources/     # 共享资源：lang/icon（各加载器模块再声明自己的元数据文件）
├── mccore/                     # MC 耦合共享层：仅依赖 Minecraft API，零加载器依赖
│   └── src/main/java/com/eecccm/yunyy/mccore/{net,client,server,util,mixin/client}/
├── fabric/                     # Fabric（26.x，Loom）：薄入口 + 网络/HUD 适配
│   └── src/main/java/com/eecccm/yunyy/fabric/…
│   └── src/main/resources/fabric.mod.json
├── forge/                      # Forge（26.x，ForgeGradle）
│   └── src/main/java/com/eecccm/yunyy/forge/…
│   └── src/main/resources/META-INF/mods.toml
└── neoforge/                   # NeoForge（26.x，ModDevGradle）
    └── src/main/java/com/eecccm/yunyy/neoforge/…
    └── src/main/resources/META-INF/neoforge.mods.toml
```

### 依赖方向（严格单向）

`加载器模块 → mccore → core`。**禁止** core 反向依赖 MC/加载器 API。

```
com.eecccm.yunyy.core          # 纯 JDK 代码（HTTP/JSON/AES/配置/队列/歌词解析/平台网关 API）
com.eecccm.yunyy.mccore        # MC 耦合共享（payload/界面/HUD/播放/服务端状态机/权限判定）
com.eecccm.yunyy.fabric        # Fabric 入口+网络+HUD 适配（薄）
com.eecccm.yunyy.forge         # Forge 入口+网络+HUD 适配（薄）
com.eecccm.yunyy.neoforge      # NeoForge 入口+网络+HUD 适配（薄）
```

### mccore 层设计要点

- `net/*`：19 个 CustomPacketPayload（纯 MC 标准类型，codec 为 `StreamCodec<RegistryFriendlyByteBuf,T>`，三加载器通用）
- `client/*`：全部 Screen、HUD（LyricsHud/NowPlayingHud）、播放器（MusicPlayer/PersonalPlayback/VolumeAudioDevice）、状态（ListeningState/QueueState）、  
  `ClientPayloadHandlers`（客户端全部包处理逻辑，三加载器共用）、`YunyyClientNet`（客户端网络抽象，加载器注入实现）
- `server/`：`YunyyServerCore`（命令树/队列状态机/全服频道，`Net net` 由加载器注入）、`ListeningChannels`
- `util/`：`YunyyIds`（Identifier 工厂）、`YunyyPayloadCodecs`（type→codec 查找表）
- `mixin/client/`：两个 mixin（ChannelPauseMixin、LibraryCleanupMixin），fabric mixins json 指向此包
- 所有加载器入口引用的类与方法均 `public`

## 3. 包/类命名规范

- 顶层包：`com.eecccm.yunyy`（作者 eecccm；group 与顶层包一致，NeoForge 规范；内层包对应 mod id `yunyy`）
- 类名：UpperCamelCase；方法/字段：lowerCamelCase；常量：UPPER_SNAKE
- 加载器入口类：`YunyyMod`（fabric）/ `YunyyForgeMod` / `YunyyNeoForgeMod` / `YunyyLegacyFabric`（legacy）
- 客户端入口类：`YunyyModClient`（fabric）/ `YunyyForgeClient` / `YunyyNeoForgeClient`
- 网络适配：`ServerNetwork` / `ClientNetwork`（各加载器模块内实现 mccore 抽象）
- 禁止中文类名/字段名；注释可用中文（本项目沿用）

## 4. 分层职责

| 层        | 职责                                                                                                                                                                                                                                                                                                           |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `common` | 纯 JDK：HTTP（SafeHttpClient，含超时与 SSRF / 重定向防护）、AES-GCM（SecretCipher）、Cookie、配置文件（ServerPolicy/GainPreferences/HudPreferences/LyricPreferences/AudioBridgeOptions）、账号服务（AccountSessionStore）、平台网关（MusicVendor/VendorGateway/VendorRegistry/NeteaseGateway/KugouGateway/QQMusicGateway）、歌词时间轴（LyricTimeline）、播放历史（ListenLog）、日志（TraceLog，含脱敏 Redactor）、运行时注入点（RuntimeRegistry.metaResolver） |
| `mccore` | MC 耦合共享：全部 GUI Screen、HUD 渲染（LyricOverlay/NowPlayingOverlay）、音频播放（PlaybackEngine/SoloPlayback/GainDevice）、payload 收发抽象（ClientNet）、服务端命令与队列状态机（ServerCore/SyncChannels）、权限判定（AccessControl）、通道 ID 与编解码（PacketIds/PacketCodecs）、客户端包处理逻辑（ClientPacketHandlers） |
| 加载器层     | 仅：入口注解、事件注册（命令/tick/连接/生命周期/HUD/按键）、payload 编解码注册与收发实现、注入 mccore 抽象                                                                                                                                                                                                                                          |

### 平台注入点

- `YunyyServerCore.net`（`Net` 接口）→ 各加载器 `ServerNetwork.INSTANCE`
- `YunyyClientNet.set(Impl)` → 各加载器 `ClientNetwork.INSTANCE`
- `RuntimeRegistry.metaResolver` → `ServerCore::currentSongEntryMeta`

## 5. 三加载器规范要点（已核实官方文档）

### 5.1 Fabric（docs.fabricmc.net 26.1.2）

- `fabric.mod.json` 放 JAR 根：`schemaVersion=1`、`id`（小写字母数字_ -）、`version`（SemVer）、`name`、`description`、`icon`（PNG 正方形）、`environment`、`entrypoints{main,client}`、`mixins`、`depends`、`custom`（自定义字段，加命名空间）
- 入口：实现 `net.fabricmc.api.ModInitializer` / `ClientModInitializer`
- 网络：`PayloadTypeRegistry.clientboundPlay()/serverboundPlay()` + `ServerPlayNetworking/ClientPlayNetworking`（`ChannelRegistrar` 直连公开 API，不做反射探测）

### 5.2 Forge 26.2（依据 Forge 65.1.5 运行时构件实测）

- `META-INF/mods.toml`：`modLoader="javafml"`、`loaderVersion="[65,)"`（对齐 `fmlloader-26.2-65.1.5` 的 Implementation-Version 65.1.5）、`license`、`[[mods]]`、`[[dependencies.<modid>]]`、`[features.<modid>] javaVersion="[25,)"`
- 入口：public class + `@Mod("yunyy")`，无参构造器；`FMLEnvironment.dist` 区分客户端
- 事件（**EventBus 7，事件为 record，每个具体事件持有独立静态 `BUS`**）：
  - `RegisterCommandsEvent.BUS`、`TickEvent.ServerTickEvent.Post.BUS`、`TickEvent.ClientTickEvent.Post.BUS`
  - `PlayerEvent.PlayerLoggedInEvent.BUS` / `PlayerLoggedOutEvent.BUS`、`ServerStoppedEvent.BUS`
  - `net.minecraftforge.eventbus.api.IEventBus` 与 `MinecraftForge.EVENT_BUS` 监听方式已废弃（后者降级为 `EventBusMigrationHelper`）
- 网络（**旧 `IPayloadRegistrar` / `RegisterPayloadHandlersEvent` 已移除**）：`ChannelBuilder.named(id).networkProtocolVersion(n).payloadChannel().play()` → `PayloadFlow.addMain(Type, StreamCodec, BiConsumer)` → `flow.build()`；接收上下文为 `CustomPayloadEvent.Context`（`getSender()`），发送 `channel.send(payload, PacketDistributor.PLAYER.with(player))`，上行 `PacketDistributor.SERVER.noArg()`
- HUD：`AddGuiOverlayLayersEvent.BUS` + `ForgeLayeredDraw.add(Identifier, ForgeLayer)`；`ForgeLayer.extract(GuiGraphicsExtractor, DeltaTracker)`
- 按键：`RegisterKeyMappingsEvent.BUS` + `event.register(KeyMapping)`
- Mixin（**与 Fabric / NeoForge 机制不同**）：Forge 26.2 的 `ModFileParser` **不解析** `mods.toml` 的 `[[mixins]]` 段
  （字节码内既无 `mixins` 也无 `config` 常量），改由 jar **manifest 的 `MixinConfigs` 属性**注册
  （`JarInJarDependencyLocator` 读取 `MixinConfigs` / `MixinConnector`）。故 `forge/build.gradle` 的 `jar` 任务需写
  `manifest { attributes 'MixinConfigs': 'yunyy.mixins.json' }`，配置文件 `forge/src/main/resources/yunyy.mixins.json`。
- `[features.<modid>]` 同为 NeoForge 专有表，Forge 会静默忽略（不产生 Java 版本约束）。
- 构建：**ForgeGradle（`net.minecraftforge.gradle`）**。Forge 与 NeoForge 是相互独立的两套构件：
  - Forge = `net.minecraftforge:forge:<MC>-<build>`（本项目 `26.2-65.1.5`），必须由 ForgeGradle 构建；
  - NeoForge = `net.neoforged:neoforge:<MC>.<minor>.<patch>`（本项目 `26.2.0.90`），由 ModDevGradle 构建（neoforge 模块）。
  - ModDevGradle 只能解析 `net.neoforged:neoforge`，解析不了 `net.minecraftforge:forge`，二者不可混用。

### 5.3 NeoForge 26.2（依据 NeoForge 26.2.0.90 运行时构件实测）

- `META-INF/neoforge.mods.toml`：结构同 Forge，`loaderVersion="[11,)"`（对齐 fancymodloader 11.0.16 的 Specification-Version 11）；`[features.<modid>] javaVersion="[25,)"`
- 入口：public class + `@Mod("yunyy")`，构造器可注入 `IEventBus`（mod 总线）
- 事件订阅：`@EventBusSubscriber(modid="yunyy", value=Dist.CLIENT)`（**26.2 已移除 `bus` 属性**，注册类事件与游戏事件按类型自动分流）
- payload：`RegisterPayloadHandlersEvent` + `PayloadRegistrar`；上行上下文 `IPayloadContext.player()`（**无 `getSender()`**）；客户端下行无 `getClient()`，取 `Minecraft.getInstance()`；发送 `PacketDistributor.sendToPlayer`，客户端上行 `ClientPacketDistributor.sendToServer`
- HUD：`RegisterGuiLayersEvent` + `net.neoforged.neoforge.client.gui.GuiLayer`（原 `IGuiLayer` 已更名）
- tick：`net.neoforged.neoforge.client.event.ClientTickEvent`（Pre/Post，不再携带 `getClient()`）、`net.neoforged.neoforge.event.tick.ServerTickEvent`
- Mixin：`neoforge.mods.toml` 内 `[[mixins]] config="yunyy.mixins.json"`（`ModFileParser` 明确解析该段，缺失时报
  `Missing "config" in [[mixins]] entry`），配置文件 `neoforge/src/main/resources/yunyy.mixins.json`
- `PayloadRegistrar` 默认 `HandlerThread.MAIN`（构造器字节码把 `thread` 初始化为 `MAIN`），因此 `playToServer`
  回调本身就在服务端主线程执行，可直接操作服务端单线程状态，无需自行 `enqueueWork`
- 构建：ModDevGradle

## 6. 元数据统一（1.0 版）

- mod id：`yunyy`；显示名：`云音乐`；version：`1.0.0`；authors：`eecccm`
- AI 声明：每个源文件与构建脚本头部声明「本文件由 eecccm 与 AI 协作完成，部分内容经由 AI 辅助生成」；元数据 `description` / `credits` 同步声明；`custom`/`modproperties` 增加 `yunyy:ai_generated=true`
- license：`GPL-3.0（附加链接例外，依据第 7 条允许与 Minecraft / Forge / Fabric 运行时链接）`
- 共享资源：lang（zh_cn/en_us，335 key，前缀 `yunyy.*`）、icon.png 在 common resources，各模块构建时并入 JAR
- 配置文件名：`yunyy-*.properties`

## 7. 构建与验证状态

| 模块               | 状态                                                                                                                                       |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| common | ✅ javac（JDK 25，`--release 25 -Xlint:all`）编译通过（38 源 / 57 类，零错误零告警）；含 H1–H3 安全修复（HTTPS 化/凭证 GCM/重定向防护）、日志乱码修复、酷狗歌单 20017 修复、QQ 音乐平台接入 |
| mccore | ✅ javac 编译通过（58 源 / 77 类，零错误零代码告警） |
| fabric | ✅ javac 编译通过（6 类）；产物 `yunyy-1.0.0+mc26.2-fabric.jar`（1115385 bytes，140 类）经 26.2-Fabric 实例实测 |
| neoforge | ✅ javac 编译通过（4 类，零错误零告警）；classpath 用实测本地 `minecraft-client-patched-26.2.0.90` + `neoforge-26.2.0.90-universal` + fancymodloader 11.0.16 实测装配 |
| forge | ✅ javac 编译通过（4 类，零错误零告警）；classpath 用实测本地 `forge-26.2-65.1.5-client` + `forge-26.2-65.1.5-universal` + eventbus 7.0.5 实测装配 |

### 本轮缺陷修复（不新增功能，只修缺陷与潜在问题）

| #  | 位置 | 缺陷 | 修复 |
| -- | ---- | ---- | ---- |
| 1  | `ReservedAddressGuard.resolveAndCheck` | IP 字面量分支直接判为公网放行，`::ffff:127.0.0.1` / `::ffff:169.254.169.254` 这类 IPv4 映射地址可绕过 SSRF 防护 | 字面量改用 `InetAddress.getByName` 解析后交 `isReservedAddress` 复核 |
| 2  | `SafeHttpClient.isReservedPrivateHost` | IPv6 前缀判定缺少「含冒号」守卫，`fcdn.kugou.com` 等以 fc/fd/fe80 开头的域名被误判内网并拒连 | 前缀判定限定在 IPv6 字面量上 |
| 3  | `SafeHttpClient.send` | 多跳重定向以最初 origin 为相对基准，第二跳后的相对 Location 会解析到错误层级 | 改 `current.uri().resolve(location)` |
| 4  | `SafeHttpClient` | `downloadBytes` 每次请求新建 `HttpClient`（最多 3 个），`send` 每次重试新建（最多 6 个），selector 线程与连接池只能等 GC | 复用两条常驻客户端（HTTP/2 / HTTP/1.1） |
| 5  | `SafeHttpClient` | `downloadBytes` 中 `lastIo` 为死变量；`permitRedirect` 的 `origin` 为死参数 | 删除 |
| 6  | `AccountSessionStore` | 与 #3 同类的相对重定向基准错误；`allowRedirect` 的 `origin` 为死参数；`localKey` 非 volatile | 修正基准、删死参数、`localKey` 加 volatile |
| 7  | `AccountSessionStore` / `VendorRegistry` | 本地密钥缺失时凭证被静默丢弃，重启后登录态凭空消失且无日志 | 持久化路径显式 WARN 留痕 |
| 8  | `VendorRegistry.gatewayFor` | 网关表是 `EnumMap` 且无同步，并发首取可能各建一份网关、后建覆盖先建，等于丢弃刚登录的会话 | 改 `ConcurrentHashMap` + `computeIfAbsent` |
| 9  | `KugouGateway.userInfoV3` / `userInfoLegacy` | `Long.parseLong(userid)` 无保护，空或非数字 userid 抛 `NumberFormatException` 中断账号/VIP 查询 | 统一走 `uidOf()` 回落 0（与其余 userid 使用点语义对齐） |
| 10 | `QrFlow` / `AlbumArt` | 每个扫码会话、每首歌曲封面各新建一个 `HttpClient` | 静态复用；封面按「是否挂代理」做二级缓存 |
| 11 | `YunyyMod`（Fabric `main` entrypoint） | 在双端都会执行的入口里注入客户端网络实现，专用服务器会连带解析 Minecraft 客户端类 | 移到 `YunyyModClient`（仅客户端 entrypoint），与 Forge/NeoForge 结构一致 |
| 12 | `ClientNet.get()` | 未注入时返回 null，界面早期访问直接 NPE | 返回带一次性告警的空实现 |
| 13 | `ChannelRegistrar`（Fabric） | 用反射探测 `clientboundPlay`/`playS2C` 等候选方法名，签名错误被推迟到运行期 | 直连 `PayloadTypeRegistry.clientboundPlay()` / `serverboundPlay()` |
| 14 | Fabric / Forge / NeoForge 元数据 | Forge 与 NeoForge **完全没有** Mixin 配置，`SyncPauseMixin` / `TempCleanupMixin` 在三端中的两端不生效 | NeoForge 补 `[[mixins]]`；Forge 补 manifest `MixinConfigs`；三端 `compatibilityLevel` 对齐 `JAVA_22`（Mixin 0.8.7 支持上限） |
| 15 | `mods.toml` / `neoforge.mods.toml` | `logoFile="yunyy.png"`，但 jar 内无该文件 | 指向实际存在的 `assets/yunyy/icon.png` |
| 16 | `assets/yunyy/icon.png` | 原图为 256×256、带 gAMA/sRGB/pHYs 扩展块的多 IDAT PNG，个别解码器兼容性一般 | 重编码为 128×128、仅 IHDR/IDAT/IEND 的最小 profile（JDK `ImageIO` 与 JavaFX `Image` 均验证可解）。另经查证：PCL2 启动器的模组管理页不读取 jar 内图标，名称/图标一律按文件哈希匹配 Modrinth/CurseForge 在线数据，未发布的模组显示其内置占位图与文件名，属该启动器设计行为；游戏内 Mod Menu 与 HMCL 则直接读 jar 图标 |

### 标准构建（Gradle）

```bash
./gradlew :forge:build :neoforge:build   # Forge / NeoForge jar（需可访问 maven.minecraftforge.net 与 maven.neoforged.net）
./gradlew :fabric:build                  # Fabric jar（需 26.x Yarn/官方映射就绪，见 §5.1）
# 产物：forge/build/libs/yunyy-1.0.0+mc26.2-forge.jar
#       neoforge/build/libs/yunyy-1.0.0+mc26.2-neoforge.jar
```

推送至受支持托管（GitHub）后由 `.github/workflows/build.yml` 在 JDK 25 上自动构建 Forge/NeoForge 并上传 Artifact。

### 离线快速校验（无网络环境）

无 Gradle 依赖解析网络时，可用 JDK 25 javac 直接分层校验（classpath 取本地已装
Minecraft / Fabric / Forge / NeoForge 运行时构件，逐模块 `--release 25 -Xlint:all`）：

```bash
javac --release 25 -Xlint:all -encoding UTF-8 -d classes/common   <common 源文件>
javac --release 25 -Xlint:all -encoding UTF-8 -d classes/mccore   -cp <mc.jar>:classes/common <mccore 源文件>
javac --release 25 -Xlint:all -encoding UTF-8 -d classes/fabric   -cp <mc.jar>:<fabric-api>:classes/common:classes/mccore <fabric 源文件>
```

该路径仅用于编译验证；交付 jar 仍需标准构建或离线拼装三层 classes + 资源 + 嵌套库
（fabric 侧需把 `common`/`mccore` 的 classes、`fabric.mod.json`、`yunyy.client.mixins.json`、
`META-INF/jars/*`、`assets/yunyy/*` 一并写入并放入实例 mods）。

## 8. 运行约束（用户强约束）

- 入口仅 `U` 键或 `/music`（禁止 K 键）  
  |  |  |  
  | --- | --- |
- 网易云、酷狗与 QQ 音乐均由本模组基于三家平台公开 HTTP 接口自行实现的客户端接入；命令前缀 `/music wyy|kg|qq`
- VIP 显示格式："账号有效：{账号}，VIP {等级}"（如 账号有效：1234567890-1，VIP 0）
- 设备指纹自动获取（读本机库，不要求授权）
- 不联动任何外部软件、不出现其他软件名称
- 全服同步用时间戳校准；服务端队列/冷却可配置
