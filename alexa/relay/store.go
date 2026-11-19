package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Config is what setup fills in. Kept in config.json (owner-only permissions).
type Config struct {
	PublicURL    string `json:"publicUrl"`   // https://alexa.tapper.co
	Invocation   string `json:"invocation"`  // "tapper" -> "Alexa, ask tapper to ..."
	SkillName    string `json:"skillName"`   // shown in the Alexa app
	CertType     string `json:"certType"`    // Wildcard | Trusted
	SkillID      string `json:"skillId"`     // amzn1.ask.skill....
	VendorID     string `json:"vendorId"`    // Amazon developer vendor id
	ClientID     string `json:"lwaClientId"` // Login with Amazon security profile
	ClientSecret string `json:"lwaClientSecret"`
	RefreshToken string `json:"lwaRefreshToken"`
	AmazonName   string `json:"amazonName,omitempty"`
	RegisterKey  string `json:"registerKey"` // TVs send it to register (X-Tapper-Key)
	SetupPIN     string `json:"setupPin"`
}

// TV is one screen of one app (a Fire TV running Tapper, a GarysTV screen...).
type TV struct {
	TokenHash string `json:"tokenHash"`
	Name      string `json:"name"`
	App       string `json:"app,omitempty"` // "tapper", "garystv" when the app says
	Created   int64  `json:"created,omitempty"`
}

// DB: linked TVs. Links: Alexa user id -> TV ids, oldest link first.
type DB struct {
	TVs   map[string]*TV      `json:"tvs"`
	Links map[string][]string `json:"links"`
}

type store struct {
	dir string
	mu  sync.Mutex
	cfg Config
	db  DB
}

func openStore(dir string) (*store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("can't create data folder %s: %w", dir, err)
	}
	s := &store{dir: dir, db: DB{TVs: map[string]*TV{}, Links: map[string][]string{}}}
	if b, err := os.ReadFile(filepath.Join(dir, "config.json")); err == nil {
		if err := json.Unmarshal(b, &s.cfg); err != nil {
			return nil, fmt.Errorf("config.json: %w", err)
		}
	}
	if b, err := os.ReadFile(filepath.Join(dir, "data.json")); err == nil {
		if err := s.loadDB(b); err != nil {
			return nil, fmt.Errorf("data.json: %w", err)
		}
	}
	changed := false
	if s.cfg.Invocation == "" {
		s.cfg.Invocation, changed = "tapper", true
	}
	if s.cfg.SkillName == "" {
		s.cfg.SkillName, changed = "Tapper TV", true
	}
	if s.cfg.CertType == "" {
		s.cfg.CertType, changed = "Wildcard", true
	}
	if s.cfg.RegisterKey == "" {
		s.cfg.RegisterKey, changed = randomWords(), true
	}
	if s.cfg.SetupPIN == "" {
		s.cfg.SetupPIN, changed = randomDigits(6), true
	}
	if changed {
		if err := s.saveConfigLocked(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

// loadDB also reads the Node relay's data.json, where a link is one TV id
// (links[user] = "tvId"), so a copied-over file keeps every TV linked.
func (s *store) loadDB(b []byte) error {
	var raw struct {
		TVs   map[string]*TV             `json:"tvs"`
		Links map[string]json.RawMessage `json:"links"`
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	if raw.TVs != nil {
		s.db.TVs = raw.TVs
	}
	for u, v := range raw.Links {
		var one string
		var many []string
		if json.Unmarshal(v, &one) == nil && one != "" {
			s.db.Links[u] = []string{one}
		} else if json.Unmarshal(v, &many) == nil {
			s.db.Links[u] = many
		}
	}
	return nil
}

func (s *store) config() Config { s.mu.Lock(); defer s.mu.Unlock(); return s.cfg }

func (s *store) updateConfig(f func(*Config)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(&s.cfg)
	return s.saveConfigLocked()
}

func (s *store) setupPIN() string { return s.config().SetupPIN }

func (s *store) saveConfigLocked() error {
	return writeJSON(filepath.Join(s.dir, "config.json"), s.cfg)
}
func (s *store) saveDBLocked() error { return writeJSON(filepath.Join(s.dir, "data.json"), s.db) }

// writeJSON replaces the file in one step, so a crash never leaves half a file.
func writeJSON(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ---- TVs and links

func (s *store) addTV(name, app string) (id, token string, err error) {
	id, token = randomUUID(), randomHex(32)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.db.TVs[id] = &TV{TokenHash: hashToken(token), Name: name, App: app, Created: time.Now().Unix()}
	return id, token, s.saveDBLocked()
}

func (s *store) checkTV(id, token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	tv := s.db.TVs[id]
	return tv != nil && id != "" && tv.TokenHash == hashToken(token)
}

func (s *store) tv(id string) (TV, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if tv := s.db.TVs[id]; tv != nil {
		return *tv, true
	}
	return TV{}, false
}

// link adds the TV to the Alexa account's TVs (moved to the end: newest link).
func (s *store) link(user, tvID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	list := without(s.db.Links[user], tvID)
	s.db.Links[user] = append(list, tvID)
	return s.saveDBLocked()
}

func (s *store) linkedTVs(user string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.db.Links[user]...)
}

func (s *store) isLinked(tvID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, l := range s.db.Links {
		for _, t := range l {
			if t == tvID {
				return true
			}
		}
	}
	return false
}

func (s *store) unlinkTV(tvID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for u, l := range s.db.Links {
		if l = without(l, tvID); len(l) == 0 {
			delete(s.db.Links, u)
		} else {
			s.db.Links[u] = l
		}
	}
	return s.saveDBLocked()
}

// forgetTV removes a TV entirely (setup page > Remove).
func (s *store) forgetTV(tvID string) error {
	if err := s.unlinkTV(tvID); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.db.TVs, tvID)
	return s.saveDBLocked()
}

type tvInfo struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	App     string `json:"app"`
	Linked  bool   `json:"linked"`
	Created int64  `json:"created"`
}

func (s *store) listTVs() []tvInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	linked := map[string]bool{}
	for _, l := range s.db.Links {
		for _, t := range l {
			linked[t] = true
		}
	}
	out := []tvInfo{}
	for id, tv := range s.db.TVs {
		out = append(out, tvInfo{ID: id, Name: tv.Name, App: tv.App, Linked: linked[id], Created: tv.Created})
	}
	return out
}

func without(list []string, v string) []string {
	out := list[:0:0]
	for _, x := range list {
		if x != v {
			out = append(out, x)
		}
	}
	return out
}

// ---- random bits

func hashToken(t string) string { h := sha256.Sum256([]byte(t)); return hex.EncodeToString(h[:]) }

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func randomUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func randomInt(max int64) int64 {
	n, _ := rand.Int(rand.Reader, big.NewInt(max))
	return n.Int64()
}

func randomDigits(n int) string {
	var sb strings.Builder
	for i := 0; i < n; i++ {
		sb.WriteByte(byte('0' + randomInt(10)))
	}
	return sb.String()
}

// A relay key that's easy to type with a TV remote: three short words and a number.
func randomWords() string {
	words := strings.Fields("amber basin cedar delta ember fable grove harbor indigo juniper kettle lantern maple north orbit pepper quartz river summit timber umber velvet willow yonder zephyr")
	parts := []string{}
	for i := 0; i < 3; i++ {
		parts = append(parts, words[randomInt(int64(len(words)))])
	}
	return strings.Join(parts, "-") + "-" + randomDigits(2)
}
