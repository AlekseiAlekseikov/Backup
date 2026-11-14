package com.example.voiceapp3.tools

/**
 * Shell-скрипт TTL/HL — iter48 §Б: **правила принадлежат приложению, а не «всем ttl-set»**.
 *
 * Что было не так (подтверждено кодом на `6a944c0`):
 *  • `del_all()` перебирал и удалял ВСЕ правила mangle/POSTROUTING, где встречается `ttl-set` /
 *    `hl-set`, и `sanitize()` вызывался даже при включении. Принадлежность не проверялась вовсе:
 *    чужое правило (другого приложения/системы) снималось так же, как своё. Комментарий
 *    «чужое — только в лог» стоял ПОСЛЕ удаления и ничего не защищал.
 *  • «своё» определялось по значению `65` и одному интерфейсу — это не доказательство владения.
 *
 * Как сделано теперь — отдельная ЦЕПОЧКА, имя которой и есть метка владения:
 *  • v4: `mangle/VAPP_TTL`, внутри одно правило `-j TTL --ttl-set 65`;
 *  • переход из POSTROUTING: ровно один `-o <uplink> -j VAPP_TTL`;
 *  • v6: то же с `mangle/VAPP_TTL` в ip6tables (`-j HL --hl-set 65`).
 *
 * Почему цепочка, а не `-m comment`: match `comment` есть не во всех сборках/ядрах, и его
 * отсутствие молча ломало бы разметку. Пользовательская цепочка — базовая возможность iptables,
 * её имя видно в `-S` и не зависит от модулей. Ничего «недоступного» не предполагается.
 *
 * ON/OFF/смена сети/boot трогают ТОЛЬКО `VAPP_TTL` и переходы на неё. Чужие правила
 * (`ttl-set`/`hl-set` вне нашей цепочки) не удаляются никогда — они только перечисляются как
 * `TTL_FOREIGN`, а исторические НЕмаркированные правила нашего же формата — как `TTL_LEGACY`
 * с `action=keep reason=ownership_unproven` (значение 65 или один интерфейс владением не считаются).
 *
 * Что печатает:
 *   `TTL_NET …`                                   — диагностика (read-only)
 *   `TTL_OWN chain=present|absent jumps=<n> rules=<n>`
 *   `TTL_FOREIGN v4|v6 spec="…"`                  — чужое правило, НЕ удаляется
 *   `TTL_LEGACY v4|v6 spec="…" action=keep reason=ownership_unproven`
 *   `TTL_DEL v4|v6 spec="…" rc=<n>`               — что именно сняли (только наше)
 *   `TTL_ADD v4|v6 scope="…" rc=<n>`
 *   `TTL_V4=<n> TTL_V6=<n> OWN_V4=<n> OWN_V6=<n> FOREIGN_V4=<n> FOREIGN_V6=<n> VERIFY_V4=… VERIFY_V6=…`
 *   `TTL_OFF … result=OK|PARTIAL|FAILED`
 *   `TTL_SCOPE result=scoped|unscoped|failed scoped=… uplink_only=… rule="…" own_jump="…"`
 *   `TTL_CHECK: … → VERDICT` / `TTL_RESULT …`
 *
 * Реальный TTL на проводе по-прежнему НЕ измеряется (`wire=not_measured`): iptables-правило —
 * это не доказательство значения TTL в пакетах.
 */
internal object TtlScript {
    private const val D = "$"

    /** Имя нашей цепочки. Оно же — метка владения; меняется только вместе с миграцией. */
    internal const val OUR_CHAIN = "VAPP_TTL"

    fun build(enable: Boolean): String {
        val enableBit = if (enable) "1" else "0"
        return """
set +e
ENABLE=@ENABLE_VALUE@
OUR=@OUR_CHAIN@
echo '=== TTL_SCRIPT start enable='${D}ENABLE' chain='${D}OUR' ==='

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

# ---------- инвентарь: своё и чужое ----------
# total — ВСЕ правила с ttl-set/hl-set (нужно только для честного счёта чужого);
# own — правила ВНУТРИ нашей цепочки; jump — переходы на нашу цепочку из POSTROUTING.
count_own_rules() { iptables -w 5 -t mangle -S "${D}OUR" 2>/dev/null | grep -c 'ttl-set'; }
count_own_jumps() { iptables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -c -- "-j ${D}OUR"; }
count_own_rules6() { ip6tables -w 5 -t mangle -S "${D}OUR" 2>/dev/null | grep -c 'hl-set'; }
count_own_jumps6() { ip6tables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -c -- "-j ${D}OUR"; }
count_all_rules() { iptables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'ttl-set'; }
count_all_rules6() { ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep -c 'hl-set'; }
num() { v=${D}1; [ -z "${D}v" ] && v=0; echo "${D}v"; }

ALL_V4=${D}(num "${D}(count_all_rules)")
ALL_V6=${D}(num "${D}(count_all_rules6)")
OWN_R4=${D}(num "${D}(count_own_rules)")
OWN_R6=${D}(num "${D}(count_own_rules6)")
OWN_J4=${D}(num "${D}(count_own_jumps)")
OWN_J6=${D}(num "${D}(count_own_jumps6)")
CHAIN4=absent; iptables -w 5 -t mangle -S "${D}OUR" >/dev/null 2>&1 && CHAIN4=present
CHAIN6=absent; ip6tables -w 5 -t mangle -S "${D}OUR" >/dev/null 2>&1 && CHAIN6=present
echo "TTL_NET ifaces=${D}{IFACES:-none} carplay=${D}{CARPLAY:-none} routes=${D}{ROUTE_DEF:-none} uplink=${D}{UPLINK:-none} all_v4=${D}ALL_V4 all_v6=${D}ALL_V6 own_rules_v4=${D}OWN_R4 own_rules_v6=${D}OWN_R6 own_jumps_v4=${D}OWN_J4 own_jumps_v6=${D}OWN_J6"
echo "OUT_IFACE=${D}{UPLINK:-none} SRC_IP=${D}{SRC_IP:-none} CARPLAY_IF=${D}{CARPLAY:-none}"
if [ -z "${D}UPLINK" ]; then
  if command -v ip >/dev/null 2>&1; then echo ROUTE_SRC=none reason=no_default_route
  else echo ROUTE_SRC=none reason=no_ip_binary; fi
fi

# ---------- что мы вправе трогать ----------
# Наша цепочка есть — значит владение доказано её именем. Всё, что ВНЕ её, — чужое:
# даже если это `-o <uplink> -j TTL --ttl-set 65` (исторический формат прежних версий).
# Такие правила НЕ удаляются: значения 65 и совпадения интерфейса недостаточно, чтобы
# объявить правило своим, а удаление чужого ломает чужие функции.
report_foreign() {
  iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | sed 's/^/TTL_FOREIGN v4 spec="/;s/${D}/"/'
  ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | sed 's/^/TTL_FOREIGN v6 spec="/;s/${D}/"/'
  iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | grep -v -- "-j ${D}OUR" | grep -q . &&
    echo "TTL_LEGACY v4 action=keep reason=ownership_unproven note=\"правило с ttl-set вне цепочки ${D}OUR не считается нашим и не снимается\""
  ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | grep -v -- "-j ${D}OUR" | grep -q . &&
    echo "TTL_LEGACY v6 action=keep reason=ownership_unproven"
  true
}

# ---------- снятие ТОЛЬКО своего ----------
drop_own() {
  # 1) переходы на нашу цепочку (точный -D по спецификатору из -S)
  iptables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -- "-j ${D}OUR" | sed -n 's/^-A POSTROUTING //p' | while IFS= read -r spec; do
    [ -z "${D}spec" ] && continue
    iptables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null
    echo "TTL_DEL v4 spec=\"${D}spec\" rc=${D}?"
  done
  ip6tables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -- "-j ${D}OUR" | sed -n 's/^-A POSTROUTING //p' | while IFS= read -r spec; do
    [ -z "${D}spec" ] && continue
    ip6tables -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null
    echo "TTL_DEL v6 spec=\"${D}spec\" rc=${D}?"
  done
  # 2) только теперь чистим и удаляем саму цепочку (её уже никто не вызывает)
  iptables -w 5 -t mangle -F "${D}OUR" 2>/dev/null; iptables -w 5 -t mangle -X "${D}OUR" 2>/dev/null
  ip6tables -w 5 -t mangle -F "${D}OUR" 2>/dev/null; ip6tables -w 5 -t mangle -X "${D}OUR" 2>/dev/null
  LEFT_J=${D}(num "${D}(count_own_jumps)"); LEFT_R=${D}(num "${D}(count_own_rules)")
  LEFT_J6=${D}(num "${D}(count_own_jumps6)"); LEFT_R6=${D}(num "${D}(count_own_rules6)")
  if [ "${D}LEFT_J" = "0" ] && [ "${D}LEFT_R" = "0" ] && [ "${D}LEFT_J6" = "0" ] && [ "${D}LEFT_R6" = "0" ]; then
    echo "TTL_OWN removed=ok jumps_left=0 rules_left=0"
  else
    echo "TTL_OWN removed=partial jumps_left=${D}LEFT_J rules_left=${D}LEFT_R jumps_left_v6=${D}LEFT_J6 rules_left_v6=${D}LEFT_R6 reason=readback_not_clean"
  fi
}

VARIANT=none
if [ "${D}ENABLE" = "1" ]; then
  # ---------- включение: сначала своё снимаем, потом ставим заново (идемпотентно) ----------
  drop_own
  if [ -z "${D}UPLINK" ]; then
    echo "TTL_TRY variant=none scope=\"\" reason=no_uplink"
  else
    SCOPE="-o ${D}UPLINK"
    VARIANT=uplink
    echo "TTL_TRY variant=${D}VARIANT scope=\"${D}SCOPE\" carplay=${D}{CARPLAY:-none}"
    iptables -w 5 -t mangle -N "${D}OUR" 2>/dev/null
    iptables -w 5 -t mangle -F "${D}OUR" 2>/dev/null
    iptables -w 5 -t mangle -A "${D}OUR" -j TTL --ttl-set 65 2>&1
    echo "TTL_ADD v4 chain=${D}OUR scope=\"${D}SCOPE\" rc=${D}?"
    iptables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j "${D}OUR" 2>&1
    echo "TTL_ADD v4 jump scope=\"${D}SCOPE\" rc=${D}?"
    ip6tables -w 5 -t mangle -N "${D}OUR" 2>/dev/null
    ip6tables -w 5 -t mangle -F "${D}OUR" 2>/dev/null
    ip6tables -w 5 -t mangle -A "${D}OUR" -j HL --hl-set 65 2>&1
    echo "TTL_ADD v6 chain=${D}OUR scope=\"${D}SCOPE\" rc=${D}?"
    ip6tables -w 5 -t mangle -A POSTROUTING ${D}SCOPE -j "${D}OUR" 2>&1
    echo "TTL_ADD v6 jump scope=\"${D}SCOPE\" rc=${D}?"
    # Ровно один переход и одно правило: лишние наши копии снимаем (только свои).
    N=${D}(num "${D}(count_own_jumps)")
    while [ "${D}N" -gt 1 ] 2>/dev/null; do
      S=${D}(iptables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -- "-j ${D}OUR" | sed -n 's/^-A POSTROUTING //p' | head -1)
      [ -z "${D}S" ] && break
      iptables -w 5 -t mangle -D POSTROUTING ${D}S 2>/dev/null || break
      N=${D}((N-1))
    done
    M=${D}(num "${D}(count_own_jumps6)")
    while [ "${D}M" -gt 1 ] 2>/dev/null; do
      S=${D}(ip6tables -w 5 -t mangle -S POSTROUTING 2>/dev/null | grep -- "-j ${D}OUR" | sed -n 's/^-A POSTROUTING //p' | head -1)
      [ -z "${D}S" ] && break
      ip6tables -w 5 -t mangle -D POSTROUTING ${D}S 2>/dev/null || break
      M=${D}((M-1))
    done
    echo "TTL_ADD_COUNT jumps_v4=${D}(num "${D}(count_own_jumps)") jumps_v6=${D}(num "${D}(count_own_jumps6)") rules_v4=${D}(num "${D}(count_own_rules)") rules_v6=${D}(num "${D}(count_own_rules6)")"
  fi
else
  # ---------- выключение: снять ТОЛЬКО свои, чужое не трогать ----------
  VARIANT=none
  drop_own
fi

report_foreign

# ---------- счётчики и проверка ----------
OWN_V4=${D}(num "${D}(count_own_rules)"); OWN_V6=${D}(num "${D}(count_own_rules6)")
OWN_J4=${D}(num "${D}(count_own_jumps)"); OWN_J6=${D}(num "${D}(count_own_jumps6)")
ALL_V4=${D}(num "${D}(count_all_rules)"); ALL_V6=${D}(num "${D}(count_all_rules6)")
# ЧУЖОЕ = все правила с ttl-set/hl-set в POSTROUTING: наше собственное правило живёт ВНУТРИ
# цепочки ${D}OUR, снаружи его быть не может. Прежний расчёт «все минус свои» давал 0 при
# реально существующем чужом правиле (своё внутри цепочки вычиталось из чужого в POSTROUTING),
# и OFF объявлял result=OK, хотя чужие правила на машине остались.
FOREIGN_V4=${D}ALL_V4
FOREIGN_V6=${D}ALL_V6
TTL_V4=${D}OWN_V4
TTL_V6=${D}OWN_V6
VERIFY_V4=FAIL
VERIFY_V6=FAIL
if [ "${D}ENABLE" = "1" ]; then
  # Пересчёт ПОСЛЕ изменений: ранее снятые значения относились к состоянию ДО ON и давали
  # ложный FAIL при фактически стоящем правиле.
  if [ "${D}OWN_V4" -ge 1 ] 2>/dev/null && [ "${D}OWN_J4" -ge 1 ] 2>/dev/null; then VERIFY_V4=OK; fi
  if [ "${D}OWN_V6" -ge 1 ] 2>/dev/null && [ "${D}OWN_J6" -ge 1 ] 2>/dev/null; then VERIFY_V6=OK; fi
else
  [ "${D}OWN_V4" = "0" ] && [ "${D}OWN_J4" = "0" ] && VERIFY_V4=OFF_OK
  [ "${D}OWN_V6" = "0" ] && [ "${D}OWN_J6" = "0" ] && VERIFY_V6=OFF_OK
  if [ "${D}VERIFY_V4" = "OFF_OK" ] && [ "${D}VERIFY_V6" = "OFF_OK" ] && [ "${D}FOREIGN_V4" = "0" ] && [ "${D}FOREIGN_V6" = "0" ]; then
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=OK"
  elif [ "${D}VERIFY_V4" = "OFF_OK" ] && [ "${D}VERIFY_V6" = "OFF_OK" ]; then
    # Своё снято, но на машине есть ЧУЖИЕ правила ttl-set: «вернулось к стоку» заявлять нельзя.
    echo "TTL_OFF own_v4=0 own_v6=0 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=PARTIAL reason=foreign_rules_remain"
  else
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=FAILED reason=own_rules_left"
  fi
fi
echo TTL_V4=${D}TTL_V4 TTL_V6=${D}TTL_V6 OWN_V4=${D}OWN_V4 OWN_V6=${D}OWN_V6 OWN_J4=${D}OWN_J4 OWN_J6=${D}OWN_J6 FOREIGN_V4=${D}FOREIGN_V4 FOREIGN_V6=${D}FOREIGN_V6 VERIFY_V4=${D}VERIFY_V4 VERIFY_V6=${D}VERIFY_V6

# скоуп проверяется по НАШЕМУ переходу, а не по «первому правилу с ttl-set» (там может быть чужое)
OWN_JUMP=${D}(iptables -t mangle -S POSTROUTING 2>/dev/null | grep -- "-j ${D}OUR" | head -1)
SCOPE_UPLINK_ONLY=no
SCOPED=no
if [ -n "${D}UPLINK" ]; then
  echo "${D}OWN_JUMP" | grep -qE -- "-o ${D}{UPLINK} " && SCOPE_UPLINK_ONLY=yes
fi
[ -n "${D}OWN_JUMP" ] && SCOPED=yes
if [ "${D}ENABLE" = "0" ] && [ "${D}OWN_J4" = "0" ] && [ "${D}OWN_R4" = "0" ]; then SCOPE_UPLINK_ONLY=yes; SCOPED=yes; fi
if [ "${D}ENABLE" = "1" ] && [ -z "${D}UPLINK" ]; then
  echo "TTL_SCOPE result=failed scoped=no uplink_only=no uplink=none rule=\"${D}{OWN_JUMP:-none}\" reason=no_uplink"
else
  echo "TTL_SCOPE result=${D}([ "${D}SCOPED" = "yes" ] && echo scoped || echo unscoped) scoped=${D}SCOPED uplink_only=${D}SCOPE_UPLINK_ONLY uplink=${D}{UPLINK:-none} rule=\"${D}{OWN_JUMP:-none}\""
fi

EXPECTED=65
RULE_LINE=${D}(iptables -t mangle -S "${D}OUR" 2>/dev/null | grep 'ttl-set' | head -1)
RULE_TTL=${D}(echo "${D}RULE_LINE" | grep -oE -- '--ttl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
RULE_HL=${D}(ip6tables -t mangle -S "${D}OUR" 2>/dev/null | grep 'hl-set' | head -1 | grep -oE -- '--hl-set[[:space:]]+[0-9]+' | awk '{print ${D}2}')
BEFORE_N=${D}RULE_TTL; [ -z "${D}BEFORE_N" ] && BEFORE_N=none
AFTER_N=${D}RULE_TTL; [ -z "${D}AFTER_N" ] && AFTER_N=none
if [ "${D}ENABLE" = "1" ] && [ "${D}OWN_V4" -ge 1 ] 2>/dev/null && [ "${D}OWN_J4" -ge 1 ] 2>/dev/null && [ "${D}SCOPE_UPLINK_ONLY" = "yes" ]; then VERDICT=SCOPED_OK
elif [ "${D}ENABLE" = "0" ] && [ "${D}OWN_V4" = "0" ] && [ "${D}OWN_J4" = "0" ] && [ "${D}OWN_V6" = "0" ] && [ "${D}OWN_J6" = "0" ] && [ "${D}FOREIGN_V4" = "0" ] && [ "${D}FOREIGN_V6" = "0" ]; then VERDICT=OFF_OK
elif [ "${D}ENABLE" = "0" ] && [ "${D}OWN_V4" = "0" ] && [ "${D}OWN_J4" = "0" ]; then VERDICT=OFF_OWN_FOREIGN_LEFT
else VERDICT=CHECK_OWNER; fi
echo "TTL_CHECK: before=${D}BEFORE_N after=${D}AFTER_N rule_ttl=${D}{RULE_TTL:-none} real=UNKNOWN expected=${D}EXPECTED src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}{UPLINK:-none} method=counters reason=wire_not_measured_scoped_rules hl_wire=${D}{RULE_HL:-} ARROW ${D}VERDICT"
echo "TTL_RESULT rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} verify_v4=${D}VERIFY_V4 verify_v6=${D}VERIFY_V6 wire_v4=not_measured enable=${D}ENABLE scoped=${D}SCOPED uplink=${D}{UPLINK:-none} variant=${D}VARIANT own_chain=${D}OUR foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6"
echo '=== TTL_SCRIPT end ==='
""".trimIndent().replace("ARROW", "\u2192").replace("@ENABLE_VALUE@", enableBit)
            .replace("@OUR_CHAIN@", OUR_CHAIN)
    }

    /** Скрипт стартовой санации (boot / приложение стартовало): снять только своё. */
    fun sanitizeScript(): String = build(false)
}
