# Sports and Library loading: measurements and plan

Written 2026-11-16, updated 2026-11-17. All timings are from the second onn 4K Pro (Android 14) with a 15,800-channel
playlist and a 32,493-movie Library, read from the app's own performance report (Settings › About Spare).

## Sports: first open after the app starts

| | 0.51.14 | 0.51.15 |
|---|---|---|
| Game cards shown | 7.0 s | 2.3 s |
| Last crest loaded | 16.1 s | 3.4 s |
| Card preparation, each time | 1.5 to 6.8 s | 1 to 136 ms |
| Guide read for other live sports | inside every preparation | 8.1 s, once, off the card path |

0.51.15 removed the guide read (golf, tennis, racing, fights) from card preparation. Reopening Sports later in the
same run was already instant.

### Problems found on 0.51.15, and what has been done

1. **Playback froze behind Sports while the cards loaded** (owner saw it on 0.51.15; the stream was tunneled).
   - 0.51.17 records stops in the playback clock during tunneled playback, so the report now shows this freeze.
   - 0.51.19 holds the guide read, RedZone scans, event channel names and stream discovery until the scoreboard
     replies finish and the remote has been still for 0.8 s (at least 3.5 s after opening).
   - **Not yet re-measured.**
2. **The saved schedule arrived at 1.9 s** and gave 0 live games, so cards waited for the first scoreboard reply.
   - 0.51.19 shows cards as replies arrive. 0.51.23 also loads today's scoreboards in the background while a channel
     plays steadily.
   - **Not yet re-measured.**
3. **The guide read took 8.1 s** to find one event.
   - 0.51.17 checks only guide keys whose current listing says "live" before grouping their channels.
   - **Not yet re-measured.**
4. 0.51.28 keeps the first 40 and the latest 20 steps of the Sports trace, so the guide read still shows after a
   long burst of scoreboard replies.

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
- **Not yet re-measured.**

### Open decision: catalogs no longer refresh on their own

Since 0.51.18 a saved Movies or Series catalog is used at any age. Before, opening Search refreshed a catalog older
than 6 hours in the background. Now new provider titles appear only after Refresh in Library. 0.51.30 shows the age
on the rows ("Saved 3 days ago · Refresh for new titles").

Refreshing automatically while Library is open is not safe as the code stands. Saving a new copy deletes the old
copy's titles, and rows and category pages that are still open read from the old copy. Options for the owner:

- Keep refresh manual, with the age label (current).
- Refresh when Library opens, and rebuild the rows on the new copy when it finishes.
- Keep the previous copy's titles until Library closes, then delete them.

## Next steps

1. Re-time Sports and Library on the second onn: first open after a cold start, three times each. This needs
   wireless debugging on.
2. Check that the Sports playback freeze is gone. It shows as "Tunneled playback position stopped … in Sports" in
   the report.
3. Decide the catalog refresh question above.
