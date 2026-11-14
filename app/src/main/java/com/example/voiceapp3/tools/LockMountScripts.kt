package com.example.voiceapp3.tools

/**
 * iter48 §В / iter49 §4 / iter50 §4 — shell-скрипты монтирования и снятия звука замка.
 *
 * ── Что было сломано к iter50 (воспроизведено на СГЕНЕРИРОВАННЫХ скриптах) ──
 *  1. `mi_layers()` в bind-скрипте печатал ТРИ поля `<id>|<maj:min>|<root>`, а разбор делал
 *     `mroot=${top#*|}` — то есть отрезал только первое поле и сравнивал `maj:min|root` с
 *     одним `stagePath`. Итог: `MOUNTED_OK` не печатался НИКОГДА, хотя `mount -o bind` уже
 *     выполнился → успешно созданный слой оставался без учёта.
 *  2. В restore-скрипте `mi_layers()` печатал ДВА поля `<id>|<root>`, а разбор делал
 *     `${top#*|*|}` (нужно ≥3 поля) → `root` оставался `id|/data/local/tmp/...` → `case`
 *     не совпадал → СВОЙ слой помечался как чужой и не снимался.
 *
 * ── Как сделано теперь ──
 *  • ЕДИНЫЙ формат строки: `<mount_id>|<major:minor>|<root>` — РОВНО три поля, в обоих
 *    скриптах одни и те же `mi_layers/mi_count/mi_top/mi_split`. Разбор без неоднозначных
 *    подстановок: `mi_split` возвращает 2 на повреждённой строке, и это НЕ превращается в
 *    «слоя нет».
 *  • Ожидаемый `root` bind-источника считается по ФАКТИЧЕСКОЙ таблице монтирования: находится
 *    самый длинный mount point, содержащий staging-путь, и к его `root` добавляется остаток.
 *    Поэтому `/data` как отдельная ФС (`root=/local/tmp/x`) обрабатывается правильно, а не
 *    сравнивается с абсолютным путём «на глаз».
 *  • `\040`/`\011`/`\012`/`\134` из mountinfo раскрываются через `printf '%b'`.
 *  • Порядок строк mountinfo НЕ считается гарантией «верхний слой последний»: решение
 *    принимается по доказанному владению (root + dev:inode), иначе — pending/отказ.
 *  • После `mount` печатаются ФАКТЫ для каждого исхода: подтверждён, не подтверждён,
 *    неизвестен. Пропадает только «мы не знаем», а не запись о слое.
 *  • `MOUNTINFO` — точка подмены таблицы для тестов (`${MOUNTINFO:-/proc/self/mountinfo}`);
 *    в бою переменная не задана и путь обычный.
 *
 * Чего это НЕ доказывает: слышимость звука, видимость слоя в namespace аудиопроцесса и
 * обратимость на конкретной прошивке. `umount -l` помечается как detach, а не освобождение.
 */
internal object LockMountScripts {

    /** Единственный каталог, который приложение вправе трогать (AVAS/`/system/etc/avas` — нет). */
    const val TARGET_DIR = "/vendor/etc/carlock"

    /** Наш staging-префикс — метка владения слоем. */
    val STAGE_PREFIX: String get() = MountInfo.STAGE_PREFIX

    private const val MAX_LAYERS_PER_TARGET = 6

    /**
     * Общий пролог обоих скриптов: namespace, единый формат mountinfo, разбор строк,
     * вычисление ожидаемого root и доказательство владения слоем.
     *
     * Печатает `MOUNT_NS`, `MOUNT_NS_MISMATCH`, `MOUNT_TABLE` (читается ли таблица вообще).
     */
    private fun commonPrologue(): String = """
set +e
SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
NS_SELF=${'$'}(readlink /proc/self/ns/mnt 2>/dev/null)
NS_INIT=${'$'}(readlink /proc/1/ns/mnt 2>/dev/null)
NS_OK=0
[ -n "${'$'}NS_SELF" ] && [ -n "${'$'}NS_INIT" ] && [ "${'$'}NS_SELF" = "${'$'}NS_INIT" ] && NS_OK=1
echo "MOUNT_NS self=${'$'}{NS_SELF:-unknown} init=${'$'}{NS_INIT:-unknown} same=${'$'}NS_OK"
[ "${'$'}NS_OK" = "1" ] || echo "MOUNT_NS_MISMATCH self=${'$'}{NS_SELF:-unknown} init=${'$'}{NS_INIT:-unknown} action=none reason=namespace_differs"

# Таблица монтирования: в бою /proc/self/mountinfo, в тестах — подмена (MOUNTINFO).
MI=${'$'}{MOUNTINFO:-/proc/self/mountinfo}
if [ -r "${'$'}MI" ]; then echo "MOUNT_TABLE path=ok"; else echo "MOUNT_TABLE path=unreadable action=none"; fi

# ЕДИНЫЙ формат: <mount_id>|<major:minor>|<root> — ровно три поля (см. KDoc).
mi_layers() { awk -v t="${'$'}1" '${'$'}5==t {print ${'$'}1"|"${'$'}3"|"${'$'}4}' "${'$'}MI" 2>/dev/null; }
mi_count() { mi_layers "${'$'}1" | wc -l | tr -d ' '; }
# iter52 §5.2: верхний слой = МАКСИМАЛЬНЫЙ mount_id среди слоёв цели, а не последняя строка
# файла. Порядок строк mountinfo не гарантирует порядок вложения: в реальных таблицах (и в
# тестах) строка с большим id может стоять ВЫШЕ по файлу, и «последняя строка» отдавала не тот слой —
# тогда проверка root/umount работала не с фактическим верхом. Если id нечисловой,
# возвращается AMBIGUOUS||, и вызывающая сторона обязана считать это «не доказано»,
# а не «слоя нет». Пустой вывод означает ЛИБО «слоёв нет», ЛИБО «таблица не прочиталась» —
# различать обязан барьер на читаемость таблицы (см. MOUNT_TABLE выше).
mi_top() {
  mi_layers "${'$'}1" | awk -F'|' '
    BEGIN { best = -1; line = ""; bad = 0 }
    { if (${'$'}1 ~ /^[0-9]+${'$'}/) { if (${'$'}1 + 0 > best) { best = ${'$'}1 + 0; line = ${'$'}0 } } else { bad = 1 } }
    END { if (bad) { print "AMBIGUOUS||"; exit } if (line != "") print line }'
}

# Разбор строки mi_layers в MID/MMIN/ROOT. 2 = повреждённая строка (не догадываемся).
# ROOT может содержать '|' (в mountinfo он не экранируется), поэтому отрезается только
# ПЕРВОЕ вхождение разделителя на каждом шаге — число полей при этом не «плывёт».
mi_split() {
  case "${'$'}1" in *"|"*"|"*) ;; *) return 2 ;; esac
  MID=${'$'}{1%%|*}; rest=${'$'}{1#*|}; MMIN=${'$'}{rest%%|*}; ROOT=${'$'}{rest#*|}
  [ -n "${'$'}MID" ] && [ -n "${'$'}MMIN" ] && [ -n "${'$'}ROOT" ] || return 2
  return 0
}

# Раскрытие octal-escape mountinfo (\040 пробел, \011 таб, \012 \n, \134 backslash).
mi_unesc() { printf '%b' "${'$'}1"; }

# Ожидаемый root для bind-источника (staging-файла): mount point, содержащий путь,
# самый длинный; результат = root_этого_mount + остаток пути.
# Возврат 1 — определить нельзя (таблица недоступна/путь не покрыт).
expected_root_for() {
  want=${'$'}1
  best=0; brows=""; bmm=""; bmp=""
  while read -r id par mm root mp rest || [ -n "${'$'}id" ]; do
    [ -n "${'$'}id" ] || continue
    case "${'$'}mm" in *:*) ;; *) continue ;; esac
    rmp=${'$'}(mi_unesc "${'$'}mp")
    # iter51 §3.4(2): корень '/' — особый случай. Шаблон "${'$'}rmp"/* при rmp=/ образует
    # `//*`, который НЕ обязан совпасть с `/data/...`, поэтому такой mount раньше либо терялся,
    # либо (как самый длинный из совпавших) подменял настоящий. Сравниваем нормализованный путь.
    case "${'$'}rmp" in
      /) case "${'$'}want" in /*) : ;; *) continue ;; esac ;;
      *) case "${'$'}want" in
           "${'$'}rmp"|"${'$'}rmp"/*) : ;;
           *) continue ;;
         esac ;;
    esac
    plen=${'$'}{#rmp}
    if [ "${'$'}plen" -gt "${'$'}best" ]; then
      best=${'$'}plen; brows=${'$'}(mi_unesc "${'$'}root"); bmm=${'$'}mm; bmp=${'$'}rmp
    fi
  done < "${'$'}MI"
  [ "${'$'}best" -gt 0 ] || return 1
  # Отрезаем от пути ТОТ mount point, по которому выбрали best, а не последний из таблицы:
  # иначе следующая строка с чужой точкой монтирования сдвигает rel и root считается неверно.
  if [ "${'$'}bmp" = "/" ]; then
    # root самой ФС = "/" → остаток это весь путь; при brows=/ результат тоже "/data/..."
    rel=${'$'}{want}
  else
    rel=${'$'}{want#"${'$'}bmp"}
  fi
  [ -n "${'$'}rel" ] || rel=""
  case "${'$'}brows" in
    */) case "${'$'}rel" in /*) rel=${'$'}{rel#/} ;; esac ;;
    *)  case "${'$'}rel" in /*) : ;; *) rel="/${'$'}rel" ;; esac ;;
  esac
  printf '%s\n' "${'$'}brows${'$'}rel"
  return 0
}
""".trimIndent()

    /**
     * Скрипт приведения целей к нашему staging-файлу.
     *
     * Печатает: `MOUNT_NS …`, `MOUNT_NS_MISMATCH …`, `MOUNT_TABLE …`, `MOUNT_ORDER …`,
     * `EXPECTED_ROOT …`, `FOREIGN_MOUNT:…`, `UMOUNTED_OK:…`, `UMOUNT_DETACHED:…`,
     * `UMOUNT_FAILED:…`, `TARGET_SKIP:…`, `MOUNT_FAIL:…`,
     * `MOUNT_VERIFIED:<target>|stage mid=… root=… dev_ino=…` (слой подтверждён),
     * `MOUNT_FACT:<target>|stage state=verified|unverified|unknown …` (факт для реестра —
     * печатается ВСЕГДА после успешного `mount`), `MOUNT_VERIFY_FAIL:…`,
     * `MOUNT_VERIFY_UNKNOWN:…`, `OWN_LAYERS_TOTAL=<n>`.
     */
    fun bindScript(stagePath: String): String {
        val stage = shQuote(stagePath)
        val sb = StringBuilder()
        sb.append(commonPrologue())
        sb.append("\n")
        // Каталог целей: значение по умолчанию — боевой; в тестах переопределяется окружением,
        // чтобы стенд не трогал /vendor/etc/carlock на машине разработчика.
        sb.append("TARGET_DIR=${'$'}{TARGET_DIR:-${TARGET_DIR}}\n")
        sb.append("STAGE_PATH=$stage\n")
        // ВАЖНО: `stage` уже в одинарных кавычках (shQuote). Подставлять его внутрь "..."
        // нельзя — одинарные кавычки станут частью пути. Дальше используем $STAGE_PATH.
        sb.append("STAGE_DEV_INO=\$(stat -c '%d:%i' \"\$STAGE_PATH\" 2>/dev/null)\n")
        sb.append("echo \"STAGE_IDENT inode=\${STAGE_DEV_INO:-unknown} path_len=\${#STAGE_PATH}\"\n")
        sb.append("EXPECTED_ROOT=\$(expected_root_for \"\$STAGE_PATH\" 2>/dev/null)\n")
        sb.append("echo \"EXPECTED_ROOT value=\${EXPECTED_ROOT:-unknown} method=longest_mount_prefix\"\n")
        sb.append("echo \"MOUNT_ORDER method=max_mount_id top_guaranteed=yes_within_table\"\n")
        sb.append(
            """
# Снять ТОЛЬКО свои слои на цели. Возврат: 0 — своих не осталось; 3 — сверху чужой/неопознанный
# слой; 4 — снятие не подтверждено; 5 — предел попыток; 6 — таблица/строка не читаются.
drop_own_layers() {
  t="${'$'}1"; n=0
  while [ "${'$'}n" -lt $MAX_LAYERS_PER_TARGET ]; do
    n=${'$'}((n+1))
    top=${'$'}(mi_top "${'$'}t")
    if [ -z "${'$'}top" ]; then echo "OWN_LAYERS:absent:${'$'}t"; return 0; fi
    mi_split "${'$'}top" || { echo "OWN_LAYERS:bad:${'$'}t line=«${'$'}top»"; return 6; }
    # Владение: корень слоя обязан совпасть с ожидаемым root нашего staging-источника.
    # Литеральный префикс сам по себе доказательством не считается (чужой bind может иметь
    # похожий корень), а несовпадение — это чужой слой, его НЕ снимаем.
    if [ -z "${'$'}EXPECTED_ROOT" ]; then
      case "${'$'}ROOT" in ${'$'}STAGE_PATH) : ;; *) echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}MID root=${'$'}ROOT reason=ownership_not_proven expected=unknown"; return 3 ;; esac
    else
      [ "${'$'}ROOT" = "${'$'}EXPECTED_ROOT" ] || { echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}MID root=${'$'}ROOT expected=${'$'}EXPECTED_ROOT reason=ownership_not_proven"; return 3; }
    fi
    echo "OWN_PROOF:${'$'}t method=root mid=${'$'}MID root=${'$'}ROOT"
    before=${'$'}(mi_count "${'$'}t")
    umount "${'$'}t" 2>/dev/null; rc=${'$'}?
    after=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc" = "0" ] && [ "${'$'}after" -lt "${'$'}before" ]; then
      echo "UMOUNTED_OK:${'$'}t mid=${'$'}MID root=${'$'}ROOT"; continue
    fi
    umount -l "${'$'}t" 2>/dev/null; rc2=${'$'}?
    after2=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc2" = "0" ] && [ "${'$'}after2" -lt "${'$'}before" ]; then
      echo "UMOUNT_DETACHED:${'$'}t mid=${'$'}MID before=${'$'}before after=${'$'}after2 reason=lazy_table_entry_may_remain"
      continue
    fi
    echo "UMOUNT_FAILED:${'$'}t mid=${'$'}MID rc=${'$'}rc lazy_rc=${'$'}rc2 before=${'$'}before after=${'$'}after2"
    return 4
  done
  echo "UMOUNT_INCOMPLETE:${'$'}t tried=$MAX_LAYERS_PER_TARGET"
  return 5
}

MOUNTED_COUNT=0
SKIPPED_COUNT=0
UNKNOWN_COUNT=0
if [ "${'$'}NS_OK" = "1" ] && [ -s "${'$'}STAGE_PATH" ]; then
  for f in "${'$'}TARGET_DIR"/*.wav; do
    [ -f "${'$'}f" ] || continue
    drop_own_layers "${'$'}f"; rm_rc=${'$'}?
    if [ "${'$'}rm_rc" != "0" ]; then
      case "${'$'}rm_rc" in
        3) echo "TARGET_SKIP:${'$'}f reason=foreign_layer_present rc=3" ;;
        6|4|5) echo "TARGET_SKIP:${'$'}f reason=own_layers_not_removed rc=${'$'}rm_rc" ;;
        *) echo "TARGET_SKIP:${'$'}f reason=own_layers_not_removed rc=${'$'}rm_rc" ;;
      esac
      SKIPPED_COUNT=${'$'}((SKIPPED_COUNT+1)); continue
    fi
    # mount выполняется без || true: rc запоминается, а не теряется.
    mount -o bind "${'$'}STAGE_PATH" "${'$'}f" 2>/dev/null; mrc=${'$'}?
    if [ "${'$'}mrc" != "0" ]; then
      echo "MOUNT_FAIL:${'$'}f rc=${'$'}mrc"
      UNKNOWN_COUNT=${'$'}((UNKNOWN_COUNT+1)); continue
    fi
    # Слой может появиться в таблице не мгновенно: до трёх перечитываний.
    top=""; i=0
    while [ "${'$'}i" -lt 3 ]; do
      top=${'$'}(mi_top "${'$'}f")
      [ -n "${'$'}top" ] && break
      i=${'$'}((i+1)); sleep 0.1 2>/dev/null || true
    done
    tgt_di=${'$'}(stat -c '%d:%i' "${'$'}f" 2>/dev/null)
    vk="verified"; vwhy=""
    if [ -z "${'$'}top" ]; then
      vk="unknown"; vwhy="layer_not_listed"
    elif ! mi_split "${'$'}top"; then
      vk="unknown"; vwhy="malformed_mountinfo"
    elif [ -n "${'$'}EXPECTED_ROOT" ] && [ "${'$'}ROOT" != "${'$'}EXPECTED_ROOT" ]; then
      vk="unverified"; vwhy="root_mismatch"
    elif [ -z "${'$'}STAGE_DEV_INO" ] || [ -z "${'$'}tgt_di" ]; then
      vk="unknown"; vwhy="stat_unavailable"
    elif [ "${'$'}STAGE_DEV_INO" != "${'$'}tgt_di" ]; then
      vk="unverified"; vwhy="dev_ino_mismatch"
    fi
    # ФАКТ печатается ВСЕГДА после успешного mount — даже если проверка не удалась.
    # Именно по нему вызывающая сторона пишет реестр: слой существует, мы обязаны его помнить.
    echo "MOUNT_FACT:${'$'}f|$stagePath state=${'$'}vk mid=${'$'}{MID:-none} root=${'$'}{ROOT:-none} dev_ino=${'$'}tgt_di reason=${'$'}{vwhy:-none} mount_rc=0"
    if [ "${'$'}vk" = "verified" ]; then
      echo "MOUNT_VERIFIED:${'$'}f|$stagePath mid=${'$'}MID root=${'$'}ROOT dev_ino=${'$'}tgt_di"
      echo "MOUNTED_OK:${'$'}f|$stagePath mid=${'$'}MID inode=${'$'}tgt_di"
      MOUNTED_COUNT=${'$'}((MOUNTED_COUNT+1))
    elif [ "${'$'}vk" = "unknown" ]; then
      echo "MOUNT_VERIFY_UNKNOWN:${'$'}f reason=${'$'}vwhy mid=${'$'}{MID:-none} dev_ino=${'$'}tgt_di"
      UNKNOWN_COUNT=${'$'}((UNKNOWN_COUNT+1))
    else
      echo "MOUNT_VERIFY_FAIL:${'$'}f reason=${'$'}vwhy mid=${'$'}{MID:-none} root=${'$'}{ROOT:-none} expected=${'$'}{EXPECTED_ROOT:-unknown} dev_ino=${'$'}tgt_di stage=${'$'}{STAGE_DEV_INO:-unknown}"
      UNKNOWN_COUNT=${'$'}((UNKNOWN_COUNT+1))
    fi
    restorecon -v "${'$'}f" 2>/dev/null || true
  done
else
  [ "${'$'}NS_OK" = "1" ] || echo "BIND_SKIP reason=namespace_differs"
  [ -s "${'$'}STAGE_PATH" ] || echo "BIND_SKIP reason=stage_missing"
fi
SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
echo "SELINUX before=${'$'}{SELINUX_BEFORE:-unknown} after=${'$'}{SELINUX_AFTER:-unknown}"
echo "MOUNTED_OK_COUNT=${'$'}MOUNTED_COUNT TARGET_SKIPPED=${'$'}SKIPPED_COUNT MOUNT_UNKNOWN=${'$'}UNKNOWN_COUNT"
echo "OWN_LAYERS_TOTAL=${'$'}(awk '${'$'}4 ~ /^\// && ${'$'}5 ~ /^\/vendor\/etc\/carlock/ {c++} END {print c+0}' "${'$'}MI" 2>/dev/null)"
""".trimIndent()
        )
        return sb.toString()
    }

    /**
     * Скрипт снятия НАШИХ слоёв по реестру.
     *
     * @param entries пары «цель → staging-источник», записанные в реестре. Источник нужен, чтобы
     *   вычислить ОЖИДАЕМЫЙ root по фактической таблице, а не сравнивать с догадкой.
     *
     * Печатает по цели `STOCK_OK:<t>` (наших слоёв нет), `STOCK_FOREIGN_LEFT:<t> layers=<n>`
     * (наши сняты/не сняты, сверху чужой) или `STOCK_UNPROVEN:<t> reason=…`.
     */
    fun restoreScript(entries: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append(commonPrologue())
        sb.append("\n")
        sb.append("echo \"MOUNT_ORDER method=max_mount_id top_guaranteed=yes_within_table\"\n")
        sb.append("STAGE_PREFIX=${shQuote(STAGE_PREFIX)}\n")
        // iter51 §3.4(1): строгий барьер namespace В ЭТОЙ ЖЕ shell-операции и ДО любого side
        // effect. Отдельная предварительная команда (`NS_PRE` в LockSound) доказывает namespace
        // только СВОЕГО вызова: следующая команда может попасть в другой namespace.
        sb.append(
            """
if [ "${'$'}NS_OK" != "1" ]; then
  echo "RESTORE_BLOCKED reason=namespace_not_proven self=${'$'}{NS_SELF:-unknown} init=${'$'}{NS_INIT:-unknown} action=none"
  echo "OWN_LAYERS_TOTAL=${'$'}(awk '${'$'}4 ~ /^\\// && ${'$'}5 ~ /^\\/vendor\\/etc\\/carlock/ {c++} END {print c+0}' "${'$'}MI" 2>/dev/null)"
  exit 0
fi
if [ ! -r "${'$'}MI" ]; then
  echo "RESTORE_BLOCKED reason=mountinfo_unreadable path=${'$'}MI action=none"
  exit 0
fi
""".trimIndent()
        )
        sb.append("\n")
        sb.append(
            """
# Снять свои слои на цели с ОЖИДАЕМЫМ root источника. 0 — своих нет, 3 — чужой сверху,
# 4 — не подтверждено, 5 — предел, 6 — таблица/строка не читаются.
drop_own_layers() {
  t="${'$'}1"; want="${'$'}2"; n=0
  exp=""
  if [ -n "${'$'}want" ]; then exp=${'$'}(expected_root_for "${'$'}want" 2>/dev/null); fi
  echo "RESTORE_EXPECTED target=${'$'}t source=${'$'}{want:-none} root=${'$'}{exp:-unknown}"
  while [ "${'$'}n" -lt $MAX_LAYERS_PER_TARGET ]; do
    n=${'$'}((n+1))
    top=${'$'}(mi_top "${'$'}t")
    if [ -z "${'$'}top" ]; then return 0; fi
    mi_split "${'$'}top" || { echo "RESTORE_BAD_LAYER:${'$'}t line=«${'$'}top»"; return 6; }
    if [ -n "${'$'}exp" ]; then
      [ "${'$'}ROOT" = "${'$'}exp" ] || { echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}MID root=${'$'}ROOT expected=${'$'}exp reason=ownership_not_proven"; return 3; }
    else
      case "${'$'}ROOT" in
        ${'$'}{STAGE_PREFIX}*) echo "OWN_PROOF:${'$'}t method=prefix root=${'$'}ROOT expected=unknown reason=root_expected_uncomputable" ;;
        *) echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}MID root=${'$'}ROOT reason=ownership_not_proven"; return 3 ;;
      esac
    fi
    before=${'$'}(mi_count "${'$'}t")
    umount "${'$'}t" 2>/dev/null; rc=${'$'}?
    after=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc" = "0" ] && [ "${'$'}after" -lt "${'$'}before" ]; then
      echo "UMOUNTED_OK:${'$'}t mid=${'$'}MID"; continue
    fi
    umount -l "${'$'}t" 2>/dev/null; rc2=${'$'}?
    after2=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc2" = "0" ] && [ "${'$'}after2" -lt "${'$'}before" ]; then
      echo "UMOUNT_DETACHED:${'$'}t mid=${'$'}MID after=${'$'}after2 reason=lazy_table_entry_may_remain"; continue
    fi
    echo "UMOUNT_FAILED:${'$'}t mid=${'$'}MID rc=${'$'}rc lazy_rc=${'$'}rc2"; return 4
  done
  return 5
}

""".trimIndent()
        )
        sb.append("\n")
        for ((t, src) in entries) {
            val q = shQuote(t)
            val qs = shQuote(src)
            sb.append("drop_own_layers $q $qs; rc=\$?\n")
            sb.append("left=\$(mi_count $q)\n")
            sb.append("if [ \"\$left\" = \"0\" ] && [ \"\$rc\" = \"0\" ]; then\n")
            sb.append("  echo \"STOCK_OK:$t\"\n")
            sb.append("elif [ \"\$left\" = \"0\" ]; then\n")
            sb.append("  echo \"STOCK_UNPROVEN:$t reason=umount_not_confirmed rc=\$rc\"\n")
            sb.append("elif [ \"\$rc\" = \"3\" ]; then\n")
            sb.append("  echo \"STOCK_FOREIGN_LEFT:$t layers=\$left reason=foreign_layer_above\"\n")
            sb.append("else\n")
            sb.append("  echo \"STOCK_UNPROVEN:$t reason=layers_left rc=\$rc layers=\$left\"\n")
            sb.append("fi\n")
        }
        sb.append("echo \"OWN_LAYERS_TOTAL=\$(awk '\$4 ~ /^\\// && \$5 ~ /^\\/vendor\\/etc\\/carlock/ {c++} END {print c+0}' \"\$MI\" 2>/dev/null)\"\n")
        return sb.toString()
    }

    /** Одинарные кавычки для путей: внутри пути апострофов быть не должно (проверяется). */
    fun shQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
