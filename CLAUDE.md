# CLAUDE.md

Guidance for AI coding assistants (and humans) working on this repository.

## What this is

A Hubitat Elevation integration for Orbit b•hyve™ smart sprinkler and hose-faucet timers. It is a
maintained fork of `dcmeglio/hubitat-orbitbhyve`, which was itself a port of Kurt Sanders'
SmartThings integration. See the README's "About this fork" section for the history and credits.
Keep that history and the authors' credits intact.

## Layout

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

- The code runs inside the Hubitat sandbox (Groovy 2.4). There is no build step, no local
  compiler, and no test suite. Don't add dependencies or imports the sandbox doesn't allow.
- The B-hyve API is undocumented and changes over time. **Treat every field from the API or from
  websocket payloads as possibly missing.** Use `?.`, `?:` and explicit null checks before
  `.toInteger()`, `.toBoolean()` and `Date.parse(...)`. Most historical bugs were an unguarded
  conversion that crashed a whole refresh. An exception inside `updateTiles` stops updates for
  every device after the failing one.
- Device attributes such as `station` may never have been set on some children. Guard
  `currentValue(...)` the same way.

## Compatibility rules

- **Don't rename** the app, the drivers, the `kurtsanders` namespace, or the package/app/driver
  IDs in `packageManifest.json`. Existing installs (including people switching from upstream
  1.8.0) depend on them to update in place.
- Keep attribute names and commands stable, because users' rules and dashboards reference them.
  Add new ones rather than changing existing ones.

## Verifying a change

You can't run this code outside a hub. A cloud or CI environment can't reach anyone's hub, so
verification is manual and happens on a real hub:

1. Paste the changed files into *Apps Code* / *Drivers Code* and save. A successful save means it
   compiled.
2. Watch *Logs* through at least one scheduled refresh and confirm there are no `ERROR` lines
   from the app or its devices.
3. Check that the device attributes update (battery, valve/switch, programs, next start time).

In a PR, say which of these were done and which were not.

## Releases

- Bump `version`, `dateReleased` and `releaseNotes` in `packageManifest.json`.
- Add the release to the README ("About this fork" → changes).
- Files changed from the upstream originals carry a short modification notice in their header
  comment, as Apache-2.0 §4(b) requires. Keep those notices up to date.

## Privacy

This is a public repository. Never commit B-hyve credentials, API tokens, hub IP addresses,
Hubitat access tokens, device IDs or MAC addresses, logs or API responses from a real account,
or anything else that identifies a specific installation. Redact samples before using them in
issues, docs or tests.

## License

Apache License 2.0 (see `LICENSE`).
