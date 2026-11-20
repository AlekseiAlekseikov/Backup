# REPORT iter67c — claim слишком длинный (1.0.64)

Ветка `fix/v2`, база `b5f67d4`. `main` не трогался, `--force` не применялся, push — fast-forward.
Версия: `versionCode = 64`, `versionName = "1.0.64"` (`app/build.gradle.kts:78-79`).
`SOURCE_SHA = 57062652385f433435627ab03756ce8cf61202b0` (последний CODE-коммит).
Локальный стенд: `bash /home/user/runtests67.sh` (kotlinc + стабы `android.jar` + JUnit 4.13.2),
финальный полный прогон — `OK (1260 tests)`. Gradle локально недоступен; сборка — только CI.

## Коммиты

| # | Коммит | Что |
|---|---|---|
| 1 | `e226202` | claim доставляется скриптом, если не влезает в инлайн-лимит |
| 2 | `8a7c9b1` | текст helper'ов без комментариев |
| 3 | `e6839f5` | отказ по длине называется (`TooLong` / `claim_cmd_too_long`) |
| 1b | `3ee3cd0` | цепочка payload доставляется файлом, если не влезает (найдено сквозным тестом) |
| 4 | `6ab7aab` | худший случай claim укладывается в таймаут доставки |
| 5 | `edc6070` | штатный отказ JIT не красный и говорит по-русски |
| 6 | `9ba6158` | при rc≠0 в «← execute» виден хвост вывода |
| 7 | `2c23c06` | отсутствующий ключ не выдаётся за ошибку чтения |
| — | `347e075` | версия 1.0.64 (versionCode 64) |
| 2b | `12ec3d6` | claim ≤3500 — тело на устройстве (библиотека) |
| 4.3 | `5706265` | файловая ветка доставки claim покрыта тестом |

---

### Правка 1. Доставка claim по пути, который выдерживает транспорт
Статус: сделано
Файлы: `JitNetCore.kt:154` (`JitShell.execScript`), `JitNet.kt:93` (AndroidShell → `AdbClient.executeScript`),
`JitNet.kt:124` (DiagShell — явный отказ `ERR_ADB_PASSIVE_UNAVAILABLE reason=script_not_supported`),
`JitNetCore.kt:2319–2330` (выбор пути + `JIT_CLAIM_DELIVERY`), `JitOwn.kt:54` (`INLINE_SAFE_BYTES`), `JitOwn.deliveryPath`.
Что было: `val claimRes = shell.exec(JitOwn.claimCmd(opState), 12_000)` — всегда инлайн.
Что стало:
```kotlin
val claimInline = JitOwn.deliveryPath(claimBytes) == "inline"   // claimBytes = AdbClient.inlinePayloadBytes(claimCmd)
if (claimInline) shell.exec(claimCmd, 12_000)
else shell.execScript("jit_claim_$opId", claimCmd, 15_000)
```
+ строка `JIT_CLAIM_DELIVERY op=… path=inline|script bytes=… text_bytes=… limit_inline=3500`.
Замеры: пакет claim 1.0.63 — 44 454 Б (> 4096). Сейчас — 596 Б, `path=inline`.
Тест: `Iter67cClaimDeliveryTest`, `Iter67cClaimPathTest` (`longClaimGoesByScriptPath`, `shortClaimStaysInline`).
Красный прогон ДО (на `b5f67d4`): `Failed vs TooLong(44454,4096)`; «claim должен уйти доставкой скриптом … expected:<1> but was:<0>»; нет `JIT_CHAIN_SENT`.
Красный ДО для §4.3 (мутация `execScript → exec` в ветке script):
`1) longClaimGoesByScriptPath … AssertionError: claim ушёл файлом: [] expected:<1> but was:<0>` / `Tests run: 2,  Failures: 1`.
Зелёный ПОСЛЕ: `OK (18 tests)` (PathTest + DeliveryTest + LibTest); полный стенд `OK (1260 tests)`.
Что НЕ доказано: `AdbClient.executeScript` на ГУ с текстом claim не запускался. После правки 2b
настоящий claim всегда идёт inline; файловая ветка проверяется только подменой текста
через тестовый шов `JitOp.claimCmdForTest` (в бою `null`).

### Правка 2. Компактная форма helper'ов (+ 2b: тело claim — библиотека на устройстве)
Статус: сделано
Файлы: `JitOwn.kt:359` (`compact`), `JitOwn.kt:78` (`HELPERS_TEXT_LIMIT = 13_500`), `JitOwn.kt:54/75`
(`INLINE_SAFE_BYTES = 3_500`, `CLAIM_TEXT_LIMIT = INLINE_SAFE_BYTES`), `JitOwn.kt:971–1180`
(`claimCmd`, `claimScriptForTest`, `_jit_claim_main`, `libText`, `libPath`, `libMissing`, `libInstallScript`),
`JitNetCore.kt:2336–2350` (`JIT_CLAIM_LIB`, установка + один повтор).
Что было: helper'ы и тело claim (с 192, затем 22 строками `#`) целиком в каждой команде claim.
Что стало (по ADB уходит только обвязка):
```sh
set +e
echo CL_BEGIN
_me_inst=…
_jl=/data/local/tmp/voiceapp3_jitlib.84ddd2ac.sh
_jl_n=$(wc -c < "$_jl" 2>/dev/null | tr -d ' ')
if [ "$_jl_n" = "14504" ]; then
. "$_jl"
_me_line="op=…;ts_ms=…;ts_s=…;want=1;seq=1;inst=$_me_inst;pid=$_me_pid;pstart=${_me_pstart:-?};boot=${_me_boot:-?}"
_jit_claim_main /data/local/tmp/voiceapp3_jit.claim <op> <seq> <ts_ms>
else
echo "CL_LIB_MISSING size=${_jl_n:-none} want=14504"
echo CL_STATUS=failed
echo CL_WHY=lib_missing
fi
echo CL_END
```
Библиотека = `compact(ownHelpersRaw + _jit_claim_main)`; имя содержит POSIX `cksum` текста
(другой текст → другое имя), подключается только при точном совпадении размера. Нет библиотеки →
claim ничего не пишет и отвечает `lib_missing`; ядро ставит её файловой доставкой
(`libInstallScript`: heredoc → tmp → сверка размера → `mv -f`, старые версии удаляются) и
повторяет claim ровно один раз.
Замеры: helper'ы 37 072 → 10 992 Б (в сообщении коммита `8a7c9b1` указано 38 360 → 11 325 — иной
`base` при замере). claimCmd 44 134 → 18 184 (после 2) → **555 Б** (после 2b), пакет 596 Б.
Библиотека 14 504 Б, установщик 14 931 Б, строк `#` в библиотеке — 0.
Тест: `Iter67cClaimSizeTest` (4), `Iter67cClaimLibTest` (5), `Iter67cClaimDeliveryTest`.
Красный прогон ДО (2): «claimCmd = 44134 байт, предел 4000», «в отправляемом тексте 192 комментариев».
Красный прогон ДО (2b, `Iter67cClaimSizeTest`, 3/4): «18248 должно быть < 4096»;
«в тексте claim 22 комментариев, первый: # ── порядок НАМЕРЕНИЙ: поздний claim не отменяет более новое намерение ────────»;
«claimCmd = 18184 байт, предел 4000».
Зелёный ПОСЛЕ: `OK (23 tests)` (4 класса Iter67c), полный стенд `OK (1258 tests)`.
Что НЕ доказано: toybox `wc -c`/`tr`/heredoc/`mv` на ГУ; SELinux-контекст файла в
`/data/local/tmp` при `.`-подключении; первый клик на 1.0.64 обязан пройти по ветке
`JIT_CLAIM_LIB action=install` — на машине это не видели.

### Правка 3. `ClaimWrite.TooLong` / `claim_cmd_too_long`
Статус: сделано
Файлы: `JitOwn.kt:1195`, `JitOwn.kt:1212`, `JitOwn.kt:1459`.
Что было: `ERR_ADB_TOO_LONG` → `ClaimWrite.Failed("transport_rc=1")` → `claim_unproven`.
Что стало: `ClaimWrite.TooLong(bytes, limit)` → гейт `send=false, code="claim_cmd_too_long"`, причина с числами.
Тест: `Iter67cClaimDeliveryTest`. Красный ДО: `claim_[cmd_too_long]` vs `claim_[unproven]`.
Зелёный ПОСЛЕ: `OK (10 tests)`, полный стенд `OK (1232)`.
Что НЕ доказано: реальный текст `ERR_ADB_TOO_LONG` на ГУ сверен только с кодом `AdbClient`.

### Правка 1b. Цепочка payload доставляется файлом (вне задания, найдено тестом)
Статус: сделано
Файлы: `JitNetCore.kt:2481` (`JIT_CHAIN_DELIVERY`).
Что было: после успешного claim пакет цепочки 15 339 Б уходил `exec` и отбивался транспортом;
`JIT_CHAIN_SENT` печаталась ДО отправки и ничего не доказывала.
Тест: `Iter67cClaimDeliveryTest` (сквозной). Красный ДО: «транспорт отбил по размеру 1 команд(ы), байт: [15339]».
Зелёный ПОСЛЕ: `OK (11 tests)`, полный стенд `OK (1233)`.
Что НЕ доказано: доставка цепочки файлом на ГУ.

### Правка 4. Таймаут админ-секции
Статус: сделано
Файлы: `JitOwn.kt:782` (`${_ADM_WAIT_ITERS:-100}`), `JitOwn.kt:1124` (`CL_ADMIN_REFUSE=…`), `JitNetCore.kt:2358` (`JIT_CLAIM_TIMEOUT`).
Что было: 400 итераций × 0.02 с — худший случай ≈ 10 с.
Что стало: 100 итераций, строка `CL_ADMIN_REFUSE=busy_timeout iters=100 max=100`, в журнале
`JIT_CLAIM_TIMEOUT op=… waited_ms=… iters=… source=admin_section|transport timeout_ms=… action=stop`.
Замеры: 10 026 мс → 2 493 мс (последний прогон: `ITER67C_WORST_CASE ms=2451`).
Тест: `Iter67cAdminTimeoutTest` (3). Красный ДО: «худший случай claim занял 10026 мс, предел 6000», «нет строки JIT_CLAIM_TIMEOUT».
Зелёный ПОСЛЕ: `OK (51 tests)`, полный стенд `OK (1236)`.
Что НЕ доказано: скорость toybox `sleep 0.02` на ГУ может отличаться от стенда Linux.

### Правка 5. Штатный отказ не красный
Статус: частично
Файлы: `ToolsResult.kt:118` (`⚠ НЕ ВЫПОЛНЕНО`), `JitUi.kt:58` (`refusalText`), `AppPerformance.kt` (уровень stdout).
Что было: TOGGLE печатал «✗ FAILED», `logToggle` — `AppLog.e`.
Что стало: ok → «✓ SUCCESS»; BLOCKED → «⚠ НЕ ВЫПОЛНЕНО» (WARN); прочее → «✗ FAILED» (ERROR).
Тексты: `claim_cmd_too_long` → «не отправлено: внутренняя ошибка размера команды (сообщите разработчику)»,
`claim_unproven` → «…не удалось подтвердить владение, повторите», `op_in_flight` → «…уже выполняется другая операция JIT».
`OP_SUMMARY` по-прежнему содержит `BLOCKED`.
Тест: `Iter67cRefusalTest` (4). Красный ДО (3/4): TOGGLE «✗ FAILED»; `logToggle` через `e()`; нет текста «не удалось подтвердить владение».
Зелёный ПОСЛЕ: `OK (4 tests)`, полный стенд `OK (1240)`.
Что НЕ доказано: `ToolsPanel` (applyNotes `"apply_failed"`, `ToggleIntent.Applied.FAILED`) не менялся —
экран может показать заметку о неудаче. Вызовы `LockSound.kt` 354/412 теперь получают WARN.

### Правка 6. `out=` при rc≠0
Статус: сделано
Файлы: `LogRedact.kt:86` (`execTail`), `AdbClient.kt` (строка `← execute`).
Что стало: `execTail` = "" при rc==0 или passive; иначе ` out=[…]<хвост ≤300 Б>`, IP/MAC/SSID замаскированы, пустой — `empty`.
Тест: `Iter67cExecTailTest` (4). Красный ДО: «в строке «← execute» нет out= при rc!=0: AppLog.d(TAG, "← execute dt=${dt}ms rc=… cmd=${LogKit.redactCmd(job.cmd, 120)}")».
Зелёный ПОСЛЕ: `OK (14 tests)`, полный стенд 1244 (1 нестабильный `Iter64EventIdTest.twoConcurrentProcessesKeepEveryRecord`, отдельно 5/5 OK).

### Правка 7. `PREFS_ABSENT` / `PREFS_UNREADABLE`
Статус: частично
Файлы: `Prefs2.kt:479–494`, `Toggles45.kt`.
Что стало: `PREFS_ABSENT key=K using_default=D` (INFO), `PREFS_UNREADABLE key=K err=…` (WARN),
`PREF_SPLIT … used=…` (INFO; конфликт — WARN). Печать только при смене сигнатуры `read|cps|dps`, не по 60-с таймеру.
Тест: `Iter67cPrefsTest` (5). Красный ДО (3/3): «нет PREFS_ABSENT»; «снимок (раз в 60 с) печатает строку безусловно»;
«ключ отсутствует → Toggles45$Resolved$Unknown → журнал: PREFS_UNKNOWN … reason=unreadable».
Зелёный ПОСЛЕ: `OK (20 tests)`, полный стенд `OK (1249)`.
Что НЕ доказано: `getLong` всё ещё пишет `PREFS_UNKNOWN`; `getBoolean` читает дважды; карта дедупликации — на процесс.

---

## Изменённые существующие тесты

| Тест | Было | Стало | Почему |
|---|---|---|---|
| `Iter53ClaimTest`, `Iter54JitConcurrencyTest`, `Iter55JitTest`, `Iter56JitTest`, `Iter58JitLockTest` | `sh(JitOwn.claimCmd(op, b), …)` | `sh(JitOwn.claimScriptForTest(op, b), …)` | Тесты исполняют claim настоящим `/bin/sh` во временном каталоге без установленной библиотеки. `claimScriptForTest` — тот же claim, где вместо `. "$_jl"` вставлен текст библиотеки байт в байт; эквивалентность проверяет `Iter67cClaimLibTest` («обвязка с библиотекой и самодостаточный текст дают одинаковый исход»). Логика и ожидания тестов не менялись. |
| `Iter56JitTest` (правка 2, 12 строк) | якоря на заголовках-комментариях `# …` | якоря на коде | комментарии удалены из отправляемого текста (правка 2). |
| `Iter61JitSlotRaceTest` | бюджет по умолчанию | `_ADM_WAIT_ITERS=400` | тест проверяет протокол гонки, а не бюджет; бюджет по умолчанию уменьшен правкой 4. |
| `BuildMarkerTest` | `1.0.63`/63 | `1.0.64`/64 (имя метода `versionIs61` прежнее) | §8.10. |
| `Iter67cClaimDeliveryTest` (свой, правка 1) | ожидание `path=script`, пакет > 4096, сторож 18 500 | `path=inline`, пакет ≤ 3500, сторож = `INLINE_SAFE_BYTES` | после 2b обвязка влезает в пакет; файловая ветка перенесена в `Iter67cClaimPathTest`. |
| `Iter67cClaimDeliveryTest`, `Iter67cAdminTimeoutTest` (свои) | поддельный ответ на ЛЮБУЮ команду | только на команду с `CL_BEGIN` | иначе тем же ответом отвечала установка/проверки, и тест мерил не то. AdminTimeout теперь ставит библиотеку настоящим `sh` перед замером. |

## Возражения

1. Задание противоречит само себе: часть 5 п.7 «Не поднимать версию» и §8.10 «Версия поднята: 64 / 1.0.64».
   Выполнено по §8.10 (и по §0.2).
2. Критерий 1 (≤ 3500) правками 1+2 в заданной форме недостижим: даже без комментариев helper'ы — 10 992 Б.
   Поэтому сделана 2b — тело на устройстве. Это новый механизм (файл в `/data/local/tmp`, установка при
   `lib_missing`), которого в задании не было; риск — первый клик зависит от успешной установки.
3. Порядок §6 нарушен: «после 1+2 claim укладывается в лимит» стало верно только в `12ec3d6`.
4. Сообщение коммита правки 2 отличается от шаблона §6 (числа в нём — замер другим `base`).
5. Коммит `3ee3cd0` (цепочка) — вне списка правок, но без него клик всё равно не дошёл бы до машины.
6. Шов `JitOp.claimCmdForTest` — тестовая точка в боевом объекте; без неё файловую ветку claim после 2b не проверить.

## Что НЕ доказано

- **Правка J (`umount`) на машине по-прежнему не проверена.**
- Ничего из iter67c на ГУ не запускалось: установка библиотеки (`JIT_CLAIM_LIB`), `.`-подключение под SELinux,
  toybox `wc`/`tr`/heredoc, доставка цепочки файлом.
- Скорость цикла админ-секции на ГУ.
- `ToolsPanel` может показать «apply_failed» на штатный отказ.
- `getLong` пишет `PREFS_UNKNOWN`.
- CI собирает только `assembleDebug`; unit-тесты локальным стендом, не Gradle.
- `BuildMarkerTest` (версия 64 / 1.0.64, непустой `SOURCE_SHA`) не входит в локальный стенд `runtests67.sh`
  и в CI не запускается: его ожидания исправлены, но этот тест ни разу не прогонялся.

## CI

Workflow `Build APK` (`.github/workflows/build.yml`, триггер `push: fix/**`).

| run | id | head_sha | conclusion |
|---|---|---|---|
| 337 | 44823392408 | `57062652385f433435627ab03756ce8cf61202b0` | success |

Шаги run 337 (все `success`): Set up job → Checkout → JDK 21 → Flutter → Flutter version →
Flutter pub get (flutter_telemetry) → Flutter build AAR (flutter_telemetry) → Android SDK packages →
local.properties → Sanity check → Gradle build (debug) → Upload APK.
Handoff-коммит (этот отчёт, `SOURCE_SHA`, WORKLOG) кода не меняет; его run — в итоговом сообщении.
