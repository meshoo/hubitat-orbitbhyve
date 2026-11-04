/*
* B-hyve API Inspector (diagnostic tool)
*
* A one-off Hubitat app that logs in to the Orbit B-hyve cloud API, fetches the raw data the
* integration works from, and produces a REDACTED JSON dump you can safely share. It does not
* create devices, change any B-hyve setting, or interact with the "Orbit Bhyve Controller" app.
*
* Part of the meshoo/hubitat-orbitbhyve fork (see README, "About this fork"). The login and request
* pattern is taken from the Orbit Bhyve Controller app by Kurt Sanders and Dominick Meglio.
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
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String apiUrl = "https://api.orbitbhyve.com/v1/"
@Field static final int maxWateringEvents = 3
@Field static final int logChunkSize = 3000
@Field static final int maxStateChars = 60000

// Keys whose values are identifiers. They are replaced with stable tokens (ID-1, ID-2, ...) so the
// relationships between objects (device -> mesh, program -> device) stay visible after redaction.
@Field static final List idKeys = ["id", "mesh_id", "device_id", "user_id", "program_id", "station_id",
                                   "weather_station_id", "weather_forecast_location_id", "et_id", "landscape_id"]

// Keys (case-insensitive regex) whose values are removed entirely.
@Field static final String sensitiveKeyPattern =
    /(?i).*(mac|token|api_key|secret|password|serial|email|address|location|coordinat|latitude|longitude|gateway|reference|image|photo|postal|^zip|_zip|^city$|_city$|country|phone|ssid|^ip$|_ip$|^ip_|timezone_id|timezone_name|^lat$|^lng$|^lon$).*/

definition(
    name:        "B-hyve API Inspector",
    namespace:   "meshoo",
    author:      "meshoo/hubitat-orbitbhyve contributors",
    description: "Diagnostic: dumps a redacted copy of your Orbit B-hyve API data so the device model can be checked. Remove when done.",
    category:    "Utility",
    iconUrl:     "",
    iconX2Url:   "",
    iconX3Url:   "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "B-hyve API Inspector", install: true, uninstall: true) {
        section("What this does") {
            paragraph "Logs in to the B-hyve cloud, reads your devices, hubs (meshes), timer programs and the most recent " +
                "watering events, then shows a redacted JSON copy below. IDs become tokens such as ID-1, and MAC addresses, " +
                "locations, addresses, e-mail and tokens are removed. Nothing is changed in your B-hyve account. " +
                "<b>Read the output before sharing it</b>, and uninstall this app when you are done."
        }
        section("B-hyve account") {
            input name: "username", type: "text", title: "B-hyve e-mail", required: true
            input name: "password", type: "password", title: "B-hyve password", required: true
            input name: "anonymizeNames", type: "bool", title: "Replace device, zone and program names with placeholders", defaultValue: true
        }
        section("Run") {
            input name: "btnRun", type: "button", title: "Fetch and redact"
            input name: "btnClear", type: "button", title: "Clear output"
            if (state.lastRunAt) paragraph "Last run: ${state.lastRunAt}. ${state.lastSummary ?: ''}"
            if (state.lastFile) paragraph "Also saved to the hub's File Manager as <b>${state.lastFile}</b> (Settings ▸ File Manager), for easy download."
        }
        if (state.lastOutput) {
            section("Redacted output") {
                paragraph "<pre style='white-space:pre-wrap;font-size:11px;max-height:600px;overflow:auto'>${htmlEscape(state.lastOutput)}</pre>"
            }
        }
    }
}

def installed() { }
def updated() { }
def uninstalled() { }

def appButtonHandler(btn) {
    switch (btn) {
        case "btnRun":
            runInspection()
            break
        case "btnClear":
            state.remove("lastOutput")
            state.remove("lastSummary")
            state.remove("lastRunAt")
            state.remove("lastFile")
            break
    }
}

// ---------------------------------------------------------------------------------------------
// Collection
// ---------------------------------------------------------------------------------------------

def runInspection() {
    def session = login()
    if (!session) {
        state.lastOutput = "Login failed: ${state.loginError ?: 'unknown error'}"
        state.lastRunAt = new Date().toString()
        return
    }

    def report = [
        generated_by : "B-hyve API Inspector",
        notes        : "IDs are replaced with stable tokens; sensitive fields are removed.",
        requests     : [:]
    ]

    def devices = apiGet(session, "devices", ["user_id": session.userId])
    report.requests.devices = devices.summary
    report.devices = devices.data

    def deviceList = (devices.data instanceof List) ? devices.data : []

    // Hubs/meshes: every distinct mesh_id seen on any device.
    def meshIds = deviceList.collect { it instanceof Map ? it.mesh_id : null }.findAll { it }.unique()
    report.meshes = []
    meshIds.each { meshId ->
        def r = apiGet(session, "meshes/${meshId}", null)
        report.requests["meshes/${meshId}"] = r.summary
        report.meshes << [mesh_id: meshId, response: r.data]
    }

    // Per sprinkler timer: programs and the most recent watering events.
    report.programs = []
    report.watering_events = []
    deviceList.findAll { it instanceof Map && it.type == "sprinkler_timer" && it.id }.each { dev ->
        def p = apiGet(session, "sprinkler_timer_programs", ["device_id": dev.id])
        report.requests["sprinkler_timer_programs?device_id=${dev.id}"] = p.summary
        report.programs << [device_id: dev.id, response: p.data]

        def w = apiGet(session, "watering_events/${dev.id}", null)
        report.requests["watering_events/${dev.id}"] = w.summary
        def events = (w.data instanceof List) ? w.data.take(maxWateringEvents) : w.data
        report.watering_events << [device_id: dev.id, response: events]
    }

    report.summary = summarize(deviceList)

    // Redact. One token map is shared by the whole report so the same ID always maps to the same token.
    def ctx = [ids: [:], names: [:]]
    def redacted = redact(report, null, ctx)
    // Request keys contain raw IDs; redact them as text.
    redacted.requests = redactRequestKeys(redacted.requests, ctx)

    def json = JsonOutput.prettyPrint(JsonOutput.toJson(redacted))
    // Keep app state small; the full dump is in File Manager and the logs.
    state.lastOutput = (json.length() > maxStateChars) ? json.substring(0, maxStateChars) + "\n... (truncated here; see File Manager or Logs for the full output)" : json
    state.lastRunAt = new Date().toString()
    state.lastSummary = "Devices: ${deviceList.size()}, meshes: ${meshIds.size()}, ${json.length()} characters."

    def fileName = "bhyve-api-inspector.json"
    try {
        uploadHubFile(fileName, json.getBytes("UTF-8"))
        state.lastFile = fileName
    } catch (e) {
        state.remove("lastFile")
        log.warn "Could not save to File Manager (needs a recent hub firmware): ${e}"
    }

    log.info "B-hyve API Inspector: ${state.lastSummary} Output follows in ${(int) Math.ceil(json.length() / (double) logChunkSize)} part(s)."
    int part = 1
    for (int i = 0; i < json.length(); i += logChunkSize) {
        log.info "B-hyve API Inspector part ${part++}:\n" + json.substring(i, Math.min(json.length(), i + logChunkSize))
    }
}

// A non-identifying overview, computed before redaction, to make the dump easy to read.
def summarize(List deviceList) {
    def out = []
    deviceList.each { d ->
        if (!(d instanceof Map)) return
        def zones = (d.zones instanceof List) ? d.zones : []
        out << [
            type              : d.type,
            hardware_version  : d.hardware_version,
            firmware_version  : d.firmware_version,
            num_stations      : d.num_stations,
            zone_count        : zones.size(),
            zone_stations     : zones.collect { it instanceof Map ? it.station : null },
            has_mesh          : d.mesh_id != null,
            has_battery       : d.battery != null,
            battery_keys      : (d.battery instanceof Map) ? d.battery.keySet().sort() : null,
            is_connected      : d.is_connected,
            top_level_keys    : d.keySet().sort(),
            status_keys       : (d.status instanceof Map) ? d.status.keySet().sort() : null,
            watering_status   : (d.status instanceof Map) ? d.status.watering_status : null
        ]
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

def login() {
    state.remove("loginError")
    if (!username || !password) {
        state.loginError = "enter your B-hyve e-mail and password"
        return null
    }
    def params = [
        uri               : apiUrl,
        path              : "session",
        headers           : ["orbit-app-id": "Orbit Support Dashboard"],
        body              : [session: [email: username, password: password]],
        requestContentType: "application/json",
        contentType       : "application/json"
    ]
    def session = null
    try {
        httpPost(params) { resp ->
            if (resp.status == 200 && resp.data?.orbit_api_key) {
                session = [apiKey: "${resp.data.orbit_api_key}", userId: "${resp.data.user_id}"]
            } else {
                state.loginError = "HTTP ${resp.status}"
            }
        }
    } catch (e) {
        state.loginError = "${e}"
    }
    return session
}

// Returns [summary: "...", data: <parsed JSON or null>]. Never throws.
def apiGet(Map session, String path, Map query) {
    def params = [
        uri        : apiUrl,
        path       : path,
        contentType: "application/json",
        headers    : ["orbit-app-id": "Orbit Support Dashboard", "orbit-api-key": session.apiKey]
    ]
    if (query) params.query = query
    def result = [summary: null, data: null]
    try {
        httpGet(params) { resp ->
            result.summary = "HTTP ${resp.status}"
            result.data = resp.data
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        result.summary = "HTTP ${e.getResponse()?.status} (${e.getMessage()})"
    } catch (e) {
        result.summary = "error: ${e}"
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// Redaction
// ---------------------------------------------------------------------------------------------

def redact(value, String key, Map ctx) {
    if (key != null && isIdKey(key) && value != null && !(value instanceof Map) && !(value instanceof List)) {
        return idToken(value, ctx)
    }
    if (key != null && key ==~ sensitiveKeyPattern) {
        return (value == null) ? null : "<redacted>"
    }
    if (key != null && anonymizeNames != false && isNameKey(key) && value instanceof String) {
        return nameToken(value, ctx)
    }
    if (value instanceof Map) {
        def out = [:]
        value.each { k, v -> out[k] = redact(v, k?.toString(), ctx) }
        return out
    }
    if (value instanceof List) {
        // Lists of IDs under an ID-ish key (e.g. "device_ids") keep the key's meaning.
        return value.collect { item -> redact(item, (key != null && isIdKey(key)) ? key : null, ctx) }
    }
    return value
}

boolean isIdKey(String key) {
    def k = key.toLowerCase()
    return idKeys.contains(k) || k.endsWith("_ids") || (k.endsWith("_id") && !(k ==~ sensitiveKeyPattern))
}

boolean isNameKey(String key) {
    def k = key.toLowerCase()
    return k == "name" || k.endsWith("_name") || k == "nickname" || k == "notes" || k == "description"
}

String idToken(value, Map ctx) {
    def v = value.toString()
    if (!ctx.ids.containsKey(v)) ctx.ids[v] = "ID-${ctx.ids.size() + 1}"
    return ctx.ids[v]
}

String nameToken(String value, Map ctx) {
    if (!ctx.names.containsKey(value)) ctx.names[value] = "Name-${ctx.names.size() + 1}"
    return ctx.names[value]
}

Map redactRequestKeys(Map requests, Map ctx) {
    def out = [:]
    requests?.each { k, v ->
        def key = k.toString()
        ctx.ids.each { raw, token -> key = key.replace(raw, token) }
        out[key] = v
    }
    return out
}

String htmlEscape(String s) {
    return s?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace(">", "&gt;")
}
