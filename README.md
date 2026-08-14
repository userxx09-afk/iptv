# Tapper IPTV — Fire TV (v0.1)

## Getting an APK without installing anything

1. Create an empty GitHub repo and push this tree to `main`.
2. Open the **Actions** tab. The build runs automatically; it takes ~4 minutes.
3. Download the `tapper-iptv-apk` artifact.

To sideload onto a Fire TV:

- Settings → My Fire TV → Developer Options → **Apps from Unknown Sources: on**
- Install the *Downloader* app from the Amazon appstore
- Tag a commit (`git tag v0.1 && git push --tags`) so the APK gets a stable
  release URL, then paste that URL into Downloader

Or over ADB, if the Fire TV and your machine share a network:

```
adb connect <fire-tv-ip>:5555
adb install -r tapper-iptv-1.apk
```

## What v0.1 does

- Loads iptv-org's playlist (13,510 channels), cached for 12 hours
- Groups by country derived from the tvg-id suffix
- Plays with per-stream headers and silent failover across alternate feeds
- Explains failures instead of spinning

## What it deliberately doesn't do yet

- **No EPG.** Needs SQLite; a guide is ~400k rows and can't live in memory.
- **No user-added Xtream sources.** Credential vault and pairing come next.
- **No watch-state sync.** Needs the sync service.
- **No Compose-for-TV components.** Uses standard Compose with explicit
  `focusable()` and key handling — fewer experimental APIs, fewer ways for the
  first build to fail.

## Note on this build

This tree was written without a compiler available — no Android SDK or Google
Maven access in the authoring environment. The parsing logic is verified against
the real 13,510-entry playlist, but the Android and Compose code has never been
compiled. Expect the first CI run to surface import or API-signature fixes.
