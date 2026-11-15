# iter57 — отчёт: рабочий JIT с возвратом интернета, TTL без присвоения чужого

Ветка `fix/v2`. Версия `versionCode=63` / `versionName="1.0.63"`
(`app/build.gradle.kts:78–79`).

| Что | Значение |
|---|---|
| HEAD (этот отчёт) | см. `git log --oneline -1` — handoff-коммит iter57 |
| Последний **кодовый** коммит | `cf6373a` (§6: типизированный результат проверки TTL) |
| `SOURCE_SHA` | `cf6373a` (исправлен: был `4f3ea25` — коммит iter55) |
| Базовый прогон на старте итерации | `def5aef` → `OK (857 tests)` |
| Итоговый прогон | `OK (890 tests)` — `bash /home/user/runtests.sh` |
| Стенд | **JVM + fake shell** (+ настоящий `/bin/sh` и подставные `iptables/ip6tables` для TTL). ГУ, Android-стенда, Robolectric и instrumentation в песочнице нет |

Статус рабочего дерева на момент отчёта: чистое, всё закоммичено.

---

## 1. Сводка

| № | Блок | Статус | Чем подтверждено |
|---|---|---|---|
| 5 | Звук: журнал в 3 состояния с evidence, поздний shell | ✅ | `Iter57SoundTest` (12 тестов), `Iter55SoundTest` |
| 6 | Delivery: типизированные исходы, `Pair<rc,out>` не выносится наружу | ✅ | `Iter57SoundTest`, `Iter53DeliveryTest`, `Iter54DeliveryTest`; `NetworkTtl.VerifyResult` |
| 7.1 | JIT: старое намерение не побеждает новое | ✅ | `Iter56JitTest`, `Iter55JitTest` |
| 7.2 | JIT: **атомарная** выдача `seq` между процессами | ✅ (JVM: межпроцессный `fcntl` проверен внешним процессом) | `Iter57SeqTest` (9) |
| 7.3 | JIT: takeover без окна потери и без стирания чужих свидетельств | ✅ | `Iter55JitTest`, `Iter56JitTest` |
| 7.4 | JIT: сквозной ручной ON/OFF и возврат Wi‑Fi | ✅ JVM/fake shell; 🟡 реальное ГУ | `Iter57JitE2eTest` (5) |
| 8 | TTL: чужое не присваивается, своё ON→OFF обратимо | ✅ fake shell + настоящая таблица правил | `Iter57TtlTest` (7), `Iter55TtlTest` |
| 9 | Логи: честное место файла, отказ `rename`/`sync` | ✅ | `Iter57LogTest` (4), `Iter54LogTest`, `LogFilesTest` |
| 10 | Окна: одна точка отката, ABA, все writers | ✅ JVM (production-код `WindowDoors`); 🟡 VHAL | `Iter57WindowTest` (8), `Iter54WindowTest`, `Iter51VentRollbackTest` |
| 10 | Тумблеры: intent/pending/applied, поздний ack, холодный старт | ✅ JVM | `Iter54ToggleTest`, `Iter50TogglePositionTest`, `Iter57WindowTest` (зависимая строка) |
| 10 | UI/WebView/плагин, настоящие listeners | 🟡 | Android-стенда нет — честно не проверено |
| — | `assembleRelease` | ❌ в песочнице | Gradle требует JVM 17, доступна только 11 (`./gradlew --offline assembleRelease` → `Gradle requires JVM 17 or later`) |
| — | CI | 🟡 | последний успешный прогон был на `7a6fd6a` (iter56); коммиты iter57 в CI ещё не проверялись |

---

## 2. Звук (§5) — `Iter57SoundTest`, файлы `ScriptDelivery.kt`, `LockOp.kt`, `LockSound.kt`, `AdbClient.kt`

Сделано в `f06e00a`:

* журнал операции пишет `PLANNED` (с `ev=` путём свидетельства) **до** отправки;
* исход — `NOT_SENT | UNKNOWN(ev) | FINISHED(rc)`; терминальность доказывается маркером
  `VAPP_DIRECT_TERMINAL_<nonce> written=1` **и** файлом `<path>.done` с `rc=N`;
* `reconStep`: `no_targets` → restore не отправлен → `restore_outcome_unknown` (поздний shell,
  `rev=`, `deferReread`) → `evidence_absent_or_unreadable` → операция **не закрывается**;
* `STOCK_RESTORED`→`DONE`; `FOREIGN_LEFT`/`UNPROVEN` остаются конфликтами; `Ready` — только без
  conflict/unresolved.

Границы: воспроизводится только «поздний shell» модели стенда; реального `mount`/`umount` на ГУ
тесты не делают и делать не могут.

---

## 3. Delivery (§6)

* `ScriptDelivery.execDetailed` возвращает `Outcome` (`NotSent` / `Unknown(ev)` / `Finished(rc, out)`),
  а не `Pair<rc,out>`; реальные вызовы (`LockSound`, `NetworkTtl`) переведены на него.
* `NetworkTtl.parseVerifyResult` возвращал `Pair<ToolsStep.Status, String>` — заменено на
  типизированный `NetworkTtl.VerifyResult(status, detail)` (`NetworkTtl.kt:197`), чтобы вердикт и
  пояснение нельзя было прочитать наоборот.
* `ttlRuleCounts(): Pair<Int,Int>` оставлен осознанно: это read-only подсчёт (§6 его допускает).

---

## 4. JIT (§7)

### 7.1 Порядок намерений — `JitOwn.kt` (`def5aef`)
`seq` сравнивается только как число намерения; `newer_intent` — лишь при `me_seq > seen_seq`,
иначе `older_intent`/`seq_tie`; `END` более нового намерения порядок не меняет; без `seq` —
`rule=ts_legacy`; нечего сравнивать — `unprovable`/`older_intent`.

### 7.2 Атомарная выдача `seq` — `JitNet.kt:183` (`958fdea`)
Было (аудит): `read → +1 → write` в двух `SharedPreferences` без исключительности; два процесса
получали один номер, а обратное чтение этого не обнаруживало.

Стало:
* выдача под **межпроцессной** `fcntl`-блокировкой (`FileChannel.lock`) отдельного файла-замка
  (`JitNet.kt:28` — `SEQ_MONITOR` сериализует потоки одной JVM, файловый замок — процессы);
* счётчик живёт в файле `.../jit/jit_intent_seq.state` (`epoch=<токен>` + `seq=<номер>`), пишется
  через временный файл + `rename` + `fsync`; `SharedPreferences` — вторая копия;
* `System.currentTimeMillis()` в номере **не участвует**; `epoch` — случайный токен, только для лога,
  поэтому «время перевели назад» порядок не ломает;
* потеря файла → продолжаем от префов (`epoch=from_prefs`); потеря префов → от файла; потеря обоих →
  `epoch=new` и нумерация с 1 — это **названо**, а не выдано за продолжение;
* переполнение `Long.MAX_VALUE` → номер **не выдаётся** (`reason=overflow`), а не переходит в
  отрицательные; `null` ⇒ вызывающий обязан назвать порядок `ts_legacy` — JIT при этом не
  блокируется навсегда;
* лог: `JIT_INTENT_SEQ … issue=epoch=kept|epoch=new|reason=… src=file_lock` (`JitNetCore.kt:1872`).

### 7.3 Захват lock без окна потери — `JitOwn.kt` (`def5aef`)
Атомарное создание (`temp`+`ln`) с содержимым; смена объекта только под `<lock>.admin`;
`_jit_lock_acquire` → 0/1/2; перехват — только при точном совпадении с **доказанно** мёртвой
строкой (`_jit_owner_dead` rc 1: нет `/proc/PID`, другая загрузка, переиспользован `pstart`,
либо `END`/`REFUSED`); payload пишет `ppid`/`ppstart`; `trap` больше не удаляет `<lock>.dead.*`
(чужие свидетельства остаются).

### 7.4 Сквозной ручной ON/OFF и возврат Wi‑Fi — `Iter57JitE2eTest` (`abf9f81`)
Доказано на настоящей цепочке `JitOp.click` / `JitOp.onProcessStart` + fake-shell:
1. ручной ON: payload уходит **один раз**, `setprop dalvik.vm.usejit true`, `ctl.restart zygote` —
   последняя команда скрипта; шаг `grep ' /data/app/' /proc/mounts` — внутри скрипта, отдельной
   командой не отправляется;
2. ручной OFF идёт тем же путём со своим значением свойства;
3. после перезапуска фреймворка Wi‑Fi **включается обратно** (`setWifiEnabled(true)` зафиксирован
   подставным `JitNetState`) — это продолжение того же клика;
4. вне ручного клика (обычный старт процесса) — **ни одной команды**, `scheduled=false`,
   `snapshot=none`, принудительного `ctl.restart` нет;
5. ни в payload, ни в возврате Wi‑Fi нет `startSoftAp`/`hostapd`/AVAS/`CarplayMonitor`/
   `connadaptor`/`adb.tcp`/`5555`/`setenforce`/`pm install`/`reboot`.

---

## 5. TTL (§8) — `TtlScript.kt` (`4454d04`)

Было: при `COMMENT_OK=0` владение выводилось из имени цепи, совпавшего с ожидаемым; аудит показал,
что чужая цепь с тем же именем и телом присваивалась, а OFF её снимал.

Стало:
* **device-side маркер создания**: `OWNMARK=${TTL_STATE_DIR}/voiceapp3_ttl_own_<gen>` (`:128`),
  содержимое `chain=<наша цепь>/gen=…/created=…`; `ownmark_ok()` (`:129`) требует
  `^chain=$OUR$`;
* `NAMED_OWN=1` — только при `COMMENT_OK=0` **и** не историческом имени цепи **и** наличии маркера;
  `REGISTRY_UNKNOWN` из этого условия убран как нерелевантный;
* маркер пишется при успешном `-N` (`:549`), снимается при подтверждённом снятии цепи
  (`:447`, `TTL_OWNMARK clear=ok`) и не переносится на другое поколение;
* глобальный стоп `TTL_TRY action=stop reason=no_safe_identity` заменён на
  `TTL_IDENTITY action=create_only` (`:496`): **создавать свою** цепь разрешено, дописывать в
  существующую немаркированную — нет;
* единственный безусловный стоп по имени — историческое имя цепи (`reason=legacy_name_not_owned`,
  `:491`): это имя использует прежняя версия приложения, владение по нему не доказывается.

Тесты `Iter57TtlTest`: цепь, существовавшая до нас с тем же именем и телом, не меняется ON и не
снимается OFF; своя цепь ON→OFF обратима и маркер исчезает вместе с ней; маркер другого поколения
не даёт владения; повторный ON не дублирует правило; чужая ссылка сохраняет цепь; чужой переход
не трогается.

---

## 6. Логи (§9) — `FileLogCore.kt`, `LogFiles.kt` (`01e847a`)

* заметка о сохранённом свидетельстве теперь называет **полный путь и исходный каталог**:
  `new_merge_unsynced_kept_as=<имя> at=<абс. путь> origin=<каталог> moved=<0|1>`
  (`FileLogCore.kt:266`); ветка отказа записи тоже (`:251`);
* отказ `rename` не выдаётся за перенос: `moved=false`, файл остаётся по исходному пути
  (тест `renameFailureIsNotReportedAsSuccessAndKeepsTheOriginalPlace`); для воспроизводимости
  имя свидетельства берётся из `clock()`, проба подтверждения — из `durableProbe` (`:278`, `:285`)
  (оба — `internal`, как уже было с `procRoot`);
* выбор каталога логов называет источник: `LogFiles.lastDirChoice` = `external path=…` либо
  `internal_fallback path=… reason=external_unavailable|external_not_writable`
  (`LogFiles.kt:186`, `:200`). Фактический выбор проверяется тестом через провайдеры, потому что
  `Context` в JVM — стаб.

---

## 7. Окна и тумблеры (§10) — `WindowDoors.kt`, `VentOwnerTx.kt` (`40f292b`)

Найдено и исправлено:
1. **mutable-set escape**: `VentOwnerTx.read {}` отдавал живые `HashSet`-ы, а `ownersOf(w)` — живой
   набор напрямую (не использовался). Мутация «утащенной» коллекции не двигала поколение и была
   невидима для ABA-проверок. Теперь `read` отдаёт снимок (`VentOwnerTx.kt:64`), `ownersOf` удалён.
2. откат после неподтверждённого закрытия вынесен в **единственную** точку
   `WindowDoors.rollbackAfterFailedClose` (`:120`) — тот же production-код вызывается тестом.
3. `WindowDoors` нельзя было загрузить в JVM: статический инициализатор падал на
   `SystemClock.elapsedRealtime()` (`UnsatisfiedLinkError`). Часы вынесены в `awakeClock` (`:239`)
   с честным `runCatching`; на устройстве поведение прежнее.

Тесты `Iter57WindowTest`: событие другой двери между планом и откатом не стирается; ABA (равное
содержимое, другое поколение) не откатывается; барьер из 8 параллельных планов не теряет ни одного
владения; `write_rejected`/`readback_missing`/`late_readback` не выдаются за «закрыто»; поздний
readback старой попытки не снимает живую запись; скрытие зависимой строки («наружная температура»
при выключенном «проценте заряда») не является записью.

Тумблеры (intent/pending/applied, поздний ack, холодный старт, недоступные prefs, частичный
снимок) — существующие `Iter54ToggleTest`, `Iter50TogglePositionTest`.

**🟡 Чего нет:** UI/WebView, настоящие listeners, физическое окно и VHAL, `CarPropertyManager`
(в JVM `android.car` — стаб).

---

## 8. Матрица §11 — 14 строк

Команда для всех строк одна: `bash /home/user/runtests.sh` (полный прогон, `OK (890 tests)`),
точечно — `java -cp "$OUT:$CP" org.junit.runner.JUnitCore <Класс>`.

| № | Проверка | Тест | Production-файлы, реально компилируемые | Стенд | Результат |
|---|---|---|---|---|---|
| 1 | Звук: prepare + журнал с evidence до отправки, crash/restart/позднее выполнение | `Iter57SoundTest.prepareJournalExistsBeforeSend…`, `Iter54DeliveryTest` | `ScriptDelivery.kt`, `LockOp.kt`, `LockSound.kt`* | JVM + fake shell | ✅ |
| 2 | Звук: no-target/corrupt/legacy, два reconcile, ни один не Ready | `Iter57SoundTest` (no_target, corrupt, legacy) | `ScriptDelivery.kt`, `LockOp.kt` | JVM + fake shell | ✅ |
| 3 | Звук: старый bind Unknown, сейчас stock — не DONE | `Iter57SoundTest.lateShellOfAnOlderAttempt…` | `ScriptDelivery.kt` | JVM + fake shell | ✅ |
| 4 | Звук: поздний restore после нового bind невозможен | `Iter54SoundTest`, `Iter57SoundTest` | `LockOp.kt`, `LockSound.kt`* | JVM + fake shell | ✅/🟡 |
| 5 | Delivery: direct/upload, потеря ответа, marker/identity, cleanup | `Iter53DeliveryTest`, `Iter54DeliveryTest`, `Iter55DeliveryTest` | `ScriptDelivery.kt` | JVM + fake shell | ✅ |
| 6 | JIT: A seq=1 задержан, B seq=2 завершён, A не заменяет B | `Iter56JitTest.lastIntentWins…` | `JitOwn.kt`, `JitNetCore.kt` | JVM + fake shell | ✅ |
| 7 | JIT: два процесса, одновременная выдача seq, reboot/clock назад/tie | `Iter57SeqTest` (9 тестов, внешний процесс держит замок) | `JitNet.kt`, `JitNetCore.kt` | JVM + **реальный второй процесс ОС** | ✅ |
| 8 | JIT: живой lock подменяет старый между pin/mv; `.dead.*` не стирается | `Iter55JitTest`, `Iter56JitTest` | `JitOwn.kt` | JVM + fake shell | ✅ |
| 9 | JIT: duplicate same inst не объявляет владельца мёртвым; ручной ON/OFF; boot без zygote; возврат Wi‑Fi без AP/AVAS | `Iter56JitTest.claimShellDeathAlone…`, `Iter57JitE2eTest` (5) | `JitOwn.kt`, `JitNetCore.kt`, `JitNet.kt` | JVM + fake shell | ✅ JVM / 🟡 ГУ |
| 10 | TTL: comment unsupported, чужая идентичная цепь не меняется; свой ON→OFF обратим | `Iter57TtlTest` (7), `Iter55TtlTest` (6) | `TtlScript.kt`, `TtlRunner.kt`, `TtlVerify.kt` | JVM + настоящий `/bin/sh` + fake `iptables` с реальной таблицей | ✅ |
| 11 | TTL: успешный `-A` при reuse, rollback с ошибкой каждой команды, чужие ссылки, delayed shell, no-uplink retries | `Iter57TtlTest`, `Iter52TtlTest`, `Iter53TtlTest`, `Iter51TtlFamilyTest` | `TtlScript.kt` | то же | ✅ |
| 12 | Логи: rename false/exception, sync failure, две одинаковые boundary-записи, crash, межпроцессно | `Iter57LogTest` (4), `Iter54LogTest`, `LogFilesTest` | `FileLogCore.kt`, `LogFiles.kt` | JVM (обычная ФС) | ✅ |
| 13 | Окна/UI: все writers, событие между планом и действием, ABA; тумблеры, listener/render | `Iter57WindowTest` (8), `Iter54WindowTest`, `Iter54ToggleTest` | `WindowDoors.kt`, `VentOwnerTx.kt`, `Toggles45.kt`, `ToggleIntent.kt` | JVM (production-код до VHAL) | ✅ до VHAL / 🟡 UI |
| 14 | Версия 63, marker кодового SHA, diff до HEAD, unit отдельно от release, CI | см. §9 ниже | `app/build.gradle.kts`, `app/buildinfo.properties` | git/CI | 🟡 (release локально не собран) |

\* `LockSound.kt` в JVM напрямую **не компилируется** (Android-зависимости); его путь проверяется
через `ScriptDelivery`/`LockOp` и `Iter54SoundTest`.

---

## 9. Версия, маркер SHA, релиз, CI

* `versionCode=63`, `versionName="1.0.63"` — `app/build.gradle.kts:78–79`.
* `SOURCE_SHA` исправлен на последний **кодовый** коммит iter57 (`cf6373a`); ранее стоял `4f3ea25`
  (iter55) — это и был дефект, замечание владельца учтено. Содержимое файла на HEAD и diff
  `SOURCE_SHA..HEAD` приведены в сообщении о передаче (в отчёте не дублируются, чтобы не разойтись
  с фактическим git).
* `assembleRelease` в песочнице **не запускался успешно**: `./gradlew --offline assembleRelease` →
  `Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 11`.
  Единственная доступная JVM — 11. Значит release-сборка здесь не проверена; это не «успех».
* Unit-тесты отдельно от release: `testDebugUnitTest` в CI-конфигурации не запускается, локальный
  прогон — `bash /home/user/runtests.sh` (890 тестов).
* CI: последний зелёный прогон — `43733933268` на `7a6fd6a` (iter56, только `Gradle build (debug)` +
  `Upload APK`). Коммиты iter57 в CI не проверялись.

---

## 10. Что НЕ доказано (границы честно)

1. **Нет испытания на ГУ.** Все результаты — JVM и fake shell. Реальные `zygote`, `svc wifi`,
   VHAL, `/system/bin/sh`, `iptables` прошивки не проверялись. На ГУ без разрешения владельца ничего
   не выполнялось.
2. **UI/WebView/плагин/настоящие listeners — 🟡**: Android-стенда нет, `android.car` в JVM — стаб.
3. **Release-сборка не собрана** (JVM 17 недоступна), CI для iter57 не гонялся.
4. **Межпроцессная часть §7.2** проверена внешним процессом ОС на обычной ФС JVM; поведение
   `fcntl` на `f2fs`/внутри Android не проверялось, и гонка внутри `SharedPreferences` тестами не
   закрывается (счётчик от неё не зависит — он в файле под замком).
5. **TTL** не проверялся на реальном ядре: поддержка `-m comment`, модулей `TTL`/`HL`, фактическое
   изменение TTL в пакетах — вне стенда.
6. Harness (`runtests.sh`) компилирует 30 production-файлов (`JitNetCore`, `JitNet`, `JitOwn`,
   `WindowDoors`, `VentOwnerTx`, `TtlScript`, `TtlRunner`, `TtlVerify`, `FileLogCore`, `LogFiles`,
   `ScriptDelivery`, `LockOp`, …) и не компилирует `ToolsBackend.kt`, `ToolsPanel.kt`,
   `UiBridge.kt`, `AdbClient.kt`, `LockSound.kt`, `NetworkTtl.kt`. `NetworkTtl.kt` (правка §6)
   отдельно собран в изолированном наборе (`NetworkTtl.kt` + `Prefs2.kt` + `ToolsResult.kt` +
   `TtlVerify.kt` + `TtlScript.kt`) — 0 ошибок; в общий список он не включён, потому что зависит от
   `AdbClient`/`android.net`, которых в стенде нет. Остальные перечисленные файлы этим прогоном НЕ
   проверены.
7. Владелец сообщал: на 1.0.62 интернет и CarPlay работали, TTL «вроде» работал, AVAS вернулся
   после стоянки. Это **не** доказательство причины и не проверка 1.0.63; AVAS в JIT не переносился.

---

## 11. План владельцу (когда решит испытывать)

1. Собрать в Android Studio (`assembleRelease`, версия 63) и установить обычным способом (без
   `pm install` из скриптов, без `setenforce 0`, без туннелей).
2. Проверить тумблеры: быстрые клики, холодный старт, «процент заряда» ↔ «наружная температура»
   (скрытие строки не должно стирать выбор).
3. TTL без раздачи и с раздачей; отдельно — одновременно с независимым CarPlay; смотреть
   `TTL_IDENTITY`, `TTL_OWN`, `TTL_OWNMARK`, `TTL_CHECK`.
4. Звук: ON/OFF и после стоянки; смотреть `PLANNED`/`UNKNOWN(ev)`/`FINISHED(rc)`.
5. Только потом — **ручной** клик JIT и реальная загрузка страницы/DNS. Смотреть `JIT_INTENT_SEQ`,
   `JIT_CLAIM`, `JIT_CHAIN_SENT`, `JIT_OP_PHASE`, `JIT_CHAIN_RETURN`, `JIT_RESTORE`.
6. Логи: `getExternalFilesDir(null)/logs/`; если там пусто — смотреть `filesDir/logs/` и строку
   `internal_fallback … reason=…` (место теперь называется явно).

Перезагрузка ГУ как лечение не предлагается; повторный JIT-клик как «ремонт интернета» — тоже.
