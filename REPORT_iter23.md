# REPORT iter23 — 1.0.45 (versionCode 45)

**Ветка:** `fix/v2` · **Репозиторий:** `AlekseiAlekseikov/Backup`
**Коммиты:** `a1506da` (версия) → `25b7f34` (основные правки P0/P1/P2/P2.5/P3/P4) → `721d3f8` (фикс KDoc и строки лога) → `00727bc` (фикс `replace()`).
**CI:** workflow `401712847`, run **41443850090** — **success** (сборка debug, `compileDebugKotlin` зелёный).
**APK:** `/home/user/app-iter23-v1.0.45.apk`, 215 237 785 B, sha256 `9373677a52eb657ab7b5fe047e4d443c4cf7aa88d819b04233cbbec7574f6d6a`.
**Версия в коде:** `app/build.gradle.kts:60` `versionCode = 45`, `:61` `versionName = "1.0.45"`.

> ВАЖНО о статусах ниже. ✅ ставится только там, где есть (а) код с `файл:строка`, (б) строка лога, по которой приёмка проверяется, и (в) референс CEX либо явная пометка «НЕТ В ЭТАЛОНЕ». Проверка **на машине** в этой итерации не выполнялась (лог 1.0.45 с устройства ещё не снят), поэтому фактических подтверждений с авто нет — все пункты помечены 🟡 «код готов, приёмка ждёт лог с машины», кроме явно отмеченных. Это честная позиция: без лога с ГУ ставить ✅ запрещено.

---

## P0 — Root-канал: убрать миграцию порта, честная диагностика

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — в CentralEXAuto 3.3.9 (`/home/user/cex/cex_out/`) нет ни ADB-клиента, ни `setprop *.adb.tcp.port`, ни `ctl.restart adbd`; эталон работает через собственный VHAL-биндинг под системным uid. Наш ADB-канал — собственное решение, поэтому единственный «референс» здесь — прошлые логи устройства, где канал работал: `voiceapp3 (9).log` — `ИТОГ = UP on port=5555, shell uid=0(root) context=u:r:su:s0, dt≈70ms`.

**ФАКТ.**
- `AdbClient.kt:262` `bootstrap()` — полная замена: `:267` `pauses = intArrayOf(0, 2000, 5000)`, `:273` `tryProbePort(ADB_PORT_DEFAULT, 2000, 8000)` — три попытки на 5555 с таймаутом CNXN 8 с, суммарно ≈20 с.
- `:298`–`:303` 65010 — **только чтение**: если уже слушает и даёт uid=0, работаем через него; записи в `service.adb.tcp.port`/`persist.adb.tcp.port` нет нигде (проверено `grep` по всему `app/src/main/java`: совпадения остались только в read-only `getprop` и в комментариях).
- `:313`–`:321` `logAdbPortReadback()` — `ADB_PORT_READBACK: working=… persist.adb.tcp.port=… service.adb.tcp.port=…` и предупреждение `ADB_PORT_STUCK=<v>` без попыток исправить.
- `:330` `adbSelfTestDiagnostics()` — root-free диагностика: `init.svc.adbd`, `getprop` портов/`ro.adb.secure`, `/dev/socket/adbd`, `/proc/net/tcp` (15B3/FDF2), `service/persist.adb.tcp.port`.
- `:355` `shellLocal()` — дешёвые команды (`pm path`, `getprop`, `settings`, `grep /proc/mounts`) через `Runtime.exec` под uid=1000 (не через adb); `:363` `executeSmart()` — сам выбирает adb/локальный путь по состоянию канала.
- Приёмные строки: `:280` `ADB_SELFTEST: port=5555 ok uid=0 dt=…ms`; `:304` `ADB_SELFTEST: failed probes=5555,65010 last=…`; `:306` **одна** итоговая строка `ROOT_UNAVAILABLE reason=no_adb_root_channel probe=5555x3+65010 detail=…`.
- UI-баннер: `ui/ToolsPanel.kt:827`–`843` (`addRootBanner()`), вызывается на странице «Для разработчика» (`:737`); данные — `tools/ToolsBackend.kt:191`–`192` (`rootState`, `rootPort` из `AdbClient.currentState()/currentPort()`). Текст ошибки: «ROOT: канал недоступен — 5555 не отвечает (uid=1000)…».
- Честный текст на странице TTL: `tools/NetworkTtl.kt:111` — вместо «порт 65010 не поднят» теперь «канал недоступен — 5555 не отвечает (uid=1000)…» (скриншот 2 из `uploads/`).

**ПРИЁМКА (по логу).** Должно появиться `ADB_SELFTEST: port=5555 ok uid=0 dt=…` **или** `ADB_SELFTEST: failed probes=5555,65010 last=…` + `ROOT_UNAVAILABLE …`. Порт-мутация в логе не должна встречаться вообще.
**Статус: 🟡** — код и логи есть, строка `ADB_SELFTEST: port=5555 ok uid=0` с устройства 1.0.45 ещё не снята. Отдельный риск ниже.

**Риск, который я обязан назвать прямо.** В логе 1.0.44 (`uploads/voiceapp3-3.log`) канал мёртв: `port=5555 unreachable (dt=3006ms)`, 25× `ERR_ROOT_CHANNEL_UNAVAILABLE (state=NO_ROOT_SH)`. В более старых логах тот же канал жил (`uid=0(root) context=u:r:su:s0`, dt≈70 ms). Удаление миграции — необходимое, но, возможно, не достаточное условие: если adbd на машине убит на уровне прошивки/`init` и сам не поднимается, P0 останется 🟡 и после установки 1.0.45, а вернуть канал можно будет только перезагрузкой ГУ. Это надо проверить первым делом: `getprop init.svc.adbd` в `ADB_SELFTEST diagnostics`.

---

## P1 — Наружная температура: источник только `0x2140A377`

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — в dex CentralEXAuto нет чтения наружной температуры отдельным свойством (панель температуры у эталона — `TempBattPill` со своим сервисом, значение приходит из его VHAL-биндинга). Значения проверялись по факту: в логе 1.0.44 `0x2140A377@0` = 9.2 °C при raw=92 — при реальных 9 °C на улице.

**ФАКТ.**
- `StatusIcons.kt:102` `PROP_AC_AMBIENT_TEMP = 0x2140A377` — единственный источник; `:103`–`:105` резервы `0x2160A6E9` / `0x2140A673` — **только** для строки лога.
- `:355`–`:368` `readTempProp()` — float, иначе int/10, диапазон −50…+70; вне диапазона → `null` с причиной.
- `:378`–`:393` `readTempScan()`; `:385` строка `TEMP_CANDS: A377=… A673=… A6E9=… src=A377` — каждый тик.
- `0x11600703` удалён из источника/`contentText`/дампа, остался только комментарием: `:88` и `:106`; `grep "11600703"` даёт ровно эти две строки-комментария.
- Мёртвые сущности, связанные со старым источником, удалены: `TEMP_PROBE_IDS`, `PROP_ENV_OUTSIDE_TEMP`, `CEX_OUTSIDE_AREAS`, `propConfigAccessAreaType` — `grep` по репозиторию пуст.

**ПРИЁМКА.** Два разных реальных значения на улице → цифра в статус-баре следует за ними; в логе `TEMP_CANDS: A377=<реальное> … src=A377`.
**Статус: 🟡** — код готов, два замера при разных температурах не сделаны (нужна машина).

---

## P2 — Панель SystemUI: диагностика, снятие «тени», честные ошибки

**РЕФЕРЕНС.** `w0/o#c` (CentralEXAuto 3.3.9) — вердикт `VIA` считается по факту `pm path`: путь вида `/data/app/...` ⇒ ставим `persist.sys.hvacdigital.via = install`, иначе bind. Наш `VIA_FORCADA` (если в прошлый раз сработал `bind`, `pm install` пропускается) — прямое следствие этой логики; снятие установленной копии `pm uninstall` — **наше дополнение**, в эталоне этот случай не разбирается.

**ФАКТ.**
- `HvacDigitalPlugin.kt:25`–`:29` — `Status` теперь несёт `mountReason`, `pmCommandFailed`, `shadowedByInstall` (различаем «пакет отсутствует» и «команда не выполнилась»).
- `:44`–`:84` `checkStatus()` — через `AdbClient.shellLocal` (без root); строгий разбор `/proc/mounts`: монтированием считается только строка, где есть `/system/`, `GeelyAutoSystemUIPlugin` **и** ≥3 поля; `ERR_*`/пусто ⇒ `reason=err|nomount` — ложный `mounted=true` из скриншота 3 устранён. Лог `:79` `checkStatus mounts mounted=<b> reason=<…> line=…`.
- `:88`–`:95` `systemPluginPathFallback()` — путь заводского плагина по `ls /system/app|/system/priv-app`, когда `pm path` не выполнился (используется в `enable()`, `:117`).
- `:108`–`:117` `enable()` — при `pmCommandFailed` **не** пишет «OEM pkg not found», а сообщает об отказе команды (`:109`–`:112`), затем падает на fallback-путь.
- `:379` `RESTORE_DIAG` — блок диагностики без root: `PM_PATH`, `PM_LIST`, `SYS_APK`, `MOUNTS`, `VIA`.
- `:386`–`:401` — если `pm path` в `/data/app` ⇒ `pm uninstall [-k --user 0]`, лог `HVAC VERDICT: UNINSTALL=<rc>`; иначе `UNINSTALL=skip`.
- `:408`–`:415` — шаг `restore`: при отсутствии root-канала статус **FAILED** (не «пропущено»), `actual=root_channel_unavailable state=… port=…`, и текст «умонтировать bind и перезапустить SystemUI нельзя».
- `:419`–`:428` — `post_check`: `BLOCKED` без root (мы не проверяли, а не «проверили и плохо»), иначе OK/FAILED по факту `mountReason`.
- `:187`–`:193` — `VIA_PREV` в лог + `VIA_FORCADA=bind` пропускает `pm install`; `PM_PATH_AFTER` печатается в скрипте `restore` после `kill`.

**ПРИЁМКА.** В логе: `checkStatus mounts mounted=false reason=nomount` (при отсутствии bind), `UNINSTALL=<rc>` при `shadowed=true`, шаг `restore` FAILED с `root_channel_unavailable` и `post_check` BLOCKED, когда канала нет; при живом канале — `RESTORE_OK` и `PM_PATH_AFTER=/system/...`.
**Статус: 🟡** — логика и тексты готовы, прогон на машине не делался.

---

## P2.5 — Иконка Wi-Fi в размер цифр

**РЕФЕРЕНС.** `w0/kg#b` (CentralEXAuto 3.3.9) — геометрия текстовой иконки статус-бара: `padH = 10 dp`, `scale = 0.82f`, высота битмапа `(descent − ascent) / 0.61f` (константа `1058810102`), канал `central_ex_status_temp`, notifId 15020/15021, Wi-Fi — 15024.

**ФАКТ.**
- `StatusIcons.kt:551`–`:588` `makeWifiIconCex()` перерисован: `bmpH = (fm.descent - fm.ascent) / 0.61f` (та же высота, что у цифр), пиктограмма вписана в `capPx = textSize * 0.72f` (`glyph`), ширина `1.15 × glyph`.
- Лог `:581`–`:583` `ICON_GEOM: kind=wifi w=…px h=…px (…dp) glyph=…dp level=… padH=10dp`; `glyph=` добавлен и к текстовым иконкам (batt/temp) — `:531`.
- Не тронуты (регресс-зона): клик `android.settings.WIFI_SETTINGS`, notifId 15024, отрисовка дуг и поведение уровней (неактивные дуги бледнее — `p.alpha = 0x55`).

**ПРИЁМКА.** В логе высота `kind=wifi h=` совпадает с `kind=batt/temp h=` (в 1.0.44 было 56 px против 111 px), `glyph≈40 dp`.
**Статус: 🟡** — новый `ICON_GEOM` нужно снять с устройства.

---

## P3 — Стёкла: набор приоткрытых окон переживает рестарт

**РЕФЕРЕНС.** `w0/q1#k0` / `w0.s2` (CentralEXAuto 3.3.9) — при закрытии двери закрывается стекло, если оно всё ещё около POS 10; набор ведётся в памяти сервиса. Persist в SharedPreferences — **наше дополнение** (в эталоне сервис-долгожитель, рестарт процесса не рассматривается).

**ФАКТ.**
- `WindowDoors.kt:47` `PREF_VENT_WINDOWS = "vent_windows"`; `:106`–`:112` `persistVentWindows()` (hex через запятую) + лог `VENT_SAVE: set=[…]`.
- `:115`–`:128` `reseedVentWindows()` — один раз за процесс, лог `VENT_RESEED: area=0x… pos=восстановлено` (или `пусто`); вызов `:311`.
- `:329`–`:338` — при **закрытии** двери закрываются и своё, и противоположное окно, если мы их приоткрывали (даже если тумблер уже выключили).
- `:390`–`:410` — единая диагностика закрытия: `DOOR_CLOSE area=… win=… pos=… inSet=<b> → POS 0|skip reason=not_ours|not_near_10`; persist вызывается на каждой ветке (`:381`, `:395`, `:406`).

**ПРИЁМКА.** В логе: открыть дверь → `POS 10` и `VENT_SAVE`; убить и перезапустить приложение → `VENT_RESEED: area=0x…`; закрыть дверь → `DOOR_CLOSE … inSet=true → POS 0`.
**Статус: 🟡** — прогон не делался.

---

## P4 — Производительность: полный дамп VHAL только по кнопке

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — полного перебора `propertyList` в эталоне нет вовсе (он читает ~10 известных свойств точечно), так что требование «дамп по кнопке» — наше ограничение, а не перенос.

**ФАКТ.**
- `StatusIcons.kt:162`–`:164` — в обычном тике дампа нет: `VHAL_DUMP: skipped in normal run (iter23 P4 — only on demand)`.
- `:189`–`:220` `dumpVhalOnDemand(ctx)` — пишет в **отдельный файл** `filesDir/vhal_dump_<ts>.txt` (приёмник `dumpSink`, `:191`–`:196`), финальная строка `:219` `VHAL_DUMP: end dt=…ms props≈… (on demand) file=…`.
- Кнопка: `ui/ToolsPanel.kt:803` «Диагностика VHAL (полный дамп ~9 с)» → событие `dev.dumpVhal`; обработчик `ui/UiBridge.kt:696`–`:700` выполняет дамп на `BgWorker`.
- Тик: интервал 15 с (`StatusIcons.kt:773`), VHAL-чтений в тике — 2 (`readSocScan` `:640`, `readTempScan` `:656`; итого 4 `getProperty`-вызова: SOC float/int + A377 float + 2 резерва по float/возврату), метрика `:601`–`:618` — `TICK: dt=…ms reads=… overdue=<b>`, раз в минуту на уровне INFO, иначе DEBUG.

**ПРИЁМКА.** В обычном логе **нет** строк `VHAL_DUMP: start`/`props≈1737`; есть `VHAL_DUMP: skipped in normal run`; `TICK: dt=…ms` < 100 (`overdue=false`). В логе 1.0.44 старт-дамп стоил `dt=8979ms` — его больше нет.
**Статус: 🟡** — новые строки нужно снять с устройства.

---

## P5 — TTL: только проверка, механизм не менялся

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — управление TTL в CentralEXAuto отсутствует; это наша фича (`NetworkTtl.kt` / `TtlScript.kt`).

**ФАКТ.** Диффа по существу нет: единственное изменение в `NetworkTtl.kt` — текст причины `:111` (см. P0). `TtlScript.kt` не менялся вовсе (`git diff df79a61..HEAD` его не содержит).
**ПРИЁМКА.** `TTL_CHECK: before=none after=65 rc=0 rule_ttl=65` — требует живого root-канала, то есть **зависит от P0**.
**Статус: ⛔** — заблокировано P0: без канала 5555 включение TTL физически невозможно; в 1.0.44 такой же ⛔ (`ИТОГ = NO_ROOT`). Механизм не трогали согласно мандату.

---

## Регресс-зона (что не должно сломаться)

| Что | Где | Как проверять по логу |
|---|---|---|
| Иконки статус-бара, размер | `StatusIcons.kt:531`, `:581` | `ICON_GEOM … h=` одинаково у batt/temp/wifi |
| Батарея с одним знаком | `StatusIcons.kt` `fmt(lastBattPct)` | `soc=…` / `contentText` |
| Wi-Fi: клик/notifId/дуги | `makeWifiIconCex`, notifId 15024 | клик открывает Wi-Fi-настройки |
| «Следовать теме ГУ» | `ui/UiBridge.kt` `refreshShellTheme()` | смена темы ГУ меняет палитру |
| Стёкла/двери, звук закрытия | `WindowDoors.kt` | `DOOR_POS`, `DOOR_CLOSE`, звук |
| Быстрый первый кадр | — | отсутствие `VHAL_DUMP` на старте (P4) |

---

## Итог

| Пункт | Статус |
|---|---|
| P0 root-канал (без миграции порта) | 🟡 (риск: adbd может быть мёртв на уровне прошивки) |
| P1 наружная температура `0x2140A377` | 🟡 (нужны два замера) |
| P2 панель SystemUI / честные ошибки | 🟡 |
| P2.5 иконка Wi-Fi | 🟡 |
| P3 стёкла после рестарта | 🟡 |
| P4 дамп VHAL по кнопке, `TICK dt` | 🟡 |
| P5 TTL | ⛔ (заблокировано P0) |

**Сборка:** CI `41443850090` — success; APK `/home/user/app-iter23-v1.0.45.apk` (sha256 `9373677a…4f6d6a`).
**Что делать на машине первым делом:** установить 1.0.45, открыть «Для разработчика», снять `ADB_SELFTEST` + баннер ROOT. Если `init.svc.adbd` = `stopped` — канал не поднимется без перезагрузки ГУ; это не регресс 1.0.45, а последствие рестарта adbd в 1.0.44, и его надо лечить перезагрузкой, а не кодом.
