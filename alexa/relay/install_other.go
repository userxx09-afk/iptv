//go:build !windows

package main

import (
	"errors"
	"os"
)

func defaultCommand() string { return "serve" }

func defaultDataDir() string {
	if os.Geteuid() == 0 {
		return "/var/lib/tapper-alexa-relay"
	}
	if h, err := os.UserHomeDir(); err == nil {
		return h + "/.tapper-alexa-relay"
	}
	return "./data"
}

func install(dataDir string, port int) error {
	return errors.New("on Ubuntu, install the .deb instead: sudo apt install ./tapper-alexa-relay_*.deb")
}

func uninstall() error {
	return errors.New("on Ubuntu: sudo apt remove tapper-alexa-relay")
}

func pauseIfWindows() {}
