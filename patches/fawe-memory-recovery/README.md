# FAWE memory protection recovery

Emergency fix for the installed FastAsyncWorldEdit Paper 2.16.1 build (upstream commit `f61a1b62`). Original jar SHA-256: `a1706c41412a108e3d2fdbd9e9dd0c60904f2ba45414c6edf11c1262812e1838`.

The upstream `MemUtil` protection flag can remain set after GC shrinks the committed heap below its maximum: `calculateMemory()` returns before clearing it. All edits can then fail even with ample heap headroom. The patch calculates actual headroom relative to max heap and refreshes the flag when queried. The configured memory threshold remains enforced; recovered edits avoid unnecessary forced GC.

Upstream Java source: <https://github.com/IntellectualSites/FastAsyncWorldEdit/blob/main/worldedit-core/src/main/java/com/fastasyncworldedit/core/util/MemUtil.java>. This is a narrowly scoped third-party Java patch; upstream licensing and jar contents are preserved.

Apply `recovery.patch` to upstream `MemUtil.java`, compile that class against the original FAWE jar and Paper log4j-api with Java 21 or later, and replace only `com/fastasyncworldedit/core/util/MemUtil.class` in a copy of the jar. Verify every other zip entry remains identical. Never bundle the regression-only `Settings` stub into the plugin.

`RecoveryTest.kt` and `Settings.kt` run with a 16 MiB initial / 64 MiB maximum heap, patched class before the original jar on the classpath. They reproduce the original failure and verify flag recovery, preservation of the configured threshold, and no forced GC on recovery. Tested against both original and patched classes; original fails, patched passes.
