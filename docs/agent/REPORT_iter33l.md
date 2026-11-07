# REPORT iter33l — 1.0.57

**База:** `811ced6` (iter33k fix1 = 1.0.56). **Ветка:** `fix/v2`. **Версия:** `versionCode = 57`, `versionName = "1.0.57"` (1.0.56 установлена 07.11).
**Код:** `3441865` · **buildinfo-маркер:** `3441865`.

```
BUILD: versionCode=57 versionName=1.0.57 commit=3441865 ci_run=local
```

Разбор лога `uploads/voiceapp3 (23).log` (07.11, сеансы 15:02–15:05 и 15:09–15:25) — все выводы ниже
сделаны по нему, файл `РАЗБОР_1.0.56_на_машине_07-11.md` в окружение НЕ передан.

---

## §0. Что показал лог 07.11 (построчно)

| Факт из лога | Строка |
|---|---|
| Применение РАБОТАЕТ технически: push → recarga → verify pid | `HVACDIGITAL_PUSH rc=0 … out=FLAGS_OK` → `RECARGA out=RECARGA_OK pid_before=27025 pid_after=27483 tries=1` → `FLAG_APPLY method=bar_recarga tries=1 result=ok dt=677ms` |
| Но кликов было **пять подряд** за 37 с (пачка, петля) | `15:04:15`, `15:04:24`, `15:04:33`, `15:04:40`, `15:04:47` — по строке `FLAG_APPLY` на каждый |
| Ложный успех: `✓` при отсутствии записи пропов | `HVACDIGITAL_PUSH rc=137 … out=ERR_ADB_TIMEOUT after 5000ms` (`15:09:51`) и `rc=1 ERR_ROOT_CHANNEL_UNAVAILABLE` (`15:20:25`, `15:21:25`, `15:22:25`…) при живом UI-тумблере |
| Настоящая причина порчи «транспорта» — ДВЕ ПАРАЛЛЕЛЬНЫЕ ЗАЛИВКИ одного и того же скрипта | `SCRIPT_UPLOAD name=ttl_script bytes=6148 chunks=4 rc=99` из `CENTRAL_Bg-2` и `CENTRAL_Bg-3` одновременно (`15:10:04.931` и `15:10:05.056`), итог `SCRIPT_BYTES=12296 EXPECTED=6148` (ровно ×2) и затем `No such file or directory` |
| Второй случай того же | `15:04:03` `SCRIPT_UPLOAD name=hvac_enable … rc=99`, `SCRIPT_BYTES= EXPECTED=7408` (файл стёрт соседним потоком) |
| Рут: канал НЕ восстанавливался сам | после `state=NO_ROOT_SH` (`15:19+`) все root-команды падают `ERR_ROOT_CHANNEL_UNAVAILABLE`, `id` при этом отвечает `rc=0` — то есть 5555 живой, сломан именно наш выбор канала |
| JIT: маркер есть, runtime нет | `JIT_PROBE: runtime=false persist=true desired=true` (`15:19:40`, `15:22:40`, `15:24:52`, cycle=20/25/30) |
| TTL-тест недействителен | `VERIFY TTL networkChange: rc=99 → FAILED (rule_not_installed … TTL_V4=null)` — правило не встало из-за порчи заливки |

---

## §1. P0 слоты — семантика закрыта

| Пункт | Статус | Доказательство |
|---|---|---|
| 1.1 `FLAG_VERIFY` после каждой recarga | ✅ | `HvacFlagsApply.kt:75-83` (формат), `:244-265` (`verifyNow`), вызов `:215-236` |
| 1.1 повторный push после `rewritten` (до 3, БЕЗ новых kill) | ✅ | `HvacFlagsApply.kt:224-232`, строка `FLAG_REWRITE pushed=… found=… retries=…` |
| 1.2 эксперимент с именем пропа наружней | ✅ | `HvacFlagsApply.kt:90-99` (`extProbeLine`/`extProbeScript`), `:266-297` (`extProbe`), кнопка «Проба слотов (наружняя)» — `ToolsPanel.kt`, обработчик `ToolsBackend.kt:988-994`, обёртка `StatusIcons.kt:1079-1103` |
| 1.2 `PersistCleaner` больше НЕ чистит `temp_ext` | ✅ | `PersistCleaner.kt:40-46` — мусор в `temp_ext` переводится в `0`, а не в пустое (`reason=legacy_key_normalized`), тест `PersistCleanerTest` обновлён |
| 1.3 разбор SystemUI.apk | ⛔ нет файла | запрос владельцу — §7 отчёта; без декса «точные имена/правила склейки» не выдумываю |
| 1.4 успех тумблера только при `rc=0` И `match=ok` | ✅ | `HvacFlagsApply.kt:234` (`val ok = rc == 0 && match`), `:63-69` (`toggleLine` с `verify`), регресс-тест `ToggleNoLieTest` |

Ожидаемые строки на один клик (после этой итерации):

```
HVACDIGITAL_PUSH rc=0 temp:1 batt:0 ext:0 via=bind out=FLAGS_OK
RECARGA out=RECARGA_OK pid_before=27025 pid_after=27483 tries=1
FLAG_APPLY method=bar_recarga tries=1 result=ok dt=677ms
FLAG_VERIFY wrote=temp:1 batt:0 ext:0 via=bind found=temp:1 batt:0 ext:0 via_found=bind match=ok
TOGGLE [hvacTemp] → ON | flags=temp:1 batt:0 ext:0 | via=bind | apply=bar_recarga result=ok verify=ok | ✓
```

Если SystemUI переписывает пропы — вместо последних двух строк:

```
FLAG_VERIFY … match=rewritten
FLAG_REWRITE pushed=temp:1 batt:0 ext:0 found=temp:0 batt:0 ext:0 retries=1
FLAG_VERIFY … match=rewritten        (до 3 повторов, новых kill нет)
TOGGLE … apply=bar_recarga result=ok verify=rewritten | ✗
TOGGLE_NOT_APPLIED pref=hvacTemp reason=props_rewritten
```

---

## §2. P0 тумблеры без петли

| Пункт | Статус | Доказательство |
|---|---|---|
| 2.1 mutex на время push+recarga+verify | ✅ | `HvacFlagsApply.kt:160-172` (`busy.compareAndSet`, `TOGGLE_SKIP reason=busy`, `finally busy.set(false)`), тест `ToggleNoLieTest.toggleIsSerialized` |
| 2.2 периодический PUSH из sync убран | ✅ | было `pushHvacDigitalPropsIfEnabled(app)` в `syncAllImpl` → стало `alignHvacFlagsOnce(app)` (`StatusIcons.kt:911`), сама функция `:939-978` — один раз за процесс (`alignDone`), пишет только если read-back ≠ UI, лог `FLAG_ALIGN … action=push_once`; sync теперь только читает (`FLAGS_SYNC`) |
| 2.3 ложный успех запрещён | ✅ | `HvacFlagsApply.kt:234,63-69`; регресс-тест `ToggleNoLieTest.toggleSuccessNeedsApplyAndVerify` |

---

## §3. P0 рут: переподключение вместо `ctl.restart adbd`

| Пункт | Статус | Доказательство |
|---|---|---|
| 3.1 восстановление = ПОЛНЫЙ reconnect (тот же цикл, что старт) | ✅ | `AdbClient.kt:301-350` — `reconnectRoot()`: `BOOTING` → `localTargets()` → `discoverDestination(attempts=1)` → `fullProbe()` (`id`) → `channelUp()`; до 3 попыток; строки `ADB_RECOVERY action=reconnect reason=… tries=N root_after=root|none` |
| 3.1 кнопка «Восстановить root-канал» = reconnect | ✅ | `AppPerformance.kt:593-637` — `recoverAdbd()` вызывает `AdbClient.reconnectRoot("owner_click:…")` |
| 3.1 `ctl.restart adbd` — только крайняя мера после reconnect | ✅ | `AdbClient.kt:339-348` (после `root_after=none`, со строкой `action=restart_adbd_after_reconnect`), больше нигде в автоматике нет |
| 3.2 root-команды не падают в `port=sh` | ✅ | `AdbClient.kt:1770-1778` — отказ + фоновый `scheduleReconnectOnce("root_cmd:…")` вместо тихого NO_ROOT |
| 3.3 инструментация обрывов `CHANNEL_LOST last_cmd=…` | ✅ | `AdbClient.kt:265-274` (`noteCmd`/`channelLost`), вызовы: `:1790` (IO на 5555), `:418-421` (таймаут ожидания) |
| 3.4 заливки скриптов с валидацией и повтором | ✅ | `AdbClient.kt:450-495` — `synchronized(scriptUploadLock)`, до `SCRIPT_UPLOAD_TRIES = 3` полных перезаливок, размер сверяется (`size_check=ok`), иначе `SCRIPT_UPLOAD GIVEUP` |
| 3.5 JIT: `persist.dalvik.vm.usejit` + boot-проверка | ✅ | `AppPerformance.kt:725-733` (ключи), `:773-790` (`jitProps` читает 3 значения), `:800-812` (`JIT_PERSIST_SET … readback_init=… marker=…`), `:876-886` (`JIT_BOOT runtime=… persist=… init=… expect_runtime_true_after_reboot=…`) |

**Корень «деградации транспорта» (важно для владельца):** это были не таймауты adbd, а **две
одновременные заливки в один и тот же файл** `/data/local/tmp/voiceapp3_ttl_script.sh` из двух
потоков (`Bg-2` и `Bg-3` в 15:10:04–15:10:05). `rm -f` одного стирал файл другого, чанки
дописывались в общий файл → `SCRIPT_BYTES=12296 EXPECTED=6148` (ровно двойной размер), затем
`No such file`. Теперь заливки сериализованы и повторяются до 3 раз с честным FAILED.

---

## §4. P1 TTL

Код со скоупом не менялся (мандат §4: «код со скоупом остаётся»). Повторный тест владельцем
станет возможен, когда канал перестанет обрываться (§3): раньше правило не вставало не из-за TTL,
а из-за `rc=99 SCRIPT_UPLOAD` (см. §0). 🟡 ждём прогон.

## §5. P1 мелочи

- Красных текстов нет: баннер root-канала уже жёлтый (`#B54708` на `#FDF3E7`, `ToolsPanel.kt:863-870`), новых красных не добавлял.
- `HVACDIGITAL_PUSH wait/deferred` оставлен, но push стал редким (§2.2).
- Остальной код 1.0.56 (`flagsCommand` = `x7.i`, `recargaScript` = `x7.g`, `via`, TTL-скоуп, `buildinfo`) не переписывался.

---

## §6. Тесты и CI

Новые тесты (всего +13 к 94):

| Тест | Что держит |
|---|---|
| `FlagVerifyTest` (5) | `FLAG_VERIFY` формат, `match=ok|rewritten`, разбор 4 строк getprop, `EXT_PROBE` (оба имени, ничего не удаляется), `✓` только при apply+verify |
| `ToggleNoLieTest` (3) | нет `✓` при `rc!=0`; periodic push из sync удалён; mutex клика |
| `ReconnectRecoveryTest` (5) | `reconnectRoot` повторяет цикл старта; `ctl.restart adbd` только внутри reconnect после `root_after=none`; `CHANNEL_LOST`; кнопка зовёт reconnect; сериализация+повтор заливок |
| `JitPersistTest` (4) | `persist.dalvik.vm.usejit` + маркер, запись обоих и read-back, `JIT_BOOT` с тремя значениями, `ctl.restart zygote` отсутствует |

### CI — доказательство (лог джобы)

| Прогон | SHA | Итог |
|---|---|---|
| `42375642156` | `e7f191c` | ❌ компиляция: `AdbClient.kt:268 Unsupported escape sequence` (`"\s+"` в обычной строке) — исправлено на `Regex("\\s+")` в `f0ce4c9` |
| `42376515294` | `f0ce4c9` | ❌ тест: `JitPersistTest > bootProbePrintsThreeValues` — тест ждал неинтерполированный литерал (в исходнике `$PROP_PERSIST_USEJIT_INIT` подставляется в константу) — исправлено в `3441865` |
| `42377387076` | `3441865` | ✅ **success**, **111 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 19s`, job `128417673619`, артефакт `13576018450` (**177 124 140 Б**) |
| **`42378330319`** | **`98d1ae1`** (код `3441865` + маркер `SOURCE_SHA=3441865` + отчёты) | ✅ **success**, **111 PASSED / 0 FAILED**, job `128420457583`, артефакт `13577001294` (**177 124 163 Б**); `BuildMarkerTest > commitSourceIsHonest PASSED` — маркер доходит до `BuildConfig.GIT_COMMIT` |

Строки из лога джобы `128417673619`:

```
FlagVerifyTest > verifyMatchOk PASSED
FlagVerifyTest > verifyRewritten PASSED
FlagVerifyTest > parseFlagsAndVia PASSED
FlagVerifyTest > extProbe PASSED
FlagVerifyTest > toggleSuccessRequiresVerify PASSED
ToggleNoLieTest > toggleSuccessNeedsApplyAndVerify PASSED
ToggleNoLieTest > syncDoesNotPushPeriodically PASSED
ToggleNoLieTest > toggleIsSerialized PASSED
ReconnectRecoveryTest > reconnectExistsAndRepeatsBootCycle PASSED
ReconnectRecoveryTest > adbdRestartOnlyAsLastResortInsideReconnect PASSED
ReconnectRecoveryTest > channelLossIsInstrumented PASSED
ReconnectRecoveryTest > buttonUsesReconnect PASSED
ReconnectRecoveryTest > scriptUploadIsSerializedAndRetried PASSED
JitPersistTest > initKeyPresent PASSED
JitPersistTest > backfillWritesBothKeys PASSED
JitPersistTest > bootProbePrintsThreeValues PASSED
JitPersistTest > zygoteNeverRestarted PASSED
```

(94 → 111 тестов: +13 новых, `BuildMarkerTest` переведён на 57/1.0.57, `PersistCleanerTest` —
на нормализацию `temp_ext`.)

---

## §7. Запрос владельцу (нужен для §1.3)

По мандату §7 и §1.3: **нужен `SystemUI.apk` (read-only)** для разбора декса —
точные имена `persist.sys.hvacdigital.*`, момент чтения, правила склейки ячеек («температура над
батареей» по скриншоту 2). Команды у владельца:

```
adb shell pm path com.android.systemui
adb pull <путь> SystemUI.apk
```

Пока файла нет — 🟡: свою раскладку не рисую, имена пропов не выдумываю. Эксперимент `EXT_PROBE`
(кнопка «Проба слотов (наружняя)») как раз и нужен, чтобы без декса определить, какое имя наружней
читает эта панель (`exttemp` или легаси `temp_ext`).

## Что владельцу сделать на машине

1. Поставить 1.0.57 (версия выше установленной, `versionCode=57`).
2. Открыть «Для разработчика» → дождаться, пока баннер root станет «канал готов» (если он был жёлтым —
   теперь при первом же отказе приложение само переподключит канал; в логе будет
   `ADB_RECOVERY action=reconnect … root_after=root`).
3. Кликнуть **один** тумблер слота и посмотреть лог: должны появиться `FLAG_VERIFY … match=ok|rewritten`
   и `TOGGLE … verify=… | ✓|✗`. Клики во время применения игнорируются (`TOGGLE_SKIP reason=busy`).
4. Нажать «Проба слотов (наружняя)» и сказать, появилась ли наружная температура на панели.
5. Повторить тест TTL (включить → `TTL_SCOPE result=scoped …` → CarPlay → выключить → `TTL_OFF`).
6. Прислать `voiceapp3.log` + по возможности `SystemUI.apk` (pull read-only).
