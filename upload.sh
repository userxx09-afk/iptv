#!/usr/bin/env bash
set -e

MSG="$1"
if [ -z "$MSG" ]; then
  MSG="Add project files"
fi

if [ ! -d .git ]; then
  echo "Initializing git repository"
  git init
  git branch -M main
  git remote add origin https://github.com/userxx09-afk/iptv.git || true
fi

git add .

git commit -m "$MSG" || { echo "Nothing to commit"; exit 0; }

echo "Pushing to origin main"

git push origin main
