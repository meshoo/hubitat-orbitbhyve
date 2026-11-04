/*
* B-hyve v2 Wi-Fi Hub (Hubitat driver)
*
* One device per B-hyve Wi-Fi hub (API type "bridge", Gen 1 or Gen 2). Reports the hub's own health and
* which timers it relays. Hubs have no battery.
*
* Part of v2 in the meshoo/hubitat-orbitbhyve fork (2026), succeeding the "Orbit Bhyve Bridge" driver by
* Kurt Sanders and Dominick Meglio (2019-2020). Changes: health, generation and linked-timer attributes.
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
    definition(name: "B-hyve v2 Wi-Fi Hub", namespace: "meshoo", author: "meshoo/hubitat-orbitbhyve contributors") {
        capability "Refresh"
        capability "Sensor"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "lastConnectedAt", "string"
        attribute "firmwareVersion", "string"
        attribute "hardwareVersion", "string"
        attribute "generation", "number"
        attribute "networkInterface", "string"
        attribute "timerCount", "number"
        attribute "timers", "string"
    }
}

def refresh() { parent?.refresh() }

// Called by the app on every refresh with the hub's JSON from GET devices.
def applyHub(Map d, List timerNames) {
    if (d == null) return
    setHealth(d.is_connected)
    if (d.last_connected_at) sendEvent(name: "lastConnectedAt", value: d.last_connected_at.toString())
    if (d.firmware_version != null) sendEvent(name: "firmwareVersion", value: d.firmware_version.toString())
    if (d.hardware_version != null) sendEvent(name: "hardwareVersion", value: d.hardware_version.toString())
    def gen = d.network_topology_id ? 2 : (d.mesh_id ? 1 : null)
    if (gen != null) sendEvent(name: "generation", value: gen)
    if (d.network_interface) sendEvent(name: "networkInterface", value: d.network_interface.toString())
    def names = (timerNames ?: []).collect { it?.toString() }.findAll { it }
    sendEvent(name: "timerCount", value: names.size())
    sendEvent(name: "timers", value: names ? names.join(", ") : "none")
}

// Websocket events routed by the app.
def handleEvent(Map payload) {
    switch (payload?.event) {
        case "device_connected":
            setHealth(true)
            break
        case "device_disconnected":
            setHealth(false)
            break
        default:
            logDebug "Hub ignored event '${payload?.event}'"
    }
}

void setHealth(connected) {
    if (connected == null) return
    def value = (connected == true || connected.toString() == "true") ? "online" : "offline"
    def previous = device.currentValue("healthStatus")
    if (previous == value) return
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} is ${value}")
    if (previous != null) parent?.sendNotice("connected", "B-hyve hub ${device.displayName} is ${value}")
}

void logDebug(String msg) { if (parent?.isDebugLogEnabled()) log.debug msg }
