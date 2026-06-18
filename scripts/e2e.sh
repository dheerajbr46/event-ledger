#!/usr/bin/env bash
# End-to-end smoke test for the Event Ledger stack.
# Both services must already be running before executing this script.
#
# Option A — Docker Compose:
#   docker compose up --build -d && bash scripts/e2e.sh
#
# Option B — Maven (two terminals):
#   terminal 1: mvn -pl account-service spring-boot:run
#   terminal 2: mvn -pl gateway spring-boot:run
#   terminal 3: bash scripts/e2e.sh
#
#   Gateway:         http://localhost:8080
#   Account Service: http://localhost:8081

set -uo pipefail

GW=http://localhost:8080
AS=http://localhost:8081
RUN=$$                        # unique suffix so repeated runs don't collide on H2 data
ACCOUNT="acct-e2e-$RUN"
PASS=0
FAIL=0

green() { printf '\033[0;32m  ✅  %s\033[0m\n' "$*"; }
red()   { printf '\033[0;31m  ❌  %s\033[0m\n' "$*"; }
section() { printf '\n\033[1m── %s\033[0m\n' "$*"; }

check_status() {
    local label=$1 actual=$2 expected=$3
    if [ "$actual" = "$expected" ]; then
        green "$label"
        PASS=$((PASS + 1))
    else
        red "$label  (got HTTP $actual, want $expected)"
        FAIL=$((FAIL + 1))
    fi
}

check_value() {
    local label=$1 actual=$2 expected=$3
    if [ "$actual" = "$expected" ]; then
        green "$label"
        PASS=$((PASS + 1))
    else
        red "$label  (got '$actual', want '$expected')"
        FAIL=$((FAIL + 1))
    fi
}

json_field() {
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1',''))" 2>/dev/null
}

json_list_first_field() {
    python3 -c "import sys,json; lst=json.load(sys.stdin); print(lst[0].get('$1','') if lst else '')" 2>/dev/null
}

# ── Preflight ────────────────────────────────────────────────────────────────
section "Preflight"

if ! curl -sf "$GW/health" > /dev/null 2>&1; then
    echo "Gateway is not reachable at $GW — start both services first."
    exit 1
fi
if ! curl -sf "$AS/health" > /dev/null 2>&1; then
    echo "Account Service is not reachable at $AS — start both services first."
    exit 1
fi

# ── Health ───────────────────────────────────────────────────────────────────
section "Health"

GW_STATUS=$(curl -s "$GW/health" | json_field status)
check_value "Gateway /health → UP" "$GW_STATUS" "UP"

AS_STATUS=$(curl -s "$AS/health" | json_field status)
check_value "Account Service /health → UP" "$AS_STATUS" "UP"

# ── Submit events ────────────────────────────────────────────────────────────
section "Submit events"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"e2e-credit-$RUN\",\"accountId\":\"$ACCOUNT\",\"type\":\"CREDIT\",\"amount\":150.00,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}")
check_status "POST /events new event → 201 Created" "$S" "201"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"e2e-credit-$RUN\",\"accountId\":\"$ACCOUNT\",\"type\":\"CREDIT\",\"amount\":150.00,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}")
check_status "POST /events same eventId again → 200 (idempotent)" "$S" "200"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"e2e-debit-$RUN\",\"accountId\":\"$ACCOUNT\",\"type\":\"DEBIT\",\"amount\":40.00,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T15:00:00Z\"}")
check_status "POST /events DEBIT → 201 Created" "$S" "201"

# ── Read endpoints ────────────────────────────────────────────────────────────
section "Read endpoints"

S=$(curl -so /dev/null -w "%{http_code}" "$GW/events/e2e-credit-$RUN")
check_status "GET /events/{id} → 200" "$S" "200"

S=$(curl -so /dev/null -w "%{http_code}" "$GW/events/does-not-exist-$RUN")
check_status "GET /events/{id} unknown → 404" "$S" "404"

# Out-of-order: submit late timestamp first, then early; list must be chronological
curl -s -X POST "$GW/events" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"e2e-late-$RUN\",\"accountId\":\"$ACCOUNT-ord\",\"type\":\"DEBIT\",\"amount\":10.00,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T18:00:00Z\"}" > /dev/null
curl -s -X POST "$GW/events" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"e2e-early-$RUN\",\"accountId\":\"$ACCOUNT-ord\",\"type\":\"CREDIT\",\"amount\":50.00,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T12:00:00Z\"}" > /dev/null

FIRST=$(curl -s "$GW/events?account=$ACCOUNT-ord" | json_list_first_field eventId)
check_value "GET /events?account list ordered by eventTimestamp (early first)" "$FIRST" "e2e-early-$RUN"

# ── Balance ───────────────────────────────────────────────────────────────────
section "Balance"

BAL=$(curl -s "$GW/accounts/$ACCOUNT/balance" | json_field balance)
check_value "GET /accounts/{id}/balance = 150 CREDIT − 40 DEBIT = 110" "$BAL" "110.0"

# ── Validation ────────────────────────────────────────────────────────────────
section "Validation (all expect 400)"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"","accountId":"acct-v","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Blank eventId (@NotBlank)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-neg","accountId":"acct-v","type":"CREDIT","amount":-5.00,"currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Negative amount (@Positive)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-zero","accountId":"acct-v","type":"CREDIT","amount":0,"currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Zero amount (@Positive)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-type","accountId":"acct-v","type":"WIRE_TRANSFER","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Unknown event type (enum parse failure)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-no-acct","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Missing accountId (@NotBlank)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-no-curr","accountId":"acct-v","type":"CREDIT","amount":10.00,"eventTimestamp":"2026-05-15T14:00:00Z"}')
check_status "Missing currency (@NotBlank)" "$S" "400"

S=$(curl -so /dev/null -w "%{http_code}" -X POST "$GW/events" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"v-no-ts","accountId":"acct-v","type":"CREDIT","amount":10.00,"currency":"USD"}')
check_status "Missing eventTimestamp (@NotNull)" "$S" "400"

# ── Observability ─────────────────────────────────────────────────────────────
section "Observability"

S=$(curl -so /dev/null -w "%{http_code}" "$GW/actuator/health")
check_status "GET /actuator/health → 200" "$S" "200"

S=$(curl -so /dev/null -w "%{http_code}" "$GW/actuator/prometheus")
check_status "GET /actuator/prometheus → 200" "$S" "200"

METRIC_COUNT=$(curl -s "$GW/actuator/prometheus" | grep -c "^ledger_events_total" || true)
if [ "$METRIC_COUNT" -gt 0 ]; then
    green "ledger_events_total metric present in Prometheus output"
    PASS=$((PASS + 1))
else
    red "ledger_events_total metric missing from Prometheus output"
    FAIL=$((FAIL + 1))
fi

# ── Graceful degradation ──────────────────────────────────────────────────────
section "Graceful degradation (manual — skipped in automated run)"
echo "  To test manually: stop the Account Service, then:"
echo "    POST /events        → should return 503"
echo "    GET  /events/{id}   → should still return 200"
echo "    GET  /events?account= → should still return 200"
echo "  The event is persisted as FAILED and replayed when the service recovers."

# ── Summary ───────────────────────────────────────────────────────────────────
printf '\n\033[1m────────────────────────────────────────────────\033[0m\n'
printf '\033[1mResults: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"

if [ "$FAIL" -eq 0 ]; then
    printf '\033[0;32mAll checks passed ✅\033[0m\n'
    exit 0
else
    printf '\033[0;31mSome checks failed ❌\033[0m\n'
    exit 1
fi
