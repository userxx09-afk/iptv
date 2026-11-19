# Tapper Alexa relay

> **New: `relay/` (version 1.0)** replaces the Node server below for most people:
> one program with Ubuntu (.deb) and Windows (setup .exe) installers, shared by
> Tapper and GarysTV, that creates and enables the Alexa skill for you after you
> sign in with Amazon. See [relay/README.md](relay/README.md). The Node server
> below still works and speaks the same protocol.

Voice control for Tapper IPTV on Fire TV: "Alexa, ask Tapper to play ESPN".

Tapper itself has no server. This folder is the one optional piece that does,
because Alexa can only talk to an HTTPS address on the internet. It is a small
Node.js process that:

1. answers the Alexa skill (`POST /alexa`, signature-checked so only Amazon can call it), and
2. holds a mailbox per TV. While Tapper is open, the TV keeps one request open
   (`GET /tv/next`) and acts on what Alexa queued: tune by name or number,
   next and previous channel, pause, resume, stop.

The skill works with the sideloaded app. It does not need an Appstore listing,
ADM, or Amazon's Video Skills Kit. The catch is the "ask Tapper" in every
phrase. Native "Alexa, tune to ESPN" without it needs the Video Skill API,
which Amazon only offers to Appstore-listed apps.

## What you need

- A server with Node.js 18+ and a public HTTPS address with a trusted
  certificate (nginx or Caddy in front of this process, or a Cloudflare tunnel).
  An AWS Lambda works too, but the long-poll mailbox wants a normal always-on
  process, so a server is simpler.
- A free Amazon developer account (developer.amazon.com), signed in with the
  same Amazon account as your Echo devices.

## Run the relay

```sh
cd alexa/server
npm install --omit=dev
ALEXA_SKILL_ID=amzn1.ask.skill.xxxx REGISTER_KEY=some-long-random-text PORT=8787 npm start
```

- `ALEXA_SKILL_ID`: the skill's id from step 3 below. It's optional but recommended, because it rejects other skills.
- `REGISTER_KEY` (optional): when set, a TV must enter the same key in Settings > Alexa before it can register.
- `DATA_FILE` (optional): where linked TVs are stored. The default is `data.json` next to `server.js`.

Point your HTTPS front end at the port. `GET /health` should answer `{"ok":true}`.

## Create the skill

1. Go to developer.amazon.com > Alexa > Skills > Create Skill. Name it "Tapper", pick the Custom model, and choose "Provision your own" for hosting.
2. Open Interaction Model > JSON Editor and paste `skill/interactionModel.en-US.json`. Save, then Build.
3. Open Endpoint, choose HTTPS, enter `https://YOUR-SERVER/alexa`, and pick "My development endpoint has a certificate from a trusted certificate authority". Copy the Skill ID into `ALEXA_SKILL_ID`.
4. Open Test and set "Skill testing is enabled in" to Development. The skill now works on every Echo on your Amazon account. It never has to be published.

`skill/skill.json` holds the same settings in manifest form, if you prefer the ASK CLI.

## Link each TV

On the Fire TV, go to Tapper > Settings > Alexa:

1. Set relay address: `https://YOUR-SERVER`.
2. Relay key: only if you set `REGISTER_KEY`.
3. Press Link to Alexa. The TV shows a code. Say "Alexa, ask Tapper to link code 123 456".

One Alexa account controls one TV, and the last TV linked wins. To control a
different TV, link that one.

## What you can say

- "Alexa, ask Tapper to play ESPN" / "put on Fox News" / "watch Marquee"
- "Alexa, ask Tapper to go to channel 204"
- "Alexa, ask Tapper for the next channel" / "previous channel"
- "Alexa, ask Tapper to pause" / "resume" / "stop watching"

Commands only reach the TV while Tapper is on screen. Commands older than a minute are dropped.
