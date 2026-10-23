import net.fabricmc.loom.task.RemapJarTask

plugins { id("fabric-loom") }

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")
    implementation(project(":core"))
    include(project(":core"))
    include("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    System.getenv("ARCH_LOD_TEST_MODS")?.let { directory ->
        modRuntimeOnly(fileTree(directory) { include("*.jar") })
        runtimeOnly(fileTree("$directory/nested") { include("*.jar") })
    }
}

val fixture by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

loom {
    mods {
        create("taixue_lod") { sourceSet(sourceSets.main.get()) }
        if (System.getenv("ARCH_LOD_NATIVE_TEST") != null)
            create("archlod_client_fixture") { sourceSet(fixture) }
    }
    runs {
        named("client") {
            runDir(System.getenv("ARCH_LOD_RUN_DIR") ?: "run")
            vmArgs("-Xmx1536m")
            System.getenv("ARCH_LOD_NATIVE_TEST")?.let { mode ->
                source(fixture)
                vmArg("-Darchlod.fixture.report=" + System.getenv("ARCH_LOD_NATIVE_REPORT"))
                vmArg("-Darchlod.fixture.mode=$mode")
                if (mode == "dh") vmArg("-Dmixin.env.remapRefMap=false")
                programArgs(
                    "--username",
                    "LODNative",
                    "--quickPlayMultiplayer",
                    System.getenv("ARCH_LOD_NATIVE_SERVER"),
                    "--width",
                    "640",
                    "--height",
                    "480",
                )
            }
        }
    }
}

base { archivesName.set("LodClient") }

tasks.jar {
    from(
        listOf(
            rootProject.file("third-party/KOTLIN-LICENSE"),
            rootProject.file("third-party/KOTLIN-PROVENANCE.txt"),
        )
    ) {
        into("META-INF/third-party")
    }
    from(rootProject.file("LICENSE"))
    from(rootProject.file("third-party/LSS-LICENSE")) { into("META-INF/third-party") }
    from(rootProject.file("third-party/LSS-PROVENANCE.txt")) { into("META-INF/third-party") }
}

val fixtureJar by
    tasks.registering(Jar::class) {
        dependsOn(tasks.named(fixture.classesTaskName))
        archiveClassifier.set("fixture-dev")
        from(fixture.output)
    }

tasks.register<RemapJarTask>("remapFixtureJar") {
    dependsOn(fixtureJar)
    inputFile.set(fixtureJar.flatMap { it.archiveFile })
    archiveClassifier.set("fixture")
    addNestedDependencies.set(false)
}
