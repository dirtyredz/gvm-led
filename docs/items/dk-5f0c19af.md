---
id: dk-5f0c19af
type: task
created: 2026-10-26
status: todo
since: 2026-10-26
area: app
priority: P2
rank: n
parent:
fixes: []
blocked_by: []
relates: []
---
# Promote useful .tmp/scan scripts into tracked code

scan.py, gatt.py and live_test.py live in git-ignored .tmp/scan/. Once the platform is chosen, move the useful parts (CRC16/XMODEM framing, hello, state parsing) into tracked code under a declared home in STRUCTURE.md Layout, with tests. Blocked on the platform decision.
