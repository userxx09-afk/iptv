#!/bin/sh
# Installs the Tapper Alexa relay on Ubuntu/Debian:
#   curl -fsSL https://github.com/userxx09-afk/prod/raw/main/alexa-relay/install.sh | sudo sh
set -eu
V=@VERSION@
BASE=${BASE:-https://github.com/userxx09-afk/prod/raw/main/alexa-relay}
case "$(dpkg --print-architecture)" in
  amd64) A=amd64 ;; arm64) A=arm64 ;;
  *) echo "Sorry, no build for $(dpkg --print-architecture) yet." >&2; exit 1 ;;
esac
[ "$(id -u)" = 0 ] || { echo "Run it with sudo." >&2; exit 1; }
T=$(mktemp -d)
curl -fsSL -o "$T/relay.deb" "$BASE/tapper-alexa-relay_${V}_${A}.deb"
apt-get install -y "$T/relay.deb"
rm -rf "$T"
