package com.example.voiceapp3.tools

/**
 * Shell-скрипт TTL/HL (iter33k §3): правила СО СКОУПОМ, чтобы TTL и CarPlay жили вместе.
 *
 * Что печатает (по одной строке, всё видно в `app.log`):
 *   `TTL_NET ifaces=… carplay=… uplink=… v4=… v6=…`   — диагностика перед лечением
 *   `TTL_TRY variant=uplink|exclude_carplay scope="…" v4=… v6=… carplay=unknown`
 *   `TTL_V4=<n> TTL_V6=<n> VERIFY_V4=OK|FAIL VERIFY_V6=OK|FAIL`
 *   `TTL_OFF v4=… v6=… result=OK|FAILED`               — только при выключении
 *   `TTL_RESULT rule_v4=… rule_v6=… verify_v4=… verify_v6=… wire_v4=… enable=…`
 *   `TTL_CHECK: before=… after=… rc=… rule_ttl=… real=… expected=65 … method=counters reason=… → VERDICT`
 *
 * Важно: глобального правила `-j TTL --ttl-set 65` БЕЗ скоупа здесь нет вообще — именно оно
 * ломало CarPlay. Измерение по проводу (tcpdump) в этой итерации не делается: проверка —
 * счётчики правил (`TTL_V4`/`TTL_V6`) и живое соединение CarPlay (владелец глазами).
 */
internal object TtlScript {
    private const val D = "$"

    fun build(enable: Boolean): String {
        val enableBit = if (enable) "1" else "0"
        return """
set +e
# iter33k §3: ENABLE — ЗНАЧЕНИЕ (0|1), подставленное из Kotlin, а не имя переменной.
ENABLE=@ENABLE_VALUE@
echo '=== TTL_SCRIPT start enable='${D}ENABLE' ==='

# ---------- диагностика (read-only) ----------
LINK=${D}(ip link 2>/dev/null)
ADDR=${D}(ip -br addr 2>/dev/null)
ROUTE_GET=${D}(ip route get 1.1.1.1 2>/dev/null)
ROUTE_DEF=${D}(ip route show default 2>/dev/null | head -1)
IFACES=${D}(echo "${D}LINK" | sed -n 's/^[0-9][0-9]*: \([A-Za-z0-9_.@-]*\):.*/\1/p' | sed 's/@.*//' | grep -v '^lo${D}' | tr '\n' ',' | sed 's/,${D}//')
CARPLAY=${D}(echo "${D}IFACES" | tr ',' '\n' | grep -E '^(rndis|ncm|usb|p2p|ap0)' | tr '\n' ',' | sed 's/,${D}//')
UPLINK=${D}(echo "${D}ROUTE_GET" | awk '{for(i=1;i<=NF;i++) if(${D}i=="dev") {print ${D}(i+1); exit}}')
[ -z "${D}UPLINK" ] && UPLINK=${D}(echo "${D}ROUTE_DEF" | awk '{for(i=1;i<=NF;i++) if(${D}i=="dev") {print ${D}(i+1); exit}}')
SRC_IP=${D}(echo "${D}ROUTE_GET" | awk '{for(i=1;i<=NF;i++) if(${D}i=="src") {print ${D}(i+1); exit}}')
V4_NOW=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
V6_NOW=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
[ -z "${D}V4_NOW" ] && V4_NOW=0
[ -z "${D}V6_NOW" ] && V6_NOW=0
echo "TTL_NET ifaces=${D}{IFACES:-none} carplay=${D}{CARPLAY:-none} routes=${D}{ROUTE_DEF:-none} uplink=${D}{UPLINK:-none} v4=${D}V4_NOW v6=${D}V6_NOW"
echo "OUT_IFACE=${D}{UPLINK:-none} SRC_IP=${D}{SRC_IP:-none} CARPLAY_IF=${D}{CARPLAY:-none}"
if [ -z "${D}UPLINK" ]; then
  if command -v ip >/dev/null 2>&1; then
    echo ROUTE_SRC=none reason=no_default_route
  else
    echo ROUTE_SRC=none reason=no_ip_binary
  fi
fi

# ---------- удаление ВСЕХ прежних правил (включая глобальную форму старых версий) ----------
# generic-проход: снимаем каждое правило, в котором есть ttl-set/hl-set (скоуп не важен).
del_all() {
  for spec in ${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | sed 's/^-A //'); do
    iptables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null || true
  done
  for spec in ${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | sed 's/^-A //'); do
    ip6tables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null || true
  done
  # эталонный цикл референса — на случай глобальной формы, добавленной старыми версиями
  for i in 1 2 3 4 5; do
    iptables -w 5 -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break
  done
  for i in 1 2 3 4 5; do
    ip6tables -w 5 -t mangle -D POSTROUTING -j HL --hl-set 65 2>/dev/null || break
  done
}

V4_BEFORE=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
V6_BEFORE=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
[ -z "${D}V4_BEFORE" ] && V4_BEFORE=0
[ -z "${D}V6_BEFORE" ] && V6_BEFORE=0

if [ "${D}ENABLE" = "1" ]; then
  # ---------- включение: скоуп (а) uplink → (б) всё кроме CarPlay → иначе честный FAILED ----------
  SCOPE=""
  VARIANT=none
  if [ -n "${D}UPLINK" ]; then
    SCOPE="-o ${D}UPLINK"; VARIANT=uplink
  elif [ -n "${D}CARPLAY" ]; then
    CP_FIRST=${D}(echo "${D}CARPLAY" | cut -d, -f1)
    SCOPE="! -o ${D}CP_FIRST"; VARIANT=exclude_carplay
  fi
  if [ "${D}VARIANT" = "none" ]; then
    echo "TTL_TRY variant=none scope=\"\" v4=${D}V4_BEFORE v6=${D}V6_BEFORE carplay=unknown reason=no_uplink_no_carplay"
    echo "TTL_SCOPE result=failed reason=no_uplink_no_carplay (глобальное правило запрещено — ломает CarPlay)"
  else
    del_all
    echo "TTL_TRY variant=${D}VARIANT scope=\"${D}SCOPE\" v4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65') v6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65') carplay=unknown"
    iptables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j TTL --ttl-set 65 2>&1 || echo IPTV4_FAIL=${D}?
    ip6tables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j HL --hl-set 65 2>&1 || echo IPTV6_FAIL=${D}?
  fi
else
  # ---------- выключение: эталонное удаление циклом + generic ----------
  del_all
fi

# ---------- счётчики и проверка ----------
TTL_V4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set 65')
TTL_V6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set 65')
[ -z "${D}TTL_V4" ] && TTL_V4=0
[ -z "${D}TTL_V6" ] && TTL_V6=0
VERIFY_V4=FAIL
VERIFY_V6=FAIL
[ "${D}TTL_V4" -ge 1 ] 2>/dev/null && VERIFY_V4=OK
[ "${D}TTL_V6" -ge 1 ] 2>/dev/null && VERIFY_V6=OK
if [ "${D}ENABLE" = "0" ]; then
  [ "${D}TTL_V4" = "0" ] && VERIFY_V4=OK
  [ "${D}TTL_V6" = "0" ] && VERIFY_V6=OK
  echo "TTL_OFF v4=${D}TTL_V4 v6=${D}TTL_V6 result=${D}([ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ] && echo OK || echo FAILED)"
fi
echo TTL_V4=${D}TTL_V4 TTL_V6=${D}TTL_V6 VERIFY_V4=${D}VERIFY_V4 VERIFY_V6=${D}VERIFY_V6
RULE_LINE=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set 65' | head -1)
RULE_TTL=${D}(echo "${D}RULE_LINE" | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
RULE_HL=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set 65' | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
SCOPED=no
echo "${D}RULE_LINE" | grep -qE -- '-o |! -o ' && SCOPED=yes
echo "TTL_SCOPE result=${D}([ "${D}SCOPED" = "yes" ] && echo scoped || echo unscoped) scoped=${D}SCOPED rule=\"${D}{RULE_LINE:-none}\""

EXPECTED=65
BEFORE_N=${D}RULE_TTL; [ -z "${D}BEFORE_N" ] && BEFORE_N=none
AFTER_N=${D}RULE_TTL; [ -z "${D}AFTER_N" ] && AFTER_N=none
if [ "${D}ENABLE" = "1" ] && [ "${D}TTL_V4" -ge 1 ] 2>/dev/null && [ "${D}SCOPED" = "yes" ]; then VERDICT=SCOPED_OK
elif [ "${D}ENABLE" = "0" ] && [ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ]; then VERDICT=OFF_OK
else VERDICT=CHECK_OWNER; fi
echo "TTL_CHECK: before=${D}BEFORE_N after=${D}AFTER_N rc=${D}? rule_ttl=${D}{RULE_TTL:-none} real=UNKNOWN expected=${D}EXPECTED src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}{UPLINK:-none} method=counters reason=wire_not_measured_scoped_rules hl_wire=${D}{RULE_HL:-} ARROW ${D}VERDICT"
echo "TTL_RESULT rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} verify_v4=${D}VERIFY_V4 verify_v6=${D}VERIFY_V6 wire_v4=not_measured enable=${D}ENABLE scoped=${D}SCOPED variant=${D}VARIANT"
echo '=== TTL_SCRIPT end ==='
""".trimIndent().replace("ARROW", "\u2192").replace("@ENABLE_VALUE@", enableBit)
    }
}
