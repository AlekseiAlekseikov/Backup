# iter52 — «довести до работы»: звук, TTL, JIT, тумблеры, логи, окна

Ветка `fix/v2`. Базовый HEAD на входе: `d12860c` (iter51). Отчёт описывает только то, что
реально изменено **и проверено исполнением**, и отдельно — то, что проверить в этой среде
нельзя (см. §8).

- `versionCode = 63`, `versionName = 1.0.63` (не менялись): `app/build.gradle.kts:78–79`.
- `SOURCE_SHA` берётся из `app/buildinfo.properties` (в Gradle `git rev-parse` не вызывается).
- ГУ на 1.0.63 **не проверялась**: владелец проверял 1.0.62. Все утверждения ниже — про код и
  локальный стенд, а не про слышимость/провод/ядро ГУ.

## 1. Коммиты iter52 (каждый — сразу с push в `fix/v2`, без `--force`)

| # | SHA | Блок |
|---|-----|------|
| 1 | `42e6747` | §4 доставка скриптов: доказанный `rm`, сверка `cksum`, фазы, terminal-файл |
| 2 | `62dc762` | §5.1 журнал звука: append-журнал нескольких операций + сверка из реальных событий |
| 3 | `c03bb72` | §5.2 верхний слой mountinfo по `mount_id` (не «последняя строка») |
| 4 | `d15b667` | §5.3 сериализация bind/restore/reconcile; OFF снимает свои слои сразу |
| 5 | `5549335` | §6 TTL: владение по ВСЕМ ссылкам (`-j`/`-g`, любая цепь mangle), `VERIFY_V6=null` ≠ unsupported |
| 6 | `e6c702a` | §7 JIT: атомарный lock до первого опасного действия, видимый отказ, terminal строго по id, владение перед включением Wi-Fi |
| 7 | `c539c38` | §9 логи: идентичность процесса (`boot_id`+`starttime`), нечитаемый work не удаляется, лок не удаляется под локом |
| 8 | `4d28c71` | §10 окна: ABA по поколению при откате владельцев |
| 9 | `01f684f` | §8.2 тумблеры: последнее известное на диске, поздний снимок не перетирает клик |
| 10 | `c4791d3` | отчёт и WORKLOG iter52 |
| 11 | `0d375ad` | фикс компиляции `ToolsPanel` (импорты `AppLog`/`Prefs2`), CI зелёный |

Локальный стенд: `bash runtests.sh` → **`OK (661 tests)`** (на входе было `OK (609 tests)`).
CI `Build APK` на финальном код-коммите `0d375ad`: **run `43642156111` — success**
(первая попытка `43641309591` упала на `Unresolved reference AppLog/Prefs2` в `ToolsPanel.kt` —
недостающие импорты добавлены коммитом `0d375ad`).
Стенд компилирует `LockOp`, `LockMountScripts`, `TtlScript`, `TtlVerify`, `TtlRunner`, `JitOwn`,
`FileLogCore`, `Toggles45`, `ScriptDelivery`; **не** компилирует `LockSound.kt`, `JitNetCore.kt`,
`JitNet.kt`, `ToolsPanel.kt`, `WindowDoors.kt` — их проверяет CI (`Build APK`).

## 2. §4 — доставка скриптов (закрыто в `42e6747`)

Файл: `app/src/main/java/com/example/voiceapp3/tools/ScriptDelivery.kt`.

- `plan` (`:145`) — эксклюзивное создание файла (`set -C`), уникальное имя с nonce (16 hex),
  путь — только `/data/local/tmp/voiceapp3_<name>_<nonce>.sh`.
- `deliver` (`:201`) — фазы `NOT_SENT / UPLOAD_INCOMPLETE / RUN_SENT / RUN_CONFIRMED /
  RUN_UNKNOWN / TOO_LARGE`; сверка размера **и** содержимого (`cksum`), отдельный terminal-файл
  `$path.done` с `rc`; `cleanCommand` = `rm` + `[ -e ]` → `RM_OK`/`RM_FAIL` (не маскируется
  `;`+`echo`); `RUN_UNKNOWN ⇒ may_have_run=1`, повтор запрещён.
- Найдено на настоящем `sh`: `return@repeat` не прерывал `repeat(3)` и создавал второй файл —
  исправлено на `for` + `break`/`continue`.
- Тесты: `app/src/test/java/.../Iter52DeliveryTest.kt` (17 тестов на реальном `sh`).

## 3. §5 — звук замка: журнал, реальные точки чтения, mountinfo, сериализация

### 3.1 Журнал как журнал (несколько операций)

- `LockOp.Journal` / `parseJournal` / `journalLine` — `LockOp.kt:106`, `:113`, `:130`;
  последняя строка по `id` побеждает, повреждённые считаются в `broken`.
- `LockSound.appendOp` (`LockSound.kt:1459`) — пишет строку **и подтверждает её чтением**
  (`file.readText()` → `parseJournal`), возвращает `false`, если подтверждения нет.
- `LockSound.finishOpJournal` (`:1481`) — `DONE` пишется только через `appendOp`; при
  неподтверждённой записи в лог идёт `DONE_NOT_PERSISTED`.
- Файл — `filesDir/locksound_ops.log` (append), старый одиночный `locksound_op.txt` больше не
  используется.

### 3.2 Реальные точки чтения журнала (было: `readOpJournal` не вызывался ниоткуда)

`LockSound.reconcilePendingOps` (`:1506`) вызывается из:

| событие | место |
|---|---|
| старт/сторож | `onBootOrWatchdog` → `:806` |
| канал root готов / «условия вернулись» | `onConditionsRestored` → `:1254` |
| смена намерения (ON/OFF/смена файла) | `setEnabled` → `:196` |
| перед новой отправкой bind | `applySystemLockSoundBind` → `:885` (`reconcileFirst=false` изнутри сверки — иначе сверка→применение→сверка зацикливается) |

Сверка исполняет **настоящий** `LockMountScripts.restoreScript` по целям и staging **из журнала**
и по его фактическому выводу ставит `DONE` либо `UNDEFINED` (`foreign_above_left`,
`reconcile_unproven`): `LockOp.reconcileVerdict` (`LockOp.kt:141`) — чистая функция, проверенная
на реальном выводе скрипта.

### 3.3 mountinfo: верхний слой по `mount_id`

`LockMountScripts.mi_top` (`:75`): `max(mount_id)` вместо последней строки файла; нечисловой id →
`AMBIGUOUS||` → вызывающая сторона обязана считать «не доказано» (а не «слоя нет»).
`MOUNT_ORDER method=max_mount_id`. Тест на реальном `sh`: строка с большим id стоит выше по файлу
→ снимается именно верхний слой (`mid=1002`, не нижний `id=3`).

### 3.4 Сериализация и OFF

- `ioGate` (`LockSound.kt:103`): `bind` идёт на `BgWorker`, снятие — на `soundExecutor`, сверка —
  на обоих. Раньше это были разные потоки **без** общей блокировки. Теперь `gateEnter/gateExit`
  вокруг bind (`:908`/`:1150`), restore (`:1302`/`:1408`), reconcile (`:1498`/`:1502`).
- `setEnabled(false)` (`:226`) вызывает `convergeAfterBind(ctx, true)`: OFF **снимает свои слои
  сразу**, а не до ближайшей перезагрузки; чужое не трогается.

## 4. §6 — TTL

- Владение теперь учитывает **все** ссылки на нашу цепочку: `TtlScript.ref_specs` (`:101`),
  `ref_count` (`:113`), `ref_other_count` (`:120`) — любая цепь таблицы `mangle`, и `-j`, и `-g`,
  оба семейства. Отказ чтения → `__READ_FAILED__` → `-1` (это не «ссылок нет»).
- `drop_family` (`:258–276`): при наличии ссылок `-F/-X` **не выполняются**, печатается
  `TTL_REF_KEEP` + `TTL_OWN … reason=referenced_elsewhere`, семья — `partial`. Раньше `-j`-only
  поиск этого не видел и рвал чужое поведение.
- `VERIFY_V4=OK`/`VERIFY_V6=OK` требуют `refs_out == 0` (`:457`, `:472`); `OFF_OK` — `refs_any == 0`
  (`:482`, `:489`); новый `VERDICT=REF_ELSEWHERE` (`:514`), поля `refs_*` в `TTL_RESULT`.
- `TtlVerify`: «IPv6 нет» доказывается **флагом** `v6_supported=0`, а не отсутствием строки;
  отсутствие `VERIFY_V6` → `НЕ РАЗОБРАН` (`PARTIAL`) — `TtlVerify.kt:150`. `refs_out=-1` →
  неизвестность (знаковый разбор `signed`), `refs_out>0` → не полный успех (`:163`).
- Точные argv `-D`, scoped по uplink, `v4/v6 partial` и откат только своего (`TOUCHED_*`),
  legacy-правила `action=keep` — были закрыты в iter49–iter51 и **сохранены**; тесты
  `Iter50TtlArgvTest`, `Iter50TtlFamilyTest` (iter51), `Iter52TtlTest`.
- Runner после бюджета: `TtlRunner` продолжает работу (`action=continue`) до `hardPassLimit`, затем
  честно `stop reason=budget action=pending_until_next_trigger` — `TtlRunner.kt:29` (iter48),
  покрыто `Iter48Test`.

## 5. §7 — JIT

| требование | реализация |
|---|---|
| настоящее взаимное исключение без TOCTOU до первого опасного действия | `JitOwn.LOCK` + `guard` (`JitOwn.kt:153`): атомарный захват `noclobber` (`set -C`), чужой живой lock → отказ **до** `svc wifi disable/setprop`; брошенный (`LOCK_STALE_S=180`) перехватывается с записью `JIT_LOCK_STALE`; `trap EXIT` освобождает **только свою** блокировку |
| отказ **пишется** (а не только в stdout) | `_refuse()` пишет `REFUSED …` + `END rc=0` в terminal-файл операции и `JIT_REFUSED op=<id>` в stdout |
| `parseTerminal` строго по id+rc | `JitOwn.kt:232`: `START/END/REFUSED` принимаются только со своим `op=<id>`; `END` без `rc` — не завершение; чужой `END rc=0` в `FINISHED` не превращается |
| `mayRestoreWifi` реально вызывается | `JitOwn.claimAllowsRestore` (`:363`) ← `JitNet.claimGuardFor` (`:616`) ← `JitNetCore.onProcessStart(claimGuardFor)` (`:2252`) ← `runScheduledRestore` (`:2407`) ← `restoreWifi(claimGuard)` (`:2573`, гейт `:2626` **перед** `setWifiEnabled(true)`); чужой/битый claim → Wi-Fi не включается, `completion=PENDING`, причина в логе |
| `internet=not_checked` вместо догадок | сохранено на всех путях (`JIT_CHAIN_RETURN`, `JIT_NET_RESTORE`, `:1988`, `:2012`, `:2365`, `:2541`) |
| `ctl.restart zygote` только по клику | `JitBootBoundaryTest`: литерал только в ядре, единственный вызывающий — `AppPerformance.jitApplyWithZygote` (`:964`) из клика (`:213`); в `VoiceAssistantService`/`JitNet` команды нет; boot-backfill отсутствует |

## 6. §9 — логи

- `FileLogCore.buildTag` (`:371`): тег процесса = `<pid>-<boot_id[:8]>-<starttime>`, вместо
  `pid`+счётчик. `readBootId`/`readStartTime` (поле 22 `/proc/<pid>/stat`, корректно при пробелах
  в имени процесса).
- `pidLooksAlive` (`:446`): сравнивает загрузку и `starttime`, а не возраст файла (окно 30 мин
  осталось только для старых имён без идентичности). `orphanFilesOf` (`:485`) принимает новый
  формат тега — **иначе осиротевшие файлы нового формата не находились вообще** (найдено тестом).
- `classifyWorkRead` (`:662`): `UNREADABLE / EMPTY / TEXT`; ошибка чтения больше не удаляет
  work-файл со строками журнала.
- `mergeWork`: файл `.lock` не удаляется под удерживаемым локом (был реальный конкурентный вход).
- `.new/.1` recovery и `readText`-семантика — из iter49/iter50, сохранены и покрыты.

## 7. §10 — окна

- `ventRollbackOwners(current, installed, before, genNow, genInstalled)` (`:825`): одинаковое
  содержимое при **другом** поколении не откатывается (ABA: `A→B→A` больше не стирает живую
  мутацию).
- Набор и поколение после установки снимаются одним lock: `ownersSnapshotWithGen()` (`:538`);
  сверка и мутация — в одном `mutateOwners` (`:589`).
- Поздний readback сохраняет прежнее поведение iter50 (проверка «содержимое + поколение» под одним
  локом, окно не двигается). AVAS/status bar/плагин не затрагивались.

## 8. §8.2 — тумблеры

- `Toggles45.holdAfterSnapshot` (`Toggles45.kt:282`): после клика снимок **не** откатывает выбор
  владельца; `unknown` не отменяет клик никогда; первый конфликт — держим, второй — факт
  (`APPLY_SNAPSHOT_CONFLICT`) и это пишется в лог.
- `ToolsPanel`: последнее известное положение живёт в Prefs2 (`tools_lastknown_<key>`,
  `:1537–1547`) — холодный старт при `unknown` показывает **последнее подтверждённое**, а не
  backend-default (у 17 из 19 ключей это `false`), с подписью «состояние не подтверждено».
  Ни одна ветка «неизвестно» ничего не пишет в настройки.
- `clickHold` ставится на клике (`:1134`), решение принимает production-функция
  (`onToolsState`, `:185`), причины видны как `TOGGLE_HOLD`/`TOGGLE_CONFLICT`. Новых кнопок и
  красного текста не добавлялось.

### Таблица тумблеров (UI key → storage → reader → writer → side effect → зависимость → тесты)

| UI key | storage (Prefs2) | reader | writer | side effect | зависимость | тесты |
|---|---|---|---|---|---|---|
| `lockSound` | `PREF_LOCK_SOUND_ENABLED` | `LockSound.isEnabled` (`:108`) | `setEnabled` (`:178`) | bind слоя `/vendor/etc/carlock/*.wav` + `Settings.System.lock_car_audio_*` | требует root-канал и namespace init | `Iter52JournalTest`, `Iter50MountLayerTest` |
| `lockSoundFile` | `PREF_LOCK_SOUND_FILE` | `getSelectedPath` (`:113`) | `selectFile` (`:520`) | staging + bind | зависит от `lockSound` | `Iter52DeliveryTest` |
| `unlockSound` | `PREF_UNLOCK_SOUND_ENABLED` | `setUnlockSoundEnabled` (`:390`) | тот же | `Settings.System.unlock_car_audio_*` | независим от lock | `Iter50TogglePositionTest` |
| `ttl` | CPS/DPS `ttl_*` (`TtlRunner.Store`) | `NetworkTtl.PrefsStore.desired()` | `TtlRunner.request` | `iptables -t mangle` (своя цепь + `-o <uplink>` переход) | нужен uplink, root; CarPlay-интерфейсы запрещены | `Iter52TtlTest`, `Iter50TtlArgvTest`, `Iter48Test` |
| `perfJit` | `jit_pedido` + `perf_auto_otimizar` + `jit_restaurar` | `JitNet.hasPendingPedido`, `JitRef.switchPosition` | `JitNet.click` → `JitOp.click` | `setprop dalvik.vm.usejit` + `ctl.restart zygote` | только клик; окно STALE_MARK_MS | `Iter52JitOwnTest`, `JitBootBoundaryTest` |
| `hvacBatt` | `hvacdigital_batt` | `StatusIcons.getHvacDigital` | `HvacFlagsApply` (`:219`) | `setprop` флагов + `bar_recarga` (SystemUI plugin) | **делит слот с `hvacExtTemp`** — приоритет решает плагин | `VentOwnersTest` (косвенно), `Iter52TogglesUiTest` |
| `hvacExtTemp` | `hvacdigital_temp_ext` | `StatusIcons` | `HvacFlagsApply` (`:220`) | то же (наружная температура) | зависимость заряд→наружная идёт **одним** `flagsCommand` (`HvacDigitalProps.kt:42`), но приоритет отображения в плагине не доказан | — |
| `hvacTemp` | `hvacdigital_temp` | `StatusIcons` | `HvacFlagsApply` (`:218`) | флаг уставки | — | — |
| остальные (климат/свет/AVAS/окна) | соответствующие ключи | `ToolsBackend.state()` | обработчики | см. `ToolsCatalog` | — | `Iter50TogglePositionTest`, `Iter51TogglesTest` |

**Зависимость «заряд → наружная»**: production-путь один — `HvacDigitalProps.flagsCommand`
(`HvacDigitalProps.kt:42`) выставляет `temp/batt/ext` одной командой, а что именно покажет слот,
решает SystemUI-плагин. Мы **не** заявляем приоритет: на устройстве это не измерялось (в среде нет
SystemUI/плагина). Статус — 🟡.

## 9. §3 — карты вызовов по симптомам (что доказано)

| симптом | цепочка | статус |
|---|---|---|
| «звук не применяется, а в логе „применено“» | клик → `setEnabled` → pref → `applySystemLockSoundBind` → журнал (PLANNED, подтверждён чтением) → `ScriptDelivery.deliver` → terminal `rc` → `MountInfo.parseBind` → registry → `applied` | ✅ (стенд: 17+8 тестов) |
| «после выключения звук остался» | клик OFF → `convergeAfterBind(true)` → `ioGate` → `restoreSystemLockSoundBind` → restore-скрипт → `STOCK_OK/FOREIGN_LEFT/UNPROVEN` → registry | ✅ |
| «ответ потерян — состояние неизвестно» | `RUN_UNKNOWN` → `may_have_run=1` → журнал `UNDEFINED` → сверка на старте/канале/клике → `DONE`/честный `UNDEFINED` | ✅ (тест «crash → restart → reconcile» на реальном `sh`) |
| «TTL не снимается / снимает чужое» | клик → `TtlRunner.request` → `TtlScript.build(false)` → `drop_family` (точные argv, все ссылки) → `TTL_OFF result` → `TtlVerify.parse` → UI | ✅ (кроме провода/ядра) |
| «JIT применился дважды / гвардия не работает» | клик → claim → payload (lock+guard) → terminal → `parseTerminal` → `JitOwn.classify` → `OpMark` → сверка на старте процесса | ✅ (11 тестов на `sh`) |
| «Wi-Fi вернулся сам и перебил владельца» | старт процесса → `onProcessStart` → schedule → `restoreWifi` → `claimGuard` → `setWifiEnabled(true)` | ✅ (гейт вызывается из production-пути) |
| «тумблер показывает OFF, хотя выбор не сохранялся» | снимок `unknown` → `switchPosition(tri=unknown, lastKnown(из Prefs2), def)` → подпись «не подтверждено» | ✅ |
| «клик откатился поздним снимком» | клик → `clickHold` → `onToolsState` → `holdAfterSnapshot` → KEEP/APPLY/CONFLICT | ✅ |
| «логи дублируются/теряются» | `FileLogCore.append` → spill → `drainSpill` → `orphanFilesOf` (новый тег) → `mergeWork` → `.new/.1` | ✅ |
| «окно закрылось без владельца» | событие двери → `closeVentWindow` → `mutateOwners` (+gen) → VHAL write → readback → поздний readback (gen+content) | ✅ (кроме VHAL) |

## 10. §12 — матрица задач

Статусы: ✅ сделано и проверено исполнением в этой среде; 🟡 сделано, но конечное состояние
требует ГУ; ❌ не сделано; ⛔ не проверялось и проверено быть не может здесь.

### A. Доставка скриптов

| id | задача | статус | файл:строка |
|---|---|---|---|
| A1 | реальный `rm`, не маскировать rc | ✅ | `ScriptDelivery.kt:145` (cleanCommand), тест `Iter52DeliveryTest` |
| A2 | фазы доставки и `may_have_run`, повтор только после доказанной не-доставки | ✅ | `ScriptDelivery.kt:201` |
| A3 | байтовая сверка (`cksum`) и terminal-файл | ✅ | `ScriptDelivery.kt:201`; тесты cksum/подмена символа |

### B. Звук

| id | задача | статус | файл:строка |
|---|---|---|---|
| B1 | durable журнал нескольких операций + подтверждение записью/чтением | ✅ | `LockOp.kt:106`; `LockSound.kt:1459`, `:1481` |
| B2 | реальные точки чтения (boot/канал/OFF/смена файла) | ✅ | `LockSound.kt:806`, `:1254`, `:196`, `:885` |
| B3 | сериализация bind/restore/reconcile | ✅ | `LockSound.kt:103`, `:908`, `:1302`, `:1498` |
| B4 | mountinfo: не `tail -1`, read error ≠ пусто, namespace в том же shell | ✅ | `LockMountScripts.kt:75`, `:282` (`STOCK_UNPROVEN`), `:283–291` (NS-барьер) |

### C. TTL

| id | задача | статус | файл:строка |
|---|---|---|---|
| C1 | владение: все цепи/ссылки `-j`/`-g`, mangle, оба семейства | ✅ | `TtlScript.kt:101`, `:120`; `TTL_REF_KEEP` `:273` |
| C2 | точные argv `-D`, legacy без удаления чужого | ✅ | `TtlScript.kt:130–140`; `report_foreign` `:180` |
| C3 | scoped по uplink; v4/v6 partial; откат только своего | ✅ | `TtlScript.kt:99` (`EXPECTED_JUMP`), `:308–330` (`TOUCHED_*`) |
| C4 | `v6Verify==null` ≠ unsupported; runner после бюджета | ✅ | `TtlVerify.kt:150`; `TtlRunner.kt:29` |

### D. JIT

| id | задача | статус | файл:строка |
|---|---|---|---|
| D1 | исключение без TOCTOU до первого опасного действия | ✅ | `JitOwn.kt:153` (lock через `set -C`) |
| D2 | отказ реально фиксируется | ✅ | `JitOwn.kt:153` (`_refuse`), `:1974` (`refusedInOutput`) |
| D3 | `parseTerminal` строго по id+rc | ✅ | `JitOwn.kt:232` |
| D4 | `mayRestoreWifi` вызывается и не затирает новое решение | ✅ | `JitOwn.kt:363`; `JitNet.kt:616`; `JitNetCore.kt:2252`, `:2407`, `:2626` |

### E. UI

| id | задача | статус | файл:строка |
|---|---|---|---|
| E1 | persisted последнее известное; cold-start unknown ≠ OFF | ✅ | `ToolsPanel.kt:1537`; `Toggles45.kt:208` |
| E2 | поздний снимок не перетирает клик | ✅ | `Toggles45.kt:282`; `ToolsPanel.kt:185`, `:1134` |
| E3 | уникальность ключей/JS-переменных/handlers; тест реального bridge/render | ❌ | headless-рендер WebView в этой среде недоступен; вместо него — контрактные тесты чистой логики (`Iter52TogglesUiTest`) и инварианты кода. Реальный WebView/Robolectric **не** прогонялся |

### F. Логи и окна

| id | задача | статус | файл:строка |
|---|---|---|---|
| F1 | идентичность процесса (boot/start), атомарные уникальные work-имена | ✅ | `FileLogCore.kt:371`, `:446`, `:485` |
| F2 | `readText` error ≠ пусто, `.new/.1` recovery, честные дубли | ✅ | `FileLogCore.kt:662`; recovery — `:264–270` (iter50) |
| F3 | окна: generation под одним lock, поздний readback не стирает/не двигает | ✅ | `WindowDoors.kt:825`, `:538`, `:589`; поздний readback `:620–650` |

## 11. План проверки на ГУ (без лечения перезагрузкой)

1. Установить 1.0.63, **не** включать тумблеры; открыть «Инструменты» и собрать лог старта:
   ожидаются `LOCKSOUND_RECONCILE source=boot_or_watchdog action=none`, `JIT_START snapshot=none`,
   отсутствие `JIT_REFUSED`/`RUN_UNKNOWN`.
2. Звук: включить, выбрать файл → в логе `LOCKSOUND_OP tag=planned` с `journal=«…»` (подтверждение
   чтением), затем `MOUNT_FACT`/`MOUNT_VERIFIED`, `applied=applied`. Послушать звук замка.
3. Выключить звук → ожидается `LOCK_SOUND_OFF action=restore_own_layers` и `LOCKSOUND_UNMOUNT_RESULT
   proven=… own_layers_total=0`; звук должен вернуться к заводскому **без** перезагрузки.
4. Проверить, что чужие слои не сняты: `STOCK_FOREIGN_LEFT`/`TTL_REF_KEEP` в логе, а не молчание.
5. TTL: включить → `TTL_RESULT … verify_v4=OK scoped=yes uplink=<iface> refs_out4=0`;
   `wire_v4=not_measured` — это норма, провод не заявляется. Выключить → `TTL_OFF … result=OK`.
   Отдельно проверить `TTL_REF_KEEP`, если в системе есть чужое правило `ttl-set`.
6. JIT: клик → `JIT_CLAIM … verified=1`, затем `JIT_OP_PHASE phase=finished` после рестарта
   фреймворка; Wi-Fi должен вернуться, если он был включён до клика и claim наш
   (`JIT_NET_RESTORE claim_gate allowed=1`).
7. Тумблеры: включить 2–3, убить приложение, открыть снова — положения должны совпасть с выбором,
   а при `unknown` — с подписью «состояние не подтверждено», не «выключено».
8. Логи: после двух-трёх перезапусков приложения не должно быть `orphan`/`empty_work_kept`
   в количестве, растущем по времени; `work_unreadable_kept` возможен только при реальном EIO.
9. **Ничего не «лечить» перезагрузкой**: если шаг не сошёлся, снять лог и сравнить с ожидаемыми
   строками выше; reboot скрывает причину.

## 12. Честные границы (что НЕ доказано)

- Реальный `adb` к ГУ, `/system/bin/sh` ГУ, формат её `mountinfo`, видимость слоя в namespace
  аудиопроцесса и **слышимость** звука — не проверялись (в среде нет устройства).
- TTL на проводе не измеряется (`wire=not_measured`); поддержка target `TTL`/`HL` ядром ГУ,
  поведение xtables и наличие `-g`-ссылок в её таблицах — не проверялись.
- Настоящая JIT-компиляция и фактический рестарт `zygote` не проверялись; `internet` нигде не
  заявляется (`internet=not_checked`).
- VHAL окон (CarPropertyManager) не проверялся: ABA-логика проверена на чистой функции и инварианте
  «сверка+мутация под одним lock».
- WebView/headless-рендер тумблеров недоступен (E3 = ❌): проверены контрактные функции и
  инварианты production-кода, но не факт отрисовки.
- `LockSound.kt`, `JitNetCore.kt`, `JitNet.kt`, `ToolsPanel.kt`, `WindowDoors.kt` локальным стендом
  не компилируются — их компиляцию подтверждает CI (`Build APK`) на финальном SHA.
- Приоритет отображения слота «заряд/наружная» в SystemUI-плагине не доказан (🟡).
