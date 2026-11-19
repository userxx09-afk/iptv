package main

// The Alexa side: POST /alexa. Every request is checked the way Amazon requires
// for skills hosted outside AWS Lambda: the signing certificate's URL, the
// certificate chain, the RSA-SHA256 signature over the exact body, and a
// timestamp within 150 seconds. The skill id must be ours.

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---- request verification

type certCache struct {
	mu    sync.Mutex
	certs map[string]*x509.Certificate
	fetch func(u string) ([]byte, error)
	roots *x509.CertPool // nil: the system's
	now   func() time.Time
}

func newCertCache() *certCache {
	client := &http.Client{Timeout: 5 * time.Second}
	return &certCache{
		certs: map[string]*x509.Certificate{},
		now:   time.Now,
		fetch: func(u string) ([]byte, error) {
			resp, err := client.Get(u)
			if err != nil {
				return nil, err
			}
			defer resp.Body.Close()
			if resp.StatusCode != 200 {
				return nil, fmt.Errorf("cert chain HTTP %d", resp.StatusCode)
			}
			return io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		},
	}
}

// validCertURL: https://s3.amazonaws.com[:443]/echo.api/... (path normalized first).
func validCertURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || !strings.EqualFold(u.Hostname(), "s3.amazonaws.com") {
		return false
	}
	if p := u.Port(); p != "" && p != "443" {
		return false
	}
	return strings.HasPrefix(path.Clean(u.Path), "/echo.api/")
}

func (c *certCache) leaf(certURL string) (*x509.Certificate, error) {
	c.mu.Lock()
	cert := c.certs[certURL]
	c.mu.Unlock()
	now := c.now()
	if cert != nil && now.Before(cert.NotAfter) {
		return cert, nil
	}
	pemBytes, err := c.fetch(certURL)
	if err != nil {
		return nil, err
	}
	var chain []*x509.Certificate
	for {
		var b *pem.Block
		b, pemBytes = pem.Decode(pemBytes)
		if b == nil {
			break
		}
		if b.Type == "CERTIFICATE" {
			x, err := x509.ParseCertificate(b.Bytes)
			if err != nil {
				return nil, err
			}
			chain = append(chain, x)
		}
	}
	if len(chain) == 0 {
		return nil, errors.New("no certificate in chain")
	}
	inter := x509.NewCertPool()
	for _, x := range chain[1:] {
		inter.AddCert(x)
	}
	if _, err := chain[0].Verify(x509.VerifyOptions{DNSName: "echo-api.amazon.com", Intermediates: inter, Roots: c.roots, CurrentTime: now}); err != nil {
		return nil, fmt.Errorf("signing certificate: %w", err)
	}
	c.mu.Lock()
	c.certs[certURL] = chain[0]
	c.mu.Unlock()
	return chain[0], nil
}

func (c *certCache) verify(h http.Header, body []byte) error {
	certURL := h.Get("SignatureCertChainUrl")
	if !validCertURL(certURL) {
		return errors.New("bad SignatureCertChainUrl")
	}
	sigB64, algo := h.Get("Signature-256"), crypto.SHA256
	if sigB64 == "" {
		return errors.New("no Signature-256")
	}
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if err != nil {
		return errors.New("bad signature encoding")
	}
	leaf, err := c.leaf(certURL)
	if err != nil {
		return err
	}
	pub, ok := leaf.PublicKey.(*rsa.PublicKey)
	if !ok {
		return errors.New("signing key isn't RSA")
	}
	sum := sha256.Sum256(body)
	if err := rsa.VerifyPKCS1v15(pub, algo, sum[:], sig); err != nil {
		return errors.New("signature doesn't match")
	}
	return nil
}

// ---- request / response shapes (only what we use)

type alexaRequest struct {
	Version string `json:"version"`
	Session *struct {
		Application struct {
			ApplicationID string `json:"applicationId"`
		} `json:"application"`
		User struct {
			UserID string `json:"userId"`
		} `json:"user"`
	} `json:"session"`
	Context struct {
		System struct {
			Application struct {
				ApplicationID string `json:"applicationId"`
			} `json:"application"`
			User struct {
				UserID string `json:"userId"`
			} `json:"user"`
		} `json:"System"`
	} `json:"context"`
	Request struct {
		Type      string `json:"type"`
		Timestamp string `json:"timestamp"`
		Intent    struct {
			Name  string `json:"name"`
			Slots map[string]struct {
				Value string `json:"value"`
			} `json:"slots"`
		} `json:"intent"`
	} `json:"request"`
}

func (a *alexaRequest) userID() string {
	if a.Context.System.User.UserID != "" {
		return a.Context.System.User.UserID
	}
	if a.Session != nil {
		return a.Session.User.UserID
	}
	return ""
}

func (a *alexaRequest) appID() string {
	if a.Context.System.Application.ApplicationID != "" {
		return a.Context.System.Application.ApplicationID
	}
	if a.Session != nil {
		return a.Session.Application.ApplicationID
	}
	return ""
}

func (a *alexaRequest) slot(name string) string {
	return strings.TrimSpace(a.Request.Intent.Slots[name].Value)
}

type speech struct {
	text     string
	reprompt string // non-empty: keep the session open
}

func alexaResponse(sp speech) map[string]any {
	resp := map[string]any{"shouldEndSession": sp.reprompt == ""}
	if sp.text != "" {
		resp["outputSpeech"] = map[string]string{"type": "PlainText", "text": sp.text}
	}
	if sp.reprompt != "" {
		resp["reprompt"] = map[string]any{"outputSpeech": map[string]string{"type": "PlainText", "text": sp.reprompt}}
	}
	return map[string]any{"version": "1.0", "response": resp}
}

// ---- the endpoint

func (s *server) handleAlexa(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 128<<10))
	if err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	if !s.skipVerify {
		if err := s.certs.verify(r.Header, body); err != nil {
			s.logf("alexa: rejected request: %v", err)
			http.Error(w, "bad signature", 400)
			return
		}
	}
	var req alexaRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	if !s.skipVerify {
		ts, err := time.Parse(time.RFC3339, req.Request.Timestamp)
		if err != nil || absDur(time.Since(ts)) > 150*time.Second {
			s.logf("alexa: rejected request: stale timestamp %q", req.Request.Timestamp)
			http.Error(w, "stale request", 400)
			return
		}
	}
	if id := s.st.config().SkillID; id != "" && req.appID() != id {
		s.logf("alexa: rejected request for another skill %s", req.appID())
		http.Error(w, "wrong skill", 400)
		return
	}
	sp := s.answer(&req)
	writeJSONResponse(w, 200, alexaResponse(sp))
}

func absDur(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}

// Commands the apps act on. Tapper and GarysTV ignore types they don't know yet.
var intentCommands = map[string]struct {
	cmd, say string
}{
	"ChannelUpIntent":           {"channelUp", "Next channel."},
	"ChannelDownIntent":         {"channelDown", "Previous channel."},
	"AMAZON.PauseIntent":        {"pause", "Paused."},
	"AMAZON.ResumeIntent":       {"resume", "Playing."},
	"StopWatchingIntent":        {"stop", "Stopped."},
	"GuideIntent":               {"guide", "Here's the guide."},
	"RecordingsIntent":          {"recordings", "Here are your recordings."},
	"AMAZON.NavigateHomeIntent": {"home", "Home."},
	"BackIntent":                {"back", "Okay."},
}

func (s *server) answer(req *alexaRequest) speech {
	inv := s.st.config().Invocation
	user := req.userID()
	linked := s.st.linkedTVs(user)
	notLinked := speech{
		text:     "This Alexa isn't linked to a TV yet. On the TV, open Tapper or Gary's TV, go to Settings, Alexa, and press Link. Then tell me the code.",
		reprompt: "What's the code on the TV?",
	}

	switch req.Request.Type {
	case "LaunchRequest":
		if len(linked) == 0 {
			return notLinked
		}
		return speech{text: "What would you like to watch?", reprompt: "Say a channel, like play ESPN."}
	case "SessionEndedRequest":
		return speech{}
	case "IntentRequest":
	default:
		return speech{}
	}

	name := req.Request.Intent.Name
	switch name {
	case "LinkIntent":
		code := digitsOnly(req.slot("code"))
		tvID, ok := s.mb.useCode(code)
		if !ok {
			return speech{text: "That code didn't work. Check the code on the TV and try again.", reprompt: "What's the code?"}
		}
		if err := s.st.link(user, tvID); err != nil {
			return speech{text: "Sorry, I couldn't save that link."}
		}
		s.mb.send(tvID, command{Type: "linked"})
		tv, _ := s.st.tv(tvID)
		s.logf("linked %s", tv.Name)
		return speech{text: fmt.Sprintf("Linked to %s. Now say, ask %s to play, and a channel.", spoken(tv.Name), inv)}
	case "AMAZON.HelpIntent":
		return speech{text: "You can say: play ESPN, channel 204, next channel, pause, open the guide, or show my recordings. To link a TV, say link code, and the code from the TV.", reprompt: "What would you like to watch?"}
	case "AMAZON.CancelIntent", "AMAZON.StopIntent":
		return speech{text: "Okay."}
	case "AMAZON.FallbackIntent":
		return speech{text: "Try saying play, and a channel name.", reprompt: "Which channel?"}
	}

	if len(linked) == 0 {
		return notLinked
	}
	var c command
	var say string
	switch name {
	case "TuneIntent":
		q := req.slot("channel")
		if q == "" {
			return speech{text: "Which channel?", reprompt: "Say a channel, like play ESPN."}
		}
		if n, err := strconv.Atoi(strings.TrimSpace(q)); err == nil {
			c, say = command{Type: "number", Number: &n}, fmt.Sprintf("Channel %d.", n)
		} else {
			c, say = command{Type: "tune", Query: truncate(q, 100)}, fmt.Sprintf("Putting on %s.", q)
		}
	case "ChannelNumberIntent":
		n, err := strconv.Atoi(digitsOnly(req.slot("number")))
		if err != nil {
			return speech{text: "Which channel number?", reprompt: "Say a channel number, like channel 204."}
		}
		c, say = command{Type: "number", Number: &n}, fmt.Sprintf("Channel %d.", n)
	default:
		ic, ok := intentCommands[name]
		if !ok {
			return speech{text: "Sorry, I can't do that yet."}
		}
		c, say = command{Type: ic.cmd}, ic.say
	}

	tvID, onScreen := s.mb.pick(linked)
	s.mb.send(tvID, c)
	if !onScreen {
		tv, _ := s.st.tv(tvID)
		appName := "Tapper"
		if tv.App == "garystv" {
			appName = "Gary's TV"
		}
		say = fmt.Sprintf("I sent that to %s, but %s isn't open on it. It'll happen if you open it in the next minute.", spoken(tv.Name), appName)
	}
	return speech{text: say}
}

func digitsOnly(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func truncate(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// "GarysTV Living room" reads better aloud as "Gary's TV Living room".
func spoken(name string) string {
	if name == "" {
		return "your TV"
	}
	return strings.NewReplacer("GarysTV", "Gary's TV", "garystv", "Gary's TV").Replace(name)
}
