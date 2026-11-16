# REPORT iter61 — довести JIT/TTL/звук/доставку до проверяемого результата

Ветка `fix/v2`, `versionCode=63`/`versionName=1.0.63` не менялись, `--force` не применялся, `main` не пушился.
База итерации: `8a004ee6744106213cb7b03289ef27896d6b0893` (CI `43902339569`, success).
Прогон на стенде: `bash /home/user/runtests58.sh` → **`OK (1033 tests)`** (было `OK (992 tests)` на iter60).
Typecheck production: `bash /home/user/typecheck_lock.sh /tmp/w60` → **`ошибок в базе: 53 / сейчас: 53 / новых нет`**.
На ГУ **ничего не выполнялось**: ни одной команды на устройстве, `svc wifi`/`iptables`/`mount`/zygote не трогались.

Статусы: ✅ проверено исполнением · 🟡 проверено на стенде, но не на ГУ · ❌ дефект воспроизведён и не устранён · ⛔ не проверено (нет доступа/условий).

---

## §3 P0 — JIT: гонка «снимок glob → обработка»

**Статус: ✅ (на стенде Linux `/bin/sh` + реальные процессы) / ⛔ toybox-ГУ.**

Что было (аудит iter60): `JitOwn.kt` перебирал имена участников снимком
`for f in "$dir"/c.* "$dir"/p.*` — снимок раскрывался ДО тела цикла, поэтому исчезнувший
`c.*` и появившийся `p.*` давали двух живых владельцев (воспроизведено проверяющим:
`z entered while a holds: True`).

Что сделано — протокол заменён на **единственную атомарную операцию toybox**, без
`flock`/`renameat2`/CAS-unlink:

| Механизм | Файл:строка | Смысл |
|---|---|---|
| захват НОМЕРА `n.<k>` через noclobber (`set -C`) + readback содержимого | `JitOwn.kt:553` (`_adm_claim_slot`) | атомарность даёт создание файла, а не список имён |
| ожидание меньших номеров **поимённо**, без снимка каталога | `JitOwn.kt:595` (`_jit_admin_enter`, шаг 4) | поздняя публикация меньшего номера либо видна, либо её владелец доказанно мёртв |
| освобождение только при **доказанной смерти** + повторная сверка байтов перед `rm` | `JitOwn.kt:572` (`_adm_reclaim_dead`) | живой номер не снимается никогда |
| миграция legacy `.admin`: `mv` в уникальное имя, сверка содержимого, возврат при подмене | `JitOwn.kt:595` (шаг 0) | чужой живой владелец не «переезжает» |
| точки остановки для детерминизма (в бою no-op) | `JitOwn.kt:588`, `:589` | `_adm_barrier_after_slot`, `_adm_barrier_before_scan` |

Доказательство «до/после» исполнением:
* iter60-код (снимок glob) — `Iter61JitSlotRaceTest` падает: `a=ENTER a witness=taken`,
  `z=ENTER z witness=VIOLATION`; также падают `liveForeignSlotIsNeverRemoved`,
  `slotOfAKilledParticipantIsReclaimed`, `corruptForeignSlotBlocksWithoutBeingDeleted`,
  `nestedEntryKeepsASingleSlotAndDoesNotReleaseItEarly`.
* iter61-код — `Iter61JitSlotRaceTest` → **`OK (8 tests)`**.

Тесты протокола приведены к новому механизму **с сохранением всех инвариантов** (не ослаблены):
`Iter60JitTicketRaceTest` → `OK (9 tests)`, `Iter59JitAdminTest` → `OK (6 тестов в семействе)`.
В `Iter59JitAdminTest`/`Iter60JitTicketRaceTest` заменены только имена записей (`p.<inst>.<pid>`/`c.*` →
`n.<k>`), хук `_adm_barrier_after_ticket` → `_adm_barrier_after_slot` и добавлено требование
«занятый номер виден до входа»; сценарии (взаимное исключение на двух порядках, вложенность,
живой не снимается, мёртвый освобождается, битая запись, legacy) сохранены.
Это осознанная правка теста под изменившийся production-протокол, а не подгонка: прежние тесты
проверяли удалённый механизм и падали по «нет хука / нет файла».

Конечное восстановление без перезагрузки: смерть владельца доказывается по `/proc` (boot id +
`pstart` + pid), мёртвый номер освобождается по своему имени, тупик ограничен 600 итерациями
(`_ad_wait`), после чего вход честно отказывает, не оставляя своих записей.

⛔ Не проверено: реальный toybox (`set -C`, `ln`, `mv`, `/proc`), `/proc` недоступен на устройстве,
PID-reuse на ГУ — всё это только в отчёте, на ГУ не исполнялось.

## §3.4 JIT-клик → сеть → zygote → интернет

**Статус: 🟡 (число побочных эффектов и порядок шагов — исполнением; интернет на ГУ — ⛔).**

`Iter61JitClickChainTest` → **`OK (8 tests)`**. Проверяется именно **число** побочных эффектов, а не
строка лога:
* один клик ⇒ `payloadRuns == 1`, ровно **одна** payload-команда в транспорте, **ноль** прямых
  вызовов Wi-Fi из клика (возврат Wi-Fi делает продолжение после restart);
* второй клик при незавершённой прошлой команде ⇒ `blocked=previous_unfinished`, `payloadRuns`
  остаётся `1`, вторая команда не уходит;
* клик OFF несёт своё значение `setprop dalvik.vm.usejit false` — та же цепочка, отдельной ветки нет;
* в одной команде: санация `ip rule show | grep -c 'oif wlan0'`, затем свойство, **последним**
  `setprop ctl.restart zygote`; запрещённого (AP/`hostapd`, AVAS, `CarplayMonitor`, `connadaptor`,
  `adb.tcp`, `5555`, `setenforce`, `pm install`, `reboot`) нет ни в одной отправленной команде;
* last-intent-wins: настоящая production-функция `_jit_intent_order` (`JitOwn.kt`) исполняется
  `/bin/sh` — при переводе часов **назад** побеждает большее поколение (`VERDICT=newer_intent
  RULE=seq`), меньшее поколение уступает даже при «более новом» времени; без `seq` правило честно
  называется `ts_legacy`, а запись без `seq`/`ts` — `unprovable` (верх не берётся).
* переполнение/эпоха счётчика намерений — уже закрыты `Iter57SeqTest` (переполнение не
  заворачивается в отрицательное, потеря файла/префов продолжает нумерацию, эпоха не из часов).

⛔ Не проверено: реальный `ctl.restart zygote`, `svc wifi`, `ip rule oif wlan0`, фактический
интернет на ГУ — на устройстве не исполнялось.

## §4 P0 — TTL: «нет файлов ≠ PROVEN_NOT_SENT»

**Статус: ✅ (на стенде, реальный `/bin/sh` + production-сборка скрипта) / ⛔ реальные `iptables`.**

| Дефект аудита | Что сделано | Файл:строка |
|---|---|---|
| `TtlState.kt:262–284` `NotStarted` ⇒ `PROVEN_NOT_SENT` | отсутствие следов и `.started` без terminal — **«неизвестно»** (`Evidence.TracesAbsent`), fence держится | `TtlState.kt:313` |
| `Iter60TtlFenceTest:243–244` закреплял ошибку | тест исправлен (не ослаблен): `TracesAbsent` больше не закрывает попытку, `assertTrue(… is Fence.Blocked)` | `Iter60TtlFenceTest`, `OK (13 tests)` |
| миграция legacy **после** fence (`NetworkTtl.kt:377` vs `:519–521`) | миграция вынесена ДО fence одним шагом | `NetworkTtl.kt:392` (`migrateLegacyBeforeFence`), `:614` |
| `parseJournal:94–102` `null → пусто` | `JournalState{ABSENT,OK,UNREADABLE,CORRUPT}` + `readJournal(text, exists)`; битый/нечитаемый/обрезанный журнал ⇒ `mutationAllowed=false` | `TtlState.kt:115`, `:127` |
| снятие только по доказанному поколению | `Phase.SUPERSEDED`; на устройстве публикуется поколение слота (`GEN_SLOT_FILE`, readback), скрипт ограждён `exit 90` / `VAPP_TTL_SUPERSEDED` | `NetworkTtl.kt:43`, `:698`; `TtlScript.kt:99–108` |
| `forceAbandon` менял `id` | `id` не меняется, причина в `Attempt.reasonHint`, фаза `SUPERSEDED`; `unresolved` её исключает | `TtlState.kt:294`, `:305` |
| каждая retry — своя полная фаза (`:427–455`) | каждая попытка получает свою запись/`MAY_HAVE_RUN`/terminal (`lastFinishedPath`) | `NetworkTtl.kt:467` |
| `markAttempt` игнорировал результат (`:552–562`) | `markAttempt(): Boolean` блокирует send (`phase_not_persisted`) | `NetworkTtl.kt:406`, `:571` |
| `TTL_DESIRE_LAG` только логировался | `scheduleDesiredConvergence("desire_lag")` через `networkRecheckOnce`, `TTL_DESIRE_LAG_DELAY_MS=5000` | `NetworkTtl.kt:513`, `:959` |

`Iter61TtlFenceTest` → **`OK (10 tests)`**: production-скрипт с `guardGen`/`genFile` на настоящем
`/bin/sh` возвращает `exit 90` при устаревшем поколении и `0` при совпадающем; порядок
«миграция → fence» проверяется по исходнику; состояния журнала, фазы retry, `phase_not_persisted`,
`desire_lag` — по production-функциям. Семейство TTL: `Iter61TtlFenceTest` + `Iter60TtlFenceTest` +
`Iter59/58/57/55/54/53/52/51/50` → `OK (107 tests)` суммарно в одном прогоне.

⛔ Не проверено: реальные `iptables`/`ip rule`, toybox `cat`/`printf` на ГУ, поведение при
`restart`/`update`, реальный uplink v4/v6.

## §5 P0 — звук: заявленное восстановление не работало

**Статус: ✅ (дефект воспроизведён зондом и устранён; цепочка — production) / ⛔ слышимость на ГУ.**

Дефект: `ScriptDelivery.evidenceCommand:542–546` всегда печатает `STARTED=0`, если файла `.started`
нет; `LockOp.parseEvidence:51–55` кладёт `"0"` в `started`; ветка `LockOp.kt:457–461` проверяла
`prevFacts.started.isEmpty()` — условие, которое production-парсер не выполняет никогда ⇒ запись
`restore_prepared` (crash до отправки) навсегда оставалась `previous_restore_unresolved`.

Доказательство дефекта исполнением (старый код, production-значения свидетельства):
`PROBE reason=previous_restore_unresolved restores=0` — восстановление запрещено бессрочно.

Исправление (не сравнение с `"0"`):
1. `LockOp.kt:42` — типизированная метка `StartedMark{PRESENT, ABSENT, UNKNOWN}`: `1` → PRESENT,
   `0` → ABSENT, отсутствие строки/мусор → UNKNOWN. Отсутствие файла — **факт о файле**, а не
   доказательство «не отправляли».
2. Доказательство «не отправляли» берётся **из журнала**: `restore_not_sent:*` (исход слоя доставки)
   и `restore_prepared` (identity записана, передачи транспорту ещё не было) — `LockOp.kt:486`;
   для `restore_handoff:*` нужен terminal. Следы на устройстве — сверка; противоречие
   (журнал «не отправляли», а скрипт/`.started`/terminal на месте) ⇒ блокировка, а не безопасный
   повтор (`LockOp.kt:488`, `evidenceContradicts`).
3. `LockSound.kt:1897` — durable-фаза `restore_handoff:<path>` пишется **ДО** вызова транспорта;
   не подтвердили запись ⇒ `handoff_not_persisted` и отправки нет.

Тесты: `Iter61SoundEvidenceTest` → **`OK (9 tests)**` — весь путь production:
`ScriptDelivery.evidenceCommand` исполняется настоящим `/bin/sh` → `LockOp.parseEvidence` →
`LockOp.reconStep` → `LockOp.recoveryGate`; проверяется ABSENT/PRESENT/UNKNOWN, «crash до
транспорта восстанавливается», «crash после передачи — fence», «противоречие — fence»,
`restore_not_sent` повторяется, `unknown` — нет, агрегатор запрещает side effect при
неразрешённой операции. `Iter60SoundAttemptTest` переведён на production-свидетельство
(`evidenceCommand` + `parseEvidence`, файлы `.started`/`.done` реально создаются) → `OK (8 tests)`.
Семейство звука: `OK (89 tests)`.

⛔ Не проверено: слышимость на ГУ, реальный `mount`/`umount` в `/vendor/etc/carlock`, VHAL.

## §6 — доставка

**Статус: ✅ (коллизия и четыре факта — исполнением) / 🟡 (таблица callers — по исходнику).**

* identity готовится и подтверждается в журнале **до** отправки: `NetworkTtl.kt:406`
  (`recordAttempt`) до `AdbClient.executePrepared`; `LockSound.kt:1897` (handoff) до отправки;
  неподтверждённая запись останавливает send (`phase_not_persisted`, `handoff_not_persisted`).
* коллизия имени не создаёт скрытый nonce: `ScriptDelivery.kt:594–601` → `State.COLLISION`,
  `mayHaveRun=false`, nonce/путь журнала не меняются, ни одной команды запуска не уходит
  (`Iter61DeliveryFactsTest`).
* payload rc / transport rc / stdout-маркер / `.done` — разные факты: `transportRc` и `payloadRc`
  раздельны (`ScriptDelivery.kt:111–114`), `rc=0` без маркера **не** `RUN_CONFIRMED`,
  «слишком большой» скрипт = `TOO_LARGE` без единой команды.
* таблица mutating callers (проверяется тестом по исходнику): `NetworkTtl.kt` (TTL-скрипт),
  `LockSound.kt` (restore слоёв). Иных мест, готовящих доставку опасной команды, нет.

`Iter61DeliveryFactsTest` → **`OK (6 tests)**`; коллизия iter60 (`Iter60DeliveryCollisionTest`) — зелёная.

⛔ Не проверено: реальный `adb`, toybox `cksum`/`md5sum`, фактическая заливка на устройство.

## §7 P1 — тумблеры, логи, окна, климат, mount/umount

**Статус: 🟡 (на стенде зелёные существующие тесты) / ⛔ всё, что требует ГУ.**

| Пункт | Что есть | Проверка | Статус |
|---|---|---|---|
| визуальный OFF / «клик включает соседей» | `ToggleIntent` + `WriteAck` (intent по readback, applied по статусу действия), независимые desired/applied/readback, подпись «сохранено, ждёт применения» | `Iter54ToggleTest`, `Iter52TogglesUiTest`, `Iter51TogglesTest`, `Iter50TogglePositionTest` | 🟡 |
| логи | каталог `getExternalFilesDir(null)/logs/`, фоллбэк `filesDir/logs/` назван честно; путь и ротация с числом частей | `LogFilesTest`, `Iter57LogTest`, `Iter54LogTest`, `Iter53LogTest`, `Iter52LogIdentityTest`, `Iter60OwnersLogTest` | 🟡 |
| окна/VHAL с fake | транзакции владельцев окон на fake | `Iter57WindowTest`, `Iter54WindowTest` | 🟡 |
| климат | `AC_AMBIENT_TEMP` 0x2140A377 area 0, `celsius=(raw−80)/2`, `ENV_OUTSIDE` только fallback, коридор правдоподобия | `TempDecodeTest`, `TempFormatTest` | 🟡 |
| mount/umount и обратимость | карта целей и наблюдаемый скрипт | `Iter50MountLayerTest`, `Iter52JournalTest`, `Iter55SoundTest` | 🟡 |
| плагин/статус-бар по скриншоту 2 | — | — | ⛔ (скриншот и ГУ недоступны; ничего не выдаётся за проверенное) |

Ни один из пунктов §7 не помечен ✅: на ГУ (статус-бар, реальное стекло, VHAL, климат-считывание)
проверок не было, а model-тесты за проверку устройства не считаются.

## §8 — harness и handoff

* Прогон: **`OK (1033 tests)`** (`bash /home/user/runtests58.sh`); +41 тест к iter60 (`992` → `1033`),
  из них новых классов три (`Iter61JitSlotRaceTest` 8, `Iter61TtlFenceTest` 10,
  `Iter61SoundEvidenceTest` 9, `Iter61JitClickChainTest` 8, `Iter61DeliveryFactsTest` 6).
* `typecheck_lock.sh /tmp/w60` → `53 / 53`, новых ошибок нет.
* `testDebugUnitTest` / `assembleDebug` / `assembleRelease`: **⛔ локально невозможны** — в песочнице
  только JVM 11, Gradle требует 17 (`Gradle requires JVM 17 or later … currently configured to use
  JVM 11`), Android SDK и сети нет. Запускались отдельно только на CI (см. ниже), `--dry-run`
  локально тоже не проходит по той же причине. Release локально не собирается — это ограничение
  среды, а не результат сборки.
* Handoff: `CODE_SHA` = коммит этого отчёта; `SOURCE_SHA=<CODE_SHA>`; CI — на точном HEAD.

## §9 — протокол владельцу (на согласование, НЕ исполнено)

Предлагается к исполнению **только после разрешения владельца** и по одному шагу:

1. `getprop dalvik.vm.usejit` / чтение `/proc/self/maps` — read-only, без эффекта.
2. Ручной клик JIT ON: одна команда (санация `ip rule oif wlan0` → свойство → `ctl.restart zygote`),
   затем разовое чтение состояния после перезапуска фреймворка.
3. Проверка интернета после клика (без AP и без изменения чужих правил).
4. TTL: ON → readback правил → OFF; проверка, что `oif wlan0` не остаётся.
5. Звук: одна restore-операция с проверкой `.done` и слышимостью.

Требуется от владельца: окно времени, разрешение на перезапуск фреймворка (zygote) и доступ к
устройству. Перезагрузка ГУ лечением не считается и в протоколе не предлагается.

## Что осталось честно незакрытым

* ⛔ ГУ: toybox (`set -C`, `ln`, `mv`, `/proc`), `ctl.restart zygote`, `svc wifi`, `iptables`/`ip rule`,
  поздний shell, mount/umount, VHAL/стекло, слышимость, статус-бар.
* ⛔ Сборки `assembleDebug`/`assembleRelease` и `testDebugUnitTest` в песочнице — только CI.
* 🟡 §7 целиком: на стенде зелено, на устройстве не проверялось.
