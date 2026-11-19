package main

// The TV side: the protocol Tapper IPTV (AlexaLink.kt) and GarysTV (core/alexa.js)
// already speak, unchanged:
//
//	POST /tv/register {name, app?}  -> {tvId, token}     X-Tapper-Key when the relay has a key
//	POST /tv/pair                   -> {code, expiresInSec, linked}
//	GET  /tv/status                 -> {linked}
//	POST /tv/unlink                 -> {ok}
//	GET  /tv/next   (long poll 25s) -> {commands: [{type, query?, number?, at}]}
//
// Authenticated calls carry X-Tapper-Tv: tvId and Authorization: Bearer token.
//
// One Alexa account can have several TVs linked (and the same Fire TV can run
// both apps). A command goes to the linked TV that's on screen right now: the
// one whose app is waiting in /tv/next. When several are, the one opened most
// recently wins. When none is, it waits (up to a minute) for the newest link.

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"
)

type command struct {
	Type   string `json:"type"`
	Query  string `json:"query,omitempty"`
	Number *int   `json:"number,omitempty"`
	At     int64  `json:"at"`
}

type pairCode struct {
	tvID    string
	expires time.Time
}

type mailbox struct {
	mu      sync.Mutex
	queues  map[string][]command
	waiters map[string]chan struct{}
	seen    map[string]time.Time // last time the TV was waiting in /tv/next
	opened  map[string]time.Time // when it came back after being away (app opened)
	codes   map[string]pairCode
}

const (
	onlineWindow = 40 * time.Second // a TV that polled this recently has its app open
	commandTTL   = 60 * time.Second // "pause" an hour late is wrong
	longPoll     = 25 * time.Second
)

func newMailbox() *mailbox {
	return &mailbox{queues: map[string][]command{}, waiters: map[string]chan struct{}{}, seen: map[string]time.Time{}, opened: map[string]time.Time{}, codes: map[string]pairCode{}}
}

func (m *mailbox) send(tvID string, c command) {
	c.At = time.Now().UnixMilli()
	m.mu.Lock()
	q := append(m.queues[tvID], c)
	if len(q) > 20 {
		q = q[len(q)-20:]
	}
	m.queues[tvID] = q
	if w := m.waiters[tvID]; w != nil {
		close(w)
		delete(m.waiters, tvID)
	}
	m.mu.Unlock()
}

func (m *mailbox) take(tvID string) []command {
	m.mu.Lock()
	defer m.mu.Unlock()
	q := m.queues[tvID]
	delete(m.queues, tvID)
	out := []command{}
	cut := time.Now().Add(-commandTTL).UnixMilli()
	for _, c := range q {
		if c.At >= cut {
			out = append(out, c)
		}
	}
	return out
}

func (m *mailbox) touch(tvID string) {
	now := time.Now()
	if last, ok := m.seen[tvID]; !ok || now.Sub(last) > onlineWindow {
		m.opened[tvID] = now
	}
	m.seen[tvID] = now
}

func (m *mailbox) online(tvID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, waiting := m.waiters[tvID]; waiting {
		return true
	}
	return time.Since(m.seen[tvID]) < onlineWindow
}

// pick: which of an account's linked TVs (oldest link first) gets a command.
func (m *mailbox) pick(linked []string) (tvID string, onScreen bool) {
	if len(linked) == 0 {
		return "", false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	var best string
	var bestOpened time.Time
	for _, id := range linked {
		_, waiting := m.waiters[id]
		if waiting || time.Since(m.seen[id]) < onlineWindow {
			if best == "" || !m.opened[id].Before(bestOpened) {
				best, bestOpened = id, m.opened[id]
			}
		}
	}
	if best != "" {
		return best, true
	}
	return linked[len(linked)-1], false
}

func (m *mailbox) newCode(tvID string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	for c, v := range m.codes {
		if v.tvID == tvID || v.expires.Before(now) {
			delete(m.codes, c)
		}
	}
	for {
		c := randomDigits(6)
		if c[0] == '0' {
			continue // Alexa hears "012345" as 12345
		}
		if _, used := m.codes[c]; !used {
			m.codes[c] = pairCode{tvID: tvID, expires: now.Add(10 * time.Minute)}
			return c
		}
	}
}

func (m *mailbox) useCode(code string) (string, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	v, ok := m.codes[code]
	if !ok || v.expires.Before(time.Now()) {
		return "", false
	}
	delete(m.codes, code)
	return v.tvID, true
}

// ---- HTTP

func (s *server) tvRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /tv/register", s.tvRegister)
	mux.HandleFunc("POST /tv/pair", s.tvAuth(s.tvPair))
	mux.HandleFunc("GET /tv/status", s.tvAuth(s.tvStatus))
	mux.HandleFunc("POST /tv/unlink", s.tvAuth(s.tvUnlink))
	mux.HandleFunc("GET /tv/next", s.tvAuth(s.tvNext))
}

func writeJSONResponse(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *server) tvRegister(w http.ResponseWriter, r *http.Request) {
	key := s.st.config().RegisterKey
	if key != "" && subtle.ConstantTimeCompare([]byte(strings.TrimSpace(r.Header.Get("X-Tapper-Key"))), []byte(key)) != 1 {
		writeJSONResponse(w, 403, map[string]string{"error": "wrong key"})
		return
	}
	var body struct {
		Name string `json:"name"`
		App  string `json:"app"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body)
	name := strings.TrimSpace(body.Name)
	if name == "" {
		name = "TV"
	}
	if len([]rune(name)) > 60 {
		name = string([]rune(name)[:60])
	}
	app := strings.ToLower(strings.TrimSpace(body.App))
	if app == "" {
		app = guessApp(name, r.UserAgent())
	}
	id, token, err := s.st.addTV(name, app)
	if err != nil {
		writeJSONResponse(w, 500, map[string]string{"error": err.Error()})
		return
	}
	s.logf("registered %s (%s)", name, app)
	writeJSONResponse(w, 200, map[string]string{"tvId": id, "token": token})
}

// GarysTV names its screens "GarysTV ..."; anything else came from Tapper so far.
func guessApp(name, ua string) string {
	n := strings.ToLower(name)
	if strings.Contains(n, "garystv") || strings.Contains(strings.ToLower(ua), "node") {
		return "garystv"
	}
	return "tapper"
}

type tvHandler func(w http.ResponseWriter, r *http.Request, tvID string)

func (s *server) tvAuth(h tvHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("X-Tapper-Tv")
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !s.st.checkTV(id, token) {
			writeJSONResponse(w, 401, map[string]string{"error": "unknown tv"})
			return
		}
		h(w, r, id)
	}
}

func (s *server) tvPair(w http.ResponseWriter, r *http.Request, tvID string) {
	writeJSONResponse(w, 200, map[string]any{"code": s.mb.newCode(tvID), "expiresInSec": 600, "linked": s.st.isLinked(tvID)})
}

func (s *server) tvStatus(w http.ResponseWriter, r *http.Request, tvID string) {
	writeJSONResponse(w, 200, map[string]bool{"linked": s.st.isLinked(tvID)})
}

func (s *server) tvUnlink(w http.ResponseWriter, r *http.Request, tvID string) {
	if err := s.st.unlinkTV(tvID); err != nil {
		writeJSONResponse(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func (s *server) tvNext(w http.ResponseWriter, r *http.Request, tvID string) {
	m := s.mb
	m.mu.Lock()
	m.touch(tvID)
	if len(m.queues[tvID]) > 0 {
		m.mu.Unlock()
		writeJSONResponse(w, 200, map[string]any{"commands": m.take(tvID)})
		return
	}
	if old := m.waiters[tvID]; old != nil {
		close(old) // a newer request from the same TV replaces the old one
	}
	ch := make(chan struct{})
	m.waiters[tvID] = ch
	m.mu.Unlock()

	t := time.NewTimer(longPoll)
	defer t.Stop()
	select {
	case <-ch:
	case <-t.C:
	case <-r.Context().Done():
	}
	m.mu.Lock()
	if m.waiters[tvID] == ch {
		delete(m.waiters, tvID)
	}
	m.touch(tvID)
	m.mu.Unlock()
	if r.Context().Err() != nil {
		return
	}
	writeJSONResponse(w, 200, map[string]any{"commands": m.take(tvID)})
}
