package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- a fake Amazon signing chain: test CA -> echo-api.amazon.com

type fakeAmazon struct {
	key   *rsa.PrivateKey
	chain []byte
	roots *x509.CertPool
}

func newFakeAmazon(t *testing.T, dns string) *fakeAmazon {
	caKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	caTpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test Root"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	caDER, err := x509.CreateCertificate(rand.Reader, caTpl, caTpl, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	ca, _ := x509.ParseCertificate(caDER)
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	leafTpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: dns}, DNSNames: []string{dns}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTpl, ca, &key.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	_ = pem.Encode(&buf, &pem.Block{Type: "CERTIFICATE", Bytes: leafDER})
	_ = pem.Encode(&buf, &pem.Block{Type: "CERTIFICATE", Bytes: caDER})
	roots := x509.NewCertPool()
	roots.AddCert(ca)
	return &fakeAmazon{key: key, chain: buf.Bytes(), roots: roots}
}

const testCertURL = "https://s3.amazonaws.com/echo.api/echo-api-cert-12.pem"

func (f *fakeAmazon) sign(body []byte) http.Header {
	sum := sha256.Sum256(body)
	sig, _ := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, sum[:])
	h := http.Header{}
	h.Set("SignatureCertChainUrl", testCertURL)
	h.Set("Signature-256", base64.StdEncoding.EncodeToString(sig))
	return h
}

func (f *fakeAmazon) cache() *certCache {
	c := newCertCache()
	c.roots = f.roots
	c.fetch = func(u string) ([]byte, error) { return f.chain, nil }
	return c
}

func TestCertURLRules(t *testing.T) {
	good := []string{
		"https://s3.amazonaws.com/echo.api/echo-api-cert.pem",
		"https://s3.amazonaws.com:443/echo.api/echo-api-cert.pem",
		"https://s3.amazonaws.com/echo.api/../echo.api/echo-api-cert.pem",
		"HTTPS://s3.amazonaws.com/echo.api/echo-api-cert.pem",
		"https://S3.AMAZONAWS.COM/echo.api/echo-api-cert.pem",
	}
	bad := []string{
		"http://s3.amazonaws.com/echo.api/echo-api-cert.pem",
		"https://notamazon.com/echo.api/echo-api-cert.pem",
		"https://s3.amazonaws.com/EcHo.aPi/echo-api-cert.pem",
		"https://s3.amazonaws.com/invalid.path/echo-api-cert.pem",
		"https://s3.amazonaws.com:563/echo.api/echo-api-cert.pem",
		"https://s3.amazonaws.com/echo.api/../evil/cert.pem",
		"",
	}
	for _, u := range good {
		if !validCertURL(u) {
			t.Errorf("should accept %s", u)
		}
	}
	for _, u := range bad {
		if validCertURL(u) {
			t.Errorf("should reject %s", u)
		}
	}
}

func TestSignature(t *testing.T) {
	fa := newFakeAmazon(t, "echo-api.amazon.com")
	c := fa.cache()
	body := []byte(`{"hello":"world"}`)
	if err := c.verify(fa.sign(body), body); err != nil {
		t.Fatalf("good signature rejected: %v", err)
	}
	if err := c.verify(fa.sign(body), []byte(`{"hello":"wOrld"}`)); err == nil {
		t.Fatal("tampered body accepted")
	}
	h := fa.sign(body)
	h.Set("SignatureCertChainUrl", "https://evil.example.com/echo.api/x.pem")
	if err := c.verify(h, body); err == nil {
		t.Fatal("foreign cert URL accepted")
	}
	// A certificate for another name, even from a trusted root, is refused.
	other := newFakeAmazon(t, "evil.example.com")
	if err := other.cache().verify(other.sign(body), body); err == nil {
		t.Fatal("cert for another host accepted")
	}
}

// ---- end to end over HTTP

type rig struct {
	t   *testing.T
	s   *server
	ts  *httptest.Server
	fa  *fakeAmazon
	dir string
}

func newRig(t *testing.T) *rig {
	dir := t.TempDir()
	s, err := newServer(dir, 8787)
	if err != nil {
		t.Fatal(err)
	}
	fa := newFakeAmazon(t, "echo-api.amazon.com")
	s.certs = fa.cache()
	_ = s.st.updateConfig(func(c *Config) { c.SkillID = "amzn1.ask.skill.test" })
	ts := httptest.NewServer(s.handler())
	t.Cleanup(ts.Close)
	return &rig{t: t, s: s, ts: ts, fa: fa, dir: dir}
}

func (r *rig) do(method, path string, body any, hdr map[string]string) (int, map[string]any) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, r.ts.URL+path, rd)
	req.Header.Set("Content-Type", "application/json")
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		r.t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

type tvCreds struct{ id, token string }

func (c tvCreds) hdr() map[string]string {
	return map[string]string{"X-Tapper-Tv": c.id, "Authorization": "Bearer " + c.token}
}

func (r *rig) register(name string) tvCreds {
	code, out := r.do("POST", "/tv/register", map[string]string{"name": name}, map[string]string{"X-Tapper-Key": r.s.st.config().RegisterKey})
	if code != 200 {
		r.t.Fatalf("register: %d %v", code, out)
	}
	return tvCreds{out["tvId"].(string), out["token"].(string)}
}

func (r *rig) alexa(user, reqType, intent string, slots map[string]string, ts time.Time, appID string) (int, string) {
	sl := map[string]any{}
	for k, v := range slots {
		sl[k] = map[string]string{"name": k, "value": v}
	}
	env := map[string]any{
		"version": "1.0",
		"context": map[string]any{"System": map[string]any{"application": map[string]string{"applicationId": appID}, "user": map[string]string{"userId": user}}},
		"request": map[string]any{"type": reqType, "timestamp": ts.UTC().Format(time.RFC3339), "intent": map[string]any{"name": intent, "slots": sl}},
	}
	body, _ := json.Marshal(env)
	req, _ := http.NewRequest("POST", r.ts.URL+"/alexa", bytes.NewReader(body))
	for k, v := range r.fa.sign(body) {
		req.Header[k] = v
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		r.t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Response struct {
			OutputSpeech struct{ Text string } `json:"outputSpeech"`
		} `json:"response"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out.Response.OutputSpeech.Text
}

func (r *rig) say(user, intent string, slots map[string]string) string {
	code, text := r.alexa(user, "IntentRequest", intent, slots, time.Now(), "amzn1.ask.skill.test")
	if code != 200 {
		r.t.Fatalf("alexa %s: HTTP %d", intent, code)
	}
	return text
}

func (r *rig) next(c tvCreds) []map[string]any {
	code, out := r.do("GET", "/tv/next", nil, c.hdr())
	if code != 200 {
		r.t.Fatalf("next: %d", code)
	}
	var cmds []map[string]any
	for _, x := range out["commands"].([]any) {
		cmds = append(cmds, x.(map[string]any))
	}
	return cmds
}

func TestRegisterNeedsKey(t *testing.T) {
	r := newRig(t)
	if code, _ := r.do("POST", "/tv/register", map[string]string{"name": "x"}, nil); code != 403 {
		t.Fatalf("register without key: %d", code)
	}
	if code, _ := r.do("GET", "/tv/status", nil, tvCreds{"nope", "nope"}.hdr()); code != 401 {
		t.Fatalf("unknown tv: %d", code)
	}
}

func TestAlexaRejects(t *testing.T) {
	r := newRig(t)
	if code, _ := r.alexa("u", "LaunchRequest", "", nil, time.Now().Add(-5*time.Minute), "amzn1.ask.skill.test"); code != 400 {
		t.Fatalf("stale request: %d", code)
	}
	if code, _ := r.alexa("u", "LaunchRequest", "", nil, time.Now(), "amzn1.ask.skill.other"); code != 400 {
		t.Fatalf("other skill: %d", code)
	}
	req, _ := http.NewRequest("POST", r.ts.URL+"/alexa", strings.NewReader(`{}`))
	resp, _ := http.DefaultClient.Do(req)
	if resp.StatusCode != 400 {
		t.Fatalf("unsigned: %d", resp.StatusCode)
	}
}

func TestLinkTuneAndCommands(t *testing.T) {
	r := newRig(t)
	tv := r.register("Fire TV Living room")
	if txt := r.say("user1", "TuneIntent", map[string]string{"channel": "ESPN"}); !strings.Contains(txt, "isn't linked") {
		t.Fatalf("unlinked: %q", txt)
	}
	_, pair := r.do("POST", "/tv/pair", nil, tv.hdr())
	code := pair["code"].(string)
	if len(code) != 6 || code[0] == '0' {
		t.Fatalf("code %q", code)
	}
	if txt := r.say("user1", "LinkIntent", map[string]string{"code": "999999"}); !strings.Contains(txt, "didn't work") {
		t.Fatalf("wrong code: %q", txt)
	}
	if txt := r.say("user1", "LinkIntent", map[string]string{"code": code}); !strings.Contains(txt, "Linked to Fire TV Living room") {
		t.Fatalf("link: %q", txt)
	}
	if txt := r.say("user2", "LinkIntent", map[string]string{"code": code}); !strings.Contains(txt, "didn't work") {
		t.Fatalf("code reused: %q", txt)
	}
	if c := r.next(tv); len(c) != 1 || c[0]["type"] != "linked" {
		t.Fatalf("linked cmd: %v", c)
	}
	_, st := r.do("GET", "/tv/status", nil, tv.hdr())
	if st["linked"] != true {
		t.Fatal("status not linked")
	}

	// The TV is "on screen" (polled just now), so Alexa doesn't warn.
	if txt := r.say("user1", "TuneIntent", map[string]string{"channel": "Fox News"}); txt != "Putting on Fox News." {
		t.Fatalf("tune: %q", txt)
	}
	r.say("user1", "ChannelNumberIntent", map[string]string{"number": "204"})
	r.say("user1", "TuneIntent", map[string]string{"channel": "7"})
	r.say("user1", "AMAZON.PauseIntent", nil)
	r.say("user1", "GuideIntent", nil)
	got := r.next(tv)
	types := []string{}
	for _, c := range got {
		types = append(types, c["type"].(string))
	}
	if strings.Join(types, ",") != "tune,number,number,pause,guide" || got[0]["query"] != "Fox News" || got[1]["number"].(float64) != 204 || got[2]["number"].(float64) != 7 {
		t.Fatalf("commands: %v", got)
	}

	// Long poll wakes up as soon as Alexa sends something.
	done := make(chan []map[string]any)
	go func() { done <- r.next(tv) }()
	time.Sleep(200 * time.Millisecond)
	r.say("user1", "ChannelUpIntent", nil)
	select {
	case c := <-done:
		if len(c) != 1 || c[0]["type"] != "channelUp" {
			t.Fatalf("woken with %v", c)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("long poll didn't wake")
	}

	r.do("POST", "/tv/unlink", nil, tv.hdr())
	if txt := r.say("user1", "AMAZON.PauseIntent", nil); !strings.Contains(txt, "isn't linked") {
		t.Fatalf("after unlink: %q", txt)
	}
}

func TestPicksTheTVThatIsOpen(t *testing.T) {
	r := newRig(t)
	a := r.register("Fire TV Bedroom")
	b := r.register("GarysTV Living room")
	for _, tv := range []tvCreds{a, b} {
		_, pair := r.do("POST", "/tv/pair", nil, tv.hdr())
		r.say("u", "LinkIntent", map[string]string{"code": pair["code"].(string)})
	}
	if tvb, _ := r.s.st.tv(b.id); tvb.App != "garystv" {
		t.Fatalf("app guess: %q", tvb.App)
	}
	// Nobody has the app open: goes to the newest link, with a warning.
	r.s.mb = newMailbox()
	if txt := r.say("u", "AMAZON.PauseIntent", nil); !strings.Contains(txt, "Gary's TV Living room") || !strings.Contains(txt, "isn't open") {
		t.Fatalf("offline: %q", txt)
	}
	r.s.mb = newMailbox()
	// Only the bedroom TV is open: it gets the command even though it was linked first.
	r.s.mb.mu.Lock()
	r.s.mb.touch(a.id)
	r.s.mb.mu.Unlock()
	if txt := r.say("u", "AMAZON.PauseIntent", nil); txt != "Paused." {
		t.Fatalf("open tv: %q", txt)
	}
	if c := r.next(a); len(c) != 1 || c[0]["type"] != "pause" {
		t.Fatalf("bedroom got %v", c)
	}
	// Both open: the one opened most recently wins.
	time.Sleep(10 * time.Millisecond)
	r.s.mb.mu.Lock()
	r.s.mb.touch(b.id)
	r.s.mb.mu.Unlock()
	r.say("u", "AMAZON.ResumeIntent", nil)
	if c := r.next(b); len(c) != 1 || c[0]["type"] != "resume" {
		t.Fatalf("living room got %v", c)
	}
}

func TestOldCommandsDropped(t *testing.T) {
	m := newMailbox()
	m.send("tv", command{Type: "pause"})
	m.mu.Lock()
	m.queues["tv"][0].At -= 2 * 60_000
	m.mu.Unlock()
	if got := m.take("tv"); len(got) != 0 {
		t.Fatalf("stale command kept: %v", got)
	}
}

func TestReadsNodeRelayData(t *testing.T) {
	dir := t.TempDir()
	old := `{"tvs":{"tv1":{"tokenHash":"` + hashToken("secret") + `","name":"Fire TV"}},"links":{"amzn1.ask.account.X":"tv1"}}`
	if err := os.WriteFile(filepath.Join(dir, "data.json"), []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !st.checkTV("tv1", "secret") || !st.isLinked("tv1") || st.linkedTVs("amzn1.ask.account.X")[0] != "tv1" {
		t.Fatal("old data not read")
	}
}

func TestSetupIsLocalAndPINProtected(t *testing.T) {
	r := newRig(t)
	if code, _ := r.do("GET", "/setup/api/state", nil, nil); code != 401 {
		t.Fatalf("no pin: %d", code)
	}
	if code, _ := r.do("POST", "/setup/api/login", map[string]string{"pin": "000000x"}, nil); code != 403 {
		t.Fatalf("bad pin: %d", code)
	}
	// Through the tunnel: not found at all, even with the right PIN.
	if code, _ := r.do("POST", "/setup/api/login", map[string]string{"pin": r.s.st.setupPIN()}, map[string]string{"Cf-Connecting-Ip": "203.0.113.9"}); code != 404 {
		t.Fatalf("via tunnel: %d", code)
	}
	req, _ := http.NewRequest("GET", r.ts.URL+"/setup", nil)
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != 404 {
		t.Fatalf("page via proxy: %d", resp.StatusCode)
	}
}

func TestInvocationRules(t *testing.T) {
	if v, err := checkInvocation("  Tapper   TV "); err != nil || v != "tapper tv" {
		t.Fatalf("%q %v", v, err)
	}
	for _, bad := range []string{"", "alexa tv", "tv 2", "launch tv", "x"} {
		if _, err := checkInvocation(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

// ---- setup against a fake Amazon (Login with Amazon + Skill Management API)

func TestSetupCreatesSkill(t *testing.T) {
	var mu sync.Mutex
	calls := []string{}
	var gotManifest, gotModel map[string]any
	polls := 0
	amazon := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		calls = append(calls, req.Method+" "+req.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case req.URL.Path == "/auth/o2/token":
			_ = req.ParseForm()
			if req.Form.Get("client_secret") != "sec" {
				w.WriteHeader(401)
				_, _ = w.Write([]byte(`{"error":"invalid_client"}`))
				return
			}
			_, _ = w.Write([]byte(`{"access_token":"Atza|abc","refresh_token":"Atzr|r1"}`))
		case req.URL.Path == "/v1/vendors":
			if req.Header.Get("Authorization") != "Atza|abc" {
				w.WriteHeader(401)
				return
			}
			_, _ = w.Write([]byte(`{"vendors":[{"id":"M123","name":"Tapper Dev"}]}`))
		case req.Method == "POST" && req.URL.Path == "/v1/skills":
			_ = json.NewDecoder(req.Body).Decode(&gotManifest)
			w.WriteHeader(202)
			_, _ = w.Write([]byte(`{"skillId":"amzn1.ask.skill.new"}`))
		case strings.HasSuffix(req.URL.Path, "/status"):
			polls++
			st := "IN_PROGRESS"
			if polls%2 == 0 {
				st = "SUCCEEDED"
			}
			if req.URL.Query().Get("resource") == "manifest" {
				_, _ = w.Write([]byte(`{"manifest":{"lastUpdateRequest":{"status":"` + st + `"}}}`))
			} else {
				_, _ = w.Write([]byte(`{"interactionModel":{"en-US":{"lastUpdateRequest":{"status":"` + st + `"}}}}`))
			}
		case strings.HasSuffix(req.URL.Path, "/interactionModel/locales/en-US"):
			_ = json.NewDecoder(req.Body).Decode(&gotModel)
			w.WriteHeader(202)
		case req.Method == "PUT" && strings.HasSuffix(req.URL.Path, "/stages/development/manifest"):
			w.WriteHeader(202)
		case strings.HasSuffix(req.URL.Path, "/enablement"):
			w.WriteHeader(204)
		default:
			w.WriteHeader(404)
		}
	}))
	defer amazon.Close()
	smapiBase, lwaToken = amazon.URL, amazon.URL+"/auth/o2/token"
	defer func() { smapiBase, lwaToken = "https://api.amazonalexa.com", "https://api.amazon.com/auth/o2/token" }()

	dir := t.TempDir()
	s, _ := newServer(dir, 8787)
	_ = s.st.updateConfig(func(c *Config) {
		c.PublicURL, c.ClientID, c.ClientSecret, c.Invocation = "https://alexa.tapper.co", "cid", "sec", "tapper"
	})
	s.setup.states["st1"] = time.Now().Add(time.Minute)
	if err := s.finishAmazon("code1", "st1"); err != nil {
		t.Fatal(err)
	}
	if err := s.finishAmazon("code1", "st1"); err == nil {
		t.Fatal("state reused")
	}
	if err := s.buildSkill(); err != nil {
		t.Fatalf("buildSkill: %v\n%v", err, s.setup.job.Lines)
	}
	cfg := s.st.config()
	if cfg.SkillID != "amzn1.ask.skill.new" || cfg.VendorID != "M123" || cfg.RefreshToken != "Atzr|r1" {
		t.Fatalf("config: %+v", cfg)
	}
	ep := gotManifest["manifest"].(map[string]any)["apis"].(map[string]any)["custom"].(map[string]any)["endpoint"].(map[string]any)
	if ep["uri"] != "https://alexa.tapper.co/alexa" || ep["sslCertificateType"] != "Wildcard" || gotManifest["vendorId"] != "M123" {
		t.Fatalf("manifest: %v", gotManifest)
	}
	if gotModel["interactionModel"].(map[string]any)["languageModel"].(map[string]any)["invocationName"] != "tapper" {
		t.Fatalf("model: %v", gotModel)
	}
	joined := strings.Join(calls, "\n")
	if !strings.Contains(joined, "PUT /v1/skills/amzn1.ask.skill.new/stages/development/enablement") {
		t.Fatalf("not enabled: %s", joined)
	}

	// Running it again updates the same skill instead of making another.
	calls = nil
	if err := s.buildSkill(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(strings.Join(calls, "\n"), "POST /v1/skills\n") || !strings.Contains(strings.Join(calls, "\n"), "PUT /v1/skills/amzn1.ask.skill.new/stages/development/manifest") {
		t.Fatalf("second run: %v", calls)
	}

	// The authorize link carries our client, scopes, and the localhost return address.
	q, _ := url.Parse(func() string {
		rec := httptest.NewRecorder()
		s.setupAmazonURL(rec, httptest.NewRequest("GET", "/setup/api/amazon-url", nil))
		var o map[string]string
		_ = json.Unmarshal(rec.Body.Bytes(), &o)
		return o["url"]
	}())
	if q.Query().Get("client_id") != "cid" || q.Query().Get("redirect_uri") != "http://localhost:8787/setup/amazon" || !strings.Contains(q.Query().Get("scope"), "alexa::ask:skills:readwrite") {
		t.Fatalf("authorize url: %v", q)
	}
}
