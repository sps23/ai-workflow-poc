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

tasks.register<JavaExec>("inventoryPdfs") {
    group = "verification"
    description = "Generate a deterministic inventory of the source PDF corpus."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.pdfextractor.PdfInventoryCli")
    args(
        "src/main/resources/car-pricelists",
        "src/main/resources/car-pricelists-extracted/manifest.json",
    )
}

tasks.register<JavaExec>("extractPdfs") {
    group = "verification"
    description = "Extract local PDF page text into validated, change-aware JSON artifacts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.pdfextractor.PdfBatchExtractorCli")
    args(
        "src/main/resources/car-pricelists",
        "src/main/resources/car-pricelists-extracted",
    )
}

tasks.register<JavaExec>("probePdfMatrix") {
    group = "verification"
    description = "Compare Tika text evidence with PDFBox coordinates on the annotated real matrix."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.pdfextractor.PdfMatrixSpikeCli")
    args(
        "src/main/resources/car-pricelists/vw/Golf_MY27.pdf",
        "8",
        "docs/evidence/vw-golf-my27-page-8-exterior-highlights.psv",
        "vw-golf-exterior",
    )
}

tasks.register<JavaExec>("probePeugeot5008Matrix") {
    group = "verification"
    description = "Evaluate the Peugeot 5008 multi-trim options matrix against manual ground truth."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sps23.aiworkflow.pdfextractor.PdfMatrixSpikeCli")
    args(
        "src/main/resources/car-pricelists/peugeot/5008-spec-sheet.pdf",
        "17",
        "docs/evidence/peugeot-5008-page-17-options.psv",
        "peugeot-5008-options",
    )
}

tasks.matching { it.name.startsWith("spotless") && it.name.endsWith("Check") }.configureEach {
    mustRunAfter("spotlessApply")
}
