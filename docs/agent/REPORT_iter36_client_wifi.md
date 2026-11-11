# REPORT iter36 (1.0.61) — JIT без точки доступа, честный клиентский Wi‑Fi, реальная проверка JIT‑кэша

Мандат: `ПРОМПТ_JIT_КЛИЕНТСКИЙ_WIFI_ИСПРАВИТЬ_1.0.61.md`.
Ветка **`fix/v2`**. База, от которой шла работа (не откатывалась): HEAD `f2cccebf…`,
код `10cd1378bbc28709aec7a1442df7f5655a548114`, `SOURCE_SHA=10cd137`, CI `43018421872` 218/0.
Версия сохранена: **`versionCode=61`, `versionName=1.0.61`** (владелец 1.0.61 не устанавливал).

**Контрольные суммы референса перепроверены перед анализом** (скачано заново, посчитано локально):

| файл | md5 | результат |
|---|---|---|
| ZIP с диска | `8a65c6c29749c64d4e335c57490a347a` | ✅ совпал |
| `CentralEXAuto_v3.3.9.apk` внутри | `c3f1664125953b9851bf4483b265135f` | ✅ совпал |

Инструменты: baksmali 2.5.2 (`/tmp/sm`), jadx 1.4.7 при необходимости. Оригинал
`/system/app/GeelyAutoSystemUIPlugin/…` не запрашивался.

---

## 0. Публикация, CI, версия

| что | значение |
|---|---|
| код итерации | **`b3705f3`** (родитель `f2ccceb`) |
| `SOURCE_SHA` | **`b3705f3`** |
| run / job | **`43022611861`** / **`130529271884`** — `success` |
| `PASSED` / `FAILED` из полного лога | **221 / 0** (`grep -a ' PASSED'` = 221, `' FAILED'` = 0, `BUILD SUCCESSFUL in 3m 9s`) |
| артефакт | `app-debug`, id `13856077487`, 177 170 063 Б |
| локально (kotlinc + JUnit на фейках) | **`OK (106 tests)`** (было 103; +5 `JitMapsTest`, ±2 переписанных) |
| ветка / пуш | только `fix/v2`; `main` не пушился, force push не использовался |

**Ограничение, которое нельзя обойти:** зелёный CI — это сборка + JVM‑тесты. На ГУ ничего не
запускалось (устройства в этом окружении нет), APK владелец собирает и подписывает сам. Поэтому
нигде ниже не утверждается, что «JIT заработал», «интернет восстановился» или «`umount` безопасен».

---

## 1. Что в референсе фактически (DEX, проверено заново)

### 1.1 `w0/bb` → `w0/sa` → `w0/j5.d` — как читается JIT‑кэш

`w0/bb.smali` (`pswitch_…`, ветка чтения состояния):

```
FileInputStream("/proc/self/maps")
  → BufferedReader(0x2000) → lines()
  → filter(new w0/sa(3))            // оставить только нужные строки
  → join("\n")
  → w0/j5.d(String) → Boolean|null
catchall_67 → const/4 v0, 0x0       // ошибка чтения ⇒ null
```

`w0/sa.smali`, `pswitch_1bb` (case 3):

```
const-string "jit-cache"        → Lo2/k;->e0(CharSequence, CharSequence, Z)Z   // contains
const-string "dalvik-jit-code-cache" → то же
→ Boolean (true, если строка содержит любое из двух имён)
```

`w0/j5.smali` `<clinit>` — регулярка поля `c`:

```
const-string "\\br[-w]x[ps]\\b"
```

`w0/j5.smali` `d(Ljava/lang/String;)Ljava/lang/Boolean;`:

```
splitlines → filter { !isBlank(it) }        // 0048–0056
if (list.isEmpty()) return null             // const/4 p0, 0x0 → return-object
for (line : list) if (Regex.c.containsMatchIn(line)) return true   // 0082–0098
return false
```

Итог: **`null`** — строк нет/файл не прочитан; **`false`** — строки есть, но ни одна не исполняема;
**`true`** — есть исполняемая (`r-xp`, `rwxp`, `r-xs`, `rwxs`).

### 1.2 Клик, скрипт, продолжение (`j9`, `la`, `onCreate`, `e2`) — без изменений с iter35

`j9.onClick`: `isWifiEnabled()` (catchall ⇒ false), `ap0` (catchall ⇒ false), один `commit()` из
`jit_pedido` + `perf_auto_otimizar` + `jit_restaurar="wifi=w,ap=a"`, затем `pool.execute(la)`.
`la.run`: `svc wifi disable` → ≤20×1 с ожидания `ip rule show | grep -c 'oif wlan0'` = 0 →
`umount -l` точек `/data/app/` → `setprop dalvik.vm.usejit` → `setprop ctl.restart zygote`.
`onCreate`: снимок → `matches()` → `remove()` ДО выполнения → `postDelayed(e2, 15000)`.
`e2` (case 1): `if (ap) U("pós-JIT", false)` в try/catch, затем `if (wifi && !isWifiEnabled())
setWifiEnabled(true)`.
`r7`: `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)`, `toggle.setActive(getprop)`,
`remove("jit_pedido").apply()`, предупреждение при `!ok && pedido`.

---

## 2. Что изменено (по пунктам мандата)

### 2.1 ✅ Точка доступа и чужая логика убраны из JIT целиком

| убрано | где было | где было привязано к JIT |
|---|---|---|
| `openAp` / `U` целиком (ранний `v()`, `O()`, `c1()`, owner/`p2`, `V`, latch 25 с, `d()`+опрос 20 с, 3 попытки, пауза 2 с) | `JitNetCore.kt` (бывш. `openAp`, ~200 строк) | только JIT‑продолжение |
| `apUp()`, `startAp()`, `apFailedAtMs()`, `beginApWatch/endApWatch`, `carplayBusy()` | `JitRef.JitNetState` | только JIT |
| `ap0`, `startSoftAp` (рефлексия), `WIFI_AP_STATE_CHANGED` + `wifi_state==14`, `SystemClock` | `JitNet.AndroidNetState` | только JIT |
| константы `AP_LATCH_WAIT_S`, `AP_POLL_TOTAL_MS`, `AP_POLL_STEP_MS`, `AP_ATTEMPTS`, `AP_RETRY_PAUSE_MS`, `AP_STATE_*`, `CARPLAY_WAIT_STEP_MS`, флаг `AUTO_WIFI_ENABLED`, `apLatch`, `JitWaiter` | `JitNetCore.kt` | только JIT |
| логи `JIT_AP …`, `carplay_wait=…` | везде | только JIT |

`JitRef.JitNetState` теперь содержит **ровно три** метода клиентского Wi‑Fi: `wifiOn()`,
`setWifi(on)`, `wifiAddress()` — мёртвого кода «на будущее» нет (`JitOrchestratorTest.noApWorkIsEverRequested`
сверяет состав интерфейса рефлексией). `CarPlay`/его службы/настройки не затрагивались
(`JitRefTest.noApAnywhereInJitCode`).

**Снимок.** Новый минимальный формат: `jit_restaurar = "wifi=0|1"` (`JitRef.SNAPSHOT_RE`,
`JitNetCore.kt:178`). Старый формат референса `"wifi=0|1,ap=0|1"` принимается: берётся только
`wifi`, поле `ap` игнорируется, **ни одного вызова AP при этом не происходит**
(`format=legacy_ap_ignored` в логе). Некорректная запись (`wifi=2`, ` wifi=1`, `wifi=1,ap=0,extra=1`)
не выполняется и не чистится — массовой уборки старых данных нет.

**`AppPerformance.jitApplyWithZygote`** больше не обещает «AP, затем Wi‑Fi» и «сеть вернётся»:
в тексте шага — что именно запрошено (`restore=wifi=…`, `confirmed=…`), что вернул транспорт и
`internet=not_checked`.

### 2.2 ✅ Реальная проверка исполняемости JIT‑кэша (найденная ошибка исправлена)

Было (`JitNet.kt:53–56` до правки):

```kotlin
lines.any { it.contains("jit-cache") || it.contains("dalvik-jit-code-cache") }   // любая строка = успех
```

Это считало успехом даже `rw-p` без права исполнения, а отсутствие строк давало `false`
(в референсе — `null`). Исправлено:

| шаг | наш код | семантика референса |
|---|---|---|
| чтение файла | `JitNet.AndroidShell.mapsExcerpt()` (`JitNet.kt:53`), ошибка ⇒ `null` | `catchall_67` ⇒ `null` |
| фильтр имён | `JitRef.isCacheLine` (`JitNetCore.kt:222`) | `w0/sa` case 3 |
| разбор прав | `JitRef.cacheExecutable` (`:240`), regex `\br[-w]x[ps]\b` | `w0/j5.d`, `0048–0098` |
| склейка | `JitRef.cacheExecutableFromMaps` (`:254`) | `filter` → `join("\n")` → `j5.d` |

Проверенные случаи (`JitRefTest.cacheExecutable*`, `JitMapsTest` — на **настоящих файлах**):

| вход | ожидание | факт |
|---|---|---|
| `r-xp … jit-cache` | `true` | ✅ |
| `rwxp`, `r-xs`, `rwxs` | `true` | ✅ |
| `rw-p … jit-cache` (имя то же!) | `false` | ✅ (прежний код давал `true`) |
| `rw-p` + `r-xp` в одной карте | `true` | ✅ |
| только посторонние строки | `null` (фильтр пуст) | ✅ |
| пустой вывод | `null` | ✅ |
| файл недоступен / ошибка чтения | `null` | ✅ |
| реальный `/proc/self/maps` процесса JVM | `null` (строк `jit-cache` нет) | ✅ |
| тот же файл БЕЗ фильтра | `true` (есть `r-xp` любого файла) | ✅ — именно поэтому фильтр обязателен |

`maps=true` из формулы `r7` не выдаётся за «JIT системы работает»: это наблюдение карты одного
процесса (сказано в KDoc и в отчёте).

### 2.3 ✅ Wi‑Fi до и после клика

1. **Перед кликом** состояние читается в три значения; `null` (исключение/нет `WifiManager`) — это
   «неизвестно», а **не** `false` (`JitOp.click`, `JitNetCore.kt:432`).
2. **Снимок проверяется чтением**: `putSnapshot` (один `commit()` на хранилище) →
   `confirmSnapshot(expected)` — сколько хранилищ реально отдают `"wifi=…"` (`JitNet.kt:140`).
3. **Цепочка не отправляется**, если `confirmed == 0`. Тогда `svc wifi disable` не уходит, а ключи
   пытаются сниматься обратно (с честной оговоркой, что `commit` мог не пройти). Так
   неизвестное/несохранённое состояние не превращается в «ГУ без Wi‑Fi после рестарта».
4. **В продолжении** (`JitOp.restoreWifi`, `JitNetCore.kt:613`): если в снимке `wifi=1` и сейчас
   выключен — один `setWifiEnabled(true)`; результат `true/false/null` пишется как есть; после
   запроса **перечитывается** фактическое состояние и адрес. Если в снимке `wifi=0` — не включаем.
   `Ethernet`, мобильная сеть, маршруты, DNS, TTL не трогаются.
5. **Отдельные read-only признаки**: `state_after=…`, `address=…` (есть ли у `wlan0` не-loopback
   адрес) и `internet=not_checked`. SSID/пароли/личные данные не читаются и не сохраняются.
6. Ложные утверждения убраны: `setWifiEnabled(true)` и `isWifiEnabled()` не выдаются за интернет;
   «сеть вернётся» из текста шага убрано.

Обоснование отличия (разрешено мандатом): референс при исключении чтения считает `false` и всё
равно выключает Wi‑Fi скриптом. На ГУ это означало бы «после рестарта фреймворка сети нет и вернуть
её нечем», поэтому здесь неизвестность **останавливает** разрушительный шаг. Команды самой цепочки
не изменены ни на байт.

### 2.4 🟡/⛔ Продолжение клика и жизненный цикл

Фактическая цепочка (проверено по коду, а не по ожиданиям):

| шаг | где | факт |
|---|---|---|
| сохранение снимка | `JitOp.click` → `AndroidStore.putSnapshot` | один `commit()` на хранилище + подтверждение чтением |
| отправка скрипта | `JitOp.click` → `AndroidShell.exec`, `CHAIN_TIMEOUT_MS=40000` | ответ транспорта ≠ доказательство рестарта |
| рестарт zygote | последняя строка литерала `la` | `ctl.restart zygote`, только по клику |
| повторный запуск | `VoiceAssistantService.onCreate:270` и `onStartCommand:349` → `checkPendingZygoteVerify(source)` | `START_STICKY` (`VoiceAssistantService.kt:406`) — **не доказательство** перезапуска после zygote |
| продолжение | `JitOp.onProcessStart` (`JitNetCore.kt:530`) → `remove()` → `postDelayed(15000)` | возврат Wi‑Fi через `BgWorker.postDelayed` |

Что сделано по риску и повтору:

* `remove(jit_restaurar)` выполняется **до** планирования (как в `onCreate`), поэтому повторного
  действия после успешного снятия нет;
* `onCreate`+`onStartCommand` больше не планируют дважды — процессный флаг `RESTORE_SCHEDULED`
  (`JitNetCore.kt:405`), второй вызов пишет `duplicate=true`;
* если снимок снять не удалось (`commit=false`), это пишется как `removal_unconfirmed=true`, и
  повторной попытки в этом процессе нет; после выполнения задачи снимок пробуют снять ещё раз;
* смерть процесса в 15 с по‑прежнему отменяет возврат — это риск референса, он назван в коде и в
  тесте `deathBeforeScheduledTaskLeavesNetworkUntouched`.

**⛔ Что остаётся недоказанным:** что приложение вообще запустится после `ctl.restart zygote`.
`BOOT_COMPLETED` при рестарте zygote не приходит (`BootReceiver` слушает `BOOT_COMPLETED` /
`LOCKED_BOOT_COMPLETED`), `START_STICKY` — обещание системы «по возможности», а не гарантия. Без
полевого прогона это нельзя ни подтвердить, ни опровергнуть; **гарантированного возврата сети я не
заявляю**. Проверить это можно только одним наблюдением на ГУ (есть ли в логе после рестарта
`JIT_START_RESULT … restore_scheduled=true` и затем `JIT_NET_RESTORE …`), и это не требует новых
кнопок или повторного клика.

### 2.5 ✅ Сверка и тумблер: формула сохранена, гонки закрыты, предупреждение дошло до UI

* Формула `r7` и настоящее удаление ключа **не менялись**; таблица всех 12 комбинаций
  `pedido × getprop × maps` + 6 случаев нечитаемого свойства — `JitRefTest.verifyTableForConfirmedValues`,
  `verifyUnknownPropertyIsNotAVerdict`.
* **Подтверждённый не-успех дошёл до интерфейса**: `JitRef.verifyHint` (`JitNetCore.kt:328`) →
  `AppPerformance.LAST_JIT_HINT`/`jitVerifyHintCached()` (`AppPerformance.kt:914`) → та же
  read-only строка панели (`ToolsPanel.jitRuntimeLineText`, `ToolsPanel.kt:1221`). Новых кнопок нет,
  красного текста нет, цвет — обычный `palette.muted`.
* **Гонка «сверка vs клик»**: `j9` пишет `jit_pedido` до отправки скрипта, поэтому панель/watchdog
  могли прочитать старый `getprop` и удалить свежее намерение (а пользователь увидел бы ложное
  «не применилось»). Теперь сверка не выполняется, пока наш клик в полёте
  (`CLICKS_IN_FLIGHT`, `JitNetCore.kt:691`, лог `skipped reason=click_in_flight`), а перед удалением
  значение перечитывается — если оно изменилось, удаления нет (`race=pedido_changed`).
  Оба случая воспроизведены управляемой очередностью:
  `verifyIsSkippedWhileOurClickIsInFlight` (поток + `CountDownLatch` внутри транспорта),
  `staleVerifyDoesNotEraseNewerRequest`.
* **Два быстрых противоположных клика**: снимок и `jit_pedido` остаются от последнего, оба скрипта
  уходят по порядку (`twoFastOppositeClicksLastOneWinsInSnapshot`); **устаревший async‑ответ** не
  переставляет тумблер после нового клика — сравнение `AppPerformance.jitGeneration()`
  (`AppPerformance.kt:905`, `ToolsPanel.kt:1184-1195`).
* **Пустой кэш в пределах 5 с больше не «съедает» callback**: `refreshJitRuntimeAsync`
  (`AppPerformance.kt:879`) отдаёт кэш только если он есть, иначе реально читает и всегда вызывает
  `onDone`; после чтения обновляются и строка, и тумблер, программно (слушатель снимается —
  обработчик клика не запускается).
* Тумблер: `on`/`off` — по факту `getprop` (в обе стороны, preference не перебивает), `unknown` →
  «· состояние неизвестно» и положение не меняется.

---

## 3. `umount` — ⛔ блокер (данных о ГУ нет)

Скрипт по‑прежнему содержит общий `umount -l` для всех точек, в пути которых есть `/data/app/`
(`JitRef.chainScript`, шаг `mount_step=present`). Владелец разрешил монтирования при соблюдении
обратимости; разрешение на отступление уже дано, повторно не запрашивается. **Но** данных, что эта
конкретная команда обратима и не влияет на работу JIT на этой ГУ, нет — ни из кода, ни из логов.

Что сделано вместо догадок: шаг не маскируется, отличие от референса не вводится «на глаз»
(ограничить команду «нашими точками» без доказательства влияния на JIT — недостаточно, как и
написано в мандате). Подготовлены **короткие read-only команды** (по одной, вывод в чат/лог):

```sh
cat /proc/self/mountinfo | grep -n '/data/app/'
```
```sh
cat /proc/1/mountinfo | grep -n '/data/app/'
```
```sh
ls -l /proc/self/ns/mnt /proc/1/ns/mnt
```
```sh
for m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do echo "== $m"; findmnt -no SOURCE,FSTYPE,OPTIONS --target "$m" 2>/dev/null || grep -F " $m " /proc/mounts; done
```

Как читать результат: наборы точек у нашего транспорта и у PID 1 должны совпадать — иначе
`umount -l` уйдёт не туда, куда кажется; `SOURCE/FSTYPE/OPTIONS` показывают, что именно снимается
(romount/overlay/bind — кандидаты на «необратимо»). **Пустой вывод, «нет доступа» или отсутствие
`findmnt` — это `unknown`, а не доказательство безопасности.** Пробный `umount`, монтирование,
`zygote` или перезагрузка ради анализа не запускались и не предлагаются; до оценки обратимости
просить владельца нажимать JIT не нужно.

---

## 4. Что не менялось (проверено)

TTL и его default, DNS, маршруты (`ip rule` только читается счётчиком), CarPlay, независимые
тумблеры, цифровой климат, статус‑строка, звуки/AVAS (`setAVASMode`, звуковые файлы, `audioserver`
не трогаются). Запреты соблюдены: нет `setenforce 0`, `pm install`, туннелей/5555, `kill audioserver`/
avas, `ctl.restart adbd`, `kill com.android.systemui`, `persist.*` для JIT, авто‑восстановления JIT
при загрузке, новых кнопок/экранов/подтверждений. Логи — `getExternalFilesDir(null)/logs/voiceapp3-ДД.ММ.ГГГГ.log`
с fallback`filesDir/logs/` (не `/data/local/tmp/logs/`). Временные скрипты — `/data/local/tmp`.

---

## 5. Таблица приёмки: референс → наши действия → отличие и причина → файл:строка → тест

| # | референс | наши действия | отличие и причина | файл:строка | тест |
|---|---|---|---|---|---|
| 1 | `j9`: `isWifiEnabled`, `ap0`, один `commit()`, `execute(la)` | читаем Wi‑Fi (3 значения), пишем `jit_pedido`+`perf_auto_otimizar`+`jit_restaurar`, отправляем скрипт | AP не снимается вовсе (решение владельца); неизвестный Wi‑Fi/неподтверждённый снимок блокируют отправку — иначе сеть не вернуть | `JitNetCore.kt:432–520`, `JitNet.kt:101–150` | `clickSavesSnapshotInEveryStoreAndSendsReferenceScript`, `clickWithUnknownWifiIsBlockedAndTouchesNothing`, `clickWithoutConfirmedSnapshotIsBlocked` |
| 2 | снимок `wifi=…,ap=…` | новый `wifi=0\|1`; старый читается, `ap` игнорируется | AP в снимке больше не нужен; миграция без потери старых данных | `JitNetCore.kt:178–190` | `newSnapshotIsMinimalWifiOnly`, `legacySnapshotIsAcceptedAndApIgnored`, `brokenSnapshotIsNotExecuted` |
| 3 | `la.run`: `svc wifi disable` → слив правил ≤20 с → `umount -l` → `setprop` → `ctl.restart zygote` | тот же литерал, 392 символа | нет | `JitNetCore.kt:193–213` | `JitRefTest.chainScriptIsReferenceLiteralByteForByte`, `JitChainScriptTest.orderIsReferenceOrder` |
| 4 | `umount -l` общий по `/data/app/` | исполняется (`mount_step=present`) | ⛔ обратимость на ГУ не проверена, данных нет | `JitNetCore.kt:205–207` | `JitChainScriptTest.mountStepTouchesOnlyDataApp` |
| 5 | `onCreate`: снимок → `remove` → `postDelayed(e2,15000)` | так же; планировщик — `BgWorker.postDelayed` | нет специфичного для AP; добавлен процессный флаг «уже запланировано» против двойного вызова `onCreate`+`onStartCommand` | `JitNetCore.kt:530–606` | `snapshotRemovedSynchronouslyAndRestoreScheduled15s`, `secondStartAfterRemovalSchedulesNothing`, `doubleStartWithUnconfirmedRemovalSchedulesOnce`, `removalFailureStillSchedulesOnceAndSaysSo` |
| 6 | `e2`: AP, затем `if (wifi && !isWifiEnabled()) setWifiEnabled(true)` | только клиентский Wi‑Fi: один `setWifiEnabled(true)`, если был включён; перечитывание факта | AP не поднимается (решение владельца); возвращённое `true` не выдаётся за интернет | `JitNetCore.kt:613–690` | `restoreRequestsWifiWhenItWasOnAndOffNow`, `restoreDoesNothingWhenWifiIsAlreadyOn`, `restoreNeverTurnsWifiOnWhenItWasOff`, `restoreReportsFalseAndNullWithoutClaimingSuccess`, `restoreExceptionIsReportedAsFailureNotSuccess` |
| 7 | `bb`+`sa`+`j5.d`: фильтр имён + права `\br[-w]x[ps]\b`, `null` при пустом | тот же разбор, три значения | исправлена реальная ошибка: любая строка = успех; `null` вместо `false` | `JitNetCore.kt:222–262`, `JitNet.kt:53–60` | `cacheExecutableUsesPermissionsNotFileName`, `cacheExecutableNullIsNotFalse`, `mapsFilterAndJoinIsLikeReference`, `JitMapsTest` (5, на файлах) |
| 8 | `r7`: `ok=(pedido==getprop)&&(!pedido‖maps!=FALSE)`, `remove`, предупреждение | та же формула, настоящее удаление; при `unknown` ключ сохраняется | неизвестное не выдаётся за результат (разрешённое отличие) | `JitNetCore.kt:307–330`, `691–760` | `verifyTableForConfirmedValues`, `verifyUnknownPropertyKeepsIntent`, `verifyOkRemovesPedidoByRealRemoval` |
| 9 | сверка в настройках (`S0`→`bb`) | при открытии панели и в watchdog раз в 5 тиков при наличии `jit_pedido` | + не идёт во время клика; + не снимает изменившийся запрос | `AppPerformance.kt:1033`, `JitNetCore.kt:691–730` | `verifyIsSkippedWhileOurClickIsInFlight`, `staleVerifyDoesNotEraseNewerRequest` |
| 10 | `toggle.setActive(getprop)`, предупреждение in‑app | тумблер по факту, предупреждение — в существующей строке панели | неизвестное → «состояние неизвестно»; без новых кнопок/красного текста | `ToolsPanel.kt:1162–1232`, `AppPerformance.kt:905–914` | `JitUiStateTest` (7), `JitRefTest.verifyLabelsAndHints` |
| 11 | — | async‑ответ не переставляет тумблер после нового клика; пустой кэш не съедает callback | исправлены две ошибки UI | `AppPerformance.kt:879–906`, `ToolsPanel.kt:1184–1195` | `panelRunsVerificationOnOpenAndUpdatesBothWidgets`, `panelProgrammaticUpdateDoesNotFireClickListener` |
| 12 | AP/CarplayMonitor/`connadaptor_modo` | **не перенесены** | решение владельца: не нужны | нет кода | `noApAnywhereInJitCode`, `noApWorkIsEverRequested` |
| 13 | потеря канала транспорта | `outcome=channel_lost`, `action=none`, ни «выполнено», ни «не выполнено» | нет | `JitNetCore.kt:490–510` | `lostChannelIsNotLoggedAsSuccess`, `JitChainScriptTest.failingSetpropDoesNotBreakOrder` |

---

## 6. Тесты (JVM, не Android‑интеграционные, не на ГУ)

`bash runtests.sh` → **`OK (106 tests)`**. CI: 221/0.

| файл | шт. | что покрывает |
|---|---|---|
| `JitOrchestratorTest` | 30 | клик (Wi‑Fi on/off/unknown, падение 0/1/2 хранилищ, `commit=false` при записанном значении, нет хранилищ, потеря канала), продолжение (новый/старый/битый снимок, дубль, снятие не подтверждено, смерть до выполнения), возврат Wi‑Fi (включён/выключен/не читается/`false`/исключение), сверка (нет намерения, ok, not_applied, cache_missing, unknown, повтор, во время клика, «запрос изменился»), два противоположных клика, отсутствие AP |
| `JitRefTest` | 16 | литерал скрипта, форматы снимка, `getprop`, таблица `r7` (12+6), подписи/подсказки, разбор maps (10 случаев), тумблер, запреты, отсутствие AP |
| `JitMapsTest` | 5 | **реальное чтение файлов**: temp‑файл в формате `/proc/self/maps`, отсутствующий файл ⇒ `null`, собственный `/proc/self/maps` JVM |
| `JitChainScriptTest` | 8 | настоящий `/bin/sh`: порядок, слив правил, только `/data/app/` под `umount -l`, отказ `setprop` |
| `JitUiStateTest` | 7 | настоящее положение тумблера, программное обновление без клика, «неизвестно», сверка при открытии |
| `JitStartZeroWritesTest` | 5 | обычный старт: ноль записей/команд/сетевых вызовов, нет авто‑JIT |
| `JitLogsCancelTest` | 5 | раздельные строки состояния/запроса/ответа, нет механизма отмены |
| `JitNoPersistTest` | 3 | нет `persist.*`, `usejitprofiles` |
| `JitBootBoundaryTest` | 4 | boot‑проба только читает, рестарт только из клика |
| `JitRulesReadOnlyTest` | 2 | `ip rule` только считается |
| `AvasIndependenceTest` | 5 | AVAS не в цепочке; звуки замков не тронуты |
| `LogFilesTest` | 6 | путь логов |

Чего эти тесты **не** доказывают: ни одного факта о ГУ — ни состояния Wi‑Fi, ни `WifiManager`,
ни `/proc/self/maps` ГУ, ни того, что приложение поднимется после рестарта zygote.

---

## 7. Итог по статусам

| пункт мандата | статус |
|---|---|
| §2.1 убрать AP и чужую логику, миграция старого снимка, новый минимальный формат | ✅ |
| §2.2 проверка исполняемости JIT‑кэша по правам, `true/false/null`, тесты на реальные строки | ✅ |
| §2.3 Wi‑Fi до/после клика, неизвестность не выдаётся за «выключено», снимок подтверждается чтением, CPS/DPS независимо, `internet=not_checked` | ✅ |
| §2.4 lifecycle и продолжение клика | ✅ по коду; ⛔ запуск приложения после `ctl.restart zygote` недоказуем без ГУ |
| §2.5 сверка, тумблер, гонки, предупреждение в UI | ✅ |
| §3 `umount` | ⛔ нужны read-only данные с ГУ (команды в §3); до этого просить владельца нажимать JIT не нужно |
| §4 запреты, независимые функции | ✅ (проверено тестами по исходнику) |
| §5.1–5.4 таблица, тесты, CI, ветка/версия/SHA, отчёт | ✅ |

**Чего я не утверждаю:** что JIT работает на ГУ; что интернет восстановится; что `umount -l`
обратим; что приложение обязательно запустится после рестарта zygote; что в 1.0.61 что‑то проверено
на машине (владелец её не устанавливал). Всё, что помечено ⛔, требует данных с ГУ или полевого
прогона, который я не проводил.
