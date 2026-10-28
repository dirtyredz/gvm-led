# Controls acceptance (revised slices 3, 4 and 5)

Phone steps for the owner after installing the signed release that contains the `controls` branch,
downloaded from GitHub Releases on the phone (Settings, Check for updates, Install; or the release
page). No adb, no debug build. Run them in order; each step says what must be seen. Record results
on dk-3189df72, dk-84d5132d and dk-b31f4e95.

**Before you start:** both lights powered, in range, and the official GVM app fully closed (it holds
the only connection). The rear knobs do nothing in app mode, so every change below comes from this
app; any starting state is set beforehand in the official app or by a power cycle, then that app is
closed.

## Slice 3: wanted state survives the upgrade

1. Open the app. Both lights reach **Live** and each card shows **Settings: Confirmed** within a few
   seconds, with no visible change to either light (first use adopts what the light shows).
2. Settings, Lights and Groups are unchanged: names, and any groups (for example "Key and Fill").
3. Force-stop the app (Android Settings, Apps, GVM LED, Force stop) and reopen it. The lights do not
   change, and each card again reaches **Settings: Confirmed**.

## Slice 4: the colour-bug fix

**What the official app gets wrong:** with a light showing white (CCT), set a colour in the official
app's colour (HSI) screen, then leave the app and come back (or reconnect). On return it re-sends
only intensity; the light stays in, or falls back to, white, and the colour only sticks after you
toggle to the white screen and back to colour.

**Reproduce the starting state:** in the official app put both lights in **white (CCT)**, close the
official app completely.

4. In this app select one light (say Key). Tap **Colour (HSI)**, move **Hue** to about 240° (blue)
   and release. The light turns **blue** directly, with no detour through white and no toggling,
   and the card goes **Applying**, then **Confirmed**. The observed state on the card reads HSI,
   hue 240°.
5. Select the other light (Fill), choose a clearly different colour (hue about 0°, red). Key stays
   blue; Fill turns red; both cards Confirmed.
6. Background/resume: press Home, wait 10 s, reopen. Both lights keep their colours (no white flash),
   and both cards go Applying then **Confirmed** (the app re-sends the full colour on reconnect).
7. Restart: force-stop and reopen. Same as step 6.
8. Out-of-range reconnect: carry the phone out of range until a card leaves Live (or switch the
   phone's Bluetooth off for 10 s), then return. The card shows **Waiting for connection**, then
   reconnects and shows Confirmed, colour unchanged.
9. Reconnect after the official app changed the light: close this app, set one light to white in
   the official app, close that app, reopen this one. That light returns to **its colour from this
   app** (mode first), Confirmed. This is the bug the project exists to fix.
10. Unreachable light: switch Fill off at the mains. Edit Key's brightness: Key changes and confirms;
    Fill shows **Waiting for connection** and does not hold Key up. Restore Fill's power: it
    reconnects and confirms its own last settings.

## Slice 5: individual and named-group controls

11. Individual isolation: with Key selected, change brightness, mode and colour. Fill never changes.
12. Group control: select the group "Key and Fill" (create it under Settings, Groups if needed).
    With different colours from step 5 the controls read **Mixed** for hue; brightness reads one
    value only if both match. Tap **On**/**Off**: both follow, keeping their own colours.
13. Field preservation: on the group, change only **Brightness**. Each light keeps its own colour.
14. Group mode: tap **Colour (HSI)** on the group and set hue 120° (green): both turn green; the
    group line reads **2 of 2 confirmed**.
15. CCT endpoints: tap **White (CCT)** and move **Colour temperature** to each end (3200 K and
    5600 K on these lights; the slider stops at the members' common range). Both lights follow and
    confirm at each end.
16. Scenes: tap **Scene**, choose **19**, set **Effect time** to 2000 ms; then scene **20**, 500 ms.
    Both lights run the scene and confirm. (Scene 20's hue/saturation come from static evidence; if
    scene 20 stays "Not confirmed; reconnecting", record it: that is a finding, not a pass.)
17. Partial result: switch Fill off at the mains and change the group's brightness. The group line
    reads **1 of 2 confirmed; Fill waiting for connection**. Restore Fill: it converges and the line
    reads 2 of 2.
18. Overlapping groups: create "Key only" (Key) alongside "Key and Fill". Edit "Key only", then
    "Key and Fill": Key ends with the last edit's value and confirms.
19. Group edits do not rewrite history: edit the group's membership or delete it in Settings. Both
    lights keep their current settings; nothing is re-sent.

Not covered by two fixtures: N > 2 lights on hardware (unit tests use three fake lights).
