# STRUCTURE

## Overview

Custom controller for two GVM 800D3X-RGB lights over the legacy plain BLE GATT path. No app code
yet; the repo holds research, docs and owner-run script plumbing. Platform (phone, PC or family
command center) is undecided (see Docket).

## Architecture at a glance

Host (TBD) -> BLE GATT write-without-response to one characteristic per light -> Telink-based light.
See [ARCHITECTURE](docs/ARCHITECTURE.md).

## Layout

```
docs/
├── research/    # protocol write-up (static analysis + live findings)
└── items/       # Docket work items (dk only)
scripts/
└── owner/       # owner-run PowerShell steps and plumbing
.tmp/            # git-ignored: scan scripts, decompiled app, logs
```

**Enforced homes:**

- `scripts/owner/` — owner-run .ps1 steps (owner-run.ps1 1.0.0 copied verbatim from ~/.claude/templates/scripts)

## Components

- **Research** — `docs/research/protocol.md`: source of truth for the protocol.
- **Owner-run plumbing** — `scripts/owner/`: logging/dry-run wrapper plus an example step and test.
- **Prototype scripts** — `.tmp/scan/{scan,gatt,live_test}.py` (Python + bleak), untracked until the platform is chosen.

## Where to find things

Protocol framing, commands, lights' addresses: `docs/research/protocol.md`. Work: `dk list`.

## Structural debt

Prototype scripts are untracked in `.tmp/scan/` (dk-5f0c19af).

_Living doc — refresh with /project-docs when it drifts._
