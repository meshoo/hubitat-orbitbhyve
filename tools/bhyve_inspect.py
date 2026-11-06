#!/usr/bin/env python3
"""B-hyve API Inspector (command-line twin of apps/tools/bhyve-api-inspector.groovy).

Fetches devices, meshes, timer programs and recent watering events from the Orbit B-hyve cloud API
and writes a REDACTED JSON dump. Raw responses are kept in memory only.

Credentials come from the environment, never from arguments or files:
    BHYVE_EMAIL, BHYVE_PASSWORD
Optional: BHYVE_API_URL (default https://api.orbitbhyve.com/v1/), BHYVE_KEEP_NAMES=1.

Usage:  python3 -I tools/bhyve_inspect.py OUTPUT.json
Licensed under the Apache License, Version 2.0 (see LICENSE).
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

API_URL = os.environ.get("BHYVE_API_URL", "https://api.orbitbhyve.com/v1/")
MAX_WATERING_EVENTS = 3
APP_ID_HEADER = {"orbit-app-id": "Orbit Support Dashboard"}

# Keep these rules identical to the Groovy inspector.
ID_KEYS = {"id", "mesh_id", "device_id", "user_id", "program_id", "station_id",
           "weather_station_id", "weather_forecast_location_id", "et_id", "landscape_id"}
SENSITIVE = re.compile(
    r"(?i).*(mac|token|key|secret|credential|auth|password|serial|email|address|location|coordinat|latitude|longitude|"
    r"gateway|reference|image|photo|postal|^zip|_zip|^city$|_city$|country|phone|ssid|^ip$|_ip$|^ip_|"
    r"timezone_id|timezone_name|^lat$|^lng$|^lon$).*")


def is_sensitive(key):
    return SENSITIVE.fullmatch(key) is not None


def is_id_key(key):
    k = key.lower()
    return k in ID_KEYS or k.endswith("_ids") or (k.endswith("_id") and not is_sensitive(k))


SECRET_VALUE = re.compile(r"[A-Za-z0-9+/=]{16,}")


def looks_secret(value):
    """Catch keys/tokens whatever their field name: long base64/hex-like strings containing a digit."""
    return SECRET_VALUE.fullmatch(value) is not None and any(c.isdigit() for c in value)


def is_name_key(key):
    k = key.lower()
    return k == "name" or k.endswith("_name") or k in ("nickname", "notes", "description")


class Redactor:
    def __init__(self, keep_names):
        self.ids, self.names, self.keep_names = {}, {}, keep_names

    def id_token(self, v):
        return self.ids.setdefault(str(v), "ID-%d" % (len(self.ids) + 1))

    def name_token(self, v):
        return self.names.setdefault(v, "Name-%d" % (len(self.names) + 1))

    def redact(self, value, key=None):
        if key is not None and is_id_key(key) and value is not None and not isinstance(value, (dict, list)):
            return self.id_token(value)
        if key is not None and is_sensitive(key):
            return None if value is None else "<redacted>"
        if key is not None and not self.keep_names and is_name_key(key) and isinstance(value, str):
            return self.name_token(value)
        if isinstance(value, dict):
            return {k: self.redact(v, str(k)) for k, v in value.items()}
        if isinstance(value, list):
            return [self.redact(i, key if key is not None and is_id_key(key) else None) for i in value]
        if isinstance(value, str) and looks_secret(value):
            return "<redacted-value>"
        return value

    def redact_text(self, text):
        for raw, token in self.ids.items():
            text = text.replace(raw, token)
        return text


def request(method, path, api_key=None, query=None, body=None):
    url = urllib.parse.urljoin(API_URL, path)
    if query:
        url += "?" + urllib.parse.urlencode(query)
    headers = dict(APP_ID_HEADER, Accept="application/json")
    if api_key:
        headers["orbit-api-key"] = api_key
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return "HTTP %d" % resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return "HTTP %d (%s)" % (e.code, e.reason), None
    except Exception as e:  # noqa: BLE001 - report, never crash mid-dump
        return "error: %s" % type(e).__name__, None


def summarize(devices):
    out = []
    for d in devices:
        if not isinstance(d, dict):
            continue
        zones = d.get("zones") if isinstance(d.get("zones"), list) else []
        status = d.get("status") if isinstance(d.get("status"), dict) else None
        battery = d.get("battery")
        out.append({
            "type": d.get("type"),
            "hardware_version": d.get("hardware_version"),
            "firmware_version": d.get("firmware_version"),
            "num_stations": d.get("num_stations"),
            "zone_count": len(zones),
            "zone_stations": [z.get("station") if isinstance(z, dict) else None for z in zones],
            "has_mesh": d.get("mesh_id") is not None,
            "has_battery": battery is not None,
            "battery_fields": sorted(battery.keys()) if isinstance(battery, dict) else None,
            "is_connected": d.get("is_connected"),
            "top_level_fields": sorted(d.keys()),
            "status_fields": sorted(status.keys()) if status else None,
            "watering_status": status.get("watering_status") if status else None,
        })
    return out


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    email, password = os.environ.get("BHYVE_EMAIL"), os.environ.get("BHYVE_PASSWORD")
    if not email or not password:
        print("Set BHYVE_EMAIL and BHYVE_PASSWORD in the environment.", file=sys.stderr)
        return 2

    status, session = request("POST", "session", body={"session": {"email": email, "password": password}})
    if not isinstance(session, dict) or not session.get("orbit_api_key"):
        print("Login failed: %s" % status, file=sys.stderr)
        return 1
    key, user_id = session["orbit_api_key"], session.get("user_id")

    report = {"generated_by": "B-hyve API Inspector (cli)",
              "notes": "IDs are replaced with stable tokens; sensitive fields are removed.",
              "requests": {}}
    status, devices = request("GET", "devices", key, {"user_id": user_id})
    report["requests"]["devices"] = status
    report["devices"] = devices
    device_list = devices if isinstance(devices, list) else []

    mesh_ids = []
    for d in device_list:
        if isinstance(d, dict) and d.get("mesh_id") and d["mesh_id"] not in mesh_ids:
            mesh_ids.append(d["mesh_id"])
    report["meshes"] = []
    for m in mesh_ids:
        status, data = request("GET", "meshes/%s" % m, key)
        report["requests"]["meshes/%s" % m] = status
        report["meshes"].append({"mesh_id": m, "response": data})

    topo_ids = []
    for d in device_list:
        if isinstance(d, dict) and d.get("network_topology_id") and d["network_topology_id"] not in topo_ids:
            topo_ids.append(d["network_topology_id"])
    report["network_topologies"] = []
    for t in topo_ids:
        status, data = request("GET", "network_topologies/%s" % t, key)
        report["requests"]["network_topologies/%s" % t] = status
        report["network_topologies"].append({"network_topology_id": t, "response": data})

    report["programs"], report["watering_events"] = [], []
    for d in device_list:
        if not (isinstance(d, dict) and d.get("type") == "sprinkler_timer" and d.get("id")):
            continue
        status, data = request("GET", "sprinkler_timer_programs", key, {"device_id": d["id"]})
        report["requests"]["sprinkler_timer_programs?device_id=%s" % d["id"]] = status
        report["programs"].append({"device_id": d["id"], "response": data})
        status, data = request("GET", "watering_events/%s" % d["id"], key)
        report["requests"]["watering_events/%s" % d["id"]] = status
        report["watering_events"].append({"device_id": d["id"],
                                          "response": data[:MAX_WATERING_EVENTS] if isinstance(data, list) else data})

    report["summary"] = summarize(device_list)

    r = Redactor(keep_names=os.environ.get("BHYVE_KEEP_NAMES") == "1")
    redacted = r.redact(report)
    redacted["requests"] = {r.redact_text(k): v for k, v in redacted["requests"].items()}
    text = json.dumps(redacted, indent=2)
    # Belt and braces: no raw ID, the e-mail or the API key may survive.
    for secret in list(r.ids.keys()) + [email, key, str(user_id)]:
        if secret and len(secret) > 3 and secret in text:
            print("Refusing to write output: a raw identifier survived redaction.", file=sys.stderr)
            return 1
    with open(argv[1], "w") as f:
        f.write(text + "\n")
    print("Wrote %s: %d devices, %d meshes, %d characters." % (argv[1], len(device_list), len(mesh_ids), len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
