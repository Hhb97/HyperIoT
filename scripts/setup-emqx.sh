#!/usr/bin/env bash
# Points EMQX 5.x at HyperIoT for device authentication and topic authorization (HTTP hooks), through EMQX's REST API.
# Run it yourself: it changes EMQX's security configuration. The Dashboard password is read from the environment so
# it never shows up on the command line.
#
#   export EMQX_ADMIN_PASSWORD='...'                         # EMQX Dashboard password
#   export HYPERIOT_HOOK_TOKEN='...'                         # same value as hyperiot.security.hook-token
#   export HYPERIOT_HOOK_BASE=http://<platform-host>:8888      # platform address as seen FROM EMQX (not localhost)
#   EMQX_URL=http://<emqx-host>:18083 ./scripts/setup-emqx.sh          # create or update the hooks
#   EMQX_URL=http://<emqx-host>:18083 ./scripts/setup-emqx.sh remove   # take them out again
#
# The request shapes were checked against the swagger document of EMQX 5.7.2 (GET /api-docs/swagger.json) but this
# script has not been run against a live EMQX by its author: read the output, and check the Dashboard afterwards.
set -euo pipefail

EMQX_URL="${EMQX_URL:-http://localhost:18083}"
EMQX_ADMIN_USER="${EMQX_ADMIN_USER:-admin}"
: "${EMQX_ADMIN_PASSWORD:?set EMQX_ADMIN_PASSWORD}"
ACTION="${1:-apply}"

API="$EMQX_URL/api/v5"

TOKEN=$(printf '{"username":"%s","password":"%s"}' "$EMQX_ADMIN_USER" "$EMQX_ADMIN_PASSWORD" \
  | curl -sS -X POST "$API/login" -H 'Content-Type: application/json' --data-binary @- \
  | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
[ -n "$TOKEN" ] || { echo "login to EMQX failed (check EMQX_URL, user and password)" >&2; exit 1; }

# $1 method, $2 path, $3 optional body; prints "<http status> <body>"
call() {
  local out
  if [ -n "${3:-}" ]; then
    out=$(printf '%s' "$3" | curl -sS -X "$1" "$API$2" -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- -w '\n%{http_code}')
  else
    out=$(curl -sS -X "$1" "$API$2" -H "Authorization: Bearer $TOKEN" -w '\n%{http_code}')
  fi
  printf '%s %s' "${out##*$'\n'}" "${out%$'\n'*}"
}

status() { printf '%s' "${1%% *}"; }

AUTHN_ID="password_based:http"

if [ "$ACTION" = "remove" ]; then
  echo "removing the HyperIoT HTTP authenticator and authorization source..."
  r=$(call DELETE "/authentication/$AUTHN_ID"); echo "  authentication: HTTP $(status "$r")"
  r=$(call DELETE "/authorization/sources/http"); echo "  authorization : HTTP $(status "$r")"
  exit 0
fi

: "${HYPERIOT_HOOK_TOKEN:?set HYPERIOT_HOOK_TOKEN}"
: "${HYPERIOT_HOOK_BASE:?set HYPERIOT_HOOK_BASE, e.g. http://<platform-host>:8888}"
case "$HYPERIOT_HOOK_TOKEN" in *[!A-Za-z0-9._-]*) echo "HYPERIOT_HOOK_TOKEN may only contain letters, digits, . _ -" >&2; exit 1;; esac
BASE="${HYPERIOT_HOOK_BASE%/}"

# the placeholders are EMQX's own, they must reach EMQX literally
AUTHN_BODY=$(printf '{"mechanism":"password_based","backend":"http","method":"post","url":"%s/api/mqtt/auth","headers":{"content-type":"application/json","x-hook-token":"%s"},"body":{"clientid":"%s","username":"%s","password":"%s"},"enable":true}' \
  "$BASE" "$HYPERIOT_HOOK_TOKEN" '${clientid}' '${username}' '${password}')
AUTHZ_BODY=$(printf '{"type":"http","method":"post","url":"%s/api/mqtt/acl","headers":{"content-type":"application/json","x-hook-token":"%s"},"body":{"clientid":"%s","topic":"%s","action":"%s"},"enable":true}' \
  "$BASE" "$HYPERIOT_HOOK_TOKEN" '${clientid}' '${topic}' '${action}')

echo "authentication (HTTP server -> $BASE/api/mqtt/auth)"
r=$(call POST "/authentication" "$AUTHN_BODY")
if [ "$(status "$r")" = "409" ]; then
  echo "  already exists, updating"
  r=$(call PUT "/authentication/$AUTHN_ID" "$AUTHN_BODY")
fi
echo "  HTTP $(status "$r")"; [ "$(status "$r")" = "200" ] || { echo "  ${r#* }" >&2; exit 1; }

echo "authorization (HTTP server -> $BASE/api/mqtt/acl)"
r=$(call POST "/authorization/sources" "$AUTHZ_BODY")
if [ "$(status "$r")" = "409" ] || [ "$(status "$r")" = "400" ]; then
  echo "  could not create (HTTP $(status "$r")), trying to update the existing http source"
  r=$(call PUT "/authorization/sources/http" "$AUTHZ_BODY")
fi
echo "  HTTP $(status "$r")"; case "$(status "$r")" in 200|204) ;; *) echo "  ${r#* }" >&2; exit 1;; esac

# evaluate the HyperIoT source before any other source (e.g. the built-in database)
r=$(call POST "/authorization/sources/http/move" '{"position":"front"}'); echo "  moved to the front: HTTP $(status "$r")"

# anything the HTTP source does not answer is denied
cur=$(call GET "/authorization/settings")
settings="${cur#* }"
echo "authorization settings: no_match -> deny"
new=$(printf '%s' "$settings" | sed 's/"no_match":"allow"/"no_match":"deny"/')
r=$(call PUT "/authorization/settings" "$new"); echo "  HTTP $(status "$r")"

echo "done. Check Access Control -> Authentication / Authorization in the Dashboard."
