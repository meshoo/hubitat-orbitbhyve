/*
* B-hyve v2 (Hubitat app)
*
* Models Orbit B-hyve hardware as it physically exists:
*   Cloud Connection (websocket)  -  one per account
*   Wi-Fi Hub                     -  one per B-hyve hub ("bridge")
*   Timer                         -  one per hose timer / controller; owns the battery
*     └─ Zone (child device)      -  one per outlet/station; valve, switch, activity
* Hubs and timers are linked by mesh_id (Gen 1 hubs) or network_topology_id (Gen 2 hubs).
* See docs/bhyve-api-model.md.
*
* v2 is a rewrite in the meshoo/hubitat-orbitbhyve fork (2026), derived from the "Orbit Bhyve Controller"
* app: 2019 (c) Kurt Sanders (SanderSoft), 2020 Dominick Meglio (Hubitat port), null-safety fixes 2025
* Matthew Petro. Changes: new device model, new names/namespace, websocket moved to its own device,
* per-device error isolation, hub-timer linking, health and battery reporting on the right device.
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

@Field static final String apiUrl = "https://api.orbitbhyve.com/v1/"
@Field static final String NS = "meshoo"
@Field static final String DRV_CLOUD = "B-hyve v2 Cloud Connection"
@Field static final String DRV_HUB = "B-hyve v2 Wi-Fi Hub"
@Field static final String DRV_TIMER = "B-hyve v2 Timer"
@Field static final long HISTORY_MIN_INTERVAL_MS = 30L * 60L * 1000L

definition(
    name:        "B-hyve v2",
    namespace:   "meshoo",
    author:      "meshoo/hubitat-orbitbhyve contributors (based on work by Kurt Sanders and Dominick Meglio)",
    description: "Orbit B-hyve hubs, timers and zones, modeled as they physically exist. Breaking successor to Orbit Bhyve Controller 1.8.",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    iconX3Url:   "",
    singleInstance: true,
    documentationLink: "https://github.com/meshoo/hubitat-orbitbhyve/blob/master/README.md"
)

preferences {
    page(name: "mainPage")
    page(name: "optionsPage")
    page(name: "devicesPage")
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

def mainPage() {
    boolean ok = false
    if (username && password) ok = login()
    dynamicPage(name: "mainPage", title: "B-hyve v2", nextPage: ok ? "optionsPage" : null, install: false, uninstall: true) {
        section("B-hyve account") {
            input name: "username", type: "text", title: "B-hyve e-mail", required: true, submitOnChange: true
            input name: "password", type: "password", title: "B-hyve password", required: true, submitOnChange: true
            if (username && password) paragraph(ok ? "Login OK." : "Login failed: ${state.loginError ?: 'unknown error'}")
        }
    }
}

def optionsPage() {
    dynamicPage(name: "optionsPage", title: "Options", install: true, uninstall: false) {
        section("Polling") {
            input name: "pollMinutes", type: "enum", title: "Refresh from the B-hyve cloud every", required: true, defaultValue: "5",
                options: ["0": "Off (websocket only)", "1": "1 minute", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes", "60": "1 hour"]
            paragraph "Watering, connection and battery changes also arrive in real time over the websocket."
        }
        section("Notifications") {
            input name: "notificationDevices", type: "capability.notification", title: "Send notifications to", multiple: true, required: false
            input name: "eventsToNotify", type: "enum", title: "Notify about", multiple: true, required: false,
                options: ["battery": "Timer battery low", "connected": "Hub/timer online or offline", "cloud": "B-hyve cloud connection lost",
                          "rain": "Rain delay changed", "valve": "Zone started/stopped watering"]
            input name: "lowBatteryPercent", type: "number", title: "Low battery threshold (%)", defaultValue: 20, range: "1..99"
        }
        section("Devices") {
            href name: "toDevices", page: "devicesPage", title: "Hubs, timers and zones", description: "Show the device tree and remove stale devices"
        }
        section(hideable: true, hidden: true, "Logging") {
            input name: "logInfo", type: "bool", title: "Info logging", defaultValue: true
            input name: "logDebug", type: "bool", title: "Debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Hubs, timers and zones", install: false, uninstall: false) {
        section("Device tree") {
            def tree = state.tree ?: []
            if (!tree) paragraph "Nothing yet. Press Done on the previous page, then come back after the first refresh."
            tree.each { h -> paragraph h }
        }
        def stale = staleChildren()
        section("Stale devices") {
            if (!stale) {
                paragraph "None."
            } else {
                paragraph "These devices no longer exist in your B-hyve account: ${stale.collect { it.displayName }.join(', ')}"
                input name: "btnRemoveStale", type: "button", title: "Remove stale devices"
            }
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "btnRemoveStale") {
        staleChildren().each { d ->
            logInfoMsg "Removing stale device ${d.displayName}"
            try { deleteChildDevice(d.deviceNetworkId) } catch (e) { log.warn "Could not remove ${d.displayName}: ${e}" }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

def installed() { initialize() }

def updated() {
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices().each { d ->
        try { deleteChildDevice(d.deviceNetworkId) } catch (e) { log.warn "Could not delete ${d.displayName}: ${e}" }
    }
}

def initialize() {
    if (!state.apiKey && !login()) {
        log.error "B-hyve login failed: ${state.loginError}"
        return
    }
    def cloud = ensureCloudDevice()
    setPolling(pollMinutes ?: "5")
    runIn(2, "refresh")
    cloud?.initialize()
}

void setPolling(String minutes) {
    switch (minutes) {
        case "1":  runEvery1Minute("refresh"); break
        case "5":  runEvery5Minutes("refresh"); break
        case "10": runEvery10Minutes("refresh"); break
        case "15": runEvery15Minutes("refresh"); break
        case "30": runEvery30Minutes("refresh"); break
        case "60": runEvery1Hour("refresh"); break
        default:   logInfoMsg "Polling off; relying on the websocket"
    }
}

// ---------------------------------------------------------------------------------------------
// Refresh: reconcile devices and apply state. One failing device never stops the others.
// ---------------------------------------------------------------------------------------------

def refresh() {
    def devices = apiGet("devices", ["user_id": state.userId])
    if (!(devices instanceof List)) {
        log.warn "B-hyve refresh: no device list returned"
        return
    }
    state.lastDeviceIds = devices.findAll { it instanceof Map && it.id }.collect { it.id.toString() }

    def hubs = devices.findAll { it instanceof Map && it.type == "bridge" && it.id }
    def timers = devices.findAll { it instanceof Map && it.type == "sprinkler_timer" && it.id }
    devices.findAll { it instanceof Map && !(it.type in ["bridge", "sprinkler_timer"]) }.each {
        logDebugMsg "Skipping unsupported B-hyve device type '${it.type}'"
    }

    boolean fetchHistory = (now() - (state.lastHistoryAt ?: 0L)) >= HISTORY_MIN_INTERVAL_MS
    def tree = []

    hubs.each { h ->
        try {
            def hubTimers = timers.findAll { t -> linkId(t) != null && linkId(t) == linkId(h) }
            def dev = ensureChild(h.id.toString(), DRV_HUB, h.name ?: "B-hyve Hub")
            dev?.applyHub(h, hubTimers.collect { it.name ?: it.id.toString() })
            tree << "<b>Hub:</b> ${h.name ?: h.id} (${h.hardware_version ?: '?'}, ${h.is_connected == false ? 'offline' : 'online'})" +
                (hubTimers ? "<br>" + hubTimers.collect { "&nbsp;&nbsp;└ Timer: ${it.name ?: it.id} (${it.num_stations ?: '?'} zone(s))" }.join("<br>") : "")
        } catch (e) {
            log.warn "B-hyve: could not update hub '${h?.name}': ${e}"
        }
    }

    timers.each { t ->
        try {
            def hub = hubs.find { h -> linkId(h) != null && linkId(h) == linkId(t) }
            def dev = ensureChild(t.id.toString(), DRV_TIMER, t.name ?: "B-hyve Timer")
            if (!dev) return
            def programs = apiGet("sprinkler_timer_programs", ["device_id": t.id])
            def ctx = [
                hubName  : hub?.name,
                hubOnline: (hub == null) ? null : (hub.is_connected != false),
                hasHub   : hub != null
            ]
            dev.applyTimer(t, (programs instanceof List) ? programs : [], ctx)
            if (fetchHistory) refreshHistory(t.id.toString())
            if (!hub) tree << "<b>Timer:</b> ${t.name ?: t.id} (no hub)"
        } catch (e) {
            log.warn "B-hyve: could not update timer '${t?.name}': ${e}"
        }
    }

    if (fetchHistory) state.lastHistoryAt = now()
    state.tree = tree
    state.lastRefreshAt = now()
}

// Called from the timer/cloud devices. Fetches recent watering events for one timer and applies them.
def refreshHistory(String orbitId) {
    def events = apiGet("watering_events/${orbitId}", null)
    def dev = getChildDevice(dni(orbitId))
    if (dev && events instanceof List) {
        try { dev.applyHistory(events) } catch (e) { log.warn "B-hyve: could not apply watering history: ${e}" }
    }
}

def refreshHistorySoon(String orbitId) {
    // The cloud records the event a little after watering ends.
    runIn(45, "refreshHistoryHandler", [data: [id: orbitId], overwrite: false])
}

def refreshHistoryHandler(Map data) {
    if (data?.id) refreshHistory(data.id.toString())
}

def refreshSoon() {
    runIn(5, "refresh")
}

// mesh_id for Gen 1 hubs and their timers, network_topology_id for Gen 2.
String linkId(Map d) {
    def v = d?.mesh_id ?: d?.network_topology_id
    return v?.toString()
}

// ---------------------------------------------------------------------------------------------
// Websocket events (from the Cloud Connection device)
// ---------------------------------------------------------------------------------------------

def handleEvent(Map payload) {
    def orbitId = payload?.device_id?.toString()
    if (!orbitId) {
        logDebugMsg "B-hyve event without device_id: ${payload?.event}"
        return
    }
    def dev = getChildDevice(dni(orbitId))
    if (!dev) {
        logDebugMsg "B-hyve event '${payload.event}' for an unknown device; refreshing"
        refreshSoon()
        return
    }
    try {
        dev.handleEvent(payload)
    } catch (e) {
        log.warn "B-hyve: error handling '${payload.event}' on ${dev.displayName}: ${e}"
    }
}

// ---------------------------------------------------------------------------------------------
// Commands (from the Timer device)
// ---------------------------------------------------------------------------------------------

def sendCommand(Map msg) {
    def cloud = ensureCloudDevice()
    if (!cloud) {
        log.error "B-hyve: no cloud connection device; command not sent"
        return
    }
    cloud.sendMessage(msg)
    runIn(15, "refresh")
}

String apiTimestamp() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
}

def startZone(String orbitId, Integer station, Integer minutes) {
    sendCommand([event: "change_mode", mode: "manual", device_id: orbitId, timestamp: apiTimestamp(),
                 stations: [[station: station, run_time: minutes]]])
}

def stopWatering(String orbitId) {
    sendCommand([event: "change_mode", mode: "manual", device_id: orbitId, timestamp: apiTimestamp(), stations: []])
}

def setRunMode(String orbitId, String mode) {
    sendCommand([event: "change_mode", mode: mode, device_id: orbitId, timestamp: apiTimestamp()])
}

def setRainDelay(String orbitId, Integer hours) {
    sendCommand([event: "rain_delay", device_id: orbitId, delay: hours])
}

// ---------------------------------------------------------------------------------------------
// Notifications and settings used by the devices
// ---------------------------------------------------------------------------------------------

def sendNotice(String category, String message) {
    logInfoMsg message
    if (notificationDevices && eventsToNotify && eventsToNotify.contains(category)) {
        notificationDevices*.deviceNotification(message)
    }
}

Integer lowBatteryThreshold() {
    return (lowBatteryPercent != null) ? lowBatteryPercent.toInteger() : 20
}

String getApiToken() { return state.apiKey }

boolean isDebugLogEnabled() { return logDebug == true }
boolean isInfoLogEnabled() { return logInfo != false }

// ---------------------------------------------------------------------------------------------
// Child devices
// ---------------------------------------------------------------------------------------------

String dniPrefix() { return "bhyve2-${app.id}-" }
String dni(String orbitId) { return dniPrefix() + orbitId }
String cloudDni() { return dniPrefix() + "cloud" }

String orbitIdFromDni(String dni) {
    if (dni == null || !dni.startsWith(dniPrefix())) return null
    def rest = dni.substring(dniPrefix().length())
    return (rest == "cloud") ? null : rest
}

def ensureCloudDevice() {
    def d = getChildDevice(cloudDni())
    if (d) return d
    try {
        d = addChildDevice(NS, DRV_CLOUD, cloudDni(), [name: DRV_CLOUD, label: "B-hyve Cloud Connection", isComponent: false])
        logInfoMsg "Created ${DRV_CLOUD}"
    } catch (e) {
        log.error "B-hyve: could not create '${DRV_CLOUD}'. Is the driver installed? ${e}"
    }
    return d
}

def ensureChild(String orbitId, String driver, String label) {
    def d = getChildDevice(dni(orbitId))
    if (d) return d
    try {
        d = addChildDevice(NS, driver, dni(orbitId), [name: driver, label: label, isComponent: false])
        d.updateDataValue("orbitId", orbitId)
        logInfoMsg "Created ${driver} '${label}'"
    } catch (e) {
        log.error "B-hyve: could not create ${driver} '${label}'. Is the driver installed? ${e}"
    }
    return d
}

List staleChildren() {
    def known = state.lastDeviceIds ?: []
    if (!known) return []
    return getChildDevices().findAll { d ->
        def id = orbitIdFromDni(d.deviceNetworkId)
        id != null && !known.contains(id)
    }
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

Map baseHeaders() { return ["orbit-app-id": "Orbit Support Dashboard"] }

boolean login() {
    state.remove("loginError")
    if (!username || !password) return false
    def params = [
        uri               : apiUrl,
        path              : "session",
        headers           : baseHeaders(),
        body              : [session: [email: username, password: password]],
        requestContentType: "application/json",
        contentType       : "application/json"
    ]
    boolean ok = false
    try {
        httpPost(params) { resp ->
            if (resp.status == 200 && resp.data?.orbit_api_key) {
                state.apiKey = resp.data.orbit_api_key.toString()
                state.userId = resp.data.user_id?.toString()
                ok = true
            } else {
                state.loginError = "HTTP ${resp.status}"
            }
        }
    } catch (e) {
        state.loginError = e.toString()
    }
    if (!ok) state.remove("apiKey")
    return ok
}

// GET with one re-login retry on 401/403. Returns parsed JSON or null; never throws.
def apiGet(String path, Map query, boolean retried = false) {
    if (!state.apiKey && !login()) return null
    def params = [uri: apiUrl, path: path, contentType: "application/json", headers: baseHeaders() + ["orbit-api-key": state.apiKey]]
    if (query) params.query = query
    def result = null
    try {
        httpGet(params) { resp -> if (resp.status == 200) result = resp.data }
    } catch (groovyx.net.http.HttpResponseException e) {
        def status = e.getResponse()?.status
        if ((status == 401 || status == 403) && !retried) {
            logDebugMsg "B-hyve: HTTP ${status} on ${path}; logging in again"
            if (login()) return apiGet(path, query, true)
        }
        log.warn "B-hyve: HTTP ${status} on ${path}"
    } catch (e) {
        log.warn "B-hyve: request ${path} failed: ${e}"
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------------------------

void logDebugMsg(String msg) { if (logDebug == true) log.debug msg }
void logInfoMsg(String msg) { if (logInfo != false) log.info msg }
