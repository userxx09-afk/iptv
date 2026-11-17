// Tapper IPTV Alexa relay.
//
// Two jobs, one small process:
//  1. The Alexa custom skill endpoint (POST /alexa). Amazon sends it what the
//     person said; the request signature and timestamp are checked by
//     ask-sdk-express-adapter, so only Amazon can call it.
//  2. A mailbox per TV. Each Fire TV registers once, then keeps one request
//     open (GET /tv/next, long poll) and gets the commands Alexa queued for
//     it: tune to a channel, pause, resume, stop.
//
// Linking: Settings > Alexa on the TV shows a six-digit code; the person says
// "Alexa, ask Tapper to link code 123456". From then on that Alexa account
// controls that TV. One Alexa account can control one TV; the last linked wins.
//
// Runs behind any HTTPS front end (Alexa requires HTTPS with a trusted
// certificate): nginx or Caddy on your server, or a tunnel. Port: PORT, 8787 by default.

const express = require('express');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const Alexa = require('ask-sdk-core');
const { ExpressAdapter } = require('ask-sdk-express-adapter');

const PORT = Number(process.env.PORT || 8787);
const SKILL_ID = process.env.ALEXA_SKILL_ID || ''; // amzn1.ask.skill...; recommended
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data.json');
// Optional: when set, a TV must send it to register, so strangers who find
// the address can't add TVs. Enter the same value in Settings > Alexa.
const REGISTER_KEY = process.env.REGISTER_KEY || '';

// ---- storage: TVs and links survive restarts; queued commands don't need to.
let db = { tvs: {}, links: {} }; // tvs[tvId] = {tokenHash, name}; links[alexaUserId] = tvId
try { db = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch (_) { /* first run */ }
function save() { fs.writeFileSync(DATA_FILE, JSON.stringify(db, null, 2)); }

const codes = new Map();   // code -> {tvId, expires}
const queues = new Map();  // tvId -> [command]
const waiters = new Map(); // tvId -> res waiting in /tv/next

const hash = (s) => crypto.createHash('sha256').update(s).digest('hex');

function send(tvId, command) {
  const q = queues.get(tvId) || [];
  q.push({ ...command, at: Date.now() });
  queues.set(tvId, q.slice(-20));
  const w = waiters.get(tvId);
  if (w) { waiters.delete(tvId); flush(tvId, w); }
}

function flush(tvId, res) {
  const q = queues.get(tvId) || [];
  queues.set(tvId, []);
  // Commands older than a minute are dropped: "pause" an hour late is wrong.
  res.json({ commands: q.filter((c) => Date.now() - c.at < 60_000) });
}

function tvAuth(req, res, next) {
  const id = req.get('X-Tapper-Tv');
  const token = (req.get('Authorization') || '').replace(/^Bearer /, '');
  const tv = id && db.tvs[id];
  if (!tv || tv.tokenHash !== hash(token)) return res.status(401).json({ error: 'unknown tv' });
  req.tvId = id;
  next();
}

// ---- the TV side
const app = express();
app.use('/tv', express.json({ limit: '8kb' }));

app.post('/tv/register', (req, res) => {
  if (REGISTER_KEY && req.get('X-Tapper-Key') !== REGISTER_KEY) return res.status(403).json({ error: 'wrong key' });
  const tvId = crypto.randomUUID();
  const token = crypto.randomBytes(32).toString('hex');
  db.tvs[tvId] = { tokenHash: hash(token), name: String(req.body?.name || 'Fire TV').slice(0, 60) };
  save();
  res.json({ tvId, token });
});

app.post('/tv/pair', tvAuth, (req, res) => {
  for (const [c, v] of codes) if (v.tvId === req.tvId || v.expires < Date.now()) codes.delete(c);
  let code;
  do { code = String(crypto.randomInt(100000, 1000000)); } while (codes.has(code));
  codes.set(code, { tvId: req.tvId, expires: Date.now() + 10 * 60_000 });
  const linked = Object.values(db.links).includes(req.tvId);
  res.json({ code, expiresInSec: 600, linked });
});

app.get('/tv/status', tvAuth, (req, res) => {
  res.json({ linked: Object.values(db.links).includes(req.tvId) });
});

app.post('/tv/unlink', tvAuth, (req, res) => {
  for (const [u, t] of Object.entries(db.links)) if (t === req.tvId) delete db.links[u];
  save();
  res.json({ ok: true });
});

app.get('/tv/next', tvAuth, (req, res) => {
  const q = queues.get(req.tvId) || [];
  if (q.length) return flush(req.tvId, res);
  const old = waiters.get(req.tvId);
  if (old) old.json({ commands: [] });
  waiters.set(req.tvId, res);
  const timer = setTimeout(() => {
    if (waiters.get(req.tvId) === res) { waiters.delete(req.tvId); res.json({ commands: [] }); }
  }, 25_000);
  req.on('close', () => { clearTimeout(timer); if (waiters.get(req.tvId) === res) waiters.delete(req.tvId); });
});

// ---- the Alexa side
const userId = (h) => h.requestEnvelope.context?.System?.user?.userId || h.requestEnvelope.session?.user?.userId;
const slot = (h, name) => Alexa.getSlotValue(h.requestEnvelope, name);
const isIntent = (name) => (h) =>
  Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === name;

function linkedTv(h) {
  return db.links[userId(h)];
}

const notLinked = (h) => h.responseBuilder
  .speak('This Alexa isn\'t linked to a TV yet. On the TV, open Tapper, go to Settings, Alexa, and press Link, then tell me the code.')
  .reprompt('What\'s the code on the TV?')
  .getResponse();

function command(name, speech, build) {
  return {
    canHandle: isIntent(name),
    handle(h) {
      const tv = linkedTv(h);
      if (!tv) return notLinked(h);
      const c = build(h);
      if (!c) return h.responseBuilder.speak('Sorry, I didn\'t catch that.').reprompt('Which channel?').getResponse();
      send(tv, c);
      return h.responseBuilder.speak(typeof speech === 'function' ? speech(h, c) : speech).getResponse();
    },
  };
}

const handlers = [
  {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'LaunchRequest',
    handle: (h) => linkedTv(h)
      ? h.responseBuilder.speak('What would you like to watch?').reprompt('Say a channel, like play ESPN.').getResponse()
      : notLinked(h),
  },
  {
    canHandle: isIntent('LinkIntent'),
    handle(h) {
      const code = String(slot(h, 'code') || '').replace(/\D/g, '');
      const entry = codes.get(code);
      if (!entry || entry.expires < Date.now()) {
        return h.responseBuilder.speak('That code didn\'t work. Check the code on the TV and try again.').reprompt('What\'s the code?').getResponse();
      }
      codes.delete(code);
      db.links[userId(h)] = entry.tvId;
      save();
      send(entry.tvId, { type: 'linked' });
      return h.responseBuilder.speak(`Linked to ${db.tvs[entry.tvId]?.name || 'your TV'}. Now say, ask Tapper to play, and a channel.`).getResponse();
    },
  },
  command('TuneIntent', (h, c) => `Putting on ${c.query}.`, (h) => {
    const q = slot(h, 'channel');
    return q ? { type: 'tune', query: q } : null;
  }),
  command('ChannelNumberIntent', (h, c) => `Channel ${c.number}.`, (h) => {
    const n = parseInt(slot(h, 'number'), 10);
    return Number.isFinite(n) ? { type: 'number', number: n } : null;
  }),
  command('ChannelUpIntent', 'Next channel.', () => ({ type: 'channelUp' })),
  command('ChannelDownIntent', 'Previous channel.', () => ({ type: 'channelDown' })),
  command('AMAZON.PauseIntent', 'Paused.', () => ({ type: 'pause' })),
  command('AMAZON.ResumeIntent', 'Playing.', () => ({ type: 'resume' })),
  command('StopWatchingIntent', 'Stopped.', () => ({ type: 'stop' })),
  {
    canHandle: isIntent('AMAZON.HelpIntent'),
    handle: (h) => h.responseBuilder
      .speak('You can say, play ESPN, channel 204, next channel, or pause. To link a TV, say link code, and the code from the TV.')
      .reprompt('What would you like to watch?').getResponse(),
  },
  {
    canHandle: (h) => isIntent('AMAZON.CancelIntent')(h) || isIntent('AMAZON.StopIntent')(h),
    handle: (h) => h.responseBuilder.speak('Okay.').getResponse(),
  },
  {
    canHandle: isIntent('AMAZON.FallbackIntent'),
    handle: (h) => h.responseBuilder.speak('Try saying, play, and a channel name.').reprompt('Which channel?').getResponse(),
  },
  {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'SessionEndedRequest',
    handle: (h) => h.responseBuilder.getResponse(),
  },
];

const builder = Alexa.SkillBuilders.custom()
  .addRequestHandlers(...handlers)
  .addErrorHandlers({
    canHandle: () => true,
    handle(h, err) {
      console.error('alexa error', err && err.message);
      return h.responseBuilder.speak('Sorry, something went wrong.').getResponse();
    },
  });
if (SKILL_ID) builder.withSkillId(SKILL_ID);
const adapter = new ExpressAdapter(builder.create(), true, true); // verify signature and timestamp

app.post('/alexa', adapter.getRequestHandlers());
app.get('/health', (_, res) => res.json({ ok: true, tvs: Object.keys(db.tvs).length }));

app.listen(PORT, () => console.log(`Tapper Alexa relay on :${PORT}`));
