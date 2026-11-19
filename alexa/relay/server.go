package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type server struct {
	st         *store
	mb         *mailbox
	certs      *certCache
	port       int
	skipVerify bool // ALEXA_SKIP_VERIFY=1, for local testing only
	instance   string
	setup      *setupState
	logMu      sync.Mutex
}

func newServer(dataDir string, port int) (*server, error) {
	st, err := openStore(dataDir)
	if err != nil {
		return nil, err
	}
	setupLogging(dataDir)
	s := &server{st: st, mb: newMailbox(), certs: newCertCache(), port: port, instance: randomHex(8), skipVerify: os.Getenv("ALEXA_SKIP_VERIFY") == "1"}
	s.setup = newSetupState()
	if s.skipVerify {
		log.Printf("WARNING: ALEXA_SKIP_VERIFY=1, Alexa signatures are not checked. Testing only.")
	}
	return s, nil
}

// setupLogging: stderr (journald on Ubuntu) and relay.log in the data folder,
// started fresh when it passes 5 MB.
func setupLogging(dir string) {
	p := filepath.Join(dir, "relay.log")
	if fi, err := os.Stat(p); err == nil && fi.Size() > 5<<20 {
		_ = os.Rename(p, p+".old")
	}
	if f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, f))
	}
}

func (s *server) logf(format string, a ...any) { log.Printf(format, a...) }

func (s *server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /alexa", s.handleAlexa)
	s.tvRoutes(mux)
	s.setupRoutes(mux)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSONResponse(w, 200, map[string]any{"ok": true, "app": "tapper-alexa-relay", "version": version, "tvs": len(s.st.listTVs()), "instance": s.instance})
	})
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		if s.localOnly(r) {
			http.Redirect(w, r, "/setup", http.StatusFound)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintln(w, "Tapper Alexa relay")
	})
	return mux
}

func (s *server) run() error {
	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", s.port),
		Handler:           s.handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second,
	}
	cfg := s.st.config()
	log.Printf("Tapper Alexa relay %s on :%d (data in %s)", version, s.port, s.st.dir)
	if cfg.SkillID == "" {
		log.Printf("Not set up yet: open http://localhost:%d/setup on this computer (setup PIN %s)", s.port, cfg.SetupPIN)
	}
	return srv.ListenAndServe()
}
