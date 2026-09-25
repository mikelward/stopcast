#!/usr/bin/env python3
"""Build stopcast's bundled station index from TfL (SPEC *Finding stops → Find a station*).

Writes the JSON the app reads as `assets/stations/station_index.json`: every tube, DLR,
Overground, Elizabeth line, tram, National Rail and pier station in and around London, plus
the interchanges ("hubs") that group them, each with its TfL id, name as TfL spells it (the
app cleans it), modes, and hub; a station also carries its position and its lines by mode (tube
lines, National Rail services, Overground lines, …), so the near-me list can name the nearest
station of a line it doesn't reach ("From …"). Bus stops are left to TfL's live search: there are ~20,000.

Built from TfL's per-mode stop lists (`/StopPoint/Mode/{mode}`), one mode at a time, and its
rail-station listing for National Rail. Every listing is required: a failed or empty one stops
the build, so the committed index stays rather than a partial one replacing it.
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


def fetch(path, params=None, retry_delays=RETRY_DELAYS):
    """GET a TfL path as JSON, retrying a timeout or a 5xx (TfL's larger lists sometimes 504)."""
    query = dict(params or {})
    key = os.environ.get("TFL_APP_KEY")
    if key:
        query["app_key"] = key
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    request = urllib.request.Request(url, headers={"User-Agent": "stopcast-station-index"})
    for attempt, delay in enumerate((0,) + tuple(retry_delays)):
        if delay:
            print(f"retrying {path} in {delay}s", file=sys.stderr)
            time.sleep(delay)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == len(retry_delays):
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == len(retry_delays):
                raise


def station_points(points):
    """Every stop point in [points] and their nested children whose type is a station's — a
    per-mode listing nests a hub's stations and a station's platforms, and only the stations
    carry departures here. Deduplicated by id, merging a station listed under several modes (a DLR
    listing's Stratford lacks the tube lines its tube listing carries); a station's own children
    aren't searched."""
    found = {}

    def walk(point):
        if point.get("stopType") in STOP_TYPES:
            sid = stop_id(point)
            if sid in found:
                found[sid] = merged(found[sid], point)
            else:
                found[sid] = point
            return
        for child in point.get("children") or []:
            walk(child)

    for point in points:
        walk(point)
    return list(found.values())


def merged(first, other):
    """[first] with [other]'s modes and line groups added: two listings of one station."""
    groups = {}
    for group in (first.get("lineModeGroups") or []) + (other.get("lineModeGroups") or []):
        mode = group.get("modeName")
        groups.setdefault(mode, set()).update(group.get("lineIdentifier") or [])
    return {
        **first,
        "modes": sorted(set(first.get("modes") or []) | set(other.get("modes") or [])),
        "lineModeGroups": [{"modeName": m, "lineIdentifier": sorted(ids)} for m, ids in sorted(groups.items())],
    }


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
        lines = lines_by_mode(stop)
        stations[sid] = {
            "id": sid, "name": name, "modes": modes, **({"hub": hub} if hub else {}),
            # Five decimals is about a meter: plenty to measure how far a station is.
            "lat": round(stop["lat"], 5), "lon": round(stop["lon"], 5),
            # "modeLines", not "lines": an earlier build wrote "lines" as a tube-only list.
            **({"modeLines": lines} if lines else {}),
        }
        if hub:
            hub_modes.setdefault(hub, set()).update(modes)
    for hub in hubs:
        hid = stop_id(hub)
        name = (hub.get("commonName") or "").strip()
        if hid in hub_modes and name:
            stations[hid] = {"id": hid, "name": name, "modes": sorted(hub_modes[hid])}
    # Sorted by id so a refresh's diff shows only what TfL changed.
    return {"version": FORMAT_VERSION, "stations": [stations[k] for k in sorted(stations)]}


def lines_by_mode(stop):
    """The lines serving [stop] per mode stopcast indexes, from TfL's lineModeGroups: a map of
    mode to sorted line ids, holding only modes with lines (empty for none). Buses are left out."""
    lines = {}
    for group in stop.get("lineModeGroups") or []:
        mode = group.get("modeName")
        if mode in MODES and group.get("lineIdentifier"):
            lines.setdefault(mode, set()).update(group["lineIdentifier"])
    return {mode: sorted(ids) for mode, ids in sorted(lines.items())}


# The modes whose lines the app offers farther stations for (FartherStations.MODES).
LINE_MODES = {"tube", "overground", "elizabeth-line", "national-rail", "dlr", "tram"}


def modes_without_lines(index):
    """The line-bearing modes the index has stations of but no station carries lines for: a
    listing that came back without its lineModeGroups. Sorted; empty when every mode has lines."""
    with_stations = {m for s in index["stations"] if not s["id"].upper().startswith("HUB") for m in s["modes"]}
    with_lines = {m for s in index["stations"] for m, ids in s.get("modeLines", {}).items() if ids}
    return sorted((with_stations & LINE_MODES) - with_lines)


def fetch_hubs(hub_ids):
    hubs = []
    ids = sorted(hub_ids)
    for start in range(0, len(ids), HUB_BATCH):
        batch = ids[start:start + HUB_BATCH]
        hubs.extend(checked_hub_batch(batch, fetch("/StopPoint/" + ",".join(batch))))
    return hubs


def checked_hub_batch(batch, found):
    """A hub batch's answer, refusing one missing a requested hub: its member stations would keep
    a reference to an interchange the index lacks, so a search shows them instead of it."""
    # One id answers with an object, several with a list.
    hubs = found if isinstance(found, list) else [found] if found else []
    missing = sorted(set(batch) - {stop_id(hub) for hub in hubs})
    if missing:
        sys.exit(f"TfL returned no hub for {', '.join(missing)}; refusing to write a partial index")
    return hubs


# The share of a listing's stations of its mode that must carry that mode's lines: nearly all do
# (a closed or brand-new station may not), and a listing stripped of its lineModeGroups has none.
MIN_LINED_SHARE = 0.9


def required_points(label, points, line_mode=None):
    """A required listing, refusing one with no stations: an empty answer (TfL's 200 with no
    stops) would otherwise drop the whole mode while the other modes keep the total up. For a
    [line_mode], also refusing one whose stations of that mode mostly lack its lines, checked on
    this listing alone so another mode's cross-listed station can't vouch for it."""
    points = points or []
    stations = station_points(points)
    if not stations:
        sys.exit(f"TfL listed no {label} stations; refusing to write an index without them")
    if line_mode in LINE_MODES:
        of_mode = [s for s in stations if line_mode in (s.get("modes") or [])]
        lined = [s for s in of_mode if lines_by_mode(s).get(line_mode)]
        if of_mode and len(lined) < MIN_LINED_SHARE * len(of_mode):
            sys.exit(f"TfL listed {len(of_mode)} {label} stations but only {len(lined)} with their lines; "
                     "refusing to write the index")
    return points


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="where to write station_index.json")
    args = parser.parse_args(argv)
    # One request per mode: an all-types listing spans the whole country and times out, while each
    # London mode's listing is TfL's own network and a fraction of the size. Every listing is
    # required: a failure stops the build and the committed index stays, rather than a partial
    # one replacing it.
    points = []
    for mode in sorted(MODES - {"national-rail"}):
        points.extend(required_points(mode, (fetch(f"/StopPoint/Mode/{mode}") or {}).get("stopPoints"), line_mode=mode))
    # National Rail's mode listing is nationwide and outlasts TfL's gateway (504); its stations
    # come from the rail-station type listing instead, which answers in time.
    points.extend(required_points("National Rail", fetch("/StopPoint/Type/NaptanRailStation"), line_mode="national-rail"))
    stops = station_points(points)
    # Only the hubs of stations the index can keep: a far-off rail station's hub isn't needed.
    hub_ids = {s.get("hubNaptanCode") for s in stops if s.get("hubNaptanCode") and in_london(s)}
    index = build_index(stops, fetch_hubs(hub_ids))
    missing = modes_without_lines(index)
    if missing:
        # TfL answered without a mode's line groups: the index would name no nearest station of
        # that mode's lines, silently, so keep the committed index instead.
        sys.exit(f"no {', '.join(missing)} station carries its lines; refusing to write the index")
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
