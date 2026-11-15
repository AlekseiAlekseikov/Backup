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

    /**
     * Имя цепи ПО УМОЛЧАНИЮ. Production подставляет своё уникальное имя установки
     * ([TtlOwnership.chainFor]); перегрузка со значением по умолчанию нужна тестам и
     * совместимости.
     */
    internal const val OUR_CHAIN = "VAPP_TTL"

    /** Историческое имя цепи до iter54: только для отчёта, никогда не изменяется. */
    internal const val LEGACY_CHAIN = "VAPP_TTL"

    /** Ожидаемое содержимое нашей цепочки (тело правила после `-A <OUR> `). */
    internal const val RULE_V4 = "-j TTL --ttl-set 65"
    internal const val RULE_V6 = "-j HL --hl-set 65"

    fun build(
        enable: Boolean,
        gen: String = "default",
        ownChain: String = OUR_CHAIN,
        legacyChain: String = LEGACY_CHAIN,
        /**
         * iter54 §6.1: реестр собственной идентичности unknown ⇒ таблица НЕ изменяется вообще
         * (ни ON, ни OFF). Это не «выключение функции навсегда»: причина и путь восстановления
         * видны в отчёте, а выбор владельца остаётся сохранённым.
         */
        registryUnknown: Boolean = false
    ): String {
        val enableBit = if (registryUnknown) "2" else if (enable) "1" else "0"
        // `gen` — токен УСТАНОВКИ (не константа): он делает владение проверяемым настолько,
        // насколько это возможно для iptables — метка в самом правиле. Имя цепи тоже уникально
        // для установки, поэтому «одноимённая чужая цепь» перестаёт быть неразрешимой коллизией.
        val safeGen = gen.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32)
            .ifEmpty { "default" }
        // Имя цепи iptables ограничено 28 символами: гарантируем это здесь.
        val safeChain = ownChain.filter { it.isLetterOrDigit() || it == '_' }.take(28)
            .ifEmpty { OUR_CHAIN }
        val safeLegacy = legacyChain.filter { it.isLetterOrDigit() || it == '_' }.take(28)
            .ifEmpty { LEGACY_CHAIN }
        return """
set +e
ENABLE=@ENABLE_VALUE@
REGISTRY_UNKNOWN=@REGISTRY_UNKNOWN@
OUR=@OUR_CHAIN@
# Историческое имя цепи (только для отчёта; никогда не изменяется). iter54 §6.1.
LEGACY=@LEGACY_CHAIN@
# Метка владения: поколение операции приходит из production (стабильно в пределах применения).
OWN_GEN=@OWN_GEN@
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

# iter53 §8.1 — ДОКАЗУЕМОЕ ВЛАДЕНИЕ ЦЕПОЧКОЙ.
# Имя цепи и содержимое правила может совпасть у чужой цепи, поэтому новое правило помечается
# комментарием (`-m comment --comment`). Поддержка ПРОВЕРЯЕТСЯ, а не предполагается: пробное
# правило создаётся во временной цепи и сразу удаляется.
#  • COMMENT_OK=1 — владение доказывается маркером;
#  • COMMENT_OK=0 — маркера нет; тогда владение НЕ заявляется по одному содержимому, и цепочка
#    без маркера в чужом/неизвестном состоянии не удаляется (см. chain_state).
OWN_TAG="voiceapp3:ttl:${D}OWN_GEN"
# iter57 §8.1 — DEVICE-SIDE МАРКЕР СОЗДАНИЯ.
# Совпадение ИМЕНИ и ТЕЛА немаркированной цепи владение НЕ доказывает: точно такую же цепь
# могло создать другое приложение или прежняя версия (аудит iter56). Поэтому «своя без
# комментария» — это только та, которую МЫ создали сами, и факт создания записан на устройстве
# (`-N` вернул rc=0 → пишем маркер). Маркер переживает рестарт приложения и обновление, а при
# удалении цепи удаляется. Потеря маркера = НЕДОКАЗАННОЕ владение (никаких -F/-X), а не «наша
# цепь по имени».
TTL_STATE_DIR=${D}{VAPP_TTL_STATE:-/data/local/tmp}
OWNMARK=${D}TTL_STATE_DIR/voiceapp3_ttl_own_${D}OWN_GEN
ownmark_ok() {
  [ -f "${D}OWNMARK" ] || return 1
  grep -q "^chain=${D}OUR${D}" "${D}OWNMARK" 2>/dev/null || return 1
  return 0
}
COMMENT_OK=0
if iptables -t mangle -N VAPP_TTLPROBE 2>/dev/null; then
  if iptables -w 5 -t mangle -A VAPP_TTLPROBE -m comment --comment "${D}OWN_TAG" -j RETURN 2>/dev/null; then
    COMMENT_OK=1
    echo "OWN_MARKER self=${D}OWN_TAG probe=ok"
  else
    echo "OWN_MARKER self=${D}OWN_TAG probe=unsupported action=fallback_name"
  fi
  iptables -w 5 -t mangle -F VAPP_TTLPROBE 2>/dev/null
  iptables -w 5 -t mangle -X VAPP_TTLPROBE 2>/dev/null
else
  echo "OWN_MARKER self=${D}OWN_TAG probe=unavailable action=fallback_name"
fi
# Аргументы правила с маркером (если поддерживается).
MARK_ARGS=""
[ "${D}COMMENT_OK" = "1" ] && MARK_ARGS="-m comment --comment ${D}OWN_TAG"

# iter55 §6.1 — ЧТО ДЕЛАТЬ, ЕСЛИ `-m comment` НЕ ПОДДЕРЖАН.
# Прежний код в этом случае всё равно создавал правило БЕЗ маркера, а затем `chain_state`
# называл его `unproven` и OFF отказывался его снимать: активный TTL, который приложение само
# не умеет убрать. Теперь идентичность без маркера доказывается ИМЕНЕМ цепи установки:
#   • имя уникально для установки (реестр дал токен) и НЕ историческое;
#   • тело правила точно совпадает с ожидаемым;
#   • реестр подтверждён (не unknown) — иначе имени верить нельзя.
# Только при всех трёх условиях цепь считается своей (`ours_named`) и может быть снята. Иначе
# (историческое имя, неизвестный реестр) — НИ ОДНОГО изменения таблицы: честный отказ до
# первой мутации, желание владельца сохраняется.
REGISTRY_UNKNOWN=@REGISTRY_UNKNOWN@
NAMED_OWN=0
# iter57 §8.1: имя установки больше НЕ доказательство. Своя немаркированная цепь — только та,
# чьё создание записано маркером на устройстве (`ownmark_ok`).
if [ "${D}COMMENT_OK" = "0" ] && [ -n "${D}OUR" ] && [ "${D}OUR" != "${D}LEGACY" ] && ownmark_ok; then
  NAMED_OWN=1
fi
echo "TTL_IDENTITY comment_supported=${D}COMMENT_OK registry_unknown=${D}REGISTRY_UNKNOWN " \
  "named_ownership=${D}NAMED_OWN chain=${D}OUR"

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

# iter52 §6.1: ССЫЛКИ НА НАШУ ЦЕПОЧКУ ИЗ ЛЮБОЙ ЦЕПИ таблицы mangle — и `-j`, и `-g`.
# Раньше смотрели только POSTROUTING и только `-j`: переход из другой цепи (OUTPUT/FORWARD/
# пользовательской) или goto (`-g`) не учитывался, и получалось «наших ссылок нет» — после чего
# `-F/-X` рвал чужое поведение, а TTL мог оставаться применимым уже без нашей цепочки.
# Формат строки: <цепь>\t<target>\t<спецификация ЦЕЛИКОМ>.
# `__READ_FAILED__` — прочитать таблицу не удалось (это НЕ «ссылок нет»).
ref_specs() {
  r_tool=${D}1
  if ! r_out=${D}(${D}r_tool -t mangle -S 2>/dev/null); then echo "__READ_FAILED__"; return 0; fi
  printf '%s\n' "${D}r_out" | awk -v our="${D}OUR" '
    /^-A / {
      chain = ${D}2
      spec = substr(${D}0, index(${D}0, ${D}3))
      n = split(spec, a, " ")
      for (i = 1; i < n; i++)
        if ((a[i] == "-j" || a[i] == "-g") && a[i+1] == our) { print chain "\t" a[i] "\t" spec; next }
    }'
}
# Все ссылки на нашу цепочку. -1 — прочитать нельзя.
ref_count() {
  r_all=${D}(ref_specs ${D}1)
  [ "${D}r_all" = "__READ_FAILED__" ] && { echo -1; return; }
  printf '%s\n' "${D}r_all" | grep -c . | tr -d ' '
}
# Ссылки, КРОМЕ ровно ожидаемого перехода POSTROUTING "-o <uplink> -j <OUR>". -1 — не прочитать.
ref_other_count() {
  r_all=${D}(ref_specs ${D}1)
  [ "${D}r_all" = "__READ_FAILED__" ] && { echo -1; return; }
  # ВНИМАНИЕ: имя переменной НЕ `exp` — это встроенная функция awk, и `-v exp=…` падает с
  # "cannot command line assign to exp" (проверено на настоящем awk). Поэтому `want_spec`.
  printf '%s\n' "${D}r_all" | awk -F'\t' -v want_spec="${D}EXPECTED_JUMP" '
    ${D}0 == "" { next }
    { if (${D}1 == "POSTROUTING" && ${D}3 == want_spec) next; c++ }
    END { print c + 0 }'
}

# iter50 §5.1: `-D` требует ОТДЕЛЬНЫЕ argv. Цикл по строкам с IFS=<newline> давал
# спецификацию ОДНИМ аргументом ("<-o wlan0 -j VAPP_TTL>"), iptables такой вызов отвергает,
# и наши правила оставались на месте. Разбираем строку на токены сами и передаём их как
# отдельные позиционные параметры; `eval` НЕ используется, набор допустимых символов
# ограничен, поэтому подстановки/кавычки/`;` через спецификацию невозможны.
# Возврат 1 — спецификация вне нашего узкого формата (удалять её нельзя).
split_spec() {
  spec=${D}1
  # Разрешены только символы обычных argv (пробел — разделитель токенов). Всё остальное —
  # кавычки, $, обратная кавычка, ;, &, |, <, >, скобки, \, *?[]{}~# — отказ: это либо не наш
  # формат, либо попытка подстановки. Разбор идёт БЕЗ eval, поэтому подстановки невозможны даже
  # теоретически, но такие правила мы всё равно не трогаем (чужое/непонятное).
  case "${D}spec" in
    *[!A-Za-z0-9./:_[:blank:]=,!@+-]*) return 1 ;;
  esac
  set -- ${D}spec
  [ ${D}# -ge 2 ] || return 1
  case "${D}1" in
    -*) return 0 ;;
    *) return 1 ;;
  esac
}

# Удаление ОДНОГО нашего перехода: точные отдельные argv, rc возвращается вызывающему.
del_jump() {
  d_tool=${D}1; d_spec=${D}2
  split_spec "${D}d_spec" || { echo "spec_unsupported"; return 1; }
  # Позиционные параметры функции заменяются токенами спецификации; каждая передаётся
  # ОТДЕЛЬНЫМ argv. Глоббинг исключён: символы *?[] отвергнуты split_spec.
  set -- ${D}d_spec
  ${D}d_tool -w 5 -t mangle -D POSTROUTING "${D}@"
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
    # Без маркера владение НЕ доказано, даже при точном совпадении содержимого: такую цепочку
    # нельзя ни чистить, ни удалять (чужое правило могло совпасть байт в байт).
    case "${D}body" in
      *"${D}OWN_TAG"*) : ;;
      *)
        # iter54 §6.1: НЕМАРКИРОВАННАЯ цепочка с совпадающим телом — это КАНДИДАТ, а не наша
        # цепочка. Совпадение текста правила не доказывает происхождение: точно такую же цепь
        # могло создать другое приложение или прежний код. Никакого присвоения, никакой
        # «миграции» и никакого -F/-X по этому признаку быть не может.
        if [ "${D}body" = "${D}want" ]; then
          # iter55 §6.1: без маркера владение может доказываться ТОЛЬКО уникальным именем
          # установки (см. NAMED_OWN). Иначе — unproven и никаких изменений.
          if [ "${D}NAMED_OWN" = "1" ]; then echo ours_named; return; fi
          echo unproven; return
        fi ;;
    esac
    body=${D}(printf '%s\n' "${D}body" | sed "s/-m comment --comment ${D}OWN_TAG //")
    # Владение — по МАРКЕРУ в самом правиле (см. OWN_TAG): имя цепи и содержимое могли
    # совпасть у чужой цепи, поэтому «наши» мы называем только помеченное. Ссылки (в том числе
    # `-g` и из других цепей) проверяются ОТДЕЛЬНО и ДО удаления (ref_other_count/TTL_REF_KEEP):
    # цепочка может быть нашей, но нужной чужой цепи — тогда её нельзя ни чистить, ни удалять.
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
# iter54 §6.1: историческое имя цепи (до появления уникального имени установки) проверяется
# ОТДЕЛЬНО и только для отчёта. Такая цепь НИКОГДА не чистится, не удаляется и не «мигрирует»:
# происхождение по имени/содержимому не доказывается (её могло создать другое приложение или
# прежняя версия), а сломать чужое правило нельзя.
LEGACY_PRESENT=no
legacy_chain_report() {
  [ -z "${D}LEGACY" ] && return 0
  if [ "${D}LEGACY" = "${D}OUR" ]; then
    # Сборка с историческим именем в качестве текущего (совместимость/тесты): отчёт нужен
    # ровно в том случае, когда владение этой цепью НЕ доказано. Доказанную свою цепь
    # «исторической» не называем, иначе отчёт противоречил бы сам себе.
    case "${D}(chain_state iptables "${D}RULE4")" in
      ours|absent|empty) return 0 ;;
    esac
  fi
  for lt in iptables ip6tables; do
    [ "${D}lt" = "ip6tables" ] && [ "${D}V6_SUPPORTED" != "1" ] && continue
    lout=${D}(${D}lt -t mangle -S "${D}LEGACY" 2>/dev/null) || continue
    ln=${D}(printf '%s\n' "${D}lout" | grep -c '^-A ')
    lj=${D}(${D}lt -t mangle -S POSTROUTING 2>/dev/null | awk -v lg="${D}LEGACY" \
      '/^-A POSTROUTING / { for(i=1;i<=NF;i++) if(${D}i=="-j"||${D}i=="-g"){ if(${D}(i+1)==lg){c++; break} } } END{print c+0}')
    if [ "${D}ln" != "0" ] || [ "${D}lj" != "0" ]; then
      LEGACY_PRESENT=yes
      echo "TTL_LEGACY_CHAIN family=${D}lt chain=${D}LEGACY rules=${D}ln jumps=${D}lj action=keep " \
        "reason=ownership_unproven origin=unknown " \
        "note=\"историческое имя цепи не доказывает владение: не изменяется; TTL может применяться и этой цепью\""
    fi
  done
  return 0
}

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
    # iter54 §6.1: тело совпало, но метки владения нет — происхождение НЕ доказано. Такую цепь
    # нельзя ни чистить, ни удалять, ни «мигрировать»: это может быть чужая цепь с тем же именем.
    # Возможные причины (в т.ч. отсутствие поддержки `-m comment`) перечисляются как факт.
    unproven)
      echo "TTL_OWN family=${D}fam state=unproven action=keep reason=ownership_unproven " \
        "comment_supported=${D}COMMENT_OK note=\"совпадение тела не доказывает владение: цепь не изменяется\""
      echo "${D}fam:skip"; return 0 ;;
    unknown) echo "TTL_OWN family=${D}fam state=unknown action=skip reason=read_failed"; echo "${D}fam:unknown"; return 0 ;;
  esac
  # iter55 §6.1: `ours_named` — своя цепь, доказанная уникальным именем установки при
  # неподдержанном `-m comment`. Основание владения печатается, чтобы отчёт не путал его с
  # маркером. Дальше путь тот же, что у `ours`.
  [ "${D}st" = "ours_named" ] && echo "TTL_OWN family=${D}fam state=ours_named " \
    "ownership=device_creation_marker gen=${D}OWN_GEN comment_supported=0"
  # состояние ours: снимаем ИМЕННО наши переходы (точное сравнение target), затем цепочку
  # Список спецификаций разбирается ПОСТРОЧНО: спецификация содержит пробелы
  # (`-o wlan0 -j VAPP_TTL`), поэтому обычный `for ${D}spec in ${D}(...)` разбил бы её на слова
  # и попытался бы удалить не то правило. IFS меняется только на время цикла.
  del_ok=0; del_fail=0
  # Построчное чтение: IFS не меняется, а строка разбирается на ОТДЕЛЬНЫЕ argv внутри
  # `del_jump`. Спецификация с кавычками/`;`/`$` не разбирается и не удаляется вовсе.
  while IFS= read -r spec; do
    [ -z "${D}spec" ] && continue
    Q1="'"; Q2='"'; BS='\\'
    case "${D}spec" in
      *${D}{Q1}*|*${D}{Q2}*|*${D}{BS}*)
        echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=refused reason=quoted_spec_needs_owner"
        del_fail=${D}((del_fail+1)); continue ;;
    esac
    spec_out=${D}(del_jump "${D}tool" "${D}spec" 2>/dev/null); rc=${D}?
    if [ "${D}rc" = "0" ]; then
      del_ok=${D}((del_ok+1)); echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=0"
    elif [ "${D}rc" = "1" ] && [ "${D}spec_out" = "spec_unsupported" ]; then
      echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=refused reason=spec_outside_our_format"
      del_fail=${D}((del_fail+1))
    else
      del_fail=${D}((del_fail+1)); echo "TTL_DEL family=${D}fam spec=\"${D}spec\" rc=nonzero rc_value=${D}rc"
    fi
  done <<EOF_SPECS
${D}(jump_specs "${D}tool")
EOF_SPECS
  if [ "${D}del_fail" != "0" ]; then
    # Часть переходов снять нельзя (сложная спецификация/отказ). Нашу цепочку НЕ чистим и
    # НЕ удаляем: иначе получится «висячая» цепочка без правила, а TTL останется применённым
    # через оставшийся переход — лучше честное partial, чем полуснятое состояние.
    echo "TTL_OWN family=${D}fam state=partial removed=partial jumps_failed=${D}del_fail reason=jumps_not_removed"
    echo "${D}fam:partial"; return 0
  fi
  # iter52 §6.1: цепочку нельзя чистить/удалять, пока на неё ссылаются ОТКУДА-ТО ЕЩЁ
  # (другая цепь, goto из POSTROUTING, чужой пользовательский переход). Иначе чужое поведение
  # ломается, а TTL может остаться применённым мимо нашей цепочки. Такое состояние НЕ снимаем:
  # отчёт честный, семья — partial.
  refs_all=${D}(ref_specs "${D}tool")
  if [ "${D}refs_all" = "__READ_FAILED__" ]; then
    echo "TTL_OWN family=${D}fam state=unknown removed=unproven reason=refs_read_failed"
    echo "${D}fam:unproven"; return 0
  fi
  refs_n=${D}(printf '%s\n' "${D}refs_all" | grep -c . | tr -d ' ')
  if [ "${D}refs_n" != "0" ]; then
    # Печать первых трёх ссылок: префикс собирается в shell (внутри sed имя семьи не раскрылось бы).
    printf '%s\n' "${D}refs_all" | head -3 | sed 's/\t/ /g' | while IFS= read -r ref_line; do
      [ -n "${D}ref_line" ] && echo "TTL_REF_KEEP family=${D}fam ref=\"${D}ref_line\""
    done
    echo "TTL_OWN family=${D}fam state=referenced action=keep refs=${D}refs_n reason=referenced_elsewhere note=\"цепочка очищена не полностью: на неё ссылаются из других мест\""
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
    echo "TTL_OWN family=${D}fam state=absent removed=ok jumps_left=0 rules_left=0"
    # Цепи больше нет ⇒ маркер создания не должен пережить её: иначе следующая установка с тем же
    # gen сочла бы «своей» цепь, которую создаст кто-то другой.
    if [ "${D}fam" = "v4" ]; then rm -f "${D}OWNMARK" 2>/dev/null; echo "TTL_OWNMARK clear=ok path=${D}OWNMARK"; fi
    echo "${D}fam:ok"
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
TTL_STOP=0
ROLLED_BACK=no
TOUCHED_v4=no
TOUCHED_v6=no
if [ "${D}ENABLE" = "2" ]; then
  # Реестр идентичности неизвестен: НИ ОДНОГО изменения таблицы. Желание владельца не
  # переписывается и не «выключается» — сообщается причина и что именно проверить.
  VARIANT=none
  echo "TTL_TRY action=skip_all reason=registry_unknown " \
    "note=\"идентичность своей цепи не установлена (реестр неизвестен): таблица не изменяется; " \
    "желание владельца сохранено, повтор — после восстановления реестра\""
elif [ "${D}ENABLE" = "1" ]; then
  if [ -z "${D}UPLINK" ]; then
    echo "TTL_TRY variant=none scope=\"\" reason=no_uplink"
  else
    echo "TTL_TRY variant=uplink scope=\"-o ${D}UPLINK\" carplay=${D}{CARPLAY:-none}"
    # iter57 §8.1: РАБОЧИЙ путь без поддержки комментария.
    # Раньше здесь стоял полный запрет любых изменений — то есть на прошивке без `-m comment`
    # TTL не включался НИКОГДА («бессрочный отказ» вместо результата). Теперь запрет касается
    # только ЧУЖИХ/неизвестных объектов: СВОЮ цепь мы вправе создать (создание доказуемо — `-N`
    # вернул 0, и это записано маркером на устройстве). Опасная операция — не создание, а
    # дописывание в уже существующую цепь неизвестного происхождения; она проверяется ниже
    # (ветка `-N` rc≠0). Отдельного глобального стопа здесь больше нет.
    # Историческое имя цепи (его использует прошлая версия приложения) НЕ создаётся и не
    # изменяется: иначе «своя по маркеру» цепь могла бы оказаться цепью прежней версии, которую
    # другой код считает своей. Это единственный безусловный стоп по имени.
    if [ -n "${D}LEGACY" ] && [ "${D}OUR" = "${D}LEGACY" ] && \
       [ "${D}COMMENT_OK" = "0" ] && ! ownmark_ok; then
      TTL_STOP=1
      echo "TTL_TRY action=stop reason=legacy_name_not_owned chain=${D}OUR " \
        "note=\"историческое имя цепи не создаётся и не изменяется: владение по нему не " \
        "доказывается (его использует прежняя версия приложения)\""
    fi
    if [ "${D}TTL_STOP" = "0" ] && [ "${D}COMMENT_OK" = "0" ] && [ "${D}NAMED_OWN" = "0" ]; then
      echo "TTL_IDENTITY action=create_only comment_supported=0 " \
        "note=\"маркера нет: разрешено только СОЗДАНИЕ своей цепи; существующая цепь без " \
        "маркера не изменяется\""
    fi
    # Если ХОТЬ ОДНА семья в неизвестном или не нашем состоянии — не применяем НИЧЕГО:
    # половинчатое состояние (только v6) выглядит как «TTL работает», хотя v4 не наш.
    NOT_OWNED=""
    case "${D}CS_V4" in collision|empty|unknown|unproven) NOT_OWNED="v4=${D}CS_V4";; esac
    if [ "${D}V6_SUPPORTED" = "1" ]; then
      case "${D}CS_V6" in
        collision|empty|unknown|unproven)
          if [ -n "${D}NOT_OWNED" ]; then NOT_OWNED="${D}NOT_OWNED v6=${D}CS_V6"
          else NOT_OWNED="v6=${D}CS_V6"; fi ;;
      esac
    fi
    # POSIX: никаких ${D}{NOT_OWNED// /} — `//` это bashism, на #!/bin/sh (dash) это
    # «Bad substitution» и скрипт обрывается на середине.
    if [ -n "${D}NOT_OWNED" ]; then
      # iter55 §6.1: состояние КАЖДОЙ семьи называется, даже когда изменять нельзя: отчёт не
      # должен терять причину из-за того, что мы ничего не трогаем.
      case "${D}CS_V4" in
        unproven) echo "TTL_OWN family=v4 state=unproven action=keep reason=ownership_unproven " \
          "comment_supported=${D}COMMENT_OK named_ownership=${D}NAMED_OWN note=\"совпадение имени/тела не доказывает владение\"" ;;
        *) echo "TTL_OWN family=v4 state=${D}CS_V4 action=keep reason=chain_state_not_ours" ;;
      esac
      if [ "${D}V6_SUPPORTED" = "1" ]; then
        case "${D}CS_V6" in
          unproven) echo "TTL_OWN family=v6 state=unproven action=keep reason=ownership_unproven" ;;
          *) echo "TTL_OWN family=v6 state=${D}CS_V6 action=keep reason=chain_state_not_ours" ;;
        esac
      fi
      echo "TTL_TRY action=skip_all reason=family_not_owned ${D}NOT_OWNED"
    else
      VARIANT=uplink
      # --- v4 ---
      # iter54 §6.1: «нашим» считается ТОЛЬКО доказанно помеченное состояние (`ours`).
      # Немаркированная цепь — не наша: её не трогаем и НЕ считаем уже применённой.
      drop_out4=${D}(drop_family iptables "${D}RULE4" v4); echo "${D}drop_out4"
      # iter50 §5.1(3): если своё снять НЕ удалось (partial/unknown/collision/skip),
      # безусловно продолжать -N/-A нельзя — рядом со старым правилом появилось бы второе,
      # а «слепой rollback» чужого состояния запрещён. Явный отказ с причиной.
      case "${D}drop_out4" in
        *:partial*|*:unknown*|*:collision*|*:skip*) echo "TTL_TRY action=stop reason=v4_own_not_removed"; TTL_STOP=1 ;;
      esac
      if [ "${D}TTL_STOP" = "0" ]; then
      # iter55 §6.2: rc создания цепи ПРОВЕРЯЕТСЯ. Раньше `-N` игнорировался: если цепь уже
      # существовала (конкурент создал одноимённую чужую), следующий `-A` дописывал правило в
      # ЧУЖОЙ объект. Теперь дописывать можно только в СВОЮ доказанную цепь.
      iptables -w 5 -t mangle -N "${D}OUR" 2>/dev/null; rc_n=${D}?
      # iter57 §8.1: цепь создана НАМИ (rc=0) — фиксируем это на устройстве.
      if [ "${D}rc_n" = "0" ]; then
        printf 'chain=%s\ngen=%s\ncreated=%s\n' "${D}OUR" "${D}OWN_GEN" "$(date +%s 2>/dev/null)" \
          > "${D}OWNMARK" 2>/dev/null || echo "TTL_OWNMARK write=failed path=${D}OWNMARK"
        echo "TTL_OWNMARK write=ok path=${D}OWNMARK chain=${D}OUR"
      fi
      if [ "${D}rc_n" != "0" ]; then
        st_n=${D}(chain_state iptables "${D}RULE4")
        case "${D}st_n" in
          ours|ours_named)
            echo "TTL_ADD family=v4 chain=${D}OUR action=reuse reason=chain_exists_ours state=${D}st_n" ;;
          *)
            echo "TTL_TRY action=stop reason=chain_exists_not_ours family=v4 state=${D}st_n rc_n=${D}rc_n " \
              "note=\"цепь с нашим именем уже существует и не доказана как наша: правило не дописывается\""
            TTL_STOP=1 ;;
        esac
      fi
      if [ "${D}TTL_STOP" = "0" ]; then
      # shellcheck disable=SC2086
      iptables -w 5 -t mangle -A "${D}OUR" ${D}MARK_ARGS -j TTL --ttl-set 65 2>&1
      rc_a=${D}?; echo "TTL_ADD family=v4 chain=${D}OUR marker=${D}COMMENT_OK rc=${D}rc_a"
      iptables -w 5 -t mangle -A POSTROUTING -o "${D}UPLINK" -j "${D}OUR" 2>&1
      rc_j=${D}?; echo "TTL_ADD family=v4 jump scope=\"-o ${D}UPLINK\" rc=${D}rc_j"
      # iter56 §8: учёт КАЖДОГО успешного действия, а не только создания цепи. Раньше
      # TOUCHED_v4 поднимался лишь при `-N` rc=0: правило, добавленное в УЖЕ СУЩЕСТВУЮЩУЮ свою
      # цепь (rc_n≠0, -A rc=0), считалось «ничего не меняли», и откат при последующем сбое не
      # выполнялся — на устройстве оставалось живое правило при отчёте «не изменяли».
      if [ "${D}rc_n" = "0" ] || [ "${D}rc_a" = "0" ] || [ "${D}rc_j" = "0" ]; then TOUCHED_v4=yes; fi
      fi
      fi
      # --- v6 ---
      if [ "${D}V6_SUPPORTED" = "1" ] && [ "${D}TTL_STOP" = "0" ]; then
        drop_out6=${D}(drop_family ip6tables "${D}RULE6" v6); echo "${D}drop_out6"
        case "${D}drop_out6" in
          *:partial*|*:unknown*|*:collision*|*:skip*) echo "TTL_TRY action=stop reason=v6_own_not_removed"; TTL_STOP=1 ;;
        esac
      fi
      if [ "${D}V6_SUPPORTED" = "1" ] && [ "${D}TTL_STOP" = "0" ]; then
        ip6tables -w 5 -t mangle -N "${D}OUR" 2>/dev/null; rc_n6=${D}?
        if [ "${D}rc_n6" = "0" ] && [ -f "${D}OWNMARK" ]; then
          echo "TTL_OWNMARK v6=created chain=${D}OUR (маркер уже записан v4)"
        fi
        if [ "${D}rc_n6" != "0" ]; then
          st_n6=${D}(chain_state ip6tables "${D}RULE6")
          case "${D}st_n6" in
            ours|ours_named)
              echo "TTL_ADD family=v6 chain=${D}OUR action=reuse reason=chain_exists_ours state=${D}st_n6" ;;
            *)
              echo "TTL_TRY action=stop reason=chain_exists_not_ours family=v6 state=${D}st_n6 rc_n=${D}rc_n6"
              TTL_STOP=1 ;;
          esac
        fi
        if [ "${D}TTL_STOP" = "0" ]; then
        # shellcheck disable=SC2086
        ip6tables -w 5 -t mangle -A "${D}OUR" ${D}MARK_ARGS -j HL --hl-set 65 2>&1
        rc_a6=${D}?; echo "TTL_ADD family=v6 chain=${D}OUR marker=${D}COMMENT_OK rc=${D}rc_a6"
        ip6tables -w 5 -t mangle -A POSTROUTING -o "${D}UPLINK" -j "${D}OUR" 2>&1
        rc_j6=${D}?; echo "TTL_ADD family=v6 jump scope=\"-o ${D}UPLINK\" rc=${D}rc_j6"
        # iter56 §8: то же для v6 — учитывается любое состоявшееся добавление, не только `-N`.
        if [ "${D}rc_n6" = "0" ] || [ "${D}rc_a6" = "0" ] || [ "${D}rc_j6" = "0" ]; then TOUCHED_v6=yes; fi
        fi
      fi
      # iter53 §8.2: откат решается по ФАКТУ изменения, а не по значениям rc.
      # Раньше при `TTL_STOP=1` (v6 отказалась после того, как v4 уже применена) rc обнулялись
      # в `skip`, блок отката пропускался целиком — и v4 оставалась изменённой при отказе v6.
      # Теперь: `TOUCHED_*` фиксирует реальное изменение, и любая «остановка» после него требует
      # снятия обеих семей.
      if [ "${D}TTL_STOP" = "1" ] && { [ "${D}TOUCHED_v4" = "yes" ] || [ "${D}TOUCHED_v6" = "yes" ]; }; then
        echo "TTL_TRY action=stop reason=partial_after_stop v4=${D}TOUCHED_v4 v6=${D}TOUCHED_v6"
      fi
      # Половинчатое включение не оставляем: если хоть одна семья не встала, снимаем обе.
      # Порядок важен: сначала ТОЧНЫЙ переход, затем -F, затем -X (иначе -X вернёт "Too many links").
      need_rb=no
      # 1) любая изменённая семья обязана быть снята, если цепочка/переход не встали;
      if [ "${D}TOUCHED_v4" = "yes" ]; then
        [ "${D}rc_a" != "0" ] && need_rb=yes
        [ "${D}rc_j" != "0" ] && need_rb=yes
      fi
      if [ "${D}TOUCHED_v6" = "yes" ]; then
        [ "${D}rc_a6" != "0" ] && need_rb=yes
        [ "${D}rc_j6" != "0" ] && need_rb=yes
      fi
      # 2) остановка ПОСЛЕ уже сделанного изменения (например, v6 отказала после v4) — откат;
      if [ "${D}TTL_STOP" = "1" ]; then
        if [ "${D}TOUCHED_v4" = "yes" ] || [ "${D}TOUCHED_v6" = "yes" ]; then need_rb=yes; fi
      fi
      if [ "${D}need_rb" = "yes" ]; then
        echo "TTL_ROLLBACK reason=incomplete rc4=${D}rc_a/${D}rc_j rc6=${D}{rc_a6:-n}/${D}{rc_j6:-n}"
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
          # Цепь снята — маркер создания больше не нужен (иначе он «доказывал» бы владение
          # несуществующей цепью и мог быть подхвачен чужой установкой с тем же gen).
          [ "${D}rx4" = "0" ] && rm -f "${D}OWNMARK" 2>/dev/null
          echo "TTL_ROLLBACK family=v4 jump_del_rc=${D}rj4 chain_flush_rc=${D}rf4 chain_del_rc=${D}rx4"
          TOUCHED_v4=no
        fi
        # iter54 §6.2: `ROLLED_BACK=yes` заявляется ТОЛЬКО после readback, доказывающего, что
        # наших правил/переходов больше нет. Попытка `-D/-F/-X` (пусть и с rc=0) откатом не
        # является: другой процесс мог успеть вернуть правило.
        # iter55 §6.2: ФАКТИЧЕСКОЕ чтение, а не константа `absent`. Прежний код объявлял v4
        # отсутствующей без чтения: отказ `-X` при уже снятом переходе давал ложный ROLLED_BACK=yes.
        iptables -t mangle -S >/dev/null 2>&1; RB_V4_RC=${D}?
        RB_V4=${D}(chain_state iptables "${D}RULE4"); RB_J4=${D}(jump_count iptables)
        RB_V6=absent; RB_J6=0; RB_V6_RC=0
        [ "${D}V6_SUPPORTED" = "1" ] && {
          ip6tables -t mangle -S >/dev/null 2>&1; RB_V6_RC=${D}?
          RB_V6=${D}(chain_state ip6tables "${D}RULE6"); RB_J6=${D}(jump_count ip6tables); }
        echo "TTL_ROLLBACK_VERIFY v4_chain=${D}RB_V4 v4_jumps=${D}RB_J4 v4_read_rc=${D}RB_V4_RC " \
          "v6_chain=${D}RB_V6 v6_jumps=${D}RB_J6 v6_read_rc=${D}{RB_V6_RC:-n}"
        # Откат доказан только при УСПЕШНОМ чтении: `absent` при недоступном readback ничего
        # не доказывает, а `-1` в jump_count — «неизвестно», а не «нет переходов».
        if [ "${D}RB_V4_RC" = "0" ] && [ "${D}RB_J4" = "0" ] && \
           { [ "${D}RB_V4" = "absent" ] || [ "${D}RB_V4" = "none" ]; } && \
           { [ "${D}V6_SUPPORTED" != "1" ] || { [ "${D}RB_V6_RC" = "0" ] && \
             { [ "${D}RB_V6" = "absent" ] || [ "${D}RB_V6" = "none" ]; }; }; } && \
           { [ "${D}V6_SUPPORTED" != "1" ] || [ "${D}RB_J6" = "0" ]; }; then
          VARIANT=none
          ROLLED_BACK=yes
          echo "TTL_ROLLBACK result=proved v4_chain=${D}RB_V4 v4_jumps=${D}RB_J4 v6_chain=${D}RB_V6 v6_jumps=${D}RB_J6"
        else
          VARIANT=none
          ROLLED_BACK=no
          echo "TTL_ROLLBACK result=unproven v4_chain=${D}RB_V4 v4_jumps=${D}RB_J4 v6_chain=${D}RB_V6 v6_jumps=${D}RB_J6 " \
            "action=keep note=\"откат не доказан чтением: состояние названо частичным, повторных воздействий нет\""
        fi
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
          # тот же безопасный путь: отдельные argv, без eval и склейки
          del_jump "${D}tool" "${D}s" >/dev/null 2>&1 || break
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
legacy_chain_report

# ---------- итоговое состояние (перечитывается ПОСЛЕ действий) ----------
CS2_V4=${D}(chain_state iptables "${D}RULE4")
CS2_V6=none; [ "${D}V6_SUPPORTED" = "1" ] && CS2_V6=${D}(chain_state ip6tables "${D}RULE6")
J4=${D}(jump_count iptables); J6=0; [ "${D}V6_SUPPORTED" = "1" ] && J6=${D}(jump_count ip6tables)
# iter52 §6.1: ссылки на нашу цепочку из ЛЮБОЙ цепи/через goto — часть доказательства владения.
REFS_OUT4=${D}(ref_other_count iptables); REFS_ANY4=${D}(ref_count iptables)
REFS_OUT6=0; REFS_ANY6=0
if [ "${D}V6_SUPPORTED" = "1" ]; then
  REFS_OUT6=${D}(ref_other_count ip6tables); REFS_ANY6=${D}(ref_count ip6tables)
fi
# -1 — таблицу прочитать не удалось: это не «ссылок нет».
if [ "${D}REFS_OUT4" = "-1" ] || [ "${D}REFS_ANY4" = "-1" ] || [ "${D}REFS_OUT6" = "-1" ] || [ "${D}REFS_ANY6" = "-1" ]; then
  echo "TTL_REF_READ result=failed refs_out4=${D}REFS_OUT4 refs_any4=${D}REFS_ANY4 refs_out6=${D}REFS_OUT6 refs_any6=${D}REFS_ANY6 action=none"
fi
if [ "${D}REFS_OUT4" != "0" ] || [ "${D}REFS_OUT6" != "0" ]; then
  echo "TTL_REF_FOREIGN refs_out4=${D}REFS_OUT4 refs_out6=${D}REFS_OUT6 note=\"на ${D}OUR ссылаются из других мест/через -g: правило работает не только на нашем переходе\""
fi
JS4_RAW=${D}(jump_specs iptables | head -1)
JS6_RAW=${D}(jump_specs ip6tables | head -1)
ALL_V4=${D}(count_ttl_rules iptables 'ttl-set')
ALL_V6=0; [ "${D}V6_SUPPORTED" = "1" ] && ALL_V6=${D}(count_ttl_rules ip6tables 'hl-set')
FOREIGN_V4=${D}ALL_V4
FOREIGN_V6=${D}ALL_V6
# iter54 §6.1: «нашим» считается ТОЛЬКО помеченное состояние `ours` (метка = наш токен
# установки). Немаркированная цепь даже с совпадающим телом — не наша: не трогаем.
OWN_OK_V4=no
[ "${D}CS2_V4" = "ours" ] && OWN_OK_V4=yes
OWN_OK_V6=no
[ "${D}CS2_V6" = "ours" ] && OWN_OK_V6=yes

OWN_V4=0; [ "${D}OWN_OK_V4" = "yes" ] && OWN_V4=1; [ "${D}CS2_V4" = "unknown" ] && OWN_V4=-1
OWN_V6=0; [ "${D}OWN_OK_V6" = "yes" ] && OWN_V6=1; [ "${D}CS2_V6" = "unknown" ] && OWN_V6=-1
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
  if [ "${D}OWN_OK_V4" = "yes" ] && [ "${D}J4" = "1" ] && [ "${D}JS4_RAW" = "${D}EXPECTED_JUMP" ] && \
     [ "${D}REFS_OUT4" = "0" ]; then VERIFY_V4=OK; fi
else
  if [ "${D}CS2_V4" = "absent" ] && [ "${D}J4" = "0" ] && [ "${D}REFS_ANY4" = "0" ]; then VERIFY_V4=OFF_OK; fi
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
    if [ "${D}OWN_OK_V6" = "yes" ] && [ "${D}J6" = "1" ] && [ "${D}JS6_RAW" = "${D}EXPECTED_JUMP" ] && \
       [ "${D}REFS_OUT6" = "0" ]; then VERIFY_V6=OK; fi
  else
    if [ "${D}CS2_V6" = "absent" ] && [ "${D}J6" = "0" ] && [ "${D}REFS_ANY6" = "0" ]; then VERIFY_V6=OFF_OK; fi
  fi
fi

if [ "${D}ENABLE" = "0" ]; then
  if [ "${D}VERIFY_V4" = "UNKNOWN" ] || [ "${D}VERIFY_V6" = "UNKNOWN" ]; then
    # Отказ чтения ≠ «правил нет»: снятие НЕ подтверждено.
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 result=UNKNOWN reason=read_failed read_v4=${D}READ_V4 read_v6=${D}{READ_V6} refs_out4=${D}REFS_OUT4 refs_any4=${D}REFS_ANY4 refs_out6=${D}REFS_OUT6 refs_any6=${D}REFS_ANY6"
  elif [ "${D}VERIFY_V4" = "OFF_OK" ] && { [ "${D}VERIFY_V6" = "OFF_OK" ] || [ "${D}VERIFY_V6" = "SKIPPED" ]; }; then
    if [ "${D}FOREIGN_V4" = "0" ] && [ "${D}FOREIGN_V6" = "0" ]; then
      echo "TTL_OFF own_v4=0 own_v6=0 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=OK v6=${D}{VERIFY_V6}"
    else
      echo "TTL_OFF own_v4=0 own_v6=0 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=PARTIAL reason=foreign_rules_remain"
    fi
  elif [ "${D}VERIFY_V4" = "COLLISION" ] || [ "${D}VERIFY_V6" = "COLLISION" ]; then
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 result=PARTIAL reason=foreign_chain_collision action=keep"
  elif [ "${D}REFS_ANY4" != "0" ] || [ "${D}REFS_ANY6" != "0" ]; then
    # iter53 §8.1: цепочку нельзя ни чистить, ни удалять, пока на неё ссылаются из других мест —
    # иначе сломается ЧУЖОЕ поведение. Это не «снято» и не «сломалось»: честный PARTIAL с причиной.
    echo "TTL_OFF own_v4=${D}OWN_V4 own_v6=${D}OWN_V6 foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 result=PARTIAL reason=referenced_elsewhere action=keep refs_out4=${D}REFS_OUT4 refs_any4=${D}REFS_ANY4 refs_out6=${D}REFS_OUT6 refs_any6=${D}REFS_ANY6"
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

if [ "${D}ENABLE" = "2" ]; then
  VERDICT=CHECK_OWNER
elif [ "${D}ENABLE" = "1" ]; then
  if [ "${D}VERIFY_V4" = "UNKNOWN" ] || [ "${D}VERIFY_V6" = "UNKNOWN" ]; then VERDICT=READ_UNKNOWN
  elif [ "${D}VERIFY_V4" = "COLLISION" ] || [ "${D}VERIFY_V6" = "COLLISION" ]; then VERDICT=CHAIN_COLLISION
  # iter50 §5.1(3): остановка из-за неснятого своего правила — НЕ успех, даже если в таблице
  # стоит корректное правило (оно осталось от прошлого раза, а не выставлено сейчас).
  elif [ "${D}TTL_STOP" = "1" ]; then VERDICT=OWN_LEFT_STOP
  elif [ "${D}REFS_OUT4" != "0" ] || [ "${D}REFS_OUT6" != "0" ]; then VERDICT=REF_ELSEWHERE
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
  elif [ "${D}REFS_ANY4" != "0" ] || [ "${D}REFS_ANY6" != "0" ]; then VERDICT=REF_ELSEWHERE
  else VERDICT=CHECK_OWNER; fi
fi

echo "TTL_CHECK: rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} real=UNKNOWN expected=65 src=${D}{SRC_IP:-unknown} dst=1.1.1.1 iface=${D}{UPLINK:-none} method=rules_not_wire reason=wire_not_measured jumps_v4=${D}J4 jumps_v6=${D}J6 chain_v4=${D}CS2_V4 chain_v6=${D}CS2_V6 ARROW ${D}VERDICT"
echo "TTL_RESULT rule_v4=${D}{RULE_TTL:-none} rule_v6=${D}{RULE_HL:-none} verify_v4=${D}VERIFY_V4 verify_v6=${D}VERIFY_V6 wire_v4=not_measured enable=${D}ENABLE scoped=${D}SCOPED uplink=${D}{UPLINK:-none} variant=${D}VARIANT own_chain=${D}OUR foreign_v4=${D}FOREIGN_V4 foreign_v6=${D}FOREIGN_V6 chain_v4=${D}CS2_V4 chain_v6=${D}CS2_V6 read_v4=${D}READ_V4 read_v6=${D}{READ_V6} refs_out4=${D}REFS_OUT4 refs_any4=${D}REFS_ANY4 refs_out6=${D}REFS_OUT6 refs_any6=${D}REFS_ANY6"
echo '=== TTL_SCRIPT end ==='
""".trimIndent()
            .replace("ARROW", "\u2192")
            .replace("@ENABLE_VALUE@", enableBit)
            .replace("@OUR_CHAIN@", safeChain)
            .replace("@LEGACY_CHAIN@", safeLegacy)
            .replace("@REGISTRY_UNKNOWN@", if (registryUnknown) "1" else "0")
            .replace("@OWN_GEN@", safeGen)
            .replace("@RULE_V4@", RULE_V4)
            .replace("@RULE_V6@", RULE_V6)
    }

    /** Скрипт стартовой санации (boot / приложение стартовало): снять только своё. */
    fun sanitizeScript(): String = build(false)
}
