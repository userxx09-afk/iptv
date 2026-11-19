#!/bin/sh
# Builds the installers into alexa/relay/dist:
#   tapper-alexa-relay_<v>_amd64.deb, _arm64.deb   Ubuntu / Debian / Raspberry Pi OS
#   TapperAlexaRelaySetup-<v>.exe                  Windows 10/11 (x64)
#   install.sh                                     one-line Ubuntu install from the prod repo
# Needs Go 1.22+ and dpkg-deb.
set -eu
cd "$(dirname "$0")/.."
V=$(sed -n 's/^var version = "\(.*\)"/\1/p' main.go)
rm -rf dist && mkdir -p dist
LD="-s -w -X main.version=$V"

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "$LD" -o "dist/TapperAlexaRelaySetup-$V.exe" .

for ARCH in amd64 arm64; do
  R=$(mktemp -d)
  mkdir -p "$R/DEBIAN" "$R/usr/bin" "$R/usr/lib/systemd/system" "$R/etc/default"
  GOOS=linux GOARCH=$ARCH CGO_ENABLED=0 go build -trimpath -ldflags "$LD" -o "$R/usr/bin/tapper-alexa-relay" .
  cp packaging/tapper-alexa-relay.service "$R/usr/lib/systemd/system/"
  cp packaging/default "$R/etc/default/tapper-alexa-relay"
  for f in postinst prerm postrm; do cp "packaging/debian/$f" "$R/DEBIAN/$f"; chmod 755 "$R/DEBIAN/$f"; done
  echo /etc/default/tapper-alexa-relay > "$R/DEBIAN/conffiles"
  cat > "$R/DEBIAN/control" <<CTL
Package: tapper-alexa-relay
Version: $V
Architecture: $ARCH
Maintainer: Tapper <noreply@tapper.co>
Depends: adduser
Section: net
Priority: optional
Homepage: https://github.com/userxx09-afk/prod
Description: Alexa voice control for Tapper IPTV and GarysTV
 The Alexa skill endpoint and TV relay. Sign in with Amazon on its setup page
 and it creates and enables the private skill for you.
CTL
  dpkg-deb --root-owner-group --build "$R" "dist/tapper-alexa-relay_${V}_${ARCH}.deb" >/dev/null
  rm -rf "$R"
done

sed "s/@VERSION@/$V/g" packaging/install.sh > dist/install.sh
chmod 755 dist/install.sh
(cd dist && sha256sum * > SHA256SUMS)
ls -l dist
