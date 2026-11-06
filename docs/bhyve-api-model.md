# B-hyve device model (reference for v2)

Status: **confirmed against a real account** (Nov 2026) with `tools/bhyve_inspect.py`. The account had
a Gen 1 hub with an HT25 hose timer and a Gen 2 hub with an XD 4-outlet timer. Field lists below come
from that redacted dump; no raw payload is committed. Treat every field as optional anyway.

## Physical hierarchy and how the API links it

```
B-hyve account (cloud: REST https://api.orbitbhyve.com/v1/, websocket wss://api.orbitbhyve.com/v1/events)
├─ Gen 1 Wi-Fi hub   type "bridge", hardware_version "BH1-0001"
│   └─ linked by mesh_id                    GET meshes/{mesh_id}
│       └─ HT25 hose timer   type "sprinkler_timer", hardware "HT25-0000", num_stations 1
│            └─ zones[0] (station 1)
└─ Gen 2 Wi-Fi hub   type "bridge", hardware_version "BH1G2-0002", network_interface "wifi"
    └─ linked by network_topology_id        GET network_topologies/{id}
        └─ XD 4-outlet timer type "sprinkler_timer", hardware "HT34-0001", num_stations 4
             └─ zones[0..3] (stations 1–4; unconfigured outlets are still listed)
```

Both hub generations are `type: "bridge"`. **The hub-to-timer link depends on the generation:**

| Hub | Link field on hub and timer | Endpoint | Endpoint returns |
|---|---|---|---|
| Gen 1 (BH1) | `mesh_id` | `GET meshes/{mesh_id}` | `bridge_device_id`, `devices[] {device_id, ble_device_id}`, `ble_network_key` 🔒, `name` |
| Gen 2 (BH1G2) | `network_topology_id` | `GET network_topologies/{id}` | `bridge_devices[] {device_id}`, `devices[] {device_id, network_device_id}`, `network_key` 🔒, `user_id` |

The shared ID on the device objects is enough to build the tree, so v2 does not need to call these
endpoints on every poll. 🔒 `ble_network_key` / `network_key` are the Bluetooth network keys. **v2 must
never store, log or send them anywhere.**

## Where each fact lives

| Level | Field | Seen on | Notes |
|---|---|---|---|
| Hub | `is_connected`, `last_connected_at` | both | hub health |
| Hub | `firmware_version`, `hardware_version`, `wifi_version` | both | |
| Hub | `network_interface` | Gen 2 | `"wifi"` |
| Hub | `status.next_start_time` | Gen 1 | `1970-01-01…` sentinel, so ignore it |
| Hub | `status.run_mode`, `status.rain_delay` | Gen 2 | hub-level copies; ignore, use the timer's |
| Hub | battery, signal | — | **none** |
| Timer | `battery.percent`, `battery.mv` | both | **one battery per timer**, shared by all its zones |
| Timer | `battery.charging` | HT25 only | |
| Timer | `status.last_battery_alarm_at` | both | |
| Timer | `is_connected`, `last_connected_at` | both | reported per timer: the XD's time differed from its hub's, so it isn't just a copy of the hub |
| Timer | `status.rssi` (dBm), `status.phy`, `status.provisioned` | XD only | Bluetooth link to the hub (`phy` e.g. `le_coded_s8_125`) |
| Timer | `firmware_version`, `hardware_version`, `num_stations` | both | |
| Timer | `status.run_mode`, `status.rain_delay`, `status.rain_delay_started_at` | both | |
| Timer | `status.next_start_time`, `status.next_start_programs` | HT25 (absent on XD when no program is enabled) | an HT25 with no enabled program keeps its last start, so the time can be in the past |
| Timer | `status.watering_status`, `status.watering_statuses[]`, `status["watering-status"]` | both | null or empty when idle; three spellings exist, so check all |
| Timer | `manual_preset_runtime_sec` | HT25 | |
| Timer | `smart_watering_enabled`, `water_sense_mode` | XD / both | |
| Zone | `station`, `name`, `num_sprinklers`, `sprinkler_type` | both | an unconfigured outlet has `num_sprinklers: 0` and no `sprinkler_type` |
| Zone | `landscape_type`, `soil_type`, `sun_shade`, `slope_grade`, `geometry`, `smart_watering_enabled` | both | |
| Zone | `device_id` | XD only | back-reference to the timer |
| Zone | `flow_data {flow_rate_gpm, cycle_volume_gal, cycle_run_time_sec}` | HT25 only | |
| Zone | programs: `sprinkler_timer_programs[].run_times[] {station, run_time}`, `enabled`, `frequency {type: days/even/odd/interval}`, `start_times[]` | both | |
| Zone | history: `watering_events/{device_id}[].irrigation[] {station, start_time, run_time, status (complete/skipped), water_volume_gal, program, program_name}` | both | `water_volume_gal` is **null on the XD** |

## Websocket events

Confirmed on an XD 4-outlet timer (Nov 2026), for a manual run started from Hubitat and from the phone
app. Top-level field names; every event also has `event`, `device_id` and `timestamp`:

| Event | Other fields seen |
|---|---|
| `change_mode` | `mode`; `stations` when a run starts, absent when it returns to auto |
| `watering_in_progress_notification` | `current_station`, `run_time`, `program`, `status`, `started_watering_station_at`, `total_run_time_sec`, `water_event_queue`, `rain_sensor_hold` |
| `watering_complete` | `program`, `stream-id`, `gateway-topic`; **no station** |
| `battery_status` | `mv`, `charging`, `stream-id`, `client-topics`; **no percent** |
| `rain_delay` | `delay`, `rain_delay_cause`, `rain_delay_weather_type` |

`battery_status` and `rain_delay` arrive with every run start and stop, and at other times when no run
is active, so they don't always mean anything changed.

Not re-verified (from v1 and other projects): `device_connected`, `device_disconnected`, `low_battery`,
`clear_low_battery`, `fault`, `flow_sensor_state_changed` (`flow_rate_gpm`),
`set_manual_preset_runtime` / `set_manual_preset_time`, `program_changed`, `device_idle`,
`fs_status_update` (flood sensor).

## How 1.8.x models it, and what's wrong

- One Hubitat device per **zone**, each with `Battery`, `is_connected`, rain delay and run mode copied
  from the timer. The XD 4-outlet shows four "batteries".
- When the API has no battery, the zone reports a fake `100`.
- Hub devices know nothing about their timers, and the Gen 2 link (`network_topology_id`) isn't used at all.
- The timer's Bluetooth signal (`status.rssi`) and its own `last_connected_at` aren't exposed.
- The websocket lives on whichever zone is flagged "master".

## Mapping for v2

| v2 device | Attribute | Source |
|---|---|---|
| Hub | `healthStatus` (online/offline) | `is_connected` |
| Hub | `lastConnectedAt`, `firmwareVersion`, `hardwareVersion`, `generation` (1/2), `networkInterface` | as named; `generation` from `mesh_id` vs `network_topology_id` |
| Hub | `timerCount`, `timers` | devices sharing the hub's link ID |
| Timer | `battery`, `batteryVoltage` (V), `batteryCharging` (if present), `lastBatteryAlarmAt` | `battery.*`, `status.last_battery_alarm_at` |
| Timer | `healthStatus`, `connectivity` (online / offline / hub offline), `lastConnectedAt`, `hubName` | `is_connected`, `last_connected_at`, hub via link ID |
| Timer | `rssi`, `radioPhy` | `status.rssi`, `status.phy` (XD only) |
| Timer | `runMode`, `rainDelay`, `nextStartTime`, `nextStartPrograms`, `activeZone`, `wateringStatus`, `numStations`, `firmwareVersion`, `hardwareVersion`, `smartWatering` | `status.*` and top-level fields |
| Zone | `valve`, `switch`, `station`, `zoneName`, `sprinklerType`, `numSprinklers`, `configured` | zone fields; `configured` = `num_sprinklers > 0` or has `sprinkler_type` |
| Zone | `flowRateGpm`, `programs`, `presetRuntime`, `lastActivity`, `lastWateringStart`, `lastWateringStatus`, `lastWateringVolume` (only when not null), `startTime`, `stopTime` | flow_data, programs, watering_events, websocket |

## Sources

- Redacted dump of a real account (not committed), made with `tools/bhyve_inspect.py`
- sebr/bhyve-home-assistant (`const.py`, `sensor.py`, `pybhyve/client.py`) and its issue #78 payload sample
- Orbit product spec sheets (24511 / 24632 / 24634)
