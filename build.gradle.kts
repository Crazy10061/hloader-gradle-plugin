plugins {
    `java-gradle-plugin`
}

group = "com.hloader"
version = "0.1.0-SNAPSHOT"

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
    plugins {
        create("hloader") {
            id = "com.hloader.plugin"
            implementationClass = "com.hloader.gradle.HloaderPlugin"
        }
    }
}
