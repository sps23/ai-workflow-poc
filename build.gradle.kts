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
        ktlint("1.8.0").editorConfigOverride(
            mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
        )
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

    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "com.sps23.aiworkflow.ui.CarPromptAppKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn("spotlessCheck")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (providers.gradleProperty("skipUiRenderTests").isPresent) {
        filter {
            excludeTestsMatching("com.sps23.aiworkflow.ui.CarPromptScreenTest")
        }
    }
}

tasks.named("build") {
    dependsOn("spotlessApply")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    mustRunAfter("spotlessApply")
}

tasks.register<JavaExec>("generateDataModelCatalog") {
    group = "application"
    description = "Normalize every car JSON, link its source PDF, and overwrite the generated catalog."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogGeneratorCli")
    outputs.file("src/main/resources/vehicle-catalog/data-model-catalog.json")
    outputs.upToDateWhen { false }
    args(
        "src/main/resources/car-pricelists",
        "src/main/resources/vehicle-catalog/data-model-catalog.json",
    )
}

tasks.register<JavaExec>("carPrompt") {
    group = "application"
    description = "Send one prompt plus the generated car catalog to an allowed OpenRouter model."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.recommendation.CarPromptCliKt")
}

tasks.matching { it.name.startsWith("spotless") && it.name.endsWith("Check") }.configureEach {
    mustRunAfter("spotlessApply")
}
