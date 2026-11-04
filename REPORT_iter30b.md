# REPORT iter30b — дельта к 1.0.51 (versionCode 51, номер версии НЕ менялся)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `3171132` (F1+F2+F3) → `9759a97` (правка F3: unix-канал без перевода `state` в `ROOT_5555`) → `…` (этот отчёт)
**Версия:** `app/build.gradle.kts` **не менялся** — `versionCode = 51`, `versionName = "1.0.51"`;
`git show --stat 3171132` и `git show --stat 9759a97` → **только**
`app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt` (+84/−25 и +16/−1). Новых файлов нет.
**CI (итог):** workflow `401712847`, run **41800171837** — **success**
**Artifact:** id **13307338970** → APK `/home/user/app-iter30b-v1.0.51.apk`
(215 312 005 B, sha256 `b15f44ec65072887ddf2fd06c8961a05d8edd680f0a4fee8b7bd0c92387cdf6a`, в манифесте `1.0.51`)

**Сбои по пути (не код):** run `41796467201` — `failure` с аннотацией раннера
`System.IO.IOException: No space left on device … Worker_20261104-080253-utc.log`;
промежуточная сборка `41798265725` (success, artifact `13306692969`) была до правки F3, в дело не идёт.
Итоговая — `41800171837` на коммите `9759a97`.

---

## 1. F1 — проба покрывает оба варианта пакета

| Что требовалось | Статус | Доказательство |
|---|---|---|
| `probeDestination(addr, attempt)` получает `variant: Int` | ✅ | `tools/AdbClient.kt:510` — сигнатура `probeDestination(addr: String, attempt: Int, variant: Int)` |
| `variant=0` → `A_MAXDATA (262144)` + `HOST_BANNER_FEATURES` | ✅ | `:532-535`: `val banner = if (variant == 0) HOST_BANNER_FEATURES else HOST_BANNER_LEGACY`, `val maxData = if (variant == 0) A_MAXDATA else A_MAXDATA_LEGACY`, `writeMsg(os, CNXN, A_VERSION, maxData, payload)` |
| `variant=1` → `4096` + `HOST_BANNER_LEGACY` | ✅ | `:68` — `A_MAXDATA_LEGACY = 4096` (комментарий фиксирует байты 1.0.43 `arg1=0x1000`, `len=20`); `:534` выбор; сам баннер — существующая `HOST_BANNER_LEGACY` (`:81`), в пробе теперь используется |
| Поле `variant=` в строке `ADB_DEST` | ✅ | `:522` (connect-ошибка), `:556` (silent/short), `:561` (24 Б → CNXN/AUTH), `:565` (прочее) |
| Поле `variant=` в `ADB_TX(probe …)` | ✅ | `:536` — `ADB_TX(probe $addr) variant=$variant cmd=CNXN arg0=… arg1=$maxData len=… hex=…` (`arg1` тоже печатается) |
| Чередование: попытка 1 → v0, 2 → v1, 3 → v0 | ✅ | `:668` — `val variant = if (a % 2 == 1) 0 else 1`; вызов `:669-671` |
| Успех (любой `CNXN`) прекращает перебор на адресе | ✅ | `:675-678` — при `d.cmd == "CNXN"` сразу `return d` (проверка стоит до следующей попытки) |
| Бюджет старта не раздут, окна чтения не менялись | ✅ | `DEST_BUDGET_MS = 32000L` `:73` (не тронут), `ROW_WINDOW_1_MS/2_MS = 500/1500` `:74-75` (не тронуты) |
| В сводке по адресу — `variants=0/1` | ✅ | `:688` — `ADB_DEST addr=… attempts=$attempts variants=0/1 last=… last_variant=… connect=…ms` |
| Протокол/заголовок/размеры/порядок адресов/`fullProbe`/`handshake` не менялись | ✅ | `git show 3171132 -- …/AdbClient.kt` — изменены только `probeDestination`, `discoverDestination`, `Dest`, `logAdbProps`, ветка F3 и `adbDiag`; `writeMsg`/`readMsg`/`handshake`/`fullProbe`/`localTargets` в diff отсутствуют |

**Байты, которые теперь уходят на попытке 2** (проверено локальной моделью заголовка `msgBytes`:
`cmd/arg0/arg1/len/sum/magic`, little-endian, `sum` = сумма байт payload):

```
43 53 59 4e 00 00 00 01 00 10 00 00 14 00 00 00 5e 07 00 00 bc ac a6 b1
cmd=CNXN arg0=0x01000000 arg1=4096 (0x1000) len=20 sum=0x75e magic=~cmd
payload = "host::central-geely\0" (20 байт)
```

Для сравнения вариант 0 (тот же расчёт, полностью совпадает с логом 1.0.50 байт в байт):

```
43 53 59 4e 00 00 00 01 00 00 04 00 09 01 00 00 15 66 00 00 bc ac a6 b1
cmd=CNXN arg0=0x01000000 arg1=262144 len=265 sum=0x6615
```

**Честно о происхождении байт варианта 1:** посимвольного hex-дампа в логе 1.0.43 нет — вывод `ADB_TX`
с hex появился только в 1.0.50. Байты собраны из трёх проверяемых фактов: (1) код того времени слал
`writeMsg(CNXN, 0x01000000, 4096, "host::central-geely\0")`; (2) в мандате §0.2 и в `REPORT_iter28.md`
зафиксировано, что рабочий пакет был `arg1=4096` + `host::central-geely`; (3) контрольная сумма
`0x75e` = сумма байт строки `host::central-geely\0` (посчитано локально). Формат заголовка не менялся —
та же `writeMsg`, что и в 1.0.50/1.0.51.

---

## 2. F2 — `init.svc.adbd` и adb-свойства вне гейта «один раз за загрузку»

| Что требовалось | Статус | Доказательство |
|---|---|---|
| Отдельный всегда-выполняемый вызов с префиксом `ADB_PROP` | ✅ | `:706-714` (`logAdbProps`), строка `:712`: `ADB_PROP key=<имя> val=<значение|(empty)> rc=<n>` |
| Вызов в `bootstrap()` сразу после `logSelfContext()` | ✅ | `:332` — `runCatching { logAdbProps() }…`, сразу после `logSelfContext()` `:325`; до `ADB_DEST` `:345` и до любых проверок исхода пробы |
| Внутри `adbDiag` эти пять строк больше не дублируются | ✅ | `:1008-1012` — старый цикл `for (prop in listOf("service.adb.tcp.port", …))` заменён комментарием-ссылкой; литералы вынесены в одну константу `PROPS_ADB` `:59`, её использует только `logAdbProps` |
| Не гейтится `diagAllowedThisBoot` | ✅ | `logAdbProps()` вызывается из `bootstrap()` безусловно; `diagAllowedThisBoot()` (`:1387`) остался только вокруг `adbDiag` |

Побочный эффект: `ADBDIAG` стал короче на 5 вызовов `getprop` (было до 5 × 2.5 с бюджета).

---

## 3. F3 — одна проба unix-сокета при `listener_owner=foreign`

| Что требовалось | Статус | Доказательство |
|---|---|---|
| Вызов в ветке провала TCP, после `logListenerOwner()`, до `adbDiag` | ✅ | `:378-381` (`logListenerOwner()`), `:380` (`if (listenerOwner == "foreign")`), `:382` (вызов), `:1010-…` — `adbDiag` идёт позже, в конце `bootstrap()` |
| Условие ровно `listenerOwner == "foreign"` | ✅ | `:380`; иначе одна строка `:398` — `ADB_UNIX skipped reason=listener_owner=…` |
| Ровно один вызов, без цикла/таймера/ретраев | ✅ | `grep -n "probeUnixAdbd"` → единственный вызов `:382` + объявление `:1181` |
| Используются существующие строки `ADBDIAG unix-socket ns=… connect=… rx=… cmd=…` | ✅ | `:1181-1240` — `tryUnixChannel` пишет их как раньше (код не менялся) |
| Итоговая строка `ADB_UNIX verdict=… state=… uid=…` | ✅ | `:386` — `ADB_UNIX verdict=up|down state=<ChannelState> uid=0|- dt=…ms ns=…` |
| При успехе — `listenerOwner = "unix"`, `logVerdict(true)`, без `scheduleDeferredRetry` | ✅ | `:389-397`: `listenerOwner = "unix"` `:390`, `channelUpUnix(...)` `:392`, `adbFixVerify("after_unix")` `:393`, `EXEC_PROP` `:394`, `replayDeferred()` `:395`, `logVerdict(true)` `:396`, `return` `:397` — до `scheduleDeferredRetry()` (`:433`) управление не доходит |
| **Отклонение от буквы мандата (сознательное): `channelUp()` не вызывается** | ⚠️ ✅ по смыслу | Мандат просит `channelUp(...)`, но `channelUp` (`:996-1002`) ставит `state = ChannelState.ROOT_5555` и `workingPort`, а `runCommand` (`:1416-1440`) маршрутизирует по `state` → все команды ушли бы обратно на мёртвый TCP. Поэтому при успехе вызывается `channelUpUnix(dt)` (`:987-994`): `state = ROOT_UNIX`, `workingPort = 5555`, и `runCommand` уходит в ветку `execAdbLocalSocket` (`:1420-1429`). Иначе F3 «нашёл канал и тут же его потерял» |
| Без ретраев, без изменения SELinux/прав, без `mount`, без записей в `/dev/**` | ✅ | В diff нет ни `mount`, ни `chmod`, ни `chown`, ни записи в файлы (`git show 3171132` — только строки логики) |

---

## 4. Что изменится в логе относительно 1.0.51 (сводка)

**Новые/изменённые строки:**

| Строка | Когда | Смысл |
|---|---|---|
| `ADB_PROP key=init.svc.adbd val=running rc=0` (+ ещё 4 свойства) | **всегда**, сразу после `ADB_SELF` | свойство adbd видно даже при `ADBDIAG skipped` |
| `ADB_DEST targets=… (…, варианты: v0 arg1=262144+features / v1 arg1=4096+legacy)` | всегда, перед пробами | какие именно байты будут пробоваться |
| `ADB_TX(probe <ip>) variant=1 cmd=CNXN arg0=0x01000000 arg1=4096 len=20 hex=43 53 59 4e …` | на 2-й попытке | шаблон 1.0.43 в чистом виде |
| `ADB_DEST attempt=2 variant=1 addr=… rx=0 cmd=silent dt=…` | каждая попытка | теперь видно, каким вариантом получен ответ |
| `ADB_DEST addr=… attempts=3 variants=0/1 last=silent last_variant=0` | после перебора адреса | итог по адресу с указанием вариантов |
| `ADB_UNIX verdict=down state=BOOTING uid=- dt=…ms ns=-` | только при `listener_owner=foreign` | итог единственной unix-пробы |
| `ADB_UNIX skipped reason=listener_owner=adbd` | если порт держит adbd | объясняет, почему unix не пробовали |

**Строки, которые изменились:**
- из `ADBDIAG` исчезли 5 строк `ADBDIAG prop …` (перенесены в `ADB_PROP`, уровень `i`, не `e`);
- в `ADB_DEST addr=…` добавлены `variants=0/1 last_variant=…`.

**Строки, которые остались без изменений:** `ADB_SELF`, `ADB_PUBKEY`/`ADB_PUBKEY_LEN`, `ADB_KEYS`,
`ADB_ROW`, `ADB_LISTEN`/`ADB_OWNER`/`ADB_PS`/`ADB_SS`, `ADB_LOGCAT`, `EXEC_PROP`, `ADB_VERDICT`,
`ADB_RETRY t=60s`.

---

## 5. Запреты — проверено grep’ом (весь `app/src/main`)

| Шаблон | Вхождений | Комментарий |
|---|---|---|
| `mt_gpio` | 2 | только комментарий `App.kt:21` и строка лога `App.kt:23` («запись отключена») |
| `65010` | 0 | — |
| `tcpip:5555` | 0 | — |
| `setprop *.adb.tcp.port` | 0 | одно вхождение `AdbClient.kt:35` — в комментарии «ни `setprop *.adb.tcp.port`» |
| `ctl.restart` | 7 | `AdbClient.kt:35`, `:310` — комментарии (запрет/эталон); остальные 5 — `AppPerformance.kt` (`ctl.restart zygote`/`$RESTART_SERVICE_NAME`), к ADB не относятся, не менялись в этой итерации |
| `adb_keys` | 12 | только комментарии + `canRead()` + `wc -l` в `ADBDIAG`; записей нет |
| `android_usb` | 0 | — |
| `ADB_TOGGLE` | 18 | всё внутри `adbToggleFix()` (`:1300-1353`), у функции **нет ни одного вызова** (`grep -n "adbToggleFix"` → только объявление) |
| `settings put` | 16 | существующие (`ScreenBrightness.kt`, `ToolsPanel.kt`), в diff итерации нет ни одной новой |

`git show --stat 3171132` и `9759a97` → только `tools/AdbClient.kt`. Панель уведомлений,
`0x2140a6ed`/`0x2140A377`, TTL, `WindowDoors`, `HvacDigitalPlugin`, `ToolsPanel`, `UiBridge` — не тронуты.

---

## 6. Проверка (владельцу) — как читать новый лог

1. `adb disconnect` перед запуском (сессия ПК на 5555 мешает чистоте замера).
2. Установить `app-iter30b-v1.0.51.apk`, запустить, подождать 60–90 с.
3. Ключевые строки по порядку:
   - `ADB_SELF scontext=… uid=… pid=…` — наш домен;
   - `ADB_PROP key=init.svc.adbd val=running` — adbd запущен (теперь это видно всегда);
   - `ADB_TX(probe …) variant=0 … arg1=262144` и `variant=1 … arg1=4096` — оба шаблона ушли;
   - `ADB_DEST attempt=… variant=… cmd=CNXN|AUTH|silent` — **какой шаблон дал ответ**;
   - `ADB_LISTEN`/`ADB_OWNER`/`ADB_SS` — кто держит 5555;
   - `ADB_UNIX verdict=…` — итог unix-пробы (только если листенер «чужой»);
   - `ADB_VERDICT channel=… listener_owner=… rx_queue_last=… propwrite=…`.
4. Прислать файл лога целиком.

**Трактовка (дополняет таблицу iter30):**

| Строка | Значение | Далее |
|---|---|---|
| ответ `CNXN` только на `variant=1` | работает шаблон 1.0.43 (4096 + `host::central-geely`), а не «новый» | в 1.0.52 фиксируем вариант 1 как основной во всём клиенте (`handshake`) |
| ответ `CNXN` только на `variant=0` | дело не в шаблоне | смотрим `ADB_LISTEN`/`ADB_ROW` |
| оба `silent` | формат окончательно исключён | работаем по `ADB_OWNER`/`ADB_UNIX`/`ADB_LOGCAT` |
| `ADB_UNIX verdict=up` | канал найден без TCP | команды уже идут через unix (`state=ROOT_UNIX`), плагин/статусы работают |
