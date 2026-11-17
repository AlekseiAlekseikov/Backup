# ITER63 — две обязательные правки владельца и один заход в машину

База: `faeaaae912742f51a69155c70d07df12c7b00c6e` (ветка `fix/v2`, CI iter62 `44157118616` — success).
`versionCode=63` / `1.0.63` не менялись, `main` не пушился, `--force` не применялся, JIT/TTL/звук
не переделывались, AP/AVAS не трогались. Новые `.md` — только этот файл; `WORKLOG.md` — одна
запись.

Обозначения: ✅ сделано и подтверждено исполнением · 🟡 сделано частично · ❌ не делалось ·
⛔ проверить нельзя в этой среде (нужно устройство/ГУ).

## 1. SHA

| что | SHA |
|---|---|
| база (iter62 handoff) | `faeaaae912742f51a69155c70d07df12c7b00c6e` |
| §3.5 README (необратимость) — до коммита кода | `31a2a31` |
| **CODE_SHA** (код + тесты, блоки P0/P1) | `5d61c63` |
| CODE_SHA (guard у каждой мутации) | `27ffaaf` |
| **SOURCE_SHA** (= последний кодовый коммит) | `27ffaaf70afa82394218a89facfc03d70f519b05` |

## 2. Прогоны

| прогон | результат |
|---|---|
| `bash /home/user/runtests63.sh` (production-код + все JVM-тесты, настоящий `/bin/sh`, `/proc`) | **`OK (1115 tests)`**, failures 0 |
| то же до последнего коммита | `OK (1112 tests)` |
| iter62 для сравнения | `OK (1072 tests)` |
| `bash /home/user/typecheck_lock.sh /tmp/w63` | **53 → 53, новых ошибок нет** |
| CI на handoff `9dea33c` | run **`44176650290`**, job `build` — **success**: `JDK 21`, `Gradle build (debug)`, `Upload APK` (все шаги success). Debug-CI ≠ unit ≠ release ≠ ГУ |
| новые тестовые методы iter63 | **43** (7 + 5 + 4 + 8 + 10 + 6 + 3) |

Локальная Gradle-сборка в этой среде **невозможна** (JDK 11, нет Android SDK и сети): единственная
настоящая сборка — CI (debug). Debug-CI ≠ unit-тесты ≠ release ≠ проверка на ГУ — ⛔.

## 3. P0-1 (B2) — журнал: обе граничные записи сохраняются, дубли по идентичности события ✅

`Файл:строка`: `tools/LogRecordId.kt` (новый), `tools/FileLogCore.kt:52` (`appendStamped`),
`:250`, `:303`, `:342`, `tools/AppFileLogger.kt:136`.

- Каждая запись несёт `rid` (монотонный в процессе) + `ts` + `try`; в конец строки пишется суффикс
  ` [r=<rid>/<ts>/<try>]`. Идентичность события — `rid`, **не текст**.
- Повтор после отказа `fsync` — тот же `rid`, `try+1` ⇒ это ОДНО событие, второй строки нет.
- Слияние `.new`: выбрасываются только записи, чей `rid` уже есть в активном файле. Отчёт:
  `new_merged=N boundary_same=<0|1> skipped_as_duplicate=<n>`.
- Журнал прежней версии (без `rid`): переносится ВЕСЬ (потерь нет), возможный дубль назван ценой
  отсутствия id — `legacyJournalLosesNothing` ожидает 4 строки.
- Переписаны с обоснованием (не «молча под новое ожидание»): `Iter53LogTest.boundaryMatchKeepsBothRecordsIter63`
  (`a,b,b,e`), `Iter54LogTest.boundaryMatchIsReportedAndNothingIsLost` (`first,carry,carry,next`,
  `new_merged=2`), `identicalTextWithoutIdIsKeptBoth`, `threeIdenticalRecordsSurviveRecovery` (3),
  `Iter62LogMarkerTest.pendingNewIsMergedWithoutDuplicatingTheSameEvent` (2 записи).

Тест `Iter63LogIdentityTest` — **`OK (5 tests)`**.

| проверка | на iter62 | после |
|---|---|---|
| L1: `same` (id=1) + `.new` `same` (id=2) — ДВА разных события | **FAIL** `expected:<[same, same]> but was:<[same]>` (потеря записи) | PASS — обе строки |
| дедуп повтора с тем же `rid` | — | PASS — одна строка |
| legacy-журнал без id | — | PASS — ни одной потери |
| L3/L4 (ротация, падение до `fsync`) | — | PASS |

## 4. P0-2 (B1) — «клик одного не меняет другие»: причина найдена, падающий тест есть ✅

**Причина (файл:строка):** `tools/ToolsStateCausality.kt:126–133` (до правки) сравнивал только
`clickId`, не сверяя КЛЮЧ подтверждения. Подтверждение ЛЮБОГО другого тумблера с большим номером
давало `APPLY_SNAPSHOT` ⇒ строка перерисовывалась по снимку — визуально «клик одного меняет
другие». Второй механизм: `recordAck` (`:153`) живёт в процессе backend, а `ui/ToolsPanel.kt:171`
читал `$acks` из снимка и не восстанавливал его ⇒ после пересоздания панели `ackFor == null` для
всех ключей и решение принималось без причинности.

**Правка:** `applyAcks(...)` (`ToolsStateCausality.kt:182`, вызывается из `ui/ToolsPanel.kt:171`) и
`sameKey = ack.key == pending.key` (`ToolsStateCausality.kt:131`).

Тест `Iter63ToggleCrosstalkTest` — **`OK (7 tests)`**, минимум два тумблера + зависимая строка.

| проверка | на iter62 | после |
|---|---|---|
| клик по первому → решение по второму остаётся `KEEP_CLICK` | **FAIL** `expected:<KEEP_CLICK> but was:<APPLY_SNAPSHOT>` | PASS |
| значение/подпись второго не изменились | FAIL (снимок применялся) | PASS |
| `applyAcks` после пересоздания панели | FAIL (нет метода/нет восстановления) | PASS |
| старые тумблер-тесты iter62 | — | PASS (61 шт.) |

**Ссылка на `tools/ToolsBackend.kt:173` из отчёта убрана.** Файл не входит в diff ни iter62, ни
iter63 (код там от iter48), исправлением он не является. Действительное исправление — UI перестал
использовать `default` при `unknown` (`ToolsPanel`/`ToolsStateCausality`), а backend не менялся.

## 5. P1-1 — JIT: уборка мёртвых `t.*` ✅

`tools/JitOwn.kt:629` (`_adm_cleanup_stale_tmps`), вызов — `:706` (перед захватом секции).

- Удаляются только файлы вида `t.<digits>.<digits>`; удаление — если pid из содержимого доказанно
  мёртв (`_adm_dead_proven`), иначе по pid из имени при отсутствии `/proc/<pid>` и `pstart`; без
  `/proc` не удаляется ничего.
- `t.*` никогда не владелец, поэтому уборка не может освободить живую секцию.
- Отчёт: `ADM_CLEANUP mode=stale_tmp seen=N`.

Тест `Iter63JitTmpCleanupTest` — **`OK (4 tests)`**; на iter62 падают 2 (200 файлов остаются,
пустой `t.*` остаётся). `Iter62JitOwnTest` — `OK (8 tests)` без изменений.

## 6. P1-2 — TTL: «файла нет / наш / чужой-нечитаемый» + guard у КАЖДОЙ мутации ✅

`tools/TtlScript.kt:134` (`_gen_owner_check`), `tools/TtlGenDiag.kt` (новый),
`tools/NetworkTtl.kt:547`, `:734–742`.

- `INIT` (файла не было) / `GEN_OK` (наш) / `ADOPT` (был чужой-нечитаемый — восстановлен своим с
  readback ДО первой мутации) / `UNWRITABLE` (записать не удалось — причина) / `SUPERSEDED`
  (поколение сменилось — `exit 90` до мутации).
- `TTL_APPLY_RESULT` теперь печатает `gen_file=«<путь>» gen_seen=«<маркер>»`; публикация —
  `TTL_GEN publish=yes/no gen_file_state=<absent|ours|foreign|foreign_unreadable> file=… readback=…`.
- **Честная граница:** чужое ЧИТАЕМОЕ значение по-прежнему даёт `SUPERSEDED` в самом скрипте —
  это ограждение (иначе `A→B→A` перестал бы отсекаться). Штатно оно не срабатывает, потому что
  `NetworkTtl` публикует своё поколение с readback ДО отправки скрипта; после обновления приложения
  файл перекрывается своим и скрипт видит `GEN_OK`. Тест это фиксирует обоими порядками.
- **Покрытие мутаций:** guard стоит у всех 27 мутаций, включая пробы `-m comment`
  (`VAPP_TTLPROBE`), ветви v6 (`-N`, `-X` при отсутствии маркера, `-A` правила и перехода), откаты
  v4/v6 и внутри `del_jump`/`drop_family` (`-D`, `-F`, `-X`). Тест `Iter63TtlGuardCoverageTest`
  разбирает ФАКТИЧЕСКИЙ текст production-скрипта: перед каждой строкой с `-A/-D/-F/-X/-N/-I/-R`
  в пределах трёх предыдущих строк есть `_gen_ok`.

Тесты: `Iter63TtlGenStateTest` — **`OK (8 tests)`**, `Iter63TtlGuardCoverageTest` — **`OK (3 tests)`**,
прежние `Iter62TtlGenTest` — без изменений.

| проверка | на iter62 | после |
|---|---|---|
| файла нет → `INIT` + работа | **FAIL** (нет маркера `VAPP_TTL_GEN_INIT`/пути) | PASS |
| файл нечитаемый (каталог) → `ADOPT`/`UNWRITABLE` с причиной | **FAIL** (вечный `rc 90` без причины) | PASS |
| чужое читаемое → `SUPERSEDED` с `file=` и `cur=` | **FAIL** (нет пути/значения) | PASS |
| наш файл → `GEN_OK` | PASS | PASS |
| боевой порядок: публикация до скрипта ⇒ чужое перекрыто, мутация идёт | — | PASS |
| guard внутри `del_jump`/`drop_family` | **FAIL** (2 из 3 тестов) | PASS |
| проба `-m comment` без guard (`:141–142`) | **FAIL** | PASS |

## 7. P1-3 — `LogRedact`: без ложных срабатываний ✅

`tools/LogRedact.kt:48`, `:58`, `:96` (`looksLikeIp6`).

- `12:34:56` (и `pid=12:34`) больше НЕ маскируется: адресом считается только сокращённая форма с
  `::` либо ровно 8 hex-групп по 1–4 символа.
- IPv6 маскируется при любом числе групп (`fe80::1`, `2a02:6b8::2:242`, `::1`,
  `2001:db8:0:0:0:0:2:1`) и вместе со скобками+портом (`[fe80::1]:8080`).
- IPv4 маскируется ВМЕСТЕ с портом (`192.168.1.5:8080`, `tcp://192.168.0.7:443/x`,
  `[192.168.0.7]:443`); MAC — ровно шесть групп по два hex-символа.

Тест `Iter63LogRedactTest` — **`OK (10 tests)`**; на iter62 падают **7 из 10**
(`APPLY at <ip6> done`, `from <ip4>:8080`, `peer=fe80::1` и др.). `LogRedact43Test` — `OK (6 tests)`.

## 8. P1-4 — `WindowDoors.setWindow*OnDoor`: статус по факту ✅

`tools/WindowDoors.kt:763`, `:796` (вызовы) и `:841` (`windowToggleStatus`).

- readback ≠ желаемому → `FAILED` + `reason=readback_mismatch` (доказанная неудача, а не «частично»);
- включено, но CarService не подключён → `BLOCKED` + `reason=car_service_unavailable` (настройка
  сохранена, примениться сейчас не может — причина названа);
- выключено и сохранено → `OK` (снимать нечего).

Тест `Iter63WindowDoorStatusTest` — **`OK (6 tests)`**; на iter62 падают **3** (безусловный `OK`).
⛔ Само опускание стекла на ГУ не проверялось (нет устройства).

## 9. P2 (B5) — необратимость после `uninstall` ✅ (текст) / ⛔ (проверка)

`README.md` (коммит `31a2a31`) — таблица «что остаётся после удаления приложения»:
правила `iptables` (цепь, переход в `POSTROUTING`) **никто не снимает при uninstall** — это не
«остаток», а необратимость до перезагрузки ГУ; `setprop dalvik.vm.usejit` — до перезагрузки;
`persist.sys.dalvik.vm.usejit` — переживает перезагрузку; `mount --bind` и каталог в
`/data/local/tmp` — до перезагрузки; `Settings.System` — бессрочно. Вывод: сначала OFF, потом
удаление. ⛔ Поведение этой прошивки при `uninstall` не проверялось.

## 10. Что НЕ делалось / не подтверждено

- ⛔ **Шаг 1 §5 (read-only на ГУ) не исполнен**: нет устройства и `adb`. Ни одна команда на машине
  не запускалась — ни шаг 1, ни шаги 2–6. `ctl.restart zygote`, `iptables -A/-D`, `setprop` с
  эффектом, `svc wifi`, монтирования не выполнялись.
- ⛔ Локальная Gradle-сборка (`assembleDebug`/`assembleRelease`/`testDebugUnitTest`) невозможна:
  JDK 11, нет Android SDK и сети.
- ⛔ Тумблеры, звук, VHAL, статус-бар, скриншот 2 — на экране ГУ не сверялись.

## 11. Порядок handoff (как в iter62)

1. код + тесты → `27ffaaf` (**SOURCE_SHA**);
2. `app/buildinfo.properties`: `SOURCE_SHA=27ffaaf70afa82394218a89facfc03d70f519b05`;
3. этот отчёт + строка в `WORKLOG.md`;
4. handoff-коммит;
5. проверка: `git show HEAD:app/buildinfo.properties` и
   `git diff --name-only 27ffaaf..HEAD` — только `docs/agent/REPORT_iter63.md`, `WORKLOG.md`,
   `app/buildinfo.properties`;
6. push `fix/v2` (без `--force`) — выполнен: `faeaaae..9dea33c`;
7. CI на handoff `9dea33c`: run **`44176650290`**, job `build` — **success** (`JDK 21`,
   `Gradle build (debug)`, `Upload APK`).
