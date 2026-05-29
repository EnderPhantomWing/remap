import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    `maven-publish`
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

group = "com.github.replaymod"
version = "SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

val testA by sourceSets.creating
val testB by sourceSets.creating

kotlinVersion("1.5.21")
kotlinVersion("1.6.20")
kotlinVersion("1.9.0")
kotlinVersion("2.0.0")
kotlinVersion("2.3.21", isPrimaryVersion = true)

dependencies {
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    api("org.cadixdev:lorenz:0.5.8")
    runtimeOnly("net.java.dev.jna:jna:5.18.1") // don't strictly need this but IDEA spams log without

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")

    testRuntimeOnly(testA.output)
    testRuntimeOnly(testB.output)
    testRuntimeOnly("org.spongepowered:mixin:0.8.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("remap")
}

publishing {
    publications {
        create("maven", MavenPublication::class) {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
    }
}

fun kotlinVersion(version: String, isPrimaryVersion: Boolean = false) {
    val name = version.replace(".", "")

    val sourceSet = sourceSets.create("kotlin$name")

    val testClasspath = configurations.create("kotlin${name}TestClasspath") {
        extendsFrom(configurations.testRuntimeClasspath.get())
        extendsFrom(configurations[sourceSet.compileOnlyConfigurationName])
    }

    dependencies {
        implementation(sourceSet.output)
        sourceSet.compileOnlyConfigurationName("org.jetbrains.kotlin:kotlin-compiler-embeddable:$version")
    }

    tasks.jar {
        from(sourceSet.output)
    }

    if (!isPrimaryVersion) {
        val testTask = tasks.register("testKotlin$name", Test::class) {
            useJUnitPlatform()
            testClassesDirs = sourceSets.test.get().output.classesDirs
            classpath = testClasspath + sourceSets.test.get().output + sourceSets.main.get().output
        }
        tasks.check { dependsOn(testTask) }
    }
}
