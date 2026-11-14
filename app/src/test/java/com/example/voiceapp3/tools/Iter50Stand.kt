package com.example.voiceapp3.tools

import java.io.File

/**
 * iter50 §5.1/§4.1 — стенд для СГЕНЕРИРОВАННЫХ production-скриптов: настоящий `/bin/sh`,
 * подставные `iptables`/`ip6tables` (строгие к argv, с настоящей таблицей правил),
 * `mount`/`umount`/`stat` и управляемая таблица `mountinfo` (production-скрипт читает её по
 * переменной `MOUNTINFO`, в бою это `/proc/self/mountinfo`).
 *
 * Зачем: прежние тесты проверяли ТЕКСТ скрипта. Оба дефекта iter50 (склеенный argv в `-D` и
 * неверное разделение полей mountinfo) текстом не ловятся — их видно только при исполнении.
 *
 * Чего стенд НЕ доказывает: поведение ядра ГУ, реальный формат её mountinfo, поддержку target
 * `TTL`, видимость слоя в namespace аудиопроцесса и слышимость звука.
 */
class Iter50Stand {

    val dir: File = File.createTempFile("it50", "").let { it.delete(); it.mkdirs(); it }
    private val binDir: File = File(dir, "bin").apply { mkdirs() }
    private val stateDir: File = File(dir, "state").apply { mkdirs() }

    /** Таблица монтирования, которую увидит production-скрипт. */
    val mountinfo: File = File(dir, "mountinfo").apply { writeText("") }

    private val argvLog: File = File(stateDir, "argv.log")
    private val mountLog: File = File(stateDir, "mount.log")

    private val sh: String = listOf("/bin/sh", "/usr/bin/sh", "sh")
        .firstOrNull { File(it).canExecute() } ?: throw IllegalStateException("нет /bin/sh")

    private val fakeIptables = """
        printf 'ME=%s' "${'$'}(basename "${'$'}0")" >> "${'$'}ARGV_LOG"
        for a in ${'$'}@; do printf ' <%s>' "${'$'}a" >> "${'$'}ARGV_LOG"; done
        printf '\n' >> "${'$'}ARGV_LOG"
        for a in ${'$'}@; do
          case "${'$'}a" in
            *" "*|*"	"*) echo "GLUED_ARGV:<${'$'}a>" >&2; exit 90 ;;
          esac
        done
        RULES="${'$'}STATE/table.${'$'}(basename "${'$'}0")"
        CHAINS="${'$'}STATE/chains.${'$'}(basename "${'$'}0")"
        touch "${'$'}RULES" "${'$'}CHAINS"
        [ -f "${'$'}STATE/fail_read.${'$'}(basename "${'$'}0")" ] && { echo "can't initialize iptables table" >&2; exit 3; }
        args=""
        while [ ${'$'}# -gt 0 ]; do
          case "${'$'}1" in
            -w) shift; case "${'$'}1" in -*) ;; *) shift ;; esac ;;
            -t|-n) shift; case "${'$'}1" in -*) ;; *) shift ;; esac ;;
            *) args="${'$'}args|${'$'}1"; shift ;;
          esac
        done
        oldifs=${'$'}IFS; IFS='|'
        set -- ${'$'}args
        IFS=${'$'}oldifs
        shift  # пустой первый элемент
        cmd=${'$'}1; shift
        chain=""; [ ${'$'}# -gt 0 ] && { chain=${'$'}1; shift; }
        rest=${'$'}*
        case "${'$'}cmd" in
          -S)
            echo "-P POSTROUTING ACCEPT"
            if [ -z "${'$'}chain" ] || [ "${'$'}chain" = "POSTROUTING" ]; then
              grep '^POSTROUTING ' "${'$'}RULES" 2>/dev/null | sed 's/^POSTROUTING /-A POSTROUTING /'
            elif grep -qx "${'$'}chain" "${'$'}CHAINS" 2>/dev/null; then
              echo "-N ${'$'}chain"; grep "^${'$'}chain " "${'$'}RULES" 2>/dev/null | sed "s/^${'$'}chain /-A ${'$'}chain /"
            else
              echo "No chain/target/match by that name." >&2; exit 1
            fi ;;
          -N) grep -qx "${'$'}chain" "${'$'}CHAINS" 2>/dev/null && { echo "Chain already exists." >&2; exit 1; }
              echo "${'$'}chain" >> "${'$'}CHAINS" ;;
          -F) if [ -n "${'$'}chain" ]; then grep -qx "${'$'}chain" "${'$'}CHAINS" || exit 1
                grep -v "^${'$'}chain " "${'$'}RULES" > "${'$'}RULES.t" 2>/dev/null; mv "${'$'}RULES.t" "${'$'}RULES"
              else : > "${'$'}RULES"; fi ;;
          -X) if [ -n "${'$'}chain" ]; then grep -qx "${'$'}chain" "${'$'}CHAINS" || exit 1
                grep -qE -- "-(j|g) ${'$'}chain( |$)" "${'$'}RULES" && { echo "Too many links." >&2; exit 2; }
                grep -v "^${'$'}chain$" "${'$'}CHAINS" > "${'$'}CHAINS.t"; mv "${'$'}CHAINS.t" "${'$'}CHAINS"
                grep -v "^${'$'}chain " "${'$'}RULES" > "${'$'}RULES.t"; mv "${'$'}RULES.t" "${'$'}RULES"
              fi ;;
          -A|-I) if [ "${'$'}chain" != "POSTROUTING" ]; then grep -qx "${'$'}chain" "${'$'}CHAINS" || { echo "No chain" >&2; exit 1; }; fi
                 if [ -f "${'$'}STATE/fail_add.${'$'}(basename "${'$'}0")" ]; then echo "Resource temporarily unavailable" >&2; exit 4; fi
                 echo "${'$'}chain ${'$'}rest" >> "${'$'}RULES" ;;
          -D) [ -f "${'$'}STATE/fail_del.${'$'}(basename "${'$'}0")" ] && { echo "Bad rule (does a matching rule exist in that chain?)." >&2; exit 1; }
              line="${'$'}chain ${'$'}rest"
              grep -qxF -- "${'$'}line" "${'$'}RULES" || { echo "Bad rule (does a matching rule exist in that chain?)." >&2; exit 1; }
              awk -v l="${'$'}line" 'BEGIN{d=0} { if(!d && ${'$'}0==l){d=1; next} print }' "${'$'}RULES" > "${'$'}RULES.t"
              mv "${'$'}RULES.t" "${'$'}RULES" ;;
          -C) line="${'$'}chain ${'$'}rest"; grep -qxF -- "${'$'}line" "${'$'}RULES" ;;
          *) exit 1 ;;
        esac
        exit 0
    """.trimIndent()

    init {
        argvLog.writeText("")
        mountLog.writeText("")
        put("iptables", fakeIptables)
        put("ip6tables", fakeIptables)

        // mount -o bind SRC TARGET — как ядро: root слоя = root ФС источника + остаток пути
        // (самый длинный покрывающий mount из ТАБЛИЦЫ, а не догадка). Побочно пишем
        // $STATE/binds (цель → источник), чтобы `stat` отдал цели identity источника.
        put("mount", """
            echo "mount ${'$'}*" >> "${'$'}MOUNT_LOG"
            src=""; tgt=""
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in
                -o) shift; shift ;;
                -*) shift ;;
                *) if [ -z "${'$'}src" ]; then src=${'$'}1; else tgt=${'$'}1; fi; shift ;;
              esac
            done
            [ -n "${'$'}tgt" ] || exit 1
            if [ "${'$'}MOUNT_FAIL" = "1" ]; then exit 32; fi
            root=${'$'}(awk -v s="${'$'}src" '
              { mp = ${'$'}5; r = ${'$'}4; l = length(mp)
                if (mp == "/" || s == mp || index(s, mp "/") == 1) {
                  if (l > best) { best = l; br = r; bmp = mp }
                } }
              END {
                if (best == 0) { print s; exit }
                rel = substr(s, best + 1)
                if (br ~ /\/${'$'}/) { if (substr(rel,1,1) == "/") rel = substr(rel, 2) }
                else if (substr(rel,1,1) != "/") rel = "/" rel
                print br rel
              }' "${'$'}MOUNTINFO")
            [ -n "${'$'}root" ] || root="${'$'}src"
            printf '%s %s\n' "${'$'}tgt" "${'$'}src" >> "${'$'}STATE/binds"
            if [ "${'$'}HIDE_LAYER" = "1" ]; then exit 0; fi
            n=${'$'}(wc -l < "${'$'}MOUNTINFO" | tr -d ' ')
            id=${'$'}((1000 + n))
            echo "${'$'}id 1 8:1 ${'$'}root ${'$'}tgt rw - ext4 /dev/block/sda1 rw" >> "${'$'}MOUNTINFO"
            exit 0
        """.trimIndent())

        // umount [-l] TARGET: снимает запись таблицы и связь «цель → источник».
        put("umount", """
            echo "umount ${'$'}*" >> "${'$'}MOUNT_LOG"
            lazy=0
            for a in ${'$'}@; do [ "${'$'}a" = "-l" ] && lazy=1; done
            tgt=""
            for a in ${'$'}@; do case "${'$'}a" in -*) ;; *) tgt=${'$'}a ;; esac; done
            if [ "${'$'}UMOUNT_FAIL" = "1" ]; then exit 32; fi
            if ! grep -q " ${'$'}tgt " "${'$'}MOUNTINFO"; then exit 32; fi
            if [ "${'$'}lazy" = "1" ] && [ "${'$'}LAZY_KEEPS" = "1" ]; then exit 0; fi
            grep -v " ${'$'}tgt " "${'$'}MOUNTINFO" > "${'$'}MOUNTINFO.tmp"
            mv "${'$'}MOUNTINFO.tmp" "${'$'}MOUNTINFO"
            grep -v "^${'$'}tgt " "${'$'}STATE/binds" > "${'$'}STATE/binds.tmp" 2>/dev/null
            mv "${'$'}STATE/binds.tmp" "${'$'}STATE/binds"
            exit 0
        """.trimIndent())

        // stat -c '%d:%i'. Identity цели с нашим bind = identity ИСТОЧНИКА (как у ядра).
        // STAT_BROKEN=1 — stat недоступен; STAT_MISMATCH=1 — identity подменена.
        put("stat", """
            fmt="%d:%i"
            if [ "${'$'}1" = "-c" ]; then fmt=${'$'}2; shift 2; fi
            for p in ${'$'}@; do
              if [ "${'$'}fmt" != "%d:%i" ]; then echo "${'$'}p"; continue; fi
              [ "${'$'}STAT_BROKEN" = "1" ] && exit 1
              src=${'$'}(awk -v t="${'$'}p" '${'$'}1==t {print ${'$'}2; exit}' "${'$'}STATE/binds" 2>/dev/null)
              real=${'$'}{src:-${'$'}p}
              [ -f "${'$'}real" ] || exit 1
              if [ "${'$'}STAT_MISMATCH" = "1" ] && [ "${'$'}real" != "${'$'}p" ]; then printf '8:999999\n'; continue; fi
              printf '8:%s\n' "${'$'}(printf '%s' "${'$'}real" | cksum | awk '{print ${'$'}1}')"
            done
            exit 0
        """.trimIndent())

        for (h in listOf("awk", "grep", "sed", "rm", "mv", "cat", "wc", "tr", "sleep", "basename", "dirname", "cksum"))
            putPassthrough(h)
        // ip: модель сети. UPLINK_IFACE/EMPTY_ROUTE управляют сценарием.
        put("ip", """
            case "${'$'}1${'$'}2" in
              "routeget") if [ "${'$'}NO_ROUTE" = "1" ]; then echo ""; else echo "1.1.1.1 dev ${'$'}{UPLINK_IFACE:-wlan0} src 10.0.0.5 uid 0"; fi ;;
              "routeshow"|"route") if [ "${'$'}NO_ROUTE" = "1" ]; then : ; else echo "default via 10.0.0.1 dev ${'$'}{UPLINK_IFACE:-wlan0}"; fi ;;
              "link") printf '1: lo: <LOOPBACK>\n2: %s: <UP>\n' "${'$'}{UPLINK_IFACE:-wlan0}" ;;
              "-br"*) printf '%s UP 10.0.0.5/24\n' "${'$'}{UPLINK_IFACE:-wlan0}" ;;
              *) : ;;
            esac
            exit 0
        """.trimIndent())
        // readlink: модель «root-шелл уже в namespace аудио/init» — production-скрипт сравнивает
        // /proc/self/ns/mnt с /proc/1/ns/mnt. На хосте разработчика /proc/1 недоступен, поэтому
        // стенд отдаёт тот же идентификатор для обоих путей (NS_MISMATCH моделируется NS_DIFFERS=1).
        put("readlink", """
            for a in ${'$'}@; do
              case "${'$'}a" in
                */ns/mnt)
                  if [ "${'$'}NS_DIFFERS" = "1" ]; then
                    case "${'$'}a" in /proc/1/*) echo "mnt:[4026532000]" ;; *) echo "mnt:[4026531841]" ;; esac
                  else echo "mnt:[4026531841]"; fi ;;
                *) if [ -e "${'$'}a" ]; then echo "${'$'}a"; else exit 1; fi ;;
              esac
            done
            exit 0
        """.trimIndent())
        put("getenforce", "echo Enforcing")
        put("restorecon", "exit 0")
    }

    private fun put(name: String, body: String) {
        val f = File(binDir, name)
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
    }

    private fun putPassthrough(name: String) {
        val real = listOf("/bin/$name", "/usr/bin/$name", "/usr/local/bin/$name")
            .firstOrNull { File(it).canExecute() } ?: return
        put(name, "exec \"$real\" \"\$@\"")
    }

    /**
     * Модель iptables: таблица в `$STATE/table.<бинарник>`, журнал КАЖДОГО отдельного argv,
     * отказ на склеенном аргументе (`GLUED_ARGV`), возможность смоделировать отказ чтения.
     */

    private fun rulesOf(family: String) = File(stateDir, "table.$family").apply { if (!exists()) createNewFile() }
    private fun chainsOf(family: String) = File(stateDir, "chains.$family").apply { if (!exists()) createNewFile() }

    /** Подготовить правило в «ядре» (family = `iptables` | `ip6tables`). */
    fun seedRule(family: String, chain: String, spec: String) {
        rulesOf(family).appendText("$chain $spec\n")
        if (chain != "POSTROUTING" && !chainsOf(family).readLines().contains(chain))
            chainsOf(family).appendText("$chain\n")
    }

    /** Смоделировать отказ удаления правила (`-D`). */
    fun failDelete(family: String) { File(stateDir, "fail_del.$family").writeText("1") }

    /** Текущий набор правил family — для сравнения «до/после». */
    fun rules(family: String): List<String> =
        rulesOf(family).readLines().map { it.trim() }.filter { it.isNotEmpty() }

    fun argv(): List<String> = argvLog.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    fun mountCalls(): List<String> = mountLog.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    fun resetLogs() { argvLog.writeText(""); mountLog.writeText("") }

    /** Проверить синтаксис сгенерированного скрипта (`sh -n`), не исполняя его. */
    fun syntaxCheck(script: String, name: String = "check.sh"): Pair<Int, String> {
        val f = File(dir, name)
        f.writeText(script)
        val p = ProcessBuilder(sh, "-n", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Pair(p.exitValue(), out)
    }

    /** Исполнить production-скрипт в подставном окружении. */
    fun exec(
        script: String,
        name: String = "script.sh",
        env: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val f = File(dir, name)
        f.writeText(script)
        val pb = ProcessBuilder(sh, f.absolutePath).redirectErrorStream(true)
        val e = pb.environment()
        e["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH") ?: "/bin:/usr/bin"}"
        e["MOUNTINFO"] = mountinfo.absolutePath
        e["STATE"] = stateDir.absolutePath
        e["ARGV_LOG"] = argvLog.absolutePath
        e["MOUNT_LOG"] = mountLog.absolutePath
        e["MOUNT_FAIL"] = "0"
        e["HIDE_LAYER"] = "0"
        e["UMOUNT_FAIL"] = "0"
        e["LAZY_KEEPS"] = "0"
        e["STAT_BROKEN"] = "0"
        e["STAT_MISMATCH"] = "0"
        e["NS_DIFFERS"] = "0"
        e["NO_ROUTE"] = "0"
        e["UPLINK_IFACE"] = "wlan0"
        e.putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Pair(p.exitValue(), out)
    }
}
