package com.example.voiceapp3.tools

/**
 * Shell-скрипт для измерения/установки TTL (iter19).
 *
 * Ключевые гарантии (iter19):
 *  1. ФИНАЛЬНАЯ СТРОКА `TTL_CHECK:` ВСЕГДА печатается — даже при падении tcpdump/iptables,
 *     при отсутствии timeout, при ошибке ping. Строка вынесена из всех if и в конце — это
 *     твёрдая гарантия завершения.
 *  2. `kill $TD_PID` вызывается ДО `wait $TD_PID` — мы не висим, ожидая второго пакета,
 *     которого может не быть. `timeout 6` — только как второй эшелон, не как
 *     единственная защита от зависания.
 *  3. `toybox-timeout` удалён из кандидатов (такой команды нет; есть `toybox timeout`).
 *     Пробуем `timeout` и `/system/bin/timeout`.
 *  4. Строка `PROBE: timeout=<bin|none> tcpdump=<bin|none>` в начале — сразу видно
 *     в логе, какая ветка сработала.
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
for cand in timeout /system/bin/timeout; do
  if command -v ${D}cand >/dev/null 2>&1; then TIMEOUT_BIN=${D}cand; break; fi
done
# iter19: проба выводится в начале — чтобы видно было в логе, каким путём пошло
echo "PROBE: timeout=${D}{TIMEOUT_BIN:-none} tcpdump=${D}{TCPDUMP_BIN:-none}"
# --- read current rule TTL/HL from iptables -S (safely, don't fail the script) ---
RULE_TTL=""
RULE_HL=""
RULE_TTL=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
RULE_HL=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
echo RULE_V4_TTL=${D}RULE_TTL RULE_V6_HL=${D}RULE_HL
# --- counters BEFORE ---
CNTR_BEFORE_V4=0
CNTR_BEFORE_V6=0
CNTR_BEFORE_V4=${D}(iptables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/TTL/ && /ttl-set/ {print ${D}2; exit}')
CNTR_BEFORE_V6=${D}(ip6tables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/HL/ && /hl-set/ {print ${D}2; exit}')
[ -z "${D}CNTR_BEFORE_V4" ] && CNTR_BEFORE_V4=0
[ -z "${D}CNTR_BEFORE_V6" ] && CNTR_BEFORE_V6=0
echo TTL_COUNTERS_BEFORE_V4=${D}CNTR_BEFORE_V4 V6=${D}CNTR_BEFORE_V6
# --- apply rules if needed (best-effort, do not abort on error) ---
V4_EXIST=0
V6_EXIST=0
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
RULE_TTL_AFTER=""
RULE_HL_AFTER=""
RULE_TTL_AFTER=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
RULE_HL_AFTER=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | head -1 | awk '{print ${D}2}')
echo RULE_V4_TTL_AFTER=${D}RULE_TTL_AFTER RULE_V6_HL_AFTER=${D}RULE_HL_AFTER
# --- verify via -C with grep -c fallback ---
V4_AFTER=0
V6_AFTER=0
VC4=1
VC6=1
VERIFY_V4="FAIL"
VERIFY_V6="FAIL"
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
# --- real TTL via tcpdump (IPv4 + IPv6). Kill BEFORE wait to avoid hanging. ---
TD_DIR=/data/local/tmp
mkdir -p ${D}TD_DIR 2>/dev/null || true
TD_ERR="${D}TD_DIR/ttl_tcpdump_${D}${D}.err"
TD_ERR6="${D}TD_DIR/ttl_tcpdump6_${D}${D}.err"
rm -f ${D}TD_ERR ${D}TD_ERR6
TTL_REAL=""
HL_REAL=""
TCPDUMP_ERRMSG=""
# iter20: tcpdump -l — построчная буферизация, чтобы пайп/файл сразу видел строки.
# После kill добавлен sleep 0.3 чтобы tcpdump успел сбросить буфер в файл до grep.
# Печатаем TCPDUMP_PID= и TCPDUMP_ALIVE= (kill -0) — для диагностики в логе.
run_v4_capture() {
  local _pid _alive
  if [ -n "${D}TIMEOUT_BIN" ]; then
    ${D}TIMEOUT_BIN 6 ${D}TCPDUMP_BIN -l -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip and dst host 1.1.1.1' >"${D}TD_ERR" 2>&1 &
  else
    ${D}TCPDUMP_BIN -l -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip and dst host 1.1.1.1' >"${D}TD_ERR" 2>&1 &
  fi
  _pid=${D}!
  echo "TCPDUMP_PID=${_pid}"
  sleep 1
  ping -c 2 -W 2 1.1.1.1 >/dev/null 2>&1 || true
  sleep 1
  # iter19/20: kill ПЕРВЫМ (не ждать wait бесконечно); после kill пауза на сброс буфера.
  kill ${D}_pid 2>/dev/null || true
  sleep 0.3
  if kill -0 ${D}_pid 2>/dev/null; then _alive=yes; else _alive=no; fi
  echo "TCPDUMP_ALIVE=${_alive} (after kill -0)"
  wait ${D}_pid 2>/dev/null || true
}
run_v6_capture() {
  local _pid _alive
  if [ -n "${D}TIMEOUT_BIN" ]; then
    ${D}TIMEOUT_BIN 6 ${D}TCPDUMP_BIN -l -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip6 and dst host 2606:4700:4700::1111' >"${D}TD_ERR6" 2>&1 &
  else
    ${D}TCPDUMP_BIN -l -ni ${D}IFACE_ARG -c 2 -v -s 0 'ip6 and dst host 2606:4700:4700::1111' >"${D}TD_ERR6" 2>&1 &
  fi
  _pid=${D}!
  echo "TCPDUMP_V6_PID=${_pid}"
  sleep 1
  ping6 -c 2 -W 2 2606:4700:4700::1111 >/dev/null 2>&1 || true
  sleep 1
  kill ${D}_pid 2>/dev/null || true
  sleep 0.3
  if kill -0 ${D}_pid 2>/dev/null; then _alive=yes; else _alive=no; fi
  echo "TCPDUMP_V6_ALIVE=${_alive} (after kill -0)"
  wait ${D}_pid 2>/dev/null || true
}
if [ -n "${D}TCPDUMP_BIN" ]; then
  run_v4_capture
  TTL_REAL=${D}(grep -oE 'ttl[[:space:]]+[0-9]+' "${D}TD_ERR" | head -1 | awk '{print ${D}2}')
  echo TCPDUMP_V4_TAIL=${D}(tail -3 "${D}TD_ERR" 2>/dev/null | tr '\n' ' ')
  echo TTL_V4_WIRE=${D}TTL_REAL
  run_v6_capture
  HL_REAL=${D}(grep -oE 'hlim[[:space:]]+[0-9]+' "${D}TD_ERR6" | head -1 | awk '{print ${D}2}')
  echo TCPDUMP_V6_TAIL=${D}(tail -3 "${D}TD_ERR6" 2>/dev/null | tr '\n' ' ')
  echo HLIM_V6_WIRE=${D}HL_REAL
else
  TCPDUMP_ERRMSG="tcpdump-absent"
  echo "TCPDUMP absent - cannot measure real TTL from wire"
fi
# --- counters AFTER ping ---
CNTR_AFTER_V4=0
CNTR_AFTER_V6=0
CNTR_AFTER_V4=${D}(iptables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/TTL/ && /ttl-set/ {print ${D}2; exit}')
CNTR_AFTER_V6=${D}(ip6tables -t mangle -L POSTROUTING -v -n -x 2>/dev/null | awk '/HL/ && /hl-set/ {print ${D}2; exit}')
[ -z "${D}CNTR_AFTER_V4" ] && CNTR_AFTER_V4=0
[ -z "${D}CNTR_AFTER_V6" ] && CNTR_AFTER_V6=0
DELTA_V4=0
DELTA_V6=0
DELTA_V4=$(( ${D}{CNTR_AFTER_V4:-0} - ${D}{CNTR_BEFORE_V4:-0} )) 2>/dev/null || DELTA_V4=0
DELTA_V6=$(( ${D}{CNTR_AFTER_V6:-0} - ${D}{CNTR_BEFORE_V6:-0} )) 2>/dev/null || DELTA_V6=0
case ${D}DELTA_V4 in ''|*[!0-9-]*) DELTA_V4=0 ;; esac
case ${D}DELTA_V6 in ''|*[!0-9-]*) DELTA_V6=0 ;; esac
echo TTL_COUNTERS: before_v4=${D}CNTR_BEFORE_V4 after_v4=${D}CNTR_AFTER_V4 delta_v4=${D}DELTA_V4 before_v6=${D}CNTR_BEFORE_V6 after_v6=${D}CNTR_AFTER_V6 delta_v6=${D}DELTA_V6
rm -f ${D}TD_ERR ${D}TD_ERR6
# --- verdict matrix (iter19 сохраняет iter18 матрицу) ---
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
  REAL_SHOW=UNKNOWN; METHOD=none; VERDICT=UNVERIFIED; REASON=${D}TCPDUMP_ERRMSG
elif [ "${D}DELTA_V4" -gt 0 ] 2>/dev/null; then
  REAL_SHOW=UNKNOWN; METHOD=iptables-counters; VERDICT=INDIRECT; REASON="counters delta=${D}DELTA_V4 after ping, but no captured wire packet"
elif [ -n "${D}RULE_TTL_AFTER" ]; then
  REAL_SHOW=UNKNOWN; METHOD=rule-text; VERDICT=UNVERIFIED; REASON="rule present but no wire measurement and counters did not increment (delta=${D}DELTA_V4)"
else
  REAL_SHOW=UNKNOWN; METHOD=none; VERDICT=UNVERIFIED; REASON="no rule, no tcpdump, no counters - nothing to verify"
fi
# --- ФИНАЛЬНАЯ СТРОКА ГАРАНТИРОВАННО печатается ВСЕГДА ---
echo "TTL_CHECK: real=${D}REAL_SHOW expected=${D}EXPECTED src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}IFACE_ARG method=${D}METHOD reason=${D}REASON rule_ttl=${D}RULE_SHOW hl_wire=${D}HL_REAL ARROW ${D}VERDICT"
echo '=== TTL_SCRIPT end ==='
""".trimIndent().replace("ARROW", "\u2192")
    }
}
