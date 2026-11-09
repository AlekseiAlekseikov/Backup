# iter33o — отчёт (версия 1.0.60)

**База:** `739bc32` (iter33n-fix1 = 1.0.59, установлена и проверена владельцем), ветка `fix/v2`.
**Код итерации:** `a155e79548a7bb42852fde2f8fdc8333de227d00`.
**Маркер:** `app/buildinfo.properties` → `SOURCE_SHA=a155e79` (пишет агент; `git rev-parse` в сборке запрещён).
**Версия:** `versionCode = 60`, `versionName = "1.0.60"` (`app/build.gradle.kts:78-79`), `BuildMarkerTest` обновлён.
**CI:** прогон `42674945910` (`a155e79`) — **success, 147 PASSED / 0 FAILED**, job `129353710310`,
артефакт `13700830595` (177 146 377 Б), `BUILD SUCCESSFUL in 2m 58s`.
Handoff-прогон **`42676491595`** (`b03f999`: отчёт + WORKLOG + `SOURCE_SHA=a155e79`) — **success,
147 PASSED / 0 FAILED**, job `129359129192`, артефакт `13700542836` (177 146 362 Б), `BUILD SUCCESSFUL in 3m 41s`.

---

## §0. Что подтвердил лог 1.0.59 (`voiceapp3 (28).log`, 28 659 строк)

| Факт | Строки лога | Вывод |
|---|---|---|
| сборка/сессия | `:6`, `:8` (`versionCode=59 commit=69729e9`), `:1043`, `:1946` | 1.0.59 стоит, три сессии (`c66ad4fe`, `ad82fce3`, `3b45fe33`) |
| плагин 1.4.1 смонтирован | `:2780` `HVAC_MOUNT_RESULT … md5_match=true … size_ok=true`, `:2784` `HVAC_PROBE … md5_match=true` | бандл работает |
| `PLUGIN_DIAG … md5_match=no` | `:328` — `md5=2fa3d11d… expected=5aab306b…` | это СТОК `/system`-файл ДО bind (сток = ровно старый 1.4.0) ⇒ §2.1 |
| JIT работает | `:1419` `JIT_APPLIED runtime=true maps=yes result=ok via=pending_verify`, `detail="JIT_ZYGOTE_WATCH start …"`, `:1421` `zygote_restart=unknown reason=no_result_file` | watcher успевал записать только первую строку ⇒ §0.4/§1.2 (ожидание `JIT_ZYGOTE_DONE`) |
| после ребута ГУ | `:2932`, `:3308`…`JIT_BOOT runtime=false persist=true` | `runtime=false` — норма ⇒ §2.3 (текст) и §1.5 (автовосстановление) |
| TTL первый клик | `:755`, `:761` `TTL_TRY variant=none reason=no_uplink` | Wi-Fi ещё подключался ⇒ §2.2 |
| TTL второй клик | `:925`, `:931` `TTL_TRY variant=uplink scope="-o wlan0"` → `TTL_ADD_COUNT n=1 v6=1` → `VERIFY … OK` | правила не трогаем |
| AVAS после рестарта | строк `AVAS_*` нет вообще | в 1.0.59 восстановления не было (он и «проваливался» в салон) |

## §1. P0 — восстановление AVAS по механизму референса

Байткод референса, по которому сделано (проверено в `/home/user/cex_smali`):

* `w0/d0.smali` — `CarAudioManager` (поле `k`), рефлексия `getMethod("getAVASMode", null)`,
  результат `instance-of Integer`; сохранение в prefs; `getMethod("setAVASMode", Integer.TYPE)`
  + `invoke(audio, Integer.valueOf(целевое))`;
* `w0/df.smali`, `w0/qa.smali` — те же `getAVASMode`/`setAVASMode`;
* `w0/t2.smali` — запись sound type (`tela_preta_prefs`) — нам НЕ нужно (границы §1.3).

| Требование | Где сделано | Доказательство |
|---|---|---|
| 1.1 сохранение режима перед рестартом | `tools/AvasRestore.kt:92-107` (`save`), `:201-205` (`saveBeforeRestart`); вызов в цепочке JIT — `AppPerformance.kt:1170` | `AvasRestoreTest > callSitesAreOnlyAllowed PASSED`; лог `AVAS_SAVE mode=<int> where=jit_toggle` |
| 1.1 метод недоступен ⇒ честный skip | `AvasRestore.kt:156-169` (`readMode` → `LAST_SAVE_REASON = no_getAVASMode/security/reflection_error`), строка `:66` | `AvasRestoreTest > unreadableModeIsFailNotCrash PASSED` (формат `AVAS_RESTORE skipped reason=no_getAVASMode …`) |
| 1.2 восстановление после подъёма | `AppPerformance.kt:1067` (`jitPendingVerify` → `AvasRestore.restoreAfterRestart`), `AvasRestore.kt:110-135` (`restore`) | лог `AVAS_RESTORE mode_saved=N mode_found=M result=ok|failed mode_after=… where=…`; `AvasRestoreTest > logLinesFollowReference PASSED` |
| 1.2 совпало ⇒ `already_ok` | `AvasRestore.kt:122-127` | `AvasRestoreTest > alreadyOkIsSkipped PASSED` (`AVAS_RESTORE skipped reason=already_ok mode=3 where=jit_toggle`) |
| 1.3 только возврат сохранённого | `AvasRestore.kt:171-178` (`writeMode(value)` — единственный вызов `setAVASMode`, аргумент = параметр) | `AvasRestoreTest > setAvasModeOnlyInAvasRestore PASSED`: литерал `setAVASMode` есть только в `AvasRestore.kt`, констант `setAVASMode(0/1/2/3/-1)`, `setSoundType`, звукового демона и `avas_muted_saved` в КОДЕ нет |
| 1.3 ровно три разрешённые точки | (а) `AppPerformance.kt:1170` (тумблер), (б) `AppPerformance.kt:873` (`APP_START` в `jitBootProbe`), (в) `§1.5` идёт через тот же `jitApplyWithZygote(route=boot_restore)` — `:932` | `AvasRestoreTest > callSitesAreOnlyAllowed PASSED` (файлы вызовов: только `AvasRestore.kt` + `AppPerformance.kt`) |
| 1.4 нет Car API ⇒ цепочка JIT продолжается | `save`/`restore` возвращают `null/false`, `jitApplyWithZygote` это лишь пишет в лог (`:1147` `avas_saved=none`) | нет ни одного `while`/`for`-ретрая: `AvasRestoreTest > setAvasModeOnlyInAvasRestore PASSED` |
| 1.5 автовосстановление при загрузке | `AppPerformance.kt:915-948` (`jitBootRestoreArm`), вызов — `App.kt:57-59`, решение — `:904-911` | `JitBootTest > bootRestoreOnlyWhenPrefOnAndRuntimeOff PASSED` (`run` / `skip_pref_off` / `skip_already_on` / `skip_same_boot`) |
| 1.5 не чаще одного раза за загрузку | маркер `jit_boot_restore_done` = boot_tag, пишется ДО рестарта (`:897`, `:928`) | тест проверяет порядок (`putString` до `jitApplyWithZygote`) — PASSED |
| 1.5 задержка ~30 с, «уже включён» ⇒ skip | `JIT_BOOT_RESTORE_DELAY_MS = 30_000L` (`:898`), `BgWorker.postDelayed` (`:937`), повторная проверка `pref_off`/`already_on` перед рестартом | тест PASSED, строки `JIT_BOOT_RESTORE cancelled reason=…` |
| §0.4 итог рестарта больше не «unknown» | `AppPerformance.kt:1046-1060` — ожидание второй строки `JIT_ZYGOTE_DONE` (6 × 7.5 с ≈ 45 с), `:1110-1111` | строка `JIT_ZYGOTE_RESULT waited_rounds=… done=yes|no` |

## §2. P1 — мелочи

| # | Требование | Где | Доказательство |
|---|---|---|---|
| 2.1 | `PLUGIN_DIAG` не должен пугать `md5_match=no` до монтирования | ранняя строка помечена `phase=pre_mount` (`HvacFlagsApply.kt:534`, `:560`); итоговая — `HvacDigitalPlugin.kt:193-212` (`md5Diag`), вызывается в `enable` (`:522`) и `bootInstall` (`:597`) как `phase=post_mount` | строки в логе: `PLUGIN_DIAG … phase=pre_mount` (сток) и `PLUGIN_DIAG md5=5aab306b… md5_match=yes phase=post_mount reason=boot_install` |
| 2.2 | до 2 повторов детекта uplink с паузой ~3 с | `NetworkTtl.kt:158-163` (константы + `isNoUplink`), цикл — `:214-232` | `TtlDebounceTest > uplinkRetryOnNoUplink PASSED` (`TTL_UPLINK_RETRIES = 2`, `3_000L`, повтор только при `reason=no_uplink`) |
| 2.2 | правила/скрипт TTL не менялись | `TtlScript.kt` не тронут (диффом `739bc32..a155e79` его нет) | тот же тест: `TTL_ADD_COUNT` + `dedupe()` на месте |
| 2.3 | вместо «применится после перезагрузки ГУ» — `jit=off_after_reboot toggle_to_enable` | `AppPerformance.kt:893-896` (константы строк), печать — `:826-831` | `JitBootTest > bootLineExplainsOffAfterReboot PASSED`; старого текста в коде нет (тест проверяет) |
| 2.3 | `JIT_BOOT`-проба остаётся безусловной | `:836-875` не менялся по логике; вызывается из `App.kt:51-53` | `JitBootTest > jitBootLineIsUnconditional / calledOnEveryStart PASSED` |

## §3. Тесты — **147 PASSED / 0 FAILED** (база 137, счётчик вырос)

| Файл | Тестов | Что держит |
|---|---|---|
| `AvasRestoreTest` (новый) | 7 | `decide` (NONE/ALREADY_OK/RESTORE/FAIL), форматы `AVAS_SAVE`/`AVAS_RESTORE`, `setAVASMode` только в `AvasRestore.kt` и только с сохранённым значением, три разрешённые точки вызова, отсутствие ретраев |
| `JitBootTest` | 6 (было 4) | +`bootRestoreOnlyWhenPrefOnAndRuntimeOff` (§1.5: `run`/`skip_pref_off`/`skip_already_on`/`skip_same_boot`, маркер до рестарта, 30 с, `App.onCreate`), +`bootLineExplainsOffAfterReboot` (§2.3); `zygoteRestartOnlyInsideJitToggleBlock` теперь допускает ДВЕ точки (тумблер + boot-restore) и требует ровно 2 вызова `jitApplyWithZygote` |
| `TtlDebounceTest` | 4 (было 3) | +`uplinkRetryOnNoUplink` (§2.2) |
| `BuildMarkerTest` | 3 | 60 / `1.0.60` |
| `PluginAssetTest`, `TtlScriptTest`, `MountResultTest`, остальные | 127 | без изменений (плагин 1.4.1, TTL-скрипт, размеры) |

Провальные прогоны этой итерации — честно:

| Прогон | SHA | Итог | Причина |
|---|---|---|---|
| `42669479902` | `949a31a` | ❌ | 147 тестов, **4 FAILED**: (1) `AvasRestoreTest.setAvasModeOnlyInAvasRestore` — запрещённое слово стояло в KDoc; (2)–(3) `JitBootTest` — литерал `ctl.restart zygote` в KDoc §1.5 вне маркеров блока и `jit=off_after_reboot` не был литералом; (4) `TtlDebounceTest.uplinkRetryOnNoUplink` — `TTL_ADD_COUNT` искали не в том файле |
| `42671578054` | `15f9bb5` | ❌ **0 джобов** | `startup_failure` GitHub Actions: в 18:09–18:13 UTC у Actions был **partial outage** (status.github.com: `Minor/Partial System Outage`); наш код не при чём — тот же прогон при повторе запустился (`42672009784`, джоба `129350053522`) |
| `42672009784` | `963a7fb` | ❌ | 147 тестов, **1 FAILED**: `AvasRestoreTest.setAvasModeOnlyInAvasRestore` — слово в KDoc («не трогаем …») считалось запрещённой операцией |
| **`42674945910`** | **`a155e79`** | ✅ **success** | **147 PASSED / 0 FAILED**, job `129353710310`, артефакт `13700830595` (177 146 377 Б), `BUILD SUCCESSFUL in 2m 58s` |

## §4. Что НЕ тронуто

`x7.i`/`x7.b`/recarga/mount-мониторинг, рут-reconnect, `TtlScript.kt` (uplink-only, санация, dedupe,
`OFF_OK`), `EXT_PROBE_V2`, exempt лог-бюджета, контракт статус-иконок `kg.j`, `StatusIconContract`,
плагин 1.4.1 (`5aab306b…`/18 284 258 Б/константы), `BatterySocView`-логика (batt прячет стек — норма).

## §5. Что проверить владельцу

1. **Установка 1.0.60** поверх 1.0.59 (настройки сохраняются). В логе `BUILD: versionCode=60`.
2. **Тумблер JIT** → экран гаснет 10–20 с → после подъёма:
   `AVAS_RESTORE mode_saved=<N> mode_found=<M> result=ok … where=jit_toggle`, затем
   `JIT_APPLIED runtime=true … via=pending_verify avas_restore=ok`, и **AVAS играет нормально (не в салоне)**.
   Ожидаемо также `JIT_ZYGOTE_RESULT waited_rounds=… done=yes`.
3. **Перезагрузка ГУ при включённом JIT** → через ~30 с один автоматический рестарт фреймворка
   (`JIT_BOOT_RESTORE armed … in=30000ms`), после подъёма — `JIT_APPLIED … via=boot_restore` + AVAS ок.
   Повторные запуски приложения в этой же загрузке рестарт не повторяют (`skip_same_boot`).
   Если тумблер выключен — авто-рестартов НЕТ (`JIT_BOOT_RESTORE skipped reason=pref_off`).
4. **Слоты** — как есть; «Процент заряда» OFF прячет оба значения (дизайн референса, не трогаем).
   `PLUGIN_DIAG` теперь печатает `phase=pre_mount … md5_match=no` и `phase=post_mount … md5_match=yes`.
5. **TTL**: при выключенном/подключающемся Wi-Fi тумблер сам делает до 2 повторов (`TTL_UPLINK_RETRY attempt=1/2`),
   и если uplink появился — правило ставится с первого клика. OFF → `TTL_OFF v4=0 v6=0 result=OK`, CarPlay жив.
6. Лог + скрины.

## Риски (не проверяемо из CI)

1. **AVAS через `Car.createCar`**: вызов идёт с фонового потока, обёрнут в `runCatching`. Если на IHU629G
   `getCarManager("audio")` вернёт объект без `getAVASMode` — в логе будет
   `AVAS_SAVE skipped reason=no_getAVASMode`, цепочка JIT при этом не ломается.
2. **Авто-рестарт фреймворка (§1.5)** — новое поведение: один раз за загрузку при включённом тумблере,
   после 30 с. Если он мешает — выключите тумблер JIT, рестартов не будет вовсе.
3. `/proc/self/maps` проверяется уже после рестарта в новом процессе; если ГУ поднимает приложение
   раньше, чем system_server готов, `maps` может быть `no` при `runtime=true` — это не ошибка JIT,
   в логе видно по паре `runtime`/`maps`.
