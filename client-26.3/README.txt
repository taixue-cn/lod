LodClient — Minecraft 26.3 / Fabric

Build separately from the Java 21 server and Minecraft 1.21.11 client:
  JAVA_HOME=/path/to/jdk-25 ./gradlew --no-daemon clean build fixtureJar

Player artifact: build/libs/LodClient-0.2.0+mc26.3.jar
Install into the client's mods directory with:
  Minecraft 26.3, Java 25+, Fabric Loader 0.20.1+, Fabric API for 26.3.
  Voxy for 26.3 is required to render Voxy terrain.
Do not install the fixture jar; it is only for automated isolated tests.
Do not install both the 1.21.11 and 26.3 editions together (same mod ID).

The build shares the Kotlin client/core sources and the vendored Java wire codec. Its checked portSources task
adapts the changed Fabric/Minecraft API names and the two-short native section
header used by 26.3. The LSS v20 network format stays unchanged. Source changes
that invalidate the expected replacements fail the build for review.

Server: continue using the Paper LodServer plugin. Connecting Minecraft 26.3 to
Paper 1.21.4 also needs a ViaVersion release supporting 26.3. A Fabric server
edition is not provided by this client port.

Integration harness (fresh loopback server only):
  python3 ../scripts/run-integration.py --client-version 26.3 \
    --via-jar /path/to/ViaVersion-5.12.1.jar --native-mode voxy \
    --mods /path/to/26.3-voxy-and-sodium --work-dir /new/test/directory \
    --minecraft-protocol /unused-for-native-test
The native harness needs Xvfb on display :91 and Mesa for software rendering.

Client command: /lodc; server administration: /lods.
Entry points: cc.taixue.lod.client.LodClient and cc.taixue.lod.server.LodServer.
