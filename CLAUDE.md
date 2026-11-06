# CLAUDE.md

Guidance for AI coding assistants (and humans) working on this repository.

## What this is

A Hubitat Elevation integration for Orbit b•hyve™ smart sprinkler and hose-faucet timers. It is a
maintained fork of `dcmeglio/hubitat-orbitbhyve`, which was itself a port of Kurt Sanders'
SmartThings integration. See the README's "About this fork" section for the history and credits.
Keep that history and the authors' credits intact.

## Layout

The repo holds two integrations:

- **v2** (`v2/`, namespace `meshoo`): the actively developed, breaking "B-hyve v2" app. See below.
- **1.8.x** (`apps/orbit-bhyve-controller.groovy`, `drivers/`, root `packageManifest.json`, namespace
  `kurtsanders`): maintenance-only. Bug fixes yes, new features go to v2.

### v2

- `v2/apps/bhyve-v2.groovy`: login, polling (`refresh()`), device reconciliation, websocket event routing
  (`handleEvent`), commands (`startZone`, `stopWatering`, `setRunMode`, `setRainDelay`), notifications
  (`sendNotice`). Every device is updated inside its own try/catch.
- `v2/drivers/bhyve-v2-cloud.groovy`: the one websocket per account; `parse()` → `parent.handleEvent()`.
- `v2/drivers/bhyve-v2-hub.groovy`: one per B-hyve hub (`bridge`). No battery.
- `v2/drivers/bhyve-v2-timer.groovy`: one per physical timer (`sprinkler_timer`). Owns battery, health,
  rssi, run mode, rain delay, schedule. Creates its zones as **child devices**.
- `v2/drivers/bhyve-v2-zone.groovy`: one per outlet/station. Valve/Switch, programs, `lastActivity`. No battery.
- DNIs: hub/timer `bhyve2-<appId>-<orbitDeviceId>`, cloud `bhyve2-<appId>-cloud`, zone `<timerDni>:<station>`.
  The station is parsed from the DNI, never read from an attribute.
- Hub ↔ timer link: shared `mesh_id` (Gen 1 hubs) or `network_topology_id` (Gen 2 hubs).
- `v2/packageManifest.json`: separate HPM package with its own IDs.
- `docs/bhyve-api-model.md`: which API field feeds which attribute (confirmed against a real account).

### 1.8.x

- `apps/orbit-bhyve-controller.groovy`: the parent app ("Orbit Bhyve Controller"). It logs in to
  the B-hyve cloud API (`https://api.orbitbhyve.com/v1/`), creates the child devices, polls on a
  user-chosen schedule (`setScheduler` → `refresh()` → `main()` → `updateTiles(data)`), and sends
  commands (`sendRequest`, `sendRainDelay`).
- `drivers/orbit-bhyve-bridge.groovy`: the Wi-Fi hub/bridge child device (firmware, hardware,
  connection state).
- `drivers/orbit-bhyve-sprinkler-timer.groovy`: one child device per timer zone/station. It also
  holds the B-hyve websocket (`interfaces.webSocket`) and handles real-time events in `parse()`
  (watering started/stopped, rain delay, battery, flow, and so on).
- `packageManifest.json`: the Hubitat Package Manager (HPM) manifest.
- Child device DNIs look like `bhyve-<appId>-<orbitDeviceId>:<station>`. Station `0` is a bridge.
  Helpers: `getOrbitDeviceIdFromDNI`, `getStationFromDNI`, `getValveDevices`, `getDeviceByIdAndStation`.

## Runtime constraints

- The code runs inside the Hubitat sandbox (Groovy 2.4). There is no build step. Don't add
  dependencies or imports the sandbox doesn't allow.
- The B-hyve API is undocumented and changes over time. **Treat every field from the API or from
  websocket payloads as possibly missing.** Use `?.`, `?:` and explicit null checks before
  `.toInteger()`, `.toBoolean()` and `Date.parse(...)`. Most historical bugs were an unguarded
  conversion that crashed a whole refresh. An exception inside `updateTiles` stops updates for
  every device after the failing one.
- Device attributes such as `station` may never have been set on some children. Guard
  `currentValue(...)` the same way.

## Compatibility rules

- **1.8.x: don't rename** the app, the drivers, the `kurtsanders` namespace, or the package/app/driver
  IDs in `packageManifest.json`. Existing installs (including people switching from upstream
  1.8.0) depend on them to update in place.
- **v2: don't rename** "B-hyve v2" and its four drivers, the `meshoo` namespace, or the IDs in
  `v2/packageManifest.json`. v2 was deliberately a breaking change from 1.8.x; don't make another one
  without a new major version.
- Keep attribute names and commands stable, because users' rules and dashboards reference them.
  Add new ones rather than changing existing ones.

## Verifying a change

Off-hub (run before every commit; needs Java, downloads Groovy 2.4 once):

```bash
tests/run.sh
```

It compile-checks every app and driver with Groovy 2.4 and runs `tests/V2Harness.groovy`, which drives
the real v2 code with stubbed Hubitat APIs and fake B-hyve data. Add a check there when you change v2
behaviour. Passing it does **not** mean the code works on a hub: the sandbox differs (state is JSON,
some classes are restricted), so verification on a real hub is still required:

1. Paste the changed files into *Apps Code* / *Drivers Code* and save. A successful save means it
   compiled.
2. Watch *Logs* through at least one scheduled refresh and confirm there are no `ERROR` lines
   from the app or its devices.
3. Check that the device attributes update (battery, valve/switch, programs, next start time).

In a PR, say which of these were done and which were not.

## Releases

- Bump `version`, `dateReleased` and `releaseNotes` in the right manifest (`packageManifest.json`
  for 1.8.x, `v2/packageManifest.json` for v2).
- Add the release to the README ("About this fork" → changes).
- Files changed from the upstream originals carry a short modification notice in their header
  comment, as Apache-2.0 §4(b) requires. Keep those notices up to date.

## Privacy

This is a public repository. Never commit B-hyve credentials, API tokens, hub IP addresses,
Hubitat access tokens, device IDs or MAC addresses, logs or API responses from a real account,
or anything else that identifies a specific installation. Redact samples before using them in
issues, docs or tests. The B-hyve `meshes` and `network_topologies` endpoints return Bluetooth network
keys (`ble_network_key`, `network_key`): never store, log or commit them. For API dumps use
`tools/bhyve_inspect.py` (credentials from `BHYVE_EMAIL` / `BHYVE_PASSWORD` in the environment) or
`apps/tools/bhyve-api-inspector.groovy`; both redact, but read the output before using it.

## License

Apache License 2.0 (see `LICENSE`).
