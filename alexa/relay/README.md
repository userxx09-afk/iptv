# Tapper Alexa relay 1.0

Alexa voice control for **Tapper IPTV** and **GarysTV**, from one small program
with installers for Ubuntu and Windows. You sign in with Amazon on its setup
page and it creates, configures and turns on the private Alexa skill by itself.

"Alexa, ask tapper to play ESPN" · "…channel 204" · "…next channel" · "…pause" ·
"…resume" · "…stop watching" · "…open the guide" · "…show my recordings" · "…go home" · "…go back"

It speaks the same `/tv/...` protocol as the Node relay in `../server`, so both
apps work with it unchanged. Copying the old relay's `data.json` into the new
data folder keeps every TV linked.

## Install

**Ubuntu (tapper):**

```sh
sudo apt install ./tapper-alexa-relay_1.0.0_amd64.deb
```

It runs as the `tapper-alexa-relay` systemd service on port 8787 (change it in
`/etc/default/tapper-alexa-relay`) and prints the setup address and PIN.
Get the PIN again: `sudo tapper-alexa-relay pin --data /var/lib/tapper-alexa-relay`.
Logs: `journalctl -u tapper-alexa-relay`.

**Windows (fm-workstation):** run `TapperAlexaRelaySetup-1.0.0.exe` and allow
admin rights. It installs to Program Files, starts at every boot (Task
Scheduler, as SYSTEM, restarted if it stops), opens port 8787 on the home
network only, and opens the setup page. Settings and the log are in
`C:\ProgramData\Tapper Alexa Relay`. Remove it from Settings › Apps. The .exe
isn't code-signed, so SmartScreen will ask: More info › Run anyway.

Install it on **one** computer. Both apps, at every location, use the same relay.

## Set up (setup page, about 5 minutes)

Open `http://localhost:8787/setup` on that computer (or `http://<its-ip>:8787/setup`
from the home network or VPN). The page is never reachable through the tunnel
and needs the PIN.

1. **Public address.** Alexa only calls HTTPS addresses on the internet. In
   Cloudflare Zero Trust › Tunnels › your tunnel › Public Hostname, add
   `alexa.tapper.co` → `http://localhost:8787`. Enter it and press Save & check.
2. **Security profile (once).** Amazon only lets a program sign in to your
   account with a Login with Amazon security profile you create. The page walks
   you through it and shows the exact Return URL to add.
3. **Sign in with Amazon.** Use the account your Echo devices are on. From
   another computer, Amazon's last step lands on a `localhost` page that won't
   load: paste that address into the page.
4. **Create the skill.** Pick the invocation name ("tapper" by default). The
   relay finds your developer account, creates the skill, points it at
   `/alexa`, builds the voice model and turns on testing, which makes the skill
   work on every Echo on that account without publishing it.
5. **Test it.** Types a phrase into Alexa's simulator and shows the reply.
6. **Link TVs.** Enter the relay address and relay key in Tapper (Settings ›
   Alexa, on each Fire TV) and in GarysTV (Settings › Alexa, once on the GarysTV
   computer), press Link on the TV and say "Alexa, ask tapper to link code …".

Several TVs can be linked to one Alexa account. A command goes to the linked TV
that has the app open right now (the most recently opened one, if several do).
If none does, it waits a minute for the TV linked last, and Alexa says so.

## Security

- `/alexa` accepts only requests Amazon signed (certificate URL and chain,
  RSA-SHA256 signature over the body, timestamp within 150 s) for this skill id.
- TVs register only with the relay key, then use their own random token.
- `/setup` answers only direct requests from this computer, the LAN or the VPN
  (any request carrying Cloudflare or proxy headers gets 404), and only after
  the PIN (10 tries per 15 minutes). The Amazon refresh token is in
  `config.json`, readable only by the service account / SYSTEM and Administrators.

## Build

```sh
cd alexa/relay
go test ./...
sh packaging/build.sh     # dist/: .deb (amd64, arm64), Windows setup .exe, install.sh, SHA256SUMS
```

Go only, no outside modules.
