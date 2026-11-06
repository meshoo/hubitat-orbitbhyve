/*
* B-hyve v2 Timer (Hubitat driver)
*
* One device per physical B-hyve timer or controller (API type "sprinkler_timer"): HT25 hose timer,
* XD 1/2/4-outlet timers, in-ground controllers. Owns everything that belongs to the hardware (battery,
* connectivity, radio signal, run mode, rain delay, schedule) and creates one "B-hyve v2 Zone" child device
* per outlet/station.
*
* Part of v2 in the meshoo/hubitat-orbitbhyve fork (2026). The program/schedule formatting and watering
* status handling are derived from the "Orbit Bhyve Controller" app and "Orbit Bhyve Sprinkler Timer"
* driver by Kurt Sanders and Dominick Meglio (2019-2020), with null-safety fixes by Matthew Petro (2025).
* Changes: one device per timer instead of per zone, zones as child devices, real battery only (never a
* default value), hub link, Bluetooth signal, per-zone error isolation.
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
* in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
* for the specific language governing permissions and limitations under the License.
*/
import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final String ZONE_DRIVER = "B-hyve v2 Zone"
@Field static final Map DAY_NAMES = [0: "Sun", 1: "Mon", 2: "Tue", 3: "Wed", 4: "Thu", 5: "Fri", 6: "Sat"]

metadata {
    definition(name: "B-hyve v2 Timer", namespace: "meshoo", author: "meshoo/hubitat-orbitbhyve contributors") {
        capability "Battery"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "batteryVoltage", "number"
        attribute "batteryCharging", "string"
        attribute "lastBatteryAlarmAt", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "connectivity", "string"
        attribute "lastConnectedAt", "string"
        attribute "hubName", "string"
        attribute "rssi", "number"
        attribute "radioPhy", "string"
        attribute "firmwareVersion", "string"
        attribute "hardwareVersion", "string"
        attribute "numStations", "number"
        attribute "runMode", "string"
        attribute "rainDelay", "number"
        attribute "nextStartTime", "string"
        attribute "nextStartPrograms", "string"
        attribute "programs", "string"
        attribute "activeZone", "string"
        attribute "wateringStatus", "enum", ["idle", "watering"]
        attribute "presetRuntime", "number"
        attribute "smartWatering", "string"
        attribute "lastFault", "string"

        command "setRainDelay", [[name: "hours*", type: "NUMBER", description: "0 cancels the rain delay"]]
        command "setRunMode", [[name: "mode*", type: "ENUM", constraints: ["auto", "off"]]]
        command "stopWatering"
    }
}

def installed() {
    sendEvent(name: "wateringStatus", value: "idle")
    sendEvent(name: "activeZone", value: "none")
}

def refresh() { parent?.refresh() }

// ---------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------

def setRainDelay(hours) {
    def h = toInt(hours)
    if (h == null || h < 0) { log.warn "setRainDelay: hours must be 0 or more"; return }
    parent?.setRainDelay(orbitId(), h)
}

def setRunMode(mode) {
    if (!(mode in ["auto", "off"])) { log.warn "setRunMode: mode must be auto or off"; return }
    parent?.setRunMode(orbitId(), mode)
}

def stopWatering() { parent?.stopWatering(orbitId()) }

// Called by zone child devices.
def startZone(Integer station, Integer minutes) { parent?.startZone(orbitId(), station, minutes) }
def stopZone(Integer station) { parent?.stopWatering(orbitId()) }
def sendNotice(String category, String message) { parent?.sendNotice(category, message) }
boolean isDebugLogEnabled() { return parent?.isDebugLogEnabled() == true }

// ---------------------------------------------------------------------------------------------
// Poll: the timer's JSON from GET devices, its programs, and hub context from the app
// ---------------------------------------------------------------------------------------------

def applyTimer(Map d, List programs, Map ctx) {
    if (d == null) return
    Map status = (d.status instanceof Map) ? d.status : [:]

    setHealth(d.is_connected, ctx)
    if (d.last_connected_at) sendEvent(name: "lastConnectedAt", value: d.last_connected_at.toString())
    sendEvent(name: "hubName", value: ctx?.hubName ?: (ctx?.hasHub ? "unknown" : "none"))

    applyBattery(d.battery instanceof Map ? d.battery : null)
    if (status.last_battery_alarm_at) sendEvent(name: "lastBatteryAlarmAt", value: status.last_battery_alarm_at.toString())

    def rssi = toInt(status.rssi)
    if (rssi != null) sendEvent(name: "rssi", value: rssi, unit: "dBm")
    if (status.phy) sendEvent(name: "radioPhy", value: status.phy.toString())
    if (d.firmware_version != null) sendEvent(name: "firmwareVersion", value: d.firmware_version.toString())
    if (d.hardware_version != null) sendEvent(name: "hardwareVersion", value: d.hardware_version.toString())
    def numStations = toInt(d.num_stations)
    if (numStations != null) sendEvent(name: "numStations", value: numStations)
    if (d.smart_watering_enabled != null) sendEvent(name: "smartWatering", value: d.smart_watering_enabled ? "on" : "off")

    def runMode = status.run_mode?.toString()
    if (runMode) sendEvent(name: "runMode", value: runMode)
    applyRainDelay(toInt(status.rain_delay))
    String nextStart = nextStartText(status, runMode)
    sendEvent(name: "nextStartTime", value: nextStart)
    def nsp = (status.next_start_programs instanceof List && nextStart != "none") ? status.next_start_programs : []
    sendEvent(name: "nextStartPrograms", value: nsp ? nsp.collect { it.toString().toUpperCase() }.join(", ") : "none")

    def presetSec = toInt(d.manual_preset_runtime_sec)
    Integer presetMin = (presetSec != null) ? Math.round(presetSec / 60.0d) as Integer : null
    if (presetMin != null) sendEvent(name: "presetRuntime", value: presetMin, unit: "min")

    List zones = (d.zones instanceof List) ? d.zones.findAll { it instanceof Map } : []
    syncZones(zones, d.name?.toString() ?: device.displayName)

    List enabled = (programs ?: []).findAll { it instanceof Map && it.enabled?.toString() == "true" }
    sendEvent(name: "programs", value: enabled ? enabled.collect { programText(it, null) }.join("; ") : "none enabled")

    List active = activeStations(status)
    zones.each { z ->
        def st = toInt(z.station)
        if (st == null) return
        def child = zoneDevice(st)
        if (!child) return
        try {
            def zonePrograms = enabled.findAll { p -> runTimeFor(p, st) != null }.collect { p -> programText(p, st) }
            child.applyZone(z, active.contains(st), zonePrograms, presetMin)
        } catch (e) {
            log.warn "${device.displayName}: could not update zone ${st}: ${e}"
        }
    }
    setActive(active.isEmpty() ? null : active[0])
}

// Recent watering events (GET watering_events/{id}): latest run per station goes to each zone.
def applyHistory(List events) {
    Map latest = [:]
    (events ?: []).each { ev ->
        if (!(ev instanceof Map) || !(ev.irrigation instanceof List)) return
        ev.irrigation.each { irr ->
            if (!(irr instanceof Map)) return
            def st = toInt(irr.station)
            def start = parseIso(irr.start_time?.toString())
            if (st == null || start == null) return
            if (!latest[st] || start.after(latest[st].start)) latest[st] = [start: start, entry: irr]
        }
    }
    latest.each { st, v ->
        def child = zoneDevice(st as Integer)
        if (!child) return
        try {
            child.applyHistory(v.entry, v.start.getTime())
        } catch (e) {
            log.warn "${device.displayName}: could not apply history to zone ${st}: ${e}"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Websocket events routed by the app
// ---------------------------------------------------------------------------------------------

def handleEvent(Map p) {
    switch (p?.event) {
        case "watering_in_progress_notification":
            def st = toInt(p.current_station)
            if (st == null) break
            def runMin = toInt(p.run_time)
            def label = programLabel(p.program?.toString(), null)
            zoneDevices().each { z ->
                def zst = stationOf(z)
                if (zst == st) z.wateringStarted(runMin, label)
                else z.wateringStopped(false)
            }
            setActive(st)
            break
        case "watering_complete":
        case "device_idle":
            zoneDevices().each { z -> z.wateringStopped(true) }
            setActive(null)
            if (p.event == "watering_complete") parent?.refreshHistorySoon(orbitId())
            break
        case "device_connected":
            setHealth(true, null)
            break
        case "device_disconnected":
            setHealth(false, null)
            break
        case "change_mode":
            if (p.mode) sendEvent(name: "runMode", value: p.mode.toString())
            break
        case "rain_delay":
            applyRainDelay(toInt(p.delay))
            break
        case "low_battery":
            if (!state.lowBatteryNotified) {
                state.lowBatteryNotified = true
                sendNotice("battery", "B-hyve timer ${device.displayName} reports a low battery")
            }
            parent?.refreshSoon()
            break
        case "clear_low_battery":
            state.lowBatteryNotified = false
            parent?.refreshSoon()
            break
        case "battery_status":
            applyBattery((p.battery instanceof Map) ? p.battery : [percent: p.percent, mv: p.mv, charging: p.charging])
            break
        case "fault":
            def text = (p.fault ?: p.message ?: p.code ?: "unspecified")?.toString()
            sendEvent(name: "lastFault", value: text.take(200), descriptionText: "${device.displayName} fault: ${text}")
            log.warn "${device.displayName} reported a fault: ${text}"
            break
        case "flow_sensor_state_changed":
            def flowStation = toInt(p.station) ?: activeStation()
            def rate = p.flow_rate_gpm
            if (flowStation != null && rate != null) zoneDevice(flowStation)?.setFlowRate(rate)
            break
        case "set_manual_preset_runtime":
        case "set_manual_preset_time":
            def sec = toInt(p.seconds)
            if (sec != null) {
                Integer min = Math.round(sec / 60.0d) as Integer
                sendEvent(name: "presetRuntime", value: min, unit: "min")
                zoneDevices().each { z -> z.setPresetRuntime(min) }
            }
            break
        case "program_changed":
            parent?.refreshSoon()
            break
        default:
            if (parent?.isDebugLogEnabled()) log.debug "${device.displayName} ignored event '${p?.event}'"
    }
}

// ---------------------------------------------------------------------------------------------
// Zones
// ---------------------------------------------------------------------------------------------

void syncZones(List zones, String timerName) {
    Map autoLabels = state.autoLabels ?: [:]
    zones.each { z ->
        def st = toInt(z.station)
        if (st == null) return
        String label = "${timerName} – ${z.name ?: "Zone ${st}"}"
        def child = zoneDevice(st)
        if (!child) {
            try {
                child = addChildDevice("meshoo", ZONE_DRIVER, zoneDni(st), [name: ZONE_DRIVER, label: label, isComponent: false])
                child.updateDataValue("station", st.toString())
                autoLabels[st.toString()] = label
                log.info "${device.displayName}: created zone '${label}'"
            } catch (e) {
                log.error "${device.displayName}: could not create zone ${st}. Is the '${ZONE_DRIVER}' driver installed? ${e}"
            }
        } else if (label != child.label && child.label == autoLabels[st.toString()]) {
            // Follow renames in the B-hyve app, unless the user has renamed the device in Hubitat.
            child.setLabel(label)
            autoLabels[st.toString()] = label
        }
    }
    state.autoLabels = autoLabels
}

String zoneDni(Integer station) { return "${device.deviceNetworkId}:${station}" }
def zoneDevice(Integer station) { return getChildDevice(zoneDni(station)) }
List zoneDevices() { return getChildDevices() ?: [] }

Integer stationOf(z) {
    def dni = z?.deviceNetworkId?.toString()
    if (!dni || !dni.contains(":")) return null
    return toInt(dni.substring(dni.lastIndexOf(":") + 1))
}

Integer activeStation() {
    def child = zoneDevices().find { it.currentValue("valve") == "open" }
    return child ? stationOf(child) : null
}

void setActive(Integer station) {
    if (station == null) {
        sendEvent(name: "activeZone", value: "none")
        sendEvent(name: "wateringStatus", value: "idle")
    } else {
        def child = zoneDevice(station)
        sendEvent(name: "activeZone", value: child ? child.displayName : "station ${station}")
        sendEvent(name: "wateringStatus", value: "watering")
    }
}

// The API reports what is running in one of three fields depending on model/firmware.
List activeStations(Map status) {
    List sources = []
    if (status.watering_status instanceof Map) sources << status.watering_status
    if (status["watering-status"] instanceof Map) sources << status["watering-status"]
    if (status.watering_statuses instanceof List) sources.addAll(status.watering_statuses.findAll { it instanceof Map })
    List result = []
    sources.each { ws ->
        if (ws.stations instanceof List && !ws.stations.isEmpty()) {
            ws.stations.each { s ->
                def n = toInt((s instanceof Map) ? s.station : s)
                if (n != null && !result.contains(n)) result << n
            }
        } else {
            def n = toInt(ws.current_station)
            if (n != null && !result.contains(n)) result << n
        }
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// Battery, health, rain delay
// ---------------------------------------------------------------------------------------------

void applyBattery(Map b) {
    if (b == null) return  // never invent a value (wired controllers have no battery)
    def pct = toInt(b.percent)
    if (pct != null) {
        sendEvent(name: "battery", value: pct, unit: "%")
        Integer threshold = parent?.lowBatteryThreshold() ?: 20
        if (pct <= threshold && !state.lowBatteryNotified) {
            state.lowBatteryNotified = true
            sendNotice("battery", "B-hyve timer ${device.displayName} battery is at ${pct}%")
        } else if (pct > threshold + 5) {
            state.lowBatteryNotified = false
        }
    }
    def mv = toNum(b.mv)
    if (mv != null && mv > 0) sendEvent(name: "batteryVoltage", value: (mv / 1000.0).setScale(2, BigDecimal.ROUND_HALF_UP), unit: "V")
    if (b.charging != null) sendEvent(name: "batteryCharging", value: b.charging.toString())
}

void setHealth(connected, Map ctx) {
    if (connected == null) return
    boolean online = (connected == true || connected.toString() == "true")
    String value = online ? "online" : "offline"
    String connectivity = online ? "online" : ((ctx?.hasHub && ctx?.hubOnline == false) ? "hub offline" : "offline")
    sendEvent(name: "connectivity", value: connectivity)
    def previous = device.currentValue("healthStatus")
    if (previous == value) return
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} is ${value}")
    if (previous != null) sendNotice("connected", "B-hyve timer ${device.displayName} is ${connectivity}")
}

void applyRainDelay(Integer hours) {
    if (hours == null) return
    def previous = device.currentValue("rainDelay")
    sendEvent(name: "rainDelay", value: hours, unit: "h")
    if (previous != null && toInt(previous) != hours) {
        sendNotice("rain", hours > 0 ? "B-hyve timer ${device.displayName}: rain delay ${hours} h" : "B-hyve timer ${device.displayName}: rain delay cleared")
    }
}

// ---------------------------------------------------------------------------------------------
// Schedule text
// ---------------------------------------------------------------------------------------------

String nextStartText(Map status, String runMode) {
    if (runMode == "off") return "off"
    def next = parseIso(status.next_start_time?.toString())
    // Some models send a 1970 sentinel; an HT25 with no enabled program keeps its last, long-past start.
    if (next == null || next.getTime() < now()) return "none"
    return fmt(next)
}

def runTimeFor(Map program, Integer station) {
    def rt = (program.run_times instanceof List) ? program.run_times.find { it instanceof Map && toInt(it.station) == station } : null
    return rt?.run_time
}

// "Morning (A): 20 min every Mon, Wed, Fri at 7:50 am". With a station, shows that station's run time.
String programText(Map p, Integer station) {
    String freq
    def f = (p.frequency instanceof Map) ? p.frequency : [:]
    switch (f.type?.toString()) {
        case "interval": freq = "every ${f.interval ?: '?'} day(s)"; break
        case "odd":
        case "even": freq = "every ${f.type} day"; break
        case "days":
            def days = (f.days instanceof List) ? f.days.collect { DAY_NAMES[toInt(it)] ?: it.toString() } : []
            freq = days ? "every ${days.join(', ')}" : "on no days"
            break
        default: freq = f.type ? "'${f.type}' schedule" : "unknown schedule"
    }
    def starts = (p.start_times instanceof List) ? p.start_times.collect { formatClock(it?.toString()) }.findAll { it } : []
    String name = programLabel(p.program?.toString(), p.name?.toString())
    String runPart = ""
    if (station != null) {
        def rt = runTimeFor(p, station)
        if (rt != null) runPart = "${rt} min "
    }
    return "${name}: ${runPart}${freq}${starts ? ' at ' + starts.join(' & ') : ''}"
}

String programLabel(String code, String name) {
    String c = code?.trim()
    String base
    if (!c) base = "program"
    else if (c == "manual") base = "manual"
    else if (c == "e") base = "smart watering"
    else base = "program ${c.toUpperCase()}"
    return name ? "${name} (${base})" : base
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

String orbitId() {
    def id = device.getDataValue("orbitId")
    if (id) return id
    def dni = device.deviceNetworkId
    def parts = dni?.split("-", 3)
    return (parts && parts.size() == 3) ? parts[2] : null
}

Integer toInt(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).intValue()
    def s = v.toString().trim()
    return s.isNumber() ? s.toBigDecimal().intValue() : null
}

BigDecimal toNum(v) {
    if (v == null) return null
    if (v instanceof Number) return new BigDecimal(v.toString())
    def s = v.toString().trim()
    return s.isNumber() ? s.toBigDecimal() : null
}

Date parseIso(String s) {
    if (!s) return null
    for (String pattern in ["yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"]) {
        try {
            def f = new SimpleDateFormat(pattern)
            f.setLenient(false)
            return f.parse(s)
        } catch (ignored) { }
    }
    return null
}

String formatClock(String hhmm) {
    if (!hhmm) return null
    try {
        return new SimpleDateFormat("HH:mm").parse(hhmm).format("h:mm a").replace("AM", "am").replace("PM", "pm")
    } catch (ignored) {
        return hhmm
    }
}

String fmt(Date d) {
    return d.format("EEE MMM d, h:mm a", location.timeZone).replace("AM", "am").replace("PM", "pm")
}
