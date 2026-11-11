# iter38 — исправления JIT и автоматическая диагностика интернета (1.0.61)

**Ветка:** `fix/v2` (без force). **Кодовый коммит:** `64ad8510392ecddef402a718265fb308f75d2da3` (`64ad851`).
**Версия:** `versionCode=61`, `versionName=1.0.61` — не менялись (1.0.61 владелец ещё не устанавливал).
**CI на кодовом коммите:** run `43076766217`, job `130718505139` (`build`) — **SUCCESS**, **297 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 48s`.
**Локальный JVM-прогон** (`/home/user/runtests.sh`: ядро + адаптеры + стабы): `OK (182 tests)`.

> APK не собирался и не подписывался здесь — это делает владелец. На ГУ ничего не запускалось: **ни один тест не выполнялся на устройстве**, полевых данных нет, «точно работает» не заявляю.

---

## 0. Что именно решено и чем это отличается от iter37

| Пункт мандата | Статус | Суть |
|---|---|---|
| §1.1 неопределённый исход | ✅ | второй клик при `chain_undefined` отклоняется (`blocked=chain_undefined`), снимок не перезаписывается; доказательств «выполнено/не выполнено» в процессе нет — блокер честно живёт до конца процесса |
| §1.2 старая задача vs новая операция | ✅ | пока возврат Wi-Fi запланирован, новый клик отклоняется (`restore_pending`); задача удаляет снимок только своей операции (по отметке) |
| §1.3 неудачная запись меняла прежние настройки | ✅ | перед записью снимаются прежние значения 4 ключей по каждому хранилищу; при блокировке они восстанавливаются; отказ восстановления виден в логе |
| §1.4 смерть процесса до возврата сети | 🟡→✅ (кроме гарантии запуска) | снимок больше НЕ удаляется на старте: удаление только после фактического возврата; смерть процесса в 15 с и отказ планировщика не теряют продолжение |
| §1.5 не регрессировать | ✅ | все прежние инварианты сохранены и покрыты тестами (см. ниже) |
| §2 диагностика монтирований | ✅ (сбор) / ⛔ (обратимость) | маркеры кода возврата и конца вывода, `wc -lc` (строки+байты), `channel_not_ready`/`root_unknown`, uid≠0, деление длинных строк без потерь |
| §3 диагностика интернета | ✅ (сбор) / ⛔ (причина не установлена) | 7 разделов, только чтение + два ограниченных `ping`, отдельные метки и статусы, нейтральная сводка |
| §4 первый запуск после обновления | ✅ | снимок без нашей отметки = `legacy_or_unknown`: не выполняется, не удаляется, данные остаются владельцу |

---

## 1. Чек-лист по пунктам

### 1.1 Неопределённый исход: второй клик больше не проходит — ✅

`JitNetCore.kt:597` (`click`), проверка `:614–631`:
- если предыдущая команда осталась без ответа транспорта (`CHAIN_UNDEFINED`), клик возвращает `blocked = "chain_undefined"` **до** чтения Wi-Fi, **до** записи снимка и **до** любой команды (`action=none commands=0`); оба направления тумблера ведут себя одинаково;
- текст в логе прямо говорит, что таймаут, `getprop`, boot id и новый процесс доказательством не являются, и что блокер живёт до конца процесса.

Какое свидетельство разрешало бы дальнейшие действия — и почему его нет: единственное возможное свидетельство («команда выполнилась» / «не выполнилась») лежит в той же очереди транспорта, ответ которой потерян. Наблюдаемые факты (`isWifiEnabled`, `getprop`, наличие IP, `/proc/self/maps`) описывают **состояние**, а не факт исполнения команды, и оба сценария выглядят одинаково. Поэтому вместо догадки — блокер без авто-JIT и без постоянного сторожа: после смерти процесса (а `ctl.restart zygote` почти наверняка её вызовет) отметка состояния в памяти исчезает, и решение принимает уже **опознанный снимок** на старте (п. 1.4/§4).

Тесты (модель, барьеры `JitFake.BlockingShell`):
- `JitOrchestratorTest.undefinedOutcomeBlocksSecondClickInBothDirections` — первая команда удерживается, Wi-Fi уже изменён ею, ответ потерян; второй клик в обе стороны отклонён, записей в префы нет, снимок прежний;
- `JitOrchestratorTest.lateExecutionOfFirstCommandDoesNotOverwriteOriginalSnapshot` — позднее исполнение первой команды: снимок остался `wifi=1`.

Остаточный риск: пока процесс жив, JIT-тумблер отклоняет нажатия (это видно в панели текстом). Иного безопасного пути без нового состояния/сторожа нет.

### 1.2 Старая отложенная задача не вмешивается в новую операцию — ✅

- `JitNetCore.kt:599` (`click`): если возврат Wi-Fi запланирован и ещё не выполнен — `blocked = "restore_pending"` (без записи снимка и без команд). Так старая задача не может выполниться «посреди» новой цепочки, а снимок новой операции не перезаписывается;
- `JitNetCore.kt:882` (`runScheduledRestore`): перед возвратом проверяется, что клик не идёт (`click_in_flight` → возврат пропускается, снимок не трогается);
- `JitNetCore.kt:920` (`cleanupOwnSnapshot`): удаление снимка и отметки — только если отметка всё ещё принадлежит **этой** операции; иначе `cleanup=skipped reason=snapshot_belongs_to_other_op`;
- новый снимок не «застревает» из-за антидубля: при `restore_pending` он вообще не пишется (нечего терять), а после выполнения задачи клик снова разрешён.

Тесты: `clickDuringScheduledRestoreIsRejectedInBothDirections`, `oldRestoreCallbackDoesNotTouchSnapshotOfNewOperation` (одинаковый и другой снимок), `snapshotKeptUntilRestoreRunsThenRemoved`, `duplicateWhileTaskPendingKeepsSnapshot`.

### 1.3 Ошибка записи не меняет прежние пользовательские настройки незаметно — ✅

- `JitNet.kt:125` (`readSnapshotState`) — прежнее состояние **четырёх** ключей (`jit_pedido`, `perf_auto_otimizar`, `jit_restaurar`, `jit_restore_marker`) в каждом доступном хранилище, в порядке чтения;
- `JitNet.kt:195` (`putSnapshot`) — один `commit()` на хранилище и readback ВСЕХ ключей, включая отметку;
- при блокировке `JitNetCore.kt:700–712` вызывает `restorePrevious` (реализация `JitNet.kt:146`): прежнее значение возвращается, отсутствовавший ключ удаляется; отчёт `prev_restore=x/y` + `prev_restore_detail` + `left_pedido`/`left_restore` пишется в лог;
- `commit=false`, изменивший память, тоже возвращается (проверено на модели «память отдельно от диска»);
- ничего не заявляется об атомарности CPS↔DPS: каждый `commit()` независим, отказ виден по имени хранилища.

Тесты: `JitOrchestratorTest.blockedClickRollsBackPreviousValuesOfAllKeys`, `failedRollbackIsReportedAndClickStaysBlocked`; на реальном `AndroidStore` — `JitPersistenceTest.snapshotStateCapturesPreviousValuesAndAbsence`, `rollbackRestoresAbsentKeysByRemovalAndPresentOnesByValue` (имя историческое), `rollbackOnStoreThatRefusesCommitsIsReported`, `updateMarkChangesOnlyTheMarkKey`, `updateMarkFailureIsReported`, `markIsWrittenInTheSameEditorAsTheReferenceKeys`.

### 1.4 Смерть процесса до возврата сети — 🟡 (сделано возможное, гарантии запуска нет)

- `JitNetCore.kt:793` (`onProcessStart`): снимок **не удаляется**; сначала планирование, затем (в задаче) фактический возврат и только потом удаление снимка и отметки — `JitNetCore.kt:882/920`;
- отказ планировщика больше не теряет продолжение: снимок и отметка остаются (`JitNetCore.kt:865–877`), следующий запуск продолжит ту же операцию;
- повторный процесс продолжает ту же операцию (опознание по отметке), а не «угадывает» по строке `wifi=1`;
- удаление не подтвердилось — снимок остаётся, следующий старт повторит возврат (`setWifiEnabled` идемпотентен), это видно как `still_present=true`.

Тесты: `schedulerFailureIsReportedHonestlyAndSnapshotSurvives`, `processDeathBeforeCallbackIsRecoveredOnNextStart`, `removalRefusedKeepsSnapshotForNextStart`, `snapshotKeptUntilRestoreRunsThenRemoved`, `JitPersistenceTest.newOperationIsContinuedAfterRestartAndRemovedOnlyAfterRestore`.

⛔ Что остаётся нерешённым и почему это не чинится кодом: **гарантировать запуск приложения после `ctl.restart zygote` нельзя** (`START_STICKY` и boot-broadcast этого не обещают). Если приложение не запустится, возврат произойдёт при следующем обычном запуске — тогда же, когда владелец увидит панель. Ровно поэтому после клика нельзя обещать «сеть вернётся через 15 секунд».

### 1.5 Прежние инварианты сохранены — ✅

| Инвариант | Где | Тест |
|---|---|---|
| только клиентский Wi-Fi, без AP | `JitNetCore.kt:255` (`WifiSnapshot`), скрипт без AP | `noApWorkIsEverRequested`, `JitChainScriptTest.noForbiddenCommands` |
| неизвестный Wi-Fi блокирует команду | `JitNetCore.kt:647–658` | `unknownWifiIsBlockedAndNothingIsWritten` |
| `wifi=false` не включается | `JitNetCore.kt:1042` (`restoreWifi`) | `restoreSkipsWhenAlreadyOnOrWasOff` |
| legacy `wifi=…,ap=…` — только Wi-Fi | `JitNetCore.kt:220` (`parseSnapshot`) | `legacyFormatIsReadOnlyByWifiAndNeverTurnsWifiOnBlindly` |
| битый снимок не выполняется | там же | `brokenSnapshotIsNotExecuted` |
| `setWifiEnabled(true)==false` ≠ «принято»; `true`/IP ≠ интернет | `JitNetCore.kt:393` (`setWifiOutcome`), `internet=not_checked` | `restoreAcceptedIsNotProofOfInternet`, `restoreRefusedIsNotCalledAccepted`, `restoreUnknownReturnValueIsUnknownNotAccepted`, `restoreExceptionIsReportedAsException` |
| фильтр `/proc/self/maps` по правам `w0/sa`→`j5.d`, `null` без строк | `JitNetCore.kt:300–330` | `JitMapsTest`, `JitRefTest.verifyTableForConfirmedValues` |
| удаление именно актуального намерения | `JitNetCore.kt:911–920` (`verifyApplied`, поколение под локом) | `newSameRequestDuringReadIsNotErased`, `clickStartingDuringVerifyBlocksRemoval` |
| UI относительно самого события клика | `ToolsPanel.kt:999`, `AppPerformance.kt:913` | `JitUiStateTest.*` |

---

## 2. Диагностика монтирований (исправление iter37) — ✅ сбор / ⛔ обратимость

`tools/JitMountDiag.kt` (переписан):

1. **Транспорт и контекст** (`JitMountDiag.kt:65`, `:222`): те же `JitRef.JitShell`/`AdbClient`, что у JIT-цепочки; в каждом разделе фиксируются `transport=jit_shell`, `uid`, `ns_self`/`ns_init`, `same_ns_as_init`. Успешный `id` больше не означает root: `id_ok_but_not_root uid=…`; при неизвестном uid — статус `root_unknown`, а поле `root = null` (не `false`).
2. **Полнота** (`:150–200`): команда = `wc -lc <файл>` + `cat <файл>` + маркер кода возврата каждой части + `__JMD_EOF=1`. Статус:
   - нет маркера конца ⇒ `unknown` (полноту подтвердить нельзя);
   - `cat_rc`/`wc_rc` ≠ 0 при rc оболочки 0 ⇒ `unknown`/`root_unknown`, а не `ok`;
   - `Permission denied` ⇒ `permission_denied`;
   - число строк **или байт** расходится с `wc` ⇒ `truncated` (это ловит и потерю в середине, если она меняет длину);
   - `empty` — только при подтверждённо полном чтении;
   - `137/ERR_ADB_TIMEOUT` ⇒ `timeout`;
   - канал не готов ⇒ `channel_not_ready` (не «нет root») и НИ ОДНОЙ команды.
3. **Фильтрация** — то же условие референса `' /data/app/'` (`:88`), строка самого `wc` в «прочитанный файл» не попадает (`:100`, `catRegion`).
4. **Длинные строки** (`:170`, `chunks`): делятся на части с явной пометкой `⟪продолжение строки a..b⟫`, ничего не обрезается молча.
5. **Предел проверки** (описан в шапке файла и в отчёте): если транспорт потеряет кусок вывода **в середине** и при этом сохранит маркеры, а длина случайно совпадёт (замена/перестановка байт), это не обнаруживается — сравнение идёт по числу строк и длине, контрольной суммы нет.

Тесты (17): `JitMountDiagTest.goodReadProducesOkSectionsAndFilteredLines`, `identityWithNonZeroUidIsNotRoot`, `identityFailureIsRootUnknownNotOk`, `emptyOnlyAfterConfirmedCompleteRead`, `missingEndMarkerIsUnknownNotEmpty`, `catErrorWithShellRcZeroIsNotOk`, `wcErrorIsUnknownNotOk`, `permissionDeniedIsNotTreatedAsEmpty`, `timeoutIsReportedAsTimeout`, `shortOutputIsTruncatedByLinesAndBytes`, `middleLossIsDetectedByByteCount`, `longLineIsSplitWithoutSilentLoss`, `channelNotReadyProducesNoCommandsAndHonestStatus`, `filtersOnlyTheSameConditionAsReference`, `wcCountsParsesLinesAndBytes`, `adapterRunsOffMainThreadAndWithoutWaiting`, `commandsAreReadOnlyAndFour`.

**⛔ Обратимость `umount -l` не установлена.** Шаг в скрипте остаётся включённым (`JitNetCore.kt:79`, `mount_step=present`). Список монтирований сам по себе её не доказывает. **Пока данных с ГУ нет и обратимость не оценена — JIT нажимать не нужно**; от меня такой просьбы нет.

## 3. Автоматическая диагностика интернета — ✅ сбор / ⛔ причина не установлена

`tools/InternetDiag.kt` (новый, `collect` — `:199`), Android-часть — `tools/NetInfoAndroid.kt` (только чтение API).

Разделы (в одном датированном логе приложения, метки `INTERNET_DIAG_BEGIN/_SECTION/_DATA/_END`):
1. `wlan0_addr` — `ip addr show wlan0` (адрес ≠ интернет);
2. `ip_rules` — `ip rule show` (только чтение правил);
3. `routes` — `ip route show table all` (включая таблицы);
4. `dns_props` — значения `net.dns1..4`, `dhcp.wlan0.dns1/2`, `net.dns.search`;
5. `ping_ip` — `ping -c 3 -W 2 1.1.1.1`;
6. `ping_name` — `ping -c 3 -W 2 google.com` (проверка разрешения имени);
7. `android_connectivity` — `wifiEnabled`, транспорты активной сети, `VALIDATED`, captive portal, DNS активной сети (через `ConnectivityManager`, только чтение).

Статусы по каждому разделу раздельно: `ok`, `empty`, `permission_denied`, `channel_not_ready`, `unavailable`, `timeout`, `unknown` (+ `truncated` для длинных строк); фиксируется rc **конкретной** команды отдельно от rc оболочки. Для `ping` дополнительно `verdict = echo_received | no_reply | not_checked`; в логе прямо написано, что отсутствие ответа **не** доказывает отсутствие интернета и что контекст root-shell отличается от контекста приложения. Итог — нейтральная сводка фактов (`note="это факты, а не диагноз"`), без автоматического «виноват TTL/DNS/телефон».

Запуск: `JitNet.requestDiagOnce` (`JitNet.kt:395`) → вызов из `VoiceAssistantService.kt:274` при обычном старте сервиса (задержка 20 с), в фоне (`BgWorker.assertNotMain`), не чаще одной попытки за процесс, без кнопок, без связи с кликом JIT, без `zygote`. Если канал не готов: ставится **одноразовый** обработчик существующего события готовности (`AdbClient.onChannelReadyOnce`, `AdbClient.kt:2144`) + одна отложенная проверка (30 с) с честным `channel_not_ready`/`root_unknown`. Бесконечных повторов и нового сторожа нет. Обе диагностики независимы: свои метки, свои ошибки.

Ограничения честно: объём вывода не ограничивается искусственно, но проверка полноты — по маркерам и (для файлов) по `wc`; для `ip`/`ping` потеря середины при сохранённых маркерах не обнаруживается.

**Что владелец увидит в логе и когда:** после обычного запуска приложения на ГУ (не после клика JIT!) в течение ~20–40 с в файле `voiceapp3-ДД.ММ.ГГГГ.log` появятся блоки `JIT_MOUNT_DIAG_*` и `INTERNET_DIAG_*`. Файл лежит в `Android/data/com.example.voiceapp3/files/logs/` (fallback — внутренний `files/logs/`). Отправить можно этот файл (или только строки с этими метками).
**Предупреждение о чувствительных данных:** в лог попадут IP-адреса, маршруты, адреса DNS и пути приложений из `/data/app/`; SSID/BSSID/MAC и идентификаторы телефона не собираются, но адреса и имена приложений — данные владельца, в публичные отчёты их копировать не нужно.
**Диагностика — не ремонт:** она ничего не меняет и причину не устраняет; вывод о причине делается только по полученным данным.

Тесты (19): `InternetDiagTest.onlyReadOnlyCommandsPlusBoundedPings`, `allSectionsOkInNormalCase`, `missingWlan0IsEmptyReadErrorNotInternetVerdict`, `missingIpToolIsUnavailableNotEmpty`, `permissionDeniedIsSeparateStatus`, `missingEndMarkerIsUnknown`, `timeoutIsTimeoutNotNoInternet`, `pingFailureIsNotProofOfNoInternet`, `ipNoReplyButNameResolvesIsKeptAsFact`, `emptyDnsPropsAreNotDnsAbsence`, `channelNotReadyMeansNoCommandsAtAll`, `channelNotReadyHelperWritesHonestMarkers`, `androidApiUnavailableIsNotFalse`, `validatedIsNotASiteCheckAndNoSsidIsLogged`, `longLineIsSplitAndMarked`, `endSummaryIsFactsNotDiagnosis`, `chunksNeverExceedLimit`, `adapterPathIsBackgroundAndIndependentOfJit`, `logGoesToDatedAppLog`.

## 4. Безопасность первого запуска после обновления 1.0.60 → 1.0.61 — ✅ (отказ вместо догадки)

В 1.0.60 ключа-отметки не было. Поэтому (см. `JitNetCore.kt:73`, `:805–829`):
- снимок без нашей отметки ⇒ `reason = legacy_or_unknown`: **ничего не выполняется и ничего не удаляется**; в логе прямо сказано, что это осознанный отказ, а не «сеть не вернулась из-за JIT»;
- снимок с отметкой (записанный 1.0.61) ⇒ нормальное продолжение: снимок снимается только после фактического возврата;
- снимка нет ⇒ `no_snapshot`, никаких записей;
- совпадение boot id ничего не доказывает и нигде не используется для решения.

Тесты: `JitOrchestratorTest.legacyOrUnknownSnapshotIsNotExecutedAndKept`, `JitPersistenceTest.legacySnapshotFrom1060IsNotContinuedByTheRealStore` (реальный `AndroidStore` на модели диска), `upgradeWithNoSnapshotWritesNothing`, `newOperationIsContinuedAfterRestartAndRemovedOnlyAfterRestore`.

⛔ **Установка 1.0.61 на ГУ не проверена.** По коду старые данные не выполняются и не стираются, но заявлять безопасность установки без полевой проверки нельзя.

---

## 5. Что осталось нерешённым (сводно)

| # | Что | Статус | Причина |
|---|---|---|---|
| 1 | Обратимость общего `umount -l` на `/data/app/` | ⛔ | нужны данные `/proc/*/mountinfo` с ГУ; сбор готов, данных нет |
| 2 | Гарантия запуска приложения после `ctl.restart zygote` | ⛔ | `START_STICKY` и boot-broadcast этого не обещают; снимок теперь переживает смерть процесса, но время возврата не гарантировано |
| 3 | Причина «сайты не грузятся» на 1.0.60 | ⛔ | нет полевых данных; TTL/JIT/DNS/телефон не объявляются виновными |
| 4 | Обнаружение потери середины вывода для `ip`/`ping` | 🟡 | проверка по маркерам; для файлов дополнительно по `wc -lc`; контрольной суммы нет |
| 5 | Блокировка клика до конца процесса при `chain_undefined` | 🟡 | осознанно выбрано вместо догадки; после перезапуска решает опознанный снимок |
| 6 | Атомарность CPS↔DPS | 🟡 | не заявляется: два независимых `commit()`, отказ виден в логе |

**Критерий, при котором можно предлагать владельцу установку ради логов:** диагностика сети и монтирований работает при обычном запуске и **не требует** клика JIT; старый снимок 1.0.60 не выполняется и не удаляется (проверено тестами модели, не полем). Для установки достаточно обычного запуска приложения и отправки датированного лога — JIT нажимать не нужно.

---

## 6. Тесты: модель / интеграция / поле

- **JVM-модель (182 локально, 297 в CI вместе с остальными тестами проекта):** `JitOrchestratorTest` (45), `JitPersistenceTest` (24), `JitMountDiagTest` (17), `InternetDiagTest` (19), плюс прежние `JitRefTest`, `JitChainScriptTest`, `JitMapsTest`, `JitUiStateTest`, `JitLogsCancelTest`, `JitNoPersistTest`, `JitStartZeroWritesTest`, `JitBootBoundaryTest`, `JitRulesReadOnlyTest`, `LogFilesTest` и др.
- **Что это НЕ проверяет:** реальные `SharedPreferences`/`Prefs2`, `WifiManager`, `AdbClient`, `ConnectivityManager`, `ip`/`ping` на ГУ. Транспорт, сеть и хранилище в тестах подставные; «новый процесс» — модель в памяти. Фейки ≠ Android-интеграционные тесты ≠ поле.
- **Android-интеграция:** не запускалась (нужно устройство). Компиляция всех Android-файлов подтверждена CI (`:app:compileDebugKotlin` + `testDebugUnitTest` в job `130718505139`).
- **Поле:** нет. Пробных `umount`, `zygote`, перезагрузок и кликов JIT ради анализа не выполнялось; кнопки не нажимались.

## 7. Артефакты

- Код: `tools/JitNetCore.kt` (отметка операции, блокеры, согласование задачи, восстановление прежних значений), `tools/JitNet.kt` (хранилище v3 + `requestDiagOnce`), `tools/JitMountDiag.kt` (переписан), `tools/InternetDiag.kt` (**новый**), `tools/NetInfoAndroid.kt` (**новый**), `tools/AppPerformance.kt` (тексты новых блокировок, лог старта), `tools/AdbClient.kt:2133/2144`, `VoiceAssistantService.kt:274`.
- Тесты: `JitOrchestratorTest.kt`, `JitPersistenceTest.kt`, `JitMountDiagTest.kt`, `InternetDiagTest.kt` (**новый**), `JitFake.kt`, `JitLogsCancelTest.kt`, `JitRefTest.kt`, `JitUiStateTest.kt`, `JitStartZeroWritesTest.kt`.
- Отчёт: `/home/user/REPORT_iter38_jit_internet_diag.md` + копия `docs/agent/`; `WORKLOG.md`; `app/buildinfo.properties` → `SOURCE_SHA=64ad851`.
- Кодовый SHA: `64ad8510392ecddef402a718265fb308f75d2da3`; CI run `43076766217` / job `130718505139` — 297 PASSED / 0 FAILED.
