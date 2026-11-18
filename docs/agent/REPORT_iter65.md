# ITER65 — две последние правки в песочнице + протокол сессии на ГУ

База: `917ae523e9bb86692632e0f40dd70a0aa722f72a` (`fix/v2`, сверено `git fetch` + `git rev-parse origin/fix/v2`).
CODE_SHA iter64 `04a4242bb1f5fd0cec83162c9ea654be2c3e21a3`, `SOURCE_SHA` совпадал с ним; CI iter64 run
`44318084831` на `0997860` — success. `versionCode=63` / `1.0.63` **не менялись**; `main` не пушился;
`--force`, `git clean`, `git reset` не применялись; JIT/TTL/звук/тумблеры не тронуты; новых `.md` — только этот файл.

## 1. SHA

| что | SHA |
|---|---|
| база | `917ae523e9bb86692632e0f40dd70a0aa722f72a` |
| **CODE_SHA** = **SOURCE_SHA** | `60d3f17326eb7460a5af17909d24e1da836b83c2` |
| HANDOFF_SHA | `744ef6eb49233d0e2fa266ada697780e5937fb9e` (отчёт с CI — `94298d6` ниже) |

## 2. Прогоны

| прогон | результат |
|---|---|
| `bash /home/user/runtests64.sh` (весь JVM-набор + настоящий `/bin/sh` + **два конкурентных java-процесса**) | **`OK (1130 tests)`**, failures 0, skipped 0 |
| `Iter64EventIdTest` (класс, в котором обе правки) | `OK (15 tests)` |
| iter64 для сравнения | `OK (1128 tests)` |
| `typecheck_lock.sh /home/user/w64` | **53 → 53, новых ошибок нет** |
| CI на handoff `94298d6` | run **`44329443616`**, job `build` — **success** (`JDK 21`, `Gradle build (debug)`, `Upload APK`) |

Локальная Gradle-сборка невозможна (JDK 11, нет Android SDK/сети): единственная настоящая сборка — CI (debug).
Debug-CI ≠ unit-тесты ≠ release ≠ проверка на ГУ.

## 3. ПРАВКА A — двухпроцессный тест стал конкурентным ✅

`Iter64EventIdTest.twoConcurrentProcessesKeepEveryRecord`, `Iter64TwoProcessMain` (`файл:строка` ниже).

- Оба процесса стартуют **до** ожидания: `startWriter(...)` дважды, затем `finish(...)` для каждого.
- `Iter64TwoProcessMain` принимает необязательный 5-й аргумент `maxBytes` и печатает
  `writer=… start=<ms> end=<ms> written=… failed=… spilled=… drained=… no_proc=… max_bytes=…`.
- Проверяется: (0) интервалы процессов **пересекаются**; (1) строк ровно `2*per`, каждая из `PA i`/`PB i`
  встречается ровно один раз; (2) ключи событий уникальны; (3) ни одна строка не разорвана —
  `^\S+\s\d+ \[r=\d+/\d+/\d+/w=[0-9a-f]{1,16}]$`; (4) меток `w=` минимум две; (5) ротация
  произошла (`<name>.1` существует) — иначе тест был бы не про лок и ротацию.

### До/после

На коде iter64 (`04a4242`, временный класс `Iter65FailBeforeTest`, тот же production-путь):

```text
FB65 A out=writer=6590637d written=30 failed=0
FB65 B out=writer=… written=30 failed=0
FB65 startA=- startB=-
java.lang.AssertionError: iter64 не печатает start= — конкурентность недоказуема: writer=6590637d written=30 failed=0
```

То есть падение — **по делу**: последовательный запуск не даёт ни пересечения интервалов, ни самого
измерения. После правки тест зелёный.

### Отдельно измеренная вещь (важно для «строк = вызовов»)

Лимит файла и политика хранения взаимосвязаны — измерено на реальных двух процессах по 60 строк:

| `maxBytes` | строк в итоге | файлы | вывод |
|---|---|---|---|
| 100000 / 8192 | 120 | активный | потерь нет, ротации нет |
| **4096** | **120** | активный + `.1` | ровно одна ротация, оба поколения целы — **используется в тесте** |
| 2000 | 84 | активный + `.1` | старые строки ушли по политике хранения (журнал держит активный + ОДИН бэкап) |
| 512 | 14 | активный + `.1` | то же: это не потеря записи, а названный размер истории |

Поэтому тест берёт 4096 байт (ротация гарантирована, `2*per` строк помещаются целиком), а не 512:
при 512 проверка «строк = вызовов» проверяла бы не сохранность, а размер хранимой истории. Ограничение
не исправлялось (вне рамок итерации) — названо в §7 одной строкой.

## 4. ПРАВКА B — fallback-метка писателя 64-битная ✅

`EventKeyGen.randomTag`, `EventKeyGen.procDisabled`, `EventKeyGen.self`.

```kotlin
fun randomTag(): String {
    val rnd = SECURE.nextLong()
    val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)
    val nano = System.nanoTime()
    return java.lang.Long.toHexString(rnd xor (pid shl 32) xor nano)   // до 16 hex
}
```

- Источник — `SecureRandom` (один на процесс, `by lazy`), плюс `pid` и `nanoTime`.
- Метка стабильна внутри процесса: её кеширует `self` (`by lazy`) — поведение не менялось.
- Переключатель `-Dvoiceapp3.noProc=1` (`procDisabled()`) делает fallback-путь **достижимым** для теста
  через тот же production-код, без подмены объекта. В бою не выставляется.

### До/после

На iter64:

```text
FB65 randomTag=dd59afc7r len=9 line=X [r=1/2/1/w=dd59afc7r] stamped=false
java.lang.AssertionError: метка не 64-битная: dd59afc7r (9 симв.)
```

Важнее длины: прежняя метка оканчивалась на `r` и была **не hex** — значит `LogRecordId.isWriterStamped`
её не признавал, строка считалась строкой iter63 «без писателя», и коллизия `rid` вернулась бы ровно в той
среде, где fallback и включается. Тесты после правки: метка hex (9–16 символов), строка с ней разбирается
как новая, `writer` не теряется; 200 генераций — без повторов; два РЕАЛЬНЫХ процесса с `-Dvoiceapp3.noProc=1`
дают разные `w=`, и все их строки сохранены:

```text
writer=… no_proc=true …   (два процесса, метки различаются, потери нет)
```

## 5. Формальности (§3 задания) — четыре строки

1. `rid` **сам по себе** у двух писателей может совпасть; уникален ключ `(rid, writer)`.
2. Смешанный журнал (активный файл старой версии + `.new` новой) может дать дубль старой записи: строки без
   суффикса принимаются всегда. Это осознанный компромисс «не терять важнее, чем не дублировать».
3. `EventKeyGen.self` кешируется `by lazy`: если первый лог случился до готовности `/proc`, метка останется
   fallback до конца жизни процесса.
4. Записи, попавшие в `spill`, считаются «сохранёнными в spill», а не «в журнале», до фактического вливания —
   так и подписано в отчётах и в `Result.wire()`.

## 6. ПРОТОКОЛ СЕССИИ НА ГУ (готов к исполнению владельцем)

Правила исполнения: **одна команда — один блок вывода**. Всё, что меняет состояние, помечено.
Секреты маскируются (`<mac>`, `<ssid>`, `<ip4>`, `<ip6>`). Критерий остановки: результат противоречит
ожиданию → **стоп**, не «попробовать иначе» и не перезагрузка. Пакет приложения ниже обозначен как
`<pkg>` — подставить фактический (`com.example.voiceapp3`).

### Ш1 — read-only (разрешён, состояние не меняется)

```sh
# 1.1 кто мы и какая прошивка
id; getprop ro.build.version.release; getprop ro.build.version.sdk
```
```sh
# 1.2 свойства JIT
getprop dalvik.vm.usejit; getprop persist.sys.dalvik.vm.usejit; getprop dalvik.vm.jitprithreshold
```
```sh
# 1.3 zygote/jit целиком (может быть длинно)
getprop | grep -iE 'zygote|jit' | head -40
```
```sh
# 1.4 su работает?
su -c 'echo SU_OK; id' 2>&1 | head -5
```
```sh
# 1.5 toybox и его версия
su -c 'command -v toybox; toybox --version 2>&1 | head -2' 2>&1
```
```sh
# 1.6 noclobber (на нём держится атомарный захват владельца)
su -c 'sh -c "set -C; : > /data/local/tmp/_c_probe; echo NOCLOB=$?; rm -f /data/local/tmp/_c_probe"' 2>&1
```
```sh
# 1.7 что уже лежит в tmp
su -c 'ls -l /data/local/tmp | head -40' 2>&1
```
```sh
# 1.8 22-е поле /proc/<pid>/stat (доказательство смерти владельца)
su -c 'readlink -f /proc/$$/stat; awk "{print \$22}" /proc/1/stat' 2>&1
```
```sh
# 1.9 плагин штатного UI и монтирования
su -c 'ls -ld /system/app/GeelyAutoSystemUIPlugin 2>/dev/null; mount | grep -iE "carlock|GeelyAuto" | head -20' 2>&1
```
```sh
# 1.10 что сейчас в iptables/mangle (наши имена и переходы)
su -c 'iptables -w 5 -t mangle -S POSTROUTING 2>&1 | head -40' 2>&1
su -c 'iptables -w 5 -t mangle -S 2>&1 | grep -iE "VAPP|TTL|voiceapp3" | head -20' 2>&1
```
```sh
# 1.11 политика маршрутизации
ip rule show 2>&1 | head -40
su -c 'ip rule show | grep -c "oif wlan0"' 2>&1
```
```sh
# 1.12 файл поколения TTL и следы приложения
su -c 'ls -l /data/data/*/files/ttl_slot_gen 2>/dev/null | head' 2>&1
su -c 'ls -l /data/local/tmp | grep -iE "lock|voiceapp3" | head -30' 2>&1
su -c 'mount | grep -iE "carlock|plugin|pip" | head -20' 2>&1
```

**Критерий продолжать:** `SU_OK`; `NOCLOB=0`; `/proc/$$/stat` читается, 22-е поле — число;
`iptables -S` возвращает rc=0. Иначе — стоп и разбор.

### Ш1-лог — состояние журнала ДО любых кликов (read-only)

```sh
adb shell 'ls -l /sdcard/Android/data/<pkg>/files/logs 2>/dev/null | head -20'
```
```sh
adb shell 'cat /sdcard/Android/data/<pkg>/files/logs/*.log 2>/dev/null | tail -40'
```
Ожидание: строки заканчиваются суффиксом `[r=…/…/…/w=<hex>]`. Фиксируем: число строк, последнюю строку,
множество меток `w=`.

### Ш2 — клик JIT ON (МЕНЯЕТ СОСТОЯНИЕ; `ctl.restart zygote` внутри клика)

Снимок хоста ДО клика (выполняется на компьютере, не на ГУ):

```sh
adb shell su -c 'iptables -w 5 -t mangle -S' > before_iptables.txt
adb shell 'ip rule show' > before_iprule.txt
adb shell 'getprop dalvik.vm.usejit' > before_usejit.txt
adb shell 'ls -l /data/local/tmp' > before_tmp.txt
adb shell 'cat /sdcard/Android/data/<pkg>/files/logs/*.log 2>/dev/null | wc -l' > before_lines.txt
```

Клик — **только тумблером в приложении**. После клика (процесс приложения умирает и поднимается):

```sh
adb shell 'getprop dalvik.vm.usejit'
```
```sh
adb shell 'cat /sdcard/Android/data/<pkg>/files/logs/*.log 2>/dev/null | wc -l'
```
```sh
adb shell 'cat /sdcard/Android/data/<pkg>/files/logs/*.log 2>/dev/null | tail -25'
```
```sh
adb shell 'cat /sdcard/Android/data/<pkg>/files/logs/*.log 2>/dev/null | sed -n "s/.*w=\([0-9a-f]\{1,16\}\)\]$/\1/p" | sort | uniq -c'
```

Ожидание и проверка (это отдельное требование к шагу 2 по журналу):

1. после клика число строк **не уменьшилось** (`wc -l` после ≥ до) — ни одна прежняя строка не пропала;
2. первая запись нового запуска несёт **новую** метку `w=` (множество меток выросло хотя бы на одну);
3. если JIT/TTL пишут из второго процесса — в выводе `uniq -c` видно **две и более** разных метки, и строки
   обоих процессов на месте;
4. интернет: открыть страницу (не «Wi-Fi ассоциирован»). Если не открывается — зафиксировать
   `ip route`, `ip rule show | grep -c 'oif wlan0'`, `getprop | grep -i dns`, `ping -c 1 1.1.1.1`,
   **ничего не меняя вручную**.

**Стоп:** приложение не поднялось; строк стало меньше; первая запись нового запуска с прежней меткой;
тумблер показывает «применено» при отсутствии интернета; появились правила `oif` не на `wlan0`.

### Ш3 — TTL ON → readback → OFF (МЕНЯЕТ СОСТОЯНИЕ)

```sh
su -c 'iptables -w 5 -t mangle -S POSTROUTING | head -20'
su -c 'iptables -w 5 -t mangle -S | grep -c "ttl-set 65"'
```
```sh
# OFF тумблером, затем:
su -c 'iptables -w 5 -t mangle -S | grep -c "ttl-set 65"'
su -c 'iptables -w 5 -t mangle -S POSTROUTING | head -20'
```
Ожидание: ON → ровно один `ttl-set 65` и переход `-o <uplink> -j <своя цепь>`; OFF → 0, чужие правила на месте.

### Ш4 — одна restore-операция звука (МЕНЯЕТ СОСТОЯНИЕ)

Одна операция, не серия. Проверить: terminal-строка `LOCKSOUND_RESTORE_RESULT`/`.done` есть,
`previous_restore_unresolved` после завершения нет, слышимость подтверждена ухом.
Стоп: статус «применено», а звука нет; `previous_restore_unresolved` в журнале; чужие mount-слои.

### Ш5 — тумблеры на экране (МЕНЯЕТ СОСТОЯНИЕ)

Скриншот до → клик по одному переключателю → скриншот после: изменился только он, подпись честная.
Повторить для 2–3 переключателей, включая зависимую пару (видимость «Цифровая наружная» от «Процент заряда»).

### Ш6 — журнал целиком

```sh
adb shell 'ls -lt /sdcard/Android/data/<pkg>/files/logs | head'
```
```sh
adb pull /sdcard/Android/data/<pkg>/files/logs ./logs_machine
```
Проверить: строки целые, нет обрезки посреди записи, нет IP/MAC/SSID, метки `w=` разных процессов
различаются, записи после рестарта на месте, файлов `.spill`/`.drain` не осталось «висящими».

### Формат блока «результат шага»

```text
### Шаг N — <название>
Команда(ы): <дословно>
Вывод: <дословно, с маскировкой>
Ожидание: <…>
Факт: <совпало / не совпало, чем именно>
Вывод: ПРОДОЛЖАТЬ / СТОП (причина)
```

## 7. ⛔ Что не проверено / названо одной строкой (не исправлялось)

- ⛔ ГУ: шаги Ш1–Ш6 **не исполнялись** — доступа к устройству/adb в этой среде нет; ни одна команда
  на машине не запускалась.
- ⛔ release-сборка, `testDebugUnitTest` на Android, статус-бар/док, слышимость, VHAL, реальные два процесса
  APK, `ctl.restart zygote`.
- Названо, не исправлено (§ рамок итерации): журнал держит активный файл + один бэкап `.1`, поэтому при
  лимите меньше объёма двух поколений старые строки уходят по политике хранения (измерено: 2000 → 84 из 120,
  512 → 14 из 120). Это размер истории, а не потеря записи; менять политику — отдельная тема.

## 8. Handoff

1. код + тесты → `60d3f17` (**SOURCE_SHA**);
2. `app/buildinfo.properties` → `SOURCE_SHA=60d3f17326eb7460a5af17909d24e1da836b83c2`;
3. этот отчёт + 3 строки в `WORKLOG.md`;
4. handoff-коммит → `744ef6eb49233d0e2fa266ada697780e5937fb9e`;
5. `git show HEAD:app/buildinfo.properties` — совпадает; `git diff --name-only 60d3f17..HEAD` — только
   `docs/agent/REPORT_iter65.md`, `WORKLOG.md`, `app/buildinfo.properties`;
6. push `fix/v2` (`917ae52..94298d6`), CI на handoff: run **`44329443616`**, job `build` — success
   (`JDK 21`, `Gradle build (debug)`, `Upload APK`).

Дальше — **стоп песочницы**: остаётся только сессия на ГУ по §6.
