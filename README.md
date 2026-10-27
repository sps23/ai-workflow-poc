# ai-workflow-poc
AI Workflow POC

## Build

Requires JDK 17. Use the included Gradle wrapper:

```sh
./gradlew build
./gradlew dependencies --configuration runtimeClasspath
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The project starts with Koog/OpenRouter, Apache Tika, Ktor, kotlinx coroutines
and serialization, and Compose Desktop dependencies. No application entry point
or tests are defined yet.

Compatibility and security adjustments to the requested starting libraries:

- Koog publishes its core as `ai.koog:agents-core`, not `ai.koog:koog-core`.
- Koog 1.3.0 uses Kotlin 2.3.10, so the Kotlin JVM, serialization, and Compose
  compiler plugins use that version rather than 2.0.21.
- Tika uses 3.2.3 instead of 3.0.0 to avoid known XXE vulnerabilities.
- Gradle may select newer transitive Ktor, coroutines, and serialization versions
  required by Koog; the dependency report shows the resolved versions.
