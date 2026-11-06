# REPORT iter33k — 1.0.56

**База:** `9d37799` (1.0.55) · **ветка:** `fix/v2` · **версия:** `versionCode = 56`, `versionName = "1.0.56"`
**Код итерации:** `96d22eb` · **buildinfo (маркер):** `96d22eb` · сборка APK — только в CI (владелец подписывает своим ключом)

Приёмка §5 (печатает приложение при старте):

```
BUILD: versionCode=56 versionName=1.0.56 commit=96d22eb ci_run=local
```

`ci_run=local` — так и должно быть у владельца (нет `GITHUB_RUN_ID`). В CI та же строка даёт `ci_run=<номер прогона>`. `git rev-parse` в сборке больше нет.

---

## §0. Итог одной таблицей

| Пункт мандата | Статус | Доказательство |
|---|---|---|
| §1 P0 тумблеры = флаги + мягкий apply, без `plugin.enable` | ✅ | `StatusIcons.kt:1044-1072`, `HvacFlagsApply.kt:85-117` |
| §1 одна PUSH трёх флагов, `via=bind|nenhum` | ✅ | `HvacFlagsApply.kt:88-96`, `HvacDigitalProps.kt:42-46` |
| §1 UI читает ФАКТ из `getprop`, расхождение → строка в лог | ✅ | `StatusIcons.kt:1096-1107`, `HvacFlagsApply.kt:152-180` (`FLAGS_SYNC`), вызов `StatusIcons.kt:796` |
| §2 P0 авто-apply = `kill $(pidof com.flyme.auto.systemuiplugin)` | ✅ | `HvacFlagsApply.kt:68-71`, строка `FLAG_APPLY method=plugin_restart` (`:51`, `:112`) |
| §2 кнопка «Применить слоты (SystemUI)» с предупреждением | ✅ | `ToolsPanel.kt:295-299`, `ToolsBackend.kt:988-992`, `StatusIcons.kt:1074-1090` |
| §2 «если применяется только через SystemUI — так и записать» | 🟡 | см. §2 ниже: раскладка слотов = SystemUI, прямой путь — кнопка |
| §3 TTL со скоупом (`-o uplink` / `! -o carplay`), глобального нет | ✅ | `TtlScript.kt:78-95`, `TtlRules.kt:56-62` |
| §3 `TTL_NET` / `TTL_TRY` / `TTL_SCOPE` / `TTL_OFF` / `TTL_V4≤?` | ✅ | `TtlScript.kt:43,87,91,112,120`; тесты `TtlScriptTest` 6/6 |
| §3 persist + восстановление при загрузке `TTL_BOOT` | ✅ | `NetworkTtl.kt:279-280`, `TtlRules.kt:95-97` |
| §4 P1 одна попытка `ctl.restart adbd` + `ADB_RECOVERY` | ✅ | `AdbClient.kt:232-266` |
| §5 P0 маркер `buildinfo`, без `git rev-parse`, `ci_run` | ✅ | `app/buildinfo.properties`, `app/build.gradle.kts:44-65,78,83,87`, `App.kt:21-25` |
| §6 P1 без красных текстов, PUSH не раньше `channel_up` | ✅ | `ToolsResult.kt:98`, `OfflineSheetController.kt:596,601`, `StatusIcons.kt:930-940` |
| §7 grep-чек-лист (`ошибка:` → 0) | ✅ | ниже, раздел «Чек-лист grep» |
| CI: сборка + все unit-тесты | ✅ | см. «CI» — 91 PASSED / 0 FAILED |

---

## §1 P0. Тумблеры слотов: клик = флаги + мягкий apply

**Что было (1.0.55):** клик «Процент заряда» вызывал полный `HvacDigitalPlugin.enable` — маунт, `kill SystemUI`, проверки inode/md5. Владелец: «клик тумблера не должен ничего монтировать».

**Что стало:** `StatusIcons.setHvacDigital` ( `StatusIcons.kt:1044` ) целиком передан в новый сервис `HvacFlagsApply`:

1. `HvacFlagsApply.toggle(ctx, pref, value)` — `HvacFlagsApply.kt:85`:
   - пишет наш преф (`Prefs2`, оба хранилища);
   - считает `via` (`viaFor`, `:39`) — хоть один включён → `bind`, все выключены → `nenhum`;
   - **одна** команда `setprop … temp && setprop … batt && setprop … exttemp && setprop … via && echo FLAGS_OK` (`HvacDigitalProps.flagsCommand`, `:42-46` + подстановка `via` в `HvacFlagsApply.kt:95-96`);
   - мягкий apply `pluginRestartScript()` (`:68`): `P=$(pidof com.flyme.auto.systemuiplugin); if [ -n "$P" ]; then kill $P && echo APPLY_KILLED=$P; else echo APPLY_NOOP=no_process; fi` — процессы нет → no-op, ошибки нет;
   - одна строка в лог (`:47`, `:112`):
     `TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=plugin_restart result=ok | ✓`
     и `FLAG_APPLY method=plugin_restart flags=temp:0 batt:1 ext:0 result=ok`.

Маунта, `kill SystemUI`, `setenforce`, `pm install`, `killall` в этом пути **нет вообще** (проверено grep, см. чек-лист).

**UI показывает факт, не память** (§1.3): `getHvacDigital` (`StatusIcons.kt:1096`) отдаёт `HvacFlagsApply.cachedFlags` — значения, прочитанные из `getprop`; префы выравниваются по факту с одной строкой

```
FLAGS_SYNC prefs=temp:0 batt:1 ext:0 fact=temp:1 batt:1 ext:0 action=ui_from_props (источник истины — persist.sys.hvacdigital.*)
```

(`HvacFlagsApply.kt:170-176`, вызов из `syncAllImpl` — `StatusIcons.kt:796`, не чаще раза в 60 с).

**Красных слов нет:** при неудаче тумблер тоже не краснеет — статус шага `PARTIAL`, текст `TOGGLE … ✗` (`StatusIcons.kt:1048-1070`).

---

## §2 P0. FLAG_APPLY: авто — процесс плагина, кнопка — SystemUI

| Путь | Что делает | Где |
|---|---|---|
| Авто (клик тумблера) | `kill $(pidof com.flyme.auto.systemuiplugin)`, no-op если процесса нет | `HvacFlagsApply.kt:68-71` |
| Кнопка «Применить слоты (SystemUI)» | `kill $(pidof com.android.systemui)` с предупреждением «CarPlay и музыка прервутся на ~10 сек» | `ToolsPanel.kt:295-299` → `ToolsBackend.kt:988-992` → `StatusIcons.kt:1074-1090` → `HvacFlagsApply.kt:120-133` |
| Кнопка «Монтировать плагин» (бывший путь тумблера) | `HvacDigitalPlugin.enable`, тоже с предупреждением о перезапуске SystemUI | `ToolsPanel.kt:290-293` → `ToolsBackend.kt:995-999` |

Строка применения одна и та же для обеих кнопок: `FLAG_APPLY method=plugin_restart|sysui_restart flags=… result=ok|noop|denied|failed` (`HvacFlagsApply.kt:51`).

**🟡 Честная запись по мандату §2.4:** флаги (`persist.sys.hvacdigital.*`) плагин не читает вовсе (0 вхождений в его dex — проверено в iter33j). Поэтому «включить слот» = плагин смонтирован + SystemUI перерисовал панель **после** перезапуска своего процесса. Мягкий `kill` процесса плагина даёт перерисовку без перезапуска SystemUI — это и есть штатный путь тумблера. Если у владельца на 1.0.56 слот не появится от мягкого apply — значит срабатывает только полный перезапуск SystemUI: тогда жмётся кнопка из таблицы выше (она для этого и сделана), и по мандату §2.4 это фиксируется как «применяется только через SystemUI». Скриншот 2 (раскладка) в этой итерации не сверялся — **🟡, нужен `adb pull` SystemUI (read-only) на следующую итерацию**, как просит мандат.

---

## §3 P0. TTL + CarPlay: правило только со скоупом

Глобальные правила `-A POSTROUTING -j TTL --ttl-set 65` / `-j HL --hl-set 65` (эталон CentralEXAuto 3.3.9) **не возвращаются** — они и рубили CarPlay.

Новый порядок в `TtlScript` (`TtlScript.kt`):

1. `TTL_NET` (`:43`): `ifaces`, `carplay` (`rndis|ncm|usb|p2p|ap0`), `routes`, `uplink`, текущие счётчики.
2. Выбор варианта (`:78-91`):
   - `uplink` → `SCOPE="-o <uplink>"` (CarPlay не затрагивается вообще);
   - иначе `exclude_carplay` → `SCOPE="! -o <первый carplay-if>"`;
   - нет ни того, ни другого → **отказ**, правило не ставится:
     `TTL_TRY variant=none … reason=no_uplink_no_carplay` + `TTL_SCOPE result=failed reason=no_uplink_no_carplay (глобальное правило запрещено — ломает CarPlay)`.
3. Установка: `iptables -w 5 -t mangle -A POSTROUTING $SCOPE -j TTL --ttl-set 65`, `ip6tables … -j HL --hl-set 65` (`:93-94`), после `del_all()` — идемпотентно.
4. Проверка — **счётчики правил**, `grep -c 'ttl-set 65'` / `'hl-set 65'` (`:101-114`): `TTL_V4=<n> TTL_V6=<n> VERIFY_V4=OK|FAIL VERIFY_V6=OK|FAIL`. Провод (tcpdump) в этой итерации не снимается — в сводке честно `wire_v4=not_measured`.
5. Выключение: `del_all()` = generic-проход по `iptables -S POSTROUTING` (удаляет любые правила с `ttl-set`/`hl-set`, в т.ч. оставшиеся от старых версий) + эталонный цикл `for i in 1 2 3 4 5 … -D` (`:55-76`), затем `TTL_OFF v4=0 v6=0 result=OK` (`:112`).
6. Persist `ttl_65_ativo` и восстановление на загрузке — `NetworkTtl.kt:247-281`; после восстановления печатается
   `TTL_BOOT rules_restored=yes v4=1 v6=1 scoped=true` (`TtlRules.kt:95-97`, вызов `NetworkTtl.kt:279-280`).

Вердикты разбора ответа обновлены под скоуп (`NetworkTtl.kt:100-135`): `SCOPED_OK`, `OFF_OK`, `CHECK_OWNER`; при `TTL_V4 ≥ 1` и `scoped=yes` шаг `OK`, при отсутствии правила — `FAILED`. Упоминания tcpdump из текстов убраны (были в `:49-55`, `:128`).

---

## §4 P1. Рут: одна автоматическая попытка восстановления

`AdbClient.kt:232-266`:

- переход в `SHELL_NO_ROOT` (любой — из `PROBING` или `ROOT`) → `autoRecoverRoot(reason)`;
- флаг `rootRecoveryDone` (`AtomicBoolean`) — **ровно одна попытка на событие**; сбрасывается только при возврате в `ROOT`, зонд 60 с не тронут (`probeRootState`, `:270-288`);
- команда: `( sleep 1; setprop ctl.restart adbd ) >/dev/null 2>&1 &` — как у референса;
- пауза 4 с, повторный `id`, затем одна строка:
  `ADB_RECOVERY action=restart_adbd reason=<причина> root_before=none root_after=root|none result=OK|FAILED wait=4000ms probe=id dt=…ms`;
- успех → `transitionRootState(ROOT, "recovery_ok")`, дальше штатный `ROOT_RESTORED bootrestore pass=1`.

Кнопка «Проверить root» на месте, `ctl.restart zygote` нет, «перезагрузка ГУ как лечение» нет.

---

## §5 P0. Сборка: SHA из маркера, а не из `.git`

- Новый файл `app/buildinfo.properties`:
  `SOURCE_SHA=<7..40 hex>` — его правит **агент** в handoff-коммите.
- `app/build.gradle.kts:44-65`: приоритет **маркер → `GITHUB_SHA` → `local`**; `ciRunId` = `GITHUB_RUN_ID` либо `local`; `buildConfigField("String","GIT_COMMIT", "…$sourceSha")` (`:83`), `CI_RUN` (`:87`); `versionCode = 56`, `versionName = "1.0.56"` (`:78-79`).
- `git rev-parse` в сборке **удалён полностью** — в коде остались только комментарии-запреты (`app/build.gradle.kts:42`, `App.kt:21`), grep по `rev-parse` других вхождений не даёт.
- `App.kt:21-26` печатает `BUILD: versionCode=… versionName=… commit=… ci_run=…`, пустое значение больше не превращается в `unknown`, а станет `local`.
- Тест `BuildMarkerTest` (3 проверки) фиксирует `versionCode=56`, `versionName=1.0.56`, допустимый формат `commit`/`ci_run` и читаемость маркера.

Для владельца: сборка в Android Studio без CI-переменных даст `commit=96d22eb ci_run=local` — ровно то, что в шапке отчёта. Чужой SHA (`fc55245`) больше напечатать неоткуда.

---

## §6 P1. Тексты и порядок PUSH

- `ошибка:` в UI-строках больше нет: `ToolsResult.kt:97-99` → «не применилось: <шаг>», `OfflineSheetController.kt:596,601` → «· не выполнено: …». Итог grep — **0**.
- Статусы неудач в тумблерах — `PARTIAL` (жёлтый), не красный (`StatusIcons.kt:1048-1070`).
- PUSH не раньше готовности канала: `StatusIcons.kt:930-940` — `HVACDIGITAL_PUSH wait reason=channel_not_ready action=retry_once_in_5s`, повтор ровно один (через 5 с), второй раз — `HVACDIGITAL_PUSH deferred reason=channel_not_ready (повтор уже был)`.
- `checkStatus`/диагностика (`bootRestoreHvacDigital`, `tempProbe`) не переписаны — только комментарий обновлён под новый путь тумблера.

---

## §7. Чек-лист grep (фактический вывод)

```
$ grep -rn "BUILD:" app/src/main/java --include=*.kt            → 2  (App.kt:24, LogKit-упоминание)
$ grep -rn "FLAG_APPLY" app/src/main/java --include=*.kt        → 2  (HvacFlagsApply.kt:51, StatusIcons.kt:1082)
$ grep -rn "TOGGLE \[" app/src/main/java --include=*.kt         → 6  (формат в HvacFlagsApply.kt:47)
$ grep -rn "TTL_BOOT" …                                         → 1  (вызов в NetworkTtl.kt:280)
$ grep -rn "TTL_OFF" …                                          → 4  (TtlScript.kt:112, NetworkTtl.kt:195 …)
$ grep -rn "TTL_TRY" …                                          → 4  (TtlScript.kt:87,91, шапка)
$ grep -rn "ADB_RECOVERY" …                                     → 3  (AdbClient.kt:260 …)
$ grep -rn "ошибка:" app/src/main/java --include=*.kt           → 0
$ grep -rn "kill " app/src/main/java --include=*.kt (код)
    HvacFlagsApply.kt:69   kill $P   (плагин, авто-apply)
    HvacFlagsApply.kt:123  kill $P   (SystemUI — только кнопка)
    StatusIcons.kt:1057,1080 — те же две команды в описании шага
    HvacDigitalPlugin.kt:386,621,726 — kill SystemUI внутри клика «Монтировать/Вернуть панель» (см. 🟡 ниже)
$ grep -rn "rev-parse" app/build.gradle.kts app/src/main/java  → только комментарии-запреты
$ grep -rn "ctl.restart zygote" …                              → 0
$ grep -rn "tcpdump" app/src/main/java --include=*.kt          → 0 (в коде)
```

**🟡 Открытый пункт по kill:** полный перезапуск SystemUI остался в трёх **явных** кликах — «Монтировать плагин», «Применить слоты (SystemUI)», «Вернуть панель SystemUI», и один раз в boot-маршруте маунта (`HvacDigitalPlugin.bootInstall`, принято в iter33b). Автоматических перезапусков SystemUI тумблеры больше не делают. Если владелец хочет убрать и boot-маунт — это отдельное решение, в этой итерации не менял (принятое ранее без мандата не трогаю).

---

## CI — доказательство

| Прогон | SHA | Что было | Итог |
|---|---|---|---|
| `42248064281` | `ef721f0` | первая сборка итерации | ❌ **FAILED: 1 тест** — `TtlScriptTest > scopedOnlyNoTcpdump` ждал `! -o $CARPLAY_IF`, а скрипт использует `$CP_FIRST` (мой тест, не код). Исправлено в `f903a1e`. |
| `42249659613` | `f903a1e` | после правки теста | ✅ success, **88 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 25s`, job `128001064019`, артефакт `13521081145` (177 120 940 Б) |
| `42251502684` | `704911c` | добавлен `BuildMarkerTest` | ❌ **FAILED: инфраструктура раннера** — `Install CMake 3.22.1` упал с `ZipException: Archive is not a ZIP archive` (`:jni:configureCMakeDebug[arm64-v8a]`). Код не компилировался — не наш дефект; прогон перезапущен следующим коммитом. |
| **`42252401367`** | **`96d22eb`** | финальный код итерации | ✅ **success**, **91 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 4s`, job `128010643713`, артефакт `13521099296` (**177 121 097 Б**) |
| **`42254379400`** | **`b90a8da`** | handoff-коммит: маркер `SOURCE_SHA=96d22eb` + отчёт + WORKLOG | ✅ **success**, **91 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 22s`, job `128017548566`, артефакт `13523295893` (177 121 100 Б); `BuildMarkerTest > commitSourceIsHonest PASSED` — путь «маркер → `BuildConfig.GIT_COMMIT`» подтверждён в сборке |

Доказательство по тестам (лог джобы `128010643713`):

```
$ grep -c " PASSED$" job.log   → 91
$ grep -c " FAILED$" job.log   → 0
BuildMarkerTest > versionIs56 PASSED
BuildMarkerTest > commitSourceIsHonest PASSED
BuildMarkerTest > markerFileParsable PASSED
TtlRulesTest      → 6/6 PASSED   (uplink, carplay, варианты, отсутствие глобального, текст правил, строки)
HvacFlagsApplyTest→ 7/7 PASSED   (via, flags, parse, TOGGLE-строка, FLAG_APPLY, скрипт apply, applyResult)
TtlScriptTest     → 6/6 PASSED   (подстановка ENABLE, TTL_RESULT, ROUTE_SRC, только скоуп без tcpdump, снятие правил, вердикт)
```

Прирост против 1.0.55: было 72 теста, стало 91 (+19: `TtlRulesTest` 6, `HvacFlagsApplyTest` 7, `TtlScriptTest` +3, `BuildMarkerTest` 3).

---

## Что владельцу делать на машине

1. Установить APK (1.0.56) и открыть шторку «Инструменты» → раздел климата.
2. **Клик по тумблеру слота** («Процент заряда» / «Цифровой климат» / «Цифровая наружная t°») — в `voiceapp3.log` появится ровно одна строка
   `TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=plugin_restart result=ok | ✓` и ниже `FLAG_APPLY …`.
   Маунта и перезапуска SystemUI при этом НЕ будет.
3. Если слот не появился — нажать **«Применить слоты (SystemUI)»** (CarPlay и музыка прервутся на ~10 с). Это единственный «тяжёлый» путь, и он только по кнопке.
4. **TTL** — включить тумблер и проверить, что CarPlay жив. В логе ожидается:
   `TTL_NET ifaces=… carplay=… uplink=… v4=0 v6=0`, затем `TTL_TRY variant=uplink scope="-o wlan0" …`, `TTL_SCOPE result=scoped scoped=yes rule="-A POSTROUTING -o wlan0 -j TTL --ttl-set 65"`, а после перезагрузки ГУ — `TTL_BOOT rules_restored=yes v4=1 v6=1 scoped=true`.
   Выключение — `TTL_OFF v4=0 v6=0 result=OK`.
5. **Рут** — если канал отвалится, в логе будет ровно одна строка
   `ADB_RECOVERY action=restart_adbd reason=channel_dropped root_before=none root_after=root result=OK`.
6. Прислать `voiceapp3.log` — по нему проверю §2.4 (появляется ли слот от мягкого apply).

---

## Открытые пункты на следующую итерацию

1. 🟡 **§2.4:** чем именно применяются слоты — мягким `kill` процесса плагина или только полным перезапуском SystemUI. Нужен лог с машины (`TOGGLE … result=ok` + факт появления слота). Если только SystemUI — зафиксировать в отчёте как штатный путь и, возможно, добавить в кнопку авто-повтор.
2. 🟡 **Раскладка ≠ скриншот 2** — нужен read-only `adb pull` `SystemUI.apk`/плагина с машины, чтобы сверить «строго как в референсе».
3. 🟡 **boot-маунт плагина** (`HvacDigitalPlugin.bootInstall`) один раз перезапускает SystemUI при загрузке — принято в iter33b, в этой итерации не менял (мандат: принятое не трогать). Если правило «SystemUI только по кнопке» распространяется и на загрузку — уберу отдельным шагом.
4. ⛔ Не делалось и не делается: smali-патчи, правки `/system`, `setenforce 0`, `pm install`, `pkill avas`, `ctl.restart zygote`, глобальное правило TTL без скоупа.
