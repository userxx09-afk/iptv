// Tapper Alexa relay: one small program that lets Alexa control Tapper IPTV and
// GarysTV ("Alexa, ask Tapper to play ESPN").
//
//   - POST /alexa      the Alexa skill endpoint (Amazon-signed requests only)
//   - /tv/...          the mailbox protocol both apps already speak (see tv.go)
//   - /setup           a local-only page: sign in with Amazon and the skill is
//     created, configured and enabled for you (setup.go)
//
// Usage:
//
//	tapper-alexa-relay serve [--data DIR] [--port 8787]
//	tapper-alexa-relay install      (Windows: installs and starts it at boot)
//	tapper-alexa-relay uninstall
//	tapper-alexa-relay pin [--data DIR]   prints the setup PIN
//	tapper-alexa-relay version
//
// On Windows, double-clicking the downloaded setup .exe runs "install".
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"strconv"
)

var version = "1.0.0"

func main() {
	args := os.Args[1:]
	cmd := ""
	if len(args) > 0 && len(args[0]) > 0 && args[0][0] != '-' {
		cmd, args = args[0], args[1:]
	}
	if cmd == "" {
		cmd = defaultCommand() // "install" for a double-clicked Windows setup .exe, else "serve"
	}

	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	dataDir := fs.String("data", envOr("DATA_DIR", defaultDataDir()), "folder for settings and linked TVs")
	port := fs.Int("port", envInt("PORT", 8787), "HTTP port (put HTTPS in front of it, e.g. a Cloudflare tunnel)")
	_ = fs.Parse(args)

	switch cmd {
	case "serve":
		s, err := newServer(*dataDir, *port)
		if err != nil {
			log.Fatal(err)
		}
		log.Fatal(s.run())
	case "install":
		if err := install(*dataDir, *port); err != nil {
			fmt.Fprintln(os.Stderr, "Install failed:", err)
			pauseIfWindows()
			os.Exit(1)
		}
	case "uninstall":
		if err := uninstall(); err != nil {
			fmt.Fprintln(os.Stderr, "Uninstall failed:", err)
			pauseIfWindows()
			os.Exit(1)
		}
	case "pin":
		st, err := openStore(*dataDir)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(st.setupPIN())
	case "version":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q (serve, install, uninstall, pin, version)\n", cmd)
		os.Exit(2)
	}
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	if n, err := strconv.Atoi(os.Getenv(k)); err == nil && n > 0 {
		return n
	}
	return d
}
