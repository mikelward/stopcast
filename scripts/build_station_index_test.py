#!/usr/bin/env python3
"""Tests for build_station_index.build_index, against a hand-written fixture (no network)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_station_index import build_index, station_points  # noqa: E402


def stop(sid, name, modes, stop_type="NaptanMetroStation", hub="", lat=51.5, lon=-0.12):
    return {"id": sid, "commonName": name, "modes": modes, "stopType": stop_type,
            "hubNaptanCode": hub, "lat": lat, "lon": lon}


class BuildIndexTest(unittest.TestCase):
    def test_stations_carry_their_modes_and_hub_and_hubs_join_the_index(self):
        index = build_index(
            [
                stop("940GZZLUEXA", "Example Underground Station", ["tube", "bus"], hub="HUBEXA"),
                stop("910GEXAMPLE", "Example Rail Station", ["national-rail"], "NaptanRailStation", hub="HUBEXA"),
            ],
            [{"id": "HUBEXA", "commonName": "Example"}],
        )
        self.assertEqual(1, index["version"])
        by_id = {s["id"]: s for s in index["stations"]}
        self.assertEqual(["910GEXAMPLE", "940GZZLUEXA", "HUBEXA"], sorted(by_id))
        self.assertEqual(["tube"], by_id["940GZZLUEXA"]["modes"], "bus is left to the live search")
        self.assertEqual("HUBEXA", by_id["940GZZLUEXA"]["hub"])
        self.assertEqual(["national-rail", "tube"], by_id["HUBEXA"]["modes"])
        self.assertNotIn("hub", by_id["HUBEXA"])

    def test_far_away_platform_and_modeless_stops_are_left_out(self):
        index = build_index(
            [
                stop("910GFAR", "Far Away Rail Station", ["national-rail"], "NaptanRailStation", lat=53.5, lon=-2.2),
                stop("9400ZZLUEXA1", "Example Platform", ["tube"], "NaptanMetroPlatform"),
                stop("940GZZNOMODE", "No Mode Station", ["bus"]),
                stop("", "No Id Station", ["tube"]),
            ],
            [],
        )
        self.assertEqual([], index["stations"])

    def test_a_hub_with_no_indexed_member_is_left_out(self):
        index = build_index([], [{"id": "HUBEXA", "commonName": "Example"}])
        self.assertEqual([], index["stations"])


    def test_station_points_finds_stations_nested_under_a_hub_but_not_their_platforms(self):
        hub = {
            "id": "HUBEXA", "stopType": "TransportInterchange",
            "children": [
                dict(stop("940GZZLUEXA", "Example", ["tube"]),
                     children=[stop("9400ZZLUEXA1", "Platform", ["tube"], "NaptanMetroPlatform")]),
            ],
        }
        top = stop("910GEXAMPLE", "Example Rail", ["national-rail"], "NaptanRailStation")
        found = station_points([hub, top, top])
        self.assertEqual(["940GZZLUEXA", "910GEXAMPLE"], [p["id"] for p in found])

if __name__ == "__main__":
    unittest.main()
