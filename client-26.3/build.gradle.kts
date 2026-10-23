plugins {
    id("net.fabricmc.fabric-loom") version "1.17.21"
    kotlin("jvm") version "2.4.21"
}

kotlin { jvmToolchain(25) }

group = "cc.taixue.lod"

version = "0.2.0+mc26.3"

repositories { mavenCentral() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    include("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("net.fabricmc.fabric-api:fabric-api:0.165.0+26.3")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Isolate version-specific API names and native section headers from the 1.21.11 client.
val portSources by tasks.registering {
    val sources =
        listOf("../core/src/main/java", "../core/src/main/kotlin", "../client/src/main/kotlin")
    sources.forEach { inputs.dir(it) }
    val output = layout.buildDirectory.dir("generated/sources/port")
    outputs.dir(output)
    doLast {
        val replacements =
            linkedMapOf(
                "NATIVE_COUNT_SHORTS = 1;" to "NATIVE_COUNT_SHORTS = 2;",
                "ClientCommandManager." to "ClientCommands.",
                "player.displayClientMessage(Component.literal(message), false)" to
                    "player.sendSystemMessage(Component.literal(message))",
                "PayloadTypeRegistry.playC2S()" to "PayloadTypeRegistry.serverboundPlay()",
                "PayloadTypeRegistry.playS2C()" to "PayloadTypeRegistry.clientboundPlay()",
                "chunkPosition().x" to "chunkPosition().x()",
                "chunkPosition().z" to "chunkPosition().z()",
                "state.values.entries" to "state.values.toList()",
                "it.key.name to valueName(it.key, it.value)" to
                    "it.property().name to valueName(it.property(), it.value())",
            )
        val used = mutableSetOf<String>()
        val destination = output.get().asFile
        delete(destination)
        for (source in sources) {
            fileTree(source) { include("**/*.java", "**/*.kt") }
                .forEach { input ->
                    var text = input.readText()
                    replacements.forEach { (before, after) ->
                        if (text.contains(before)) {
                            used += before
                            text = text.replace(before, after)
                        }
                    }
                    val target = destination.resolve(input.relativeTo(file(source)))
                    target.parentFile.mkdirs()
                    target.writeText(text)
                }
        }
        check(used == replacements.keys) {
            "Shared sources changed; review the 26.3 port replacements"
        }
    }
}
val portDirectory = layout.buildDirectory.dir("generated/sources/port")

sourceSets.main {
    java.setSrcDirs(listOf(portDirectory))
    resources.setSrcDirs(listOf("../client/src/main/resources"))
}

kotlin.sourceSets.main { kotlin.setSrcDirs(listOf(portDirectory)) }

tasks.compileKotlin { dependsOn(portSources) }

tasks.compileJava { dependsOn(portSources) }

tasks.processResources {
    filesMatching("fabric.mod.json") {
        filter { line ->
            line
                .replace("\"0.2.0\"", "\"0.2.0+mc26.3\"")
                .replace("\"1.21.11\"", "\"26.3\"")
                .replace(">=0.18.4", ">=0.20.1")
                .replace(">=21", ">=25")
        }
    }
}

base { archivesName.set("lodclient") }

tasks.jar {
    from(listOf("../third-party/KOTLIN-LICENSE", "../third-party/KOTLIN-PROVENANCE.txt")) {
        into("META-INF/third-party")
    }
    from("../LICENSE")
    from(listOf("../third-party/LSS-LICENSE", "../third-party/LSS-PROVENANCE.txt")) {
        into("META-INF/third-party")
    }
}

val fixture by sourceSets.creating {
    resources.srcDir("../client/src/fixture/resources")
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

kotlin.sourceSets.named("fixture") { kotlin.srcDir("../client/src/fixture/kotlin") }

tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveClassifier.set("fixture")
    from(fixture.output)
}

loom { mods { create("taixue_lod") { sourceSet(sourceSets.main.get()) } } }

kotlin.sourceSets.test { kotlin.srcDir("../core/src/test/kotlin") }

tasks.test { useJUnitPlatform() }
