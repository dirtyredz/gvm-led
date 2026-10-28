---
id: dk-2c9a65da
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: release
priority: P2
rank: zzw
parent:
fixes: []
blocked_by: []
relates: []
---
# Release tooling: shared GitHub transport base (github/common.js)


Push review 2026-10-28 (abstraction). publishing/github/gh-session.js imports listAllReleases/meterToFile from gh-client.js (the CI token transport), and both redefine API_VERSION, fail/RELEASE_GITHUB and the uploads host. Direction: move pagination, metered download, GitHub constants and the error factory into github/common.js and name the {request, upload, downloadAsset, allReleases} surface once there.
