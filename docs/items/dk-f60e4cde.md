---
id: dk-f60e4cde
type: task
created: 2026-11-01
status: todo
since: 2026-11-01
area: android
priority: P2
rank: zzzzzzzt
parent:
fixes: []
blocked_by: []
relates: []
---
# Build: name the debug-only Compose UI test group instead of a class glob; one Test configureEach block

app/build.gradle.kts:130 excludes **/ColorWheelPickerTest* from testReleaseUnitTest because ui-test-manifest is debugImplementation only. The next Compose UI test will break the release unit tests unless someone remembers to edit that glob. Name the group once, e.g. a *UiTest suffix convention or a JUnit category, and exclude that. Also fold this second tasks.withType<Test>().configureEach block (line ~129) into the existing one at line ~84. From the abstraction lens on 91dbfbe..5e10401.
