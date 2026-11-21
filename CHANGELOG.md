# Changelog

## 0.52.2 — 2026-11-20

- New setting: Playback › Surround sound. "Sent on as is" (the default, as before) sends Dolby and DTS sound undecoded for the TV or a receiver to decode, when Android says they can. "Decoded on this box" has the box decode it into ordinary sound every TV plays, for a TV or soundbar that stays silent. On the owner's onn, every version of a movie from one playlist played without sound, while the same movie from another playlist (ordinary sound) played with it. Changing the setting restarts the playing stream so it applies at once.
- A stream whose sound this box cannot play at all (no decoder and nothing to pass it to) now says so on screen ("No sound: this copy has DTS audio, which this box can't play. Try another version.") instead of playing silently.
- The performance report names the sound's format (for example "Dolby Digital Plus") and says whether it was decoded on this box or sent on undecoded, and whether it was tunneled.

## 0.52.1 — 2026-11-20

- Search shows a movie once. On the owner's onn, 0.52.0 showed two results for a moment and then four identical posters again: copies already part-watched came back from watch history ungrouped, and the same movie on a second playlist had its own card. Now every copy with the same cleaned-up title and year shares one card, led by the active playlist, under the title without version tags.
- A movie's details page also lists its copy on other enabled playlists (for example "Standard · Second playlist"), and that copy plays from its own playlist.
- Back from a search result returns to the search results, on that result. A live channel picked in Search returns there from full screen (it went to the guide's All channels on that channel). A movie returns to its details page and then the results, and a series to its episodes and then the results. An episode played straight from Search returns to the results. Voice does not start again.
- When Spare groups an older saved Movies list into versions, the performance report's recent actions say so and how long it took.

## 0.52.0 — 2026-11-20

- A movie that a provider lists more than once, once per version (for example "4KDV: A Film (2011)", "4K: A Film (2011)" and "A Film (2011)"), shows once in Library rows, grids and search, under its name without the version tags. Its details page lists each version (4K Dolby Vision, 4K, 1080p, HD, Standard). Play starts the best one for this TV: Dolby Vision only where the box and the TV both report it, otherwise the plain 4K copy. A version already part-watched is the one Play resumes. Every version stays one press away. Versions are grouped only when the cleaned-up title and year match, or the provider's movie ID and year match, so remakes and different films never merge. Title counts count each movie once. Series are not grouped.
- Version tags in front of a title ("4KDV:", "4K:") and a year after a dash ("A Film - 2011") are recognised when matching titles.
- Search results look like Library: posters only, with the title under the cursor and what it is (Movie, Series or Episode, year, playlist) above the grid. Before, each poster had a "MOVIE" badge and two lines of text squeezed under it, and the playlist line was cut off.
- A Movies list saved by an earlier version is grouped into versions in the background the first time Library opens it, without delaying the rows.

## 0.51.47 — 2026-11-20

- When a background update of a Movies or Series list finishes while its rows are open, the line under the heading now just gives the new count, for example "129136 titles · Saved today". It no longer says "Updated just now · New titles show when you reopen Library". On the owner's onn, 0.51.46 fetched the rest of the list in the background, from 100,000 titles to 129,136.

## 0.51.46 — 2026-11-20

- A Movies or Series list saved by an older version at its lower title limit downloads again at once in the background, instead of waiting until the saved copy is 6 hours old. Spare now notes when a list stopped at the limit. Lists of exactly 100,000 titles saved by 0.51.45 and earlier count as stopped there. On the owner's onn, 0.51.45 kept showing "100000 titles · Saved today" because the copy saved earlier that day still counted as current. While the rest arrives, the rows say "Getting the rest of your titles…".
- Library reads ahead while the remote rests. The next two rows below the screen read their titles and first posters, and so do the next two lines of a category grid. A row is usually ready when the cursor reaches it, instead of loading its posters then. Read-ahead waits until the posters on screen have started loading, and never runs while a key is held.

## 0.51.45 — 2026-11-20

- Library gets a catalog ready calmly. While it saves a Movies or Series list for the first time (or opens a saved one that takes a moment), it shows three steps that tick off in turn (Categories, Titles, Rows) above placeholder rows with a soft shimmer, and says that later visits open at once. The rows fade in when ready, and posters fade in as they arrive. Before, a grid of the titles so far grew every second: the count kept changing and the title under the cursor flickered with it. "Loading more" messages no longer show a count while titles are still arriving.
- A Movies or Series list keeps up to 200,000 titles (was 100,000). A playlist on the owner's onn stopped at exactly 100,000, and a provider that lists by category would lose whole categories at the end.

## 0.51.44 — 2026-11-20

- The background check for new movies and series pauses for half the processor time it uses instead of all of it. On the second onn, 0.51.43's check finished in 59 s, just inside the provider's one-minute cut-off; this leaves room. If a provider still cuts the check off, Spare tries once more straight away without pauses, instead of waiting 30 minutes.

## 0.51.43 — 2026-11-20

- New movies and series show up again on playlists whose provider cuts off slow downloads. The background check for new titles paused after every 64 titles to leave the processor to the playing channel. On a 32,000-title list that pausing stretched the download past a minute, and the second onn's provider resets any response still running after about a minute, so every check failed ("The provider connection was interrupted") and the saved list stayed 41 hours old. The check now pauses only as long as it actually used the processor. Waiting on the provider adds no pause.

## 0.51.42 — 2026-11-20

- Library opens on the rows for a playlist with a very large movie list. Spare read at most 64 MB of the list, so on a playlist with more than about 80,000 movies the download never finished: every Library visit spent about 85 s downloading the same titles again and then showed the plain all-titles grid. The limit is now 128 MB. A list past Spare's limits (also 100,000 titles) keeps the titles that fit instead of starting over, and the performance report says so. The first Library visit after updating downloads the list once.

## 0.51.41 — 2026-11-20

- Library grids (all titles, a category, Search from the rows) look like the rows: posters only, with the big title above naming the poster under the cursor. The title and genre under each poster are gone; a two-line title used to push the genre off the bottom of its card. A title without artwork still shows its name, and a Watchlist title has a small ✓ in its corner. Catch-up lists keep their text.
- Retry on a playback error puts the remote back where it was. With the guide open and the menu hidden, it used to land on the menu handle at the left edge; it now returns to the channel's row.

## 0.51.40 — 2026-11-20

- The switch away from a reopened channel that has ended comes after 10 s instead of 29 s. On an onn 4K Pro the ended slot never answered, so Spare waited its full 15 s start time and then tried the slot again in the provider's other stream format for 14 s more. A channel Spare reopens by itself played before in its usual format, so it now gets 10 s and no second format before the most recent working channel plays. Channels you choose still get the full wait and both formats.

## 0.51.39 — 2026-11-19

- When Spare reopens on the last channel you watched and that channel has no stream any more (usually an event slot whose game has ended), it plays the most recent channel that showed a picture instead, regular channels before other event slots, and says so: "LP 07 … has no stream now · Playing Harbour Sports HD". It used to retry the dead slot for about 30 s and then show "Could not read the provider's response". A channel you choose yourself still shows the error with Retry.

## 0.51.38 — 2026-11-19

- Sports opens on the first card. On an onn 4K Pro 0.51.37 put the cursor on the game the header had picked from the saved scores, which after the fresh scoreboard arrived was the second card. Until you press a key, the cursor now stays on the first card as games arrive and reorder; a card you move to yourself is still where Sports resumes.

## 0.51.37 — 2026-11-19

- Sports no longer jumps to the bottom card when it opens. RedZone is the last card in Live now, and it took the cursor whenever it was the only card while games loaded or the header had last shown it; the cursor then stayed there as the games arrived. Sports now starts at the top game, or on the card you last moved to yourself.
- The NFL RedZone card appears only on Sundays (in your time zone or New York's), when RedZone is on. On other days its channel only shows a holding screen, so the card and Spare's search of the channel list for RedZone channels are skipped.

## 0.51.36 — 2026-11-19

- Sports cards count only streams whose guide listing or channel name names the game. A college card used to say "512 streams found · Quality unverified" when nothing named it: "ESPN+" was read as "espn", so every ESPN channel counted, and team nicknames ("Bulls", "Tigers") matched hundreds of unrelated channels. Possible streams are now only the game's own broadcasters' channels (at most 96), and a card with no named stream says "No stream names this game yet · Select to look through ESPN+ channels".
- Spare reads today's names of event channels from up to 24 event categories instead of the first 8 in list order, starting with categories that sound like your leagues and today's broadcasters (NCAAB, ESPN+, Game Pass…). In a large lineup the college categories were past the first 8, so their names stayed days old and today's games were not found.
- Event channel names are read more like people write them: "(W)" and "(M)" mark women's and men's college games, a date written "12.05", "05.12" or "Dec 5" and a start time such as "1:00 PM ET" must fit the game (a name from another day, or more than three hours off, is ignored), and a bare "Basketball: Richmond vs. Towson" counts in the game's own league.

## 0.51.35 — 2026-11-18

- Library poster tiles no longer paint a solid block behind each poster. The placeholder and then the poster always cover it, so on an onn 4K Pro it was one of about 16 fills drawn every Library frame without ever being seen.
- Settings › Playback says "Off · Spare switched it off after a playback problem with it" when Spare turned tunneled playback off. It used to say a stream did not start, even when the reason was the picture stalling.
- docs/LOADING-PLAN.md has overnight measurements from the onn: video smoothness with Sports, Library and the guide, what makes a channel change take 2.5 s, and where the menus' frame time goes.

## 0.51.34 — 2026-11-18

- Adding leagues no longer makes the video behind Sports choppy. With ten leagues, an onn 4K Pro dropped about 50 frames every 1–2 seconds for 13 seconds while Sports read the new scoreboards: each team a scoreboard named without a known code (mostly college and soccer teams) was compared with every catalog team's cleaned-up name. Those names are now looked up once per league; 514 unknown college baseball teams took 162 ms that way on a Mac and now take under 1 ms, with the same results.
- The NFL RedZone search over the whole channel list, which runs again whenever the guide changes while Sports is open, checks each name and listing for "zone" or "rz" before running its patterns. It finds the same channels.

## 0.51.33 — 2026-11-18

- Playback is smooth again. 0.51.32 downloaded guide listings for game channels during full-screen playback, and on an onn 4K Pro the video skipped frames while it ran: 15 frames in the 10 s it spent ranking a 15,800-channel lineup, and about twice the normal rate during its requests. Full-screen playback again downloads only the playing channel's listings, as in 0.51.31.
- OK on a live Sports card with no saved listing for the game asks the provider about the game's own broadcasters from the scoreboard first, then team-named channels, then 4K and league channels. The 12 requests used to go mostly to local, 4K and League Pass channels and could miss the channel showing the game, so the stream list opened instead of the game.

## 0.51.32 — 2026-11-18

- OK on a live Sports card plays the best stream at once again instead of opening the stream list. A card plays at once when the saved guide shows a channel carrying the game now. Those listings came from the quiet guide fill that 0.51.18 limited to the Guide screen, so on a TV that mostly plays full screen they ran out within a day. After 30 s of steady full-screen playback with no remote input, Spare again fills listings for favourites and the channels likely to carry today's games, at most one request a second. The rest of the lineup still fills only on the Guide screen, and nothing is filled while Sports, Library or Search is open.

## 0.51.31 — 2026-11-18

- Library refreshes a Movies or Series catalog older than 6 hours in the background when you open its rows ("Checking for new titles…"). The rows and grids on screen keep the copy they show until you leave Library; the new titles appear the next time the rows open. A failed refresh tries again after 30 minutes.
- Search reads the current saved catalog after a refresh instead of the copy it loaded first.
- docs/LOADING-PLAN.md has the first measurements from the second onn on 0.51.30.

## 0.51.30 — 2026-11-17

- Library's Movies and Series rows say how old the saved catalog is ("Saved today", "Saved yesterday", "Saved 3 days ago · Refresh for new titles"). Since 0.51.18 catalogs refresh only when you choose Refresh, so this shows when new provider titles may be missing.
- docs/LOADING-PLAN.md records the Sports and Library loading work so far and what still needs measuring on the onn.

## 0.51.29 — 2026-11-17

- Each playback hiccup in the performance report now shows how fast the live stream was arriving over the last 10 seconds against its average for that channel, how long ago its last data came, and what else Spare was downloading (guide, artwork, scoreboards, catalogs) or still waiting on. A "Network now" line shows the same while you watch. Counts only; no addresses.
- Background workers that download artwork, Library details, updates and scheduled refreshes are named, so the report can say which one was busy.

## 0.51.28 — 2026-11-17

- The performance report's Sports and Library opening traces keep their first 40 steps and the latest 20, so steps that come after a burst of scoreboard replies, such as the guide read for other live sports, are no longer dropped.
- Five device tests were updated to match changes made in 0.51.19–0.51.22: Search opens listening, Sports reads the guide after the scoreboard replies finish, and Library lays out only the rows on screen.

## 0.51.27 — 2026-11-17

- Removed the VLC handoff added in 0.51.26. Live TV always plays in Spare's own player again: the Live TV player setting, Play in VLC in channel details and on playback errors, and the automatic VLC choice on Google TV Streamer are gone. TiviMate plays the same streams on that box, so the Streamer fix belongs in Spare's own playback.
- Google TV Streamer only, following other open-source IPTV players' fixes for its MediaTek decoder: live channels start video on a full restart frame, because the Streamer can take a stream joined mid-picture-group and never show video while audio plays. Frame-rate matching asks for 50 or 60 Hz instead of 25 or 30 fps, which broke frame pacing on the Streamer. Other TVs, including the onn, are unchanged.
- Settings › Playback › Playback diagnostics shows when the Streamer settings are in use.

## 0.51.26 — 2026-11-17

- Settings → Playback → Live TV player can open full-screen Guide selections in VLC for Android. Automatic offers installed VLC on Google TV Streamer and uses ExoPlayer on onn and other TVs. Channel details and live playback errors also offer a one-time VLC handoff.
- Spare asks before sharing a playlist’s stream addresses with VLC. HTTPS-only playlists and playlists requiring a custom User-Agent use the built-in player.

## 0.51.25 — 2026-11-17

- Playback on boxes other than the onn 4K Pro now follows what other open-source Android players (Just Player, IPVIO) do. A Google TV Streamer stalled on channels that TiviMate played smoothly on the same box; its report showed the stream running out of data while the hardware decoder was fine.
- Requests to providers carry Android's own User-Agent instead of "Spare/<version>", which some providers treat as an unknown app. A User-Agent set on a playlist is still used for that playlist.
- The video decoder is only asked to run at 60 fps on Amlogic decoders (the onn 4K Pro, where it was measured to help). Other boxes keep Media3's own decoder settings.
- The Google TV Streamer is no longer switched to the software H.264 decoder (0.51.24). Its hardware decoder played with no dropped frames.
- If the picture stalls twice within ten minutes while seconds of video are already buffered, Spare switches off tunneled playback, and on a later repeat frame-rate matching, for good on that box. Settings › Playback says when this happened; switching the option back on clears it. Stalls with an empty buffer (the provider or network) do not count.

## 0.51.24 — 2026-11-16

- On Google TV Streamer, prefer a compatible software H.264 decoder when opening a live MPEG-TS channel to avoid the device's frozen-picture issue. Playback diagnostics show when this device policy applies.

## 0.51.23 — 2026-11-16

- During steady live playback, Spare warms artwork from saved Library catalogs and a small set of today's Sports scoreboards and crests. Background warming pauses on input and buffering.
- Choose a Nostalgia loading animation for foreground waits in Theme settings.
- Sports replays combine adjacent archived listings for the same game and use the playlist that owns the archive feed.

## 0.51.22 — 2026-11-16

- Library starts poster requests in shelf order and drops old requests if the queue fills.
- Posters use a static theme-tinted placeholder with the title on shelves. With fades selected, artwork fades over it at the chosen speed.
- Search opens the in-app microphone. A spoken query replaces the current search, and opening the keyboard starts a fresh query.
- Library leaves background catalog progress out of the browsing UI.

## 0.51.21 — 2026-11-16

- Library shows catalog and cached details while browsing. Press OK to load full movie or series details.
- Library shelves build lighter poster tiles and lay out only the tiles needed by the grid, reducing work as shelves appear over live TV.

## 0.51.20 — 2026-11-16

- Sports and Library skip extra artwork requests for upcoming cards and rows. With Instant navigation, cards and artwork appear without reveal animations and Library focus moves without an animated lift.
- Debug builds report decoder video output by screen to help distinguish picture freezes from playback-clock stalls.

## 0.51.19 — 2026-11-16

- Sports shows scoreboard cards while scores arrive. RedZone and other guide scans, event channel names, and automatic stream discovery wait for the scoreboard and remote input to settle. Playlist scans use one worker, and Sports can reuse the existing guide index without rebuilding it on entry.
- Library picks its Recently added and Top rated shelves in one pass, keeping only the titles that can make those rows. The same titles and tie order are preserved.
- The performance report labels video buffers discarded by Media3 and shows the time covered by each drop report.

## 0.51.18 — 2026-11-16

- Browse saved Movies and Series without refreshing either catalog in the background. On a first visit, only the selected tab imports. Search reuses saved catalogs, starts missing catalogs one at a time per playlist, and stops their downloads when closed.
- Keep guide read-ahead and the whole-lineup guide fill on the Guide screen. Library starts no guide requests, and Sports browsing reads only the playing channel unless you open game coverage. The programme index rebuilds after the screen settles and at most every 30 seconds while guide data is changing.
- Opening Sports no longer filters the hidden channel grid. Library limits concurrent poster downloads to two to reduce work alongside playback.

## 0.51.17 — 2026-11-16

- Artwork retries in Library and Sports check only images still waiting to retry, in batches of four. Binding or laying out a poster no longer walks every tracked image on the screen thread.
- Sports checks only guide keys with a current listing marked live before grouping their channels, and defers that search briefly on open so scoreboards and cards get a head start.
- The performance report records short stops in the playback clock during tunneled playback, including the buffer and which screen was open. Library opening appears near the top of the report.

## 0.51.16 — 2026-11-16

- The performance report (Settings › About Spare) has a Last Library opening section: how long the saved catalog's database, index file and index took to read, how long the rows took to pick, read and build, and when they showed. A first Library open before the app has warmed it took about 6 s on an onn 4K Pro; this is to find which step that time goes to. Nothing about how Library loads has changed yet.

## 0.51.15 — 2026-11-16

- Sports opens with its games in about a second instead of several. Measured on an onn 4K Pro with a 15,800-channel playlist, the saved schedule was ready 0.8 s after choosing Sports, but the cards waited 7 s: every time the cards were prepared, the app first read every channel's guide listing looking for live golf, tennis, racing and fights, which took 1.5 to 6.8 s there and ran four times in the first half minute. The games no longer wait for that reading. It runs once on its own, is reused for a minute, and those extra cards join the games when it finishes.
- The Last Sports opening section of the performance report also says how long that guide reading took.

## 0.51.14 — 2026-11-16

- Navigation animations (Settings › Theme › Advanced › Guide) no longer leave a ghost behind. With Fade in or Cross-fade, moving quickly through channels or categories could leave a row frozen part-way through its fade: a grey highlight on a row the cursor had left, or a row missing its background. The fade is now drawn by the clock, so a skipped frame shows the finished state instead of sticking.
- A change to the animation style or speed applies to what is already on screen, without reopening the guide.
- Sports shows games as scores arrive. Cards prepared while another league's reply came in were thrown away and prepared again, so on a busy box nothing showed until the last league answered and then everything appeared at once. Each prepared set is now shown, and refreshed as more arrives.
- The performance report (Settings › About Spare) has a Last Sports opening section: when the scoreboard replies came in, how long the cards took to prepare, when they showed, and how long each team crest waited and took to load. This is to find the remaining wait for crests on the first open, which is not fixed yet.

## 0.51.13 — 2026-11-16

- First-run setup keeps the remote on its own buttons. Pressing Right or Down past Continue (or past any button at the edge of a step) used to move the cursor to a guide button hidden behind setup, and the next press threw it back to the first choice, so Continue could be impossible to stay on. The cursor now stays put at the edge.
- When a loading playlist takes the cursor during setup, the next press puts it back where it was and carries on from there, instead of jumping to the first choice and swallowing the press.
- Releases are now published as regular GitHub releases instead of pre-releases, so `https://github.com/mot1us/spare/releases/latest/download/spare.apk` always downloads the newest APK (for a Downloader shortcut, for example). From this version the in-app updater reads the same latest release; older installs keep finding updates the way they did.

## 0.51.12 — 2026-11-16

- Pressing Back in the guide no longer switches category. Back moves the cursor from the channels to the category list, and it used to land on the category the cursor last rested on, which selected it. If the list had been changed another way since (the All channels tab, for example), Back alone jumped to the earlier category. The cursor now lands on the category whose channels are on screen.

## 0.51.11 — 2026-11-16

- A movie's details page in Library shows one Watchlist button, beside Play. The second one on the tab line is hidden there; series pages, which have no button beside Play, keep it.

## 0.51.10 — 2026-11-16

- Moving through channels and categories is instant again, as it was before 0.50.8. The highlight fade added then made fast scrolling feel slower and left a trail of half-lit rows behind the cursor. Guide, Sports and Library also switch at once again.
- New in Settings › Theme › Advanced › Guide: "Navigation animation" (Instant, Fade in, or Cross-fade) and "Animation speed" (Fast, Normal, Slow). "Fade in" lights the new item gradually and clears the old one at once, so nothing trails; "Cross-fade" is the 0.50.8 behaviour.
- Settings › About Spare › Performance report now ends with "Recent actions": the last 40 things that changed the screen or the channel (keys pressed and which remote or device sent them, channel and category changes, the guide and Library opening and closing, Spare leaving and returning to the screen). It is there to trace a channel or category change nobody asked for. Like the rest of the report it is kept in memory only and never sent anywhere.

## 0.51.9 — 2026-11-16

- Opening Spare from a saved playlist reads the channel list much faster. The list is now saved in a compact form that is read straight into channels; before, it was saved as JSON, and every start rebuilt each channel from text and worked out its quality from its name again. In a test with 15,800 channels on the emulator, reading the list took 66 ms instead of 449 ms, and the saved file is less than half the size. The saved list from an older version is converted the first time it is read; nothing is downloaded again.

## 0.51.8 — 2026-11-16

- Library posters no longer wait behind an unrelated download. While one poster was downloading, the check for whether another poster was already saved on the TV could be made to wait for it, and every poster behind that check waited too. Downloads now run without holding that lock. On an onn 4K Pro a row's new posters started appearing about 1.5 seconds after scrolling stopped and then arrived one or two at a time; whether this change removes that delay is still to be measured there.

## 0.51.7 — 2026-11-16

- Library posters load in the order you need them. Posters for the row you stopped on now load first, and requests for rows you scrolled past are let go. Before, they loaded in the order they were asked for, so a new row waited behind every row passed on the way: on an onn 4K Pro each poster took about 0.1 seconds to load but waited about 2 seconds in line.
- Library's banner (title, rating and description) is filled in when Library reopens on the poster you left. It stayed blank until you moved.
- Starting Spare with a saved playlist says "Opening your saved playlist" instead of "Loading", since nothing is downloaded again.

## 0.51.6 — 2026-11-16

- The guide now plays the channel behind it by default, instead of in a small preview at the top right. TVs where "Guide video" was never changed switch to this on update; a choice you made yourself is kept. Change it in Settings › Guide › Guide video.

## 0.51.5 — 2026-11-16

- Lighter guide drawing, aimed at smoother scrolling on budget boxes. Guide rows no longer use rounded clipping or a drop shadow under the highlighted row, and a duplicate black layer behind the whole app is gone. The guide looks the same. Measured on an onn 4K Pro, the TV's graphics chip, not the app's own code, was the slow part of each guide frame.
- "Up next" in the guide header only shows with the small preview, where there is room for it. With the channel behind the guide it had clipped the description.

## 0.51.4 — 2026-11-16

- The guide header shows "Up next" with the time and title of the programme that follows on the highlighted channel, in what was empty space under the description.
- Library details: the on-screen Back button is replaced by Add to Watchlist, next to Play. The remote's Back button leaves the page as before.
- When the channel plays in the small preview, the guide is painted as a solid color instead of a see-through one over black. It looks the same and gives the TV less to blend on each frame while scrolling.
- Opening the guide does slightly less work per channel on very large playlists.

## 0.51.3 — 2026-11-16

- "Guide video" is now in Settings › Guide and in first-run setup ("Channel while browsing"): play the channel in a small preview at the top right, or behind the guide. It was only in Theme › Advanced › Guide, where it was hard to find. Settings itself always shows the channel behind its panel.

## 0.51.2 — 2026-11-16

- Phone setup: as soon as your phone opens the form, the TV shows "✓ Phone connected" and points you to your phone, and the QR code fades back. Before, the TV looked unchanged until you pressed Send. The TV still shows nothing you type until you send it, so passwords never appear on screen while you type.

## 0.51.1 — 2026-11-16

- Add playlist now opens with the remote on "Fill in from my phone", so OK starts phone setup straight away. Before, focus started on the form itself and OK did nothing until you pressed Down. The button also shows a clear white highlight like the rest of Spare.

## 0.51.0 — 2026-11-16

- First-run setup. A new install opens four quick steps: add your playlist, pick the sports you watch, choose a look, and choose how the guide works (see-through or solid, text size, and whether OK plays full screen). Every step can be changed later in Settings.
- Set up from your phone. The first step shows a QR code. Scan it with your phone's camera, fill in your M3U link or Xtream login there, and press Send. The TV shows the details in Add playlist so you can check them before saving. The same option is in Settings › Playlists › Add playlist as "Fill in from my phone". The form is served by the TV itself over your home Wi-Fi, so nothing passes through an outside service. Each code works once, expires after ten minutes, and only accepts devices on your home network.
- Sports no longer stops to ask for your leagues the first time you open it. Leagues are chosen in setup; anyone who skips it starts with the NFL, MLB, NBA, NHL, Premier League, MLS and Champions League, changeable in Sports settings.
- Theme presets show their check mark again after you apply one.

## 0.50.8 — 2026-11-16

- Search opens ready to type, with the keyboard up. Before, opening it from the menu started voice search and Android's microphone prompt. Voice still starts from the Voice button or the remote's search key. The Done button is gone; Back closes Search.
- Sports cards show the channel with a short HD, FHD or 4K tag instead of technical detail such as "Quality unknown" or "FPS unavailable · Playback resolution". The full detail stays in the stream list. Hints now read "OK Watch", and the guide's hint line is shorter.
- Opening Sports shows the last saved live games straight away (up to 20 minutes old, marked "Saved score · checking live status") while fresh scores download. Before, it showed placeholders for several seconds. Cards then settle in one after another.
- Smoother movement: focus highlights cross-fade across the app, Guide, Sports and Library fade in when you switch to them, and posters fade in softly as they load.
- Local channels: the city box and its suggestions sit at the top, above the on-screen keyboard. Down from the box reaches the first suggestion, and OK adds it. Bigger TV markets are suggested first ("Port" offers Portland before Port Arthur), and up to five are shown.
- Removed the Metadata setting and the optional metadata service behind it. Library and the guide use your provider's own artwork, descriptions and ratings.

## 0.50.7 — 2026-11-16

- Opening Sports no longer freezes briefly while it builds the first screen of game cards. Spare now builds them ahead of time while the app is idle, one per frame, and keeps them ready.

## 0.50.6 — 2026-11-16

- New playlists allow HTTP links by default. Most IPTV providers send streams over plain HTTP, often by redirecting from an HTTPS address. Without this setting those streams failed with "HTTP 302", which looked like a broken channel. Turn it off in the playlist's settings if your provider uses HTTPS throughout. Existing playlists keep their setting.
- A stream redirect Spare won't follow now fails at once with a clear message ("turn on Allow HTTP-only links"). Before, it was retried for about five seconds per stream before the next choice, so a game could take 15–20 seconds to fail over.
- Alternate presentations of a game (studio shows, "mobile view", arena feeds, watch parties) are tried after the main broadcast. They were ranked first when their picture quality was higher.

## 0.50.5 — 2026-11-16

- Removed a 0.7-second screen freeze when opening Sports: the check for renamed event channels (Game Pass, PPV) scanned every channel name on the screen thread. It now runs in the background.

## 0.50.4 — 2026-11-16

- Opening Spare and going straight to Sports is faster. Library's catalog preparation, which could take half a minute of processor on an onn 4K Pro, now waits until you are idle and not in Sports. If you open Library first, it prepares what it needs then.
- One set of Sports settings. Settings › Sports now opens the same screen as Leagues and teams in Sports, at the section you choose (Preferences, Leagues, Your teams, Stream quality, Score delay, Sports overlay), instead of its own partial copy with a second "Sports settings" button. Sports overlay moved into that screen.
- Library shows ratings: the banner and details page lead with the provider's 0–10 score (for example "★ 7.8"), or Cinemeta's IMDb score when a metadata source is set and the provider has none.
- Back on the main menu while in Sports now goes to the live TV guide. Before, it returned to the Sports cards.
- With the hidden main menu, opening it no longer pushes the guide, Sports or Library screen aside; the menu draws over the screen.
- Releases now publish once the app builds and passes its unit tests, lint and release check. Emulator device tests still run weekly and on request but no longer hold up a release.
- Fixed a crash when Spare closed just after moving in Library: a queued request for the focused title's details reached Library's helper after it had stopped.
- The cast on the details page reads "Starring A · B · C" with the first six names, instead of a long run-on line.

## 0.50.3 — 2026-11-15

- Picking a live game plays straight away when your guide or a channel's name already confirms the game. Before, Spare first asked your provider about every other candidate channel in case one was sharper, which took about six seconds on an onn 4K Pro while the picture stood still. That check still runs when nothing local confirms the game.
- Fixed a race in the image loader: an image turned away because 48 were already waiting could slip through while another moved between the saved-copy and download stages, then download in the background after it had left the screen.
- Smoother Sports browsing: team crests, card logos and banner photos now load at the size they are shown, not two to three times bigger. Measured on an onn 4K Pro, every cursor move was copying oversized pictures to the video chip, and most frames missed their deadline.

## 0.50.2 — 2026-11-15

- OK on a live game card plays the game, choosing the best stream. Before, it opened a "Watch live" button first. Hold OK still offers other streams and actions, and Sports settings can turn this off ("Play best stream on selection" is now on by default, including after a Sports reset).
- Removed a half-second freeze after changing channel: Spare worked out which game the new channel is showing on the screen thread. It now does that in the background and updates playback details when ready.
- When the TV runs short of memory while Spare is open, Spare now frees the pictures of screens that aren't showing (Library, Sports, Search, details). Measured on an onn 4K Pro with several other apps open, Android was compressing memory so hard that every app, Spare included, slowed down.

## 0.50.1 — 2026-11-15

- OK on Sports in the main menu now moves straight into Sports. While scores were still loading, the cursor stayed in the menu, so OK seemed to do nothing until the games arrived. The cursor now rests on Live now and moves to the games when they load.
- Event channels renamed for today's games are found. Providers rename slots such as "NFL Game Pass 11: NO EVENT" to "NFL Game Pass 11: SEA @ LV" as games start, but Spare only saw the names from its last channel-list download. While Sports is open, Spare now re-reads just those event categories from Xtream providers every 10 minutes (a small request, not the whole list) and matches games to the fresh names.

## 0.50.0 — 2026-11-15

- Sports opens on Live now: live games from all your leagues together. Choosing a league shows that league's whole day (live, upcoming and results); Today and Calendar use the last league you chose.
- Live now also shows golf, tennis, motor racing and fights that your guide lists as live, for events with no scoreboard in Spare. One card gathers every channel showing the event: OK watches the best one, and holding OK lists them all. Only listings that say "live" and name the sport count; studio shows, replays, previews and highlights are left out.
- Fewer wrong channels for a game. When the guide doesn't confirm a game, Sports used to offer every channel named after its broadcaster, such as CBS or FOX. On a Sunday, each local station carries its own region's game. Sports now drops a station whose guide names a different matchup at that time.
- Team names Sports missed are now recognised: New York Rangers vs Islanders, several MLS clubs (Inter Miami, NYCFC, Red Bull New York, Sporting Kansas City, St. Louis CITY), "Man City" and "Inter Milan". A title whose extra words suggested a third team (the city "New York", "CF", "Man") used to be rejected; the two clear names now win. These were found with a new check of Spare's team names against ESPN's complete team lists (developer tool; the app makes no extra requests).
- OK on the RedZone card plays the best feed Spare already knows immediately. Before, it first asked provider guides for a sharper feed, which could take several seconds while nothing seemed to happen.
- Fewer half-second screen freezes during Sports: the team-name index is built in the background at startup, and the playing channel's game is worked out once instead of several times a minute.
- Startup no longer unpacks your other playlists while the first channel is still settling. On an onn 4K Pro, unpacking a large second playlist took about seven seconds of processor right after launch. Opening Sports during that time froze the menu briefly and made the picture stutter. Other playlists now load once the channel has played smoothly for about ten seconds and the remote is idle, or after 45 seconds at most. Until then, Sports and Search only see the playlist you are watching.
- Sports cards show the cursor as a border in your theme's accent colour instead of growing. A grown card in the first column ran under the main menu and was cut off.
- The guide now sits over the playing channel at 50% opacity by default. Settings → Theme can still change it.
- Playlists can name a User-Agent (Edit playlist → Show advanced options → Optional User-Agent), for providers that only answer certain players. Spare sends it to that playlist's servers for channel lists, guides and playback. Leave it blank to keep Spare's own.
- Xtream playlists can name a backup server address, also under Show advanced options. When the main server can't be reached, Spare loads the channel list from the backup, and those channels play from it until the main server answers again. Wrong usernames or passwords never switch servers.
- Settings → About Spare shows where to support Spare: ko-fi.com/mot1us. The GitHub page has a Ko-fi button and a line in the README. There are no pop-ups or reminders; Spare stays free.
- Large playlist, guide and catalog downloads now fail only when they stall (less than 32 KB arriving in 20 seconds), with a 10-minute ceiling. A slow but healthy provider could previously be cut off after one minute.

## 0.49.3 — 2026-11-15

- Sports says when "Show only my teams" is hiding games. With the setting on and none of your teams playing, Today or a league showed only the RedZone card with no explanation. The line under the title now reads, for example, "Today · Showing only your teams · 12 other games hidden", and an otherwise empty screen says how to turn the setting off. Live now still shows every live game.

## 0.49.2 — 2026-11-15

- Sports no longer sits empty (only the RedZone card) for up to a minute when its first scoreboard load runs out of time, which could happen right after leaving Library on a busy game day. It now tries again within a few seconds, up to four times, before falling back to the usual once-a-minute refresh.

## 0.49.1 — 2026-11-15

- Library banner: the title's artwork now spans the whole banner and fades smoothly into the page behind the text, instead of starting part-way across with a visible edge.
- Continue watching and Watchlist series keep your own detail (such as "Resume at 1:00" or "2 new · 5 unwatched") at the front of the banner after the provider's year, genre and length arrive. Before, the provider's details replaced it.
- The details page no longer repeats the section name ("Movies") above the poster.
- Removed needless null checks and two always-true conditions the compiler flagged.

## 0.49.0 — 2026-11-14

- Library redesigned like a streaming app. The second column of Movies, Series, Up next, Watchlist and Continue watching buttons is now a tab line across the top, with Search and Refresh as icons at its right. Below it, a larger banner shows the focused title's artwork, name, details and plot, and its text fades in as you move. Movies and Series rows use the full width of the screen. The search box only appears while it holds a search, and the remote-hint line is gone.
- Remote in Library: Left/Right move along the tabs, Down enters the titles, and Up from the first row returns to the tabs. Left from a row's first poster opens the main menu. Back from the titles goes to the tabs, and Back again leaves Library.
- The main menu rests as a slim column of icons and widens with labels over the screen when you move onto it. Guide, Sports, Library and Settings gain the space it used to take.
- Posters: artwork that arrives after its tile is drawn fades in instead of popping. Posters already saved on the TV are read on a thread of their own, so they no longer wait behind downloads, which can take seconds each. Tiles are larger, so posters are decoded at 320 px and the poster memory cache grows to 36 MB.
- Removed unused code: helpers for an old popup picker, the hidden category button, two Sports "best stream" functions the app no longer called (and tests that only covered them), settings markers nothing read, unused imports and a leftover macOS icon script.

## 0.48.0 — 2026-11-14

- Tunneled playback (Settings › Playback), on by default: the TV's own video hardware times each frame and keeps it in sync with the sound, so the picture no longer depends on the app keeping up. Before, scrolling, opening Library or the app's own background work could make video stutter while audio stayed smooth. The same approach is offered by OwnTV.
- If a stream does not start with tunneled playback, or the decoder or audio output refuses it, Spare switches it off for good, says so in Settings, and plays the stream again normally. Multiview always plays normally. Some streams are declined automatically by the player; diagnostics show whether the current stream is tunneled.
- While tunneled, frames never pass through the app: frame-rate matching reads the stream's own header once instead of timing frames, stall detection relies on the playback position, and dropped frames are no longer counted.

## 0.47.4 — 2026-11-14

- Live channels that do not state their frame rate (most live transport streams) now ask the TV's video decoder to run fast enough for 60 frames a second, when it supports that picture size at 60. Before, the decoder was given no target; on the onn 4K Pro it reported 30 for 59.94 fps channels and dropped frames whenever the app was busy. Streams that state their own frame rate are unchanged.
- Playback diagnostics show whether the decoder was asked for 60 fps or took its speed from the stream.

## 0.47.3 — 2026-11-14

- Playback diagnostics and the Performance report name the video decoder in use and whether it is the TV's hardware decoder or a software one. A software decoder shares the processor with the app, which would make video stutter whenever the app is busy while audio stays smooth.

## 0.47.2 — 2026-11-14

- Fixed a stall caused by the Performance report itself: when video frames dropped, it looked up what every part of the app was doing on the screen's own thread (about 0.2 s each time). That now happens in the background.
- A Movies or Series catalog refresh that nobody is waiting for runs slowly, pausing briefly between batches, so a playing channel keeps the processor. The first import of a new playlist still runs at full speed.

## 0.47.1 — 2026-11-14

- Sports fetches the guide listings of the channels carrying today's games first. The scoreboard names each game's networks; any channel named for one of them is checked right away, so its listing can confirm the game. Before, only channels with sports words in their names (ESPN, Fox, NBC…) were checked early, and a channel such as "Big Ten Network" could wait behind thousands of others.
- The poster and logo cache no longer lists and re-sorts every saved file after each image it saves. It tidies up every few dozen images, reading each file's date once. The poster cache also holds up to 2,500 posters (still within 64 MB) instead of 600, so browsing a large catalog stops re-downloading posters it had a moment ago.
- Settings › About Spare › Performance report can be scrolled with the remote: Down from Start over moves into the report, then Down and Up scroll it.

## 0.47.0 — 2026-11-14

- Movies and Series no longer live in memory. Each catalog is kept in the encrypted on-disk database, and Spare holds only a small index per title (search text, category, date added, rating) to filter and search. A 15,000-title catalog needed about 6 MB of memory before and about 1 MB now (measured on the emulator). Titles are read from the database only when they are about to be shown.
- The Movies and Series rows load the way other TV apps do: the first few category rows are ready when the page opens, and each further row reads its titles as it scrolls into view.
- A catalog saved by an earlier version moves into the database once, the first time it is opened; nothing is downloaded again.
- Removed the macOS preview app and its build from the project. Spare is an Android TV app only.

## 0.46.3 — 2026-11-14

- Sports now knows that a scoreboard's short network names and a playlist's spelled-out channel names are the same network: BTN = Big Ten Network, SECN = SEC Network, ACCN = ACC Network, CBSSN = CBS Sports Network, FS1/FS2 = Fox Sports 1/2, Pac-12 Network. A game on BTN now offers every "Big Ten Network" channel, not only the ones whose names say "BTN".

## 0.46.2 — 2026-11-14

Fixes found with the Performance report from an onn 4K Pro:

- Refreshing the Movies and Series catalog no longer slows Library down. The half-downloaded copy used to replace the complete one in memory while the refresh ran, so every Library screen fell back to its slow path (over a minute in the report). Library now keeps using the complete copy until the new one is whole.
- Loading the saved catalog reads it one title at a time instead of building the whole file in memory first (7–8 s per catalog in the report, and a memory spike that coincided with dropped video frames).
- The Performance report now also shows what is playing (format, buffer, dropped frames, display rate) and memory in use.

## 0.46.1 — 2026-11-14

- New: Settings › About Spare › Performance report. It shows what the app was doing when it felt slow, measured on the TV itself: which code blocked the screen and for how long, where slow Library frames spent their time, how long posters waited and took to load, and for each playback hiccup how much of the stream was buffered, how much memory was in use and what else the app was running. Use the app until it stutters, open the page and photograph it. It is kept in memory only and never leaves the TV.
- The published build now keeps readable code names so the report can name what was slow (about 75 KB larger).

## 0.46.0 — 2026-11-14

- Library navigation is rebuilt on Leanback, Google's own list engine for TV remotes (`androidx.leanback:leanback-grid`, the same engine well-known open-source TV apps use). The Movies and Series rows, the category grid, episode lists and the category/season list now let that engine move the cursor, keep it on screen, scroll while a key is held and put it back when titles change. The hand-written cursor code these screens used is gone.
- The screens look the same and the keys do the same things: Up from the top leads to search, Left from the first column leads to the side list, each row remembers its place, and CH+/CH− still jump a screen of posters.
- Starting the app is gentler on live TV: loading the saved Movies and Series catalog into memory now waits 45 seconds after start instead of 10, so it no longer lands on top of the guide loading while a channel has just begun playing. Opening Library sooner still loads it right away.

## 0.45.3 — 2026-11-14

- Picking a category in Library puts the cursor on that category's first title. While the titles loaded, the cursor could be handed to the Movies/Series buttons, which also cancelled the move to the titles; that hand-off is now recognised and the cursor continues to the titles. Pressing a direction key yourself always wins.

## 0.45.2 — 2026-11-14

- Live TV no longer competes with a Library catalog refresh. A saved Movies and Series catalog more than a day old was being downloaded again in the background while a channel played, which could freeze the picture for a second or two every half minute. The refresh now waits until Library is opened; the very first import still runs shortly after start.

## 0.45.1 — 2026-11-13

- Smoother Library scrolling. Category rows now share one stock of poster tiles: a row scrolling in reuses the tiles of the row that left, where it used to build most of its tiles again each time.
- A row keeps two tiles ready past each edge instead of a whole extra screen on both sides, so showing a row is about a third of the work and off-screen posters no longer queue ahead of the ones you can see.
- While a direction key is held, the banner at the top (image, title and description) waits for the cursor to stop instead of changing on every step.
- The category grid keeps enough spare tiles to scroll a full row of posters without building new ones.

## 0.45.0 — 2026-11-13

- A series opens on one season — the first with an episode you have not watched — with the seasons listed on the left ("All seasons" stays first) and a taller hero image of the show above the episodes.
- Library no longer re-imports the whole catalog while you browse. A saved catalog of any age opens at once; it is refreshed only while Library is closed, when it is more than a day old (or with Refresh). On a small TV a re-import could stall a category change for a minute.
- Changing category with the catalog in memory is shown immediately, without waiting for a background worker.
- Opening Library from the menu returns to where you left it: the same section, your place in the rows, the category grid you had open, or the series you were in.
- Holding a direction key no longer starts a download for every poster that flies past: placeholders show while the key is held and the posters on screen load as soon as it is released. Posters already in memory still appear at once.
- Fixed: after the rows were hidden and shown again, a row that remembered a tile far to the right could refuse the cursor, leaving Up/Down stuck on the neighbouring row.

## 0.44.0 — 2026-11-13

- Library posters are lighter to download, decode and draw. Artwork hosted on TMDB (image.tmdb.org) is requested at the 342-pixel width instead of the 500-pixel-or-larger size providers usually list; other hosts are fetched as before. Tiles in the rows, the grids and Search are decoded at the size they are drawn (about 256 pixels tall on a 1080p TV, under a third of the memory of before), so roughly three times as many posters stay ready in memory; the title page still gets a sharper copy. Posters stay in the same encrypted on-device cache; TMDB posters saved by earlier versions are downloaded once more at the smaller size.
- The rows keep decoding ahead of the remote: the tile just past the right edge, the first tiles of the next row down, and — ten seconds after the guide loads — the first screen of the Movies and Series rows.
- Movies and Series rows now start with Continue watching (titles stopped part-way; for Series, one tile per series with its latest episode) and Watchlist, when there is something to show. OK on a movie opens its page with Play / Resume; OK on an episode resumes it. Both rows are read again each time the rows are shown, and show See all when they hold more than 20 titles.
- A catalog update or a late grid page arriving while the rows are being prepared can no longer replace them with the full grid.
- Settings › Playlists › Refresh settings shows a "Library frames (last visit)" line: how many frames Library drew, how many were slow, about how many screen refreshes were dropped, and the worst frame. It counts only while Library is on screen, starts again on each visit, and is kept in memory only.

## 0.43.0 — 2026-11-12

- Movies and Series open on a rows home, like streaming apps: Recently added, Top rated, then one row per provider category with its first 20 posters and a See all tile. Up/Down moves between rows (each remembers its place), Left/Right within a row, OK opens a title. See all opens that category's grid with Sort and the category list, whose first entry is Home; Back from a grid, a title or a search returns to the rows on the same tile. Typing in Search on the rows searches the whole catalog, and clearing the text returns to the rows.
- The rows need no provider request: the whole Movies and Series catalogs are imported in the background — ten seconds after the guide loads, and when Library opens — and kept in memory. Once imported, rows, categories and search read memory; the catalog refreshes itself when it is more than six hours old. Recently added and Top rated appear only when the provider supplies "added" dates and ratings. If the import fails, Library falls back to browsing one category at a time.
- Saved catalogs from earlier versions are refreshed once. Settings › Playlists › Refresh settings shows how long the whole-catalog imports took ("All movies", "All series").

## 0.42.1 — 2026-11-12

- Library no longer gets stuck on the first 80 titles while a big category is still downloading: progress updates no longer cancel a "load more" page you scrolled to, and the grid is redrawn at most every 2.5 seconds during a download instead of every ¾ second.

## 0.42.0 — 2026-11-12

- Saved Movies and Series categories now live in an encrypted on-device database instead of one large file per category. Opening a saved category reads and decrypts only the page on screen, so a category with thousands of titles no longer has to be parsed whole before anything appears. Titles, artwork and stream paths are encrypted row by row with the same data key as the other caches; only the category, provider order and a generation id are readable.
- A refreshed category replaces its old rows when it finishes, and removing or changing a playlist deletes its rows. Category files from earlier versions are removed the first time the database is created; each category is downloaded again once.

## 0.41.5 — 2026-11-12

- Library posters are ready sooner: ten seconds after the guide loads, Spare quietly decodes the first screen of posters for Movies and Series, so the first visit shows them at once instead of waiting several seconds for them to arrive.

## 0.41.4 — 2026-11-12

- Sorting Library by A–Z or Newest no longer recomputes every title's sort key on each comparison, which took far longer than the rest of opening a large category. While a category is still downloading, it shows in provider order immediately and sorts once it finishes.
- Settings › Playlists › Refresh settings also shows how long a saved category took to read.

## 0.41.3 — 2026-11-12

- Settings › Playlists › Refresh settings now also shows how long Movies and Series took to list categories and to show their first titles, to find what still delays the first Library open.

## 0.41.2 — 2026-11-12

- Library loads a category much faster: reading a saved category is about five times quicker, and importing one from the provider about twice as quick, by removing repeated work for every title (compiled patterns, a plain-text fast path for search keys, a saved title key, cached stream address prefix). Saved partial progress while a very large category downloads is written every 20 seconds instead of 5.

## 0.41.1 — 2026-11-12

- Cached posters and logos (small files) are now converted to the fast data-key format on first read, as the large cache files were in 0.41.0. Library scrolling no longer waits on slow Keystore calls for each saved poster.

## 0.41.0 — 2026-11-12

- Faster start: saved channels, guide and Library files are now encrypted with a random data key that the Android Keystore protects, instead of running every byte through the Keystore. Large files used to take several seconds to open on TV hardware. Existing files are converted the first time they are read, so the first start after updating is still slow once.
- Settings › Playlists › Refresh settings now splits "Last start" into decrypt and parse times for the channel lineup and the guide, to show what remains slow.

## 0.40.1 — 2026-11-12

- Library opens a single saved category grid with categories always visible on the left. Switching categories cancels abandoned requests; opening Library stops its background warming. Search, sorting, Up next and Watchlist remain available.
- Game reminders can appear while browsing after a brief input pause. A Back release from the previous screen cannot dismiss a new reminder, and remote focus stays inside its actions.
- Overlapping daily and weekly scoreboards choose the fresher result, preventing a cached upcoming listing from overwriting a live game.

## 0.40.0 — 2026-11-12

- Movies and Series open on category rows, like streaming apps: one row per provider category with its first 20 posters and a "See all" tile. Up/Down moves between categories (each row remembers its place), Left/Right within a row, OK opens a title. Only rows on screen download, two categories at a time, and each is saved on the TV for the next visit.
- See all (or the category picker) opens that category's full grid with Sort and search; Back returns to the rows on the same tile. Choosing All categories in the picker returns to the rows.
- Stopping a movie returns to the row or category it was started from, with the cursor on it.
- Leaving Movies or Series (or Library) stops their row downloads, so Up next, Watchlist and playback get the provider to themselves.

## 0.39.3 — 2026-11-12

- Smoother Library scrolling while a category downloads: the grid keeps the titles already shown and only grows, instead of being rebuilt (and every poster redrawn) about once a second.
- Smoother playback after startup: the quiet background guide fill starts 45 s after launch instead of 15 s, and saves its listings at most every 5 minutes (and whenever Spare stops) rather than rewriting the whole encrypted guide every 30 s.
- Settings › Refresh shows how long the last start took to show channels and the saved guide, and how long the provider took to answer the last Library category, movie details and series request.

## 0.39.2 — 2026-11-12

- Faster Library over the network: finished provider and artwork requests keep their connection open for reuse, so later category, details, episode and poster requests skip a fresh connection and encryption handshake. Library posters download four at a time.
- Opening a series you just paused on is instant: the spotlight's series request already carries the episode list, so it is fetched once instead of up to three times.

## 0.39.1 — 2026-11-12

- Continue watching shows episodes as their series, with the series poster and name, the episode code and the resume point, instead of a blank tile with only the episode title.

## 0.39.0 — 2026-11-12

- Sort Library Movies and Series: a Sort button beside the category picker cycles Provider order, A–Z and Newest, and the choice is remembered. A–Z ignores provider tags such as "EN |" and leading articles, so "The Batman" sorts under B.

## 0.38.1 — 2026-11-12

- Includes 0.38.0 and 0.37.0, whose publishes were stopped by CI device runs.
- Rank the lineup for the background guide fill on its own low-priority thread, and pause the fill while Sports is matching a game, so very large lineups never delay saved-guide or Sports work.
- Preview a theme preset live while the cursor rests on it: the preview panel and the studio's own accents switch before OK applies it.
- Show a remote-hint line at the bottom of Library, matching the Guide and Sports: OK Details, Hold OK Watchlist, CH+/CH− Page, and context-specific hints on details, episode and archive pages.
- Counts read naturally ("1 title", "1 episode", "1 channel", "1 game").
- Watchlist movies show their year beside "Movie".

## 0.38.0 — 2026-11-12

- Includes 0.37.0, whose publish was stopped by an unresponsive CI emulator.
- Add ten bolder palettes (Neon, Synthwave, Crimson, Gold, Electric, Emerald, Tangerine, Aurora, Lagoon, Ultraviolet) and eight new presets built on them: Neon Night, Synthwave, Stadium, Gilded, Aurora, Ember, Emerald and Ultraviolet. Preset swatches show each theme's background and accent together, and the preset list scrolls with the remote.
- Make Search a fully opaque page so the guide underneath no longer ghosts through the results.
- Sports cards name the channel without repeating "Quality unknown" on every game.
- Sports times for today and tomorrow read "Today · 7:15 PM" instead of repeating the date.
- Sports calendar days show the date large with "Today" and the game count on a smaller line, instead of an all-caps label squeezed into the tile.
- Playlist, metadata and notice dialogs use the theme's panel style instead of the stock black system dialog.
- Give a device test's secondary playlist a unique ID per run, so a playlist removed by an earlier run cannot block its saved guide.
- Series pages title the header "Series" and leave the series name and plot to the spotlight; episode rows drop the dangling separator when the provider gives no runtime; the details page's Back button is simply "Back".

## 0.37.0 — 2026-11-12

- Add Up next to Library: one poster per series you follow, showing the episode to resume, the next unwatched episode, or a newly released one marked New. Series you start are followed automatically, alongside Watchlist series, without being added to the Watchlist. Newly released episodes are listed first, then the series you watched most recently.
- Episode lists for Up next are re-checked at most every 15 minutes, for up to 40 series and within 25 seconds, and stopping an episode started from Up next returns to the list.

## 0.36.0 — 2026-11-11

- Give Library Movies and Series a streaming-app layout: a spotlight above the grid shows the focused title's backdrop, name, year, genre, runtime and plot, with seven posters per row below. Plot and backdrop are fetched once when the cursor settles on a poster and are reused by the details page.
- Restyle the details page around the same spotlight: backdrop, title and facts above the poster, with Play and Back leading the column beside it. Series episode lists keep their series in the spotlight.
- Make the cursor easy to find: the focused Library or Search poster gets a white ring and lifts slightly, and the focused Sports card lifts the same way.
- Opening Library from the menu puts the cursor on the first poster as soon as it appears, instead of leaving it on the menu.
- Opening Sports while games are loading leaves the cursor where it was and moves it to the first game when games arrive, instead of parking it on a league filter.

## 0.35.0 — 2026-11-11

- Fill the programme guide for every channel quietly in the background, one request at a time, so categories you have not opened already show programmes and the guide is saved for the next launch. Favorites go first, then likely game networks, so Sports can match games from the saved guide instead of asking the provider each time. The fill waits for startup to settle, pauses for visible rows, and backs off when the provider errors or rate-limits.
- Stop failing large provider XMLTV guides on slower TV boxes: parsing time no longer counts against the download deadline, and the full guide gets a three-minute network budget.
- Prepare Library in the background after startup: the category lists and the first Movies and Series categories are saved, so the first Library visit opens from the TV instead of the provider.
- Scroll Library posters more smoothly: posters use half the memory, the cache holds several screens instead of under three rows, posters skip unused mipmaps, tiles no longer rebuild their styling on every bind, and posters two rows ahead are decoded before they scroll into view.
- Save the guide within 30 seconds of new programme data even while updates keep arriving.

## 0.34.2 — 2026-11-11

- Release a provider download's worker the moment it is cancelled: close the connection instead of waiting up to a minute for a slow server, so switching Library categories no longer queues behind abandoned requests.
- Cancel the other Library tab's category download when you pick a category, so Movies and Series never compete for the same workers.
- Back from a Sports game now returns straight to the Sports cards instead of the list of streams found for that game.
- Back follows what is on screen: after switching from a game to a non-game channel with Left, Back returns to the guide on that channel instead of the Sports section.
- Keep waiting, and reconnect, when a CI emulator briefly reports offline during boot instead of failing the device-test job within seconds.

## 0.34.1 — 2026-11-11

- Open Library Movies and Series categories quickly even while Search is still downloading the full catalog in the background: category pages now use their own download workers instead of waiting behind it.
- Stop decrypting the whole saved catalog before a category's first page; it is now used only as an offline fallback when the category download fails.

## 0.34.0 — 2026-11-11

- Show Library and Search posters as tall 2:3 tiles, five per row, so artwork is no longer cropped to a square.
- Keep Library and Search posters in their own encrypted, size-limited cache, so they appear instantly after a restart instead of downloading again.
- Page and Channel keys jump a screen at a time through Library poster grids.
- Replace the jumping counters while a Sports game's channels are searched with one smooth progress bar, plain wording, and placeholder rows.
- Rename the Sports "Other day" filter to Calendar, and show arrows at either end of the league row when more leagues are off screen.
- Widen the Sports card score slot so messages like "Score unavailable" are no longer clipped.
- Fail device-test jobs within three minutes when the CI emulator never connects, instead of hanging until the job timeout, and retry truncated emulator downloads.
- Merge the 0.32.16 review: keep VOD titles visible after a missing sub-file, isolate provider caches from source edits and deletions, reject reused release tags and physical-device test targets, and add architecture, provider, guide, Library, Sports, playback and release documentation.

## 0.33.2 — 2026-11-11

- Add a sleep timer in Settings → Playback (30, 60, 90 or 120 minutes). When it ends, playback stops and the TV is allowed to sleep; OK resumes the same channel live.
- Show recent searches as one-press chips when Search is empty. A search is remembered only after you open one of its results, and Settings → Guide can clear them.
- Measure startup with a 20,000-channel saved guide (first frame in about 1.1 s on the test emulator) as a regression check.
- Choosing a Search tab now waits for its results to lay out before moving focus into them.
- Split release device tests across four emulators.

## 0.33.1 — 2026-11-11

- Switch Sports leagues without stutter: league changes went from 44% to under 3% janky frames by skipping placeholder flashes for fast loads, laying out only the cards on screen, and using fixed-size card text.
- Add remote keys: Channel +/− and Page Up/Down change channel in full screen and jump a page in the guide, Last channel returns to the previous channel, Guide opens the guide, and number keys pick a channel by its position in the list.
- Add a large-playlist guide scrolling benchmark (3,000 channels with logos) to the device tests.
- Split release device tests across three emulators.

## 0.33.0 — 2026-11-11

- Rebuild Sports around one filter row: Live now, Today or another day, your leagues, and Leagues and teams. The side rail and schedule popup are gone, and the game grid uses the full width.
- Split Search into Live TV and Movies and series tabs with result counts, so live channels and on-demand titles are never mixed.
- Draw guide channel logos larger and with smooth scaling, and decode them closer to their display size so they no longer look pixelated.
- Load programme guide data ahead of the visible rows in the background, in the direction you are scrolling, and pause read-ahead when a provider rate-limits.
- Run release device tests on two emulators in parallel to roughly halve publish time.

## 0.32.16 — 2026-11-10

- Give Sports a compact game grid, a league and settings rail, and one schedule control.

## 0.32.15 — 2026-11-10

- Browse Library posters and live programme results together in Search, with filters for each type.
- Keep matching Sports games to channels while the preview rebuffers, instead of restarting the search on every stall.

## 0.32.14 — 2026-11-10

- Prepare Sports matches from a shared guide index and warm several visible games together.

## 0.32.13 — 2026-11-10

- Trace Sports cache decisions, EPG matching, provider lookups and first-frame timing in opt-in playback diagnostics.

## 0.32.12 — 2026-11-09

- Reduce repeated guide drawing and focus work while preserving programme labels and remote navigation.

## 0.32.11 — 2026-11-09

- Preserve Media3’s codec buffer locking and callbacks when observing input cadence.

## 0.32.10 — 2026-11-09

- Add an opt-in performance overlay under Playback diagnostics for playback and guide measurements.

## 0.32.9 — 2026-11-09

- Release cached artwork when Android asks Spare to trim background memory.

## 0.32.8 — 2026-11-09

- Exclude M3U parsing time from the playlist download deadline.

## 0.32.7 — 2026-11-08

- Reduce allocations when reading provider quality tags from M3U playlists.

## 0.32.6 — 2026-11-08

## 0.32.5 — 2026-11-08

## 0.32.4 — 2026-11-08

- Verify import and video playback on the minified APK before publishing a release.

## 0.32.3 — 2026-11-08

- Reduce memory use while importing large M3U playlists and downloading app updates.

## 0.32.2 — 2026-11-08

- Combine Today, Live now and Choose date in one Sports schedule picker.
- Give Library the same left rail as Sports and put categories in a picker above the catalog. Right enters the grid and Back returns to the selected rail item.

## 0.32.1 — 2026-11-08

- Label available Sports replays **Replay** so the badge fits in the game card footer.

## 0.32.0 — 2026-11-08

- Use startup/playback/storage smoke checks plus explicitly selected feature checks for routine releases. Keep the full TV suite for higher-risk changes, manual runs and weekly checks; log each active test. Signing and update-install verification stay required.

- Choose leagues on the first Sports visit before loading scoreboards. Preserve existing choices and browse one league at a time using a left rail; Today, Live and Calendar follow that league. Remember the last league and each league’s card position, with direct remote access to top controls.
- Restore large, smooth team logos on game cards. Keep both team names readable and move score, status and game clock into one compact bottom line.
- Give playlist fields explicit D-pad and keyboard Next/Done navigation, including username-to-password focus. Add an optional Show password control without changing provider URLs or HTTP consent.
- Open completed games from the Sports overlay in their own league so a late refresh cannot replace their details.

## 0.31.1 — 2026-11-08

- Move game scores, clock, status and situation into the team-logo banner. Give both team names/records fixed-height rows and shorten the cards without squeezing text.
- Show confirmed EPG/channel-title feeds in normal game results when available; keep generic network suggestions only as an explicitly unconfirmed fallback when no matchup feed is known. A working HD feed remains valid regardless of higher-resolution guesses.
- Recheck coverage labels against current listings, and demote/label recent playback failures when reopening alternatives. Preserve unknown-FPS playback, manual retry and RedZone matching; no background playback probes.

## 0.31.0 — 2026-11-08

- Restore the saved playable lineup before its EPG and additional playlists; keep loading visible until channels actually reach the guide. Resume playback once without overriding navigation, and protect each playlist’s saved EPG during staggered restores. Previously unseen guide rows still fetch on demand.
- Keep large Sports schedules responsive with background card diffs, cached card IDs, and bounded stream/replay discovery for the focused viewport and followed teams. Retain the complete schedule and league filter.
- Retry visible team logos after temporary failures or queue pressure, cancel retries when paused, replace corrupt Sports image-cache files, and clear empty drawables so fallback logos remain available.
- Put team records beside names, distinguish live/upcoming/final/replay and coverage details with clearer colors, and show available quarter/clock, football possession/down-and-distance, and baseball count/outs from the existing scoreboard. Game-state details follow score delay and spoiler settings.

## 0.30.2 — 2026-11-07

- Correct sustained fractional frame-rate changes during live TV, including 60/59.94 and 30/29.97. Require eight seconds of stable source timing before a small correction so rounded measurements cannot repeatedly switch display modes; preserve VOD protection and playback connections.
- Show dropped video frames, actual display refresh rate, and the requested frame rate in Playback diagnostics. Keep buffer sizes and stall/reconnect policy unchanged.

## 0.30.1 — 2026-11-06

- Add a league filter beside Today, Live and Calendar. Browse one enabled league or all leagues without changing Sports settings; the filter also applies to the calendar.
- Keep team-versus-team logos on every matchup card; reserve editorial photography for the hero. Remove card-photo downloads and whole-grid redraws when hero artwork arrives.
- Keep remote highlights immediate while coalescing hero and background matching work until focus settles. Avoid restyling unchanged badges and refreshing every visible stream label on each card move.

## 0.30.0 — 2026-11-06

- Confirm higher-resolution Sports candidates even after finding a playable lower-resolution feed. Promote newly available candidate guide data without scanning the playlist again, retain ready results during upgrade checks, and recognize Blazers/Trailblazers aliases.
- Give Sports cards one cancellable vertical scroll animation in both directions, retain focus through fast remote repeats, show a glimpse of the next row, and avoid fading unchanged cached crests on refresh.
- Add Sports score delay (0–300 seconds), a default and per-channel overrides. Cards, hero, calendar and Sports overlay use bounded timestamped score/status history; newly enabled delays wait for history instead of exposing newer scores. Fixtures, reminders and video timing stay current.
- Retry temporarily missing live HLS child resources before resetting the source, preserve recovering buffered media, and use heap-scaled byte targets for main/multiview playback. Keep bounded source recovery and add local numeric retry, discontinuity, live-offset and buffering diagnostics.

## 0.29.1 — 2026-11-06

- Reopen a previously playing live channel after a temporary provider 404/410, including the main stream endpoint and direct TS streams. Preserve the existing two-reconnects-per-two-minutes limit and Retry controls for persistent failures; missing VOD, initial missing channels, account errors, and rate limits remain separate.

## 0.29.0 — 2026-11-06

- Add NCAA football (FBS/FCS), men's basketball, women's basketball, and baseball to Sports leagues and followed teams. College games share scores, records, artwork, calendar, reminders, live stream matching, and provider catch-up.
- Request complete Division I scoreboards, including unranked schools, with bounded parallel requests. Bundle the school directory locally; no startup team downloads or stream probing. Preserve saved league preferences.
- Distinguish college sports from pro leagues and men's from women's games. Prefer full school names over overlapping aliases such as Texas/Texas Tech and Washington/Washington State.

## 0.28.5 — 2026-11-06

- Remove speculative category/EPG/image sweeps and automatic VOD catalog downloads during live TV. Library/Search imports VOD on demand; live details check saved titles. Keep persistent logos and load uncached guide rows when needed.
- Stream encrypted EPG cache reads/writes without constructing whole JSON object trees or flattening every programme into temporary arrays. Coalesce snapshots, save less frequently while active, and never overwrite a saved guide with the startup's temporarily empty EPG.
- Batch per-channel EPG updates off the main thread, reuse unchanged lineups, index channel categories, and update only the sidebar's changed selection instead of rebuilding/diffing its whole list on every move.
- Defer automatic bulk refresh until playback has settled and remote input is quiet. Stop repeated archive-metadata refresh attempts for M3U/providers without those flags; remove forced refresh migration. Show blocking guide progress only without saved channels, with background status in playlist settings.

## 0.28.4 — 2026-11-06

- Keep channel logos in an encrypted, size-limited disk cache; decode guide-size thumbnails, retain each visible row's bitmap, and warm nearby category logos one at a time after playback settles. HTTP images still require playlist consent.
- Keep a fair, bounded current/upcoming programme window for oversized XMLTV feeds and playlists with many HD/4K aliases instead of discarding their whole guide. Retain the existing XML/download/memory safety limits and retry the bulk guide once with this policy.
- Warm missing listings for nearby categories and favorites while watching. Foreground rows have priority; speculative guide work uses one metadata request at a time, pauses during buffering, and stays off during VOD and multiview. Build the category look-ahead index off the main thread.

## 0.28.3 — 2026-11-06

- Back moves from guide channels to categories, then to the main menu; Back from that menu returns to live playback. Left remains available for moving between columns.
- Switch the guide's channels as a category is highlighted, with a short delay during fast scrolling and no playback changes. Keep focus in categories, apply the highlighted category immediately on Right/OK, and expand playlist headers only on OK.
- Add Guide browsing preferences for highlight/OK category selection, full-screen/preview-first channel selection, and channel-list looping. Highlight switching is enabled by default; preview-first enters full screen when the playing channel is selected again.

## 0.28.2 — 2026-11-06

- Bound the live-channel VOD availability spinner to eight seconds per programme, keep it current without waiting for provider callbacks, and preserve its deadline when reopening details. Background catalog updates continue and late matches can still appear.
- Distinguish titles absent from a complete library from unfinished imports and lookup failures. Use complete cached catalogs immediately and exclude live-only M3U playlists from VOD completeness checks; show Start VOD only for confirmed matches.

## 0.28.1 — 2026-11-05

- Preserve exact fractional display rates for VOD movies and episodes. Require stable source timing before fractional corrections, prefer precise compatible modes on older TVs, and use the current VOD rate when re-enabling matching. Retain live TV's existing timing policy.
- Keep subtitle appearance shared across live TV and VOD, scale image-based subtitles with the saved size without copying decoded bitmaps, and identify Image tracks whose original lettering cannot be restyled.
- Show measured resolution and FPS in VOD playback controls.

## 0.28.0 — 2026-11-05

- Check known game feeds with advertised archives first, cache archive inventories, return the first verified replay promptly, and preserve progress across unrelated EPG updates. Keep catch-up status stable while scanning and publish confirmed cards without a full Sports redraw.
- Refresh the theme editor with six quick presets, a live preview, and a separate Advanced section retaining every existing color, font, style and guide control. Coalesce rapid adjustments without restarting playback.
- Check for updates on launch and offer Install now or Later once per launch; defer the offer while backgrounded. Use GitHub’s release feed with API fallback and keep checksum/package/signing verification.

## 0.27.5 — 2026-11-05

- Reuse confirmed Sports matches for the active game instead of expiring them every two minutes. Unrelated EPG updates, fresh scores and equivalent lineup snapshots keep the ready card; changed coverage, programme boundaries and playlist changes still invalidate it.
- Open confirmed game cards directly without another provider-coverage check. Prefetch live games only, with room for all visible live matchups, and retain discovered streams when returning from playback.

## 0.27.4 — 2026-11-04

- Cache Xtream providers’ full XMLTV guides in the background after playback settles, so unvisited categories already have programme listings. Preserve Xtream XMLTV IDs without changing existing channel or catch-up identities; upgrade old lineups once.
- Stream bounded plain/gzipped XMLTV without buffering the complete download, retain archive history, and keep per-channel EPG when a provider lacks a usable full guide. Keep large guide caches instead of trimming them to 5,000 channels.
- Default EPG refresh to every six hours when no interval has been configured; preserve explicitly chosen refresh settings. Seed missing data without replacing fresh listings or restarting stopped/on-demand playback in Library.

## 0.27.3 — 2026-11-04

- Combine channel OK-menu actions into one consistent icon row, focused on Sports overlay. Remove manual Reconnect; recent channels use hold OK to add a screen. Keep VOD title/episode details alongside the action row.
- Show today’s final scores after live games in Sports overlay, honoring hidden-final-score preferences. Stop navigation at both ends and hide the overlay immediately over Settings, Sports settings and Calendar.
- A reminder now refreshes the complete scoreboard instead of treating its single event as a recent full refresh; show a small loading indicator while other games arrive.

## 0.27.2 — 2026-11-04

- Render Sports stream and replay results with the same channel rows as the Guide: channel logos, theme spacing and focus, programme titles, and provider/quality details. Hold OK on a live result to add it to multiview.

- Finish Sports searches across every enabled playlist instead of treating the 1.5-second partial scan as a final result. Keep progress advancing through both guide and channel-name checks, avoid normalizing unrelated channel names, and check named team feeds before generic event channels. Exclude radio from game playback.
- Scores stay pinned automatically after opening, with a single close control. Press Up to focus the bar without changing channels; Left/Right browses, OK watches, and Back returns to playback.

## 0.27.1 — 2026-11-02

- Fix Other streams for this game stopping partway through a search when a background scoreboard refresh selects a different game. Keep the active game, hero, search progress and stream choices stable while scores update; retain playback and reuse existing alternatives immediately.

## 0.27.0 — 2026-11-01

- Retain saved games while other leagues refresh, mark unrefreshed live scores as saved, and reject renders for an old Sports tab; avoid temporarily showing only RedZone when returning to Sports.

- Add a compact live-score strip through OK → Scores, with saved top/bottom placement, pinning, followed-team priority and single-player game switching. Share Sports schedule caching and request limits; preserve remote focus and current playback during matching.
- Clicking RedZone autoplays the highest-resolution cached or discovered feed. Hold OK for manual stream choices, also reachable from playback details.

## 0.26.0 — 2026-11-01

- Add a game-position indicator and up/down cues when more Sports cards are off-screen; remove an invisible message row that clipped the cards.
- Add opt-in Sports stream recovery under Playback: switch to a confirmed alternative after 10–30 seconds of sustained buffering, with session limits and one player.
- Exclude obvious movie, series, 24/7 and PBS channels from speculative provider lookups while retaining explicit game matches from any category.

- Show named RedZone feeds immediately while provider-guide discovery continues, retaining remote focus as results arrive. Look for higher-resolution alternatives across NFL/Game Pass and Sky Sports feeds; resolution takes priority over FPS.
- Avoid normalizing unrelated channels during RedZone discovery, reuse discovery when quality readings change, and precompute Game Pass priorities before sorting lookup candidates.

## 0.25.1 — 2026-11-01

- Present cached and arriving Sports schedules without waiting for disk writes, archive capability checks or RedZone scans; keep the NFL RedZone destination in Today and Live.
- Check existing EPG before expensive channel-name matching. Preserve identically named feeds across providers and share lookup budgets between enabled playlists.

## 0.25.0 — 2026-11-01

- Enable several playlists together. Browse collapsible playlist/category sections, search across their live and VOD catalogs, and compare Sports streams and catch-up across providers.
- Keep channel identities, favorites, quality/history, HTTP consent, metadata requests and playback settings tied to the owning provider. Preserve existing caches and account data; support mixed-provider multiview with per-playlist connection limits.
- Share bounded search/import workers, restore cached catalogs progressively, and keep live-channel search off the UI thread.

## 0.24.4 — 2026-11-01

- Show Sports fixtures before archive and RedZone discovery. Replay availability fills in separately instead of blocking the initial page.
- Reuse normalized team/network/channel data during archive searches, reject unrelated programme text without repeated Unicode normalization, and run scoreboard/replay workers at Android background priority to protect playback.

## 0.24.3 — 2026-11-01

- Show overall team records beneath team names on Sports cards and in the hero. Keep scoreboard records in the encrypted schedule cache, omit missing records, and hide completed-game records when spoiler protection is enabled.
- Discover RedZone from channel names, aliases and current NFL/Game Pass guide listings across playlist categories. Cache and display the best match with its quality; explicitly opening RedZone can check a bounded set of missing provider guide listings without probing video streams.
- Keep Settings focus movement lightweight: defer pages skipped during scrolling, render immediately when entering a page, and load/reuse the local-station directory on a background worker.

## 0.24.2 — 2026-11-01

- Deliver game reminders at their scheduled start while Spare is open, without waiting for a delayed live scoreboard flag. Honor updated start times and known postponements, and retain once-only delivery across restarts.
- Keep Watch game full screen with a focused, cancelable progress overlay while the current programme continues. Play an already-confirmed match without waiting to search for a higher-resolution alternative; use the Sports choices screen only when automatic matching cannot find a confirmed feed.

## 0.24.1 — 2026-10-31

- Keep Sports card-focus handling lightweight: debounce background work, validate coverage and rank cached stream summaries on a worker, and apply only completed text updates to visible cards. Avoid copying a full programme map for matches without extra guide data.
- Keep an immutable, nonblocking cache snapshot for instant playback of already validated matches. Send cache misses and changed-guide validation to a worker, preserving the existing provider-lookup limits.
- Reset queued loading labels when background work is canceled, and retain discovered coverage while quality labels refresh.

## 0.24.0 — 2026-10-30

- Share catalog restores/imports between Library and Search; broadcast partial pages to consumers, preserve remaining consumers when one cancels, and keep cached results during refresh.
- Invalidate derived caches and measured quality when a playlist server/account changes. Scope encrypted Watchlist and continue-watching data to the provider identity while retaining existing data for its original account.
- Retain confirmed Sports coverage through unrelated EPG updates, and use session-only playback health to rank repeatedly failing feeds below usable alternatives. No background stream probes.
- Require fresh consecutive frame-rate observations after interruptions; keep VOD-check feedback visible while loading.
- Add bounded, redacted local playback history with buffer, Java heap, dropped-frame and stream-count readings, plus explicit Copy and Clear actions. Keep buffer durations unchanged pending physical-device memory measurements.
- Give Local regions separate Add/Edit/Remove controls; keyboard drafts no longer overwrite saved locations.

## 0.23.1 — 2026-10-30

- Give resumed playback a quiet startup window before optional VOD indexing and automatic playlist/EPG refreshes. Keep explicit search/details and manual refresh available, retain launch refresh requests until playback settles, and leave live/VOD buffer settings unchanged.
- Restore the last measured channel frame rate before preparing playback; settle fresh frame-rate changes and ignore weak half-rate decoder/render hints over a saved rate. Keep source FPS measurement unchanged.
- Limit full-screen guide requests to the current channel, defer off-screen guide prefetch during startup, and run cached catalog/local-channel/index writes at background thread priority.

## 0.23.0 — 2026-10-30

- Show a themed Checking for VOD animation while the catalog index is restoring or loading. Replace it with the matched movie/episode action, or an explicit no-match, paused or unavailable state; do not leave a spinner running after a failed or paused check.
- Match upcoming Sports games against future EPG listings, show scheduled channels on cards and in coverage results, and cache them until kickoff. Keep automatic tuning restricted to live games. Explicit upcoming coverage checks request up to 72 future listings per bounded channel lookup; background cards use saved guide data only.
- Settle the hidden navigation dock before returning from full-screen playback to Guide, and exclude the collapsed dock from automatic focus search to avoid a closing flash.
- Preserve saved Local regions when adding a new one, even if the TV/phone keyboard replaces the draft text. Show saved regions, constrain ambiguous city matches by state, and invalidate old local-match caches.

## 0.22.1 — 2026-10-30

- Name the Watch from beginning target in channel details and its loading overlay: a movie title/year or series title with its exact season/episode. Include the resolved episode title and season/episode in VOD playback controls.

## 0.22.0 — 2026-10-30

- Keep Watch from beginning on its own compact loading overlay while resolving a series episode; start the matching episode without opening Library or moving focus through its search/catalog pages. Cancellation and failed/missing episode choices stay on the overlay.
- Distinguish favorite live channels from a movie/series Watchlist, with explicit save/remove labels and confirmation. Existing saved titles and episode history carry over.
- Move catch-up out of Library into the Guide and channel details, with a dedicated archive browser and no movie/series tabs. Return to the active stream without reconnecting.
- Match Library, search, settings, Sports settings/calendar and playback surfaces to the Guide palette, opacity and layout style. Refresh fonts, focus colors, category/card backgrounds and progress accents when themes change.
- Replace the unfinished recent-channel + View control with a consistent Add screen action and multiview icon. Separate recent-card actions, keep channel details controls scrollable, improve catalog button/card spacing, and move verbose codec/buffer diagnostics into Playback settings.

## 0.21.1 — 2026-10-29

- Give movies and episodes a five-second startup cushion and twelve-second rebuffer cushion while preserving live TV buffering and the existing read-ahead limits. Stop background catalog imports during on-demand playback.
- Monitor on-demand playback for stuck buffering or frozen video, reconnect from the saved episode/movie position with bounded retries, and preserve that position when leaving the app. Allow extra startup time for on-demand files; deliberate pauses and Library browsing never trigger recovery.

## 0.21.0 — 2026-10-29

- Play a fresh confirmed Sports card match immediately instead of discarding it for a second search. Keep a new search for expired, changed or unconfirmed coverage, and preserve playback fallback choices.
- Recheck in-flight coverage when relevant guide data changes or a new matching programme arrives; unrelated guide updates no longer keep an unresolved search restarting.
- Keep the active live or on-demand stream when leaving the Library browser. Back or Stop during a movie/episode stops it and returns to Library without automatically tuning an older live channel; series return to their episode list.
- Add encrypted Library favorites for movies and series, poster/header favorite controls, watched episode history, new/unwatched episode badges and Mark all watched. Check saved series in bounded metadata-only background requests when Favorites opens.

## 0.20.3 — 2026-10-29

- Restore and build one compact encrypted VOD title index in the background, shared by Library, search and live programme matching. Browse/search cached titles without decrypting individual catalog pages, and show Watch VOD from beginning only for known catalog matches. Suppress files that returned 404 for one hour.
- Refresh legacy catch-up capabilities from per-channel provider flags. A retention duration alone cannot enable catch-up; suppress stale capability badges until current metadata is available.
- Check higher-resolution event feeds even after finding a lower-resolution game, include generic UHD channels in bounded coverage checks, and invalidate cached results when another channel gains matching EPG data. Keep pending higher-resolution lookups when a lower-quality reply arrives.
- Poll due reminders independently of Sports page loading, with a 30-second scoreboard cache/check interval. Reminder playback uses the same refreshed coverage search.

## 0.20.2 — 2026-10-29

- Rebuild Watch from beginning as a dedicated, calm TV overlay. Start a clear movie or episode match directly; show at most four matching release choices, with no mixed live-channel or unrelated-title list. Search only movies or only series, and check remembered titles before catalog pages.
- Start a clear VOD movie or numbered episode match as soon as it arrives, without waiting for unrelated catalog downloads. Keep cached searches busy until their reader finishes, and let a valid match play even if another catalog fails.
- Match DV, Dolby Vision, HDR and codec labels as quality variants; tolerate missing provider years while keeping conflicting known releases separate. Selecting a movie from the live-to-VOD results now starts it from the beginning directly.
- Save encrypted partial catalog indexes during long imports so interrupted searches can reuse titles after an app restart instead of starting empty.

## 0.20.1 — 2026-10-28

- Keep playlist search focus inside its results and stop delayed guide navigation from taking over the remote. Use cached live-channel search keys instead of repeatedly normalizing the full lineup on the UI thread.
- Batch encrypted VOD catalog writes, index title keys to skip unrelated pages, reuse the full catalog when browsing categories, and keep metadata imports through search query changes and dismissal. Publish usable results while imports continue.
- Restore primary-player audio and audio-track selection when starting a movie or episode, handle audio focus, and honor provider file extensions and permitted direct playback URLs without probing alternate movie formats.

## 0.20.0 — 2026-10-28

- Add Watch VOD from beginning to live channel details. Play unique movie matches and explicitly identified episodes from zero; offer choices for remakes, ambiguous titles or episodes the guide does not identify. Channel catch-up stays separate.
- Mark provider-advertised catch-up channels in the guide and count the full playlist inventory in Library. Search archives beyond the broadcaster shortlist, support keyed EPG responses and the legacy Xtream archive endpoint, and share browsed archive history with Sports and the saved guide.
- Use one native playlist search from Guide and Library, with live channels, movies, series and known episode results. Show live matches immediately; load encrypted movie/series catalogs on explicit search and reuse them for six hours. No video probes or bulk episode requests.
- Combine up to eight cities/states in Local, with autocomplete for additional regions and duplicate stations removed.
- Remove the redundant Nostalgia shortcut from the theme editor; retain Nostalgia in the style picker.

## 0.19.5 — 2026-10-28

- Process Sports guide replies as they arrive and stop pending metadata requests once a game match is confirmed. Preserve completed replies at the timeout boundary while keeping the existing two-request concurrency and eight-channel budget.
- Keep completed searches through unrelated guide refreshes, preserve historical programmes when merging lookup data, and prevent those merges from invalidating their own cached match.
- Bound saved replay data to the reader's actual UTF-8 byte limit, prioritize recent games, and reuse channel identity hashes during save/restore.

## 0.19.4 — 2026-10-28

- Refresh local game coverage when Watch Best or a reminder starts playback, rather than reusing a previously inferred broadcaster list. Require current game evidence before automatic broadcaster tuning and exclude known conflicting programmes.
- Include a bounded, varied selection of league/event channels when current guide data needs confirmation. Preserve 720p, lower FPS and unknown-quality game matches; rank resolution and FPS only after matching the game.

## 0.19.3 — 2026-10-28

- Preserve historical programmes through each channel's catch-up window when live guide data refreshes, keeping current and upcoming listings available within bounded storage.
- Keep confirmed Sports replay matches until their provider archive expires instead of dropping them after ten minutes. Save bounded replay references in encrypted playlist storage and restore them across app restarts, validating current channel identity and retention.

## 0.19.2 — 2026-10-28

- Keep discovered Sports card channels visible when returning to Sports and while refreshing expired match data. Show Finding stream only for cards without a valid saved match; preserve labels when cancelling a quiet refresh.
- Retain playlist, relevant-guide and game/programme-boundary invalidation. Automatic playback still refreshes expired evidence; background card discovery opens no video connections.

## 0.19.1 — 2026-10-28

- Give transient provider reads Media3's normal bounded retries instead of escalating after one retry, and allow longer quiet intervals on live HTTP connections before declaring a timeout. Keep account/rate-limit errors and stalled-playback recovery bounded.
- Let HLS honor provider segment timing and hold-back instead of forcing a 12-second live offset. Preserve normal playback speed and existing startup/rebuffer thresholds.
- Move decoder metadata reads and quality/display updates out of the per-frame hot path. Add a local channel-details line for transport, buffered seconds, stalls, load errors and reconnects without exposing provider URLs.
- Publish Android updates independently of optional Mac packaging so an unavailable Mac runner cannot block a checked, signed APK.

## 0.19.0 — 2026-10-28

- Add a native macOS preview for Apple Silicon and Intel: M3U/Xtream playlist setup, live playback, cached guide, categories, favorites, search, audio/subtitle tracks and previous-channel controls.
- Add desktop Sports schedules, live/final scores, followed teams, league preferences, score hiding and playlist stream matching ordered by coverage and resolution. Unknown FPS remains playable; no background stream probes.
- Save Mac credentials in Keychain and encrypt lineup/guide caches. Package the VLC runtime, source/license notices and Mac download alongside the Android APK in GitHub Releases.
- Preserve Android TV playback and feature behavior. The Mac preview uses manual release downloads and is ad-hoc signed pending Developer ID setup.

## 0.18.2 — 2026-10-28

- Discover catch-up across completed Sports cards beyond the first eight, progressively check more archive variants, reuse exact-channel histories and retry negative results after guide updates. Keep replay badges for the same lifetime as their discovery cache and separate histories for channels sharing XMLTV IDs.
- Exclude 24/7 collections and ambiguous station-name matches from Local results. Warm Local matches as the playlist loads, save an encrypted cache tied to location and lineup content, and show an animated indicator while matching.
- Recover expired live HLS segments through the existing bounded reconnect path instead of treating a missing child segment as a missing channel. Preserve buffer sizes, retry limits and account-error handling.

## 0.18.1 — 2026-10-28

- Rank Sports live, RedZone and catch-up streams by resolution first; use known/preferred FPS only between streams at the same resolution. Preserve game coverage, archive availability and lower-quality fallbacks.
- Let Watch Best play the highest-resolution match immediately when FPS is unknown. Accept working playback without an FPS trial or quality-driven retune; learn and cache FPS through the existing player, with no background stream probes.
- Clarify Sports quality settings and metadata counts.

## 0.18.0 — 2026-10-28

- Add a live theme editor with a persistent guide preview, compact Look/Colors/Type/Guide tabs, custom colors, fonts, corner/border controls, density, spacing, opacity and video preview options. Save changes automatically without restarting playback.
- Group frame matching, tracks, subtitles, stream format and recovery controls under Playback. Replace duplicate track dialogs with the native settings surface; streamline Guide and Library navigation.
- Add inline automatic Local city/state suggestions and matching, more guide header/row padding, a trophy Sports icon and automatic Sports refresh.
- Treat Sports quality consistently as preferences across live results and RedZone, preserve all playable variants, label provider versus observed evidence accurately, and stop guessing provider frame cadence or video aspect ratio.
- Discover catch-up from saved guide data and bounded background provider metadata across archive-capable variants, independently of the live pick. Cache archive checks and show checking/confirmed/not-found states without hiding fixtures.
- Fix recovery timing after buffering and restore first-frame timeouts after pause/resume; preserve the existing buffer and bounded reconnect policy. Reduce repeated font/poster binding work and remove unused duplicate controls.

## 0.17.2 — 2026-10-27

- Keep live-stall monitoring, reconnect and startup callbacks active when opening other game streams, including when returning to the current channel without selecting an alternative.

## 0.17.1 — 2026-10-27

- Add Other streams for this game to the OK details panel, focused first for recognized games and available during buffering. Reuse discovered matches, preserve game context after manual selection or reconnect, and open selected alternatives full screen.
- Use persistent green Live pills and blue Catch-up badges on confirmed replay cards, show the replay channel/quality, and distinguish unconfirmed archive support. Replay lookup includes lower-quality archive variants independently of the live pick.
- Offer game alternatives from the playback error panel for manually selected Sports streams too. Recognize current-guide matchups from EPG or channel titles and find their streams on demand.

## 0.17.0 — 2026-10-27

- Recover sustained live playback stalls with per-attempt progress monitoring, restore recovery after pause/resume and first frames, and keep reconnects bounded. Add Reconnect to channel details while preserving short-buffer recovery and existing buffer sizes.
- Add a Local guide tab and Settings → Local channels for a U.S. city/state. Match your playlist offline using station call signs and explicit provider location labels; include a small attributed historical FCC reference, with no GPS or location requests.
- Reuse Sports card summaries during focus changes and compute new summaries off the UI thread. Show Play replay directly on final-game cards for confirmed, unexpired catch-up matches; other eligible cards go straight to finding replays.
- Treat Sports quality as a preference: prefer FHD/60, then fall back to lower-resolution, lower-FPS or unverified matches. Play a single fallback immediately or offer a chooser; keep working playback instead of rejecting its measured quality. Selecting a guide channel opens full screen.
- Add guide background opacity percentages under Guide video, reserve space while the hidden dock is open or closing, and move the TV graphic after spare on the Android TV launcher banner and icon.

## 0.16.2 — 2026-10-27

- Let background Sports matching finish its local playlist pass instead of discarding large lineups after 1.5 seconds. Show checked-channel progress and preserve cancellation when tuning or leaving Sports.
- Keep completed matches across unrelated EPG updates and retry when relevant coverage changes during a search. Show found streams separately from verified automatic-play quality so missing quality data does not hide discovery.

## 0.16.1 — 2026-10-27

- Move resolution and FPS preferences into Sports settings → Stream quality, preserving saved per-playlist choices. Keep all qualities visible in the main guide, Favorites and search.
- Keep category visibility separate, remove the obsolete guide quality pause controls, and change Sports quality without retuning playback or resetting the guide selection.
- Fix Sports cards stuck on Finding stream: keep completed matches when quality changes, show loading only during active work, and defer unfinished searches to selection. Prepare cards from saved data without provider requests and reuse those results when confirming coverage.
- Cancel background matching before channel changes and bound stalled live reads to 10 seconds instead of 30, preserving the playback buffer and retry policy.

## 0.16.0 — 2026-10-27

- Prepare live-game channel matches in the background and cache them for the active app session. Prioritize focused cards, pause work during buffering or multiview, and cancel it when leaving Sports.
- Show matched channel, resolution and FPS evidence on Sports cards without moving focus or rebuilding the grid.
- Prefer known FHD+/60 streams; automatically try FHD/4K coverage with unknown FPS and measure it during playback. Keep strict guide filters and exclude known lower rates from automatic selection.
- Try up to three game streams after failed starts or measured lower quality, then show alternatives. Preserve account/authentication errors and offer Other game streams for later failures.
- Prioritize provider quality before bounding broadcaster variants so a known 60 FPS channel is less likely to be omitted.

## 0.15.1 — 2026-10-27

- Add an optional Sports setting to find and play the best known FHD+/60 stream when selecting a live game card, including calendar games. Show stream results if no suitable match is found.
- Keep reminder and replay actions for upcoming and completed games, and preserve manual stream selection when adding a multiview pane. Reset Sports restores automatic playback to off.

## 0.15.0 — 2026-10-27

- Show games beside the Sports calendar, including local start times, live/final states, saved-score preferences and counts on loaded dates. Open game actions directly from the calendar.
- Load highlighted dates after navigation settles, reuse cached schedules, reject stale responses and keep playback running under the overlay.
- Let Watch Best try a known FHD+/60 advertised-broadcaster stream when no exact game listing exists, clearly label unconfirmed coverage and reject guide entries for other games or replays.
- Open selected Sports stream results full screen; Back returns to the results. Show fallback explanations prominently and use Find streams for stale live status.

## 0.14.3 — 2026-10-27

- Let Watch Best use the same explicit provider or playback FHD+/60 evidence as guide filters, including 4K/59.94; keep measured quality authoritative.
- Explain missing FPS separately from unconfirmed game coverage in the stream results.
- Decode Sports card logos at higher resolution with smooth scaling.
- Replace the date dialog with a full-screen translucent month calendar, remote navigation, month controls and Today.

## 0.14.2 — 2026-10-27

- Keep Sports focus on the latest remote action, prioritize focused-game artwork and cancel obsolete queued artwork work.
- Retry artwork skipped while paused or busy, and clear completed Sports placeholders even when the page is hidden.
- Preserve Library detail focus, reject stale backdrops, return M3U movies to their catalog and make episode Refresh fetch fresh data.
- Cache numeric row IDs, remove duplicate channel scans and batch quality saves to reduce scrolling and playback overhead.
- Refresh guide time labels with programme rows; clear removed-playlist playback/resume state and stale background refresh results.

## 0.14.1 — 2026-10-26

- Open Sports on Today, add Live across enabled leagues and keep Calendar for other dates. Live includes unfollowed games and excludes replay/upcoming cards and unconfirmed RedZone status.
- Hold the first Sports layout behind fixed card placeholders, reveal the completed scoreboard together and keep existing cards stable during refresh.
- Add a confirmed reset of Sports teams, leagues, reminders and preferences.
- Preserve provider resolution during incomplete playback readings, accept more explicit provider FPS metadata, recover cached label declarations and show known/unknown quality counts.

## 0.14.0 — 2026-10-26

- Rebuild Sports as a card grid with a focus-driven hero and fixed loading layout. Remove the Sports sidebar; configure leagues, followed teams, reminders and spoiler preferences in full-screen Sports settings.
- Open live, replay and reminder actions only after selecting a game. Keep replay results free of duplicate search buttons and add an NFL RedZone playlist destination without inventing live status.
- Replace both Android TV launcher banner and icon with a bespoke pixel wordmark and vintage TV, with an opaque background beyond all edges.

## 0.13.1 — 2026-10-26

- Keep the Sports dock closed during incidental focus restoration; recover the previous Sports control and require deliberate Left navigation to enter the dock. Up at the first league stays in Sports.
- Move completed-score visibility entirely to Settings → Sports. Hide final scores by default when no explicit preference exists, and remove in-card Hide/Reveal buttons.

## 0.13.0 — 2026-10-26

- Find completed Sports games in provider catch-up, using historical EPG and per-channel replay windows. Show replay expiry, bound provider lookups, and return Back to the selected Sports game.
- Add persistent completed-score hiding, per-game reveal/hide controls, and spoiler-safe Sports cards, hero details and artwork. Live scores remain visible.

## 0.12.3 — 2026-10-26

- Let Right enter Sports game cards from either left menu when the featured game has no enabled actions. Let Up return to leagues instead of trapping focus on completed games.
- Keep focus on visible stream controls when a game search has no matches.

## 0.12.2 — 2026-10-26

- Bound decoded artwork dimensions and deliver images directly to their card bindings. Avoid whole-rail image callbacks and repeated decoding after cache eviction that can overwhelm Sports navigation.
- Pause playback and Sports image work immediately when leaving the app; stop the player before background cleanup. Resume playback when returning.
- Exclude the dock from automatic Sports focus search without blocking deliberate remote navigation into it.

## 0.12.1 — 2026-10-26

- Stabilize fractional FPS readings without losing real channel/frame-rate changes.
- Speed up game coverage lookup with target-team prefiltering, independent search workers, bounded EPG confirmation and visible search/tuning progress. Include live event-channel title matches without EPG.
- Add selected leagues and full-screen team management using the Settings layout.
- Enter hero actions directly from league navigation; retain horizontal card focus while scrolling and prevent accidental dock focus.

## 0.12.0 — 2026-10-26

- Add saved game reminders, in-player live-game alerts, and one-click Watch best for confirmed FHD/60 fps coverage. Keep unmatched or unverified streams available for review.
- Measure video sample timestamps before decoding for AVC/HEVC and other video codecs, with B-frame reordering support. Feed this source rate to quality filters instead of allowing decoder/display cadence to undercount it.
- Show input and display sample rates separately in channel details. Remeasure older playback FPS caches while retaining provider metadata and last-channel resume.

## 0.11.0 — 2026-10-26

- Synchronize encrypted file access across refresh jobs and app instances to keep source saves atomic.
- Open saved channels before loading EPG; keep encrypted lineup and guide caches independently. Refresh without restarting streams or resetting guide navigation.
- Add per-playlist launch and interval schedules for channels and EPG, quiet progress, last-success status and retry controls. Use native Android background jobs; keep usable data on failures.
- Share a bounded disk cache across Sports hero images and thumbnails, reuse pending artwork requests, retry transient failures and prefetch nearby matchups.
- Add dated game cards, Tomorrow/date browsing and NFL hero priority for live or tonight’s games. Preserve card focus across score/art updates and return Sports home to Today.
- Label confirmed versus possible coverage and provider versus playback quality; preserve Multiview’s retained surfaces and selected audio.

## 0.10.2 — 2026-10-26

- Replace Android TV launcher art with a clean Spare wordmark. Add an optional Nostalgia preset with phosphor colors, square borders, a full guide grid and monospace type.
- Open Sports on Today across supported leagues, independent of followed teams. Request explicit daily schedules and add illustrated game cards while retaining All games and My teams.
- Keep Multiview video surfaces attached and reuse pane controls when returning from browsing; avoid an intermediate full-screen resize. Label recent-channel additions as + View.

## 0.10.1 — 2026-10-26

- Add persistent frame-rate matching On/Off controls without restarting playback.
- Open Multiview directly and add recent-channel logo tiles with separate watch/add actions in playback details.
- Restore remote access to Library tabs after the navigation dock opens; keep asynchronous artwork updates from moving focus.
- Fill missing movie/series posters from optional AIO Metadata, cache artwork locally, and show series art beside episode details. Improve title matching and metadata retry controls.
- Give Sports larger layered team artwork, reject nested cast portraits as programme art, and remove unused scoreboard work.

## 0.10.0 — 2026-10-26

- Rebuild Sports around league navigation, featured game artwork, live/upcoming/final status and scores, and a remote-friendly game rail. Add NBA, NHL, Premier League, MLS and Champions League.
- Prioritize followed teams live, then their next game. Keep scoreboards independent of playlist matching and fetch artwork only for the focused game.
- Add explicit Watch together actions, two-up/four-up layouts, focus-selected audio, and game/channel picking. Release additional streams when closed or backgrounded.
- Show live-programme hero artwork from XMLTV/Xtream metadata, or optional AIO Metadata matches, with a channel-logo fallback.

## 0.9.3 — 2026-10-26

- Smooth the hidden dock with a cached slide, retarget interrupted movement, and reveal its handle after closing completes.
- Add seven color palettes, Soft/Outline/Cinema styles, and six offline device font choices; refresh the dock appearance with the selected theme.

## 0.9.2 — 2026-10-26

- Right from the dock returns to current content; only OK opens a destination. Add hidden or always-open navigation with a smooth overlay that keeps content layout steady.
- Give Sports Scores the full screen area; keep the live preview when finding and watching streams.
- Keep Library category focus and loaded poster pages stable during imports, reject stale category callbacks, and make manual refresh apply once.

## 0.9.1 — 2026-10-26

- Replace mixed controls with a uniform, fixed navigation rail: Search, Guide, Sports, Library, Settings. Preserve the selected category when moving between columns.
- Keep Settings open while changing options and make Left move one level per press. Settings also owns input when Library is underneath.
- Use one continuously retargeted scroll controller and retain nearby rows to reduce fast-scroll stutter and focus flashing.
- Back from per-channel Catch-up returns directly to the live channel without reopening an already playing stream.

## 0.9.0 — 2026-10-25

- Load and cache VOD by category instead of downloading the entire library before browsing.
- Separate Sports Scores and Watch, with a team sidebar, inline stream matches, sharper logos and optional two-stream multiview.
- Find FHD/60+ across the playlist without stale search/category restrictions; align resolution filtering with displayed video height.
- Wrap live channel lists, hold focus while offscreen rows attach, jump directly from categories to main controls, and add Glass, Flat and Grid styles.
- Add subtitle size/style controls with a smaller default; increase playback buffer headroom and wait longer before reconnecting a stalled stream.

## 0.8.5 — 2026-10-25

- Separate fast push/PR checks from explicitly requested signed releases. Cancel obsolete check runs and retain TV/update-install verification once a release is requested. The public APK remains 0.8.4 until the next release request.

## 0.8.4 — 2026-10-25

- Resume the saved channel when its FPS needs rechecking after a quality-cache migration, so strict FHD/FPS filters cannot prevent verification. Keep category, resolution and unavailable-channel restrictions intact.

## 0.8.3 — 2026-10-25

- Read AVC SPS timing and decoder headers; version old quality readings and synchronize live quality updates so details and FHD/FPS filters agree. Preserve progressive frames versus interlaced field-rate labels.
- Stream VOD catalogs into bounded encrypted pages with a small local index, show the first page before the full download, and keep imports independent of browsing and playback.
- Preserve Library focus and category positions, append pages without clearing the grid, and deliver provider details before optional metadata enrichment.

## 0.8.2 — 2026-10-25

- Measure timestamp cadence over a stable sample window instead of one median interval, correctly handling millisecond-rounded 60/59.94 and 30/29.97 fps timestamps.
- Prefer valid stream/decoder FPS metadata over render callbacks that can omit frames. Detect MPEG-2 interlaced sequence flags and raw H.264 SPS alongside Annex B headers; retain progressive 30 fps.
- Show codec, source, decoder and timestamp FPS in channel details so a remaining provider-specific discrepancy can be reported without credentials or URLs.
- Keep release checks deterministic across voice-service restarts and avoid decoding large fixture caches on the UI thread.

## 0.8.1 — 2026-10-25

- Make guide and Library search ignore spacing, punctuation and provider text styling, so KING 5 also finds KING5.
- Reduce the README to key features, remote controls and installation.
- Reuse cached quality readings directly and avoid repeated metadata parsing for large unlabelled lineups.

## 0.8.0 — 2026-10-25

- Return directly to live TV with Back from on-demand playback; reopen Library at the cached catalog and pause the movie while browsing.
- Replace large Library category/search diffs on the UI thread with linear filtering and a visible-row refresh; defer focus until pending adapter changes settle.
- Restore the previous Media3 HTTP transport for live streams while retaining consent-aware VOD redirects. Avoid whole-playlist refiltering for ordinary FPS updates, save observations off the UI thread, and suppress tiny refresh-rate fluctuations.
- Pause queued artwork downloads when Library/Sports is hidden.
- Import provider-reported stream dimensions/FPS and explicit quality labels for immediate filtering. Prefer observed playback, hide unknown quality, and add a direct FHD+ / 60 fps preset.
- Accept the feed’s minute-precision timestamps and replace live-only Sports with NFL current-week and MLB daily scoreboards, including team logos, scores, final/live/scheduled status, and local start times. Match playlist channels only when a game is selected; label broadcaster-only matches as possible coverage.

## 0.7.0 — 2026-10-25

- Follow bounded HTTP redirects explicitly for provider media, preserving byte ranges/resume and checking source HTTP consent before every hop. Report blocked HTTP downgrades clearly and strip sensitive headers when origins change.
- Give guide/category lists a single scrolling owner and center the focused row as navigation advances. Make Library reachable from Sports, and define explicit navigation between Sports controls, team selectors and results.
- Build Sports from external MLB/NFL schedules instead of waiting for a provider EPG scan. All live shows confirmed games even without matched channels; each followed team shows its live or next game. Cache schedules, show incremental results, and distinguish EPG-confirmed channels from possible broadcaster matches.
- Show cached/in-memory Library catalogs immediately, fetch titles before optional categories, and move catalog writes off the first-render path. Add poster grids, movie detail pages, provider artwork/descriptions and episode thumbnails/details.
- Add optional encrypted AIO Metadata configuration for movie/series and identifiable live-programme enrichment. Use provider IDs or conservative exact title/year matching, preserve provider playback, and keep optional enrichment off the playback path.
- Prefer measured frame cadence over misleading declared FPS. Recognize H.264 interlaced sequence information, label field cadence explicitly, and use the corrected cadence for filters and refresh-rate requests while preserving progressive 30 fps.

## 0.6.0 — 2026-10-25

- Verify current MLB/NFL provider matchups against ESPN live event state. Hide future, delayed, suspended, finished, stale, and unverified games; retain progress and manual retry without sending playlist data to the sports service.
- Add a built-in font picker in Settings and Theme: Default, Compact, Casual, Serif, and Monospace. Apply fonts across native UI and canvas guide rows without restarting playback.
- Add a separate, virtualized Library for provider movies, series, seasons/episodes, categories, text search, and Continue watching. Load Xtream catalogs on demand with encrypted caches; recognize explicitly declared M3U media entries.
- Add advertised Xtream and M3U catch-up archives, available from the Library and channel details, with bounded archive history and completed-programme selection.
- Reuse the same player for on-demand playback with pause, seek, start over, audio/subtitles, next episode, encrypted per-playlist resume positions, and explicit Library/Live TV return. Keep live channel history and recovery rules separate.

## 0.5.0 — 2026-10-25

- Replace the Settings menu with a full-screen translucent two-pane surface: scroll settings on the left, change values on the right, and keep the existing player running behind it.
- Show live Sports only, with All live and followed-team selectors. Reuse current EPG, reduce Sports requests to four listings per channel across at most 64 prioritized networks, and show animated progress while results arrive incrementally. Prevent continuous responses from postponing the render.
- Keep rapid category navigation anchored to one pending focus target; stop held Up at the first channel instead of cascading into top controls. Disable scroll-edge effects and redundant boundary focus changes.
- Update Android TV launcher and application icon/banner declarations. The pixel TV is the S, followed by PARE, forming one SPARE wordmark on the home-screen tile.

## 0.4.1 — 2026-10-25

- Make future guide browsing independent for each channel, with Left to rewind, Back to return to live, and reachable Now controls. Add a persistent 12/24-hour time preference.
- Clear search on category/Favorites selection, redraw favorite stars and EPG in place without stealing focus, with dark favorite-star focus contrast, and confirm additions/removals. Preserve sidebar focus with targeted updates and smooth offscreen scrolling; stop repeated Left on Search from bouncing focus.
- Separate Guide/Sports navigation from provider categories. Broaden league-aware matchup matching, request deeper bounded sports EPG, diversify network warmup, distinguish filtered/missing listings, and offer labeled team-channel fallbacks. Refresh XMLTV without retuning.
- Keep Xtream short EPG keyed by stream instead of shared XMLTV IDs; migrate only unambiguous cached guide entries and preserve explicit XMLTV overrides.
- Replace the channel-change text block with a compact translucent programme card, subtle edges and shadows. Add an original minimal pixel-art TV icon and matching launcher banner.

## 0.4.0 — 2026-10-25

- Add a Sports section beside Favorites: follow MLB/NFL teams, discover live/upcoming matchups in the provider guide, and choose among matching channels while keeping the live preview running.
- Group alternate broadcasts, preserve separate doubleheaders, prioritize favorite channels, and exclude ambiguous team names, replay/highlight listings, expired games, and games more than 48 hours away.
- Reuse cached EPG and warm at most 160 likely sports/favorite channels with the existing two guide workers. Pause Sports guide requests on account/rate-limit errors or repeated failures. Respect category, observed-quality, and unavailable-channel filters; never probe video streams.
- Keep a channel’s verified FPS when a same-size HLS/caption restart temporarily supplies no frame-rate metadata; replace it when a new valid reading arrives, and remeasure after resolution changes.
- Move focus to already-visible channel/category/game rows synchronously so rapid direction-then-OK input cannot activate the previous row; retain bounded layout waits for off-screen rows.
- Keep team preferences on-device, support all-games browsing, and retain direct sidebar/toolbar navigation and per-channel captions from 0.3.1.

## 0.3.1 — 2026-10-24

- Jump left from any category to Search/Settings, return down to the same category and scroll position, and keep vertical category navigation inside the list. Menu opens Settings from guide or playback.
- Expose CC1/CEA-608 and Digital 1/CEA-708 compatibility readers when transport-stream caption descriptors are absent, including HLS masters that omit captions or incorrectly declare NONE. Preserve actual provider descriptors and separate subtitle tracks.
- Make Automatic select supported subtitles even without matching language/default metadata, and try the two unlabeled compatibility services until captions appear, with a bounded search that never retunes the video.
- Save per-playlist/channel subtitle choices, keep an app-wide default, and expose Subtitles in channel details. Validate actual embedded caption rendering, per-channel persistence, and deep-sidebar navigation.

## 0.3.0 — 2026-10-24

- Keep new channel selections in the guide preview, retain explicit full-screen actions, and keep vertical remote navigation in the channel list across off-screen rows.
- Replace the external speech activity with native microphone permission and inline recognition, cancellation, timeout, and editable fallback.
- Add visible-category presets/custom selection and five guide color themes without restarting playback. Explain temporarily hidden channels and subtitle availability.
- Persist automatic captions across channels, inspect HLS segments for undeclared captions when enabled, and keep explicit track selection.
- Install verified updates through native PackageInstaller sessions, requesting confirmation-free self-updates on Android 12+ with system-confirmation fallback.
- Replace channel-opening/buffering text with a thin progress bar, reduce initial buffer threshold from 2.5 to 1.5 seconds while retaining the larger rebuffer cushion, and make media-timestamp FPS measurement tolerate duplicate/skipped frames.

## 0.2.3 — 2026-10-24

- Reduce guide controls to search/settings icons above the categories. Move Playlists, updates, and temporary filter bypass into Settings; remove My guide/Browse all buttons.
- Enlarge live preview to 320×180 dp with space for programme details; make preview the default for unset preferences.
- Show FPS in channel details, with bounded media-timestamp measurement when the stream omits frame-rate metadata.
- Resume cached playlists quietly, refresh lineups at most every six hours unless requested, and prefetch nearby/favorite EPG with two workers. Preserve merged guide snapshots through serialized encrypted writes and flush on exit.

## 0.2.2 — 2026-10-24

- Replace the search dialog with an inline guide search strip, live channel results, immediate TV voice input, and editable text fallback. Canceling voice input keeps the search usable.
- Show a compact programme/channel details panel on OK while watching, with guide, previous-channel, and favorite actions. Keep Back-to-guide and hold-Back behavior.
- Add single-press Left to toggle between the last two successfully played channels. Preserve source-local history across restarts and keep failed tune attempts out of it.
- Preserve Xtream category-array order in the encrypted guide cache and M3U group first-appearance order. Default to provider order; offer stable USA-first ordering in Settings.
- Extend remote, search/voice-result, details, history, category-order, and cache regression coverage.

## 0.2.1 — 2026-10-24

- Separate startup, buffering, alternate-format, and reconnect status. Add a delayed corner buffering indicator, a larger resume cushion, a bounded buffer target, a conservative adaptive-live offset, and bounded reconnects.
- Stop retrying terminal HTTP errors. Temporarily hide channels only when their attempted startup endpoints all return 404/410; retain network/account failures and restore hidden channels after 15 minutes or on demand. Return to the last working channel when available.
- Put general controls above the guide and order USA categories first. Preserve the active category when returning to the sidebar.
- Offer transparent overlay and small live preview in Settings. Keep one player running while browsing; hold Back or click the current channel to expand without reconnecting.
- Replace the sports preset and 60-only toggle with FPS exclusions, grouping fractional broadcast rates with their nominal rates. Preserve existing 60-only preferences during migration.
- Load bounded per-channel Xtream EPG for visible channels and the current stream instead of requiring a whole-provider XMLTV download. Decode programme text, handle missing EPG IDs, cache data, and explain guide failures. Accept additional XMLTV time formats without relaxing entity or size protections.
- Extend regression coverage for actual network stalls, 404 versus account failures, preview rendering, hold-Back, guide modes, category ordering, FPS exclusions, and EPG responses.

## 0.2.0 — 2026-10-24

- Add a compact translucent guide over live video, provider categories and Favorites on the left, smaller text, separated controls, and Back-to-guide navigation.
- Add per-source category, HD/FHD/4K minimum-resolution, and 59.94/60 fps filters, plus an MLB/NFL FHD+60 preset. Quality is observed during playback; unknown streams stay hidden under quality filters. Browse all temporarily bypasses preferences for verification without background scanning.
- Enable MPEG-TS access-unit detection and non-IDR startup compatibility, decoder fallback, consent-aware cross-protocol stream redirects, Xtream TS/HLS fallback, and an explicit stream-format setting. Replace endless startup with a timeout and safe playback diagnostics.
- Show playlist/guide import stages and measured download progress. Preserve one player while browsing and retain per-source quality observations locally.
- Extend unit and isolated Android TV coverage for quality filters, encoded stream URLs, MPEG-TS without access-unit delimiters, HLS fallback, progress, categories, and remote navigation.

## 0.1.2 — 2026-10-24

- Fix large provider imports: replace the 20,000-entry rejection with a bounded 100,000-entry channel limit, and explain that limit explicitly when exceeded.
- Skip malformed Xtream entries while preserving usable channels and provider order.
- Preserve the entered server URL, including its trailing slash, in source settings; normalize only when constructing requests.
- Give remaining data, interrupted-connection, and HTTP-protocol failures distinct reasons without exposing provider URLs or credentials.
- Add regression coverage for large lists, malformed entries, slash handling, and a full 25,001-channel import through video playback on Android TV.

## 0.1.1 — 2026-10-24

- Refresh the guide with clearer programme hierarchy, quieter dark controls, rounded channel rows, and a persistent remote-control hint.
- Make dialog buttons, text fields, checkboxes, and source-type choices visibly focused; show only fields relevant to the selected source type.
- Keep passwords masked after configuring single-line TV text entry.
- Keep HTTPS addresses intact when enabling HTTP compatibility, display the connection type, and normalize pasted Xtream API suffixes.
- Load live channels even when the optional Xtream category endpoint fails. Explain HTTP, authentication, DNS, timeout, certificate, and response-format failures without showing credentials; offer Edit source and Retry.
- Let prerelease builds discover signed prerelease updates through GitHub Releases while stable builds retain the stable channel.
- Add source-diagnostic, release-discovery, remote-focus, HTTPS-preservation, and provider-category regression coverage.

## 0.1.0 — 2026-10-24

- Initial native Android TV live-TV player with M3U/Xtream sources, XMLTV guide, preview, Favorites, groups, search, last-channel resume, and audio/subtitle controls.
- Add guarded frame-rate matching, encrypted source/cache storage, bounded imports, per-source HTTP consent, and verified in-app APK updates.
- Add versioned builds, tests, lint, release-signing automation, and a physical-device validation checklist.
- Make the tested API 36 baseline explicit across local and CI lint, and sign the exact optimized APK produced by build checks.
- Wait for guide focus and hold the remote key beyond the long-press threshold in device checks; retain failure diagnostics from CI.
- Initialize the Android SDK explicitly in the independent APK-signing job.

Physical onn/Samsung frame-rate and provider compatibility remain to be validated before promoting the initial prerelease to stable.
