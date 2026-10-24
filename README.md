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
