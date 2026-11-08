# iter33n-fix1 — отчёт (версия 1.0.59, БЕЗ смены версии)

**База:** `af913cd` (закрытие iter33n), ветка `fix/v2`.
**Код итерации:** `69729e954596b3c4b7b67781cd4ff9d0551df7f4`.
**Маркер:** `app/buildinfo.properties` → `SOURCE_SHA=69729e9` (пишет агент; `git rev-parse` в сборке запрещён).
**Версия НЕ менялась:** `versionCode = 59`, `versionName = "1.0.59"` (`app/build.gradle.kts:78-79`, `BuildMarkerTest` не тронут).
**HEAD ветки `fix/v2`:** `47e927b` (после него — только правки этого отчёта/WORKLOG, код не меняется;
код итерации = `69729e9`).
**CI:** прогон `42510035315` (`69729e9`) — **success, 137 PASSED / 0 FAILED**, job `128820887253`,
артефакт `13628863500` (177 148 033 Б), `BUILD SUCCESSFUL in 4m 20s`; handoff-прогон
**`42511098442`** (`74af9c8`) — success, **137 PASSED / 0 FAILED**, job `128824133411`,
артефакт `13629277892` (177 147 942 Б), `BUILD SUCCESSFUL in 4m 12s`, `BuildMarkerTest > commitSourceIsHonest PASSED`.
Прогон на HEAD `47e927b`: **`42512116433`** — success, **137 PASSED / 0 FAILED**, job `128827221487`,
артефакт `13630073416` (177 148 005 Б), `BUILD SUCCESSFUL in 4m 8s`.

---

## §0. Что показал лог `voiceapp3 (26).log` (7371 строк)

| Факт | Строки лога | Что подтвердилось |
|---|---|---|
| сборка/сессия | `:6`, `:874`, `:8`, `:876` | это 1.0.58 (`versionCode=58`, `commit=d62b5bd`, `ver=1.0.53` в `SESSION_START` — правка `VER` не была в билде) |
| TTL ON при мёртвом интернете | `:7251-7261` | честный `TTL_TRY variant=none reason=no_uplink`, `uplink=none`, правило НЕ поставлено, CarPlay не тронут |
| повторный клик через 13 с | `:7340-7342` | `skipped: debounce 13424ms` — окно 30 с съело клик |
| JIT | `:1801` | `JIT_BOOT runtime=false persist=true init=true expect_runtime_true_after_reboot=true` — проба iter33n в билде есть |
| слоты | нет `PLUGIN_DIAG md5=`, `TOGGLE [hvacBatt] … verify=ok` при пустой панели | дизайн уведомлений верный, «молчал» сам плагин |

Вывод, совпадающий с §0 мандата: панель не рисовала слоты не из-за контракта `kg.j`, а из-за
бандла 1.0.58, который не читает `persist.sys.hvacdigital.*`.

## §1. P0 — плагин 1.4.1

| Требование | Где сделано | Доказательство |
|---|---|---|
| 1.1 заменить бандл на 1.4.1 | `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk` (18 284 258 Б) | скачан с постоянной ссылки коммита `raw.githubusercontent.com/swimapps/CentralEXAuto/e0bd36c9/…`; локально md5 `5aab306b34a2ce06d527399ed536cb35`, sha256 `fabc06a3…3d21` — совпало с §0.1 байт-в-байт |
| 1.2 все константы md5/size | `tools/HvacDigitalPlugin.kt:31-33` (`PLUGIN_APK_MD5`, `PLUGIN_APK_SIZE = 18_284_258L`, `PLUGIN_APK_SHA256`) | `PluginAssetTest > constantsMatchAsset PASSED`; `grep -rn '2fa3d11d\|de4b4742\|18280162\|18_280_162' app/` = **0** (в коде, скриптах, gradle; исторические записи `docs/agent/WORKLOG.md` прошлых итераций не в счёт) |
| 1.3 `PLUGIN_DIAG md5=… expected_md5=… md5_match=` | `tools/HvacFlagsApply.kt:533-537` (команда `PLUGIN_MD5=$(md5sum "$(pm path …)") path=…`), разбор — `:550-558` | статически; строка вида `PLUGIN_DIAG md5=5aab306b… expected_md5=5aab306b… md5_match=yes path=/system/app/… version=1.4.1` |
| 1.4 размер заливки/чанки | `PLUGIN_APK_SIZE` — единственная константа размера; APK не чанкуется: он извлекается из `res/raw` в `cacheDir` (`HvacDigitalPlugin.kt:217-236`) и копируется НА УСТРОЙСТВЕ (`cp -f`, `:270-280`) | `ScriptChunker` режет только ТЕКСТ скриптов (не зависит от размера APK); порог «извлеклось» `> 1_000_000` Б остаётся валидным |
| мониторинг/проверки заливки | `checkStatus` сравнивает `apk_size` с `PLUGIN_APK_SIZE` (`:173`, `:442`, `:477`, `:542`) | эти строки теперь печатают `expected_size=18284258 size_ok=true` |

⚠️ Честно: **сам файл плагина я заменить могу, но проверить его поведение на ГУ — только вы.**
Признак успеха в логе: `PLUGIN_DIAG … md5_match=yes` + `HVAC_MOUNT_RESULT … md5_match=true`.

## §2. P0 — JIT механизмом референса (zygote разрешён только в тумблере)

Цепочка ровно как `w0/bb.run`:

1. `getprop` до операции (фон, лог `JIT_APPLIED_REQUEST … runtime_before= … persist_before= …`);
2. `persist.dalvik.vm.usejit` + маркер `persist.sys.dalvik.vm.usejit` (как было, `jitPersistBackfill`);
3. `setprop dalvik.vm.usejit` + `dalvik.vm.usejitprofiles`;
4. **сохранение транспорта**: состояние wifi пишется в `wifi_before=` и восстанавливается после рестарта
   (`svc wifi enable`, если было `1`);
5. **рестарт фреймворка** — `setprop ctl.restart zygote` из detached-watcher;
6. после подъёма: `getprop dalvik.vm.usejit`, ожидание смены pid zygote/system_server (≤180 с),
   `/proc/self/maps` на `dalvik-jit-code-cache` — проверяется в НАШЕМ процессе на следующем старте.

| Требование | Где | Доказательство |
|---|---|---|
| 2.1 ON/OFF одинаковым циклом | `tools/AppPerformance.kt:1042-1119` (`jitApplyWithZygote`), скрипт-watcher `:1002-1040` | `JitBootTest > zygoteRestartOnlyInsideJitToggleBlock PASSED` |
| 2.1 `JIT_APPLIED runtime=… zygote_restart=… maps=…` | `:903-980` (`jitPendingVerify`), строка `:963-966` | статически (формат фиксирован тестом `zygoteRestartOnlyInsideJitToggleBlock` через маркеры блока) |
| 2.2 «только по клику тумблера» | вызов один: `:217` из `setAutoOptimize`; блок ограничен маркерами `JIT_ZYGOTE_BEGIN :981` / `JIT_ZYGOTE_END :1120` | тест: литерал `ctl.restart zygote` есть ТОЛЬКО в `AppPerformance.kt` и ТОЛЬКО внутри маркеров; `jitBootProbe`/`bootRestoreJit`/`verifyJitOnly`/`jitPendingVerify`/`checkPendingZygoteVerify`/`jitRound` его не вызывают — **PASSED** |
| 2.2 предупреждение в UI | `ui/ToolsCatalog.kt:31` (`desc.perfJit`) | текст: «Экран погаснет на 10–20 секунд, открытые приложения закроются…» |
| 2.2 транспорт после рестарта | `jitPendingVerify` при пустом ответе делает `AdbClient.reconnectRoot("jit_pending_verify")` и повторное чтение (`:939-952`) | adbd (native, init) zygote не убивает — это проверяемо по логу следующего прогона |
| 2.3 `JIT_BOOT` остаётся безусловной | `:836-869` (не менялся), `App.kt:51-53` | `JitBootTest > jitBootLineIsUnconditional/calledOnEveryStart PASSED` |
| 2.3 при `runtime!=want` — честный `result=failed` | `:953-960`, `:963-966` | статически |
| 2.4(а) persist-маркер ДО рестарта | `:1049-1054` (файл `jit_pending_verify` + `jit_pending_want`) | статически |
| 2.4(б) тумблер НЕ верифицирует из мёртвого процесса | шаг возвращается `PARTIAL` с текстом «итог будет строкой `JIT_APPLIED … via=pending_verify`» (`:1108-1118`) | статически |
| 2.4(в) следующий старт дочитывает итог | `jitBootProbe` → `runCatching { jitPendingVerify(context) }` (`:867`), плюс `VoiceAssistantService.onCreate/onStartCommand` → `checkPendingZygoteVerify` (`:880-887`, уходит в `BgWorker`) | статически |
| 2.4 намерение не сжигается при мёртвом канале | `:946-952` (`jitPendingDone.set(false)`) | статически |

**Почему watcher, а не прямой вызов из приложения:** `ctl.restart zygote` убивает все app-процессы —
`getprop` после рестарта выполнять некому. Поэтому скрипт стартует detached (`nohup … &`, `setsid`-эффект),
переживает смерть процесса, сам рестартует и пишет итог в `/data/local/tmp/jit_zygote_result.txt`;
приложение дочитывает его на следующем старте. Задержка рестарта 8 с (`JIT_ZYGOTE_DELAY_S :997`) —
чтобы лог и статус в UI успели записаться и владелец видел причину «экран погас».

Синтаксис скрипта watcher проверен `sh -n` локально (899 Б → base64 1.2 КБ, влезает в payload 4096).

## §3. P1 — TTL debounce

| Требование | Где | Доказательство |
|---|---|---|
| OFF не блокируется никогда | `tools/NetworkTtl.kt:178` (`debounceMs = if (enabled) TTL_ON_DEBOUNCE_MS else 0L`), `:180` | `TtlDebounceTest > offIsNeverDebounced PASSED` |
| повтор ON ≤ 3 с | `:162` (`TTL_ON_DEBOUNCE_MS = 3_000L`) | `TtlDebounceTest > onWindowIsThreeSeconds PASSED` |
| окно 30 с в тумблере не осталось | — | `TtlDebounceTest > toggleNoLongerUsesThirtySeconds PASSED` |
| правила TTL не менялись | `TtlScript.kt` не тронут (uplink-only, санация, dedupe, OFF_OK) | §5 |

Приёмник сети (`TTL_RECEIVER`) сохранил своё окно 30 с: там шторм переподключений, а не клик владельца.

## §4. Тесты и CI — **137 PASSED / 0 FAILED** (база 129, счётчик не уменьшен)

| Файл | Тестов | Что держит |
|---|---|---|
| `PluginAssetTest` (новый) | 5 | размер `18_284_258`, md5 `5aab306b…`, sha256 `fabc06a3…`, совпадение констант кода с активом, наличие в dex строк `persist.sys.hvacdigital.batt/.exttemp/.temp` |
| `TtlDebounceTest` (новый) | 3 | окно ON 3 с, OFF без окна, в тумблере нет 30 000 мс |
| `JitBootTest` | 4 (было 3) | +`zygoteRestartOnlyInsideJitToggleBlock` (call-sites `ctl.restart zygote`) |
| `JitPersistTest` | 3 (было 4) | `zygoteNeverRestarted` (абсолютный запрет) заменён тестом выше — запрет больше не абсолютный по решению владельца |
| `MountResultTest` | 8 | размеры в фикстуре пересчитаны на `18_284_258` |
| остальные (22 файла) | 114 | без изменений |

Провальные прогоны показаны честно:

| Прогон | SHA | Итог | Причина |
|---|---|---|---|
| `42508998476` | `0339d34` | ❌ failure | `compileDebugKotlin` `AppPerformance.kt:1034` — при генерации патча в Kotlin-строку попал литеральный перевод строки (`+ "\` ⏎ `"`) |
| **`42510035315`** | **`69729e9`** | ✅ **success** | **137 PASSED / 0 FAILED**, job `128820887253`, артефакт `13628863500` (177 148 033 Б), `BUILD SUCCESSFUL in 4m 20s`; `PluginAssetTest` 5/5, `TtlDebounceTest` 3/3, `JitBootTest` 4/4 |

## §5. Что НЕ тронуто (дифф `af913cd..69729e9`, 12 файлов)

`x7.i`/`x7.b`/recarga/mount-мониторинг, рут-reconnect и сериализация заливок, TTL-скрипт
(uplink-only, санация, dedupe, `OFF_OK`), фиксы `EXT_PROBE_V2`, exempt лог-бюджета,
`SESSION_START ver=BuildConfig`, контракт статус-иконок `kg.j`, `StatusIconContract`, версии/`versionCode`.

## §6. Что проверить владельцу

1. **Установка 1.0.59** (финальная; версия та же). В логе сразу после старта:
   `PLUGIN_DIAG md5=5aab306b34a2ce06d527399ed536cb35 expected_md5=5aab306b… md5_match=yes`.
   Скрин ДО/ПОСЛЕ тумблера «Цифровой климат» — плагин 1.4.1 должен сам поднять слоты.
2. **Слоты**: «Процент заряда» + «Цифровая наружная» → ожидаемый вид — стек «°C» над «%».
   Клик по `%` → приложение Энергия. Выключение каждого убирает свой элемент. Скрины ДО/ПОСЛЕ.
   Если стек не появился — пришлите лог: теперь в нём видно, ЧЕМ именно ответил плагин
   (`PLUGIN_DIAG`, `HVAC_MOUNT_RESULT`, `HVAC_PROBE`).
3. **JIT**: клик тумблера → экран погаснет на 10–20 с (это ожидаемо и предупреждено в UI) →
   после подъёма: `adb shell getprop dalvik.vm.usejit` = `true`, а в логе на следующем старте
   строка `JIT_APPLIED runtime=true want=true zygote_restart=ok maps=yes result=ok via=pending_verify`.
   Если `maps=no` при `runtime=true` — сообщите: значит JIT-кэш в процессе не поднялся.
4. **TTL при живом интернете**: ON → `TTL_ADD_COUNT n=1` + `SCOPED_OK`, CarPlay жив;
   повторный клик через 4+ с должен пройти (не «debounce 30 s»); OFF → `TTL_OFF v4=0 v6=0 result=OK`.
5. Прислать лог + скрины.

## Риски, которые нельзя проверить из CI (честно)

1. **Рестарт фреймворка на этой прошивке не проверялся ни разу** (владелец 06.11 запрещал, 08.11
   разрешил только для JIT). Если `ctl.restart zygote` на IHU629G не поднимает framework сам —
   это будет видно по отсутствию строки `JIT_APPLIED` и/или по мёртвому каналу; тогда
   единственный безопасный откат — перезагрузка ГУ вручную владельцем (я её не делаю).
2. `pgrep` может отсутствовать в toybox: строка запуска тогда печатает `launched=unknown`, статус
   шага от этого не зависит (watcher уже detached).
3. `nohup`+`&` внутри `adb shell` — стандартный приём; если прошивка убьёт watcher при закрытии
   сессии, в логе следующего старта будет `zygote_restart=unknown reason=no_result_file`, а итог
   всё равно печатается по `getprop`.
