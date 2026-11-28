# Spare

**A small, fast IPTV player for Android TV, built for finding a game and watching it.**

Bring your own playlist (M3U link or Xtream Codes login). Spare shows the games that are live right now, finds each one in your playlist, and plays it with one press of OK. It also has a full live TV guide and your provider's movies and series.

Free and open source (MIT). No ads, no accounts, no tracking. Spare includes no channels or subscriptions. If it's useful to you, you can [support it on Ko-fi](https://ko-fi.com/mot1us).

## Install

On your Android TV or Google TV:

1. Install **Downloader** by AFTVnews from the Play Store.
2. Open Downloader and enter the code **3192623**.
3. Install Spare when Downloader asks. Android may first ask you to let Downloader install apps; allow it.

The code always downloads the newest release. In a browser, this link does the same: `https://github.com/mot1us/spare/releases/latest/download/spare.apk`

After that, Spare updates itself. New versions appear under **Settings → Updates**.

## Set up

1. Open Spare and scan the QR code with your phone. Enter your M3U link or Xtream login on the phone, or type it with the remote instead.
2. Pick your sports, a look, and how the guide works. You can change all of it later in Settings.
3. Open **Sports** and press OK on a game.

## How it works

- **Your playlist** gives Spare its channels, the programme guide, movies and series. You can also add a separate guide link: Spare uses it for the channels it lists and your provider's guide for the rest.
- **Sports** gets schedules and scores from ESPN's public scoreboards. For each game, Spare looks for channels whose guide listing or name mentions that game, and ranks them. It never guesses: a channel with no such evidence isn't offered. Spare does this in the background, so when you open Sports the cards already know their streams.
- **Movies and series** are downloaded once and saved on the TV, so Library opens quickly. Spare refreshes them in the background.
- **Privacy:** Spare connects only to your provider and the guide and poster addresses your playlist lists, your guide link if you add one, ESPN for scores and team logos, and GitHub for updates. Your logins and saved data are encrypted on the TV.
- **Phone setup** runs a small form on the TV itself. Your phone talks to the TV over your home network, not through a server.
- **Updates** come from this repository's GitHub releases. Spare installs an update only if it is signed with the same key as the copy you have.

## What it does

**Sports**
- **Live now:** every live game in your leagues on one screen, plus golf, tennis, racing and fights found in your guide.
- **My teams:** your teams' games on now, recent results and what's next.
- **Leagues:** NFL (with RedZone), MLB, NBA, NHL, Premier League, MLS, Champions League, UFC, PFL, and college football, basketball and baseball.
- Reminders, spoiler-free scores, a score delay to match your stream, and replays when your provider keeps them.

**Live TV**
- A fast guide with categories, favorites, local channels and search, including voice search.
- Hold Left while watching to see the channel's programmes, with catch-up shows marked so you can replay them.
- Frame-rate matching, audio tracks, subtitles, and Multiview with two or four channels at once.

**Movies and Series**
- Your provider's library, with ratings, Watchlist, Continue watching and Up next.
- A movie your provider lists in several versions shows once, and Spare plays the best one your TV supports (for example 4K or Dolby Vision).

**Small**
- About 4 MB, a native Android TV app with no web views, so it stays quick on budget streaming sticks.

## Remote basics

| Where | Button | Does |
| --- | --- | --- |
| Watching | Up / Down | Change channel |
| Watching | OK | Details, recent channels and actions (including Multiview) |
| Watching | Left | Previous channel |
| Watching | Hold Left | This channel's programmes and catch-up |
| Watching | Back | Open the guide |
| Sports | OK on a game | Play it |
| Sports | Hold OK | Choose a stream, set a reminder |
| Guide | Hold OK | Add or remove a favorite |
| Guide, Sports, Library | Left at the edge | Main menu (Search, Guide, Sports, Library, Settings) |
| Watching, Guide | Menu | Settings |

## More

[Changelog](CHANGELOG.md) · [How Spare is built](docs/README.md) · [Building and testing](docs/TESTING.md) · [Releasing](docs/RELEASING.md) · [Security](SECURITY.md)

Spare is in beta and is tested on the onn 4K Pro.
