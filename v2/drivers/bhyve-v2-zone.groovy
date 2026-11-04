/*
* B-hyve v2 Zone (Hubitat driver)
*
* One child device per outlet/station of a "B-hyve v2 Timer". Shows and controls that zone's watering
* (Valve and Switch), its schedule, and its activity. It has no battery: the battery belongs to the timer.
*
* Part of v2 in the meshoo/hubitat-orbitbhyve fork (2026). Valve/switch behaviour and the open(duration)
* command are derived from the "Orbit Bhyve Sprinkler Timer" driver by Kurt Sanders and Dominick Meglio
* (2019-2020), with contributions by jpage4500 (Switch) and Matthew Petro (null-safety, 2025). Changes:
* zone-only device (no battery), child of its timer, activity and history attributes.
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

metadata {
    definition(name: "B-hyve v2 Zone", namespace: "meshoo", author: "meshoo/hubitat-orbitbhyve contributors") {
        capability "Valve"
        capability "Switch"
        capability "Actuator"
        capability "Refresh"
        capability "Sensor"

        attribute "station", "number"
        attribute "zoneName", "string"
        attribute "configured", "string"
        attribute "sprinklerType", "string"
        attribute "numSprinklers", "number"
        attribute "flowRateGpm", "number"
        attribute "programs", "string"
        attribute "presetRuntime", "number"
        attribute "startTime", "string"
        attribute "stopTime", "string"
        attribute "lastActivity", "string"
        attribute "lastWateringStart", "string"
        attribute "lastWateringStatus", "string"
        attribute "lastWateringVolume", "number"

        command "open", [[name: "minutes", type: "NUMBER", description: "How long to water. Blank uses the preset run time."]]
    }
    preferences {
        input name: "presetRunTime", type: "number", title: "Default run time (minutes) for open/on", required: false
    }
}

def installed() {
    sendEvent(name: "valve", value: "closed")
    sendEvent(name: "switch", value: "off")
}

def refresh() { parent?.refresh() }

// ---------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------

def on() { open() }
def off() { close() }

def open() {
    open(null)
}

def open(minutes) {
    Integer m = toInt(minutes)
    if (m == null || m <= 0) m = toInt(presetRunTime) ?: toInt(device.currentValue("presetRuntime")) ?: 10
    def st = myStation()
    if (st == null) { log.warn "${device.displayName}: unknown station"; return }
    parent?.startZone(st, m)
}

def close() {
    def st = myStation()
    parent?.stopZone(st)
}

// ---------------------------------------------------------------------------------------------
// Updates from the timer
// ---------------------------------------------------------------------------------------------

// Poll: zone JSON, whether the API says it is watering now, its program lines, and the preset run time.
def applyZone(Map z, boolean active, List programLines, Integer presetMin) {
    def st = toInt(z?.station)
    if (st != null) sendEvent(name: "station", value: st)
    sendEvent(name: "zoneName", value: z?.name ? z.name.toString() : "Zone ${st ?: '?'}")
    def n = toInt(z?.num_sprinklers)
    boolean configured = (n != null && n > 0) || z?.sprinkler_type
    sendEvent(name: "configured", value: configured ? "yes" : "no")
    sendEvent(name: "sprinklerType", value: z?.sprinkler_type ? z.sprinkler_type.toString() : "not set")
    if (n != null) sendEvent(name: "numSprinklers", value: n)
    def flow = (z?.flow_data instanceof Map) ? z.flow_data.flow_rate_gpm : null
    if (flow != null) setFlowRate(flow)
    sendEvent(name: "programs", value: programLines ? programLines.join("; ") : "none enabled")
    if (presetMin != null) setPresetRuntime(presetMin)

    boolean open = device.currentValue("valve") == "open"
    if (active && !open) {
        wateringStarted(null, null)
    } else if (!active && open) {
        // A poll can lag a live start by a few seconds; only close from a poll once the run should be over.
        Long expected = state.expectedStopMs as Long
        if (expected == null || now() > expected) wateringStopped(true)
    }
}

// Live (websocket) start of watering on this zone.
def wateringStarted(Integer runMin, String programLabel) {
    long t = now()
    boolean wasOpen = device.currentValue("valve") == "open"
    state.runStartMs = wasOpen && state.runStartMs ? state.runStartMs : t
    state.runProgram = programLabel
    state.expectedStopMs = (runMin != null) ? t + (runMin + 1) * 60000L : null
    sendEvent(name: "valve", value: "open")
    sendEvent(name: "switch", value: "on")
    if (!wasOpen) sendEvent(name: "startTime", value: fmt(new Date(t)))
    if (runMin != null) sendEvent(name: "stopTime", value: fmt(new Date(t + runMin * 60000L)))
    String what = [runMin != null ? "${runMin} min" : null, programLabel].findAll { it }.join(", ")
    setActivity("Watering${what ? ' (' + what + ')' : ''}, started ${clock(new Date(state.runStartMs as Long))}", state.runStartMs as Long)
    if (!wasOpen) parent?.sendNotice("valve", "${device.displayName} started watering${what ? ' (' + what + ')' : ''}")
}

// Live end of watering (or a poll that shows it has ended). No-op if already closed.
def wateringStopped(boolean completed) {
    if (device.currentValue("valve") != "open") return
    long t = now()
    Long started = state.runStartMs as Long
    sendEvent(name: "valve", value: "closed")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "stopTime", value: fmt(new Date(t)))
    String dur = started ? "${Math.max(1, Math.round((t - started) / 60000.0d))} min" : null
    String what = [dur, state.runProgram].findAll { it }.join(", ")
    setActivity("${completed ? 'Watered' : 'Stopped'}${what ? ' (' + what + ')' : ''}, ended ${fmt(new Date(t))}", started ?: t)
    parent?.sendNotice("valve", "${device.displayName} stopped watering${dur ? ' after ' + dur : ''}")
    state.remove("expectedStopMs")
    state.remove("runProgram")
}

// Latest entry for this station from GET watering_events (authoritative: status, run time, volume).
def applyHistory(Map irr, Long startMs) {
    if (irr == null || startMs == null) return
    if (startMs < ((state.historyStartMs ?: 0L) as Long)) return
    state.historyStartMs = startMs
    String when = fmt(new Date(startMs))
    String status = irr.status?.toString() ?: "unknown"
    sendEvent(name: "lastWateringStart", value: when)
    sendEvent(name: "lastWateringStatus", value: status)
    def vol = irr.water_volume_gal
    if (vol != null && vol.toString().isNumber()) sendEvent(name: "lastWateringVolume", value: vol, unit: "gal")

    if (device.currentValue("valve") == "open") return   // don't replace the live "Watering ..." line
    // Don't let an older history entry overwrite a newer live line (allow 5 min for clock differences).
    Long current = state.activityStartMs as Long
    if (current != null && startMs + 300000L < current) return

    def rt = irr.run_time
    String mins = (rt != null && rt.toString().isNumber()) ? "${Math.max(1, Math.round(rt.toString().toBigDecimal().doubleValue()))} min" : null
    String prog = programLabel(irr.program?.toString(), irr.program_name?.toString())
    String volText = (vol != null && vol.toString().isNumber()) ? "${vol.toString().toBigDecimal().setScale(1, BigDecimal.ROUND_HALF_UP)} gal" : null
    String text
    switch (status) {
        case "complete": text = "Watered (" + [mins, prog, volText].findAll { it }.join(", ") + "), ${when}"; break
        case "skipped":  text = "Skipped (${prog}), ${when}"; break
        default:         text = "${status.capitalize()} (" + [mins, prog].findAll { it }.join(", ") + "), ${when}"
    }
    setActivity(text, startMs)
}

def setFlowRate(rate) {
    if (rate != null && rate.toString().isNumber()) {
        def r = rate.toString().toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
        sendEvent(name: "flowRateGpm", value: r, unit: "gpm")
    }
}

def setPresetRuntime(Integer minutes) {
    if (minutes != null) sendEvent(name: "presetRuntime", value: minutes, unit: "min")
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

void setActivity(String text, Long startMs) {
    state.activityStartMs = startMs
    sendEvent(name: "lastActivity", value: text)
}

Integer myStation() {
    def s = toInt(device.getDataValue("station"))
    if (s != null) return s
    def dni = device.deviceNetworkId
    return (dni && dni.contains(":")) ? toInt(dni.substring(dni.lastIndexOf(":") + 1)) : null
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

Integer toInt(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).intValue()
    def s = v.toString().trim()
    return s.isNumber() ? s.toBigDecimal().intValue() : null
}

String fmt(Date d) {
    return d.format("EEE MMM d, h:mm a", location.timeZone).replace("AM", "am").replace("PM", "pm")
}

String clock(Date d) {
    return d.format("h:mm a", location.timeZone).replace("AM", "am").replace("PM", "pm")
}
