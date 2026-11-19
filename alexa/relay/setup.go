package main

// /setup: the one-time setup page. Reachable only from this computer or the home
// network (never through the tunnel), and only with the setup PIN.
//
// What it automates, after you sign in with Amazon:
//   1. finds your Amazon developer (vendor) id
//   2. creates the custom skill (or updates it), pointed at <public address>/alexa
//   3. uploads the interaction model and waits for it to build
//   4. turns on testing in Development, which makes it work on every Echo on
//      your Amazon account without ever publishing it
//   5. lets you test it by typing a phrase (Alexa's simulator)
//
// Amazon's APIs used: Login with Amazon (OAuth) and the Skill Management API.

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

//go:embed setup.html
var setupHTML []byte

var (
	lwaAuthorize = "https://www.amazon.com/ap/oa"
	lwaToken     = "https://api.amazon.com/auth/o2/token"
)

const (
	lwaScopes = "alexa::ask:skills:readwrite alexa::ask:models:readwrite alexa::ask:skills:test"
)

var smapiBase = "https://api.amazonalexa.com"

type setupState struct {
	mu       sync.Mutex
	sessions map[string]time.Time // cookie -> expiry
	states   map[string]time.Time // OAuth state -> expiry
	job      jobLog
	tries    int
	tryReset time.Time
	http     *http.Client
}

type jobLog struct {
	Running bool     `json:"running"`
	Lines   []string `json:"lines"`
	OK      bool     `json:"ok"`
	Error   string   `json:"error,omitempty"`
}

func newSetupState() *setupState {
	return &setupState{sessions: map[string]time.Time{}, states: map[string]time.Time{}, http: &http.Client{Timeout: 30 * time.Second}}
}

// ---- who may open setup

var privateNets = func() []*net.IPNet {
	var out []*net.IPNet
	for _, c := range []string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10", "169.254.0.0/16"} {
		_, n, _ := net.ParseCIDR(c)
		out = append(out, n)
	}
	return out
}()

// localOnly: a request straight from this computer or the home network/VPN, not
// one forwarded by the Cloudflare tunnel or another proxy.
func (s *server) localOnly(r *http.Request) bool {
	for _, h := range []string{"Cf-Connecting-Ip", "Cf-Ray", "X-Forwarded-For", "X-Real-Ip", "Forwarded", "Cdn-Loop"} {
		if r.Header.Get(h) != "" {
			return false
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, n := range privateNets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

func (s *server) setupRoutes(mux *http.ServeMux) {
	page := func(w http.ResponseWriter, r *http.Request) {
		if !s.localOnly(r) {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Frame-Options", "DENY")
		_, _ = w.Write(setupHTML)
	}
	mux.HandleFunc("GET /setup", page)
	mux.HandleFunc("GET /setup/{$}", page)
	mux.HandleFunc("GET /setup/amazon", s.amazonCallback)
	mux.HandleFunc("POST /setup/api/login", s.setupLogin)

	api := func(pattern string, h http.HandlerFunc) { mux.HandleFunc(pattern, s.setupAuth(h)) }
	api("GET /setup/api/state", s.setupStateAPI)
	api("POST /setup/api/config", s.setupConfig)
	api("POST /setup/api/check-url", s.setupCheckURL)
	api("GET /setup/api/amazon-url", s.setupAmazonURL)
	api("POST /setup/api/amazon-paste", s.setupAmazonPaste)
	api("POST /setup/api/amazon-signout", s.setupAmazonSignOut)
	api("POST /setup/api/create", s.setupCreate)
	api("POST /setup/api/test", s.setupTest)
	api("POST /setup/api/tv/remove", s.setupRemoveTV)
	api("POST /setup/api/new-key", s.setupNewKey)
}

func (s *server) setupLogin(w http.ResponseWriter, r *http.Request) {
	if !s.localOnly(r) {
		http.NotFound(w, r)
		return
	}
	ss := s.setup
	ss.mu.Lock()
	if time.Now().After(ss.tryReset) {
		ss.tries, ss.tryReset = 0, time.Now().Add(15*time.Minute)
	}
	ss.tries++
	tooMany := ss.tries > 10
	ss.mu.Unlock()
	if tooMany {
		writeJSONResponse(w, 429, map[string]string{"error": "Too many tries. Wait 15 minutes."})
		return
	}
	var b struct{ PIN string }
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&b)
	if strings.TrimSpace(b.PIN) != s.st.setupPIN() {
		writeJSONResponse(w, 403, map[string]string{"error": "That PIN isn't right."})
		return
	}
	tok := randomHex(24)
	ss.mu.Lock()
	ss.sessions[tok] = time.Now().Add(12 * time.Hour)
	ss.tries = 0
	ss.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: "relay_setup", Value: tok, Path: "/setup", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 12 * 3600})
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func (s *server) setupAuth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.localOnly(r) {
			http.NotFound(w, r)
			return
		}
		c, err := r.Cookie("relay_setup")
		ss := s.setup
		ss.mu.Lock()
		exp, ok := time.Time{}, false
		if err == nil {
			exp, ok = ss.sessions[c.Value]
		}
		ss.mu.Unlock()
		if !ok || time.Now().After(exp) {
			writeJSONResponse(w, 401, map[string]string{"error": "Enter the setup PIN."})
			return
		}
		// Same-origin JSON only (no cross-site form posts).
		if r.Method == "POST" && !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			writeJSONResponse(w, 415, map[string]string{"error": "JSON only"})
			return
		}
		h(w, r)
	}
}

func readJSON(r *http.Request, v any) error {
	return json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(v)
}

// ---- state and settings

func (s *server) redirectURI() string { return fmt.Sprintf("http://localhost:%d/setup/amazon", s.port) }

func (s *server) setupStateAPI(w http.ResponseWriter, r *http.Request) {
	cfg := s.st.config()
	s.setup.mu.Lock()
	job := s.setup.job
	job.Lines = append([]string{}, job.Lines...)
	s.setup.mu.Unlock()
	writeJSONResponse(w, 200, map[string]any{
		"version":     version,
		"publicUrl":   cfg.PublicURL,
		"invocation":  cfg.Invocation,
		"skillName":   cfg.SkillName,
		"certType":    cfg.CertType,
		"skillId":     cfg.SkillID,
		"clientId":    cfg.ClientID,
		"hasSecret":   cfg.ClientSecret != "",
		"signedIn":    cfg.RefreshToken != "",
		"amazonName":  cfg.AmazonName,
		"registerKey": cfg.RegisterKey,
		"redirectUri": s.redirectURI(),
		"port":        s.port,
		"tvs":         s.tvsWithOnline(),
		"job":         job,
	})
}

func (s *server) tvsWithOnline() []map[string]any {
	out := []map[string]any{}
	for _, t := range s.st.listTVs() {
		out = append(out, map[string]any{"id": t.ID, "name": t.Name, "app": t.App, "linked": t.Linked, "online": s.mb.online(t.ID), "created": t.Created})
	}
	return out
}

func (s *server) setupConfig(w http.ResponseWriter, r *http.Request) {
	var b struct {
		PublicURL, Invocation, SkillName, CertType, ClientID, ClientSecret *string
	}
	if err := readJSON(r, &b); err != nil {
		writeJSONResponse(w, 400, map[string]string{"error": "bad request"})
		return
	}
	var bad error
	err := s.st.updateConfig(func(c *Config) {
		if b.PublicURL != nil {
			u := strings.TrimRight(strings.TrimSpace(*b.PublicURL), "/")
			u = strings.TrimSuffix(u, "/alexa")
			if u != "" && !strings.HasPrefix(u, "https://") {
				bad = errors.New("The public address has to start with https:// (Alexa only calls HTTPS).")
				return
			}
			c.PublicURL = u
		}
		if b.Invocation != nil {
			inv, err := checkInvocation(*b.Invocation)
			if err != nil {
				bad = err
				return
			}
			c.Invocation = inv
		}
		if b.SkillName != nil && strings.TrimSpace(*b.SkillName) != "" {
			c.SkillName = truncate(strings.TrimSpace(*b.SkillName), 50)
		}
		if b.CertType != nil && (*b.CertType == "Wildcard" || *b.CertType == "Trusted") {
			c.CertType = *b.CertType
		}
		if b.ClientID != nil {
			id := strings.TrimSpace(*b.ClientID)
			if id != c.ClientID {
				c.RefreshToken, c.AmazonName = "", ""
			}
			c.ClientID = id
		}
		if b.ClientSecret != nil && strings.TrimSpace(*b.ClientSecret) != "" {
			c.ClientSecret = strings.TrimSpace(*b.ClientSecret)
		}
	})
	if bad != nil {
		writeJSONResponse(w, 400, map[string]string{"error": bad.Error()})
		return
	}
	if err != nil {
		writeJSONResponse(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

// Amazon's invocation name rules, the ones people run into: lowercase letters
// and spaces, no "alexa"/"echo"/"skill"/"app", and not something like "launch".
func checkInvocation(v string) (string, error) {
	inv := strings.Join(strings.Fields(strings.ToLower(v)), " ")
	if inv == "" {
		return "", errors.New("Enter an invocation name, like tapper.")
	}
	for _, r := range inv {
		if !(r >= 'a' && r <= 'z') && r != ' ' && r != '\'' && r != '.' {
			return "", errors.New("The invocation name can only use letters and spaces (spell numbers out).")
		}
	}
	for _, w := range strings.Fields(inv) {
		switch w {
		case "alexa", "amazon", "echo", "skill", "app", "launch", "ask", "tell", "open", "load", "begin", "enable":
			return "", fmt.Errorf("Amazon doesn't allow %q in an invocation name.", w)
		}
	}
	if len(inv) < 2 || len(inv) > 50 {
		return "", errors.New("The invocation name has to be 2 to 50 letters.")
	}
	return inv, nil
}

// Asks the public address for /health and checks this very relay answers.
func (s *server) setupCheckURL(w http.ResponseWriter, r *http.Request) {
	u := s.st.config().PublicURL
	if u == "" {
		writeJSONResponse(w, 400, map[string]string{"error": "Enter the public address first."})
		return
	}
	resp, err := s.setup.http.Get(u + "/health")
	if err != nil {
		writeJSONResponse(w, 200, map[string]any{"ok": false, "message": "Couldn't reach " + u + ": " + err.Error()})
		return
	}
	defer resp.Body.Close()
	var h struct {
		App      string `json:"app"`
		Instance string `json:"instance"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 8<<10)).Decode(&h)
	switch {
	case h.Instance == s.instance:
		writeJSONResponse(w, 200, map[string]any{"ok": true, "message": u + " reaches this relay."})
	case h.App == "tapper-alexa-relay":
		writeJSONResponse(w, 200, map[string]any{"ok": false, "message": u + " reaches a different Tapper Alexa relay (another computer?). Point it at this one."})
	default:
		writeJSONResponse(w, 200, map[string]any{"ok": false, "message": fmt.Sprintf("%s answered HTTP %d, but not from this relay. Check the tunnel sends it to http://localhost:%d.", u, resp.StatusCode, s.port)})
	}
}

// ---- Login with Amazon

func (s *server) setupAmazonURL(w http.ResponseWriter, r *http.Request) {
	cfg := s.st.config()
	if cfg.ClientID == "" || cfg.ClientSecret == "" {
		writeJSONResponse(w, 400, map[string]string{"error": "Enter the security profile's Client ID and Client Secret first (step 2)."})
		return
	}
	state := randomHex(16)
	s.setup.mu.Lock()
	for k, exp := range s.setup.states {
		if time.Now().After(exp) {
			delete(s.setup.states, k)
		}
	}
	s.setup.states[state] = time.Now().Add(15 * time.Minute)
	s.setup.mu.Unlock()
	q := url.Values{"client_id": {cfg.ClientID}, "scope": {lwaScopes}, "response_type": {"code"}, "redirect_uri": {s.redirectURI()}, "state": {state}}
	writeJSONResponse(w, 200, map[string]string{"url": lwaAuthorize + "?" + q.Encode()})
}

// Amazon sends the browser here after sign-in, when the browser is on this computer.
func (s *server) amazonCallback(w http.ResponseWriter, r *http.Request) {
	if !s.localOnly(r) {
		http.NotFound(w, r)
		return
	}
	msg := "Signed in with Amazon. You can close this tab and go back to setup."
	if e := r.URL.Query().Get("error"); e != "" {
		msg = "Amazon sign-in didn't finish: " + e + " " + r.URL.Query().Get("error_description")
	} else if err := s.finishAmazon(r.URL.Query().Get("code"), r.URL.Query().Get("state")); err != nil {
		msg = "Amazon sign-in didn't finish: " + err.Error()
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><meta name=viewport content="width=device-width"><body style="font:16px system-ui;background:#0b0f17;color:#e8ecf3;padding:40px"><p>%s</p><p><a style="color:#7cc4ff" href="/setup">Back to setup</a></p><script>try{window.opener&&window.opener.postMessage("amazon-done","*")}catch(e){}</script>`, htmlEscape(msg))
}

// The browser was on another computer: Amazon's redirect to localhost failed there,
// and the person pasted that address here.
func (s *server) setupAmazonPaste(w http.ResponseWriter, r *http.Request) {
	var b struct{ URL string }
	if err := readJSON(r, &b); err != nil {
		writeJSONResponse(w, 400, map[string]string{"error": "bad request"})
		return
	}
	u, err := url.Parse(strings.TrimSpace(b.URL))
	if err != nil || u.Query().Get("code") == "" {
		msg := "That address has no sign-in code in it. Copy the whole address from the tab Amazon sent you to."
		if u != nil && u.Query().Get("error") != "" {
			msg = "Amazon said: " + u.Query().Get("error") + " " + u.Query().Get("error_description")
		}
		writeJSONResponse(w, 400, map[string]string{"error": msg})
		return
	}
	if err := s.finishAmazon(u.Query().Get("code"), u.Query().Get("state")); err != nil {
		writeJSONResponse(w, 400, map[string]string{"error": err.Error()})
		return
	}
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func (s *server) setupAmazonSignOut(w http.ResponseWriter, r *http.Request) {
	_ = s.st.updateConfig(func(c *Config) { c.RefreshToken, c.AmazonName = "", "" })
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func (s *server) finishAmazon(code, state string) error {
	s.setup.mu.Lock()
	exp, ok := s.setup.states[state]
	delete(s.setup.states, state)
	s.setup.mu.Unlock()
	if !ok || time.Now().After(exp) {
		return errors.New("this sign-in link is old or was already used. Start sign-in again from the setup page")
	}
	cfg := s.st.config()
	tok, err := s.lwaTokenCall(url.Values{"grant_type": {"authorization_code"}, "code": {code}, "redirect_uri": {s.redirectURI()}, "client_id": {cfg.ClientID}, "client_secret": {cfg.ClientSecret}})
	if err != nil {
		return err
	}
	s.logf("setup: signed in with Amazon")
	return s.st.updateConfig(func(c *Config) { c.RefreshToken, c.AmazonName = tok.RefreshToken, "" })
}

type lwaTok struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Error        string `json:"error"`
	Description  string `json:"error_description"`
}

func (s *server) lwaTokenCall(form url.Values) (*lwaTok, error) {
	resp, err := s.setup.http.PostForm(lwaToken, form)
	if err != nil {
		return nil, fmt.Errorf("couldn't reach Amazon: %w", err)
	}
	defer resp.Body.Close()
	var t lwaTok
	_ = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&t)
	if resp.StatusCode != 200 || t.AccessToken == "" {
		if t.Error == "invalid_client" {
			return nil, errors.New("Amazon didn't accept the Client ID / Client Secret. Copy them again from the security profile's Web Settings")
		}
		return nil, fmt.Errorf("Amazon sign-in failed (HTTP %d %s %s)", resp.StatusCode, t.Error, t.Description)
	}
	if t.RefreshToken == "" {
		t.RefreshToken = form.Get("refresh_token")
	}
	return &t, nil
}

func (s *server) accessToken() (string, error) {
	cfg := s.st.config()
	if cfg.RefreshToken == "" {
		return "", errors.New("sign in with Amazon first")
	}
	t, err := s.lwaTokenCall(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {cfg.RefreshToken}, "client_id": {cfg.ClientID}, "client_secret": {cfg.ClientSecret}})
	if err != nil {
		return "", err
	}
	if t.RefreshToken != cfg.RefreshToken {
		_ = s.st.updateConfig(func(c *Config) { c.RefreshToken = t.RefreshToken })
	}
	return t.AccessToken, nil
}

// ---- Skill Management API

type smapi struct {
	s     *server
	token string
}

func (a *smapi) do(method, path string, body any, out any) (int, http.Header, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
		rd = strings.NewReader(string(b))
	}
	req, err := http.NewRequest(method, smapiBase+path, rd)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", a.token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := a.s.setup.http.Do(req)
	if err != nil {
		return 0, nil, fmt.Errorf("couldn't reach Amazon: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		var e struct {
			Message    string `json:"message"`
			Violations []struct {
				Message string `json:"message"`
			} `json:"violations"`
		}
		_ = json.Unmarshal(raw, &e)
		msg := e.Message
		for _, v := range e.Violations {
			msg += " " + v.Message
		}
		if msg == "" {
			msg = strings.TrimSpace(string(raw))
		}
		return resp.StatusCode, resp.Header, fmt.Errorf("Amazon answered HTTP %d: %s", resp.StatusCode, truncate(msg, 400))
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return resp.StatusCode, resp.Header, fmt.Errorf("unexpected answer from Amazon: %w", err)
		}
	}
	return resp.StatusCode, resp.Header, nil
}

func (s *server) say(line string) {
	s.logf("setup: %s", line)
	s.setup.mu.Lock()
	s.setup.job.Lines = append(s.setup.job.Lines, time.Now().Format("15:04:05")+"  "+line)
	s.setup.mu.Unlock()
}

func (s *server) setupCreate(w http.ResponseWriter, r *http.Request) {
	cfg := s.st.config()
	switch {
	case cfg.PublicURL == "":
		writeJSONResponse(w, 400, map[string]string{"error": "Enter the public address first (step 1)."})
		return
	case cfg.RefreshToken == "":
		writeJSONResponse(w, 400, map[string]string{"error": "Sign in with Amazon first (step 3)."})
		return
	}
	s.setup.mu.Lock()
	if s.setup.job.Running {
		s.setup.mu.Unlock()
		writeJSONResponse(w, 409, map[string]string{"error": "Already working on it."})
		return
	}
	s.setup.job = jobLog{Running: true}
	s.setup.mu.Unlock()
	go func() {
		err := s.buildSkill()
		s.setup.mu.Lock()
		s.setup.job.Running = false
		s.setup.job.OK = err == nil
		if err != nil {
			s.setup.job.Error = err.Error()
		}
		s.setup.mu.Unlock()
		if err != nil {
			s.say("Stopped: " + err.Error())
		}
	}()
	writeJSONResponse(w, 202, map[string]bool{"started": true})
}

func (s *server) buildSkill() error {
	s.say("Signing in to Amazon's Skill Management API...")
	tok, err := s.accessToken()
	if err != nil {
		return err
	}
	a := &smapi{s: s, token: tok}
	cfg := s.st.config()

	if cfg.VendorID == "" {
		var v struct {
			Vendors []struct{ ID, Name string } `json:"vendors"`
		}
		if _, _, err := a.do("GET", "/v1/vendors", nil, &v); err != nil {
			return err
		}
		if len(v.Vendors) == 0 {
			return errors.New("this Amazon account has no Alexa developer account yet. Sign in once at developer.amazon.com/alexa/console/ask with the same Amazon account (it's free), then try again")
		}
		cfg.VendorID = v.Vendors[0].ID
		_ = s.st.updateConfig(func(c *Config) { c.VendorID = cfg.VendorID })
		_ = s.st.updateConfig(func(c *Config) { c.AmazonName = v.Vendors[0].Name })
		s.say("Developer account: " + v.Vendors[0].Name)
	}

	manifest := skillManifest(cfg)
	if cfg.SkillID != "" {
		code, _, err := a.do("PUT", "/v1/skills/"+cfg.SkillID+"/stages/development/manifest", manifest, nil)
		if code == 404 {
			s.say("The skill made earlier is gone; making a new one.")
			cfg.SkillID = ""
		} else if err != nil {
			return err
		} else {
			s.say("Updating skill " + cfg.SkillID + "...")
		}
	}
	if cfg.SkillID == "" {
		body := map[string]any{"vendorId": cfg.VendorID, "manifest": manifest["manifest"]}
		var out struct {
			SkillID string `json:"skillId"`
		}
		if _, _, err := a.do("POST", "/v1/skills", body, &out); err != nil {
			return err
		}
		if out.SkillID == "" {
			return errors.New("Amazon didn't return a skill id")
		}
		cfg.SkillID = out.SkillID
		if err := s.st.updateConfig(func(c *Config) { c.SkillID = out.SkillID }); err != nil {
			return err
		}
		s.say(fmt.Sprintf("Created skill \"%s\" (%s).", cfg.SkillName, cfg.SkillID))
	}
	if err := s.waitStatus(a, cfg.SkillID, "manifest"); err != nil {
		return err
	}
	s.say("Endpoint set to " + cfg.PublicURL + "/alexa (" + cfg.CertType + " certificate).")

	s.say(fmt.Sprintf("Uploading the voice model (invocation \"%s\")...", cfg.Invocation))
	if _, _, err := a.do("PUT", "/v1/skills/"+cfg.SkillID+"/stages/development/interactionModel/locales/en-US", interactionModel(cfg), nil); err != nil {
		return err
	}
	if err := s.waitStatus(a, cfg.SkillID, "interactionModel"); err != nil {
		return err
	}
	s.say("Voice model built.")

	code, _, err := a.do("PUT", "/v1/skills/"+cfg.SkillID+"/stages/development/enablement", nil, nil)
	if err != nil && code != 409 {
		return fmt.Errorf("turning on testing: %w", err)
	}
	s.say("Testing turned on: the skill works on every Echo signed in to this Amazon account.")
	s.say(fmt.Sprintf("Done. Try: \"Alexa, open %s\".", cfg.Invocation))
	return nil
}

// waitStatus polls until Amazon finishes building the manifest or model.
func (s *server) waitStatus(a *smapi, skillID, resource string) error {
	deadline := time.Now().Add(4 * time.Minute)
	for time.Now().Before(deadline) {
		var st map[string]json.RawMessage
		if _, _, err := a.do("GET", "/v1/skills/"+skillID+"/status?resource="+resource, nil, &st); err != nil {
			return err
		}
		status, errs := parseStatus(st, resource)
		switch status {
		case "SUCCEEDED":
			return nil
		case "FAILED":
			return fmt.Errorf("Amazon couldn't build the %s: %s", resource, strings.Join(errs, "; "))
		}
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("Amazon is still building the %s after 4 minutes; press the button again later", resource)
}

type updateReq struct {
	LastUpdateRequest struct {
		Status string `json:"status"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
	} `json:"lastUpdateRequest"`
}

func parseStatus(st map[string]json.RawMessage, resource string) (string, []string) {
	var u updateReq
	switch resource {
	case "manifest":
		_ = json.Unmarshal(st["manifest"], &u)
	case "interactionModel":
		var locales map[string]updateReq
		_ = json.Unmarshal(st["interactionModel"], &locales)
		u = locales["en-US"]
	}
	var errs []string
	for _, e := range u.LastUpdateRequest.Errors {
		errs = append(errs, e.Message)
	}
	return u.LastUpdateRequest.Status, errs
}

// Types a phrase into Alexa's simulator and returns what Alexa said back.
func (s *server) setupTest(w http.ResponseWriter, r *http.Request) {
	var b struct{ Phrase string }
	_ = readJSON(r, &b)
	cfg := s.st.config()
	if cfg.SkillID == "" {
		writeJSONResponse(w, 400, map[string]string{"error": "Create the skill first."})
		return
	}
	phrase := strings.TrimSpace(b.Phrase)
	if phrase == "" {
		phrase = "open " + cfg.Invocation
	}
	tok, err := s.accessToken()
	if err != nil {
		writeJSONResponse(w, 400, map[string]string{"error": err.Error()})
		return
	}
	a := &smapi{s: s, token: tok}
	body := map[string]any{"session": map[string]string{"mode": "FORCE_NEW_SESSION"}, "input": map[string]string{"content": phrase}, "device": map[string]string{"locale": "en-US"}}
	var sim struct {
		ID string `json:"id"`
	}
	if _, _, err := a.do("POST", "/v1/skills/"+cfg.SkillID+"/stages/development/simulations", body, &sim); err != nil {
		writeJSONResponse(w, 400, map[string]string{"error": err.Error()})
		return
	}
	for i := 0; i < 20; i++ {
		time.Sleep(1500 * time.Millisecond)
		var res struct {
			Status string `json:"status"`
			Result struct {
				Error *struct {
					Message string `json:"message"`
				} `json:"error"`
				AlexaExecutionInfo struct {
					AlexaResponses []struct {
						Content struct {
							Caption string `json:"caption"`
						} `json:"content"`
					} `json:"alexaResponses"`
				} `json:"alexaExecutionInfo"`
			} `json:"result"`
		}
		if _, _, err := a.do("GET", "/v1/skills/"+cfg.SkillID+"/stages/development/simulations/"+sim.ID, nil, &res); err != nil {
			writeJSONResponse(w, 400, map[string]string{"error": err.Error()})
			return
		}
		if res.Status == "IN_PROGRESS" {
			continue
		}
		if res.Result.Error != nil {
			writeJSONResponse(w, 200, map[string]any{"ok": false, "said": phrase, "reply": "", "message": res.Result.Error.Message})
			return
		}
		reply := ""
		for _, ar := range res.Result.AlexaExecutionInfo.AlexaResponses {
			reply += ar.Content.Caption + " "
		}
		writeJSONResponse(w, 200, map[string]any{"ok": res.Status == "SUCCESSFUL", "said": phrase, "reply": strings.TrimSpace(reply)})
		return
	}
	writeJSONResponse(w, 200, map[string]any{"ok": false, "said": phrase, "message": "Alexa's simulator didn't answer in 30 seconds."})
}

func (s *server) setupRemoveTV(w http.ResponseWriter, r *http.Request) {
	var b struct{ ID string }
	_ = readJSON(r, &b)
	if err := s.st.forgetTV(b.ID); err != nil {
		writeJSONResponse(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func (s *server) setupNewKey(w http.ResponseWriter, r *http.Request) {
	_ = s.st.updateConfig(func(c *Config) { c.RegisterKey = randomWords() })
	writeJSONResponse(w, 200, map[string]bool{"ok": true})
}

func htmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(s)
}
