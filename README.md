# hubitat-orbitbhyve

> **This is a maintained fork** of [dcmeglio/hubitat-orbitbhyve](https://github.com/dcmeglio/hubitat-orbitbhyve).
> It contains two integrations:
>
> - **B-hyve v2** (`v2/`, beta) is a new, **breaking** app that models hubs, timers and zones as they physically exist.
> - **Orbit Bhyve Controller 1.8.x** (`apps/`, `drivers/`) is a maintenance-only, drop-in update for the original 1.8.0, with the same app/driver names, namespace and package IDs.

## B-hyve v2 (beta, breaking)

### Device model

1.8.x creates one device per *zone* and copies the timer's battery, connection and rain delay onto each one,
so a 4-outlet timer shows four batteries. v2 creates devices that match the hardware:

```
B-hyve Cloud Connection          the websocket to the B-hyve cloud (one per account)
B-hyve Wi-Fi Hub                 one per hub: healthStatus, firmware, generation, timers it relays
B-hyve Timer                     one per physical timer: battery, batteryVoltage, healthStatus, connectivity,
 │                               hubName, rssi (XD), runMode, rainDelay, nextStartTime, activeZone
 ├─ B-hyve Zone (child)          one per outlet: valve, switch, lastActivity, startTime/stopTime,
 ├─ B-hyve Zone (child)          programs, lastWateringStart/Status/Volume, flowRateGpm, configured
 └─ …
```

- **Battery is on the timer only.** Zones have no battery. A low-battery notification fires once per timer.
- **Hubs and timers are linked:** Gen 1 hubs by `mesh_id`, Gen 2 hubs by `network_topology_id`. The timer's
  `hubName` and the hub's `timers` show the link. If a hub goes offline, its timers report `connectivity: hub offline`.
- **Zone activity:** `lastActivity` is a readable line for dashboards, for example
  `Watering (20 min, program A), started 8:45 am`, `Watered (20 min, Evens (program A)), Wed Nov 4, 8:45 am`
  or `Skipped (Manual (manual)), Tue Nov 3, 2:53 pm`. Valve/switch changes appear in each zone's *Events*.
- Zone names follow renames in the B-hyve app unless you've renamed the device in Hubitat.
- See [`docs/bhyve-api-model.md`](docs/bhyve-api-model.md) for the API fields behind each attribute.

### Install

v2 has its own names, namespace (`meshoo`) and package IDs, so it installs **alongside** 1.8.x.

- **Hubitat Package Manager:** *Install ▸ From a URL*:
  `https://raw.githubusercontent.com/meshoo/hubitat-orbitbhyve/master/v2/packageManifest.json`
- **Manual:** add `v2/drivers/bhyve-v2-cloud.groovy`, `bhyve-v2-hub.groovy`, `bhyve-v2-timer.groovy` and
  `bhyve-v2-zone.groovy` in *Drivers Code*, then `v2/apps/bhyve-v2.groovy` in *Apps Code*. Then
  *Apps ▸ Add User App ▸ B-hyve v2*.

### Migrating from 1.8.x

1. Install v2 next to 1.8.x and check that the devices and attributes look right.
2. Re-point your rules, dashboards and notifications to the v2 devices:

   | 1.8.x (per-zone device) | v2 |
   |---|---|
   | `battery` | Timer `battery` |
   | `is_connected` | Hub/Timer `healthStatus` (`online`/`offline`), Timer `connectivity` |
   | `valve`, `switch`, `open`, `close`, `on`, `off`, `open(minutes)` | Zone, unchanged |
   | `rain_delay`, `setRainDelay` | Timer `rainDelay`, `setRainDelay` |
   | `run_mode` | Timer `runMode`, plus the `setRunMode` command |
   | `next_start_time` (epoch ms) | Timer `nextStartTime` (text) |
   | `next_start_programs` | Timer `nextStartPrograms` |
   | `programs`, `start_times` | Zone `programs` |
   | `preset_runtime`, `manual_preset_runtime_min` | Timer/Zone `presetRuntime` |
   | `last_watering_volume` | Zone `lastWateringVolume` (not reported by XD timers) |
   | `start_time`, `stop_time` (epoch ms) | Zone `startTime`, `stopTime` (text) |
   | `water_flow_rate` | Zone `flowRateGpm` |
   | `sprinkler_type`, `station` | Zone `sprinklerType`, `station` |
   | `firmware_version`, `hardware_version` (bridge) | Hub `firmwareVersion`, `hardwareVersion` |

3. Remove the *Orbit Bhyve Controller* app, which also removes its devices.

### Status

Beta. All files compile with Groovy 2.4, and a scripted test runs the app and drivers against API data
shaped like a real Gen 1 + HT25 and Gen 2 + XD 4-outlet account. It has also been run on a real hub with
that hardware: device creation, polling, and a manual XD zone run started from Hubitat and from the
B-hyve app all worked. Notifications, `setRainDelay`, `setRunMode` and HT25 watering haven't been tested
on real hardware yet, so please report problems.

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
| Nov 2026 | this fork | **B-hyve v2** beta: Hub → Timer → Zone device model (see above). Derived from the code above, with credits kept in each file. |

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
   addresses, locations, time zones, tokens and Bluetooth network keys are removed.
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
