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

太学 MC Service 的同步路径为 `/v1/internal/lod/sync`，服务器凭据置于 `project.lod.server-tokens.<server-id>`；网页入口为世界项目设置里的“远景模型”。仅网站管理员可以修改世界开关，项目管理员及只有观察权限的访问者不能获得额外授权。接入不改变 LSS/DH 缓存格式或客户端协议，因此不要求更换客户端 mod。
