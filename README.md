# LOD-S / LOD-C

独立的 Minecraft 共享远景缓存项目，同时支持 Voxy 和 Distant Horizons。

- **LOD-S**：Paper 1.21.4 / Java 21 服务端插件，插件内部名称为 ArchLOD。
- **LOD-C**：Fabric 客户端辅助 mod，分别提供 Minecraft 1.21.11（Java 21）和 26.3（Java 25）版本。Voxy 远景渲染仍需安装 Voxy 及其依赖；DH 可直接接收服务端远景。

安装、权限、世界名单、缓存与验证范围见 [README.txt](README.txt)；26.3 说明见 [client-26.3/README.txt](client-26.3/README.txt)。

## 命令

服务端使用 `/lod`（别名 `/lod-s`），例如 `/lod cache status`、`/lod warm status`、`/lod reload`。客户端使用 `/lod-c`，例如 `/lod-c radius 64`、`/lod-c worlds`。原 `/dhs`、`/lodclient` 已移除；服务端不再占用 DH 的 `/dh` 命令。权限节点与现有配置、缓存目录继续沿用。

## 构建

Java 21：

```sh
./gradlew --no-daemon :core:test :server:test :server:shadowJar :client:remapJar
```

Java 25：

```sh
cd client-26.3
./gradlew --no-daemon build
```

## 自动发布

推送到 `main` 后，GitHub Actions 运行上述测试和编译，全部成功才创建带有提交号的 Release，上传服务端、两个客户端版本和 SHA256 校验文件。也支持手动触发工作流。每个 Release 对应同一提交，源码可从该 Release 的标签下载。

客户端只安装与 Minecraft 对应的一个版本。Release 中的 `LOD-S` 文件放服务端 `plugins/`，`LOD-C` 文件放客户端 `mods/`。现有 ArchLOD 安装升级时须移走旧插件 jar，避免重复加载同一个插件。

CI 执行单元测试和编译，不启动需要本机运行时及图形环境的集成测试。许可证及第三方来源见 [LICENSE](LICENSE) 和 [third-party](third-party)。
