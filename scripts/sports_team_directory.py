#!/usr/bin/env python3
"""Capture ESPN's public team lists for every Sports league, for the team-name audit test.

Developer tool only: the app never makes these requests. Usage:
  python3 scripts/sports_team_directory.py OUT.json
  SPARE_TEAM_DIRECTORY=OUT.json ./gradlew :app:testDebugUnitTest --tests '*SportsTeamDirectoryAuditTest*'
"""
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen

LEAGUES = {
    "NFL": "football/nfl", "MLB": "baseball/mlb", "NBA": "basketball/nba", "NHL": "hockey/nhl",
    "EPL": "soccer/eng.1", "MLS": "soccer/usa.1", "UCL": "soccer/uefa.champions",
    "NCAAF": "football/college-football", "NCAAM": "basketball/mens-college-basketball",
    "NCAAW": "basketball/womens-college-basketball", "NCAABSB": "baseball/college-baseball",
}
LIMIT = 8 * 1024 * 1024


def fetch(item):
    league, path = item
    url = f"https://site.api.espn.com/apis/site/v2/sports/{path}/teams?limit=1000"
    with urlopen(Request(url, headers={"User-Agent": "Spare-team-audit"}), timeout=20) as response:
        raw = response.read(LIMIT + 1)
    assert len(raw) <= LIMIT, f"{league}: response too large"
    teams = [entry["team"] for entry in json.loads(raw)["sports"][0]["leagues"][0]["teams"]]
    keep = ("id", "abbreviation", "displayName", "shortDisplayName", "name", "location", "nickname")
    return league, [{key: str(team.get(key, "")) for key in keep} for team in teams]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with ThreadPoolExecutor(max_workers=4) as pool:
        directory = dict(pool.map(fetch, LEAGUES.items()))
    Path(sys.argv[1]).write_text(json.dumps(directory, indent=1, sort_keys=True))
    print(", ".join(f"{league} {len(teams)}" for league, teams in directory.items()))


if __name__ == "__main__":
    main()
