# Local channels

Add up to eight U.S. cities or states in Settings → Local channels, then choose Local at the top of the guide. Use Add region, Save changes, keyboard Done/Enter, or a suggestion to commit an entry; typing alone keeps a draft. City/state pairs such as Seattle, WA disambiguate place names. Matching combines the saved regions across enabled playlists, keeps quality variants, and respects visible categories.

The lookup runs offline using explicit provider location labels and a small call-sign/community reference derived from the FCC's [Post-Auction Coverage Baseline 2.0](https://opendata.fcc.gov/Wireless/Post-Auction-Coverage-Baseline-2-0/42c8-wnh4). This is a historical reference, not a guarantee of current station identity, market coverage, or stream availability. Stations licensed in nearby communities may require selecting the state. Only channels already supplied by your playlist are shown.

Public-domain FCC records and public factual state names from the [IPTV-org API](https://github.com/iptv-org/api) were retrieved on 2026-10-27. Refresh the bundled reference with `python3 scripts/local_stations.py`. Spare does not send a location, playlist, or credentials to either service.
