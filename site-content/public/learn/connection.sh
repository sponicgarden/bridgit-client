#!/bin/sh
# Bridgit connection ladder (https://bridgit.cc/learn/connection.md).
# Run it when a call to the phone fails. It is read-only: one Tailscale ping, one TCP connect and
# GET /v1/health (with your key, and once without it if the key is refused). It prints one JSON
# result per layer; paste them into a bug report as "diagnosis". Your key is only sent to the phone
# and never printed.
#
#   HOST=<phone Tailscale IP or name> PORT=<port> HDR=<file holding "Authorization: Bearer ..."> sh connection.sh
#
# Userspace Tailscale (no tailnet routes on this host)? Plain TCP can't reach the phone: use the
# helper's "bridgit GET /v1/health" for the app layer and record transport as skip.

H=${HOST:?set HOST to the phone Tailscale IP or name}
P=${PORT:-8734}
HDR=${HDR:?set HDR to the file with your Authorization header}
stop=""

clean() { tr -d '"\\\n\r' | cut -c1-300; }
ms() { awk -v s="${1:-0}" 'BEGIN { printf "%d", s * 1000 }'; }
step() { # layer target outcome ms detail
  printf '{"layer":"%s","target":"%s","outcome":"%s","ms":%s,"detail":"%s","at":"%s"}\n' \
    "$1" "$2" "$3" "${4:-null}" "$(printf '%s' "$5" | clean)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
skip() { step "$1" "$2" skip "" "earlier layer failed: $stop"; }

# 1 network: is the phone on the tailnet and answering Tailscale?
TS=$(command -v tailscale 2>/dev/null)
[ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
if [ -n "$TS" ]; then
  if out=$("$TS" ping -c 1 --timeout 5s "$H" 2>&1); then
    step network "$H" pass "$(printf '%s' "$out" | sed -n 's/.* in \([0-9.]*\)ms.*/\1/p' | head -1 | cut -d. -f1)" "$(printf '%s' "$out" | tail -1)"
  else
    step network "$H" fail "" "$(printf '%s' "$out" | tail -1)"; stop=network
  fi
else
  step network "$H" skip "" "no tailscale command on this host"
fi

# 2 transport: does anything accept TCP on the Bridgit port at that address?
if [ -n "$stop" ]; then skip transport "$H:$P"; else
  t=$(curl -s -o /dev/null -m 5 -w '%{time_connect}' "http://$H:$P/" 2>/dev/null); rc=$?
  c=$(ms "$t")
  case $rc in
    0) step transport "$H:$P" pass "$c" "connected" ;;
    7) step transport "$H:$P" fail "" "refused: the phone answers but nothing listens on port $P (Bridgit stopped, paused or bound elsewhere)"; stop=transport ;;
    28) if awk -v s="${t:-0}" 'BEGIN { exit !(s > 0) }'; then step transport "$H:$P" pass "$c" "connected, then no HTTP answer in 5 s"
        else step transport "$H:$P" fail "" "timeout: no TCP answer in 5 s (phone asleep, its Tailscale down, or a wrong address)"; stop=transport; fi ;;
    6) step transport "$H:$P" fail "" "name not resolved: use the IP fallback from your setup prompt"; stop=transport ;;
    *) step transport "$H:$P" fail "" "curl exit $rc"; stop=transport ;;
  esac
fi

# 3 app: does Bridgit answer GET /v1/health, how fast, and what does it say about its listener?
code=""
if [ -n "$stop" ]; then skip app "$H:$P/v1/health"; else
  body=$(curl -s -m 10 -H @"$HDR" -w '\n%{http_code} %{time_total}' "http://$H:$P/v1/health" 2>/dev/null); rc=$?
  last=$(printf '%s\n' "$body" | tail -1); code=${last%% *}; tt=$(ms "${last#* }")
  listener=$(printf '%s' "$body" | grep -o '"listener":{"state":"[a-z_]*"' | head -1 | sed 's/.*"state":"//; s/"$//')
  if [ $rc -eq 28 ]; then step app "$H:$P/v1/health" fail "" "no_answer: connected but health did not answer in 10 s (app wedged)"; stop=app
  elif [ $rc -ne 0 ]; then step app "$H:$P/v1/health" fail "" "curl exit $rc"; stop=app
  else case $code in
    200) step app "$H:$P/v1/health" pass "$tt" "200 status ok${listener:+, listener $listener}" ;;
    401|403) step app "$H:$P/v1/health" pass "$tt" "$code: Bridgit answers (see auth)" ;;
    5*) step app "$H:$P/v1/health" fail "$tt" "http_$code: Bridgit is up but erroring"; stop=app ;;
    *) step app "$H:$P/v1/health" fail "$tt" "http_$code: something other than Bridgit answered"; stop=app ;;
  esac; fi
fi

# 4 auth: is it the key? Compare with the same call without it.
if [ -n "$stop" ]; then skip auth "$H:$P/v1/health"; else
  case $code in
    200) step auth "$H:$P/v1/health" pass "" "key accepted" ;;
    *) err=$(printf '%s' "$body" | grep -o '"error":"[a-z_]*"' | head -1 | cut -d'"' -f4)
       reason=$(printf '%s' "$body" | grep -o '"reason":"[a-z_]*"' | head -1 | cut -d'"' -f4)
       nk=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "http://$H:$P/v1/health" 2>/dev/null)
       step auth "$H:$P/v1/health" fail "" "with key $code ${err}${reason:+ $reason}, without key $nk" ;;
  esac
fi
