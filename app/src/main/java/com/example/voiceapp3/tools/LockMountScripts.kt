package com.example.voiceapp3.tools

/**
 * iter48 §В — shell-скрипты монтирования/снятия звука замка, вынесенные из `LockSound`.
 *
 * Что исправлено относительно iter47 (подтверждено кодом):
 *  1. Владение слоем определялось по ПЕРВОМУ полю `/proc/mounts` — это устройство ФС, а не путь
 *     bind-источника. Свой слой не распознавался никогда, а чужой мог быть принят за «наш».
 *     Теперь принадлежность доказывается по `/proc/self/mountinfo`: mount ID, root (для bind —
 *     путь источника), точка монтирования.
 *  2. `umount … || true` закрывал провал: успех заявлялся независимо от результата. Теперь rc
 *     фиксируется, после каждой попытки перечитывается mountinfo, и только уменьшение числа
 *     слоёв считается снятием. `umount -l` помечается как ОТЛОЖЕННОЕ снятие (запись в таблице
 *     остаётся, пока держатся ссылки) — это не «снято».
 *  3. На чужой верхний слой не монтируем: иначе мы «затеняем» чужую подмену и ломаем чужую
 *     функцию. Такой путь честно пропускается (`TARGET_SKIP reason=foreign_layer_present`).
 *  4. Пространство монтирования: если `/proc/self/ns/mnt` ≠ `/proc/1/ns/mnt`, bind из нашего
 *     пространства имён НЕ виден системе, и «применено» заявлять нельзя. Скрипт этого не делает
 *     и печатает `MOUNT_NS_MISMATCH`.
 *
 * Что здесь по-прежнему НЕ доказывается: слышимость звука. Факт «слой смонтирован и читается по
 * пути» — не факт «машина играет этот файл»; это проверяет только владелец.
 */
internal object LockMountScripts {

    /** Единственный каталог, который приложение вправе трогать (AVAS/`/system/etc/avas` — нет). */
    const val TARGET_DIR = "/vendor/etc/carlock"

    /** Наш staging-префикс = метка владения слоем. */
    val STAGE_PREFIX: String get() = MountInfo.STAGE_PREFIX

    private const val MAX_LAYERS_PER_TARGET = 6

    /**
     * Скрипт приведения целей к нашему staging-файлу.
     *
     * Печатает: `MOUNT_NS …`, `MOUNT_NS_MISMATCH …`, `FOREIGN_MOUNT:…`, `UMOUNTED_OK:…`,
     * `UMOUNT_DETACHED:…`, `UMOUNT_FAILED:…`, `TARGET_SKIP:…`, `MOUNT_FAIL:…`,
     * `MOUNTED_OK:<target>|<stage> mid=… inode=…`, `MOUNT_VERIFY_FAIL:…`, `OWN_LAYERS_TOTAL=<n>`.
     */
    fun bindScript(stagePath: String): String {
        val stage = shQuote(stagePath)
        return """
set +e
SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
NS_SELF=${'$'}(readlink /proc/self/ns/mnt 2>/dev/null)
NS_INIT=${'$'}(readlink /proc/1/ns/mnt 2>/dev/null)
echo "MOUNT_NS self=${'$'}{NS_SELF:-unknown} init=${'$'}{NS_INIT:-unknown}"
NS_OK=0
[ -n "${'$'}NS_SELF" ] && [ -n "${'$'}NS_INIT" ] && [ "${'$'}NS_SELF" = "${'$'}NS_INIT" ] && NS_OK=1
[ "${'$'}NS_OK" = "1" ] || echo "MOUNT_NS_MISMATCH self=${'$'}{NS_SELF:-unknown} init=${'$'}{NS_INIT:-unknown} action=none reason=namespace_differs"

mi_layers() { awk -v t="${'$'}1" '${'$'}5==t {print ${'$'}1"|"${'$'}4}' /proc/self/mountinfo 2>/dev/null; }
mi_count() { mi_layers "${'$'}1" | wc -l | tr -d ' '; }
mi_top() { mi_layers "${'$'}1" | tail -1; }

# Снять ТОЛЬКО свои слои на цели. Возврат: 0 — своих не осталось; 3 — сверху чужой слой;
# 4 — снятие не подтверждено; 5 — предел попыток.
drop_own_layers() {
  t="${'$'}1"; n=0
  while [ "${'$'}n" -lt $MAX_LAYERS_PER_TARGET ]; do
    n=${'$'}((n+1))
    top=${'$'}(mi_top "${'$'}t")
    if [ -z "${'$'}top" ]; then echo "OWN_LAYERS:absent:${'$'}t"; return 0; fi
    mid=${'$'}{top%%|*}; root=${'$'}{top#*|}
    case "${'$'}root" in
      /data/local/tmp/carlock_custom*) : ;;
      *) echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}mid root=${'$'}root";
         return 3 ;;
    esac
    before=${'$'}(mi_count "${'$'}t")
    umount "${'$'}t" 2>/dev/null; rc=${'$'}?
    after=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc" = "0" ] && [ "${'$'}after" -lt "${'$'}before" ]; then
      echo "UMOUNTED_OK:${'$'}t mid=${'$'}mid root=${'$'}root"; continue
    fi
    umount -l "${'$'}t" 2>/dev/null; rc2=${'$'}?
    after2=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc2" = "0" ] && [ "${'$'}after2" -lt "${'$'}before" ]; then
      echo "UMOUNT_DETACHED:${'$'}t mid=${'$'}mid after=${'$'}after2 reason=lazy_table_entry_may_remain"
      continue
    fi
    echo "UMOUNT_FAILED:${'$'}t mid=${'$'}mid rc=${'$'}rc lazy_rc=${'$'}rc2 before=${'$'}before after=${'$'}after2"
    return 4
  done
  echo "UMOUNT_INCOMPLETE:${'$'}t tried=$MAX_LAYERS_PER_TARGET"
  return 5
}

MOUNTED_COUNT=0
SKIPPED_COUNT=0
if [ "${'$'}NS_OK" = "1" ] && [ -s $stage ]; then
  STAGE_INODE=${'$'}(stat -c '%i' $stage 2>/dev/null)
  for f in $TARGET_DIR/*.wav; do
    [ -f "${'$'}f" ] || continue
    drop_own_layers "${'$'}f"; rm_rc=${'$'}?
    if [ "${'$'}rm_rc" != "0" ]; then
      echo "TARGET_SKIP:${'$'}f reason=own_layers_not_removed rc=${'$'}rm_rc"
      SKIPPED_COUNT=${'$'}((SKIPPED_COUNT+1)); continue
    fi
    left=${'$'}(mi_count "${'$'}f")
    if [ "${'$'}left" != "0" ]; then
      echo "TARGET_SKIP:${'$'}f reason=foreign_layer_present layers=${'$'}left"
      SKIPPED_COUNT=${'$'}((SKIPPED_COUNT+1)); continue
    fi
    mount -o bind $stage "${'$'}f" 2>&1 || echo "MOUNT_FAIL:${'$'}f rc=${'$'}?"
    top=${'$'}(mi_top "${'$'}f"); mid=${'$'}{top%%|*}; mroot=${'$'}{top#*|}
    tgt_inode=${'$'}(stat -c '%i' "${'$'}f" 2>/dev/null)
    if [ -n "${'$'}top" ] && [ "${'$'}mroot" = $stage ] && [ -n "${'$'}STAGE_INODE" ] &&
       [ "${'$'}STAGE_INODE" = "${'$'}tgt_inode" ]; then
      echo "MOUNTED_OK:${'$'}f|$stagePath mid=${'$'}mid inode=${'$'}tgt_inode"
      MOUNTED_COUNT=${'$'}((MOUNTED_COUNT+1))
    else
      echo "MOUNT_VERIFY_FAIL:${'$'}f mid=${'$'}{mid:-none} root=${'$'}{mroot:-none} inode=${'$'}tgt_inode"
    fi
    restorecon -v "${'$'}f" 2>/dev/null || true
  done
else
  [ "${'$'}NS_OK" = "1" ] || echo "BIND_SKIP reason=namespace_differs"
  [ -s $stage ] || echo "BIND_SKIP reason=stage_missing"
fi
SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
echo "SELINUX before=${'$'}{SELINUX_BEFORE:-unknown} after=${'$'}{SELINUX_AFTER:-unknown}"
echo "MOUNTED_OK_COUNT=${'$'}MOUNTED_COUNT TARGET_SKIPPED=${'$'}SKIPPED_COUNT"
echo "OWN_LAYERS_TOTAL=${'$'}(awk '${'$'}4 ~ /^\/data\/local\/tmp\/carlock_custom/ {c++} END {print c+0}' /proc/self/mountinfo 2>/dev/null)"
""".trimIndent()
    }

    /**
     * Скрипт снятия НАШИХ слоёв по реестру.
     *
     * Для каждого пути: снимаем только слои с нашим корнем, каждый раз перечитывая mountinfo;
     * чужой слой не трогаем. Печатает по пути `STOCK_OK:<t>` (наших слоёв нет),
     * `STOCK_FOREIGN_LEFT:<t> layers=<n>` (наши сняты, сверху чужой) или
     * `STOCK_UNPROVEN:<t> reason=…` (доказать снятие нельзя).
     */
    fun restoreScript(targets: List<String>): String {
        val sb = StringBuilder()
        sb.append("set +e\n")
        sb.append("NS_SELF=\$(readlink /proc/self/ns/mnt 2>/dev/null)\n")
        sb.append("NS_INIT=\$(readlink /proc/1/ns/mnt 2>/dev/null)\n")
        sb.append("echo \"MOUNT_NS self=\${NS_SELF:-unknown} init=\${NS_INIT:-unknown}\"\n")
        sb.append("mi_layers() { awk -v t=\"\$1\" '\$5==t {print \$1\"|\"\$4}' /proc/self/mountinfo 2>/dev/null; }\n")
        sb.append("mi_count() { mi_layers \"\$1\" | wc -l | tr -d ' '; }\n")
        sb.append("mi_top() { mi_layers \"\$1\" | tail -1; }\n")
        sb.append(
            """
drop_own_layers() {
  t="${'$'}1"; n=0
  while [ "${'$'}n" -lt $MAX_LAYERS_PER_TARGET ]; do
    n=${'$'}((n+1))
    top=${'$'}(mi_top "${'$'}t")
    if [ -z "${'$'}top" ]; then return 0; fi
    mid=${'$'}{top%%|*}; root=${'$'}{top#*|}
    case "${'$'}root" in
      /data/local/tmp/carlock_custom*) : ;;
      *) echo "FOREIGN_MOUNT:${'$'}t mid=${'$'}mid root=${'$'}root"; return 3 ;;
    esac
    before=${'$'}(mi_count "${'$'}t")
    umount "${'$'}t" 2>/dev/null; rc=${'$'}?
    after=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc" = "0" ] && [ "${'$'}after" -lt "${'$'}before" ]; then
      echo "UMOUNTED_OK:${'$'}t mid=${'$'}mid"; continue
    fi
    umount -l "${'$'}t" 2>/dev/null; rc2=${'$'}?
    after2=${'$'}(mi_count "${'$'}t")
    if [ "${'$'}rc2" = "0" ] && [ "${'$'}after2" -lt "${'$'}before" ]; then
      echo "UMOUNT_DETACHED:${'$'}t mid=${'$'}mid after=${'$'}after2"; continue
    fi
    echo "UMOUNT_FAILED:${'$'}t mid=${'$'}mid rc=${'$'}rc lazy_rc=${'$'}rc2"; return 4
  done
  return 5
}
""".trimIndent()
        )
        sb.append("\n")
        for (t in targets) {
            val q = shQuote(t)
            sb.append("drop_own_layers $q; rc=\$?\n")
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
        sb.append("echo \"OWN_LAYERS_TOTAL=\$(awk '\$4 ~ /^\\/data\\/local\\/tmp\\/carlock_custom/ {c++} END {print c+0}' /proc/self/mountinfo 2>/dev/null)\"\n")
        return sb.toString()
    }

    /** Одинарные кавычки для путей: внутри пути апострофов быть не должно (проверяется). */
    fun shQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
