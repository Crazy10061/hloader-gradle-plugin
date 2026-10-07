plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "dev.crazy10061"
version = "0.1.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
}

gradlePlugin {
    website.set("https://github.com/Crazy10061/hloader-gradle-plugin")
    vcsUrl.set("https://github.com/Crazy10061/hloader-gradle-plugin.git")
    plugins {
        create("hloader") {
            id = "dev.crazy10061.hloader"
            implementationClass = "com.hloader.gradle.HloaderPlugin"
            displayName = "hloader"
            description = "Build and run mods for hloader."
            tags.set(listOf("minecraft", "mods"))
        }
    }
}