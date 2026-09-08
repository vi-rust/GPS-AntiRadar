# GPS AntiRadar Kotlin migration plan

## Goal

Move all application and test source code from Java to Kotlin without changing runtime behavior, package names, Android component names, alert selection, speed thresholds, map rendering, or Android Auto behavior.

## Scope

- Convert every production source to `.kt` and place it under the standard `src/main/kotlin/ru/gpsantiradar/app` package path.
- Convert every unit test under `src/test` and `tests` to `.kt`, and integrate the standalone suite into Gradle through a Kotlin wrapper.
- Convert the root build and settings scripts from Groovy DSL to Kotlin DSL.
- Update source-contract checks so they validate behavior/API contracts independently of Java syntax.
- Do not change release version or publish/commit as part of the migration.

## Stages

1. Verify Kotlin compilation in the custom `src/` source set with one leaf type.
2. Convert pure models, rules, formatting, theme, and diff/layout helpers.
3. Convert persistence, parsing, update coordination, and sound/tracking infrastructure.
4. Convert Android Auto screens, session, map presentation, and surface controller.
5. Convert phone UI, shared map layer, application, and services.
6. Convert all unit tests and update their Gradle entry points.
7. Remove obsolete Java interoperability annotations and replace Java-only source checks with Kotlin-aware checks.
8. Verify no `.java` sources remain, then run contract tests, debug/release unit tests, lint, and a clean release build.
9. Convert `build.gradle` and `settings.gradle` to their `.gradle.kts` equivalents and validate Gradle configuration loading.
10. Move production sources out of the legacy `src/ru/hudspeed/pro` tree and remove its custom Kotlin source-set entry.

## Compatibility rules

- Preserve `ru.gpsantiradar.app` JVM class names used by the manifest.
- Keep `const val` where compile-time constants are useful, but remove `@JvmField`, `@JvmStatic`, and `@JvmOverloads` after the last Java caller is converted.
- Keep nullable platform inputs explicit and avoid introducing `!!` where a checked branch exists.
- Prefer literal, behavior-preserving conversion before Kotlin-specific refactoring.
- Stop and fix each compilation/test failure before converting the next dependency layer.
