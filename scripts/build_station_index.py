#!/usr/bin/env python3
"""Build stopcast's bundled station index from TfL (SPEC *Finding stops → Find a station*).

Writes the JSON the app reads as `assets/stations/station_index.json`: every tube, DLR,
Overground, Elizabeth line, tram, National Rail and pier station in and around London, plus
the interchanges ("hubs") that group them, each with its TfL id, name as TfL spells it (the
app cleans it), modes, and hub. Bus stops are left to TfL's live search: there are ~20,000.

Built from TfL's per-mode stop lists (`/StopPoint/Mode/{mode}`), one mode at a time.
Run by the `station-index` workflow, which commits the output; the sandboxed dev environment
can't reach TfL. Keyless by default (a handful of requests); set TFL_APP_KEY to use a key.
Public transport data only — nothing about any user.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.tfl.gov.uk"
STOP_TYPES = ["NaptanMetroStation", "NaptanRailStation", "NaptanFerryPort"]
# The modes stopcast shows departures for, bus aside (bus stops come from the live search).
MODES = {"tube", "dlr", "overground", "elizabeth-line", "national-rail", "tram", "river-bus", "cable-car"}
# Greater London with a margin, so a TfL stop just past the boundary (Watford Junction, Epping)
# stays, while TfL's National Rail stations across the country don't bloat the index.
LAT_RANGE = (51.25, 51.75)
LON_RANGE = (-0.65, 0.40)
HUB_BATCH = 10
FORMAT_VERSION = 1


RETRY_DELAYS = (5, 15, 45)


def fetch(path, params=None):
    """GET a TfL path as JSON, retrying a timeout or a 5xx (TfL's larger lists sometimes 504)."""
    query = dict(params or {})
    key = os.environ.get("TFL_APP_KEY")
    if key:
        query["app_key"] = key
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    request = urllib.request.Request(url, headers={"User-Agent": "stopcast-station-index"})
    for attempt, delay in enumerate((0,) + RETRY_DELAYS):
        if delay:
            print(f"retrying {path} in {delay}s", file=sys.stderr)
            time.sleep(delay)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == len(RETRY_DELAYS):
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == len(RETRY_DELAYS):
                raise


def station_points(points):
    """Every stop point in [points] and their nested children whose type is a station's — a
    per-mode listing nests a hub's stations and a station's platforms, and only the stations
    carry departures here. Deduplicated by id; a station's own children aren't searched."""
    found = {}

    def walk(point):
        if point.get("stopType") in STOP_TYPES:
            found.setdefault(stop_id(point), point)
            return
        for child in point.get("children") or []:
            walk(child)

    for point in points:
        walk(point)
    return list(found.values())


def in_london(stop):
    lat, lon = stop.get("lat"), stop.get("lon")
    if lat is None or lon is None:
        return False
    return LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]


def stop_id(stop):
    return stop.get("id") or stop.get("naptanId") or ""


def build_index(stops, hubs):
    """The index document from TfL's station list and its hubs' own records (pure, for tests)."""
    stations = {}
    hub_modes = {}
    for stop in stops:
        sid = stop_id(stop)
        name = (stop.get("commonName") or "").strip()
        modes = sorted(set(stop.get("modes") or []) & MODES)
        if not sid or not name or not modes or stop.get("stopType") not in STOP_TYPES or not in_london(stop):
            continue
        hub = stop.get("hubNaptanCode") or ""
        stations[sid] = {"id": sid, "name": name, "modes": modes, **({"hub": hub} if hub else {})}
        if hub:
            hub_modes.setdefault(hub, set()).update(modes)
    for hub in hubs:
        hid = stop_id(hub)
        name = (hub.get("commonName") or "").strip()
        if hid in hub_modes and name:
            stations[hid] = {"id": hid, "name": name, "modes": sorted(hub_modes[hid])}
    # Sorted by id so a refresh's diff shows only what TfL changed.
    return {"version": FORMAT_VERSION, "stations": [stations[k] for k in sorted(stations)]}


def fetch_hubs(hub_ids):
    hubs = []
    ids = sorted(hub_ids)
    for start in range(0, len(ids), HUB_BATCH):
        batch = ids[start:start + HUB_BATCH]
        # One id answers with an object, several with a list.
        found = fetch("/StopPoint/" + ",".join(batch))
        hubs.extend(found if isinstance(found, list) else [found])
    return hubs


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="where to write station_index.json")
    args = parser.parse_args(argv)
    # One request per mode: the all-types listing spans the whole country's rail stations and
    # times out, while each mode's listing is TfL's own area and a fraction of the size.
    points = []
    for mode in sorted(MODES):
        points.extend(fetch(f"/StopPoint/Mode/{mode}").get("stopPoints", []))
    stops = station_points(points)
    hub_ids = {s.get("hubNaptanCode") for s in stops if s.get("hubNaptanCode")}
    index = build_index(stops, fetch_hubs(hub_ids))
    if len(index["stations"]) < 200:
        # A near-empty result means TfL answered oddly; keep the committed index rather than
        # shipping a list that can't find most stations.
        sys.exit(f"only {len(index['stations'])} stations; refusing to write a partial index")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as out:
        json.dump(index, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"wrote {len(index['stations'])} stations to {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
