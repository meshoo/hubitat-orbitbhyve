// Behavioural test for B-hyve v2: loads the real app/driver files, stubs the Hubitat APIs
// (sendEvent, child devices, httpGet, websocket) and replays fake B-hyve data shaped like a real
// Gen 1 hub + HT25 and Gen 2 hub + XD 4-outlet account. Run via tests/run.sh. Fake IDs only.
import groovy.json.JsonSlurper

class Dev {
    String deviceNetworkId; String label; String id
    Map data = [:]; Map attrs = [:]; List events = []
    Script script
    String getDisplayName() { label }
    def currentValue(String n) { attrs[n] }
    String getDataValue(String k) { data[k] }
    void updateDataValue(String k, v) { data[k] = v?.toString() }
    void setLabel(String l) { label = l }
    def methodMissing(String name, args) { script.invokeMethod(name, args) }
}

repo = args[0]
shell = new GroovyShell()
files = ["B-hyve v2 Wi-Fi Hub": "v2/drivers/bhyve-v2-hub.groovy", "B-hyve v2 Timer": "v2/drivers/bhyve-v2-timer.groovy",
         "B-hyve v2 Zone": "v2/drivers/bhyve-v2-zone.groovy", "B-hyve v2 Cloud Connection": "v2/drivers/bhyve-v2-cloud.groovy"]
notices = []; runIns = []; warnings = []
clock = 1_800_000_000_000L
logger = [info: { m -> }, debug: { m -> }, warn: { m -> warnings << m.toString(); println "  WARN: $m" }, error: { m -> warnings << m.toString(); println "  ERROR: $m" }]
loc = [timeZone: TimeZone.getTimeZone("America/Phoenix")]
idSeq = 100

def common(Binding b, Dev dev) {
    b.setVariable("device", dev)
    b.setVariable("state", [:])
    b.setVariable("log", logger)
    b.setVariable("location", loc)
    b.setVariable("now", { -> clock })
    b.setVariable("sendEvent", { Map e -> dev.attrs[e.name] = e.value; dev.events << e })
    b.setVariable("runIn", { Object[] a -> runIns << a[1] })
    ["schedule", "unschedule", "pauseExecution", "runEvery1Minute", "runEvery5Minutes", "runEvery10Minutes", "runEvery15Minutes", "runEvery30Minutes", "runEvery1Hour"].each { n -> b.setVariable(n, { Object[] a -> }) }
}

def loadDriver(String type, String dni, String label, parentObj) {
    def dev = new Dev(deviceNetworkId: dni, label: label, id: (idSeq++).toString())
    def b = new Binding()
    common(b, dev)
    def children = [:]
    b.setVariable("parent", parentObj)
    b.setVariable("presetRunTime", null)
    b.setVariable("getChildDevice", { String d -> children[d] })
    b.setVariable("getChildDevices", { -> children.values() as List })
    b.setVariable("addChildDevice", { String ns, String t, String d, Map p -> def c = loadDriver(t, d, p.label, dev); children[d] = c; c })
    dev.script = shell.parse(new File(repo, files[type]).text, type.replaceAll(/[^A-Za-z0-9]/, "_"))
    dev.script.setBinding(b)
    dev.metaClass.children = children
    if (dev.script.metaClass.respondsTo(dev.script, 'installed')) dev.script.installed()
    return dev
}

// ---- API fixture shaped like the real account (fake IDs) ----
def fixture(Map o = [:]) {
    def hub1 = [id: "hub1", type: "bridge", name: "Garage Hub", mesh_id: "mesh1", is_connected: o.hub1Online != false, hardware_version: "BH1-0001", firmware_version: "0095", last_connected_at: "2026-10-29T15:22:34.524Z",
                status: [next_start_time: "1970-01-01T00:00:00-07:00", next_start_programs: []]]
    def ht25 = [id: "t25", type: "sprinkler_timer", name: "Front Hose", mesh_id: "mesh1", num_stations: 1, is_connected: o.t25Online != false, hardware_version: "HT25-0000",
                battery: [percent: 42, charging: false, mv: 2655], manual_preset_runtime_sec: 600,
                status: [run_mode: "auto", next_start_programs: ["a"], watering_status: null, "watering-status": null, watering_statuses: [], rain_delay: 0,
                         next_start_time: "2026-09-23T07:50:00-07:00", last_battery_alarm_at: "2026-08-09T17:24:49.104Z"],
                zones: [[station: 1, name: "Drip Beds", num_sprinklers: 10, sprinkler_type: "drip", flow_data: [flow_rate_gpm: 1.81886, cycle_volume_gal: 0.45]]]]
    def hub2 = [id: "hub2", type: "bridge", name: "Patio Hub", network_topology_id: "topo1", network_interface: "wifi", is_connected: true, hardware_version: "BH1G2-0002",
                status: [rain_delay: 0, run_mode: "auto", watering_status: null]]
    def xd = [id: "xd4", type: "sprinkler_timer", name: "Backyard XD", network_topology_id: "topo1", num_stations: 4, is_connected: true, hardware_version: "HT34-0001",
              smart_watering_enabled: true, battery: [percent: o.xdBattery ?: 45, mv: 2670],
              status: [run_mode: "auto", rssi: -69, phy: "le_coded_s8_125", provisioned: true, watering_status: o.xdWatering, "watering-status": null, watering_statuses: [], rain_delay: o.rainDelay ?: 0],
              zones: [[station: 1, name: "Shrubs", num_sprinklers: 1, sprinkler_type: "drip", device_id: "xd4"], [station: 2, name: "Beds", num_sprinklers: 6, sprinkler_type: "spray", device_id: "xd4"],
                      [station: 3, name: "Lawn", num_sprinklers: 8, sprinkler_type: "spray", device_id: "xd4"], [station: 4, name: "Spare", num_sprinklers: 0, device_id: "xd4"]]]
    return [hub1, ht25, hub2, xd, [id: "fs1", type: "flood_sensor", name: "Leak"]]
}
programs = [xd4: [[name: "Evens", program: "a", enabled: true, frequency: [type: "even"], start_times: ["08:45"], run_times: [[station: 1, run_time: 20], [station: 2, run_time: 20], [station: 3, run_time: 20]]]],
            t25: [[name: "Morning", program: "a", enabled: true, frequency: [type: "days", days: [1, 3, 5]], start_times: ["07:50"], run_times: [[station: 1, run_time: 10]]]]]
history = [xd4: [[date: "2026-09-27T00:00:00.000Z", irrigation: [
                     [station: 2, program_name: "Evens", program: "a", status: "complete", start_time: "2026-11-04T15:45:00.000Z", run_time: 20.0, water_volume_gal: null],
                     [station: 3, program_name: "Manual", program: "manual", status: "skipped", start_time: "2026-11-03T21:53:09.000Z", run_time: 0.33]]]],
           t25: [[irrigation: [[station: 1, program: "a", program_name: "Morning", status: "complete", start_time: "2026-11-04T14:50:00.000Z", run_time: 10, water_volume_gal: 3.14]]]]]
apiState = [devices: fixture()]

// ---- App ----
appDev = new Dev(deviceNetworkId: "app", label: "app")
ab = new Binding(); common(ab, appDev)
appChildren = [:]
[username: "u", password: "p", pollMinutes: "5", notificationDevices: [[deviceNotification: { m -> notices << m }]], eventsToNotify: ["battery", "connected", "cloud", "rain", "valve"],
 lowBatteryPercent: 20, logInfo: false, logDebug: false].each { k, v -> ab.setVariable(k, v) }
ab.setVariable("app", [id: 42])
ab.setVariable("getChildDevice", { String d -> appChildren[d] })
ab.setVariable("getChildDevices", { -> appChildren.values() as List })
ab.setVariable("deleteChildDevice", { String d -> appChildren.remove(d) })
ab.setVariable("httpPost", { Map p, Closure c -> c([status: 200, data: [orbit_api_key: "k", user_id: "u1"]]) })
ab.setVariable("httpGet", { Map p, Closure c ->
    def path = p.path
    def data = path == "devices" ? apiState.devices :
               path == "sprinkler_timer_programs" ? programs[p.query.device_id] :
               path.startsWith("watering_events/") ? history[path.split("/")[1]] : null
    c([status: 200, data: data])
})
appDev.script = shell.parse(new File(repo, "v2/apps/bhyve-v2.groovy").text, "BhyveV2App"); appDev.script.setBinding(ab)
ab.setVariable("addChildDevice", { String ns, String t, String d, Map p -> def c = loadDriver(t, d, p.label, appDev); appChildren[d] = c; c })
// notificationDevices*.deviceNotification(msg) on maps: make them objects
ab.setVariable("notificationDevices", [new Object() { def deviceNotification(m) { notices << m } }])

failures = 0
def check(String what, boolean ok) { println((ok ? "  pass  " : "  FAIL  ") + what); if (!ok) failures++ }
def A = { String dni -> appChildren["bhyve2-42-" + dni] }

println "1. First refresh builds the hierarchy"
appDev.script.login(); appDev.script.refresh()
def xd = A("xd4"), t25 = A("t25"), hub1 = A("hub1"), hub2 = A("hub2")
check "app children: 2 hubs + 2 timers, flood sensor skipped (got ${appChildren.keySet()})", xd && t25 && hub1 && hub2 && appChildren.size() == 4
check "XD has 4 zone children", xd.children.size() == 4
check "HT25 has 1 zone child", t25.children.size() == 1
def z = { Dev t, int st -> t.children[t.deviceNetworkId + ":" + st] }
check "zone label = timer – zone (${z(xd, 2).label})", z(xd, 2).label == "Backyard XD – Beds"
check "battery only on timers: XD=${xd.attrs.battery}, HT25=${t25.attrs.battery}", xd.attrs.battery == 45 && t25.attrs.battery == 42
check "no zone has a battery attribute", (xd.children.values() + t25.children.values()).every { !it.attrs.containsKey("battery") }
check "battery voltage 2.67 V (${xd.attrs.batteryVoltage}); charging only on HT25", xd.attrs.batteryVoltage == 2.67G && t25.attrs.batteryCharging == "false" && !xd.attrs.containsKey("batteryCharging")
check "hub links: XD->${xd.attrs.hubName}, HT25->${t25.attrs.hubName}", xd.attrs.hubName == "Patio Hub" && t25.attrs.hubName == "Garage Hub"
check "hub timers: ${hub1.attrs.timers} / ${hub2.attrs.timers}; generations ${hub1.attrs.generation}/${hub2.attrs.generation}", hub1.attrs.timers == "Front Hose" && hub2.attrs.timers == "Backyard XD" && hub1.attrs.generation == 1 && hub2.attrs.generation == 2
check "XD rssi -69 and phy", xd.attrs.rssi == -69 && xd.attrs.radioPhy == "le_coded_s8_125"
check "zone 4 unconfigured (${z(xd, 4).attrs.configured}, ${z(xd, 4).attrs.sprinklerType})", z(xd, 4).attrs.configured == "no"
check "zone programs: ${z(xd, 2).attrs.programs}", z(xd, 2).attrs.programs == "Evens (program A): 20 min every even day at 8:45 am"
check "zone 4 programs: ${z(xd, 4).attrs.programs}", z(xd, 4).attrs.programs == "none enabled"
check "HT25 next start: ${t25.attrs.nextStartTime}; flow ${z(t25, 1).attrs.flowRateGpm}", t25.attrs.nextStartTime.startsWith("Wed Sep 23, 7:50") && z(t25, 1).attrs.flowRateGpm == 1.82G
check "hub1 next_start 1970 sentinel not shown on timers; XD nextStart 'none' (${xd.attrs.nextStartTime})", xd.attrs.nextStartTime == "none"
check "history applied: XD zone 2 lastActivity = ${z(xd, 2).attrs.lastActivity}", z(xd, 2).attrs.lastActivity?.startsWith("Watered (20 min, Evens (program A)), ")
check "skipped history: ${z(xd, 3).attrs.lastActivity}", z(xd, 3).attrs.lastActivity?.startsWith("Skipped (Manual (manual)), ")
check "HT25 volume ${z(t25, 1).attrs.lastWateringVolume} and text ${z(t25, 1).attrs.lastActivity}", z(t25, 1).attrs.lastWateringVolume == 3.14 && z(t25, 1).attrs.lastActivity.contains("3.1 gal")
check "XD volume not set (null in API)", !z(xd, 2).attrs.containsKey("lastWateringVolume")
check "no notices on first refresh (${notices})", notices.isEmpty()

println "2. Live watering on XD zone 3"
appDev.script.handleEvent([event: "watering_in_progress_notification", device_id: "xd4", current_station: 3, run_time: 20, program: "a"])
check "zone 3 open/on", z(xd, 3).attrs.valve == "open" && z(xd, 3).attrs.switch == "on"
check "others closed", [1, 2, 4].every { z(xd, it).attrs.valve == "closed" }
check "timer activeZone=${xd.attrs.activeZone}, status=${xd.attrs.wateringStatus}", xd.attrs.activeZone == "Backyard XD – Lawn" && xd.attrs.wateringStatus == "watering"
check "lastActivity: ${z(xd, 3).attrs.lastActivity}", z(xd, 3).attrs.lastActivity.startsWith("Watering (20 min, program A), started ")
clock += 60_000L
appDev.script.refresh()
check "poll with no watering_status 1 min later keeps zone 3 open", z(xd, 3).attrs.valve == "open"
check "history refresh skipped within 30 min (lastActivity still live)", z(xd, 3).attrs.lastActivity.startsWith("Watering")
clock += 17 * 60_000L
appDev.script.handleEvent([event: "watering_complete", device_id: "xd4"])
check "zone 3 closed after complete", z(xd, 3).attrs.valve == "closed" && z(xd, 3).attrs.switch == "off"
check "lastActivity: ${z(xd, 3).attrs.lastActivity}", z(xd, 3).attrs.lastActivity.startsWith("Watered (18 min, program A), ended ")
check "timer idle", xd.attrs.wateringStatus == "idle" && xd.attrs.activeZone == "none"
check "history refresh scheduled (${runIns})", runIns.contains("refreshHistoryHandler")
check "valve notices sent: ${notices}", notices.size() == 2

println "3. Poll-detected watering (no websocket) and stale history"
apiState.devices = fixture(xdWatering: [current_station: 1])
appDev.script.refresh()
check "zone 1 opened from poll", z(xd, 1).attrs.valve == "open"
apiState.devices = fixture()
clock += 5 * 60_000L
appDev.script.refresh()
check "zone 1 closed by later poll (no expected stop)", z(xd, 1).attrs.valve == "closed"

println "4. Battery, health and rain delay"
notices.clear()
apiState.devices = fixture(xdBattery: 18, rainDelay: 24, hub1Online: false, t25Online: false)
appDev.script.refresh(); appDev.script.refresh()
check "one low-battery notice for XD after two polls (${notices.findAll { it.contains('battery') }})", notices.count { it.contains("battery is at 18%") } == 1
check "HT25 connectivity = ${t25.attrs.connectivity}", t25.attrs.connectivity == "hub offline" && t25.attrs.healthStatus == "offline"
check "hub1 offline", hub1.attrs.healthStatus == "offline"
check "rain delay notice", notices.any { it.contains("rain delay 24 h") }
appDev.script.handleEvent([event: "low_battery", device_id: "xd4"])
check "low_battery event doesn't duplicate the notice", notices.count { it.contains("battery") } == 1
appDev.script.handleEvent([event: "device_connected", device_id: "hub1"])
check "hub1 back online via event", hub1.attrs.healthStatus == "online"

println "5. Commands and misc events"
sent = []
appDev.script.ensureCloudDevice(); def cloud = A("cloud")
check "cloud device created", cloud != null
cloud.script.binding.setVariable("interfaces", [webSocket: [sendMessage: { m -> sent << m }, connect: { u -> }, close: { -> }]])
cloud.script.webSocketStatus("status: open")
z(xd, 2).script.open(7)
check "open(7) sent change_mode for station 2: ${sent[-1]}", sent[-1].contains('"station":2') && sent[-1].contains('"run_time":7') && sent[-1].contains('"device_id":"xd4"')
z(t25, 1).script.on()
check "on() uses preset 10 min: ${sent[-1]}", sent[-1].contains('"run_time":10') && sent[-1].contains('"device_id":"t25"')
xd.script.setRainDelay(0)
check "setRainDelay: ${sent[-1]}", sent[-1].contains('"event":"rain_delay"') && sent[-1].contains('"delay":0')
cloud.script.parse('{"event":"set_manual_preset_runtime","device_id":"xd4","seconds":300}')
check "preset via websocket -> 5 min on timer and zones", xd.attrs.presetRuntime == 5 && z(xd, 3).attrs.presetRuntime == 5
cloud.script.parse('{"event":"flow_sensor_state_changed","device_id":"t25","station":1,"flow_rate_gpm":2.5}')
check "flow event -> zone", z(t25, 1).attrs.flowRateGpm == 2.50G
cloud.script.parse('not json')
cloud.script.parse('{"event":"something_new","device_id":"xd4"}')
cloud.script.parse('{"event":"watering_in_progress_notification","device_id":"xd4"}')
check "malformed/unknown events don't throw", true

println "6. Zone rename follows B-hyve unless user renamed it"
def d2 = fixture(); d2[3].zones[0].name = "Roses"; d2[3].zones[1].name = "Veg"
z(xd, 2).setLabel("My custom name")
apiState.devices = d2; appDev.script.refresh()
check "auto-labelled zone renamed: ${z(xd, 1).label}", z(xd, 1).label == "Backyard XD – Roses"
check "user-renamed zone kept: ${z(xd, 2).label}", z(xd, 2).label == "My custom name"

println "7. Stale devices"
apiState.devices = fixture().findAll { it.id != "t25" }
appDev.script.refresh()
check "HT25 reported stale", appDev.script.staleChildren()*.deviceNetworkId == ["bhyve2-42-t25"]

println(failures ? "\n${failures} FAILURE(S); warnings: ${warnings}" : "\nALL PASSED; warnings: ${warnings}")
System.exit(failures ? 1 : 0)
