LodServer / LodClient — 服务端命令 /lod（兼容 /lods），客户端命令 /lodc

LodServer 0.2.0 — ARCH 共享远景缓存

适配目标
  服务端：Paper 1.21.4 / Java 21 或 Paper 26.3 / Java 25，各有独立安装包。
  客户端：Minecraft 1.21.11 / Fabric，Voxy 0.2.16-beta，DH 3.3.3。
  跨版本连接依赖 arch 现有 ViaVersion。其它服务端版本尚未验证。
  服务端使用 LSS v20 与 DH v16 两套协议。Voxy 原版需要额外的接收桥接
  mod；不修改、不打包 Voxy 本体。DH 原版自带接收能力。

功能矩阵
  服务器没有 LodServer：
    原版客户端：保持原功能。
    Voxy：保持本地采集功能；加 LodClient 也不会影响连接。
    DH：保持原有本地生成/缓存功能。
  服务器安装 LodServer：
    原版客户端：保持原功能，不主动接收模型。
    仅 Voxy：保持本地采集，不具备服务器远景接收能力。
    Voxy + LodClient：共享远景、个人下载世界名单、协商缓存回传。
    Voxy + 公共 LSS v20：使用相同公开协议下载，取决于 LSS 自身的配置。
    DH 3.3.3：直接支持服务器远景，不需要 Taixue mod。
    DH + LodClient：额外提供个人下载世界名单。
    Taixue mod、但无 Voxy/DH：不请求地形，正常游戏。
    同时装公共 LSS/VSS 与 Taixue mod：Taixue mod 让出所有通道，避免重复
    注册；此时 Taixue 的上传和个人世界名单功能不启用。

世界权限与名单
  两套协议共用检查：archlod.use + multiverse.access.<实际世界名>。
  每次读取前、异步任务结束后、每个实际发送前重新检查；换世界、退出、
  撤销权限会使旧任务失效。只提供玩家当前世界，不能通过伪造世界 ID 获取
  其它世界的模型或初始化信息；跨世界预下载不开放。
  管理员配置 plugins/LodServer/config.yml：
    lod.worlds.mode: blacklist 或 whitelist
    lod.worlds.entries: 世界名、维度 key、世界 UUID；支持 * 通配符。
  whitelist 空列表：全部禁用。blacklist 空列表：不额外排除世界。
  名单同时控制新缓存生成、读取和发送，DH 预生成也遵守名单。
  修改配置后 /lods reload，或直接使用：
    /lods lodworlds
    /lods lodworlds mode whitelist
    /lods lodworlds add main-city
    /lods lodworlds remove main-city
  管理命令要求 distant_horizons.admin（默认 OP）。
  玩家装 Taixue mod 后：
    /lodc radius 64
    /lodc worlds
    /lodc worlds mode whitelist
    /lodc worlds add current
    /lodc worlds remove current
  个人配置按服务器地址保存在 config/taixue-lod-worlds.json，只能缩小服务器
  许可范围。建议用 current 添加准确的维度 key。下载距离取服务器上限
  lod.radius-chunks 与个人 radius；个人默认 1000 chunks，可选 8～1024。
  撤权可以阻止后续下载，无法删除玩家以前已经合法获得的本地缓存。

客户端缓存回传
  发行和泰学 ARCH 均设置 upload.enabled=false。服务端从地图文件和区块卸载快照生成缓存，不依赖玩家回传。
  客户端只读取当前连接、当前维度的活动 Voxy 缓存，不扫描其它服务器目录。
  默认本地可信地址：mc.taixue.cc、minecraft.taixue.cc、*.taixue.cc；实际
  连接地址不同，可在 config/taixue-lod-client.json 的 trustedServers 添加。
  shareLocalCache=false 可关闭本地分享。
  服务器 upload.allowed-players 可使用玩家名、UUID、*，也可单独授予
  archlod.upload；upload.blocked-players 的拒绝优先。
  客户端提议 -> 服务器决定是否需要 -> 索取 -> 分片上传 -> 摘要/结构/身份
  校验 -> 与服务器实际保存的地形核对 -> 写入共享缓存。
  最大 512 KiB/column、28 KB/片、15 秒超时，默认全服 4 个上传、每玩家
  1 个上传及 5 秒冷却。未请求的上传忽略。
  旧 Voxy 没有可靠的区域时间戳，因此发送 0（未知），默认仅补缺失缓存；
  不用“导出时间”冒充地形更新时间。trusted-client-timestamps 默认关闭。
  模型与当前地形不符会拒绝；缺失的 section 视为未知，由服务器补齐。
  此模式免去了重新让玩家跑图，但仍需要服务器读取已有区块进行核对。
  本版回传支持 Voxy，本地 DH 数据库导入未实现；DH 直接从服务器生成。

缓存与负载
  只读取已有区块，远景请求不生成新的地图区块。
  Voxy gzip 缓存在 plugins/LodServer/voxy-cache，默认不因年龄过期；限额由下面的共用策略管理。
  DH 缓存在 plugins/LodServer/data.sqlite。缓存按世界 UUID 隔离。
  普通方块事件、WorldEdit/FAWE 编辑会触发更新；Voxy 的有效期还会兜底其它
  绕过事件的修改。世界名单变化不删除历史缓存，但禁止向玩家发送。
  FAWE 必须允许本插件的 extent；在 FAWE config.yml 的 extent.allowed-plugins
  添加：cc.taixue.lod.server.WorldEditInvalidation$TrackingExtent
  升级 Kotlin 版时，将旧的 WorldEditInvalidation$1 允许项替换为上述稳定类名。
  修改 FAWE 配置前备份原文件；本次编译、发布不会自动改动生产服配置。
  队列、并发、坐标和消息长度均有上限；默认每 tick 2 个 Voxy columns，
  全局最多 4 个处理任务、单玩家队列 256。初次远景填充会逐步完成。

安装与回退
  服务端 jar 放 plugins/LodServer-0.2.0.jar；下次完整启动加载。
  客户端 jar 放 Fabric 客户端 mods/，并装 Fabric API 和原版 Voxy/Sodium。
  不要把客户端 jar 放服务器 plugins 根目录。DH 玩家可不装此客户端 jar。
  回退：服务器停服时移走 LodServer jar；客户端移走 LodClient mod 即可。
  不需要删除原有 Voxy/DH 缓存。不要使用热重载工具加载本插件。

源码与构建
  项目为独立 Gradle 工程：core / server / client，不修改 ARCH 主插件。
  Java 21；./gradlew :core:test :server:test :server:shadowJar :client:remapJar
  Gradle 9.4.0 / Loom 1.16.2。fixture 源码只用于隔离测试，不进正式 jar。
  scripts/run-integration.py 创建全新 loopback Paper 实例，不加载生产世界。
  测试脚本中的运行时源路径为本机 arch；迁移测试环境时需调整该路径。
  GPL-3.0-or-later。DH Support 原始代码及 LSS MIT 协议代码的来源、许可证
  见 third-party/。Voxy、DH 客户端本体未随发行包分发。

验证范围
  自动化核心测试覆盖会话失效、名单模式、缓存过期/损坏、消息与模型校验。
  隔离 Paper 与 ViaVersion 实测覆盖两套协议、未授权世界、在线撤权、世界
  伪造、名单切换、协商回传/恶意模型/错误摘要、FAWE 缓存更新。
  分发版本的 Fabric mod 在无显示器环境中使用真实 Voxy/DH 验证接收数据；
  通过本地缓存/地形接口检查原版视距外的指定方块，而非仅检查收到字节。
  公共 LSS 本体未做真实客户端回归，兼容范围为已核对的 LSS v20 协议。
  测试中观察到 DH 3.3.3 在只分配一个工作线程时卡住；使用至少两个 DH
  工作线程后，自动请求正常。此问题属于所测 DH 客户端版本的运行限制。
  不包含生产负载压测、所有光影组合或其它 Minecraft 版本验证。


0.2.0 服务器缓存管理与预热
  两种协议共用 cache.global.max-mib（默认 4096 MiB），并可通过
  cache.by_world 设置世界名或 UUID。例子：
    cache:
      global:
        max-mib: 4096
      by_world:
        main-city:
          max-mib: 1024
          priority: 10
        archive:
          max-mib: -1
          priority: 100
      maintenance-seconds: 30
      refresh-on-unload: true
  单位为 MiB，统计 gzip 文件及 SQLite 中 DH 压缩模型/信标载荷的合计。
  数据库元数据、文件系统分配和维护期间的临时空间不计入模型预算。
  修改后 /lods reload；/lods cache status 查看状态，/lods cache trim 立即清理。
  维护任务周期性执行，写入后可能暂时超过限额，下一轮维护收敛。
  全局 -1 不限制总量，有限的世界限额仍然生效；世界显式 -1 不自动删除，
  也不会为了全局限额而删除该世界。保护世界本身超过全局限额时会报告。
  世界不填 max-mib 则只参与全局限额。0 清空该预算内的缓存。
  先满足各世界限额，再满足全局限额。优先删 priority 较小的世界；同优先级
  删更新时间最早的缓存。读取缓存不改变更新时间。
  DH 删除后回收 SQLite 空闲页；旧数据库第一次转为增量回收可能需一次整理。

  服务端预热只扫描已加载世界的 Anvil region 文件中已完整生成（FULL）的区块，不创造地形：
    prewarm:
      global:
        enabled: false
        protocols: [voxy, dh]
        chunks-per-tick: 1
      by_world:
        main-city:
          enabled: true
        archive:
          enabled: true
          protocols: [dh]
  global 是默认设置，by_world 可覆盖 enabled、protocols；仍服从世界白/黑名单。
  enabled 控制启动/配置重载后的自动预热。未加载的世界需先加载。
  /lods warm start all 或 /lods warm start main-city 手动扫描与刷新。
  /lods warm status 查看每个世界进度；/lods warm stop all 停止后续工作。
  手动 start 可启动尚未设为自动预热的世界，仍使用该世界的协议选择。
  全局每 tick 1～8 个扫描项、最多两个活动任务；DH 的 4×4 chunk 模型按区段
  合并生成。缺少的相邻区块作为空白处理，不会因此生成新地形。
  区块卸载时抓取最新快照并异步刷新 Voxy，合并刷新对应 DH 模型；后台预热
  自己引发的临时加载/卸载不会递归触发新一轮工作。
  世界白/黑名单仍决定是否生成与分发，两套协议的玩家权限检查保持生效。

Minecraft 26.3 Fabric client: see client-26.3/README.txt. This is a separate Java 25 build; the original client and server remain Java 21 builds.

命名与升级
  插件名为 LodServer，mod 名为 LodClient；服务端指令为 /lod（兼容 /lods），客户端指令为 /lodc。
  自有入口为 cc.taixue.lod.server.LodServer、cc.taixue.lod.client.LodClient。
  第三方包名、网络协议标识、客户端 mod ID 与配置文件名保持兼容。
  首次启动前备份旧 plugins/ArchLOD/，移走旧版服务端 jar，安装 LodServer jar。
  插件会在创建默认配置前把旧 ArchLOD 数据目录整体迁移到 LodServer。
  若两个非空目录同时存在，拒绝启动且不覆盖文件，需管理员先合并数据。
  回退到旧版时，应在停服后将目录改回 ArchLOD；配置与缓存格式保持兼容。

通用命令入口
  /lod 与 /lods 是同一服务端命令，权限完全相同。例如 /lod cache status、
  /lod warm status、/lod reload。个人设置继续用 /lodc，不在客户端注册
  /lod 根命令，避免拦截服务端子命令。/lodc worlds 为个人下载偏好；
  /lod lodworlds 为服务端 LOD 世界名单，/lod worlds 为 服务端世界列表。

Paper 26.3 服务端版本
  使用 Java 25，安装 LodServer-0.2.0+mc26.3.jar。旧版 Paper 1.21.4 使用
  LodServer-0.2.0.jar / Java 21。同一服务器只安装其中一个版本。
  名称 LodServer、包名 cc.taixue.lod.server、/lod 与 /lods 指令、配置目录、
  权限规则与缓存协议保持一致；世界格式由对应 Minecraft 服务端管理。
  构建：cd server-26.3 && ./gradlew --no-daemon build（Java 25）。

排查游戏远景缺块：在客户端输入 `/lodc status` 查看当前世界的握手、服务器开关、个人设置、接收／提交 Voxy 队列计数与错误数。该命令也将状态写入客户端日志；提交队列不等于 Voxy 已完成渲染。计数在换世界或重连时清零。

大范围游戏远景
  /lodc radius 1000 设置个人下载半径为 1000 区块（16000 方块）。
  管理员须将 lod.radius-chunks 设为 1000 或更大，再 /lod reload；有效范围取两者较小值。
  更新后的服务端和客户端最高支持 1024 区块，默认值为服务端 1000／客户端 1000。
  请求采用最多 64 个未完成区块的窗口并重试超时请求，范围扩大不提高服务器限速。
  大范围首次下载逐步完成；此指令不修改 Voxy 自己的渲染距离，也不生成新地图区块。

下载半径默认值：客户端 1000 区块，服务端 1000 区块，实际取两者较小值；服务端始终强制检查范围。已有客户端手动保存的半径不会被覆盖，可用 /lodc radius 1000 修改。
