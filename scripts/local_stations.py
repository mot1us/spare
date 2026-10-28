"""Refresh the small offline US call-sign/community reference; no stream URLs."""
import json
import re
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parents[1]
station_url = 'https://opendata.fcc.gov/resource/42c8-wnh4.json?$select=call,city,state&$limit=10000'
state_url = 'https://iptv-org.github.io/api/subdivisions.json'

def read(url):
    with urlopen(url, timeout=30) as response:
        return json.loads(response.read(4 * 1024 * 1024))

def generate(stations, subdivisions):
    names = {s['code'].removeprefix('US-'): s['name'] for s in subdivisions if s['country'] == 'US'}
    rows = set()
    for station in stations:
        call = station.get('call', '').split('-')[0].upper()
        state = station.get('state', '').upper()
        if not re.fullmatch(r'[KW][A-Z0-9]{2,6}', call) or state not in names:
            continue
        for city in station.get('city', '').split(','):
            city = city.strip().strip('.').title()
            if city and city.lower() not in ('etc', 'et al'):
                rows.add('\t'.join([call, city, state, names[state]]))
    destination = root / 'app/src/main/assets/local-stations.tsv'
    destination.write_text('\n'.join(sorted(rows)) + '\n')
    print(f'{len(rows)} offline station/community records, {destination.stat().st_size:,} bytes')

if __name__ == '__main__':
    generate(read(station_url), read(state_url))
