---
id: dk-edc157f5
type: task
created: 2026-10-31
status: todo
since: 2026-10-31
area: release
priority: P2
rank: zzzzzt
parent:
fixes: []
blocked_by: []
relates: []
---
# Release tooling: one streaming sha256 helper


Baseline review 2026-10-31 (abstraction lens).

Facts: streaming sha256-with-size exists in src/mobile/releases/publishing/assets.js:11 (describeAsset) and publishing/github/gh-client.js:35-48 (meterToFile); a one-shot sha256 lambda is in signing/key-generation.js:39.

Direction: one digest helper beside assets.js used by all three.
