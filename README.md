# hubitat-orbitbhyve

> **This is a maintained fork** of [dcmeglio/hubitat-orbitbhyve](https://github.com/dcmeglio/hubitat-orbitbhyve).
> If you already run the original 1.8.0, this is a drop-in update: same app/driver names, namespace and package IDs.

## About this fork

### History

| When | Who | What |
|---|---|---|
| 2017–2019 | [Kurt Sanders](https://github.com/KurtSanders/STOrbitBhyveController) | Original Orbit b•hyve™ SmartThings SmartApp and device handlers. |
| 2020 | [Dominick Meglio](https://github.com/dcmeglio) | Hubitat port and significant rewrite (direct valve control, websocket events). Published here and via Hubitat Package Manager. |
| Oct 2020 | [jpage4500](https://github.com/jpage4500) | Switch capability on valves (PR #1), released as **1.8.0** — the last upstream release. |
| Mar 2021 | — | Last commit to the upstream repository. |
| Apr 2025 | [Matthew Petro](https://github.com/matthewpetro) | [PR #3](https://github.com/dcmeglio/hubitat-orbitbhyve/pull/3): null checks for newer B-hyve devices. Still open and unmerged. |
| Nov 2026 | this fork | PR #3 merged here (author credit kept) plus further null-safety fixes; released as **1.8.1**. |

### Why fork

The B-hyve cloud API now returns fields this integration assumed would always be present.
The most visible symptom is an error logged on every refresh:

```
java.lang.NullPointerException: Cannot invoke method toInteger() on null object on line 265 (method refresh)
```

It happens when a multi-station timer reports a `watering_status` with neither `stations` nor
`current_station`. The exception aborts the refresh, so that timer's zones stop updating in
Hubitat (valve/switch sync, battery, rain delay, programs, next start time). Watering itself
is unaffected because schedules run in the B-hyve cloud.

PR #3 fixes this, but upstream has had no activity since 2021, so the fix hasn't reached
Hubitat Package Manager users. This fork exists to ship that fix and keep the integration
working, with credit to everyone above. If upstream becomes active again, these changes are
happy to go back there.

### Changes in 1.8.1

- Upstream PR #3 (Matthew Petro): null-safe `current_station`, `next_start_time` and program `enabled`.
- Null-safe battery notifications, `watering_status.stations` entries, station lookups for
  child devices whose `station` attribute was never set, and websocket payloads missing
  `run_time` / `seconds`.

Field-tested: with these changes installed, the hourly line-265 error stops and the affected
timer's zones resume updating on the next scheduled refresh.

### Switching an existing install to this fork

- **Hubitat Package Manager:** install from this fork's manifest
  `https://raw.githubusercontent.com/meshoo/hubitat-orbitbhyve/master/packageManifest.json`
  (HPM ▸ Install ▸ From a URL). The app/driver names, namespace and package IDs are unchanged.
- **Manual:** in *Apps Code* and *Drivers Code*, open each existing Orbit Bhyve entry, use
  *Import* with the raw URL of the matching file from this repo, and save. Your existing app
  and devices are kept.

### Diagnostics: B-hyve API Inspector

`apps/tools/bhyve-api-inspector.groovy` is a stand-alone, temporary app that dumps a **redacted** copy of
your B-hyve API data (devices, hubs/meshes, programs, recent watering events). It is used to check the
device model for the upcoming v2 (see `docs/bhyve-api-model.md`). It changes nothing in your account
and doesn't interact with the controller app.

1. *Apps Code* ▸ *New App* ▸ paste the file ▸ *Save*.
2. *Apps* ▸ *Add User App* ▸ *B-hyve API Inspector*. Enter your B-hyve login and press **Fetch and redact**.
3. Copy the output from the page, the logs, or *Settings ▸ File Manager ▸ bhyve-api-inspector.json*.
   **Read it before sharing.** IDs become `ID-n` tokens, names become `Name-n`, and MACs,
   addresses, locations, time zones and tokens are removed.
4. Uninstall the app when you're done.

---

Based on Kurt Sander's SmartThings integration, https://github.com/KurtSanders/STOrbitBhyveController but adds support for some HE
specific functionality such as the ability to control the valves directly.

### Description:

Custom app/drivers which provides a connection to ones Orbit b•hyve™ network attached devices.


## Requirements:

1. One or more of the following [Orbit b•hyve™ Smart Timers and/or Wi-Fi Hub](https://bhyve.orbitonline.com/hosefaucet/) shown below: 
<p align="center">
<img src="https://raw.githubusercontent.com/KurtSanders/STOrbitBhyveController/master/images/icons/bhyveIcon.png" width=200><br>
<a href="https://www.amazon.com/Orbit-B-hyve-21004-Faucet-Compatible/dp/B0758NR8DJ/ref=sr_1_2?s=lawn-garden&ie=UTF8&qid=1519147062&sr=1-2&keywords=bhyve">Amazon™ Orbit b•hyve™ Model 21004</a><br><br>
<img src="https://raw.githubusercontent.com/KurtSanders/STOrbitBhyveController/master/images/icons/ht-12.jpg" width=200><br>
<a href="https://www.amazon.com/Orbit-57950-Outdoor-Sprinkler-Controller/dp/B01D15HOTU?ie=UTF8&*Version*=1&*entries*=0">Amazon™ Orbit 57950 B-hyve Smart Indoor/Outdoor 6/12-Station WiFi Sprinkler System Controller</a><br>
</p>

## Installation & Configuration

This integration should be installed using [Hubitat Package Manager](https://raw.githubusercontent.com/dcmeglio/hubitat-packagemanager/master/apps/Package_Manager.groovy). You will need your BHyve username and password to connect.
