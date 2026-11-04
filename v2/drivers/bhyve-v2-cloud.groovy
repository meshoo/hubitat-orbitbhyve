/*
* B-hyve v2 Cloud Connection (Hubitat driver)
*
* Holds the single B-hyve websocket for the account and hands every event to the B-hyve v2 app.
* It reflects the health of the cloud connection, not of any B-hyve hardware.
*
* Part of v2 in the meshoo/hubitat-orbitbhyve fork (2026). The websocket connect/retry/ping logic is derived
* from the "Orbit Bhyve Sprinkler Timer" driver by Kurt Sanders and Dominick Meglio (2019-2020), with
* null-safety fixes by Matthew Petro (2025). Changes: moved to a dedicated device, retry is driven by a
* watchdog even when the first connection fails, health attributes added.
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
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String wsHost = "wss://api.orbitbhyve.com/v1/events"
@Field static final long RECONNECT_EVERY_MS = 30L * 60L * 1000L
@Field static final int CLOUD_DOWN_NOTIFY_MIN = 10
@Field static ConcurrentHashMap socketOpen = new ConcurrentHashMap()
@Field static final Object socketLock = new Object()

metadata {
    definition(name: "B-hyve v2 Cloud Connection", namespace: "meshoo", author: "meshoo/hubitat-orbitbhyve contributors") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "lastEventAt", "string"
        attribute "connectedSince", "string"

        command "reconnect"
    }
}

def installed() {
    setOpen(false)
    sendEvent(name: "healthStatus", value: "offline")
}

def uninstalled() {
    unschedule()
    try { interfaces.webSocket.close() } catch (ignored) { }
}

def refresh() { parent?.refresh() }

def reconnect() { initialize() }

// Called at hub start-up (Initialize capability) and by the app.
def initialize() {
    unschedule()
    state.retryCount = 0
    state.nextRetry = 0
    connect()
    schedule("0/25 * * * * ? *", "watchdog")
}

void connect() {
    synchronized (socketLock) {
        setOpen(false)
        try { interfaces.webSocket.close() } catch (ignored) { }
        logDebug "Connecting to B-hyve websocket"
        try {
            interfaces.webSocket.connect(wsHost)
        } catch (e) {
            log.warn "B-hyve websocket connect failed: ${e}"
        }
    }
}

// Every 25 s: ping when open, reconnect with back-off when not, and recycle the socket every 30 min.
def watchdog() {
    if (isOpen()) {
        if (state.openedAt && now() - (state.openedAt as Long) >= RECONNECT_EVERY_MS) {
            logDebug "Websocket open for 30 minutes; reconnecting"
            state.retryCount = 0
            state.nextRetry = 0
            connect()
            return
        }
        sendRaw([event: "ping"])
        return
    }
    long nextRetry = (state.nextRetry ?: 0L) as Long
    if (now() >= nextRetry) {
        int retries = ((state.retryCount ?: 0) as Integer) + 1
        state.retryCount = retries
        state.nextRetry = now() + 30000L * Math.min(retries, 10)
        connect()
    }
    checkCloudDown()
}

// Commands from the app. If the socket is down, keep the latest command and send it once reconnected.
def sendMessage(Map msg) {
    if (!isOpen()) {
        state.pendingCommand = JsonOutput.toJson(msg)
        log.warn "B-hyve websocket not connected; command will be sent after reconnecting"
        state.nextRetry = 0
        watchdog()
        return
    }
    sendRaw(msg)
}

void sendRaw(Map msg) {
    try {
        interfaces.webSocket.sendMessage(JsonOutput.toJson(msg))
    } catch (e) {
        log.warn "B-hyve websocket send failed: ${e}"
        setOpen(false)
    }
}

def webSocketStatus(String message) {
    if (message == "status: open") {
        setOpen(true)
        state.openedAt = now()
        state.retryCount = 0
        state.nextRetry = 0
        sendRaw([event: "app_connection", orbit_app_id: UUID.randomUUID().toString(), orbit_session_token: parent?.getApiToken()])
        markOnline()
        parent?.refreshSoon()
        if (state.pendingCommand) {
            def cmd = state.pendingCommand
            state.remove("pendingCommand")
            pauseExecution(1000)
            logDebug "Sending command queued while disconnected"
            try { interfaces.webSocket.sendMessage(cmd) } catch (e) { log.warn "B-hyve websocket send failed: ${e}" }
        }
    } else {
        // "status: closing", "failure: ..." and anything else mean the socket is gone; the watchdog reconnects.
        if (isOpen()) log.warn "B-hyve websocket ${message}; will reconnect"
        setOpen(false)
        markOffline()
    }
}

def parse(String message) {
    def payload
    try {
        payload = new JsonSlurper().parseText(message)
    } catch (e) {
        logDebug "Ignoring non-JSON websocket message"
        return
    }
    if (!(payload instanceof Map)) return
    sendEvent(name: "lastEventAt", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone))
    if (payload.event in ["pong", "ping"]) return
    logDebug "Event: ${payload.event} for device ${payload.device_id ? '(set)' : '(none)'}"
    parent?.handleEvent(payload)
}

void markOnline() {
    state.remove("offlineSince")
    state.remove("downNotified")
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        sendEvent(name: "connectedSince", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone))
    }
}

void markOffline() {
    if (!state.offlineSince) state.offlineSince = now()
    if (device.currentValue("healthStatus") != "offline") sendEvent(name: "healthStatus", value: "offline")
}

void checkCloudDown() {
    markOffline()
    long since = (state.offlineSince ?: now()) as Long
    if (!state.downNotified && now() - since >= CLOUD_DOWN_NOTIFY_MIN * 60000L) {
        state.downNotified = true
        parent?.sendNotice("cloud", "B-hyve cloud connection has been down for ${CLOUD_DOWN_NOTIFY_MIN} minutes")
    }
}

boolean isOpen() { return socketOpen.get(device.id.toString()) == true }
void setOpen(boolean v) { socketOpen.put(device.id.toString(), v) }

void logDebug(String msg) { if (parent?.isDebugLogEnabled()) log.debug msg }
