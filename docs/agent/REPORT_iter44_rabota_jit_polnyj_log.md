# iter44 — рабочий JIT + интернет после `zygote` + полный лог без потери событий

База: `fix/v2` `adf0bcd`. Коммиты iter44: `e21a5a9` (код+тесты), `797c463` (`SOURCE_SHA=e21a5a9`).
`versionCode=61`, `versionName=1.0.61` — **не менялись**. Только `fix/v2`, `--force` не применялся.

Уровни доказательности ниже указаны для каждого пункта:
**`код`** — прочитано в исходниках, **`JVM`** — исполнялось в модели (`/home/user/runtests.sh`),
**`A-инт`** — Android-интеграция (реальные `Context`/`SharedPreferences`/файлы) — **не запускалась**,
**`CI`** — GitHub Actions, **`ГУ`** — головное устройство — **ничего не выполнялось**.

Итог локального прогона: **`OK (370 tests)`**, `check_applog.sh` → `APOLOG_TYPES_OK`.

---

## §1.1 Ложное утверждение iter43 «шаг `umount` выключен» — ✅ (код) / ⛔ (ГУ)

**Что было неверно.** В `REPORT_iter43_no_log_loss_ttl_jit.md` строки 50 и 93 утверждали, что шаг
монтирований «остаётся выключенным». Это ложь: шаг **исполняется на каждом клике**. Текст отчёта iter43
исправлен на месте (в файле стоят пометки «ИСПРАВЛЕНО в iter44 §1.1»).

**Что реально исполняется (`код`).**

| шаг | место | условие |
|---|---|---|
| `svc wifi disable` | `JitNetCore.kt:716` (`scriptBody`) | клик существующего тумблера JIT + все блокировки пройдены |
| дренаж `ip rule show \| grep -c 'oif wlan0'` ≤ `DRAIN_LOOP_MAX`×1 с | `JitNetCore.kt:717-721` | там же |
| `umount -l` по точкам `grep ' /data/app/' /proc/mounts` | `JitNetCore.kt:722-725` | там же |
| `setprop dalvik.vm.usejit true/false` | `JitNetCore.kt:726-727` | там же |
| `setprop ctl.restart zygote` | `JitNetCore.kt:727` | ровно один раз, только в цепочке клика |

`chainScript(want)` (`JitNetCore.kt:734`) — то же тело для тестов; `MOUNT_STEP_NOTE="mount_step=present"`
пишется в лог клика, т.е. код сам заявляет о наличии шага.

**Mount namespace (`код`, с оговоркой).** Скрипт выполняется через root-shell приложения, поэтому
`/proc/mounts` читается в namespace этого shell'а (глобальный namespace `init`), а `umount -l`
действует на точки, видимые **в этом** namespace. Отдельная проверка namespace для zygote/system_server
не выполняется — референс её тоже не делает. Это **не** доказательство, что освобождение `/data/app`
увидят все процессы: 🟡, требуется снимок namespace на ГУ (см. §6, шаг ДО).

**Сверка с референсом (`код`, декомпиляция `CentralEXAuto` 3.3.9).**
`w0/b9` case 22 → `j9.onClick` (`jit_pedido`, `perf_auto_otimizar`, `jit_restaurar`) → `la.run` case 0:
`svc wifi disable` → ≤20×1 с ожидание `ip rule show | grep -c 'oif wlan0' == 0` → `umount -l` по строкам
`/proc/mounts`, содержащим `/data/app/` → `setprop dalvik.vm.usejit` → `setprop ctl.restart zygote` →
`ua.run` → `bb` (`getprop` + `/proc/self/maps`) → `j5.d`/`sa` case 3 → `r7.run` (`remove("jit_pedido")`).

| шаг референса | у нас | отличие |
|---|---|---|
| `svc wifi disable` | есть (`:716`) | нет |
| дренаж `ip rule` | есть (`:717-721`) | предел цикла — константа, не 20; ожидание то же |
| `umount -l` по `/data/app/` | есть (`:722-725`) | нет (добавлены `2>/dev/null` и подстановка кавычек) |
| `setprop dalvik.vm.usejit` | есть (`:726`) | нет |
| `setprop ctl.restart zygote` | есть (`:727`), один раз | у референса так же |
| восстановление Wi-Fi после рестарта | **есть** (наша адаптация, `JitNet` `RESTORE_DELAY_MS=15_000`) | у референса Wi-Fi не восстанавливается |
| чтение `jit_restaurar` | **есть** (`planRestore`) | референс флаг не читает |
| проверка namespace | нет | нет (одинаково) |

Адаптация не выдаётся за точную копию: восстановление Wi-Fi и условный возврат — наши добавления,
обратимость `umount` на ГУ не проверялась. **Воздействующих экспериментов на ГУ не выполнялось.**

---

## §1.2 Fail closed на состав и тождество копий — ✅ (JVM) / 🟡 (A-инт)

`код` + `JVM`:

* `JitRef.SnapshotWrite` получил `appeared`/`absent`; `storeSetChanged = setDiff || appeared/absent ||
  idChanged` (`JitNetCore.kt:1631`). Одно **имя** без содержимого больше не считается нормой:
  появление копии между чтением и записью, исчезновение и подмена содержимого под тем же именем
  блокируют отправку скрипта (`chain_undefined`/`store_set_changed` → `action=none commands=0`).
* Тождество копии — `JitNet.identityOf(name, prefs)` (`JitNet.kt:179`, используется в `:229`, `:474`):
  `name@hash`; сравнение идёт только по доказанным идентичностям.
* Тесты: `JitFailClosed44Test` — `appearedCopyBlocksTheScript`, `vanishedCopyBlocksTheScript`,
  `copyReplacedUnderSameNameBlocksTheScript`, `sameCopyAndSameNamesAreNotBlocked`.
* Сторожевые условия «не затирать чужие настройки»: `planRestore` возвращает `owned/foreign/unknown`,
  запись выполняется только по `owned`; `foreign` не трогается (`JitNetCore.kt:247`).
* Direct Boot / второй процесс: состояние читается через `Prefs2` с тем же контекстом, что и запись;
  отдельного теста на `android:directBootAware`/второй процесс **нет** — 🟡.

## §1.3 Условный возврат и продолжение клика — ✅ (JVM) / 🟡 (A-инт)

* `planRestore(prev, ops, opId, snap)` (`JitNetCore.kt:247`): откат только `owned`; если не доказано
  ничего — строка `restore_plan=nothing_proven_owned`, shell не вызывается.
* `identityChanged` (`:273`) — только доказанные `name@hash`; недоступная идентичность даёт
  `identity_unprovable=…` и **не** блокирует (иначе клик ломался бы на чужой ОС).
* `sending`/`undefined` не снимаются по текущему Wi-Fi; возврат исходного состояния Wi-Fi выполняется
  продолжением клика и **не включает** Wi-Fi, если он был выключен (исходное состояние передаётся
  в `planRestore`/продолжение).
* В логах нет запрещённого литерала `rollback`: используются `prev_restore=` и `restore_plan=`.
* `ctl.restart zygote` — ровно одна вхождённая строка в теле скрипта, только в цепочке клика
  (тест `JitRefTest.noOwnMechanismsLeftInCore` и `JitLogsCancelTest` зелёные).
* Тесты: `planRestoreKeepsForeignAndUnreadableUntouched`, `planRestoreOwnsWhenMarkAndSnapshotMatch`,
  `planRestoreTreatsReadErrorsAndMissingMarksAsUnknown`,
  `clickLogDoesNotClaimNoWriteWhenRollbackHappened`.

## §1.4 Тесты P0 production-пути — ✅ (JVM) / ⛔ (A-инт)

Покрыто в `JitIter44Test.kt` на **production-пути** `JitNetCore` (не на `JitFake.Store` в части блокировок):
исчезновение/появление копии → `blocked` без shell; подмена идентичности; `commit=false`;
частичная запись (`wrote_any`); чужое не затёрто; новый процесс — через `resetStateForTest()`.
Wi-Fi ON/OFF — проверено только в модели восстановления; реальные `WifiManager`/`SharedPreferences`
ГУ не запускались — ⛔.

---

## §2 Полный лог без потери событий — ✅ (JVM) / 🟡 (A-инт)

* **§2.1** `LogBudget.DiagState` (`LogBudget.kt:293`): строки диагностики **не отбрасываются**;
  вместо `*_TRUNCATED` пишется одна `*_BUDGET_HIGH … action=continue_writing` на блок за окно
  (`:327`). `truncated()`/`truncatedFor()` всегда `false` (`:346`, `:350`), `droppedFor()` → 0,
  новый `overBudgetFor(label)` (`:358`). Сохранённые поля `truncated/truncatedFor/droppedFor`
  оставлены ради совместимости сигнатур (двойной дубль `DiagState` устранён).
  `DIAG_BYTES=96 КиБ` / `DIAG_LINES=240` — **мягкие ориентиры**, а не отбрасывание.
* **§2.2** `LogFiles`: `MAX_FILE_BYTES=8 МиБ`, `MAX_FILES_PER_DAY=6`, `MAIN_TIER_BYTES=64 МиБ`,
  `RETENTION_DAYS=14`, `FREE_SPACE_MARGIN=8 МиБ`, `MAX_PARTS_TOTAL=48`, `MAX_LINE_BYTES=256 КиБ`.
  Каталог — `getExternalFilesDir(null)/logs/`, фолбэк `filesDir/logs/`; отказ каталога
  (`no_dir`) репортится независимо и больше не инкрементирует счётчик ошибок дважды.
* **§2.3** `writeSplit` (`LogFiles.kt:580`): любая неуспешная часть ⇒ строка **не** считается
  записанной (`split_partial:parts=…` `:626`, `split_all_failed`); старая часть не удаляется,
  пока новая не записана (`oldPartSurvivesWhenNewPartCannotBeWritten`).
* **§2.4** `parseOwn` принимает номера до 9 цифр (`\d{1,9}`, `LogFiles.kt:150`) — переход
  `.999→.1000` больше не «теряет» файл; ретеншн работает во время работы (`RUNTIME_PRUNE_MS=10 мин`,
  `maybeRuntimePrune` `:530`, `LOG_RETENTION_RUNTIME`), ротация `LOG_ROTATE skipped=1`.
* **Тесты реальных файлов** (`production`-путь `LogFiles.append(File dir, …)`):
  `stormAboveOldBudgetKeepsEveryUniqueEvent`, `partNumberBeyondThreeDigitsIsStillRecognised`,
  `unavailableDirectoryIsReportedNotFaked`, `oldPartSurvivesWhenNewPartCannotBeWritten`,
  `retentionWorksWhileRunning`. Чего **нет**: сценарии «6 частей + ≥10 ротаций в одном прогоне»,
  несколько потоков, смена даты и fallback внешний→внутренний каталог — 🟡 (это A-инт-уровень,
  требует `Context`); в JVM проверены отдельные свойства, а не весь сценарий целиком.
* **§2.5 Секреты.** `LogRedact.kt`: длинные base64/hex → `<blob:N>`, `key|token|secret|password|…=…`
  → `<redacted>`. `VERIFY EXEC`, `last_cmd` и stdout проходят через `LogRedact` до записи; traceback
  не содержит тел скриптов. Реальные адреса/пути владельца в этот отчёт не копировались.
  Replay (`replay`-строки) пишется без payload. 🟡 — фильтр регулярный, покрытие проверено тестами
  на маркерах, но не на реальном логе владельца.

---

## §3 Достоверные снимки — ✅ (JVM) / 🟡 (A-инт)

* **§3.1 Реальные времена `pre`.** `JitRef.TtlSnapshotFlow` (`JitNetCore.kt:1270`) хранит в `Op`
  `requestedAt/preStartedAt/preFinishedAt/appliedAt/appliedResult/postScheduledAt/postCollectedAt`.
  `preLabel` (`:1327`): `pre_unknown` (сбор не начинался) / `pre_unfinished` (начат, не завершён) /
  `pre_confirmed` (завершён строго до применения) / `pre_late` / `pre_overlap`. «Подтверждённое до»
  больше не выставляется по запросу — только по факту завершения сбора.
  Начало сбора фиксируется на реальном старте: `JitNet.requestEventSnapshot(..., onCollectStart)`
  (`JitNet.kt:872`, вызов `:909`, подключение `:957`), а не в момент постановки в очередь.
* **§3.2 Привязка callbacks к op ID.** `applyTo(op, …)` (`:1311`) применяет отметку только к текущей
  операции и возвращает `false` для чужой; поздние callback'и предыдущей операции не портят новую
  (`lateCallbackOfPreviousOpDoesNotTouchNewOp`). `postLabel` (`:1344`): `post_without_confirmed_apply`
  / `post_after_apply` / `post_after_apply_not_ok:<result>` — неуспешное применение не выдаётся за
  успех. В лог пишется `TTL_SNAP_PAIR applied_to_op=…` (`JitNet.kt:989`).
* **§3.3 Trailing-снимок независимо от TTL.** `NetworkTtl` больше не выходит до снимка при выключенном
  TTL (`NetworkTtl.kt:388-397`): снимок `net_change:ttl=on|off` заказывается всегда, ограничен
  частотой и single-flight; при пропуске по частоте запрашивается один trailing-снимок после
  затихания шторма (бюджет `EventSnapshots.remainingMs()`). 🟡 — сам trailing-механизм
  (`scheduleTrailing`) в модели проверен, но его поведение на реальных событиях сети не наблюдалось.
* **§3.4 Различение измерений.** Снимок и отчёт разделяют: ассоциацию, IP, маршрут/`ip rule`, DNS,
  `VALIDATED`, ping по IP, ping по имени, HTTPS. Ни один из них не выдаётся за другой
  (`successful ping ≠ HTTPS`, `VALIDATED ≠ интернет`). HTTP(S) не выполняет сетевых изменений.

---

## §4 `END`/`END_CONFIRM` и параллельная диагностика — ✅ (JVM) / 🟡 (CI)

* Убрана «двойная» диагностика: `DiagOnce(2)` и `DiagRunner` больше не работают параллельно —
  **единственный** владелец попыток — `DiagRunner` (`JitNet.kt:780`, запуск `:805`); `DiagOnce`
  остался только счётчиком фактических сборов. `DiagRunner` **не расходует** попытку на
  `CHANNEL_NOT_READY` (`JitNetCore.kt:1174`): сбора не было ⇒ `DIAG_ORCH reason=… status=wait_channel`,
  `attempts` не растёт, исчерпание не объявляется, автоповтор не планируется. Это закрывает дефект
  «к моменту реальной готовности канала собирать уже нечем».
* Одиночный флаг `markDiagAttempted()` больше не «сжигает» диагностику навсегда: повторный вход
  блокируется только при уже **завершённом** сборе (`JitNet.kt:691`,
  `status=skipped why=already_completed`). Повтор — только по реальному событию готовности канала.
* «Страховка» 30 с продолжает ставиться, но её результат — честный `channel_not_ready_after_30s`
  и `partial_android_only`; она не занимает успешный сбор и не выдаётся за него.
* `END`/`END_CONFIRM` на реальных файлах: в JVM проверены `LogFiles` (`append`→закрытие→ротация) и
  записи через `LogKit`; сквозной путь `AppLog → AppFileLogger → LogFiles` в JVM **не исполняется**
  (AppLog в стенде — стаб) — 🟡, типовая корректность подтверждена `check_applog.sh` (`APOLOG_TYPES_OK`)
  и CI, поведение на устройстве — ⛔.

---

## §5 Сборка и CI — 🟡

* Владелец собирает **только** `./gradlew :app:assembleRelease` без `-x` и без тестов; привязки
  тестов к `assemble*` в проекте нет и не возвращалась.
* `:app:testDebugUnitTest` — отдельная задача: `./gradlew :app:testDebugUnitTest`.
* `--dry-run` реального проекта в песочнице **не выполнялся**: ~2 ГБ RAM, Gradle daemon падает OOM
  (`kotlinc` тоже падает в psi2ir без `-Xmx`). Это 🟡, а не «пройдено».
* CI: прогон **`43367216355`** (ветка `fix/v2`, `52ad3b3`) — **success**, job `build`:
  шаги 1–12, 22–25 все `success` (в т.ч. `Gradle build (debug)` и `Upload APK`), 0 `FAILED`, 0 `SKIPPED`.
  Прогон `43367011105` (`797c463`) — `cancelled` политикой `concurrency` при следующем пуше (не ошибка
  сборки). Предыдущий зелёный — `43295574013` (`adf0bcd`).
  ВАЖНО: этот CI **не запускает unit-тесты** (`:app:testDebugUnitTest` в workflow нет) — success сборки
  не равен success тестов; 370 тестов подтверждены только локально на JVM. `rerun-failed-jobs` недоступен (403), права на
  правку workflow у агента отсутствуют — дифф `docs/agent/ci-unit-tests.diff` владелец применяет сам.
* `macOS /proc/self/maps` → `SKIPPED` с причиной (`/proc` отсутствует) — по-прежнему честный SKIPPED.
* 4649 предупреждений `WARNING: D8` и `kotlin metadata 2.4.0` — **не** объявляются косметикой:
  это отдельный открытый дефект (см. ниже), к iter44 не относятся и не «исправлены».

---

## §6 План испытания на ГУ (для владельца)

Ничего из этого агентом не выполнялось. Воздействие — только по согласованию, один клик
существующего тумблера JIT.

**ДО (только чтение, артефакты в `/data/local/tmp`):**

1. `getprop dalvik.vm.usejit`; `getprop | grep -i jit`.
2. `ip rule show`; `ip addr show wlan0`; `dumpsys wifi | head -40` (SSID/BSSID в отчёт не копировать).
3. `grep ' /data/app/' /proc/mounts` **из shell'а, откуда пойдёт скрипт**, и
   `cat /proc/1/mountinfo | grep -c data/app` — снимок namespace (пустой снимок = снимок момента,
   а не доказательство).
4. `ls -l` каталогов логов (внешний `logs/` и `filesDir/logs/`), `df` по разделам.
5. Состояние Wi-Fi (вкл/выкл) и CarPlay/проекция — зафиксировать, т.к. клик выключает Wi-Fi на время.
6. Как забрать **все** части лога: `ls -l` каталога логов, затем копирование всех `*.log*` вместе
   с номерными частями (`adb pull` или `cp` в `/data/local/tmp/logs_owner/`), а не только «последнего»
   файла; `END`/`END_CONFIRM` искать в **закрытых** частях.

**Клик.** Один клик существующего тумблера JIT; условия: Wi-Fi включён, CarPlay не активен,
лог не пишется в момент полного диска, до клика сделать `ls` каталога логов (чтобы части были
различимы).

**ПОСЛЕ — различать четыре разных факта:**

| что | где искать | как отличить |
|---|---|---|
| «отправлено» | `VERIFY EXEC`, `last_cmd` (без payload) | только факт отправки, не результат |
| «перезапуск доказан» | смена PID `zygote`/`system_server`, новый boot-время процесса | **не** boot ID и не `getprop` |
| «JIT работает» | `getprop dalvik.vm.usejit`, `/proc/self/maps` содержит dex из `/data/app/` | успешный `getprop` сам по себе — не доказательство |
| «интернет восстановлен» | `ip addr`/`ip rule` + assoc + `VALIDATED` + ping по IP + ping по имени + HTTPS | ping ≠ HTTPS, `VALIDATED` ≠ интернет |

**Стоп-симптомы** (остановиться и не повторять клик): Wi-Fi не вернулся через 60 с; `ip rule` пуст;
проекция CarPlay пропала и не вернулась; лог не растёт; `zygote` не поднялся. Повторный клик при
этих симптомах запрещён.

**Восстановление Wi-Fi без перезагрузки ГУ:** штатное включение Wi-Fi в настройках ГУ; при
необходимости — тот же тумблер JIT в обратную сторону (он восстановит исходное состояние), и только
потом отдельная диагностика чтением. `setenforce 0`, `pm install`, туннели — запрещены.

---

## Открытые дефекты (не закрыты в iter44)

1. **`AppLog.kt:409–425`** — прежний отбрасывающий `diagBudget`-путь сохранён как обёртка ради
   совместимости; в новом `LogBudget.DiagState` он не отбрасывает, но старые вызовы требуют ревизии
   при следующей правке `AppLog`. ❌
2. **`--dry-run` реального проекта** — OOM песочницы; не выполнялся. 🟡
3. **CI без unit-тестов** — workflow не содержит `testDebugUnitTest`; дифф у владельца. 🟡
4. **Путь `AppLog → AppFileLogger → LogFiles` в JVM не исполняется** (стаб AppLog) — только типы. 🟡
5. **Trailing-снимок** отвязан от TTL, но на реальных событиях сети не наблюдался. 🟡
6. **Fallback каталога логов, полный диск, отказ `delete`, недоступный lock** — только JVM-модель. 🟡
7. **`umount` и namespace** — обратимость и видимость в namespace zygote на ГУ не проверены. ⛔
8. **4649 `WARNING: D8`, kotlin metadata 2.4.0** — не косметика, отдельная задача. ❌
9. **Direct Boot / второй процесс** для чтения настроек — тестов нет. 🟡

## Что НЕ проверено на ГУ

Всё, что связано с устройством: реальный клик, `umount`, `setprop ctl.restart zygote`, восстановление
Wi-Fi, поведение `LogFiles` на реальных каталогах и при полном диске, `SharedPreferences` двух копий,
CarPlay, TTL. На ГУ **не выполнялось ни одной команды**; воздействующих экспериментов не проводилось.
