package com.example.voiceapp3.tools

/**
 * Shell-скрипт для измерения/установки TTL (iter18).
 *
 * iter18 fixes:
 *  - tcpdump под timeout 6, -c 2, kill после wait — не виснет, даже если ответов нет.
 *  - Отдельные tcpdump-блоки для IPv4 (ttl N -> 1.1.1.1) и IPv6 (hlim N -> 2606:4700:4700::1111).
 *  - verdict-матрица: VERIFIED только при реальном ttl/hlim с провода; если счётчики
 *    выросли, а пакета нет — real=UNKNOWN method=iptables-counters → INDIRECT;
 *    есть правило, но нет измерения — real=UNKNOWN method=rule-text → UNVERIFIED;
 *    число из правила выносится в отдельное поле rule_ttl=/rule_hl=, НЕ в real=.
 *  - DELTA_V4/V6 всегда число (по умолч. 0) — нет 'unary operator expected'.
 *
 * Все $ в этом triple-quoted шаблоне относятся к shell. Kotlin $ нужный, мы
 * экранируем через константу ${D} (D="$").
 */
internal object TtlScript {
    private const val D = "$"

    fun build(enable: Boolean): String {
        val enableBit = if (enable) "1" else "0"
        return """
set +e
echo '=== TTL_SCRIPT start enable=${D}enableBit ==='
# --- determine outgoing iface / src ---
OUT_IFACE=${D}(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${D}i=="dev") {print ${D}(i+1); exit}}')
SRC_IP=${D}(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${D}i=="src") {print ${D}(i+1); exit}}')
[ -z "${D}OUT_IFACE" ] && OUT_IFACE=${D}(ip route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${D}i=="dev"){print ${D}(i+1); exit}}' | head -1)
IFACE_ARG="${D}{OUT_IFACE:-any}"
echo OUT_IFACE=${D}OUT_IFACE SRC_IP=${D}SRC_IP IFACE_ARG=${D}IFACE_ARG
# --- resolve tcpdump + timeout binaries ---
TCPDUMP_BIN=""
TIMEOUT_BIN=""
for cand in tcpdump toybox-tcpdump busybox-tcpdump /system/bin/tcpdump /system/xbin/tcpdump; do
  if command -v ${D}cand >/dev/null 2>&1; then TCPDUMP_BIN=${D}cand; break; fi
done
for cand in timeout toybox-timeout; do
  if command -v ${D}cand >/dev/null 2>&1; then TIMEOUT_BIN=${D}cand; break; fi
done
echo TCPDUMP_BIN=${D}TCPDUMP_BIN TIMEOUT_BIN=${D}TIMEOUT_BIN
# --- read current rule TTL/HL from iptables -S ---
RULE_TTL=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
RULE_HL=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
echo RULE_V4_TTL=${D}RULE_TTL RULE_V6_HL=${D}RULE_HL
# --- counters BEFORE ---
CNTR_BEFORE_V4=${D}(iptables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/TTL/ && /ttl-set/ {print ${D}2; exit}')
CNTR_BEFORE_V6=${D}(ip6tables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/HL/ && /hl-set/ {print ${D}2; exit}')
[ -z "${D}CNTR_BEFORE_V4" ] && CNTR_BEFORE_V4=0
[ -z "${D}CNTR_BEFORE_V6" ] && CNTR_BEFORE_V6=0
echo TTL_COUNTERS_BEFORE_V4=${D}CNTR_BEFORE_V4 V6=${D}CNTR_BEFORE_V6
# --- apply rules if needed ---
V4_EXIST=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c -- '--ttl-set')
V6_EXIST=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c -- '--hl-set')
echo TTL_V4_EXIST=${D}V4_EXIST TTL_V6_EXIST=${D}V6_EXIST
if [ "${D}enableBit" = "1" ]; then
  if [ "${D}V4_EXIST" -lt 1 ] 2>/dev/null; then
    echo "APPENDING iptables TTL --ttl-set 65"
    iptables -w 5 -t mangle -A POSTROUTING -j TTL --ttl-set 65 2>&1 || echo IPTV4_FAIL=${D}?
  else
    echo "V4 rule already present (ttl-set=${D}RULE_TTL)"
  fi
  if [ "${D}V6_EXIST" -lt 1 ] 2>/dev/null; then
    echo "APPENDING ip6tables HL --hl-set 65"
    ip6tables -w 5 -t mangle -A POSTROUTING -j HL --hl-set 65 2>&1 || echo IPTV6_FAIL=${D}?
  else
    echo "V6 rule already present (hl-set=${D}RULE_HL)"
  fi
else
  for i in 1 2 3 4 5 6; do
    iptables -w 5 -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break
  done
  for i in 1 2 3 4 5 6; do
    ip6tables -w 5 -t mangle -D POSTROUTING -j HL --hl-set 65 2>/dev/null || break
  done
fi
RULE_TTL_AFTER=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
RULE_HL_AFTER=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
echo RULE_V4_TTL_AFTER=${D}RULE_TTL_AFTER RULE_V6_HL_AFTER=${D}RULE_HL_AFTER
# --- verify via -C with grep -c fallback ---
V4_AFTER=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c -- '--ttl-set')
V6_AFTER=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c -- '--hl-set')
iptables -w 5 -t mangle -C POSTROUTING -j TTL --ttl-set 65 >/dev/null 2>&1; VC4=${D}?
ip6tables -w 5 -t mangle -C POSTROUTING -j HL --hl-set 65 >/dev/null 2>&1; VC6=${D}?
if [ "${D}VC4" -eq 0 ] 2>/dev/null; then VERIFY_V4=OK
elif [ "${D}V4_AFTER" -ge 1 ] 2>/dev/null; then VERIFY_V4=OK-grepc
else VERIFY_V4=FAIL; fi
if [ "${D}VC6" -eq 0 ] 2>/dev/null; then VERIFY_V6=OK
elif [ "${D}V6_AFTER" -ge 1 ] 2>/dev/null; then VERIFY_V6=OK-grepc
else VERIFY_V6=FAIL; fi
echo TTL_V4=${D}V4_AFTER TTL_V6=${D}V6_AFTER VERIFY_V4=${D}VERIFY_V4 VERIFY_V6=${D}VERIFY_V6
# --- real TTL via tcpdump (IPv4) ---
TD_DIR=/data/local/tmp
mkdir -p ${D}TD_DIR 2>/dev/null || true
TD_ERR="${D}TD_DIR/ttl_tcpdump_${D}${D}.err"
TD_ERR6="${D}TD_DIR/ttl_tcpdump6_${D}${D}.err"
rm -f ${D}TD_ERR ${D}TD_ERR6
TTL_REAL=""
HL_REAL=""
WIRE_TTL_METHOD="none"
if [ -n "${D}TCPDUMP_BIN" ]; then
  # IPv4 capture: timeout 6, -c 2 (one for ping request + retrans), under timeout so we never hang
  if [ -n "${D}TIMEOUT_BIN" ]; then
    ${D}TIMEOUT_BIN 6 ${D}TCPDUMP_BIN -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip and dst host 1.1.1.1' >"${D}TD_ERR" 2>&1 &
  else
    ${D}TCPDUMP_BIN -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip and dst host 1.1.1.1' >"${D}TD_ERR" 2>&1 &
  fi
  TD_PID=${D}!
  sleep 1
  ping -c 2 -W 2 1.1.1.1 >/dev/null 2>&1 || true
  wait ${D}TD_PID 2>/dev/null || true
  kill ${D}TD_PID 2>/dev/null || true
  TTL_REAL=${D}(grep -oE 'ttl[[:space:]]+[0-9]+' "${D}TD_ERR" | head -1 | awk '{print ${D}2}')
  echo TCPDUMP_V4_TAIL=${D}(tail -5 "${D}TD_ERR" 2>/dev/null | tr '\n' ' ')
  echo TTL_V4_WIRE=${D}TTL_REAL
  # IPv6 capture (separate filter for hlim)
  if [ -n "${D}TIMEOUT_BIN" ]; then
    ${D}TIMEOUT_BIN 6 ${D}TCPDUMP_BIN -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip6 and dst host 2606:4700:4700::1111' >"${D}TD_ERR6" 2>&1 &
  else
    ${D}TCPDUMP_BIN -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip6 and dst host 2606:4700:4700::1111' >"${D}TD_ERR6" 2>&1 &
  fi
  TD_PID6=${D}!
  sleep 1
  ping6 -c 2 -W 2 2606:4700:4700::1111 >/dev/null 2>&1 || true
  wait ${D}TD_PID6 2>/dev/null || true
  kill ${D}TD_PID6 2>/dev/null || true
  HL_REAL=${D}(grep -oE 'hlim[[:space:]]+[0-9]+' "${D}TD_ERR6" | head -1 | awk '{print ${D}2}')
  echo TCPDUMP_V6_TAIL=${D}(tail -5 "${D}TD_ERR6" 2>/dev/null | tr '\n' ' ')
  echo HLIM_V6_WIRE=${D}HL_REAL
else
  echo "TCPDUMP absent - cannot measure real TTL from wire"
fi
# --- counters AFTER ping ---
CNTR_AFTER_V4=${D}(iptables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/TTL/ && /ttl-set/ {print ${D}2; exit}')
CNTR_AFTER_V6=${D}(ip6tables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/HL/ && /hl-set/ {print ${D}2; exit}')
[ -z "${D}CNTR_AFTER_V4" ] && CNTR_AFTER_V4=0
[ -z "${D}CNTR_AFTER_V6" ] && CNTR_AFTER_V6=0
DELTA_V4=$(( ${D}{CNTR_AFTER_V4:-0} - ${D}{CNTR_BEFORE_V4:-0} )) 2>/dev/null || DELTA_V4=0
DELTA_V6=$(( ${D}{CNTR_AFTER_V6:-0} - ${D}{CNTR_BEFORE_V6:-0} )) 2>/dev/null || DELTA_V6=0
case ${D}DELTA_V4 in ''|*[!0-9-]*) DELTA_V4=0 ;; esac
case ${D}DELTA_V6 in ''|*[!0-9-]*) DELTA_V6=0 ;; esac
echo TTL_COUNTERS: before_v4=${D}CNTR_BEFORE_V4 after_v4=${D}CNTR_AFTER_V4 delta_v4=${D}DELTA_V4 before_v6=${D}CNTR_BEFORE_V6 after_v6=${D}CNTR_AFTER_V6 delta_v6=${D}DELTA_V6
rm -f ${D}TD_ERR ${D}TD_ERR6
# --- verdict per iter18 matrix ---
EXPECTED=65
VERDICT="UNVERIFIED"
REASON=""
METHOD="none"
REAL_SHOW="UNKNOWN"
RULE_SHOW=${D}RULE_TTL_AFTER
[ -z "${D}RULE_SHOW" ] && RULE_SHOW="none"
if [ -n "${D}TTL_REAL" ] && [ "${D}TTL_REAL" = "${D}EXPECTED" ]; then
  REAL_SHOW=${D}TTL_REAL; METHOD=tcpdump; VERDICT=VERIFIED; REASON="wire ttl matches expected"
elif [ -n "${D}TTL_REAL" ] && [ "${D}TTL_REAL" != "${D}EXPECTED" ]; then
  REAL_SHOW=${D}TTL_REAL; METHOD=tcpdump; VERDICT=MISMATCH; REASON="wire ttl=${D}TTL_REAL != expected=${D}EXPECTED"
elif [ -z "${D}TCPDUMP_BIN" ]; then
  REAL_SHOW=UNKNOWN; METHOD=none; VERDICT=UNVERIFIED; REASON="tcpdump-absent"
elif [ "${D}DELTA_V4" -gt 0 ] 2>/dev/null; then
  # iter18: число из правила НЕ подставляется в real=; real=UNKNOWN, verdict=INDIRECT
  REAL_SHOW=UNKNOWN; METHOD=iptables-counters; VERDICT=INDIRECT; REASON="counters delta=${D}DELTA_V4 after ping, but no captured wire packet"
elif [ -n "${D}RULE_TTL_AFTER" ]; then
  REAL_SHOW=UNKNOWN; METHOD=rule-text; VERDICT=UNVERIFIED; REASON="rule present but no wire measurement and counters did not increment (delta=${D}DELTA_V4)"
else
  REAL_SHOW=UNKNOWN; METHOD=none; VERDICT=UNVERIFIED; REASON="no rule, no tcpdump, no counters - nothing to verify"
fi
echo "TTL_CHECK: real=${D}REAL_SHOW expected=${D}EXPECTED src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}IFACE_ARG method=${D}METHOD reason=${D}REASON rule_ttl=${D}RULE_SHOW hl_wire=${D}HL_REAL ARROW ${D}VERDICT"
echo '=== TTL_SCRIPT end ==='
""".trimIndent().replace("ARROW", "\u2192")
    }
}
