package com.example.voiceapp3.tools

/**
 * Shell-скрипт TTL/HL (iter33m §1) — правила ТОЛЬКО у uplink, удаление БЕЗ word-splitting.
 *
 * Почему переписано (лог 07.11, `run=050ce701`):
 *   • `del_all()` разбирал `iptables -S` через `for spec in $(…)` — слова рвались по пробелам,
 *     `-D … $spec` не совпадал с правилом, правила оставались: `TTL_OFF v4=1 v6=1 result=FAILED`
 *     при `enable=0`. Теперь — `while IFS= read -r spec` + `sed 's/^-A //'`, плюс эталонный
 *     цикл `-D … || break` как дополнение.
 *   • скоуп `! -o p2p0` (fallback `exclude_carplay`) не покрывал групповой интерфейс
 *     `p2p-p2p0-0` и ломал CarPlay. Теперь скоуп ровно один: `-o <uplink>`. Нет uplink —
 *     правило НЕ ставится: `TTL_TRY variant=none reason=no_uplink`, тумблер `✗`.
 *
 * Что печатает (по одной строке, всё видно в `app.log`):
 *   `TTL_NET ifaces=… carplay=… routes=… uplink=… v4=… v6=…`   — диагностика (read-only)
 *   `TTL_SANITIZED removed=<n> v4_removed=… v6_removed=…`       — санация (старт/ON/OFF)
 *   `TTL_DEL v4|v6 spec="…" rc=<n>`                             — что именно сняли
 *   `TTL_FOREIGN spec="…"`                                      — чужое `ttl-set` правило, если осталось
 *   `TTL_TRY variant=uplink|none scope="…" …`
 *   `TTL_ADD v4|v6 scope="…" rc=<n>`
 *   `TTL_V4=<n> TTL_V6=<n> VERIFY_V4=OK|FAIL|OFF_OK VERIFY_V6=…`
 *   `TTL_OFF v4=… v6=… result=OK|FAILED`                        — только при выключении
 *   `TTL_SCOPE result=scoped|unscoped|failed scoped=… rule="…"`
 *   `TTL_CHECK: … → VERDICT` / `TTL_RESULT …`
 *
 * Глобального правила `-j TTL --ttl-set 65` БЕЗ скоупа здесь нет. Измерение по проводу
 * (tcpdump) не делается: подтверждение — счётчики правил и живой CarPlay (глазами владельца).
 */
internal object TtlScript {
    private const val D = "$"

    fun build(enable: Boolean): String {
        val enableBit = if (enable) "1" else "0"
        return """
set +e
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
# CarPlay/групповой p2p никогда не uplink: правило на него ставить запрещено.
case "${D}UPLINK" in
  p2p*|rndis*|ncm*|usb*|ap0*) echo "TTL_UPLINK_REJECT uplink=${D}UPLINK reason=carplay_iface"; UPLINK="" ;;
esac
SRC_IP=${D}(echo "${D}ROUTE_GET" | awk '{for(i=1;i<=NF;i++) if(${D}i=="src") {print ${D}(i+1); exit}}')
V4_NOW=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set')
V6_NOW=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set')
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

# ---------- удаление ВСЕХ правил ttl-set/hl-set ----------
# iter33m §1.1: НИКАКОГО word-splitting. Строка правила читается целиком (`IFS= read -r`),
# префикс `-A ` срезается sed — получается точный спецификатор для `-D POSTROUTING <spec>`.
del_all() {
  iptables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | sed -n 's/^-A POSTROUTING //p' | while IFS= read -r spec; do
    [ -z "${D}spec" ] && continue
    iptables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null
    echo "TTL_DEL v4 spec=\"${D}spec\" rc=${D}?"
  done
  ip6tables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | sed -n 's/^-A POSTROUTING //p' | while IFS= read -r spec; do
    [ -z "${D}spec" ] && continue
    ip6tables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null
    echo "TTL_DEL v6 spec=\"${D}spec\" rc=${D}?"
  done
  # эталонный цикл референса — дополнение на случай правила, которого нет в `-S` по grep
  for i in 1 2 3 4 5; do
    iptables -w 5 -t mangle -D POSTROUTING -j TTL --ttl-set 65 2>/dev/null || break
  done
  for i in 1 2 3 4 5; do
    ip6tables -w 5 -t mangle -D POSTROUTING -j HL --hl-set 65 2>/dev/null || break
  done
}

# ---------- санация: снять всё и напечатать, сколько сняли ----------
sanitize() {
  B4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set')
  B6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set')
  [ -z "${D}B4" ] && B4=0
  [ -z "${D}B6" ] && B6=0
  del_all
  A4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set')
  A6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set')
  [ -z "${D}A4" ] && A4=0
  [ -z "${D}A6" ] && A6=0
  R4=${D}((B4-A4)); R6=${D}((B6-A6)); [ "${D}R4" -lt 0 ] && R4=0; [ "${D}R6" -lt 0 ] && R6=0
  echo "TTL_SANITIZED removed=${D}((R4+R6)) v4_removed=${D}R4 v6_removed=${D}R6 left_v4=${D}A4 left_v6=${D}A6"
  # чужое правило (не наше, но с ttl-set) — только в лог, ничего не трогаем повторно
  iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | sed 's/^/TTL_FOREIGN v4 spec="/;s/${D}/"/'
  ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | sed 's/^/TTL_FOREIGN v6 spec="/;s/${D}/"/'
}

VARIANT=none
if [ "${D}ENABLE" = "1" ]; then
  # ---------- включение: только uplink ----------
  sanitize
  if [ -z "${D}UPLINK" ]; then
    echo "TTL_TRY variant=none scope=\"\" v4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set') v6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set') reason=no_uplink"
  else
    SCOPE="-o ${D}UPLINK"
    VARIANT=uplink
    echo "TTL_TRY variant=${D}VARIANT scope=\"${D}SCOPE\" v4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set') v6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set') carplay=${D}{CARPLAY:-none}"
    iptables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j TTL --ttl-set 65 2>&1
    echo "TTL_ADD v4 scope=\"${D}SCOPE\" rc=${D}?"
    ip6tables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j HL --hl-set 65 2>&1
    echo "TTL_ADD v6 scope=\"${D}SCOPE\" rc=${D}?"
    # iter33n §4.4: правило РОВНО одно. Если после добавления копий больше (гонка двух
    # одновременных включений / старый дубль), лишние снимаем здесь же и печатаем счётчик.
    dedupe() {
      N=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set')
      [ -z "${D}N" ] && N=0
      while [ "${D}N" -gt 1 ] 2>/dev/null; do
        iptables -w 5 -t mangle -D POSTROUTING ${D}SCOPE -j TTL --ttl-set 65 2>/dev/null || break
        N=${D}((N-1))
      done
      M=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set')
      [ -z "${D}M" ] && M=0
      while [ "${D}M" -gt 1 ] 2>/dev/null; do
        ip6tables -w 5 -t mangle -D POSTROUTING ${D}SCOPE -j HL --hl-set 65 2>/dev/null || break
        M=${D}((M-1))
      done
      echo "TTL_ADD_COUNT n=${D}N v6=${D}M scope=\"${D}SCOPE\""
    }
    dedupe
  fi
else
  # ---------- выключение: санация (снять всё) ----------
  VARIANT=none
  sanitize
fi

# ---------- счётчики и проверка ----------
TTL_V4=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set')
TTL_V6=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set')
[ -z "${D}TTL_V4" ] && TTL_V4=0
[ -z "${D}TTL_V6" ] && TTL_V6=0
VERIFY_V4=FAIL
VERIFY_V6=FAIL
if [ "${D}ENABLE" = "1" ]; then
  [ "${D}TTL_V4" -ge 1 ] 2>/dev/null && VERIFY_V4=OK
  [ "${D}TTL_V6" -ge 1 ] 2>/dev/null && VERIFY_V6=OK
else
  # OFF: отдельный маркер OFF_OK (не путать с OK правила) — иначе парсер считает выключение сбоем
  [ "${D}TTL_V4" = "0" ] && VERIFY_V4=OFF_OK
  [ "${D}TTL_V6" = "0" ] && VERIFY_V6=OFF_OK
  echo "TTL_OFF v4=${D}TTL_V4 v6=${D}TTL_V6 result=${D}([ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ] && echo OK || echo FAILED)"
fi
echo TTL_V4=${D}TTL_V4 TTL_V6=${D}TTL_V6 VERIFY_V4=${D}VERIFY_V4 VERIFY_V6=${D}VERIFY_V6
RULE_LINE=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | head -1)
RULE_TTL=${D}(echo "${D}RULE_LINE" | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
RULE_HL=${D}(ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | head -1 | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
SCOPED=no
SCOPE_UPLINK_ONLY=no
echo "${D}RULE_LINE" | grep -qE -- "^-A POSTROUTING -o ${D}{UPLINK} " && SCOPE_UPLINK_ONLY=yes
if [ "${D}ENABLE" = "0" ]; then
  [ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ] && SCOPE_UPLINK_ONLY=yes
fi
echo "${D}RULE_LINE" | grep -qE -- '-o ' && SCOPED=yes
if [ "${D}ENABLE" = "0" ] && [ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ]; then SCOPED=yes; fi
if [ "${D}ENABLE" = "1" ] && [ -z "${D}UPLINK" ]; then
echo "TTL_SCOPE result=failed scoped=no uplink_only=no uplink=none rule=\"${D}{RULE_LINE:-none}\" reason=no_uplink"
else
echo "TTL_SCOPE result=${D}([ "${D}SCOPED" = "yes" ] && echo scoped || echo unscoped) scoped=${D}SCOPED uplink_only=${D}SCOPE_UPLINK_ONLY uplink=${D}{UPLINK:-none} rule=\"${D}{RULE_LINE:-none}\""
fi

EXPECTED=65
BEFORE_N=${D}RULE_TTL; [ -z "${D}BEFORE_N" ] && BEFORE_N=none
AFTER_N=${D}RULE_TTL; [ -z "${D}AFTER_N" ] && AFTER_N=none
if [ "${D}ENABLE" = "1" ] && [ "${D}TTL_V4" -ge 1 ] 2>/dev/null && [ "${D}SCOPE_UPLINK_ONLY" = "yes" ]; then VERDICT=SCOPED_OK
elif [ "${D}ENABLE" = "0" ] && [ "${D}TTL_V4" = "0" ] && [ "${D}TTL_V6" = "0" ]; then VERDICT=OFF_OK
else VERDICT=CHECK_OWNER; fi
echo "TTL_CHECK: before=${D}BEFORE_N after=${D}AFTER_N rc=${D}? rule_ttl=${D}{RULE_TTL:-none} real=UNKNOWN expected=${D}EXPECTED src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}{UPLINK:-none} method=counters reason=wire_not_measured_scoped_rules hl_wire=${D}{RULE_HL:-} ARROW ${D}VERDICT"
echo "TTL_RESULT rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} verify_v4=${D}VERIFY_V4 verify_v6=${D}VERIFY_V6 wire_v4=not_measured enable=${D}ENABLE scoped=${D}SCOPED uplink=${D}{UPLINK:-none} variant=${D}VARIANT"
echo '=== TTL_SCRIPT end ==='
""".trimIndent().replace("ARROW", "\u2192").replace("@ENABLE_VALUE@", enableBit)
    }

    /** Скрипт стартовой санации (boot / приложение стартовало): снять чужие правила, ничего не ставить. */
    fun sanitizeScript(): String = build(false)
}
