plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.skillatlas"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

// Live-тесты ходят в реальный GitHub: тратят rate limit и требуют GITHUB_TOKEN.
// По умолчанию выключены, включаются через ./gradlew test -Dlive=true
val liveProp: String? = providers.systemProperty("live").orNull
val liveTestsEnabled: Boolean = liveProp != null && !liveProp.equals("false", ignoreCase = true)

tasks.test {
    useJUnitPlatform {
        if (!liveTestsEnabled) {
            excludeTags("live")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass.set("com.skillatlas.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("skill-atlas")
    archiveClassifier.set("")
    archiveVersion.set("")
}
