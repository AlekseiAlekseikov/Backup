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
| handoff | `REPORT_iter54.md`, `WORKLOG.md`, `SOURCE_SHA=789f8f6` | CI на handoff-коммите | этот коммит |

## Инцидент среды

Песочница откатила `/home/user/w` (пропали `.git` и `app/src`), а также `toolchains/kotlinc/lib`.
Восстановлено: `git clone -b fix/v2` (все коммиты были запушены) + `kotlin-compiler-1.9.24` заново.
После восстановления прогнан полный набор: `OK (805 tests)` на `d270a2d`, затем `OK (806 tests)` на `789f8f6` — цифры выше подтверждены на восстановленном дереве.

## Открытые долги

- Локально `assembleDebug/Release` не собирались (нет Gradle-кэша SDK в песочнице). CI собирает **debug** на `789f8f6` — success (`43707141785`); **release** на этом HEAD не проверялся.
- Живой Android/UI-рендер (Robolectric/instrumentation) не исполнялся — проверены production-модели и JSON снимка, не listener/render.
- На реальном ГУ не проверялись: VHAL окон, zygote-переходы, mount верхнего слоя, выживание TTL после reboot/update, плагин SystemUI.
- Межпроцессность логов — потоки + реальный `FileLock`, без `fork`.
