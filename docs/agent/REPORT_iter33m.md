# iter33m — отчёт (версия 1.0.58)

**База:** `0150f00` (закрытие iter33l), ветка `fix/v2`.
**Код итерации:** `d62b5bd117ba895808838cf602fb9938a8dc9a21`.
**Маркер сборки:** `app/buildinfo.properties` → `SOURCE_SHA=d62b5bd` (пишет агент; `git rev-parse` в сборке не используется).
**Версия:** `versionCode = 58`, `versionName = "1.0.58"` (`app/build.gradle.kts:78-79`).
**CI:** прогон `42390693171` (`d62b5bd`) — **success, 119 PASSED / 0 FAILED**, job `128457450413`,
`BUILD SUCCESSFUL in 3m 23s`, артефакт `app-debug` id `13581512748` (177 129 381 Б).

Факты прогона 1.0.57 (`voiceapp3 (24).log`, `run=77a5a2c5` / `050ce701`) взяты из лога владельца и НЕ перепроверялись:
`TTL_OFF v4=1 v6=1 result=FAILED` при `TOGGLE [ttl65] → ON | ✓ SUCCESS | применено частично`;
`FLAGS_SYNC prefs=0,0,0 fact=0,1,1 action=ui_from_props`; `EXT_PROBE` по двум именам экран не изменил.

---

## §1 (P0) TTL + CarPlay — правила снимаются и ставятся только на uplink

| Что требуется | Где сделано | Чем доказано |
|---|---|---|
| `del_all()` без word-splitting | `tools/TtlScript.kt:71-90` — `iptables … -S POSTROUTING \| grep 'ttl-set' \| sed -n 's/^-A POSTROUTING //p' \| while IFS= read -r spec` → `iptables -w 5 -t mangle -D POSTROUTING $spec`; то же для `ip6tables`/`hl-set` (`:77`) | `TtlScriptTest > delAllHasNoWordSplitting PASSED` (нет `for spec in $(…)`, есть `while IFS= read -r spec`, `-D POSTROUTING $spec`, `\|\| break`) |
| эталонный цикл `-D … \|\| break` как дополнение | `tools/TtlScript.kt:82-89` (`for i in 1 2 3 4 5`) | тот же тест (`\|\| break`) |
| скоуп ТОЛЬКО uplink, `exclude_carplay` удалён | `tools/TtlScript.kt:117-124` (`SCOPE="-o $UPLINK"`, `VARIANT=uplink`); fallback `! -o $CP_FIRST` физически удалён; `tools/TtlRules.kt:64-72` — `variants()` возвращает только uplink | `TtlScriptTest > scopeIsUplinkOnly PASSED`, `TtlRulesTest > variantsOrderedAndScoped PASSED` (1 вариант вместо 2), `TtlRulesTest > noGlobalFallback PASSED` |
| uplink = CarPlay-интерфейс запрещён | `tools/TtlScript.kt:50-52` — `TTL_UPLINK_REJECT uplink=p2p-p2p0-0 reason=carplay_iface` + `UPLINK=""` | `TtlScriptTest > scopeIsUplinkOnly PASSED` (`TTL_UPLINK_REJECT`) |
| нет uplink → правило не ставится, тумблер `✗` | `tools/TtlScript.kt:113-115` (`TTL_TRY variant=none … reason=no_uplink`), `:158-160` (`TTL_SCOPE result=failed … reason=no_uplink`); `tools/NetworkTtl.kt:128` — `FAILED` с текстом «нет uplink … ломает CarPlay» | `TtlScriptTest > noUplinkIsHonestFail PASSED` |
| ON успех только при счётчике ≥1 и скоупе | `tools/NetworkTtl.kt:88-141` — ON `OK` только при `TTL_V4>=1 && VERIFY_V4=OK && uplink_only=yes`; правило без `-o <uplink>` → `FAILED` (`:131-132`) | `TtlScriptTest > verdictRequires… offVerdictNeedsZeroRules PASSED` (`SCOPE_UPLINK_ONLY`, `VERDICT=SCOPED_OK`) |
| OFF честен только при `TTL_V4=0 && TTL_V6=0` | скрипт: `tools/TtlScript.kt:141-145` (`VERIFY_V4=OFF_OK`, `TTL_OFF … result=OK|FAILED`), парсер: `tools/NetworkTtl.kt:125-127` (осталось правило → `FAILED`, текст «TTL_OFF не выполнился: осталось правило v4=… v6=…»); `PARTIAL` для TTL больше не возвращается (`status` = `OK`/`FAILED`/`BLOCKED`) | `TtlScriptTest > offVerdictNeedsZeroRules PASSED`; логи в скрипте: `TTL_OFF v4=… v6=… result=` |
| санация при старте | `tools/NetworkTtl.kt:279-283` (нечего снимать → `TTL_SANITIZED removed=0 … reason=start_nothing_to_remove`), `:293-299` (boot-ветка ставит правило ТОЛЬКО при включённом тумблере: `buildScript(active)`; иначе это санация, строки `TTL_SANITIZED`/`TTL_DEL`/`TTL_FOREIGN`/`TTL_TRY` — в лог) | `TtlScriptTest > sanitizeReportsRemovedCount PASSED` (`TTL_SANITIZED removed=`, `v4_removed=`, `TTL_DEL v4 spec=`, `TTL_FOREIGN`) |
| логировать чужое правило `ttl-set` | `tools/TtlScript.kt:105-106` — `TTL_FOREIGN v4|v6 spec="…"` | тот же тест |

Попутно исправлен дефект прошлой итерации: boot-ветка вызывала `buildScript(true)` независимо от тумблера
(т.е. при выключенном TTL ставила правило) — теперь `buildScript(active)` (`tools/NetworkTtl.kt:293`).

Проверка скрипта вне Android-сборки: `bash -n` + прогон на заглушках `iptables`/`ip6tables`/`ip`
(локально в песочнице агента, три сценария): (1) три старых правила v4 + одно v6 → `TTL_SANITIZED removed=4`,
после ON в mangle ровно `-A POSTROUTING -o wlan0 -j TTL --ttl-set 65`; (2) uplink = `p2p-p2p0-0` →
`TTL_UPLINK_REJECT`, `TTL_TRY variant=none reason=no_uplink`, правил ноль; (3) OFF, когда `-D` не срабатывает →
`TTL_OFF v4=0 v6=1 result=FAILED`, `TTL_FOREIGN v6 spec="-A POSTROUTING -j HL --hl-set 65"`. Итоги прогонов
совпадают с ожидаемыми строками лога.

## §2 (P0) тумблеры без скачков — источник истины ПРЕФЫ

| Что требуется | Где сделано | Чем доказано |
|---|---|---|
| префы — истина, клик сразу обновляет кэш | `tools/HvacFlagsApply.kt:216-223` — `Prefs2.putBoolean(pref, value)` + `PREF TRACE write key=…` + `cachedFlags = Triple(tempOn, battOn, extOn)` сразу же | `ToggleNoLieTest > prefsAreSourceOfTruth PASSED` |
| `ui_from_props` убрать | `tools/HvacFlagsApply.kt:400-430` — обратной записи нет; строка `FLAGS_SYNC … action=props_readonly` | тот же тест (`action=props_readonly` есть, обратной записи нет) |
| `syncFromDevice` read-only | `tools/HvacFlagsApply.kt:408-438` — читает props, **кэш UI = префы**, расхождение только печатает | тот же тест |
| `alignPropsToPrefs` при расхождении и при старте (вместо `alignHvacFlagsOnce`) | `tools/HvacFlagsApply.kt:441-475`; вызов при старте — `tools/StatusIcons.kt:938-947` (`alignHvacFlagsOnce` → `HvacFlagsApply.alignPropsToPrefs(app)`, один раз за процесс, `FLAG_ALIGN result=… action=align_props_to_prefs`) | `ToggleNoLieTest > syncDoesNotPushPeriodically PASSED` |
| `PREF TRACE: write/read key=hvacdigital_*` | `tools/HvacFlagsApply.kt:499-507` (`prefTraceWrite`, `prefTraceRead`) | `ToggleNoLieTest > prefsAreSourceOfTruth PASSED` |
| тумблер UI показывает преф | `tools/StatusIcons.kt:1074-1079` — `getHvacDigital()` = `prefs(ctx).getBoolean(pref, false)` | тот же тест |
| регресс-тест | `ToggleNoLieTest > prefsAreSourceOfTruth` (новый) | PASSED |

## §3 (P0) `EXT_PROBE_V2` — три имени наружней

| Что требуется | Где сделано | Чем доказано |
|---|---|---|
| три имени: `exttemp`, `temp_ext`, `ext_temp` | `tools/HvacFlagsApply.kt:36` (`PROP_EXT_THIRD`), `:38-42` (`EXT_NAMES`, порядок фиксирован) | `ExtProbeV2Test > threeNamesInFixedOrder PASSED` |
| одно имя =1, остальные =0, ОДНОЙ командой | `tools/HvacFlagsApply.kt:118-128` (`extProbeV2WriteScript`, `&& echo FLAGS_OK`, ни одной `;`) | `ExtProbeV2Test > writeScriptSetsOneAndClearsOthersInOneCommand PASSED` |
| полный цикл recarga + пауза 15 с | `tools/HvacFlagsApply.kt:344-350` (`EXT_PROBE_V2 recarga step=i/3 …`), `:360-364` (`Thread.sleep(EXT_PROBE_V2_PAUSE_MS)`, `:45` = 15 000 мс) | `ExtProbeV2Test > pauseIsFifteenSecondsAndNothingIsDeleted PASSED` |
| строка шага | `tools/HvacFlagsApply.kt:108-117` → `EXT_PROBE_V2 step=1/3 prop=… wrote=1 found=… result=ok|missing owner_look=wait15s` | `ExtProbeV2Test > stepLineFormat PASSED` |
| в конце `props_follow_prefs` | `tools/HvacFlagsApply.kt:366-383` — restore флагов ПО ПРЕФАМ + read-back → `EXT_PROBE_V2 props_follow_prefs=ok|fail prefs=… fact=… steps_ok=i/3` | `ExtProbeV2Test > pauseIsFifteenSecondsAndNothingIsDeleted PASSED` |
| ничего не удалять | в скриптах нет `setprop … ""` и `resetprop -d` | тот же тест |
| `PersistCleaner` нормализует все три легаси-имени в `0` | `tools/PersistCleaner.kt:20-31` (`LEGACY_PROPS` = `temp_ext` + `ext_temp`), `FLAG_PROPS` = `temp/batt/exttemp`; причина в лог — `reason=legacy_key_normalized` | `PersistCleanerTest > legacyKeyNormalizedAndFlagValuesResetToZero PASSED` |
| стартовая read-only диагностика | `tools/HvacFlagsApply.kt:473-497` (`EXT_DIAG prop=persist.sys.hvacdigital.* value=…`, `PLUGIN_DIAG sysui_plugin_shortcut_settings=…`, `sysui_tuner_version=…`), вызов — `App.kt:46-49` (`BgWorker`, один раз за процесс) | статически: строки в исходнике; отдельных тестов на `startupDiagnostics` нет (read-only, без записи) |
| свою раскладку НЕ рисовать, `StatusIcon_*` не трогать | правок в `res/` и в `StatusIcon_*` в этой итерации нет (`git diff --stat`: только `app/build.gradle.kts`, `App.kt`, 8 файлов `tools/`, `ui/ToolsPanel.kt` — текст описания кнопки) | `git diff 0150f00..d62b5bd --stat` |
| кнопка | `ui/ToolsPanel.kt:294-303` — та же кнопка «Проба слотов (наружняя)», текст объясняет три шага и ~1.5 минуты; маршрут `hvac_ext_probe_btn` → `tools/ToolsBackend.kt:986-992` → `StatusIcons.extProbe` → `HvacFlagsApply.extProbeV2` | `app/src/main/.../ToolsBackend.kt:986-992` |

## §4 не тронуто (проверено диффом `0150f00..d62b5bd`)

- `recargaScript()` (`x7.g`) — без изменений (`tools/HvacFlagsApply.kt:150-172`).
- `flagsCommand()` (`x7.i`) — порядок `temp → batt → exttemp`, `&& echo FLAGS_OK` (`tools/HvacDigitalProps.kt:45-52`), не менялся.
- `via` + bind-маршрут, мониторинг плагина, `reconnectRoot` / `CHANNEL_LOST` / сериализация заливок, JIT — файлы `AdbClient.kt`, `HvacDigitalPlugin.kt`, `JitBootMark.kt`, `AppPerformance.kt` не менялись.

## §5 тесты (счётчик не уменьшен: было 111 → стало 119)

| Файл | Тестов | Что держит |
|---|---|---|
| `TtlScriptTest` | 8 (было 6) | `ENABLE=1/0`, `TTL_RESULT`, `ROUTE_SRC`, `delAllHasNoWordSplitting`, `scopeIsUplinkOnly`, `noUplinkIsHonestFail`, `sanitizeReportsRemovedCount`, `offVerdictNeedsZeroRules` |
| `ToggleNoLieTest` | 4 (было 3) | `toggleSuccessNeedsApplyAndVerify`, `syncDoesNotPushPeriodically`, `prefsAreSourceOfTruth` (новый), `toggleIsSerialized` |
| `ExtProbeV2Test` | 5 (новый) | три имени/порядок, скрипт шага одной командой, порядок чтения, формат строки, пауза 15 с + ничего не удаляем |
| `PersistCleanerTest` | 3 | `isGarbage`, только свои props, три легитимных имени нормализуются |
| `TtlRulesTest` | 6 | вариант только uplink (fallback удалён) |
| `BuildMarkerTest` | 3 | `versionCode=58`, `versionName=1.0.58`, честный `SOURCE_SHA` (`commitSourceIsHonest PASSED`) |

Итог CI: **119 tests completed, 0 failed** (`/tmp/j8.log`, строка `BUILD SUCCESSFUL in 3m 23s`).

Таблица прогонов этой итерации (все провалы показаны честно):

| Прогон | SHA | Итог | Причина |
|---|---|---|---|
| `42387938562` | `0e611d0` | ❌ failure | компиляция `HvacFlagsApply.kt:482` — незакрытая кавычка в `startupDiagnostics` (моя ошибка патча) |
| `42388708679` | `1fe50d1` | ❌ failure | компиляция `HvacFlagsApply.kt:493-494` — наложение правки съело ветку `when` для `SYSUI_TUNER` |
| `42389628649` | `bfa4409` | ❌ failure | тесты: `ToggleNoLieTest.prefsAreSourceOfTruth` (маркер `ui_from_props` остался в KDoc), `TtlScriptTest.offVerdictNeedsZeroRules` (проверка ловила grep-шаблон `^-A POSTROUTING -o`) — 119 тестов, 2 failed |
| **`42390693171`** | **`d62b5bd`** | ✅ **success** | **119 PASSED / 0 FAILED**, job `128457450413`, артефакт `13581512748` (177 129 381 Б) |
| **`42391607364`** | **`a1d4d5b`** (handoff: маркер `SOURCE_SHA=d62b5bd` + отчёты) | ✅ **success** | **119 PASSED / 0 FAILED**, job `128460179802`, артефакт `13581349184` (177 129 336 Б), `BuildMarkerTest > commitSourceIsHonest PASSED`, `BUILD SUCCESSFUL in 4m 15s` |

## §6 что проверить владельцу (по логу + 1–2 скрина)

1. **CarPlay + TTL.** Включить «Фиксация TTL 65» при живом интернете, затем посмотреть в лог:
   - успех виден как `TTL_TRY variant=uplink scope="-o wlan0"` → `TTL_ADD v4 … rc=0` →
     `TTL_V4=1 … VERIFY_V4=OK` → `TTL_SCOPE … uplink_only=yes` → `TTL_CHECK: … → SCOPED_OK`, тумблер `✓`;
   - нет интернета/маршрута — `TTL_TRY variant=none reason=no_uplink` и тумблер `✗` (правило НЕ ставится);
   - выключение — `TTL_SANITIZED removed=N` + `TTL_OFF v4=0 v6=0 result=OK`, тумблер `✗` без «применено частично»;
   - CarPlay должен остаться рабочим всё время (правило только на `-o <uplink>`).
2. **Проба трёх имён.** Нажать «Проба слотов (наружняя)», держать панель на виду ~1.5 минуты
   (три шага по 15 с). Прислать лог и скрин панели: на каком шаге (`1=exttemp`, `2=temp_ext`,
   `3=ext_temp`) появилась наружная температура. Плюс строка `EXT_PROBE_V2 props_follow_prefs=…`.
3. **Тумблеры держат состояние.** Включить «Цифровой климат»/«Батарея», подождать 2–3 минуты в покое
   (без кликов): состояние не должно меняться само, в логе не должно быть `FLAGS_SYNC … action=` с записью,
   только `props_readonly`. `PREF TRACE write key=hvacdigital_*` — ровно одна строка на клик.
4. Если экран по-прежнему не меняется после `FLAG_VERIFY match=ok` — приложить скрин панели и строки
   `EXT_DIAG`/`PLUGIN_DIAG` из начала лога: по ним видно, смонтирован ли плагин референса
   (`sysui_plugin_shortcut_settings`) и какие значения реально в props.

## Чего в этой итерации НЕ делалось (границы)

- Не правились система, `/system`, `/product`, `/vendor`; никаких `setenforce 0`, `pm install`,
  `ctl.restart zygote`, перезагрузок ГУ; глобальное правило TTL отсутствует как класс.
- Не менялись `recargaScript`, `flagsCommand`, порядок `temp→batt→exttemp`, bind-маршрут, JIT, reconnect.
- Своя раскладка строки состояния не рисовалась (эталон — скрин владельца, ждём решения по слоту наружней).
- APK собирает и подписывает владелец: `app-debug` из CI — только проверка сборки/тестов.
