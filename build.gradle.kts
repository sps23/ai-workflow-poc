plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("com.diffplug.spotless") version "8.10.4"
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(25)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
    }
}

dependencies {
    implementation("ai.koog:agents-core:1.3.0")
    implementation("ai.koog:prompt-executor-openrouter-client:1.3.0")
    implementation("ai.koog:http-client-ktor:1.3.0")

    implementation("org.apache.tika:tika-core:3.3.3")
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.3")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation(compose.desktop.currentOs)

    testImplementation(kotlin("test-junit"))
}

tasks.withType<Test>().configureEach {
    dependsOn("spotlessCheck")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("build") {
    dependsOn("spotlessApply")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    mustRunAfter("spotlessApply")
}

tasks.matching { it.name.startsWith("spotless") && it.name.endsWith("Check") }.configureEach {
    mustRunAfter("spotlessApply")
}
