import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.3.1" apply false
    id("fabric-loom") version "1.16.2" apply false
}

subprojects {
    group = "cc.taixue.lod"
    version = "0.2.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
    plugins.withId("java") {
        configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
        tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
        dependencies {
            add("testImplementation", platform("org.junit:junit-bom:5.11.4"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}

repositories { mavenCentral() }

val ktfmt by configurations.creating

dependencies { ktfmt("com.facebook:ktfmt:0.64:with-dependencies@jar") }

val kotlinFiles =
    fileTree(rootDir) {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
    }

fun JavaExec.configureFormatter(check: Boolean) {
    classpath = ktfmt
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style")
    if (check) args("--dry-run", "--set-exit-if-changed")
    args(kotlinFiles.files.sorted().map { it.absolutePath })
}

tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Format Kotlin sources and build scripts using the official Kotlin style."
    configureFormatter(false)
}

tasks.register<JavaExec>("checkKotlinFormat") {
    group = "verification"
    description = "Check Kotlin sources and build scripts without modifying them."
    configureFormatter(true)
}
