# REPORT iter60 — JIT, звук, TTL и доставка без засорения ветки

Дата: 2026-10-22. Ветка: `fix/v2`. Версия: `versionCode 63` / `versionName "1.0.63"` (НЕ повышалась,
`app/build.gradle:78–79`).

## 0. База, SHA и размещение файлов

| Что | Значение |
|---|---|
| База (кодовая) | `5c092d7c15872450b140a236ffbf1469cc3fde40` (remote `fix/v2` на входе в iter60) |
| `SOURCE_SHA` (реальный кодовый коммит) | `53a4f9c` — последний коммит, меняющий production-код |
| Коммиты iter60 | `159b088` (§3), `5b8b2c9` (§4), `557bfff` (§5), `f5bfbdc` (§6), `53a4f9c` (§7+§0-перенос) |
| Версия | 63 / 1.0.63, без повышения |

Изменённые production-файлы: `JitOwn.kt`, `TtlState.kt` (новый), `NetworkTtl.kt`, `LockOp.kt`,
`LockSound.kt`, `ScriptDelivery.kt`, `AdbClient.kt`, `VentOwnerTx.kt`.
Новые тесты: `Iter60JitTicketRaceTest` (9), `Iter60TtlFenceTest` (13), `Iter60SoundAttemptTest` (8),
`Iter60DeliveryCollisionTest` (4), `Iter60OwnersLogTest` (4). Обновлены под новый контракт:
`Iter52DeliveryTest`, `Iter59DeliveryTest`, `Iter59TtlTest`.

**Перенос агентских `.md` (откуда → куда)** — `git mv`, содержимое не менялось:

| Откуда | Куда |
|---|---|
| `REPORT_iter54.md` | `docs/agent/REPORT_iter54.md` |
| `REPORT_iter55.md` | `docs/agent/REPORT_iter55.md` |
| `REPORT_iter56.md` | `docs/agent/REPORT_iter56.md` |
| `REPORT_iter57.md` | `docs/agent/REPORT_iter57.md` |
| `REPORT_iter58.md` | `docs/agent/REPORT_iter58.md` |
| `REPORT_iter59.md` | `docs/agent/REPORT_iter59.md` |

В корне остались только не-агентские файлы: `README.md`, `WORKLOG.md`, `scripts/localjvm/README.md`,
`supabase/README.md`, `artifacts/README.md`, `decompiled/README.md`, `ota/README_PERSISTENT_PROPS_READ.md`,
`flutter_telemetry/*`. Все отчёты агента — только в `docs/agent/`.

⛔ **Важная честная поправка.** Проверка `typecheck_lock.sh` в iter59 и в первой половине iter60
писала «0 ошибок / новых нет», потому что путь к компилятору в скрипте был неверным
(`/tmp/kotlinc/kotlinc/lib/...` вместо `/tmp/kotlinc/lib/...`), и `java` вообще не запускала
компилятор. Это ложный зелёный результат. После исправления пути реальная база — **53
предсуществующие ошибки стабов**, и iter60 не добавил **ни одной новой** (53 → 53). То же самое
касается `runtests58.sh`: список файлов не содержал новых `Iter60*Test.kt`, а в цикле не было
проверки `[ -f ]`; до исправления «полный набор» не включал новые тесты (963 вместо 992).

## 1. Итоговая матрица

| Раздел | Состояние | Доказательство |
|---|---|---|
| §3 P0 JIT admin | ✅ сделано | гонка воспроизведена до правки, regression падает на iter59 (5/9) и зелёный после (9/9) |
| §4 P0 TTL durable+fence | ✅ сделано (JVM) | 13 тестов, `NetworkTtl` реально вызывает `TtlState` |
| §5 P0 звук | ✅ сделано (JVM) | 8 тестов, 5 из них падают на коде iter59 |
| §6 delivery collision | ✅ сделано | 4 теста + обновлены 2 старых теста под новый контракт |
| §7 UI/окна/логи | 🟡 частично | `VentOwnerTx` escape закрыт, `FileLogCore` проверен, таблица тумблеров; плагин статус-бара и док на ГУ не проверялись |
| §0 файлы/отчёт | ✅ сделано | таблица переноса выше, `git diff --cached --name-status` проверен перед коммитами |
| ГУ (устройство) | ⛔ не проверялось | см. §8 |

## 2. §3 — JIT admin: фаза выбора закрывает гонку одинаковых ticket

Дефект iter59 (`JitOwn.kt:497–516`, `:519–554`): `ticket = 1 + max(видимых)` вычислялся ДО
публикации своей записи, поэтому два участника получали ОДИН номер; первый входил, второй
публиковался позже с меньшим именем и тоже входил — два владельца одновременно.

Протокол iter60 (блок `jit_admin60.sh`, внедрён в `JitOwn.ownHelpers`, shell-синтаксис проверен
`sh -n`):

1. участник сначала публикует **фазу выбора** `c.<inst>.<pid>` (atomic: temp + `ln`), и только
   потом вычисляет и публикует ticket в `p.<inst>.<pid>`;
2. ожидание требует отсутствия ЖИВЫХ участников в фазе выбора и ЖИВЫХ с меньшим ключом
   (ticket, имя) ⇒ при остановке между ЛЮБЫМИ инструкциями двух владельцев быть не может;
3. identity (`inst`+`pid`+`pstart`) сверяется при повторном входе: чужая запись с нашим именем
   (PID reuse) секции не даёт и не удаляется; повторный вход считается по глубине, выход
   вложенного не выпускает внешнюю секцию;
4. снятие чужой записи — только по её уникальному имени и только после доказанной смерти; при
   отсутствии `/proc` — отказ, а не «считаем мёртвым»;
5. legacy `<lock>.admin`: живой владелец блокирует, мёртвый переносится в уникальное имя со
   сверкой содержимого; `_lock_self_mark` не даёт принять за «свой» чужой файл.

**Failing-before / passing-after** (`Iter60JitTicketRaceTest`, реальные `/bin/sh` + `/proc`,
барьер — production-функция `_adm_barrier_after_ticket`, в бою no-op):

* на коде iter59 (`git checkout -- JitOwn.kt`): `Tests run: 9, Failures: 5` — гонка, фаза выбора,
  оба порядка публикации, nested, identity;
* на коде iter60: `OK (9 tests)` (повторено дважды).

Главный инвариант теста — «свидетель» (`mkdir`) не может быть взят дважды из двух процессов;
при нарушении печатается `VIOLATION`. Участник, который не смог войти, обязан назвать причину
(`REFUSE <who>`), поэтому «тихого» второго владельца не бывает.

Честные границы: semантика `ln`/`mkdir`/`mv` и доступность `/proc` на toybox конкретной прошивки
ГУ не проверялись; реальный zygote/Android-пути — нет.

## 3. §4 — TTL: durable-попытки и fence

Дефект iter59 (`NetworkTtl.kt:346–376`): один перезаписываемый файл `ttl_last_attempt`; прошлый
`Unknown` не сверялся. Сценарий: ON (path=A, ответ потерян) → OFF (path=B записан ПОВЕРХ A) →
поздний shell A применил ON после OFF — итог не совпал с последним намерением, адрес A потерян.

Новое (`TtlState.kt`, вызывается из `NetworkTtl.runTransport`):

| Элемент | Смысл |
|---|---|
| `Attempt(id, evidencePath, want, generation, phase, payloadRc)` | попытка, а не операция |
| журнал `ttl_attempts.log` | append-only, last-wins по `id`; новая попытка НЕ затирает адрес старой |
| фазы | `PREPARED` → `MAY_HAVE_RUN` → `TERMINAL` / `PROVEN_NOT_SENT` |
| `fence(records)` | пока есть незавершённая попытка — новая мутация запрещена |
| `resolveByEvidence` / `classifyEvidence` | закрытие ровно ЭТОЙ попытки по ЕЁ `.done`; `started` без terminal = «неизвестно»; полное отсутствие следов = «не запускалось» |
| `Desired(on, generation)` | намерение владельца отдельно: temp + fsync + `renameTo`, поколение растёт монотонно |
| `migrateLegacy` | старая запись iter59 → `MAY_HAVE_RUN` (НЕ «не отправлено») ⇒ блокирует до readback |

Порядок в production: `persistDesire` → `resolvePreviousAttempts` (fence + read-only сверка) →
`recordAttempt` → send → durable переход фазы. `NotSent` закрывает попытку как
`PROVEN_NOT_SENT` (единственный случай без terminal), `Unknown` оставляет журнал незавершённым,
и следующая мутация ждёт readback. Расхождение «желание новее применения» пишется как
`TTL_DESIRE_LAG`, а не выдаётся за успех.

Тесты (`Iter60TtlFenceTest`, 13): адрес не перезаписывается; fence блокирует; чужой terminal не
закрывает нашу попытку; restart (новое чтение с диска); атомарность/поколения; **ON→OFF во время
pending доводится до OFF**; v4/v6 раздельно; миграция; разбор свидетельства; отказ записи без
адреса; обнаружение усечённой записи; `forceAbandon` не выдумывает rc.

Честно: `FileOutputStream.fd.sync()` — fsync ФАЙЛА; синхронизация КАТАЛОГА в чистой Java
недоступна, поэтому после power loss возможна потеря последней строки журнала (журнал
append-only, поэтому это потеря последней записи, а не «тихое переписывание»).

## 4. §5 — звук: NotSent и различимость crash до/после send

Дефекты (аудит iter59, `LockOp.kt:451–460`, `LockSound.kt`):
1. `NotSent` с НЕПУСТЫМ `restoreEv` давал `previous_restore_unresolved` — а terminal у такой
   попытки не появится никогда ⇒ восстановление звука запрещено бессрочно;
2. crash до send и после send были неразличимы;
3. `scheduleReread` ставил задачу на каждый проход (цепочка и дубли);
4. `finishOpJournal` возвращал `Unit` — вызывающий не знал, что DONE не закреплён.

Сделано:
* прошлая попытка считается закрытой, если её фаза доказанно `restore_not_sent:*` ИЛИ это
  `restore_prepared` без следов запуска (нет скрипта, нет `.started`, нет terminal) — обоснованная
  recovery crash-до-send;
* `restore_outcome_unknown` и `started=1` без terminal остаются блокирующими; проверка позднего
  bind по-прежнему ПЕРВАЯ (`late_shell_unproven`), ослабление §5 её не открывает;
* `NotSent` пишет фазу в durable `reason` и сохраняет identity попытки ⇒ следующий проход
  повторяет безопасно;
* reread: предел `REREAD_MAX_PER_OP=3` на операцию, дедупликация одного запланированного прохода,
  линейная пауза по номеру попытки, снятие флага в `finally`, исчерпание предела логируется;
* `finishOpJournal(...): Boolean`; неподтверждённая запись делает операцию незавершённой
  (`journal_not_persisted:<id>`).

Тесты (`Iter60SoundAttemptTest`, 8): на коде iter59 падают 5, на iter60 — `OK (8 tests)`.

Матрица §5.4 (caller → фаза → исход → действие) — покрыта тестами `reconStep`:

| Caller | Фаза прошлой попытки | Что видно | Действие |
|---|---|---|---|
| сверка (обычный проход) | `restore_not_sent:*` | terminal не будет | повтор restore (новая identity) |
| сверка | `restore_prepared`, следов нет | не начиналось | повтор restore |
| сверка | `restore_prepared`, `started=1` | могло исполниться | стоп, ждём terminal |
| сверка | `restore_outcome_unknown` | неизвестно | стоп, ждём terminal |
| сверка | bind-terminal не доказан | поздний bind | стоп, restore не отправляется |
| агрегатор | любая незавершённая | — | не Ready: bind не планируется, OFF не заявляется |

## 5. §6 — delivery collision

Дефект (`ScriptDelivery.kt:582–591`): при `EXISTS` отправка генерировала НОВЫЙ nonce и исполняла
скрипт по пути B, тогда как durable журнал вызывающего содержал путь A. Свидетельство указывало
не туда.

Сделано: при занятом имени отправка НИЧЕГО не делает и возвращает `State.COLLISION`
(`ERR_SCRIPT_PATH_COLLISION`); `outcome()` отображает её в `NotSent("path_collision_requires_new_identity")`;
порядок `prepare A → durable journal A → upload A` сохраняется, B готовит вызывающий
(`prepare B → journal B → upload B`); в `AdbClient.executePrepared` добавлена ветка
`SCRIPT_UPLOAD COLLISION action=no_send`.

Различение кода покрыто тестом: `payloadRc` (скрипт), `transportRc` (канал), stdout-маркер
`SCRIPT_RUN_DONE`, durable `<path>.done` (`rcFromDone`); при отсутствии обоих — `RUN_UNKNOWN` +
`mayHaveRun` (повтор запрещён).

Таблица caller → фаза → outcome → fence:

| Caller | Фаза доставки | Outcome | Fence |
|---|---|---|---|
| `LockSound.prepareRestore` | `COLLISION` | `NotSent(path_collision...)` | journal не меняется, другая identity |
| `LockSound.restore` | `RUN_UNKNOWN` | `Unknown` (evidence path) | новая отправка запрещена |
| `NetworkTtl.runTransport` | `NotSent` | `PROVEN_NOT_SENT` | fence снят, повтор возможен |
| `NetworkTtl.runTransport` | `Unknown` | `MAY_HAVE_RUN` | fence держит до readback |
| `TtlAttempt` retry | `Finished(no_uplink)` | `RETRY` с новой identity | предыдущая закрыта terminal |

Доказательство старого поведения: на iter59 тест `Iter52DeliveryTest.existingFileIsNeverOverwritten`
зеленел с утверждением `res.plan.path != path` и `RUN_CONFIRMED` — то есть отправка сама меняла имя.

## 6. §7 — UI, окна, логи (объём честно)

**Исправлено:** `VentOwnerTx.mutate` больше не отдаёт живые коллекции (копия под lock + коммит в
конце, поколение всегда растёт). Это закрывает mutable-set escape, который оставался после
iter57 §10 (там закрыли только `read`). Проверено `Iter60OwnersLogTest`: утёкшая ссылка не меняет
состояние и поколение; 800 конкурентных мутаций без потери и без исключений.

**Проверено чтением кода и тестами (не менялось):** `FileLogCore` проверяет `renameTo`/`fsync`,
возвращает файл-свидетельство и признак повреждённого хвоста; у TTL и звука намерение, факт
применения и причина ожидания лежат в РАЗНЫХ ключах, автоматика не переписывает намерение.

**Таблица ключей настроек (статический анализ; на ГУ не проверялась):**

| Ключ | Строка prefs | Объявление | Другие места использования |
|---|---|---|---|
| `PREF_BATTERY` | `status_icon_battery` | tools/StatusIcons.kt:48 | tools/ToolsBackend.kt×1 |
| `PREF_BOOT_INSTALL_BOOTTAG` | `hvac_boot_tag_v2` | tools/HvacDigitalPlugin.kt:51 | — |
| `PREF_BOOT_INSTALL_RESULT` | `hvac_boot_result_v2` | tools/HvacDigitalPlugin.kt:52 | — |
| `PREF_CACHED_SERIAL` | `cached_serial_v1` | tools/ScreenBrightness.kt:70 | — |
| `PREF_CACHED_SERIAL_SRC` | `cached_serial_src_v1` | tools/ScreenBrightness.kt:71 | — |
| `PREF_CACHED_SERIAL_TS` | `cached_serial_ts_v1` | tools/ScreenBrightness.kt:72 | — |
| `PREF_CAR_PAINT` | `pref_car_paint` | ui/CarCardPanel.kt:530 | ui/UiBridge.kt×3 |
| `PREF_DAY` | `bright_day` | tools/ScreenBrightness.kt:47 | — |
| `PREF_DIAG_BOOT` | `adbdiag_boot_id` | tools/AdbClient.kt:53 | — |
| `PREF_DONE_TS_PREFIX` | `nav_pack_done_ts_` | nav/offline/OfflineSheetController.kt:738 | — |
| `PREF_EXT_TEMP` | `status_icon_temp_ext` | tools/StatusIcons.kt:49 | tools/ToolsBackend.kt×2 |
| `PREF_HVAC_BATT` | `hvacdigital_batt` | tools/StatusIcons.kt:54 | tools/HvacFlagsApply.kt×6; tools/ToolsBackend.kt×2 |
| `PREF_HVAC_EXT_TEMP` | `hvacdigital_temp_ext` | tools/StatusIcons.kt:56 | tools/HvacFlagsApply.kt×6; tools/ToolsBackend.kt×2 |
| `PREF_HVAC_TEMP` | `hvacdigital_temp` | tools/StatusIcons.kt:55 | tools/HvacFlagsApply.kt×6; tools/ToolsBackend.kt×2 |
| `PREF_KEEP_AWAKE` | `keep_awake_never_sleep` | tools/ScreenBrightness.kt:68 | tools/ToolsBackend.kt×3; App.kt×1 |
| `PREF_KEEP_AWAKE_APPLIED` | `keep_awake_applied_v1` | tools/ScreenBrightness.kt:69 | — |
| `PREF_KEY_LEGACY_VCODEC` | `vcodec_60fps` | tools/AppPerformance.kt:38 | — |
| `PREF_LIGHT_LEGACY` | `light_theme` | AppTheme.kt:34 | — |
| `PREF_LOCK_SOUND_APPLIED` | `lock_sound_applied` | tools/LockSound.kt:56 | — |
| `PREF_LOCK_SOUND_DELAY` | `lock_car_audio_delay` | tools/LockSound.kt:36 | — |
| `PREF_LOCK_SOUND_ENABLED` | `lock_car_audio_enable` | tools/LockSound.kt:32 | App.kt×1; tools/ToolsBackend.kt×1 |
| `PREF_LOCK_SOUND_NAME` | `lock_car_audio_display_name` | tools/LockSound.kt:34 | — |
| `PREF_LOCK_SOUND_PATH` | `lock_car_audio_path` | tools/LockSound.kt:33 | — |
| `PREF_LOCK_SOUND_PENDING_REASON` | `lock_sound_pending_reason` | tools/LockSound.kt:57 | — |
| `PREF_LOCK_SOUND_VOLUME` | `lock_car_audio_volume` | tools/LockSound.kt:35 | — |
| `PREF_MODE` | `theme_mode` | AppTheme.kt:33 | — |
| `PREF_NIGHT` | `bright_night` | tools/ScreenBrightness.kt:48 | — |
| `PREF_ORIGINAL_TIMEOUT` | `original_screen_off_timeout_ms` | tools/ScreenBrightness.kt:53 | tools/ToolsBackend.kt×1 |
| `PREF_PACKAGES` | `packages` | tools/AppPerformance.kt:39 | — |
| `PREF_PERF_AUTO_OPTIMIZE` | `perf_auto_otimizar` | tools/AppPerformance.kt:36 | tools/ToolsBackend.kt×1 |
| `PREF_PERF_VIDEO` | `perf_video_60fps` | tools/AppPerformance.kt:37 | tools/ToolsBackend.kt×1 |
| `PREF_REMEMBER` | `master_remember_brightness` | tools/ScreenBrightness.kt:46 | tools/ToolsBackend.kt×2; App.kt×1 |
| `PREF_REST_SEC` | `cx_screen_saver_timeout_sec` | tools/ScreenBrightness.kt:49 | tools/ToolsBackend.kt×3 |
| `PREF_TIMEOUT_NOTED` | `screen_timeout_noted_v2` | tools/ScreenBrightness.kt:54 | — |
| `PREF_TOKEN` | `ttl_own_token` | tools/TtlOwnership.kt:30 | — |
| `PREF_TOTAL_PREFIX` | `nav_pack_total_` | nav/offline/OfflineSheetController.kt:737 | — |
| `PREF_TTL_65_ACTIVE` | `ttl_65_ativo` | tools/NetworkTtl.kt:42 | App.kt×1; tools/ToolsBackend.kt×1 |
| `PREF_TTL_65_APPLIED` | `ttl_65_applied` | tools/NetworkTtl.kt:55 | — |
| `PREF_TTL_65_APPLIED_AT` | `ttl_65_applied_at` | tools/NetworkTtl.kt:59 | — |
| `PREF_TTL_65_APPLIED_BOOT` | `ttl_65_applied_boot` | tools/NetworkTtl.kt:61 | — |
| `PREF_TTL_65_APPLIED_GEN` | `ttl_65_applied_gen` | tools/NetworkTtl.kt:235 | — |
| `PREF_TTL_65_INTENT_GEN` | `ttl_65_intent_gen` | tools/NetworkTtl.kt:232 | — |
| `PREF_TTL_65_PENDING_REASON` | `ttl_65_pending_reason` | tools/NetworkTtl.kt:57 | — |
| `PREF_UNLOCK_SOUND_ENABLED` | `unlock_car_audio_enable` | tools/LockSound.kt:38 | VoiceAssistantService.kt×1; App.kt×1; tools/ToolsBackend.kt×1 |
| `PREF_UNLOCK_SOUND_NAME` | `unlock_car_audio_display_name` | tools/LockSound.kt:40 | — |
| `PREF_UNLOCK_SOUND_PATH` | `unlock_car_audio_path` | tools/LockSound.kt:39 | — |
| `PREF_UPDATE_AUTO` | `update.auto` | ui/UiBridge.kt:65 | ui/UpdatesPanel.kt×1 |
| `PREF_VENT_OWNERS` | `vent_owners` | tools/WindowDoors.kt:54 | — |
| `PREF_VENT_WINDOWS` | `vent_windows` | tools/WindowDoors.kt:47 | — |
| `PREF_WAKE_WORD` | `wake_word_enabled` | VoiceAssistantService.kt:152 | projection/ProjectionVoiceGate.kt×1 |
| `PREF_WIFI` | `status_icon_wifi` | tools/StatusIcons.kt:50 | tools/ToolsBackend.kt×1 |
| `PREF_WINDOW_DRIVER_ON_DOOR` | `window_driver_on_door` | tools/WindowDoors.kt:44 | VoiceAssistantService.kt×1; App.kt×1; tools/ToolsBackend.kt×1 |
| `PREF_WINDOW_OPPOSITE_ON_DOOR` | `window_opposite_on_door` | tools/WindowDoors.kt:45 | VoiceAssistantService.kt×1; App.kt×1; tools/ToolsBackend.kt×1 |

**Не сделано / не проверено (❌/⛔, без маскировки):**
* статус-бар как плагин по референсу, док по скриншоту, климат/формула температуры — не
  реализовывалось в iter60; проверка возможна только на ГУ;
* `WindowDoors.closeVentWindow`, VHAL-writes и rollback проверены только fake-тестами; движение
  стекла на ГУ не проверялось (fake это доказать не может);
* ротация/бюджет/обрезание логов на реальной прошивке, многопроцессность — только JVM-тесты;
* референс (APK Google Drive) построчно не сверялся в iter60.

## 7. §0 — мусор в ветке

Перед каждым коммитом проверялся `git diff --cached --name-status`. В коммитах только
production-код, тесты и `.md` в `docs/agent/`. Не коммитились: логи, дампы, bundle, APK, токены.
`git ls-files '*.md'` вне `docs/agent/` больше не содержит агентских отчётов.

## 8. Проверки: что выполнено и что нет

| Проверка | Результат |
|---|---|
| Полный JVM-набор (`runtests58.sh`, W=`/tmp/w60`) | **OK (992 tests)** |
| Только новые тесты | 9 + 13 + 8 + 4 + 4 = 38, все зелёные |
| Failing-before на коде iter59 | JIT 5/9, звук 5/8 падают (до правки) |
| `typecheck_lock.sh` (весь пакет `tools`, Android-стабы) | 53 → 53, новых ошибок нет |
| `sh -n` внедрённого admin-блока | SYNTAX_OK |
| `assembleDebug` / `assembleRelease` | ⛔ не запускались: локальная сборка Android в песочнице невозможна (Gradle 9 → JVM 17, нет сети/SDK) |
| CI на итоговом SHA | см. ниже |

CI: итог будет указан в ответе владельцу после push (юнит-тесты CI не запускает, собирает
`app-debug`).

## 9. Что НЕ проверено на ГУ (требуется отдельное согласие)

⛔ JIT (реальный toybox/zygote, `ln`/`mv`/`/proc`), ⛔ «Wi‑Fi ассоциирован», ⛔ «интернет/страница
открывается», ⛔ TTL (`iptables`, `svc wifi`), ⛔ слышимость звука замка, ⛔ mount/umount, VHAL,
обновление статус-бара, release-сборка. Никаких действий на ГУ без явного разрешения владельца.

## 10. Протокол для ГУ (на согласование, НЕ исполнять)

1. Установить debug-APK, включить лог, выключить TTL-тумблер, дождаться `TTL_*` в логе (без reboot).
2. ON/OFF JIT 2–3 клика; проверить: интернет, `JIT_ADMIN_*` строки, отсутствие двух
   `witness=taken` в одно время.
3. Включить звук замка, закрыть/открыть авто, проверить слышимость и `LOCKSOUND_*`.
4. TTL: ON → OFF → ON, проверить `TTL_DESIRE_LAG`/`TTL_FENCE` отсутствие в норме.
5. Собрать логи и прислать; reboot как «лечение» — только по отдельному согласованию.
