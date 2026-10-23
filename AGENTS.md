# LOD project conventions

- Use Kotlin for all first-party production code, unit tests, integration fixtures, and Gradle build scripts.
- Follow Kotlin Official Style. Keep `kotlin.code.style=official`; run `./gradlew formatKotlin` to format and `./gradlew checkKotlinFormat` to verify. Formatting uses ktfmt's `--kotlinlang-style` (four-space indentation).
- Use Java only for a documented technical constraint, such as a Mixin target that requires it. Record the concrete constraint next to the code; convenience or an existing Java caller is not a constraint.
- Exception authorized by the project owner: vendored DH Support (`server/src/main/java/no/jckf/dhsupport`) and LSS wire code (`core/src/main/java/dev/vox/lss/common/wire`) may remain Java. Preserve attribution, license notices and provenance. The first-party Paper entry point is Kotlin even though it retains the upstream package for compatibility.
- Add concise KDoc to public responsibilities, and comments explaining security boundaries, lifetime/thread ownership, and Minecraft-version assumptions. Do not narrate obvious statements or compress multiple operations into one line.
- Preserve the existing LSS/DH wire and cache formats, world-access leases, upload validation, and the separate Java 21 / Java 25 Minecraft builds. Recheck authorization immediately before sending terrain.
- Ship the Kotlin runtime with the artifacts: the Paper shadow jar contains it; Fabric jars include it as a nested dependency. Players should not need an extra runtime mod solely for LOD-C.
