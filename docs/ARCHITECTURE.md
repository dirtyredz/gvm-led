# ARCHITECTURE

## Overview
A controller talks directly to each light over BLE; no cloud, no mesh, no pairing.

## Link
- Light advertises as `BT_LED`, Telink SoC (USB VID 0x248A). Address is the per-light identity.
- Service `00010203-0405-0607-0809-0a0b0c0d1910`, characteristic `...2b10` (notify, write-without-response, read).
- One connection per light at a time.

## Framing
`4C 54 | LEN | DEVID | DEVTYPE | body | CRC_hi CRC_lo | FF`; CRC16/XMODEM over 4C..body, big-endian.
Hello `4C 54 09 00 00 53 00 00 01 00 94 74`; set body `57 00 CMD 01 VAL`; query body `52 00 00 04`.
Lights push a state report (kind 03) after hello and about every 4 s.

## Commands
power 00, pick 01, brightness 02 (0-100), CCT 03 (K/100), hue 04 (deg/5), saturation 05, mode 06 (1 CCT, 2 HSI, 3 scenes), scene 07, effect time 08.
The "colour bug" fix: always send mode 06=02 first, then hue, saturation, brightness, and confirm via the state report.

Full detail: [protocol.md](research/protocol.md).

_Living doc — refresh with /project-docs when it drifts._
