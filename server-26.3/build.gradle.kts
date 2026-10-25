plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.3.1"
}

kotlin { jvmToolchain(25) }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

group = "cc.taixue.lod"

version = "0.2.0+mc26.3-capacity.20261026"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // WorldEdit is optional; its Minecraft-pinned runtime dependencies belong to the server.
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.12") { isTransitive = false }
    compileOnly("com.google.guava:guava:33.6.0-jre")
    implementation("org.json:json:20250107")
    implementation("org.tukaani:xz:1.10")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Share protocol, authorization and cache code; only the Paper API and JVM target differ.
sourceSets.main {
    java.setSrcDirs(listOf("../core/src/main/java", "../server/src/main/java"))
    resources.setSrcDirs(listOf("../server/src/main/resources"))
}

kotlin.sourceSets.main {
    kotlin.setSrcDirs(
        listOf(
            "../core/src/main/kotlin",
            "../server/src/main/kotlin",
            "../core/src/main/java",
            "../server/src/main/java",
        )
    )
}

kotlin.sourceSets.test {
    kotlin.setSrcDirs(listOf("../core/src/test/kotlin", "../server/src/test/kotlin"))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") {
        filter { it.replace("'0.2.0'", "'${project.version}'").replace("'1.21.4'", "'26.3'") }
    }
}

tasks.shadowJar {
    archiveFileName.set("LodServer-0.2.0+mc26.3.jar")
    mergeServiceFiles()
    relocate("org.json", "cc.taixue.lod.lib.json")
    relocate("org.tukaani.xz", "cc.taixue.lod.lib.xz")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    from(file("../LICENSE"))
    from(file("../third-party")) { into("META-INF/third-party") }
}

tasks.build { dependsOn(tasks.shadowJar) }

val fixture by sourceSets.creating {
    java.setSrcDirs(listOf("../server/src/fixture/java"))
    resources.setSrcDirs(listOf("../server/src/fixture/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}

kotlin.sourceSets.named("fixture") {
    kotlin.setSrcDirs(listOf("../server/src/fixture/kotlin", "../server/src/fixture/java"))
}

tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveFileName.set("LodServerFixture.jar")
    from(fixture.output)
}
