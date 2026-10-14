buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.5.0")
    }
}

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta10"
}

val pluginVersion: String = (project.findProperty("pluginVersion") as String?) ?: "1.0.0-SNAPSHOT"
group = "com.lunatech.tpcore"
version = pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("org.spongepowered:configurate-yaml:4.1.2")
    implementation("com.zaxxer:HikariCP:5.1.0")

    testImplementation("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7")
    testImplementation("me.clip:placeholderapi:2.11.6")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf("version" to pluginVersion)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("raw")
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("TPCore-$pluginVersion.jar")
        relocate("org.spongepowered.configurate", "com.lunatech.tpcore.libs.configurate")
        relocate("com.zaxxer.hikari", "com.lunatech.tpcore.libs.hikari")
    }

    register<proguard.gradle.ProGuardTask>("proguard") {
        dependsOn(shadowJar)

        val shadowJarTask = shadowJar.get()
        val inputFile = shadowJarTask.archiveFile.get().asFile
        val outputFile = file("${layout.buildDirectory.get().asFile}/libs/TPCore-$pluginVersion-min.jar")

        injars(inputFile)
        outjars(outputFile)

        libraryjars("${System.getProperty("java.home")}/jmods/java.base.jmod")
        configurations.compileClasspath.get().files.forEach { file ->
            libraryjars(file)
        }

        configuration("proguard-rules.pro")
    }

    build {
        dependsOn(shadowJar)
    }
}
