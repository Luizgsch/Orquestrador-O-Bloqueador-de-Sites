import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("android") version "1.9.25" apply false
    id("com.android.application") version "8.3.0" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1"
    java
    application
}

group = "com.orquestrador"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.orquestrador.MainKt")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveVersion.set("")
    archiveClassifier.set("")
    archiveFileName.set("orquestrador-bloqueador.jar")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.orquestrador.MainKt"
    }
}

tasks.register<ShadowJar>("shadowJarGuardian") {
    group = "build"
    description = "Fat JAR do GuardianWatchdog (mútua sobrevivência systemd)"
    from(sourceSets["main"].output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    archiveVersion.set("")
    archiveClassifier.set("")
    archiveFileName.set("guardian-watchdog.jar")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.orquestrador.GuardianWatchdogKt"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar, tasks.named("shadowJarGuardian"))
}
