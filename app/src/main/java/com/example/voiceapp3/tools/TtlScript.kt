package com.example.voiceapp3.tools

/**
 * Shell-скрипт TTL/HL — iter49 §5: **доказанное владение, а не совпадение подстроки**.
 *
 * Что было не так к iter48 (подтверждено кодом `46c30cf` и аудитом):
 *  1. переходы искались `grep -- "-j VAPP_TTL"`: эта подстрока совпадает и с `-j VAPP_TTL_OTHER`,
 *     поэтому `-D`/`-F`/`-X` могли затронуть ЧУЖУЮ цепочку с похожим именем;
 *  2. само имя `VAPP_TTL` считалось доказательством: существующая ОДНОИМЁННАЯ чужая цепочка
 *     молча очищалась `-F`, а затем удалялась `-X`;
 *  3. `iptables -S` вызывался с `2>/dev/null`, пустой результат превращался в ноль: отказ
 *     чтения (нет бинарника, permission, xtables lock) выглядел как «правил нет», и OFF
 *     заявлялся подтверждённым;
 *  4. проверка ON опиралась на «счётчик ≥ 1» и «первый найденный переход», без проверки точной
 *     спецификации и дубликатов.
 *
 * Как сделано теперь:
 *  • владение = (а) цепочка с нашим именем, содержимое которой ТОЧНО равно одному нашему
 *    правилу (`-j TTL --ttl-set 65` для v4, `-j HL --hl-set 65` для v6), и (б) переходы в
 *    POSTROUTING, у которых target — ТОЧНО наше имя (сравнение токена после `-j`, не подстрока);
 *  • цепочка с другим содержимым — `collision`: НЕ чистим, НЕ удаляем, НЕ дополняем; семья
 *    пропускается с диагностикой (fail-safe: лучше не применить TTL, чем сломать чужое);
 *  • пустая цепочка с нашим именем — `empty`: происхождение не доказано (мог оставить и чужой
 *    код), поэтому тоже `action=keep`, семья пропускается;
 *  • чтение различается: `READ=ok|failed`. При `failed` состояние НЕ называется `absent`, OFF
 *    получает `result=UNKNOWN`, а не «OK»;
 *  • ON подтверждается только при точном совпадении: ровно одна наша цепочка с ровно одним
 *    правилом, ровно один переход, и его спецификация РАВНА `-o <uplink> -j <OUR>`; дубликаты,
 *    нескоупленные и лишние переходы дают `CHECK_OWNER`, а не «успех»;
 *  • чужие правила (`ttl-set`/`hl-set` вне нашей цепочки) не удаляются никогда, только
 *    перечисляются (`TTL_FOREIGN`, `TTL_LEGACY … action=keep reason=ownership_unproven`);
 *  • IPv6: если `ip6tables` недоступен, это `v6=unsupported`, а не «OK» и не «провал».
 *
 * Чего скрипт НЕ делает и не заявляет:
 *  • TTL на проводе не измеряется (`wire=not_measured`): правило — не измерение пакетов;
 *  • Wi-Fi/CarPlay не переключаются, глобальное правило не ставится;
 *  • неизвестная цепочка/чужой источник не присваиваются даже при совпадении значения 65.
 */
internal object TtlScript {
    private const val D = "$"

    /** Имя нашей цепочки. Оно же — часть метки владения (полное доказательство — содержимое). */
    internal const val OUR_CHAIN = "VAPP_TTL"

    /** Ожидаемое содержимое нашей цепочки (тело правила после `-A <OUR> `). */
    internal const val RULE_V4 = "-j TTL --ttl-set 65"
    internal const val RULE_V6 = "-j HL --hl-set 65"

    fun build(enable: Boolean): String {
        val enableBit = if (enable) "1" else "0"
        return """
set +e
ENABLE=@ENABLE_VALUE@
OUR=@OUR_CHAIN@
RULE4='@RULE_V4@'
RULE6='@RULE_V6@'
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
EXPECTED_JUMP=""
[ -n "${D}UPLINK" ] && EXPECTED_JUMP="-o ${D}UPLINK -j ${D}OUR"

V6_SUPPORTED=0
command -v ip6tables >/dev/null 2>&1 && V6_SUPPORTED=1

# ---------- владение: точные сравнения, а не подстроки ----------
# Спецификации переходов POSTROUTING, у которых target — ТОЧНО наше имя.
# Сравнивается токен после "-j": "-j VAPP_TTL_OTHER" НЕ совпадает.
jump_specs() {
  ${D}1 -t mangle -S POSTROUTING 2>/dev/null | awk -v our="${D}OUR" '
    /^-A POSTROUTING / {
      spec = substr(${D}0, 16)
      n = split(spec, a, " ")
      for (i = 1; i < n; i++) if (a[i] == "-j" && a[i+1] == our) { print spec; next }
    }'
}
jump_count() {
  if ! ${D}1 -t mangle -S POSTROUTING >/dev/null 2>&1; then echo -1; return; fi
  jump_specs ${D}1 | wc -l | tr -d ' '
}

# Состояние нашей цепочки: absent | empty | ours | collision | unknown.
# unknown — прочитать нельзя (нет бинарника/прав/lock): это НЕ «правил нет».
chain_state() {
  tool=${D}1; want=${D}2
  if ! out=${D}(${D}tool -t mangle -S "${D}OUR" 2>/dev/null); then
    if ${D}tool -t mangle -S >/dev/null 2>&1; then echo absent; else echo unknown; fi
    return
  fi
  n=${D}(printf '%s\n' "${D}out" | grep -c '^-A ')
  if [ "${D}n" = "0" ]; then echo empty; return; fi
  if [ "${D}n" = "1" ]; then
    body=${D}(printf '%s\n' "${D}out" | grep '^-A ' | sed "s/^-A ${D}OUR //")
    if [ "${D}body" = "${D}want" ]; then echo ours; return; fi
  fi
  echo collision
}

# Сколько правил ttl-set/hl-set в POSTROUTING (чужое + возможное наше вне цепочки).
# При отказе чтения — -1 (неизвестно), а не 0.
count_ttl_rules() {
  tool=${D}1; pat=${D}2
  if ! ${D}tool -t mangle -S POSTROUTING >/dev/null 2>&1; then echo -1; return; fi
  ${D}tool -t mangle -S POSTROUTING 2>/dev/null | grep -c "${D}pat"
}

# ---------- отчёт о чужом (read-only) ----------
report_foreign() {
  if iptables -t mangle -S POSTROUTING >/dev/null 2>&1; then
    iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | sed 's/^/TTL_FOREIGN v4 spec="/;s/${D}/"/'
    if [ "${D}(chain_state iptables "${D}RULE4")" != "ours" ]; then
      iptables -t mangle -S POSTROUTING 2>/dev/null | grep 'ttl-set' | grep -q . && \
        echo "TTL_LEGACY v4 action=keep reason=ownership_unproven note=\"правило с ttl-set вне нашей доказанной цепочки ${D}OUR не снимается\""
    fi
  else
    echo "TTL_FOREIGN v4 read=failed reason=iptables_S_failed"
  fi
  if [ "${D}V6_SUPPORTED" = "1" ]; then
    if ip6tables -t mangle -S POSTROUTING >/dev/null 2>&1; then
      ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | sed 's/^/TTL_FOREIGN v6 spec="/;s/${D}/"/'
      if [ "${D}(chain_state ip6tables "${D}RULE6")" != "ours" ]; then
        ip6tables -t mangle -S POSTROUTING 2>/dev/null | grep 'hl-set' | grep -q . && \
          echo "TTL_LEGACY v6 action=keep reason=ownership_unproven"
      fi
    else
      echo "TTL_FOREIGN v6 read=failed reason=ip6tables_S_failed"
    fi
  fi
  true
}

# ---------- снятие ТОЛЬКО доказанно своего ----------
DROP_RESULT_V4=none
DROP_RESULT_V6=none
drop_family() {
  tool=${D}1; want=${D}2; fam=${D}3
  st=${D}(chain_state "${D}tool" "${D}want")
  case "${D}st" in
    absent)  echo "TTL_OWN family=${D}fam state=absent action=none"; echo "${D}fam:none"; return 0 ;;
    empty)   echo "TTL_OWN family=${D}fam state=empty action=keep reason=empty_chain_origin_unproven"; echo "${D}fam:skip"; return 0 ;;
    collision) echo "TTL_OWN family=${D}fam state=collision action=keep reason=chain_content_not_ours"; echo "${D}fam:collision"; return 0 ;;
    unknown) echo "TTL_OWN family=${D}fam state=unknown action=skip reason=read_failed"; echo "${D}fam:unknown"; return 0 ;;
  esac
  # состояние ours: снимаем ИМЕННО наши переходы (точное сравнение target), затем цепочку
  # Список спецификаций разбирается ПОСТРОЧНО: спецификация содержит пробелы
  # (`-o wlan0 -j VAPP_TTL`), поэтому обычный `for ${D}spec in ${D}(...)` разбил бы её на слова
  # и попытался бы удалить не то правило. IFS меняется только на время цикла.
  del_ok=0; del_fail=0
  NL=${D}(printf '\nx'); NL=${D}{NL%x}
  OLD_IFS=${D}IFS; IFS=${D}NL
  for spec in ${D}(jump_specs "${D}tool"); do
    [ -z "${D}spec" ] && continue
    # Спецификация с кавычками/экранированием означает сложные match-условия. Такой переход
    # создали не мы (наш всегда ровно "-o <uplink> -j <OUR>"), а разобрать его без риска
    # удалить не то правило нельзя — отказ безопаснее (как требует §5.1).
    Q1="'"; Q2='"'; BS='\'
    case "${D}spec" in
      *${D}{Q1}*|*${D}{Q2}*|*${D}{BS}*)
        echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=refused reason=quoted_spec_needs_owner"
        del_fail=${D}((del_fail+1)); continue ;;
    esac
    if ${D}tool -w 5 -t mangle -D POSTROUTING ${D}spec 2>/dev/null; then
      del_ok=${D}((del_ok+1)); echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=0"
    else
      del_fail=${D}((del_fail+1)); echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=nonzero"
    fi
  done
  IFS=${D}OLD_IFS
  if [ "${D}del_fail" != "0" ]; then
    # Часть переходов снять нельзя (сложная спецификация/отказ). Нашу цепочку НЕ чистим и
    # НЕ удаляем: иначе получится «висячая» цепочка без правила, а TTL останется применённым
    # через оставшийся переход — лучше честное partial, чем полуснятое состояние.
    echo "TTL_OWN family=${D}fam state=partial removed=partial jumps_failed=${D}del_fail reason=jumps_not_removed"
    echo "${D}fam:partial"; return 0
  fi
  ${D}tool -w 5 -t mangle -F "${D}OUR" 2>/dev/null; fl_rc=${D}?
  ${D}tool -w 5 -t mangle -X "${D}OUR" 2>/dev/null; x_rc=${D}?
  echo "TTL_DEL family=${D}fam chain_flush_rc=${D}fl_rc chain_del_rc=${D}x_rc jumps_deleted=${D}del_ok"
  if [ "${D}fam" = "v4" ]; then TOUCHED_v4=yes; else TOUCHED_v6=yes; fi
  st2=${D}(chain_state "${D}tool" "${D}want")
  jc2=${D}(jump_count "${D}tool")
  if [ "${D}st2" = "unknown" ]; then
    echo "TTL_OWN family=${D}fam state=unknown removed=unproven reason=readback_failed"; echo "${D}fam:unproven"; return 0
  fi
  if [ "${D}st2" = "absent" ] && [ "${D}jc2" = "0" ]; then
    echo "TTL_OWN family=${D}fam state=absent removed=ok jumps_left=0 rules_left=0"; echo "${D}fam:ok"
  else
    echo "TTL_OWN family=${D}fam state=${D}st2 removed=partial jumps_left=${D}jc2"; echo "${D}fam:partial"
  fi
}

CS_V4=${D}(chain_state iptables "${D}RULE4")
CS_V6=none
[ "${D}V6_SUPPORTED" = "1" ] && CS_V6=${D}(chain_state ip6tables "${D}RULE6")
READ_V4=ok; [ "${D}CS_V4" = "unknown" ] && READ_V4=failed
READ_V6=ok; [ "${D}CS_V6" = "unknown" ] && READ_V6=failed
echo "TTL_OWNERSHIP v4=${D}CS_V4 v6=${D}CS_V6 v6_supported=${D}V6_SUPPORTED read_v4=${D}READ_V4 read_v6=${D}READ_V6 own_chain=${D}OUR"

VARIANT=none
ROLLED_BACK=no
TOUCHED_v4=no
TOUCHED_v6=no
if [ "${D}ENABLE" = "1" ]; then
  if [ -z "${D}UPLINK" ]; then
    echo "TTL_TRY variant=none scope=\"\" reason=no_uplink"
  else
    echo "TTL_TRY variant=uplink scope=\"-o ${D}UPLINK\" carplay=${D}{CARPLAY:-none}"
    # Если ХОТЬ ОДНА семья в неизвестном или не нашем состоянии — не применяем НИЧЕГО:
    # половинчатое состояние (только v6) выглядит как «TTL работает», хотя v4 не наш.
    NOT_OWNED=""
    case "${D}CS_V4" in collision|empty|unknown) NOT_OWNED="v4=${D}CS_V4";; esac
    if [ "${D}V6_SUPPORTED" = "1" ]; then
      case "${D}CS_V6" in collision|empty|unknown) NOT_OWNED="${D}{NOT_OWNED} v6=${D}CS_V6}";; esac
    fi
    if [ -n "${D}{NOT_OWNED// /}" ]; then
      echo "TTL_TRY action=skip_all reason=family_not_owned ${D}{NOT_OWNED# }"
    else
      VARIANT=uplink
      # --- v4 ---
      drop_out4=${D}(drop_family iptables "${D}RULE4" v4); echo "${D}drop_out4"
      iptables -w 5 -t mangle -N "${D}OUR" 2>/dev/null
      iptables -w 5 -t mangle -A "${D}OUR" -j TTL --ttl-set 65 2>&1
      rc_a=${D}?; echo "TTL_ADD family=v4 chain=${D}OUR rc=${D}rc_a"
      iptables -w 5 -t mangle -A POSTROUTING -o "${D}UPLINK" -j "${D}OUR" 2>&1
      rc_j=${D}?; echo "TTL_ADD family=v4 jump scope=\"-o ${D}UPLINK\" rc=${D}rc_j"
      TOUCHED_v4=yes
      # --- v6 ---
      if [ "${D}V6_SUPPORTED" = "1" ]; then
        drop_out6=${D}(drop_family ip6tables "${D}RULE6" v6); echo "${D}drop_out6"
        ip6tables -w 5 -t mangle -N "${D}OUR" 2>/dev/null
        ip6tables -w 5 -t mangle -A "${D}OUR" -j HL --hl-set 65 2>&1
        rc_a6=${D}?; echo "TTL_ADD family=v6 chain=${D}OUR rc=${D}rc_a6"
        ip6tables -w 5 -t mangle -A POSTROUTING -o "${D}UPLINK" -j "${D}OUR" 2>&1
        rc_j6=${D}?; echo "TTL_ADD family=v6 jump scope=\"-o ${D}UPLINK\" rc=${D}rc_j6"
        TOUCHED_v6=yes
      fi
      # Половинчатое включение не оставляем: если хоть одна семья не встала, снимаем обе.
      # Порядок важен: сначала ТОЧНЫЙ переход, затем -F, затем -X (иначе -X вернёт "Too many links").
      need_rb=no
      [ "${D}rc_a" != "0" ] && need_rb=yes
      [ "${D}rc_j" != "0" ] && need_rb=yes
      if [ "${D}V6_SUPPORTED" = "1" ]; then
        [ "${D}rc_a6" != "0" ] && need_rb=yes
        [ "${D}rc_j6" != "0" ] && need_rb=yes
      fi
      if [ "${D}need_rb" = "yes" ]; then
        echo "TTL_ROLLBACK reason=incomplete rc4=${D}rc_a/${D}rc_j rc6=${D}{rc_a6:-n}/ ${D}{rc_j6:-n}"
        if [ "${D}TOUCHED_v6" = "yes" ]; then
          ip6tables -w 5 -t mangle -D POSTROUTING -o "${D}UPLINK" -j "${D}OUR" >/dev/null 2>&1; rj6=${D}?
          ip6tables -w 5 -t mangle -F "${D}OUR" >/dev/null 2>&1; rf6=${D}?
          ip6tables -w 5 -t mangle -X "${D}OUR" >/dev/null 2>&1; rx6=${D}?
          echo "TTL_ROLLBACK family=v6 jump_del_rc=${D}rj6 chain_flush_rc=${D}rf6 chain_del_rc=${D}rx6"
          TOUCHED_v6=no
        fi
        if [ "${D}TOUCHED_v4" = "yes" ]; then
          iptables -w 5 -t mangle -D POSTROUTING -o "${D}UPLINK" -j "${D}OUR" >/dev/null 2>&1; rj4=${D}?
          iptables -w 5 -t mangle -F "${D}OUR" >/dev/null 2>&1; rf4=${D}?
          iptables -w 5 -t mangle -X "${D}OUR" >/dev/null 2>&1; rx4=${D}?
          echo "TTL_ROLLBACK family=v4 jump_del_rc=${D}rj4 chain_flush_rc=${D}rf4 chain_del_rc=${D}rx4"
          TOUCHED_v4=no
        fi
        VARIANT=none
        ROLLED_BACK=yes
      fi
      # дедупликация: лишние НАШИ переходы снимаем (точное сравнение target)
      for fam_tool in "v4 iptables" "v6 ip6tables"; do
        set -- ${D}fam_tool
        fam=${D}1; tool=${D}2
        [ "${D}fam" = "v6" ] && [ "${D}V6_SUPPORTED" != "1" ] && continue
        n=${D}(jump_count "${D}tool")
        while [ "${D}n" -gt 1 ] 2>/dev/null; do
          s=${D}(jump_specs "${D}tool" | head -1)
          [ -z "${D}s" ] && break
          ${D}tool -w 5 -t mangle -D POSTROUTING ${D}s 2>/dev/null || break
          n=${D}(jump_count "${D}tool")
        done
      done
    fi
  fi
else
  VARIANT=none
  drop_out4=${D}(drop_family iptables "${D}RULE4" v4); echo "${D}drop_out4"
  if [ "${D}V6_SUPPORTED" = "1" ]; then
    drop_out6=${D}(drop_family ip6tables "${D}RULE6" v6); echo "${D}drop_out6"
  fi
fi

report_foreign

# ---------- итоговое состояние (перечитывается ПОСЛЕ действий) ----------
CS2_V4=${D}(chain_state iptables "${D}RULE4")
CS2_V6=none; [ "${D}V6_SUPPORTED" = "1" ] && CS2_V6=${D}(chain_state ip6tables "${D}RULE6")
J4=${D}(jump_count iptables); J6=0; [ "${D}V6_SUPPORTED" = "1" ] && J6=${D}(jump_count ip6tables)
JS4_RAW=${D}(jump_specs iptables | head -1)
JS6_RAW=${D}(jump_specs ip6tables | head -1)
ALL_V4=${D}(count_ttl_rules iptables 'ttl-set')
ALL_V6=0; [ "${D}V6_SUPPORTED" = "1" ] && ALL_V6=${D}(count_ttl_rules ip6tables 'hl-set')
FOREIGN_V4=${D}ALL_V4
FOREIGN_V6=${D}ALL_V6
OWN_V4=0; [ "${D}CS2_V4" = "ours" ] && OWN_V4=1; [ "${D}CS2_V4" = "unknown" ] && OWN_V4=-1
OWN_V6=0; [ "${D}CS2_V6" = "ours" ] && OWN_V6=1; [ "${D}CS2_V6" = "unknown" ] && OWN_V6=-1
RULE_TTL=-1; [ "${D}OWN_V4" = "1" ] && RULE_TTL=65
RULE_HL=-1; [ "${D}OWN_V6" = "1" ] && RULE_HL=65

# Точная проверка ON: одна наша цепочка, одно правило, один переход, его спецификация РАВНА
# "-o <uplink> -j <OUR>". Никаких «счётчик ≥ 1» и «первый похожий переход».
VERIFY_V4=FAIL
if [ "${D}CS2_V4" = "unknown" ]; then VERIFY_V4=UNKNOWN
elif [ "${D}CS2_V4" = "collision" ]; then VERIFY_V4=COLLISION
elif [ "${D}CS2_V4" = "empty" ]; then
  if [ "${D}TOUCHED_v4" = "yes" ]; then VERIFY_V4=FAIL; else VERIFY_V4=COLLISION; fi
elif [ "${D}ENABLE" = "1" ] && [ "${D}CS2_V4" = "absent" ] && [ "${D}TOUCHED_v4" != "yes" ]; then VERIFY_V4=NOT_APPLIED
elif [ "${D}ENABLE" = "1" ]; then
  if [ "${D}CS2_V4" = "ours" ] && [ "${D}J4" = "1" ] && [ "${D}JS4_RAW" = "${D}EXPECTED_JUMP" ]; then VERIFY_V4=OK; fi
else
  if [ "${D}CS2_V4" = "absent" ] && [ "${D}J4" = "0" ]; then VERIFY_V4=OFF_OK; fi
fi
VERIFY_V6=SKIPPED
if [ "${D}V6_SUPPORTED" = "1" ]; then
  VERIFY_V6=FAIL
  if [ "${D}CS2_V6" = "unknown" ]; then VERIFY_V6=UNKNOWN
  elif [ "${D}CS2_V6" = "collision" ]; then VERIFY_V6=COLLISION
  elif [ "${D}CS2_V6" = "empty" ]; then
    if [ "${D}TOUCHED_v6" = "yes" ]; then VERIFY_V6=FAIL; else VERIFY_V6=COLLISION; fi
  elif [ "${D}ENABLE" = "1" ] && [ "${D}CS2_V6" = "absent" ] && [ "${D}TOUCHED_v6" != "yes" ]; then VERIFY_V6=NOT_APPLIED
  elif [ "${D}ENABLE" = "1" ]; then
    if [ "${D}CS2_V6" = "ours" ] && [ "${D}J6" = "1" ] && [ "${D}JS6_RAW" = "${D}EXPECTED_JUMP" ]; then VERIFY_V6=OK; fi
  else
    if [ "${D}CS2_V6" = "absent" ] && [ "${D}J6" = "0" ]; then VERIFY_V6=OFF_OK; fi
  fi
fi

if [ "${D}ENABLE" = "0" ]; then
  if [ "${D}VERIFY_V4" = "UNKNOWN" ] || [ "${D}VERIFY_V6" = "UNKNOWN" ]; then
    # Отказ чтения ≠ «правил нет»: снятие НЕ подтверждено.
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 result=UNKNOWN reason=read_failed read_v4=${D}READ_V4 read_v6=${D}{READ_V6}"
  elif [ "${D}VERIFY_V4" = "OFF_OK" ] && { [ "${D}VERIFY_V6" = "OFF_OK" ] || [ "${D}VERIFY_V6" = "SKIPPED" ]; }; then
    if [ "${D}FOREIGN_V4" = "0" ] && [ "${D}FOREIGN_V6" = "0" ]; then
      echo "TTL_OFF own_v4=0 own_v6=0 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=OK v6=${D}{VERIFY_V6}"
    else
      echo "TTL_OFF own_v4=0 own_v6=0 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=PARTIAL reason=foreign_rules_remain"
    fi
  elif [ "${D}VERIFY_V4" = "COLLISION" ] || [ "${D}VERIFY_V6" = "COLLISION" ]; then
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 result=PARTIAL reason=foreign_chain_collision action=keep"
  else
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=FAILED reason=own_rules_left"
  fi
fi

echo "TTL_V4=${D}OWN_V4 TTL_V6=${D}OWN_V6 OWN_V4=${D}OWN_V4 OWN_V6=${D}OWN_V6 OWN_J4=${D}J4 OWN_J6=${D}J6 FOREIGN_V4=${D}FOREIGN_V4 FOREIGN_V6=${D}FOREIGN_V6 VERIFY_V4=${D}VERIFY_V4 VERIFY_V6=${D}VERIFY_V6 CHAIN4=${D}CS2_V4 CHAIN6=${D}CS2_V6 READ_V4=${D}READ_V4 READ_V6=${D}READ_V6"

SCOPE_UPLINK_ONLY=no
SCOPED=no
if [ "${D}J4" = "1" ] && [ -n "${D}EXPECTED_JUMP" ] && [ "${D}JS4_RAW" = "${D}EXPECTED_JUMP" ]; then SCOPE_UPLINK_ONLY=yes; fi
[ "${D}J4" = "1" ] && SCOPED=yes
if [ "${D}ENABLE" = "1" ] && [ -z "${D}UPLINK" ]; then
  echo "TTL_SCOPE result=failed scoped=no uplink_only=no uplink=none rule=\"${D}{JS4_RAW:-none}\" reason=no_uplink"
else
  echo "TTL_SCOPE result=${D}([ "${D}SCOPED" = "yes" ] && echo scoped || echo unscoped) scoped=${D}SCOPED uplink_only=${D}SCOPE_UPLINK_ONLY uplink=${D}{UPLINK:-none} rule=\"${D}{JS4_RAW:-none}\""
fi

if [ "${D}ENABLE" = "1" ]; then
  if [ "${D}VERIFY_V4" = "UNKNOWN" ] || [ "${D}VERIFY_V6" = "UNKNOWN" ]; then VERDICT=READ_UNKNOWN
  elif [ "${D}VERIFY_V4" = "COLLISION" ] || [ "${D}VERIFY_V6" = "COLLISION" ]; then VERDICT=CHAIN_COLLISION
  elif [ "${D}VERIFY_V4" = "OK" ] && [ "${D}VERIFY_V6" = "OK" ] && [ "${D}SCOPE_UPLINK_ONLY" = "yes" ]; then VERDICT=SCOPED_OK
  elif [ "${D}VERIFY_V4" = "OK" ] && [ "${D}VERIFY_V6" = "SKIPPED" ] && [ "${D}SCOPE_UPLINK_ONLY" = "yes" ]; then VERDICT=SCOPED_V4_ONLY
  elif [ "${D}ROLLED_BACK" = "yes" ]; then VERDICT=APPLY_FAILED
  elif [ "${D}VERIFY_V4" = "NOT_APPLIED" ] || [ "${D}VERIFY_V6" = "NOT_APPLIED" ]; then VERDICT=NOT_APPLIED
  else VERDICT=CHECK_OWNER; fi
else
  if [ "${D}VERIFY_V4" = "UNKNOWN" ] || [ "${D}VERIFY_V6" = "UNKNOWN" ]; then VERDICT=READ_UNKNOWN
  elif [ "${D}VERIFY_V4" = "OFF_OK" ] && { [ "${D}VERIFY_V6" = "OFF_OK" ] || [ "${D}VERIFY_V6" = "SKIPPED" ]; }; then
    if [ "${D}FOREIGN_V4" = "0" ] && [ "${D}FOREIGN_V6" = "0" ]; then VERDICT=OFF_OK; else VERDICT=OFF_OWN_FOREIGN_LEFT; fi
  elif [ "${D}VERIFY_V4" = "COLLISION" ] || [ "${D}VERIFY_V6" = "COLLISION" ]; then VERDICT=CHAIN_COLLISION
  else VERDICT=CHECK_OWNER; fi
fi

echo "TTL_CHECK: rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} real=UNKNOWN expected=65 src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}{UPLINK:-none} method=rules_not_wire reason=wire_not_measured jumps_v4=${D}J4 jumps_v6=${D}J6 chain_v4=${D}CS2_V4 chain_v6=${D}CS2_V6 ARROW ${D}VERDICT"
echo "TTL_RESULT rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} verify_v4=${D}VERIFY_V4 verify_v6=${D}VERIFY_V6 wire_v4=not_measured enable=${D}ENABLE scoped=${D}SCOPED uplink=${D}{UPLINK:-none} variant=${D}VARIANT own_chain=${D}OUR foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 chain_v4=${D}CS2_V4 chain_v6=${D}CS2_V6 read_v4=${D}READ_V4 read_v6=${D}{READ_V6}"
echo '=== TTL_SCRIPT end ==='
""".trimIndent()
            .replace("ARROW", "\u2192")
            .replace("@ENABLE_VALUE@", enableBit)
            .replace("@OUR_CHAIN@", OUR_CHAIN)
            .replace("@RULE_V4@", RULE_V4)
            .replace("@RULE_V6@", RULE_V6)
    }

    /** Скрипт стартовой санации (boot / приложение стартовало): снять только своё. */
    fun sanitizeScript(): String = build(false)
}
