plugins {
    java
    id("com.gradleup.shadow")
}

repositories { maven("https://maven.enginehub.org/repo/") }

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.12")
    compileOnly("com.google.guava:guava:33.3.1-jre")
    implementation("org.json:json:20250107")
    implementation("org.tukaani:xz:1.10")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

tasks.shadowJar {
    archiveFileName.set("LodServer-0.2.0.jar")
    mergeServiceFiles()
    relocate("org.json", "cc.taixue.lod.lib.json")
    relocate("org.tukaani.xz", "cc.taixue.lod.lib.xz")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    from(rootProject.file("LICENSE"))
    from(rootProject.file("third-party")) { into("META-INF/third-party") }
}

tasks.build { dependsOn(tasks.shadowJar) }

val fixture by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}

tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveFileName.set("LodServerFixture.jar")
    from(fixture.output)
}
