# Sports and Library loading: measurements and plan

Written 2026-11-16, updated 2026-11-18. All timings are from the second onn 4K Pro (Android 14) with a 15,800-channel
playlist and a 32,493-movie Library, read from the app's own performance report (Settings › About Spare).

## Sports: first open after the app starts

| | 0.51.14 | 0.51.15 | 0.51.30 (three cold starts, 2026-11-18) |
|---|---|---|---|
| Saved schedule read | | 1.9 s | 0.2 s |
| Cards shown | 7.0 s | 2.3 s | 0.4 s |
| Last crest loaded | 16.1 s | 3.4 s | 1.7 s (one run had live games) |
| All scoreboard replies in | | | 2.5 to 3.1 s |
| Card preparation, each time | 1.5 to 6.8 s | 1 to 136 ms | 0 to 17 ms |
| Guide read for other live sports | inside every preparation | 8.1 s, once, off the card path | 110 ms, at 4.4 s |

0.51.15 removed the guide read (golf, tennis, racing, fights) from card preparation. Reopening Sports later in the
same run was already instant.

### Problems found on 0.51.15, and what has been done

1. **Playback froze behind Sports while the cards loaded** (owner saw it on 0.51.15; the stream was tunneled).
   - 0.51.17 records stops in the playback clock during tunneled playback, so the report now shows this freeze.
   - 0.51.19 holds the guide read, RedZone scans, event channel names and stream discovery until the scoreboard
     replies finish and the remote has been still for 0.8 s (at least 3.5 s after opening).
   - 0.51.30: no playback hiccups in three runs, but the channel playing was not tunneled. **The tunneled case is
     not yet re-checked.**
2. **The saved schedule arrived at 1.9 s** and gave 0 live games, so cards waited for the first scoreboard reply.
   - 0.51.19 shows cards as replies arrive. 0.51.23 also loads today's scoreboards in the background while a channel
     plays steadily.
   - 0.51.30: the saved schedule arrives at 0.2 s.
3. **The guide read took 8.1 s** to find one event.
   - 0.51.17 checks only guide keys whose current listing says "live" before grouping their channels.
   - 0.51.30: 110 ms.
4. 0.51.28 keeps the first 40 and the latest 20 steps of the Sports trace, so the guide read still shows after a
   long burst of scoreboard replies.
5. **Live cards opened the stream list instead of playing** (owner saw it on 0.51.31, 2026-11-18). A card plays at
   once only when the saved guide shows a channel carrying the game now. Those listings came from the quiet guide
   fill, which 0.51.18 limited to the Guide screen; on a TV that mostly sits in full-screen playback and Sports they
   ran out within a day. A click then asked the provider about up to 12 likely channels and, when none of them was
   the right one, showed the list. The onn's log matched this: about a dozen guide requests right after each card click.
   - 0.51.32: after 30 s of steady full-screen playback with no remote input for 5 s, Spare fills favourites and the
     channels likely to carry today's games, at most one request a second. The rest of the lineup still fills only on the
     Guide screen, and nothing is filled while Sports, Library or Search is open.

## Library: first open before the app has warmed it

Measured on 0.51.14: about 6 s of "Getting your library ready…" before any tile.

- 3 s reading the saved catalog ("Saved category")
- 3 s more until the rows showed ("First titles")
- one 1.3 s screen freeze in the image loader (`LogoLoader.scheduleRetries`) just after
- posters then waited 3 s on average and took 0.2 s each

### What has been done

- 0.51.16 traces Library opening step by step: database open, index file read, index parse, rows picked, titles
  read, rows built.
- 0.51.17 fixed the image-loader freeze: retries check only images still waiting, four at a time.
- 0.51.19 picks the Recently added and Top rated rows in one pass.
- 0.51.21 builds lighter poster tiles and lays out only rows on screen.
- 0.51.22 requests posters in row order and drops old requests when the queue fills.

Measured on 0.51.30 (three cold starts, 2026-11-18):

| | 0.51.14 | 0.51.30 |
|---|---|---|
| Saved catalog ready (database, 1.6 MB index file, parse of 32,493 titles) | 3 s | 0.3 s |
| Rows shown | about 6 s | 0.5 s |
| Poster wait | 3 s on average | 0.32 to 0.36 s |
| Poster load | 0.2 s each | about 50 ms each |
| Screen freezes during opening | 1.3 s in the image loader | none over 0.2 s |
| Playback hiccups | | none |

Spare's own Library frame count still marks 38 to 45% of the 64 frames drawn while the rows fill in as slow (worst
132 to 154 ms). Frame counts from the app overstate stutter on the onn, so check this with SurfaceFlinger timestats
before changing anything.

### Catalog refresh (decided 2026-11-18)

From 0.51.18 to 0.51.30 a saved Movies or Series catalog was used at any age, and new provider titles appeared only
after Refresh in Library. The owner chose to refresh when Library opens. Since 0.51.31:

- Opening the rows of a catalog older than 6 hours starts one quiet background download ("Checking for new titles…").
  A failed attempt is retried after 30 minutes.
- Saving the new copy no longer deletes titles that the rows and grids on screen still read. That copy is kept until
  Library closes, then deleted. The new titles show the next time the rows open.
- Search switches to the new copy.

Still to measure on the onn: whether the background download (it shows as "catalog-import" in the report's other
downloads) causes playback hiccups during the first open after updating.

## Next steps

1. Check that the Sports playback freeze is gone on a tunneled channel. It shows as "Tunneled playback position
   stopped … in Sports" in the report.
2. After updating the onn to 0.51.31, open Library once and check the report for hiccups while the catalog
   downloads again.
3. Measure Library frames on the onn with SurfaceFlinger timestats.
4. After updating the onn to 0.51.32, watch a channel full screen for a few minutes, then open Sports: a live card
   should play at once. Check the report's hiccups for "guide" as another download running at the time.
