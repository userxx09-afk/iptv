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

## Free TV and showing two sources at once

- **Free TV** is a built-in source: GarysTV's lineup of about 140 US channels
  streamed openly by their owners or licensed free services (Tubi, Xumo,
  Stirr, Samsung TV Plus...). The app reads it from the public GarysTV repo
  (`src/core/sources/free-channels.json`), so a channel GarysTV adds shows up
  here within a day with no new build. A copy is bundled in
  `firetv/src/main/assets/garystv` for a first launch without internet.
- **Show alongside**: in Settings, any source other than the active one has a
  "Show alongside" button. Its live channels then join the active source's
  lineup and guide, under categories named after it ("Free TV · News").
  Free TV is shown alongside by default.
- **Guides**: the same extra guide files GarysTV uses
  (`src/core/guide/free-guides.json` and `extra-guides.json`: epgshare01 and
  i.mjh.nz copies of the Plex, Roku, Pluto, Samsung TV Plus and DistroTV
  schedules) are downloaded every 12 hours, keeping only the channels they
  list. The Free TV ones are only fetched while Free TV or the iptv-org
  playlist is in use. Each source's guide is stored under its own id and
  the guide screens read all the ones in use (see `EpgDatabase`).
- To refresh the bundled copies: copy those three files from a GarysTV
  checkout into `firetv/src/main/assets/garystv`.

## Cloud sync setup (Sign in with Google / Microsoft)

Settings > Shared watch history (Fire TV) and Settings > Sync (iPad) can sign
in to a Google or Microsoft account. The TV shows a short code; you enter it at
google.com/device or microsoft.com/devicelogin on your phone, and the app
creates its sync folder in the account by itself:

- **Google Drive:** a `Tapper IPTV Sync` folder in My Drive. The app only asks
  for `drive.file`, so it can see the files it creates and nothing else.
- **OneDrive:** `Apps/Tapper IPTV`, via `Files.ReadWrite.AppFolder`.

Sign every device in to the same account and they share watch history. The
apps need OAuth client IDs, supplied as repository secrets (Settings > Secrets
and variables > Actions). Without them the build still works and the sign-in
buttons explain that they aren't set up.

**Google** (`GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`)

1. console.cloud.google.com > new project > APIs & Services > Library >
   enable **Google Drive API**.
2. OAuth consent screen: External, add the `.../auth/drive.file` scope, then
   **Publish app** (In production). Left in Testing, Google expires the
   sign-in after 7 days.
3. Credentials > Create credentials > OAuth client ID > type
   **TVs and Limited Input devices**. Copy the client ID and secret into the two
   secrets. (Google treats this secret as non-confidential; it ships in the app.)

**Microsoft** (`MICROSOFT_OAUTH_CLIENT_ID`)

1. portal.azure.com > App registrations > New registration. Name it
   **Tapper IPTV** (OneDrive names the app folder after it). Supported account
   types: *any organizational directory and personal Microsoft accounts*.
2. Authentication > Advanced settings > **Allow public client flows: Yes**.
3. API permissions > Microsoft Graph > Delegated: `Files.ReadWrite.AppFolder`,
   `User.Read`, `offline_access`.
4. Copy the Application (client) ID into the secret.

Push to `main` (or re-run the workflows) after adding the secrets.

## Optional service keys (Trakt, OpenSubtitles)

Both stay hidden in the Fire TV app until their keys are added as repository
secrets (Settings > Secrets and variables > Actions). Neither needs a server.

| Secret | Where to get it |
| --- | --- |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | trakt.tv > Settings > Your API Apps > New Application. Redirect URI: `urn:ietf:wg:oauth:2.0:oob`. |
| `OPENSUBTITLES_API_KEY` | opensubtitles.com > your profile > API consumers > New consumer. |

With them set, Settings shows "Sign in to Trakt" (a code entered at
trakt.tv/activate) and turning captions on for a title with no subtitles
fetches them from OpenSubtitles.

## Release pipeline

The source repo is private. Every CI build publishes its installable APK to
the public, releases-only repo **userxx09-afk/prod**, which hosts the final
builds of all the Tapper apps:

- `iptv-build-N` - one release per build (the in-app updater reads these)
- `iptv-latest` - always the newest build, at a fixed address for Downloader:
  `https://github.com/userxx09-afk/prod/releases/download/iptv-latest/tapper-iptv.apk`

Repository secrets this needs:

| Secret | What |
| --- | --- |
| `PROD_REPO_TOKEN` | Fine-grained token, repository access *only* `prod`, permission Contents: Read and write |
| `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | The private signing key. Keep an offline copy: a lost key means reinstalling on every device again |

Builds are R8-obfuscated release builds; the R8 mapping for each build is a
CI artifact (`r8-mapping-N`) for reading crash stack traces.
