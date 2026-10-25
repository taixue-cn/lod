# LodServer / LodClient

独立的 Minecraft 共享远景缓存项目，同时支持 Voxy 和 Distant Horizons。

- **LodServer**：分别提供 Paper 1.21.4 / Java 21 和 Paper 26.3 / Java 25 服务端插件，入口为 `cc.taixue.lod.server.LodServer`。
- **LodClient**：Fabric 客户端辅助 mod，分别提供 Minecraft 1.21.11（Java 21）和 26.3（Java 25）版本。Voxy 远景渲染仍需安装 Voxy 及其依赖；DH 可直接接收服务端远景。

安装、权限、世界名单、缓存与验证范围见 [README.txt](README.txt)；26.3 说明见 [server-26.3/README.txt](server-26.3/README.txt) 和 [client-26.3/README.txt](client-26.3/README.txt)。

## 命令

服务端通用入口为 `/lod`，例如 `/lod cache status`、`/lod warm status`、`/lod reload`；明确指定服务端的 `/lods` 仍可用，两者权限和行为相同。客户端使用 `/lodc`，例如 `/lodc radius 64`、`/lodc worlds`。原 `/dhs`、`/lod-s`、`/lod-c` 和 `/lodclient` 已移除；服务端不再占用 DH 的 `/dh` 命令。原权限节点和缓存格式继续沿用。首次启动自动将旧 `plugins/ArchLOD/` 迁移为 `plugins/LodServer/`；两个非空目录并存时会拒绝启动，保留两边文件供管理员合并。

客户端不注册 `/lod` 根命令，避免 Fabric 客户端优先处理命令时拦截服务端子命令。个人世界偏好使用 `/lodc worlds`，服务端世界管理使用 `/lod worlds` 或 `/lod lodworlds`；其中 `worlds` 为服务端世界列表，`lodworlds` 为服务端 LOD 世界名单。

自有服务端与客户端代码的包名分别为 `cc.taixue.lod.server`、`cc.taixue.lod.client`；共享逻辑为 `cc.taixue.lod.core`。第三方代码保留上游命名空间，客户端 mod ID 与原配置文件名保持兼容。

## 技术栈与代码规范

自有服务端、客户端、共享逻辑及测试均使用 Kotlin 2.4.21，构建脚本使用 Gradle Kotlin DSL。遵循 Kotlin Official Style；`./gradlew formatKotlin` 格式化，`./gradlew checkKotlinFormat` 检查，CI 在发布前执行格式检查。权限、协议、线程和版本边界附有必要注释。

第三方 DH Support／LSS 源码保留 Java 与原许可证；自有代码仅在有明确技术限制时使用 Java，例如受限制的 Mixin。具体约定见 [AGENTS.md](AGENTS.md)。Kotlin 运行库随服务端插件／客户端 mod 打包，不额外要求玩家安装运行库 mod。

## 构建

Java 21：

```sh
./gradlew --no-daemon checkKotlinFormat :core:test :server:test :server:shadowJar :client:remapJar
```

Java 25：

```sh
cd server-26.3
./gradlew --no-daemon build
cd ../client-26.3
./gradlew --no-daemon build
```

## 自动发布

推送到 `main` 后，GitHub Actions 运行上述测试和编译，全部成功才创建带有提交号的 Release，上传两个服务端版本、两个客户端版本和 SHA256 校验文件。也支持手动触发工作流。每个 Release 对应同一提交，源码可从该 Release 的标签下载。

客户端只安装与 Minecraft 对应的一个版本。Release 中的 `LodServer` 文件放服务端 `plugins/`，`LodClient` 文件放客户端 `mods/`。现有 ArchLOD 安装升级时须移走旧插件 jar，避免重复加载同一个插件。

CI 执行单元测试和编译，不启动需要本机运行时及图形环境的集成测试。许可证及第三方来源见 [LICENSE](LICENSE) 和 [third-party](third-party)。

Paper 26.3 安装 `LodServer-0.2.0+mc26.3.jar`，旧 Paper 1.21.4 安装 `LodServer-0.2.0.jar`；同一服务器只放对应版本的一个服务端 jar。两者插件名、包名、指令、配置与权限规则相同。不要将已升级的 26.3 世界用于旧版服务端。

## 可选的网页管理接入

`management.enabled` 默认关闭，保持现有命令与世界名单的行为。启用后，配置可信管理服务的 `management.sync-url`、`server-id` 和至少 32 字符的独立 `token`。插件每 10 秒主动同步已加载世界的 UUID、策略版本和预热进度，无需另开监听端口。该接口只传管理元数据；地形仍通过已有 Minecraft 协议下发，不走网页或 CDN。

托管模式以管理服务的世界开关为准，替代静态世界名单；新世界、无法绑定的世界和超过 30 秒未续期的授权默认拒绝生成和下发。原有玩家权限、个人世界偏好和共享缓存限额仍生效。开启一个世界会对已有地形预热 Voxy/LSS 和 DH 数据；关闭会停止后续预热及下发，保留现有缓存，已经进行中的少量处理可完成。客户端缓存不会被远程删除。后台预热状态不代表网页三维地图已生成。

管理服务使用 `POST` 接收 `{server_id, worlds:[{world_uuid, revision, state, completed_chunks, failed_chunks}]}`，通过 `Authorization: Bearer …` 验证独立服务器身份。返回 `{worlds:[{world_uuid, revision, enabled}]}`，必须覆盖且仅覆盖请求内的 UUID。请求上限为 2048 个世界／1 MiB，响应上限 2 MiB；迟到的响应不延长原请求的 30 秒授权期限。`OFF`、`RUNNING`、`COMPLETED`、`FAILED` 是实际预热状态，没有虚构的扫描总量或百分比。策略变化需要按版本隔离进度回报。

太学 MC Service 的同步路径为 `/v1/internal/lod/sync`，服务器凭据置于 `project.lod.server-tokens.<server-id>`；网页入口为世界项目设置里的“世界地图 → 三维详情 → 游戏远景模型”。修改世界开关需要独立的 `project.capability.manage-lod` 权限，该权限不默认授予任何组；平台管理员通过统一权限服务拥有权限，项目管理员可按需单独授权。观察权限不授予地图浏览或远景管理权限。接入不改变 LSS/DH 缓存格式或客户端协议，因此不要求更换客户端 mod。

排查游戏远景缺块：在客户端输入 `/lodc status` 查看当前世界的握手、服务器开关、个人设置、接收／提交 Voxy 队列计数与错误数。该命令也将状态写入客户端日志；提交队列不等于 Voxy 已完成渲染。计数在换世界或重连时清零。

客户端按实际已加载的原版区块跳过下载，不使用客户端设置视距推断服务器覆盖范围；服务器视距较小时，夹在原版区块与配置视距之间的区域也会请求 LOD。

大范围下载：更新客户端与服务端后，`/lodc radius 1000` 表示 16000 方块半径；管理员同时配置 `lod.radius-chunks: 1000` 并 `/lod reload`。两端范围取较小值，最高 1024 区块，两端默认值均为 1000。未完成请求最多 64 个，超时优先重试；首次大范围填充受服务器限速约束，Voxy 的渲染距离需另设。

下载半径默认值：客户端 1000 区块，服务端 1000 区块，实际取两者较小值；服务端始终强制检查范围。已有客户端手动保存的半径不会被覆盖，可用 /lodc radius 1000 修改。

批量下载：新版 LodClient 在 LSS 握手中协商 `taixue:lod_batch` 扩展（能力位 `1 << 16`、帧版本 1），同一帧可包含模型、缓存未变及地形不存在的回复。帧按字节数自动拆分，最多 64 条且不超过 1,048,000 字节；复用 Minecraft 的传输压缩。没有声明该能力的 LSS 客户端继续使用原协议，状态确认也会合并。

`lod.cached-columns-per-tick` 默认 16，是所有玩家共享的缓存查询预算（1..64），按玩家轮换分配；同时最多两个查询批次、128 个处理中坐标。缺失缓存另排队，仍受 `lod.columns-per-tick` 和最多四个生成任务限制。客户端最多保留 64 个未完成请求，接收模型后以每 tick 最多八份、约 4 ms 的协作时间预算交给 Voxy；等待处理的数据最多 8 MiB，超量数据留待重试。`/lodc status` 显示批量响应、缓存有效确认和待处理模型数量。

原生快速同步：新版两端通过 `taixue:lod_fast_*` 协商独立协议。服务端发布当前授权世界、有效半径内的缓存索引，客户端只请求已有模型；原版区块传输和旧 LSS 协议继续独立工作。索引与数据发送都受世界授权租约约束，旧世界的会话令牌不能复用。缓存失效会撤销对应版本，缺失数据仍通过原有受限队列从已生成地形更新。

服务端在 `voxy-native-v1` 下按 32×32 区域保存可复用的预计算数据：使用方块／生物群系身份字典、体素游程和五级 mip，不包含客户端私有映射 ID。派生区域计入现有世界缓存预算。`lod.native-columns-per-tick` 默认 512，所有玩家共享，最多两个后台任务；首次预计算完成前的速度会较低。

LodClient 在后台直接调用 Voxy 的 WorldUpdater 导入预计算体素，不再构建 Minecraft 区块对象。若两端版本导致不透明度不同，则以客户端 Voxy 的规则重算 mip。接收缓冲最多 32 MiB、在途坐标最多 4096；原生数据在客户端 `lod-native-cache-v1` 按服务器身份和世界身份隔离保存，重连时可直接复用。`/lodc status` 显示原生同步的待处理、下载、本地复用和导入计数；导入计数不代表 GPU 已完成渲染。

本次原生快速同步构建按维护者要求编译后直接暂存部署，未进行最终运行验证或性能验收，不能视为已证明达到百倍提速或全世界 10～15 秒渲染完成。

预热资源配置修改后执行 `/lod reload` 即可生效；管理服务连接设置未变时保留授权和扫描进度。`prewarm.global.min-tps`（默认 19.5）与 `max-mspt`（默认 40）控制低负载恢复条件，低于 TPS 阈值或超过耗时阈值时暂停新预热工作及后台区域／进度扫描，已在途工作可以完成。TPS 使用最近 100 个 tick 的实际墙钟时间测量。`scan-duty-percent`（默认 25）限制每个扫描线程的工作占比，避免仅降低并发却仍持续解压和分配内存。上述设置均可热重载；它们不能消除其他插件或主机换页造成的卡顿。

`prewarm.global.max-heap-percent`（默认 75）限制后台预热、区域／进度扫描和全量缓存维护启动时的堆使用率，按 `(totalMemory-freeMemory)/maxMemory` 计算；超限时让出资源给玩家编辑。阈值可热重载，已在途操作可完成，不会删除已有缓存。FAWE 2.16.1 的内存保护恢复补丁及回归测试见 `patches/fawe-memory-recovery/`。

后台生成现在每秒按实际 tick 节奏、平均 MSPT、整机 CPU、堆占用及 Linux MemAvailable/内存 PSI 自动调整资源预算。CPU 达 65%/80% 时分别限制到 50%/25%，达 95% 暂停；TPS 低于 18.5、平均 MSPT 达 45ms、主线程超过 250ms 无心跳、堆达配置的 `prewarm.global.max-heap-percent`、可用内存低于 768MiB 或内存 PSI some avg10 达 5% 时暂停。每连续十个稳定的秒级样本只恢复一级，避免反复启停。现有更严格的扫描、并发和堆预算仍生效；`/lod warm status` 显示预算及原因。后台扫描和 native 预计算受预算约束，玩家已有缓存的授权读取仍可用。

暂停时卸载刷新队列只保留坐标，不保留完整区块快照；native 区域索引/字节缓存可释放，恢复后从磁盘重建，索引最多保留 128 个区域。目录版本和覆盖进度改用紧凑区域结构，预计算不再保留全目录的第二份列表。不会清除磁盘缓存、改变协议或生成未探索地形，也不会调用强制 GC。释放的是可重建对象引用，JVM/操作系统的 RSS 不保证立即下降。
