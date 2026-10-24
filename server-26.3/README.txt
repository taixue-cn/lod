LodServer for Paper 26.3

Requires Java 25. Build with ./gradlew --no-daemon build.
Artifact: build/libs/LodServer-0.2.0+mc26.3.jar
Install only this server jar in plugins/ when using Paper 26.3.
The Paper 1.21.4 artifact remains available from the root server module.

First-party Kotlin and vendored upstream Java sources, tests, configuration and
licenses are shared with ../server and ../core. No fork of the authorization,
LOD protocol or cache implementation is maintained. This build targets the
Paper 26.3 API and sets api-version: 26.3. Kotlin runtime is embedded.

Plugin name: LodServer. Entry point: cc.taixue.lod.server.LodServer.
Commands: /lod and /lods. Data directory: plugins/LodServer/.
Client mod: LodClient for the player's Minecraft version; Voxy still needs
its original mod and dependencies. See ../README.txt for permissions and setup.
