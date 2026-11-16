# WORKLOG — iter54 (JIT, доставка, TTL, звук, окна, логи, тумблеры)

Ветка `fix/v2`. `main` не пушился, `--force` не применялся. Версия не менялась: `versionCode=63`, `versionName=1.0.63`.
Локальный прогон: `bash /home/user/runtests.sh` (kotlinc 1.9.24 + `android.jar`-стабы + JUnit 4.13.2), полный отчёт — `REPORT_iter54.md`.

## Журнал итерации

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| §6.1 TTL | идентичность своей цепи: `CHAIN_PREFIX`/токен установки, `chain_state absent\|empty\|ours\|unproven\|collision\|unknown`, историческое имя только для отчёта, `ROLLED_BACK=yes` только после readback | `Iter54TtlOwnershipTest` (9) + `Iter53TtlTest` | `8c0f9b1` `OK (757)` |
| §4 JIT | `ClaimIdentity(op, instance, boot, pid, pstart)`, `CL_ORDER verdict=…`, отказ претендента ≠ terminal владельца, атомарный lock (`temp + ln`), `ClaimWrite{Taken,Busy,Stale,Failed}` + `claimGate` (Failed ⇒ payload не уходит) | `Iter54JitConcurrencyTest` (6) на настоящем `/bin/sh` с барьерами | `9f76bb1` `OK (763)` |
| §5 доставка | `DeliveryOutcome{NotSent, Unknown(op, EvidenceRef), Finished}`, `outcome()`, `directScript()` в подоболочке; потребители `LockSound`/`NetworkTtl` реагируют на `Unknown` | `Iter54DeliveryTest` (10) | `8a54ce6` `OK (773)` |
| §7 звук | `JournalRead{Absent, Unknown(why), Readable}`, `ENOENT ≠ EACCES ≠ corrupt`, идемпотентная crash-safe миграция, `mi_top` = последняя строка mountinfo + сверка с `mount_id`, `AMBIGUOUS` блокирует umount, сверка по ВСЕМ целям | `Iter54SoundTest` (5) + `Iter52JournalTest` | `78cc0a4` `OK (779)` |
| §8 окна | новый `VentOwnerTx` (одно состояние, один lock, поколение); `before→переход→установка→generation` в одной секции; closed-ветка через `mutateOwners` | `Iter54WindowTest` (4): 8×25 транзакций, 200 разных поколений, 0 ошибок | `2252175` `OK (783)` |
| §9 логи | убрана content-дедупликация (`recoverPendingNew`), позиционная граница (`boundary_dup=N`), слияние `.new` под общим `<name>.lock`, при занятом локе `.new` сохраняется | `Iter54LogTest` (6) | `74ebdac` `OK (789)` |
| §10 тумблеры | `ToggleIntent` (intent по readback хранилища, applied по статусу действия), `WriteAck{intent, applied}` + `note()`, `decide()` не путает PARTIAL с отказом записи, подписи «сохранено, ждёт применения», `perfJit` показывает последнее известное, а не default | `Iter54ToggleTest` (15) | `d270a2d` `OK (805)` |
| §10 fix | сводный статус действия — `statusWire` (находка CI: `Unresolved reference 'status'`), `ToggleIntent.Applied.ofWire()` | `Iter54ToggleTest` (16) | `789f8f6` `OK (806)` · CI `43707141785` **success** |
| handoff | `REPORT_iter54.md`, `WORKLOG.md`, `SOURCE_SHA=789f8f6` | CI `43708022868` на `e4f22ec` — **success** | `e4f22ec` |

## Инцидент среды

Песочница откатила `/home/user/w` (пропали `.git` и `app/src`), а также `toolchains/kotlinc/lib`.
Восстановлено: `git clone -b fix/v2` (все коммиты были запушены) + `kotlin-compiler-1.9.24` заново.
После восстановления прогнан полный набор: `OK (805 tests)` на `d270a2d`, затем `OK (806 tests)` на `789f8f6` — цифры выше подтверждены на восстановленном дереве.

## Открытые долги

- Локально `assembleDebug/Release` не собирались (нет Gradle-кэша SDK в песочнице). CI собирает **debug** на `789f8f6` — success (`43707141785`); **release** на этом HEAD не проверялся.
- Живой Android/UI-рендер (Robolectric/instrumentation) не исполнялся — проверены production-модели и JSON снимка, не listener/render.
- На реальном ГУ не проверялись: VHAL окон, zygote-переходы, mount верхнего слоя, выживание TTL после reboot/update, плагин SystemUI.
- Межпроцессность логов — потоки + реальный `FileLock`, без `fork`.

---

# iter56 — владение, terminal, mount, учёт TTL

Ветка `fix/v2`. `main` не пушился, `--force` не применялся. Версия не менялась: `versionCode=63`,
`versionName=1.0.63`. Подробный отчёт — `REPORT_iter56.md`.

## Журнал итерации

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| §4.1 отказ ≠ конец | `_refuse` не пишет `END`; `_jit_owner_dead` требует `END` с точной identity (`op`+`inst`+`pid`+`pstart`, при наличии `boot`), `REFUSED` не участвует | `Iter52JitOwnTest` (`guardDoesNotCreateLockForRefusedPayload`, `endOfAnotherInstanceOrOfAnotherOpDoesNotProveDeath`), `Iter56JitTest.replayRefusalOfTheSameInstanceNeverFreesTheLiveOwner` | `e83e264` |
| §4.2 атомарный захват | `tmp`+`ln` (`_jit_take_lock`), перехват `_jit_steal_dead` (пин inode + сверка), возврат `_jit_restore_dead` через `ln`, `CL_TAKEOVER_KEPT` при занятом имени | `Iter56JitTest.takeoverNeverLosesOrReplacesTheObjectItDidNotProveDead`, `restoreOfAMovedForeignLockNeverOverwritesTheNewOne` | `e83e264` |
| §4.3 порядок намерений | `seq` (монотонное поколение, `bumpIntentSeq` + подтверждение чтением) и единое правило `_jit_intent_order` (`seq` → `seq_owner_ended` → `ts_legacy` → `unprovable`); ранняя и подlock-проверки — один хелпер | `Iter54JitConcurrencyTest.lateClaimOfOlderIntentNeverCancelsNewerIntent`, `Iter56JitTest.newerIntentWinsEvenWhenTheClockWentBackwards`, `equalGenerationsAreATie…`, `legacyClaimWithoutSeq…` | `e83e264` |
| §4.3 чтение claim | формат проверяется по полям (`validClaimValue`): новое поле `seq` не делает валидный claim «испорченным» | `Iter53ClaimTest.realClaimWrittenByClaimCommandIsReadAsPresent` | `e83e264` |
| §5 terminal = исполнение | убран выбор «последнего `inst`»; факты только своего исполнения (`expectInst`), `END` учитывается при совпадении `pid` со `START`, при нескольких исполнениях без identity — `started`/`finished` не заявляются (`term_exec_ambiguous`), легаси-файл помечается `inst_unproven_legacy` | `Iter56JitTest`, `Iter51JitOwnershipTest.terminalParsingDistinguishesEveryProvablePhase` | `d58904b` |
| §5 durable identity | `JitRef.OpMark.inst` пишется в отметку ДО claim/отправки и читается после перезапуска; один `opInst` на клик (отметка → claim → гвардия → чтение terminal) | `JitOrchestratorTest`, `JitPersistenceTest` | `d58904b` |
| §5 гонка lock | ограниченное ожидание (до 2 c) занятого коротким претендентом lock: два одновременных клика больше не дают НОЛЬ side effect'ов | `Iter54JitConcurrencyTest.twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect` (0/40 прогонов после правки; 1/50 до неё) | `d58904b` |
| §7 mount | снятие слоя по литеральному префиксу запрещено: при невычислимом ожидаемом root слой сохраняется (`reason=root_expected_uncomputable action=keep_layer`) | `Iter56MountJitTest` (2 теста; на старом коде второй падает — проверено откатом файла) | `948e4ec` |
| §8 TTL | `TOUCHED_v4/v6` поднимается при любом состоявшемся `-N`/`-A`/`-j`, а не только при создании цепи ⇒ откат покрывает правило, добавленное в существующую свою цепь | семейство `Iter55TtlTest`/`Iter54TtlOwnershipTest` (отдельного теста под reuse-путь не добавлено — см. отчёт §3) | `948e4ec` |

## Прогоны

- `bash /home/user/runtests.sh` → `OK (841 tests)` после `d58904b`, `OK (843 tests)` после `948e4ec`.
- Прогон против старого кода (файл `LockMountScripts.kt` возвращён к `HEAD`): `Tests run: 843, Failures: 1` — падает ровно `Iter56MountJitTest.unprovableExpectedRootKeepsTheLayerInsteadOfGuessing`.

## Инциденты итерации

- Обрыв файла при in-place правке `JitOwn.kt` (срез в python-скрипте отрезал хвост ~250 строк).
  Восстановлено: хвост взят из `git show HEAD:…/JitOwn.kt` и к нему заново применены правки
  iter56 (`_refuse` без `END`, `_jit_restore_dead` вместо `mv`, полный `END` в `trap`).
  После восстановления — полный прогон, `OK`.
- `twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect` был плавающим (1 сбой на ~50 прогонов);
  причина найдена и устранена ограниченным ожиданием lock, подтверждено 40 прогонами без сбоев.

## Открытые долги (iter56)

- **§6 звук (`LockSound.kt`)** — не трогался: `PLANNED` без durable evidence до отправки,
  no-target/corrupt не должны давать Ready, `DONE` не должен закрываться снимком mount,
  поздний restore опасен.
- **§8 `NetworkTtl`** — типизированные исходы вместо `Pair` не вводились.
- **§9 окна** — `WindowDoors.kt` не менялся (все writers, физическое действие после плана).
- **§9 логи** — `renameTo` уже проверяется (`FileLogCore.kt:256,381,403,783,825`), правки не требовалось.
- На ГУ ничего не испытывалось; JIT-компиляция и слышимость WAV не измерялись.

---

# iter57 — план звука (зафиксирован до правок, по решению владельца)

## Состояния звука (то, что МОЖНО утверждать)

```text
подготовка (prepare)        → известны nonce, путь скрипта и путь СВИДЕТЕЛЬСТВА (<path>.done)
PLANNED   + ev=<path>       → записано и подтверждено чтением ДО отправки (сбой записи ⇒ отправки нет)
NOT_SENT                    → доказано: ни один байт скрипта не исполнялся  ⇒ retry безопасен
UNKNOWN(bind)               → ответ потерян, МОГ стартовать: запись НЕ закрывается, новый bind НЕ идёт
STARTED (в свидетельстве)   → конкретное исполнение начало работу; terminal нет ⇒ поздний shell возможен
FINISHED(rc) + verdict      → terminal ДОКАЗАН → только теперь допустим переход в DONE / stock
EFFECT_VERIFIED             → по каждой цели ровно один VERIFIED-факт слоя (iter51/iter56 без изменений)
```

Ключевые правила, которые входят в код:
1. `ev` (путь свидетельства) известен и записан **до** отправки; отправляется ровно подготовленный
   nonce (прямой путь тоже оставляет device-side `.done`).
2. Нет целей/stage, нет свидетельства, свидетельство не читается, `.done` пуст/бит → `UNDEFINED`
   + конфликт; **Ready не возвращается**, новый bind/слепой umount не запускается.
3. Снимок stock/успешный restore **не закрывает** операцию, пока её terminal не доказан
   (`late_shell_unproven`). Restore тоже типизирован: `Unknown` restore держит fencing (новый bind
   запрещён), `rev=` пишется в журнал.
4. `mi_top` и снятие слоя: только по identity источника + всех целей (iter56 сохранён), чужие не
   трогаются, `umount -l` без повторного подтверждения стоком не считается.
5. Неразрешимый по доказательствам случай (легаси-запись без свидетельства) остаётся конфликтом и
   имеет ЯВНЫЙ путь разрешения владельцем (`owner_resolved_unprovable`), а не «ждём вечно».

## Стенд

`LockOp.Reconciler` — production-решатель (его вызывает `LockSound.reconcileLocked`), с
инъецируемыми портами `evidence()`/`restore()`/`journal()`. Тесты гоняют РЕАЛЬНЫЙ журнал в файлах,
реальные `BindOp` и тот же код, подменяя только устройство. Стенд записи/чтения — настоящая ФС.
Границы: JVM + fake device, не ГУ.

---

# iter57 §6–§10: что сделано и чем проверено (итог итерации)

Порядок §4 был: звук (готов, `f06e00a`) → остаточные дефекты JIT → TTL ownership и поздняя доставка
→ логи/окна/UI → тесты, сборки, CI, отчёт.

## §7.1 JIT: старое намерение не побеждает новое (`def5aef`)
`seq` сравнивается только как номер намерения; `newer_intent` — лишь при `me_seq > seen_seq`;
`END` более нового порядок не меняет; без `seq` — `rule=ts_legacy`; нечего сравнивать —
`unprovable`. Удалены `seq_owner_ended` и `_jit_intent_ended` (они объявляли «намерение кончилось»
по факту чужого завершения).

## §7.3 JIT: takeover без окна потери (`def5aef`)
Атомарное создание lock (`temp`+`ln`), смена объекта только под `<lock>.admin`, `_jit_lock_acquire`
→ 0/1/2, перехват — только при точном совпадении с доказанно мёртвой строкой, payload пишет
`ppid`/`ppstart`, `trap` не чистит `<lock>.dead.*`.

## §8 TTL (`4454d04`)
`OWNMARK=${TTL_STATE_DIR}/voiceapp3_ttl_own_<gen>` — device-side маркер создания; `NAMED_OWN`
требует `ownmark_ok` (имя установки владением больше не считается). Стоп
`reason=no_safe_identity` заменён на `TTL_IDENTITY action=create_only`: создание своей цепи
разрешено, дописывание в существующую немаркированную — нет. Единственный безусловный стоп по
имени — историческое имя цепи (`legacy_name_not_owned`). Маркер снимается вместе с цепью.

## §7.2 JIT: атомарная выдача `seq` (`958fdea`)
Файл-счётчик + `FileChannel.lock` (fcntl) + `rename`/`fsync`; префы — вторая копия; часы в номере
не участвуют; `epoch` — случайный токен для лога; переполнение → `null` (порядок `ts_legacy`),
а не отрицательное число; JIT не блокируется навсегда. Лог: `issue=… src=file_lock`.

## §9 Логи (`01e847a`)
Свидетельство `.new` называет полный путь и исходный каталог (`at=`, `origin=`, `moved=`); отказ
`rename` не выдаётся за перенос; `LogFiles.lastDirChoice` называет `external` /
`internal_fallback` с причиной.

## §10 Окна (`40f292b`)
`VentOwnerTx.read` отдаёт снимок (был mutable-set escape), `ownersOf` удалён; откат вынесен в
единственную точку `WindowDoors.rollbackAfterFailedClose`; часы `awakeClock` — чтобы объект вообще
загружался в JVM. `NetworkTtl.parseVerifyResult` → типизированный `VerifyResult` (`cf6373a`).

## §7.4 JIT сквозной путь (`abf9f81`)
Ручной ON/OFF через настоящую `JitOp.click`: один payload, `ctl.restart zygote` последней командой,
возврат Wi‑Fi после рестарта фреймворка, вне клика — ни одной команды, без AP/AVAS/плагина.

## Прогон
`bash /home/user/runtests.sh` → `OK (890 tests)`. Стенд: JVM + fake shell (+ настоящий `/bin/sh` и
подставные `iptables` для TTL). Release локально не собран: Gradle требует JVM 17, доступна 11.

---

# ITER58 — восстановленная итерация (база `026e2f4`)

## Почему «восстановленная»
Песочница потеряла `.git` и `app/src` рабочего дерева; коммиты iter58 существовали только как
запись. Итерация повторена на свежем клоне `fix/v2` (`026e2f4`): правки применены теми же
скриптами (`patch_jitown*.py`, `patch_sound*.py`, `patch_ttl58.py`), тесты написаны заново.
**SHAs отличаются от прежней записи** — воспроизвести их невозможно (хэш зависит от содержимого).

## §2.1 JIT-lock: реальный маршрут
```
клик → JitOp.click → claimCmd (shell)
  _jit_admin_enter   (атомарное set -C создание <lock>.admin; снятие — только при _jit_death_proven)
  _jit_take_lock     (tmp + ln: имя появляется сразу с содержимым)
  запись claim (tmp + mv, readback = taken)
  _jit_release       (под admin, повторное чтение, удаляется ТОЛЬКО своя строка)
  _lock_self_mark    (определена ДО вызова; дописывает ppid/ppstart payload)
guard payload:
  _jit_death_proven → _jit_may_takeover (0 можно / 1 нельзя / 2 payload мог начать)
```
Полярность одна: `rc 0` = смерть ДОКАЗАНА. `REFUSED` доказательством не является.

## §3.1/§3.2 Звук
```
тумблер → LockSound.applySystemLockSoundBind → BgWorker → reconcileLocked(allowRestore)
  LockOp.reconStep(op, ports):
    no_targets → previous_restore_unresolved → prepareRestore (пустой ⇒ нет отправки)
    → ports.restore → not_sent / outcome_unknown → bindLate → verdict
  агрегатор: LockOp.recoveryGate(unresolved, conflict, lateShell, readOnlyPass)
    Conflict > Pending(unresolved:…) > Pending(read_only_pass) > null (можно сходиться)
  null ⇒ bind/OFF-отметка; иначе — ранний return gate, side effect НЕТ
```

## §4 Delivery
`directScript`: `_VRC` снимается ОДИН раз сразу после payload; `.done` получает код payload,
внешний код совпадает; отказ записи честно `written=0`.

## §5 TTL
`-N` → маркер `…_own_<gen>` (шапка один раз + строки `v4=created`/`v6=created`, чтение обратно)
→ при отказе: `action=stop reason=ownmark_not_persisted` + снятие созданной цепи.
`ownmark_ok` требует совпадения цепи И поколения И существования цепи.
OFF снимает только строку своего семейства; файл — когда семей не осталось.

## Прогон
`bash /home/user/runtests58.sh` (клон `/tmp/iter58`) → **`OK (921 tests)`**.
База `026e2f4` тем же стендом → `OK (890 tests)`.
