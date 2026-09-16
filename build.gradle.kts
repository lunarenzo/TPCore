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
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    implementation("org.spongepowered:configurate-yaml:4.1.2")
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

    processResources {
        val props = mapOf("version" to pluginVersion)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("TPCore-$pluginVersion.jar")
        relocate("org.spongepowered.configurate", "com.lunatech.tpcore.libs.configurate")
    }

    build {
        dependsOn(shadowJar)
    }
}
