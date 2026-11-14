#!/usr/bin/env bash
# Save your Home Assistant URL and token for the launcher, and check that they work.
#   basement/set-ha.sh
# Create the token first: Home Assistant -> your profile (bottom left) -> Security -> Long-lived access tokens -> Create token.
# The token is typed hidden, checked against Home Assistant, and written only to launcher/local.properties
# (gitignored). It is never printed or committed.
set -euo pipefail
cd "$(dirname "$0")/.."
PROPS=launcher/local.properties
DEFAULT_URL="${HA_URL:-http://192.168.1.64:8123}"
read -r -p "Home Assistant URL the TV can reach [$DEFAULT_URL]: " URL
URL="${URL:-$DEFAULT_URL}"; URL="${URL%/}"
read -r -s -p "Paste the long-lived token (it stays hidden), then press Return: " TOKEN; echo
case "$TOKEN" in
  eyJ*) ;;
  *) echo "That doesn't look like a Home Assistant token (they start with eyJ and are about 180 characters). Nothing saved."; exit 1 ;;
esac
code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$URL/api/" || true)
if [ "$code" != "200" ]; then echo "Home Assistant answered $code at $URL/api/ (200 expected). Nothing saved."; exit 1; fi
touch "$PROPS"
grep -vE '^(ha\.url|ha\.token)=' "$PROPS" > "$PROPS.tmp" || true
{ cat "$PROPS.tmp"; echo "ha.url=$URL"; echo "ha.token=$TOKEN"; } > "$PROPS"; rm -f "$PROPS.tmp"; chmod 600 "$PROPS"
echo "Saved and verified. Tell Claude 'token saved' and it will rebuild and install."
