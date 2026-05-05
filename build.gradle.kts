plugins {
    id("org.jetbrains.intellij.platform") version "2.14.0"
    kotlin("jvm") version "2.1.21"
    java
}

group = "com.swissas.amos"
version = "1.0.0"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.1")
        bundledPlugin("org.jetbrains.idea.eclipse")
        bundledPlugin("Git4Idea")
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("251")      // IntelliJ 2025.1
        untilBuild.set("")         // no upper bound — compatible with future versions
    }
}



