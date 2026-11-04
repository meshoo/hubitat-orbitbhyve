# B-hyve device model (working notes for v2)

Status: **draft from public research.** Items marked ❓ are confirmed or corrected with a redacted
dump from `apps/tools/bhyve-api-inspector.groovy` before v2 is built.

## Physical hierarchy

```
B-hyve account (cloud, REST https://api.orbitbhyve.com/v1/ + websocket wss://api.orbitbhyve.com/v1/events)
└─ Wi-Fi Hub             API type "bridge"   (Gen 1 / Gen 2 hub, e.g. 21027)
   │  linked to the timers it relays by a shared mesh_id ❓
   ├─ Hose timer          type "sprinkler_timer", num_stations 1   (HT25, XD 1-outlet 24511)
   ├─ XD 2-outlet timer   type "sprinkler_timer", num_stations 2   (24632)
   └─ XD 4-outlet timer   type "sprinkler_timer", num_stations 4   (24634)
        └─ zones[]        one entry per outlet/station
```

Also seen in the API: wired in-ground Wi-Fi controllers (also `sprinkler_timer`, 4–16 stations, no
battery, no hub) and `flood_sensor`.

## Where each fact lives

| Level | Field (API) | Notes |
|---|---|---|
| Hub | `is_connected`, `last_connected_at` | hub health |
| Hub | `firmware_version`, `hardware_version`, `wifi_version` | |
| Hub | `mesh_id` | ❓ link to timers; `GET meshes/{mesh_id}` contents unknown |
| Hub | signal strength | ❓ not seen in public samples |
| Timer | `battery.percent`, `battery.charging`, `battery.mv` ❓ | **one battery per physical timer, shared by all its zones** |
| Timer | `is_connected`, `last_connected_at` | ❓ whether this reflects the timer itself or only its hub |
| Timer | `firmware_version`, `hardware_version`, `num_stations`, `mesh_id` | |
| Timer | `status.run_mode`, `status.rain_delay`, `status.next_start_time`, `status.next_start_programs` | |
| Timer | `manual_preset_runtime_sec`, `scheduled_modes`, `status.rssi` ❓ | |
| Timer | `status.watering_status` | `current_station` or `stations[]`, either may be missing |
| Zone | `zones[].station`, `name`, `num_sprinklers`, `sprinkler_type`, `smart_watering_enabled` | |
| Zone | `zones[].flow_data.flow_rate_gpm`, `cycle_volume_gal`, `cycle_run_time_sec` | |
| Zone | `sprinkler_timer_programs[].run_times[].station` | programs per zone |
| Zone | `watering_events/{device_id}[].irrigation[]` (`station`, `water_volume_gal`, `start_time`, `run_time`) | history per zone |

## Websocket events (device-level unless noted)

`watering_in_progress_notification` (`current_station`, `run_time`), `watering_complete`,
`device_connected`, `device_disconnected`, `change_mode` (`mode`, optional `stations[]`), `rain_delay`
(`delay`), `low_battery`, `clear_low_battery`, `battery_status`, `fault`, `flow_sensor_state_changed`
(`flow_rate_gpm`), `set_manual_preset_runtime` (newer name) / `set_manual_preset_time` (older),
`program_changed`, `device_idle`, `fs_status_update` (flood sensor).

## How 1.8.x models it, and what's wrong

- One Hubitat device per **zone**, each with `Battery`, `is_connected`, rain delay and run mode copied
  from the timer. A 4-outlet timer therefore shows four "batteries".
- When the API has no battery, the zone reports a fake `100`.
- The hub device knows nothing about its timers, and timers know nothing about their hub.
- The websocket lives on whichever zone is flagged "master".

v2 (see the README once it lands) uses Hub → Timer → Zone devices instead.

## Sources

- Public payload sample: sebr/bhyve-home-assistant issue #78
- sebr/bhyve-home-assistant `const.py`, `sensor.py`, `pybhyve/client.py`
- Orbit product spec sheets (24511 / 24632 / 24634, hub 21027)
