#!/usr/bin/env bash
# Creates a demo product (thermostat), a device, and an overheat rule through the REST API, then prints the
# command that starts a simulated device. Requires bash and curl.
#
#   HYPERIOT_URL=http://localhost:8080 HYPERIOT_ADMIN_PASSWORD=... ./scripts/seed-demo.sh
set -euo pipefail

BASE="${HYPERIOT_URL:-http://localhost:8080}"
USER_NAME="${HYPERIOT_ADMIN_USERNAME:-admin}"
PASSWORD="${HYPERIOT_ADMIN_PASSWORD:?set HYPERIOT_ADMIN_PASSWORD to the admin password}"
DEVICE_NAME="${DEVICE_NAME:-thermostat-001}"

json_field() { # json_field <name> : first string/number value of "name" in stdin
  { grep -o "\"$1\":\(\"[^\"]*\"\|[0-9]*\)" || true; } | head -1 | sed -e "s/^\"$1\"://" -e 's/^"//' -e 's/"$//'
}

# The body goes through stdin so that non-ASCII text keeps its UTF-8 bytes (command-line arguments do not on Windows).
call() { # call <method> <path> [body]
  if [ -n "${3:-}" ]; then
    printf '%s' "$3" | curl -sS -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN"       -H 'Content-Type: application/json; charset=utf-8' --data-binary @-
  else
    curl -sS -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN"
  fi
}

TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" | json_field token)
[ -n "$TOKEN" ] || { echo "login failed" >&2; exit 1; }

PRODUCT=$(call POST /api/products '{"name":"智能温控器","description":"演示产品","nodeType":"DEVICE","protocol":"MQTT"}')
PRODUCT_ID=$(echo "$PRODUCT" | json_field id)
PRODUCT_KEY=$(echo "$PRODUCT" | json_field productKey)
[ -n "$PRODUCT_KEY" ] || { echo "creating the product failed: $PRODUCT" >&2; exit 1; }

call PUT "/api/products/$PRODUCT_ID/thing-model" '{
  "properties": [
    {"identifier":"temperature","name":"温度","type":"double","unit":"℃","min":-40,"max":125,"access":"r"},
    {"identifier":"humidity","name":"湿度","type":"int","unit":"%","min":0,"max":100,"access":"r"},
    {"identifier":"mode","name":"模式","type":"enum","values":["auto","manual"],"access":"rw"},
    {"identifier":"fan","name":"风扇","type":"bool","access":"rw"}
  ],
  "events": [
    {"identifier":"overheat","name":"过热","level":"WARN","params":[{"identifier":"temp","type":"double"}]}
  ],
  "services": [
    {"identifier":"reboot","name":"重启","input":[{"identifier":"delaySeconds","name":"延迟(秒)","type":"int","min":0,"max":60}],"output":[]},
    {"identifier":"fanOn","name":"开风扇","input":[],"output":[]}
  ]
}' > /dev/null

DEVICE=$(call POST /api/devices "{\"productKey\":\"$PRODUCT_KEY\",\"deviceName\":\"$DEVICE_NAME\",\"nickname\":\"演示设备\"}")
SECRET=$(echo "$DEVICE" | json_field deviceSecret)

call POST /api/rules "{
  \"name\":\"高温告警并开风扇\",\"productKey\":\"$PRODUCT_KEY\",\"logic\":\"AND\",\"level\":\"CRITICAL\",
  \"conditions\":[{\"identifier\":\"temperature\",\"op\":\"GT\",\"value\":60}],
  \"actions\":[{\"type\":\"COMMAND\",\"identifier\":\"fanOn\",\"params\":{}}]
}" > /dev/null

cat <<EOF

Demo data created.
  product key : $PRODUCT_KEY
  device name : $DEVICE_NAME

Start a simulated device (the secret is passed through the environment, not the command line):

  HYPERIOT_DEVICE_SECRET='$SECRET' java -jar hyperiot-simulator/target/hyperiot-simulator-0.1.0-SNAPSHOT.jar \\
      --product-key $PRODUCT_KEY --device-name $DEVICE_NAME --interval 3

EOF
