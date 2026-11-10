# REPORT iter33p-fix2 — ответ на REVIEW iter33p-fix1

**Ветка:** `fix/v2`. **Версия:** `versionCode=61`, `versionName="1.0.61"` (владелец подтвердил: 1.0.61 не
устанавливалась, версию не повышаем). **Предыдущий зелёный CI:** `42843273431`, commit `6335ed0`,
job `129923870857`, artifact `13778445997`, 174 PASSED / 0 FAILED.

**Статус полевого прогона JIT на ГУ: НЕ рекомендуется** до зелёного CI по этому коммиту и до
повторной проверки ревьюером — как и требовало ревью.

Ниже — по каждому блокирующему замечанию: что было, что изменено (`файл:строка`), чем это
доказано (имя теста), и чего доказательство **не** покрывает.

---

## 0. Сводка

| Замечание | Состояние | Доказательство |
|---|---|---|
| 1. Тесты сети текстовые | исправлено: тесты гоняют переходы, старый `NetRestoreTest.kt` удалён | `JitNetCoreTest` (18 тестов), `AvasCoreTest` (12) |
| 2. Потеря механизма повтора сети | исправлено: ворота + подтверждённое намерение + `abortAfterNet` + маркер до подтверждения | `JitNetCoreTest.gateOrderAndAborts`, `unconfirmedSaveAborts`, `failedRestoreKeepsMarkerAndNextStartRetries`, `successfulRestoreClearsMarker` |
| 3. `ap0` может отсутствовать штатно | исправлено: read-only проба `P=/OS=/A=`, «нет интерфейса» ≠ «неизвестно» | `absentAp0IsOffNotUnknown`, `unknownStateAbortsWholeOperation` |
| 4. Старые таймеры AVAS / пустой sound / снимок до сети | исправлено: id-привязка, сериализация Car API, `UNVERIFIED` на пустом снимке, снимок после ворот | `AvasCoreTest` (12), `AvasContractTest` (5) |
| 5. Скрипт доказательства AVAS | исправлено: оба входа, автоопределение формата, суммы, мини-дизассемблер, dex+smali-контроли | `scripts/derive_avas_sound_prop.py`, `docs/agent/avas_identity.md` |
| 🟡 TTL 10 мин | обоснование + безопасная политика + тесты | `JitNetCoreTest.expiredMarkerRestoresWifiButNotAp`, `clockWentBackwardsIsNotExpiry`, `AvasCoreTest.expiryIsOwnSnapshotOnly` |
| 🟡 `allConfirmed` без `sanitizeDone`, `isWifiEnabled` ≠ интернет | разделены `essentials` / `fully` / `link` / `sanitize_left`; «интернет» не заявляется | `restoreResultSeparatesEssentialsFromInternet` |
| 🟡 гонка при удалении `ip rule` | удаление по полному `spec` + перечитывание + отказ при неоднозначности; абсолютная защита не заявляется | `sanitizeKeepsForeignRules`, `NetSanitizePlanTest` (7) |

**Локальный прогон поведенческих тестов (не только компиляция):** 50 PASS / 0 FAIL,
воспроизведение — `scripts/localjvm/README.md` (см. §7).

**CI по этому коммиту:** будет добавлен отдельным коммитом после прогона (в тот момент, когда
появится job-id) — без лога джобы слово «PASSED» не используется.

---

## 1. Замечание 1 — тесты сети были текстовыми

**Было:** `NetRestoreTest.kt:65–145` использовал `src`/`contains`/`indexOf`; проверялся исходный
текст, а не поведение.

**Стало:**
- `NetRestoreTest.kt` **удалён** (вместе с `AvasRestoreTest.kt`, который тоже был текстовым).
- Вся логика сети вынесена в чистое ядро **`app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt`**
  (462 строки, ни одного `android.*`): `JitNetModel` (`:30`), `JitFlow` (`:236`), `JitNetCore`
  (`:283`). Android остался только в адаптере `JitNet.kt`.
- **`app/src/test/java/com/example/voiceapp3/tools/JitNetCoreTest.kt`** (370 строк, 18 тестов) —
  фейковое окружение (`FakePrefs`, подставленные `readWifi`/`setWifi`/`apProbe`/`link`/`runScript`,
  управляемые часы) и реальные переходы: ошибка записи префов, гибель процесса между шагами
  (новый экземпляр ядра на тех же префах), отказ подготовки, отказ запуска watcher-а, неудачный
  возврат и следующий старт.
- **`AvasCoreTest.kt`** (261 строка, 12 тестов) — то же для AVAS: `AvasCore` (`AvasCore.kt:26`)
  без Android, «устройство» в памяти.
- **`AvasContractTest.kt`** (106 строк, 5 тестов) — остались только те контракты, которые по своей
  природе статические (единственный файл-снимок, единственная точка записи звука, отсутствие
  «удобных» эвристик). Это осознанно, а не замена поведению.

**Чего это не доказывает:** что переходы ведут себя так же на устройстве (Car API, `WifiManager`,
`ctl.restart` подменены).

---

## 2. Замечание 2 — механизм повтора сети

Было три дефекта: pending удалялся до компенсации, ошибка записи pending игнорировалась, запуск
watcher-а объявлялся `armed` без подтверждения.

**Новый порядок в `AppPerformance.jitApplyWithZygote` (`AppPerformance.kt:1127`):**

1. `JitNet.saveBeforeRestart` (`AppPerformance.kt:1142`) — состояние сети читается и **пишется с
   чтением обратно** (`JitNetCore.kt:315`): `prefs.put` + `savedState()` + сверка `wifi/ap`;
   не подтвердилось — маркер снят, операция не начинается.
2. `writeIntentConfirmed` (`AppPerformance.kt:1359`) — файл намерения + чтение обратно; затем
   ворота `JitFlow.gate` (`JitNetCore.kt:244`, использование `AppPerformance.kt:1146`):
   `ABORT_NO_NET_STATE` / `ABORT_INTENT_WRITE` / `PROCEED`. В обоих отказах — **сеть не тронута,
   рестарта нет** (`AppPerformance.kt:1148–1181`); при отказе записи маркер снимается
   (`JitNet.abandonSaved`, `AppPerformance.kt:1175`).
3. Снимок AVAS — **после** ворот (`AppPerformance.kt:1186`), а не до проверки сети.
4. Сеть + props: `JitNet.runPreRestart` (`JitNet.kt:137` → `JitNetCore.preRestartScript`,
   `JitNetCore.kt:109`): `svc wifi disable` → слив `oif wlan0` до 20 с → `setprop` (как `w0/la.run`).
5. Запуск watcher-а и **подтверждение** запуска: `waitWatcherArmed` (`AppPerformance.kt:1382`,
   6×500 мс) ищет строку `JIT_ZYGOTE_ARMED`, а не `pgrep` (его нет на всех прошивках).
   Не подтвердилось — `JitFlow.Stage.LAUNCH_UNCONFIRMED` (`AppPerformance.kt:1272`).
6. Итог тумблера — `status=PARTIAL` со строкой `JIT_APPLIED … zygote_restart=armed … verify=next_start`:
   **запуск сети и результат JIT — разные состояния** в одной строке, никогда не смешиваются.

**Отмена после сети — `abortAfterNet` (`AppPerformance.kt:1300`):** план берётся из
`JitFlow.abortPlan` (`JitNetCore.kt:258`) и выполняется строго в порядке
(а) погасить watcher и проверить, что он не поднимется (`killWatcherAndVerify`,
`AppPerformance.kt:1343`, `JitFlow.restartStillPossible`, `JitNetCore.kt:276`), (б) вернуть прежние
runtime-props (`JitNet.rollbackProps` → `JitNetCore.propsRollbackScript`, `JitNetCore.kt:136`,
успех — по read-back `rt=`/`pr=`), (в) компенсировать сеть (`JitNet.compensate`, `JitNet.kt:167`),
(г) удалить снимок AVAS **только если рестарт точно не произойдёт** (`AppPerformance.kt:1321`).

**Повтор на следующем старте:** восстановление сети больше не зависит от файла-намерения —
`jitPendingVerify` сначала вызывает `recoverNetOnStart` (`AppPerformance.kt:945` → `:919`), и только
потом смотрит pending. Если возврат не подтвердился, маркер **не снимается**
(`Recovery.KEPT`, `JitNetCore.kt:434`), пишется `keep_saved` и следующий старт повторяет возврат.
Снятие маркера — только по `RestoreResult.canClearMarker` (`JitNetCore.kt:222`), а не по факту
«команду отправили».

**Доказано тестами** (`JitNetCoreTest`): `unconfirmedSaveAborts` (ошибка записи префов → сети не
касаемся), `unknownStateAbortsWholeOperation` (канал молчит → отказ целиком),
`failedRestoreKeepsMarkerAndNextStartRetries` (неудачный возврат → маркер жив → новый экземпляр
ядра на тех же префах возвращает сеть), `successfulRestoreClearsMarker`, `gateOrderAndAborts`
(порядок ворот и состав компенсаций), `propsRollbackIsVerified` (откат props подтверждается
чтением), `restartRiskIsOnlyClearedByConfirmedKill` (`restartStillPossible`),
`launchConfirmationUsesWatcherMarker` (armed — только по строке watcher-а).

**Чего это не доказывает:** поведение `ctl.restart zygote` и `svc wifi disable` на конкретной
прошивке; полевого прогона нет.

---

## 3. Замечание 3 — `ap0` может отсутствовать штатно

**Было:** `ip -o addr show ap0` → ненулевой код/пусто → «неизвестно» → JIT прерывался.

**Стало:** read-only проба `JitNetModel.apProbeCommand()` (`JitNetCore.kt:101`) печатает три факта:
`P=<есть каталог /sys/class/net/ap0>`, `OS=<operstate>`, `A=<есть IPv4|0>`; разбор —
`JitNetModel.parseApProbe` (`JitNetCore.kt:91`):
- `P=0` → **`false` (AP выключен — штатно, даже если интерфейса нет)**;
- `P=1, A>0` → `true`; `P=1, A=0` → `false`;
- `null` — **только** когда канал не ответил (пусто или `ERR_…`).

Неизвестное состояние не превращается в «выключено»: `JitNetCore.readState` (`JitNetCore.kt:296`)
возвращает `null`, и это `ABORT_NO_NET_STATE`, а не «продолжаем, решив, что AP был выключен».
Никаких `svc`, `startSoftAp`, `settings put` в пробе нет.

**Доказано:** `JitNetCoreTest.absentAp0IsOffNotUnknown` (нет `ap0` → AP выключен, операция идёт),
`unknownStateAbortsWholeOperation` (канал не ответил → отказ).

---

## 4. Замечание 4 — старые таймеры AVAS, пустой sound, снимок до сети

**4.1 id-привязка.** Планирование и выполнение вынесены в `AvasCore` (`AvasCore.kt:26`):
`scheduleRestore` (`:101`) ставит задания **на id своего снимка**, `attempt(id, …)` (`:126`)
первым делом сравнивает `snap.id != id` → no-op `stale_task`; удаление снимка (`:163`) требует
и `lastWindow`, и «снимок всё ещё мой», и подтверждение. `discard` (`:179`) удаляет только свой
снимок. Идентификатор монотонный (`AvasCore.nextId`, `:56`): две операции в одну миллисекунду не
делят id.

**4.2 Сериализация Car API.** Все обращения — под одним `lock` (`AvasCore.kt:38`): `save`,
`attempt`, `discard` не переплетаются, при этом работа всё ещё уходит в пул `BgWorker` (4 потока),
т.е. UI не блокируется, но гонки общих ссылок Car API исключены. `openDevice/closeDevice` —
внутри той же критической секции (`:75`, `:139`).

**4.3 Пустой sound-снимок.** `AvasRestore.verdict` (`AvasRestore.kt:128`) на пустом снимке даёт
`UNVERIFIED`, а не `already_ok` (`all` на пустом списке не используется как «всё совпало»;
`verdictReason` = `sound=not_captured`). Дополнительно: если режим **не читается**, попытка не
пишет ничего и возвращает `UNVERIFIED` (`AvasCore.kt:147`) — «неизвестно» не равно «выключено»,
слепая запись недоказуема, снимок остаётся до следующего окна.

**4.4 Снимок до сети.** Снимок AVAS делается **после** ворот сети/намерения
(`AppPerformance.kt:1186`); при любом отказе после сети `abortAfterNet` его удаляет, если рестарт
точно не состоится (`AppPerformance.kt:1321`). Сценарий «снимок остался от отменённой операции»,
на который указывало ревью, закрыт.

**Доказано** (`AvasCoreTest`): `overlappingOperationsUseOwnWindows` (две перекрывающиеся операции),
`staleTasksDoNotTouchNewSnapshot` (задания старого снимка не трогают новый),
`alreadyOkKeepsSnapshotUntilLastWindow` и `nextStartSchedulesOwnSnapshot` (последнее окно и
следующий старт), `emptySoundSnapshotIsUnverified`, `unreadableDeviceWritesNothing`,
`writeWithoutReadBackIsFailed`, `discardOnlyOwnSnapshot`, `expiryIsOwnSnapshotOnly`,
`scheduleDropsExpiredSnapshot`, `driftIsRestoredWithSavedValue`, `identityMatchesReference`.
`AvasContractTest`: `callSitesAreStillTwo` (AVAS дёргается только из `AppPerformance.kt`),
`modeIsWrittenOnlyFromSavedValue`, `soundTypeUsesProvenIdOnly`, `voidPropertyApiIsNotTreatedAsResult`,
`snapshotIsSingleAtomicFile`.

---

## 5. Замечание 5 — скрипт доказательства AVAS

**Переписан** `scripts/derive_avas_sound_prop.py` (481 строка изменений). Что он теперь делает:

1. определяет **формат по содержимому**, а не по расширению/вложенности: ZIP-обёртка → ищет внутри
   `CentralEXAuto_*.apk` → распаковывает; обычный APK → читает сразу (ловушка
   `assets/pip-overlay.apk` больше не срабатывает);
2. печатает md5/sha256 всех трёх уровней (zip, apk, `.so`) — сверяемо с ревьюером;
3. находит `.so` по `lib/arm64-v8a/libexpolitica.so` в самом APK, находит символ/ветку `nativeCall`
   и **собственным мини-дизассемблером AArch64** восстанавливает `int[248]`, который получает
   `Politica.h()`: ветка `cmd=5`, 428 инструкций, 14 копий `ldr q/stur q`, 2 int через `stur x`,
   последний — через `movz/movk + str w`;
4. печатает и проверяет `h(7)`, контроли `h(0x15)`/`h(0x16)`, dex-строки (`Politica`, `w0/u`, `w0/t2`,
   `d1`, `z0`) и, при `--smali-dir`, 10 текстовых smali-проверок (цепочка `h(7)` → `u.b` → `t2` →
   `g1.l` → `setIntProperty`, области `u.c={0,1}`, `getIntProperty` как единственный read-back);
5. заканчивается строкой `САМОПРОВЕРКА: OK`; на неподходящем файле честно падает с кодом 1.

**Прогоны (выполнены):**
- обёртка `/home/user/cex/cex.apk` → `САМОПРОВЕРКА: OK`;
- сам APK `/tmp/ref/CentralEXAuto_v3.3.9.apk --smali-dir /tmp/ref/sm` → все dex- и 10 smali-проверок
  `OK`, хвост `ИТОГ: h(7) = 0x21407057`;
- `assets/pip-overlay.apk` → корректный отказ (exit 1, «не APK и не обёртка»).

**Воспроизводимость без внешней распаковки:** smali-улики сохранены в репозиторий
(`docs/agent/ref/Politica.smali`, `docs/agent/ref/w0/{u,t2,g1,d1,z0}.smali`), поэтому команда

```sh
python3 scripts/derive_avas_sound_prop.py <CentralEXAuto_v3.3.9.apk> --smali-dir docs/agent/ref
```

работает в чистом клоне.

**Смысл индекса 7** (подробно — `docs/agent/avas_identity.md`): `Politica.c`/`la` →
`arr[i] = buf[i+1]` (smali `Politica:870–878`, `invoke-static … f(I[B)I` + `aput`); `w0/u.<clinit>` —
`const/4 v0, 0x7` (`:80`) → `sput-object v2, Lw0/u;->c:[I`, `u.b = arr[7]`; `w0/t2.run` —
`sget v10, Lw0/u;->b:I` (`:82`) → `g1.l(cpm, u.b, area, value)` + лог `AVAS sound type write area=` +
преф `avas_sound_type_saved`; `g1.l` → `d1.setIntProperty(III)V` (`d1:49`); `g1.c` →
`z0.getIntProperty(II)I` (`z0:95`). Суммы: `.so` md5 `316222cfc3e7080b81c4b426b7451a61` (6656 Б),
APK md5 `c3f1664125953b9851bf4483b265135f`.

**Чего это не доказывает:** что `setIntProperty` на нашем ГУ примет именно это значение — успех
пишется только по read-back `getIntProperty`, и до полевого прогона «id работает» не заявляется.

---

## 6. Дополнительные риски (🟡)

**6.1 Удаление `ip rule` и неоднозначность.** Санация удаляет только по полному `spec`
(`ip rule del $spec`), список перечитывается перед каждым удалением, при `dup>1`, нескольких `oif`
или чужом интерфейсе — отказ с `reason=` (`JitNetCore.sanitizeScript`, `JitNetCore.kt:148`).
Формулировка «абсолютная защита чужих правил» **не заявляется** — в отчёте и в логе пишется
`sanitize_left=<n>`, т.е. наблюдаемый остаток. Проверка совместимости точных селекторов с `ip` на
целевом Android — по полю, вместе с логом `JIT_NET_SANITIZE` (там печатается `priority` и `spec`).

**6.2 «Восстановлено» ≠ «интернет есть».** `RestoreResult` (`JitNetCore.kt:203`) разделяет
`wifiConfirmed`/`apConfirmed` (`essentialsConfirmed`), `link` (состояние `wlan0`),
`sanitizeLine`/`sanitizeLeft`, и отдельно `fullyRestored` (требует `link=up` **и** `left=0`).
Маркер снимается по `canClearMarker` (essentials + `link != unknown`), а не по `fullyRestored`,
чтобы «правила слиты, но `inet` ещё не поднялся» не держало маркер вечно. Формулировка в логе —
`wifi_confirmed=… link=… sanitize_left=…`, слово «интернет восстановлен» не используется.
Доказано: `restoreResultSeparatesEssentialsFromInternet`.

**6.3 TTL 10 минут.** Обоснование — в коде (`AvasRestore.SNAPSHOT_TTL_MS`,
`app/src/main/java/com/example/voiceapp3/tools/AvasRestore.kt:41`) и в `JitNetModel.ttlPolicy`
(`JitNetCore.kt:70`) + тесты:
- AVAS: просрочка не откатывает значения молча (пишется `reason=expired`), снимок удаляется —
  лучше ничего не писать, чем писать вслепую (`AvasCoreTest.expiryIsOwnSnapshotOnly`,
  `scheduleDropsExpiredSnapshot`);
- сеть: просрочка **не выбрасывает** `wifi=1` — Wi-Fi включается (это возврат к состоянию ДО
  операции, выключенный Wi-Fi = гарантированная потеря связи), а точка доступа не поднимается
  (`TtlPolicy.WIFI_ONLY`); при `wifi=0` возвращать нечего → `DROP`
  (`JitNetCoreTest.expiredMarkerRestoresWifiButNotAp`, `expiredMarkerIsDroppedWhenNothingToRestore`);
- часы назад (`now < ts`, типично для ГУ до синхронизации времени) — **не** просрочка
  (`JitNetCoreTest.clockWentBackwardsIsNotExpiry`, `freshMarkerIsFullPolicy`).

**6.4 Состояние сети после ошибки.** Остаётся наблюдаемой величиной, а не предположением: каждый
возврат пишет `request=` и `state=`/`link=`, а неизвестное состояние (`readWifi() == null` или
канал не ответил) не трактуется ни как «включено», ни как «выключено» — оно ведёт к `ABORT` или
`Recovery.KEPT` с повтором. Полностью это закроется только полевым логом; в отчёте полевой
гарантии не заявляю.

---

## 7. Доказательства поведенческих тестов (локальный JVM-прогон)

Раньше единственным доказательством был CI (компиляция + запуск JUnit), а локальной сборки не
было вовсе. Теперь есть воспроизводимый прогон без Android SDK: `scripts/localjvm/`
(заглушки `android.*`, `org.junit.*`, `AppLog`, `AdbClient`, рефлексивный `Runner`), инструкция —
`scripts/localjvm/README.md`. Каталог вне `app/src/**`, в Gradle-сборку не входит.

Результат прогона на этом коде:

```
AvasCoreTest (12)        PASS 12 / FAIL 0
JitNetCoreTest (18)      PASS 18 / FAIL 0
AvasContractTest (5)     PASS 5  / FAIL 0
JitToggleChainTest (5)   PASS 5  / FAIL 0
NetSanitizePlanTest (7)  PASS 7  / FAIL 0
BuildMarkerTest (3)      PASS 3  / FAIL 0
ИТОГ: PASS=50 FAIL=0
```

Что этот прогон ловит на практике (реальные находки этой итерации, исправленные до коммита):
- `AvasCoreTest.unreadableDeviceWritesNothing` падал с `FAILED` вместо `UNVERIFIED` — при
  нечитаемом режиме код шёл писать вслепую; исправлено в `AvasCore.kt:147`;
- `JitToggleChainTest.netCallsStayInsideToggleChain` не учитывал новое ядро `JitNetCore.kt`;
- `NetSanitizePlanTest.scriptReReadsAndRefusesAmbiguity` проверял строку с неинтерполированным
  `${D}` вместо фактического `ip rule del $spec`;
- `AvasCoreTest` не компилировался (трейлинг-лямбда попадала в `log` вместо `now`).

**Границы:** это не запуск на ГУ. Android-часть (`Car API`, `WifiManager`, `ctl.restart`) подменена;
официальный прогон — CI (`./gradlew testDebugUnitTest`).

---

## 8. Версия и маркер сборки

- `app/build.gradle.kts`: `versionCode = 61`, `versionName = "1.0.61"` — согласовано с
  `BuildMarkerTest.versionIs61` (переименован из `versionIs60`, проверка та же).
- `app/buildinfo.properties`: `SOURCE_SHA` обновляется коммитом после кода (как в предыдущих
  итерациях, `git rev-parse` в сборке не используется); `BuildMarkerTest.commitSourceIsHonest`
  проверяет, что маркер указывает на существующий коммит этого репозитория.
- Владелец подтвердил: **1.0.61 не устанавливалась**, повышения версии для этого исправления нет.

---

## 9. Что изменено (файлы)

Новые:
- `app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt` — чистое ядро сети + `JitFlow`.
- `app/src/main/java/com/example/voiceapp3/tools/AvasCore.kt` — чистое ядро AVAS (снимок/окна/отмена).
- `app/src/test/java/com/example/voiceapp3/tools/JitNetCoreTest.kt`, `AvasCoreTest.kt`,
  `AvasContractTest.kt` — поведенческие тесты.
- `docs/agent/avas_identity.md`, `docs/agent/ref/**` — доказательство id AVAS и smali-улики.
- `scripts/localjvm/**` — локальный прогон тестов без SDK.

Изменены:
- `AppPerformance.kt` — новый порядок цепочки тумблера, `recoverNetOnStart`, `abortAfterNet`,
  `killWatcherAndVerify`, `writeIntentConfirmed`, `waitWatcherArmed`, ворота `JitFlow.gate`.
- `JitNet.kt` — адаптер над ядром (Prefs2/WifiManager/AdbClient/AppLog), без собственной логики.
- `AvasRestore.kt` — модель + рефлексия Car API + обоснование TTL; поведение — в `AvasCore`.
- `JitToggleChainTest.kt`, `NetSanitizePlanTest.kt`, `BuildMarkerTest.kt` — согласованы с новым
  расположением логики и версией 1.0.61.
- `scripts/derive_avas_sound_prop.py` — переписан (§5).
- `app/build.gradle.kts` — 61 / `1.0.61`.

Удалены: `app/src/test/java/com/example/voiceapp3/tools/NetRestoreTest.kt`,
`app/src/test/java/com/example/voiceapp3/tools/AvasRestoreTest.kt` (текстовые тесты).

---

## 10. Ограничения этого отчёта

- Полного полевого прогона JIT на ГУ не было; выводы — по коду, локальному JVM-прогону и CI.
- Локально не собирался APK (нет Android SDK в песочнице): компиляция проверена компилятором
  Kotlin 2.0.21 с заглушками для затронутых файлов; полноценная проверка — CI.
- Совместимость точных `ip rule del <spec>` с `ip` на целевой прошивке подтверждается только
  полем (лог `JIT_NET_SANITIZE … priority=… spec=…`).
- Идентичность AVAS-свойства доказана по коду и нативной таблице, но не его приём на ГУ: успех —
  только по read-back `getIntProperty`.
