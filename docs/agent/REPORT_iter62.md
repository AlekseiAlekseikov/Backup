# ITER62 — исправление механизмов и функций владельца

База: `49dcbf2fe309b7e513a6a6c9d41b7fcd96903ee1` (ветка `fix/v2`, CI iter61 `43946350664` — success).
`versionCode=63` не менялся, `main` не пушился, `--force` не применялся.

## 1. Фактические SHA

| что | SHA |
|---|---|
| база (iter61) | `49dcbf2fe309b7e513a6a6c9d41b7fcd96903ee1` |
| A1 (JIT) | `e118fbc` |
| A2 + A3 (TTL + звук) | `a61a955` |
| регресс A1/A2 (скрипт + тесты прежних протоколов) | `71ab974` |
| **CODE_SHA** (код + тесты, блок B) | `6fccb1403acb437776cc1c8770c5d3f86b67149b` |
| HANDOFF_SHA (этот отчёт + WORKLOG + маркер) | см. `git log -1` ветки `fix/v2` |

Содержимое `app/buildinfo.properties` в handoff:

```
SOURCE_SHA=6fccb1403acb437776cc1c8770c5d3f86b67149b
```

## 2. Прогон тестов (реальные числа)

- `bash /home/user/runtests62.sh` (production-код + все JVM-тесты, настоящий `/bin/sh`, `/proc`):
  **`OK (1072 tests)`**, failures 0, skipped 0.
- `bash /home/user/typecheck_lock.sh /tmp/w62`: **53 → 53, новых ошибок нет**.
- Новых тестовых методов в iter62: **39** (8 + 7 + 8 + 8 + 4 + 4).
- Локальная Gradle-сборка (`:app:assembleDebug`, `:app:assembleRelease`, `:app:testDebugUnitTest`)
  в этой среде **невозможна**: JDK 11, нет Android SDK и сети. Единственная настоящая сборка —
  CI (debug). Это ⛔, а не «сделано».

## 3. Блок A — механизмы

### A1. JIT: один владелец, номера нет (`e118fbc`)

Причина дефекта (аудит): протокол «номер = 1 + максимум ЖИВЫХ» и ожидание только МЕНЬШИХ номеров.
Освободившийся номер доставался новому участнику НИЖЕ живого ⇒ цикл ожидания не выполнялся ни
разу ⇒ два живых владельца (`a slot=1`, `b slot=2`, `c slot=1`).

Что сделано (`JitOwn.kt`): упорядочивающего ключа больше нет. Секция — единственный файл
`<lock>.own/o`, который появляется АТОМАРНО вместе с identity (`printf` в `t.<pid>.<try>` + `ln`).
Смерть доказывается только по `/proc` (pid + 22-е поле stat + boot_id); снятие мёртвой записи —
`mv` в уникальное `retired.<pid>.<try>` со сверкой содержимого и только потом `rm`. Нет
`flock`/`renameat2`/CAS-unlink. Разбор строк — `read`/`case`/`${var%%}`/`${var#}`, без `sed`
(fork-шторм 8 `sed` на строку убран).

Тесты: `Iter62JitOwnTest` (A1-1…A1-8 + стресс 4 процесса × 30 входов) — **`OK (8 tests)`**;
на коде iter61 **5 из 8 падают**. Стресс считает ФАКТИЧЕСКИХ одновременных владельцев свидетелем,
который можно взять только один раз: нарушений 0, у каждой из 120 попыток есть исход (вход или
отказ с причиной `busy_alive`/`busy_timeout` — требование «при недоказуемой смерти отказ с причиной»).

### A2. TTL: поколение проверяется перед КАЖДОЙ мутацией (`a61a955`)

- `TtlScript.kt`: `_gen_ok` в заголовке и перед каждой из 9 мутаций (`-N`, `-A`, `-D`, `-F`, `-X`,
  `ip rule`); при отсутствии файла поколения скрипт ПУБЛИКУЕТ своё поколение с readback
  (`VAPP_TTL_GEN_INIT`), несовпадение ⇒ `VAPP_TTL_SUPERSEDED` + `exit 90`.
- `NetworkTtl.kt`: поколение публикуется в `filesDir/ttl_slot_gen` ДО отправки скрипта, с readback
  (иначе `gen_not_published`); `supersede` принимает только `SupersedeProof` (поколение +
  подтверждение чтением + доказанная смерть прежнего исполнителя); без доказательства —
  `action=wait`, а не «применено/не применено».
- `TtlState.forceAbandon` больше не используется production-путём (мёртвый путь, назван в отчёте).

Тесты: `Iter62TtlGenTest` — **`OK (7 tests)`**; fail-before на базе: **4 падения**
(`VAPP_TTL_SUPERSEDED gen=7 cur=none`, `VAPP_TTL_GEN_OK gen=9`, `VAPP_TTL_GEN_OK gen=5`, второй проход).
Дополнительно дефект A2.2 воспроизведён на РЕАЛЬНОМ `sh` со сгенерированным скриптом iter61
(`/tmp/t61/script_iter61.sh` + поддельный `iptables`): поздний ON начинал мутировать таблицу при
желании OFF.

**Регресс, найденный полным прогоном (важно):** у скрипта, собранного БЕЗ `guardGen/genFile`,
вызовы `_gen_ok || exit 90` оставались, а функции не было ⇒ `/bin/sh: _gen_ok: not found` и
rc 90 на КАЖДОМ шаге, то есть TTL не работал бы вообще. Исправлено (`71ab974`): при выключенном
ограждении в скрипт попадает `_gen_ok() { return 0; }`. Это же лечение вернуло 30+ тестов
TTL-семейства (`Iter50TtlArgvTest`, `Iter52TtlTest`, `Iter53TtlTest`, `Iter54TtlOwnershipTest`,
`Iter55TtlTest`, `Iter57TtlTest`, `Iter58TtlTest`, `Iter59TtlTest`).

### A3. Звук: подготовка из журнала + однократное безопасное завершение (`a61a955`)

- `LockSound.rebuildPrepared(op, attempt)` восстанавливает подготовку по ЖУРНАЛЬНОМУ пути
  (`voiceapp3_lock_restore_<nonce>.sh`) со сверкой `evidencePath`; иначе `action=no_send`
  (лог `LOCKSOUND_RESTORE_REBUILD`) — вечного pending из-за потерянного in-memory `preparedRestore` нет.
- `LockOp`: `COMPLETION_USED`; handoff-Unknown получает РОВНО ОДИН идемпотентный повтор
  завершения (свой таргет/скрипт, старый pid доказанно мёртв), после чего читающий проход
  закрывает операцию по `observed_stock_after_unknown_restore` либо называет
  `previous_restore_unresolved`. Метка `COMPLETION_USED` durable и переживает неудачный исход
  (иначе «однократность» стала бы вечной).
- Новых кнопок не добавлено.

Тесты: `Iter62SoundRecoveryTest` — **`OK (8 tests)`**; fail-before на базе: **6 падений**
(ожидали 1 отправку завершения / DONE по наблюдаемому стоку, получали 0 и `reconcile_unproven`).
Существующий `Iter61SoundEvidenceTest.crashAfterHandoffStaysFencedEvenWithoutDeviceTraces`
переведён на новый контракт (однократное завершение вместо вечного фенса) — проверяемое свойство
сохранено: операция НЕ объявляется закрытой без terminal.

### A4. Маркер сборки

Порядок соблюдён: код+тесты → `CODE_SHA=6fccb14…` → `SOURCE_SHA=<CODE_SHA>` в `app/buildinfo.properties`
→ этот отчёт + `WORKLOG.md` → handoff. `git rev-parse` внутри build-скрипта не используется.

## 4. Блок B — функции владельца

### B1. Тумблеры

Фактическая таблица (`key` → pref → default; источник желаемого — `settings.change` из панели,
подтверждение — `Prefs2` readback + `WriteAck`, UI читает `<key>State` из `ToolsBackend.state()`):

| key | название | pref | default |
|---|---|---|---|
| `imm` | Полный экран | `app_immersive_mode_enabled` | true |
| `wake` | Запуск при пробуждении | `app_launch_on_wake_enabled` | false |
| `cb` | Полоса климата | `app_climate_bar_enabled` | false |
| `proj` | CarPlay / Android Auto | `app_projection_beta_enabled` | false |
| `follow` | Следовать теме ГУ | `app_theme_follow_hu_enabled` | true |
| `remember` | Запоминать яркость по фарам | `master_remember_brightness` | false |
| `notifBatt` | Батарея автомобиля | `status_icon_battery` | false |
| `notifTemp` | Наружная температура | `status_icon_temp_ext` | false |
| `notifWifi` | Wi‑Fi | `status_icon_wifi` | false |
| `hvacBatt` | Процент заряда | `hvacdigital_batt` | false |
| `hvacTemp` | Цифровой климат-контроль | `hvacdigital_temp` | false |
| `hvacExtTemp` | Цифровая наружная температура | `hvacdigital_temp_ext` | false |
| `lockSound` | Звук блокировки замка | `lock_car_audio_enable` | false |
| `unlockSound` | Звук открытия дверей | `unlock_car_audio_enable` | false |
| `windowDriverOnDoor` | Стекло водителя при открытии двери | `window_driver_on_door` | false |
| `windowOppositeOnDoor` | Противоположное стекло | `window_opposite_on_door` | false |
| `ttl65` | Фиксация TTL 65 | `ttl_65_ativo` | false |
| `perfVideo` | Оптимизация видео 1080p60 | `perf_video_60fps` | false |
| `perfJit` | JIT-оптимизация | `perf_auto_otimizar` | false |

Найдённые причины (код, а не значения):

1. **«Работает, а визуально OFF»** — `Toggles45.boolOrNull` (`Toggles45.kt`, ветка разбора
   значений) считал ПУСТУЮ строку значением `false`; панель зовёт её через `boolState`
   (`ui/ToolsPanel.kt:1699`). Пустое/нечитаемое значение рисовалось как «выключено».
   Исправлено: `"" -> null` («значения нет»).
2. **Тот же симптом через default**: `ToolsBackend.state()` пишет `addProperty(t.key, tri ?: t.default)`
   (`tools/ToolsBackend.kt:173`) — при `unknown` в значении лежит ВСТРОЕННЫЙ default (у 16 из 19
   ключей это `false`). Экран обязан читать `<key>State`; где это нарушалось — исправлено (пункт 3).
3. **Зависимая строка** («Процент заряда» → «Цифровая наружная»): видимость считалась по
   `boolState` (`ui/ToolsPanel.kt:1134`, `applyBattDependency`), то есть по значению из снимка:
   при `unknown` там default `false` ⇒ строка ИСЧЕЗАЛА, хотя сохранённое намерение владельца могло
   быть включено. Плюс клик не перестраивал панель, и композиция обновлялась только со следующим
   снимком. Исправлено: `Toggles45.dependentVisible(confirmed, pending, lastKnown)` берёт ровно ту
   же трёхзначную модель, что и сам тумблер (`pending → on/off → последнее известное`, `null` —
   «неизвестно» и НЕ скрывает строку), а клик по «Проценту заряда» пересчитывает строку сразу.
   Скрытие остаётся только видимостью: значение зависимой настройки не пишется и не сбрасывается
   (`Toggles45.dependentHidesChoice = false`), красных текстов нет, тумблеры независимы.
4. **«Клик одного включает других»**: общего `mutableStateOf`/склеенного состояния в экране нет
   (это не Compose), но было ДВА общих носителя состояния: слитый `state` (`JsonObject`) и
   `boolState/strState`, читающие его для ВСЕХ строк. Клик писал в тот же `state`
   (`ui/ToolsPanel.kt:1236`), а `boolState` защищён списком ключей последнего снимка только при
   `stateLoaded`; `intState` (`ui/ToolsPanel.kt:1708`) не защищён вовсе. Введено правило: положение
   строки считается ТОЛЬКО из (`<key>State`, `pending`, `lastKnown`) — значение из снимка больше не
   участвует в решении о положении, поэтому соседняя строка не может «переехать» от чужого клика.

Тесты: `Iter62TogglesScreenTest` — **`OK (8 tests)`**, экран проверяется ЦЕЛИКОМ (19 ключей):
после клика по одному остальные строки не меняются ни положением, ни подписью; подпись
соответствует применённому состоянию; зависимость; быстрые клики; повторный вход; холодный старт;
устаревший снимок не применяется. Fail-before: `emptyValueIsNotOff` падает на коде iter61
(`expected null, but was false`). Две проверки зависимости используют новую перегрузку
`dependentVisible(confirmed, pending, lastKnown)` и на iter61-коде не компилируются — это
честно помечено 🟡 в §6, а не выдано за «падало и прошло».

### B2. Логи

Найдено и исправлено (реальные дефекты, оба воспроизведены тестом на iter61-коде):

1. **Секреты в журнале.** `LogRedact.cmd/text` намеренно НЕ маскировали IPv4 («нужны в
   диагностике сети»), а SSID/BSSID/MAC не маскировались вообще. Владелец запретил печатать
   IP/MAC/SSID. Добавлено: `ssid|bssid|wifi_ssid|wifi_bssid=<redacted>`, MAC → `<mac>`,
   IPv4 → `<ip4>`, IPv6 → `<ip6>`; имена хостов (`example.invalid`) остаются, длина записи
   ограничена как раньше.
2. **Дубль на границе `.new`.** `FileLogCore.mergePendingLocked` при совпадении последней записи
   активного файла и первой записи `.new` ТОЛЬКО СООБЩАЛ `boundary_same=1`, а строку переносил
   снова — в журнале появлялся ровно один дубль (то, что видел владелец). Теперь выбрасывается
   РОВНО ОДНА граничная запись и только при точном совпадении с последней ЦЕЛОЙ записью активного
   файла; дубли ВНУТРИ `.new` сохраняются (две одинаковые записи — нормальный журнал), причём если
   переносить нечего, `.new` удаляется с проверкой и причина названа.

Что проверено и НЕ требовало правок (названо по коду): путь `getExternalFilesDir(null)/logs` с
fallback `filesDir/logs` и однократной записью фактического пути (`LogFiles.kt`); `LOG_BUDGET`
отбрасывает ЦЕЛЫЕ строки и пишет одну сводку (`LogBudget.kt:245–272`), середину записи не режет;
ротация транзакционная — `.new` + `fsync` + `rename`, при отказе `.new` остаётся свидетельством
(`FileLogCore.kt:289–410`); межпроцессная запись под отдельным `<name>.lock` + внутрипроцессная
очередь, при занятом локе строка уходит в spill-файл процесса, а не теряется (`FileLogCore.kt:59–140`).

Тесты: `Iter62LogMarkerTest` — **`OK (4 tests)`**: 120 маркеров с ротацией (каждая сохранённая
строка целая, без дублей, без `0x00` в хвосте), восстановление `.new` без дубля границы,
`.new` при отсутствии активного файла остаётся, маскирование секретов. Fail-before: 2 из 4 падают
на iter61-коде (`secretsAreRedactedBeforeWriting`, `pendingNewIsMergedWithoutDuplicatingTheBoundaryLine`).

**Смена контракта (обоснование):** iter55 §8 фиксировал обратное — «граничная запись не
выбрасывается», и это закреплено четырьмя тестами (`Iter53LogTest.boundaryMatchKeepsBoth…`,
`Iter54LogTest.boundaryMatchIsReportedButTheRecordIsKept`, `identicalBoundaryRecordsAreBothKept`,
`threeIdenticalRecordsSurviveRecovery`). В iter62 требование владельца прямо противоположно
(«нет дублей при crash на границе»), поэтому тесты переписаны под новый контракт с явным
комментарием; компромисс назван честно: две РАЗНЫЕ записи с одинаковым текстом на самой границе
теперь неразличимы и одна из них будет снята.

### B3. Окна и VHAL

- `VentOwnerTx.planCloseInstall` складывал в `ClosePlan` ССЫЛКУ на карту состояний дверей,
  переданную вызывающим (`tools/VentOwnerTx.kt`, `states = states`). Вызывающий мог изменить её
  после транзакции, и «снимок решения» (по которому решалось, закрывать ли окно) менялся задним
  числом, вне lock и без нового поколения. Исправлено: `states.toMap()`. Ранее уже закрытые
  `snapshot()`/`read()`/`mutate()` отдают копии, а `mutate` коммитит копию.
- `WindowDoors.closeVentWindow`: конкурирующие open/close идут одной критической секцией
  (`planCloseInstall` → `genAtInstall`), откат при отказе VHAL — `rollbackAfterFailedClose` только
  если содержимое равно тому, что записали МЫ, и поколение не изменилось (ABA), иначе запись живёт
  и не трогается; `posAfter == null` не считается закрытием (`ventCloseOutcome`).
- Найденное, но НЕ исправленное в этой итерации (честно): `WindowDoors.setWindowDriverOnDoor` /
  `setWindowOppositeOnDoor` возвращают `status = OK` и `exitCode = 0` независимо от readback
  (`tools/WindowDoors.kt:752–800`): расхождение readback не попадает в статус шага. Это
  следующий P1 (нужен тест с поддельным `CarPropertyManager`).

Тесты: `Iter62VentPlanTest` — **`OK (4 tests)`** (копия входной карты, независимость набора от
плана, поколение на равной мутации/A→B→A, 4 потока конкурирующих open/close без потерянных
владельцев). Fail-before: `planDoesNotAliasTheCallersStateMap` падает на iter61-коде.

### B4. Статус-бар, док, климат

Проверено по коду, правок не потребовалось (требования уже выполнены):
строку состояния делает ПЛАГИН (`HvacDigitalPlugin`, `com.flyme.auto.systemuiplugin`), ставится
`mount --bind` свежего APK на цель из `pm path` с проверкой inode И md5 (`HvacDigitalPlugin.kt:356–400`),
оригинальный system plugin не копируется; «recarga» = `kill $pidof(com.android.systemui)` — как в
референсе (`HvacFlagsApply.kt:162–178`), процесс ПЛАГИНА не убивается нигде.
Климат: наружная — `AC_AMBIENT_TEMP` `0x2140A377`, `getIntProperty`, area 0, decode `(raw − 80)/2`
(`OutsideTemp.kt:14`), `ENV_OUTSIDE_TEMPERATURE` `0x11600703` — ТОЛЬКО fallback при недоступном или
неправдоподобном ambient, с явной строкой `ENV_OUTSIDE_TEMPERATURE ignored` (`StatusIcons.kt:385–435`);
`AC_AMBIENT_TEMP_INVALID` — не гейт, только лог. Уставку плагин читает сам, приложение её не пишет
(`StatusIcons.kt:1073`). Заглушка `+12° 🌤` на месте (`ui/TopBarPanel.kt:43`).
Скриншот 2 (`uploads/скриншот 2.jpg`) доступен, но сверить композицию экрана ГУ в этой среде
нечем — см. ⛔ в §6: это одна строка, а не отмена раздела.

### B5. Обратимость монтирований

| источник | цель | владелец слоя | включение | выключение | сон / reboot / update | удаление приложения |
|---|---|---|---|---|---|---|
| staged APK плагина (`HvacDigitalPlugin`, md5 `5aab306b…`) | APK `GeelyAutoSystemUIPlugin` из `pm path` | наше приложение (`/proc/mounts` = источник истины) | `mount --bind` после свежего `umount` | `umount` цели (6 попыток), затем проверка, что записи в `/proc/mounts` нет | сон — монтирование живёт; reboot — bind НЕ переживает (есть boot-ветка `HvacDigitalPlugin.bootInstall` с одной попыткой), update приложения — как reboot | **остаётся ничего**: bind исчезает вместе с процессом/ребутом; но если ОС убьёт приложение до `umount`, слой висит до перезагрузки — честно названо |
| `filesDir/carlock_sounds/<файл>.wav` (или stage в `/data/local/tmp`) | `/vendor/etc/carlock/<файл>.wav` | наше приложение, реестр `files/locksound_mounts.txt` | bind-mount при ON | `convergeAfterBind(off)` снимает ТОЛЬКО свои слои из реестра | сон — живёт; reboot — исчезает, при ON восстанавливается boot-сверкой; update — сверка журнала | **остаётся системная запись** `Settings.System.lock_car_audio_path` / `lock_car_audio_enable` (это не монтирование); после uninstall ОС её не откатывает ⇒ названный остаток |
| `Settings.System` путь/флаг звука | системные настройки Geely | приложение | `putString` при ON | сброс пути при OFF | reboot — настройка сохраняется | остаётся до ручного сброса (не монтирование) |
| правила `iptables` mangle `VAPP_TTL` + `ip rule oif wlan0` | ядро | приложение (метка поколения в правиле) | скрипт ON | скрипт OFF снимает ТОЛЬКО свои правила/цепь | reboot — правила исчезают (не персистентны); сон — живут | правила остаются до reboot (или до OFF); uninstall их не снимает — названный остаток |
| свойства JIT/zygote | система | платформа | — | — | reboot — сброс | не монтирование; приложение ничего не монтирует |

Чужие слои не снимаются: снятие идёт по реестру/метке, «похожее имя» не является доказательством
владения (`TtlOwnership`, `LockSound` реестр). Обещаний «после uninstall всё вернётся» нет —
названы ровно те остатки, которые ОС не откатывает.

## 5. Что и где изменено (файлы)

- `tools/JitOwn.kt` — §A1 протокол (`_adm_stat_pstart`, `_adm_dead_proven`, `_adm_reclaim`,
  `_jit_admin_enter/_exit`, каталог `<lock>.own`, единственный файл `o` через `ln`).
- `tools/TtlScript.kt` — `_gen_ok` перед каждой мутацией + no-op при выключенном ограждении.
- `tools/NetworkTtl.kt`, `tools/TtlState.kt` — публикация поколения до отправки, `SupersedeProof`,
  `action=wait` без доказательства.
- `tools/LockOp.kt`, `tools/LockSound.kt` — восстановление подготовки из журнала, `COMPLETION_USED`,
  однократное безопасное завершение handoff-Unknown, закрытие по наблюдаемому стоку.
- `tools/Toggles45.kt`, `ui/ToolsPanel.kt` — §B1 (пустое значение, зависимая строка, пересчёт при клике).
- `tools/LogRedact.kt`, `tools/FileLogCore.kt` — §B2 (маскирование, дедуп границы).
- `tools/VentOwnerTx.kt` — §B3 (копия карты состояний).
- Тесты: `Iter62JitOwnTest`, `Iter62TtlGenTest`, `Iter62SoundRecoveryTest`, `Iter62TogglesScreenTest`,
  `Iter62VentPlanTest`, `Iter62LogMarkerTest`; обновлены `Iter58JitLockTest`, `Iter59JitAdminTest`,
  `Iter60JitTicketRaceTest`, `Iter61JitSlotRaceTest`, `Iter61SoundEvidenceTest`, `Iter60TtlFenceTest`,
  `Iter61TtlFenceTest`, `Iter53LogTest`, `Iter54LogTest`, `Toggles45Test`.

## 6. ⛔ по одной строке

- ⛔ ГУ: ни одна команда разрешённого read-only шага 1 §8 НЕ исполнена — в этой среде нет устройства и adb-канала; значит toybox/`ln`/22-е поле `/proc`, `su`, `iptables`, `mount --bind`, свойства JIT на устройстве не проверены.
- ⛔ Локальная Gradle-сборка (`assembleDebug`, `assembleRelease`, `testDebugUnitTest`) невозможна: JDK 11, нет Android SDK и сети; единственная настоящая сборка — CI (debug).
- ⛔ Реальный статус-бар/плагин, слышимость звука, стекло через VHAL, Wi‑Fi/интернет на ГУ не проверялись.
- ⛔ Скриншот 2: сравнение композиции на экране ГУ не выполнено (нет ГУ); код-часть климата проверена по `file:line`.
- 🟡 Две проверки зависимости в `Iter62TogglesScreenTest` используют новую перегрузку и на iter61-коде не компилируются; fail-before доказан для `boolOrNull("")` и для §B2/§B3.
- 🟡 `WindowDoors.setWindowDriverOnDoor/setWindowOppositeOnDoor` сообщают `OK` независимо от readback — названо, не исправлено в этой итерации.
- 🟡 Стресс §A1 и стресс `Iter59JitAdminTest` допускают честный отказ (`busy_alive`/`busy_timeout`) как исход; invitation «все 120 входов состоялись» заменена на «у каждой попытки есть исход» — это следует из требования «при недоказуемой смерти — отказ с причиной».
