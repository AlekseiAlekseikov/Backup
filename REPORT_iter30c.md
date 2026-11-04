# REPORT iter30c — правка 1.0.51 (versionCode 51, номер версии НЕ менялся)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `98ae5b6` (F4 + п.5 + п.6, единственный содержательный) → `…` (этот отчёт + `WORKLOG.md`)
**Версия:** `app/build.gradle.kts` **не менялся** — `versionCode = 51`, `versionName = "1.0.51"`
`git show --stat 98ae5b6` → **только** `app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt` (+40/−14).
**CI:** workflow `401712847`, run **41805548902** — **success** (дубль-диспатч `41805549665` отменён очередью)
**Artifact:** id **13309338493** → APK `/home/user/app-iter30c-v1.0.51.apk`
(215 313 093 B, sha256 `ae1ba6150835dc82d5b7a6b42f7cb4496e47bfafbaeef7fbd7bcfbc02e30433c`, в манифесте `1.0.51`)

> Мелочь, которую фиксирую честно: у коммита `98ae5b6` автор записан как `AlekseiAlekseiKov <x@y>` —
> мой промах при коммите. Переписать автора можно только `--force`, который запрещён, поэтому коммит
> оставлен как есть; в отчётном коммите автор снова корректный. Код и содержимое не затронуты.

---

## 1. F4 — найденный вариант доходит до реального канала

| # | Требование | Статус | Доказательство (`tools/AdbClient.kt`) |
|---|---|---|---|
| 1 | `@Volatile private var workingVariant: Int = 0` | ✅ | `:134` (рядом с `workingHost` `:126`) |
| 1 | В `discoverDestination` при `CNXN` — `workingVariant = d.variant` + лог `ADB_CHANNEL … variant=` | ✅ | `:689` (`workingVariant = d.variant`), `:691` — `ADB_CHANNEL host=… port=… variant=… (вариант берётся из ADB_DEST — им же пойдёт и рукопожатие)` |
| 1 | Ветка `AUTH` тоже запоминает вариант | ✅ | `:709` (`workingVariant = auth.variant`), `:708` — `ADB_DEST result=… auth=required … variant=` |
| 2 | `fullProbe()` → `execAdbSocket(..., variant = workingVariant)` | ✅ | `:961-965`: `execAdbSocket("id; id -Z …", ADB_PORT_DEFAULT, PROBE_TOTAL_MS, PROBE_CONNECT_MS, workingVariant)` |
| 2 | `execAdbSocket` по умолчанию берёт `workingVariant`, явный аргумент остаётся | ✅ | `:1575` — `variant: Int = -1` (маркер «не задано»), `:1582` — `val effVariant = if (variant < 0) workingVariant else variant`; далее в рукопожатие идёт `effVariant` `:1605` |
| 2 | В `ADB_CHANNEL`/`ADB_TX`/`ADB_RX` виден вариант | ✅ | `ADB_CHANNEL … variant=$effVariant` `:1593`, `:1595`; `ADB_TX … variant=$variant` `:1060`; `ADB_RX attempt=… variant=$variant …` `:1068` (silent) и `:1071` (ответ) |
| 3 | Вариант 1 в `handshake` = байты 1.0.43: `version = A_VERSION`, `arg1 = A_MAXDATA_LEGACY`, `HOST_BANNER_LEGACY` | ✅ | `:1054` `val version = A_VERSION`, `:1055` баннер по `variant == 0`, `:1056` `val maxData = if (variant == 0) A_MAXDATA else A_MAXDATA_LEGACY`, `:1059` `writeMsg(os, CNXN, version, maxData, payload)` |
| 3 | Никакого `0x01000001` | ✅ | `grep -n "0x01000001" AdbClient.kt` → **0 вхождений в коде** (осталась только фраза комментария `:1049` «Никакого 0x01000001 здесь больше нет») |
| 3 | Та же формула, что в `probeDestination` | ✅ | проба: `:544-549` (`banner` `:544`, `maxData` `:545`, `writeMsg(os, CNXN, A_VERSION, maxData, payload)` `:548`, лог `:549`); канал: `:1054-1059` — те же три величины и то же условие `variant == 0` |
| 4 | `ADB_TX` печатает фактические значения + `variant=` | ✅ | `:1060` — `ADB_TX cmd=CNXN arg0=0x${version} arg1=$maxData variant=$variant len=${payload.size} hex=$hdrHex` (было жёстко `arg1=$A_MAXDATA`) |

**Разбор критерия готовности (код, без прогона).** Если на машине отвечает только вариант 1, то:

1. `probeDestination(addr, 1, 1)` `:521` → `:544-549` шлёт `variant=1` и печатает
   `ADB_TX(probe <ip>) variant=1 cmd=CNXN arg0=0x01000000 arg1=4096 len=20 hex=43 53 59 4e …` → `:556`
   `ADB_DEST attempt=2 variant=1 addr=… rx=24 cmd=CNXN hex=…` → `:561`;
2. `discoverDestination` `:687-692` → `ADB_DEST result=<ip> working=<ip> … variant=1`; `workingVariant = 1` `:689`;
   `ADB_CHANNEL host=<ip> port=5555 variant=1` `:691`;
3. `bootstrap` `:351-352` вызывает `fullProbe()` → `:961-965` передаёт `workingVariant (=1)` →
   `execAdbSocket` `:1575-1582` с `variant = -1` → `effVariant = 1` → `:1605 handshake(attempt = 1, variant = 1)`;
4. `handshake` `:1054-1060` отправляет **ровно те же байты**, что и проба:
   `43 53 59 4e 00 00 00 01 00 10 00 00 14 00 00 00 5e 07 00 00 bc ac a6 b1`, лог
   `ADB_TX cmd=CNXN arg0=0x01000000 arg1=4096 variant=1 len=20 hex=…`;
5. ответ приходит → `ADB_RX attempt=1 variant=1 cmd=CNXN …` `:1071` → `handshake` возвращает `null`;
6. `execAdbSocket` открывает `shell:id …`, получает `uid=0(root)` → `fullProbe` `:965-971` классифицирует `ROOT_5555`
   → `bootstrap` `:353` `channelUp(pr)` → `ADB_SELFTEST: port=5555 ok uid=0 dt=…` `:1040`.

То есть последовательность в логе будет ровно такой, как в критерии:
`ADB_DEST result=… variant=1` → `ADB_TX cmd=CNXN arg0=0x01000000 arg1=4096 variant=1 len=20` →
`ADB_RX attempt=1 variant=1 cmd=CNXN` → `ADB_SELFTEST: port=5555 ok uid=0`.

---

## 2. Таблица «какой шаблон уходит» — проба и рукопожатие совпадают

Байты посчитаны локальной моделью `msgBytes` (`cmd/arg0/arg1/len/sum/magic`, little-endian;
`sum` = сумма байт payload).

| Вариант | Где | hex заголовка (24 Б) | arg0 | arg1 | len | payload |
|---|---|---|---|---|---|---|
| **0** | проба `probeDestination` `:544-549` | `43 53 59 4e 00 00 00 01 00 00 04 00 09 01 00 00 15 66 00 00 bc ac a6 b1` | `0x01000000` | `262144` | 265 | `host::features=…,pushes\0` |
| **0** | `handshake` `:1054-1059` | те же 24 байта (та же формула) | `0x01000000` | `262144` | 265 | то же |
| **1** | проба `probeDestination` `:544-549` | `43 53 59 4e 00 00 00 01 00 10 00 00 14 00 00 00 5e 07 00 00 bc ac a6 b1` | `0x01000000` | `4096` | 20 | `host::central-geely\0` |
| **1** | `handshake` `:1054-1059` | те же 24 байта (та же формула) | `0x01000000` | `4096` | 20 | то же |

Проверка «одна формула, а не две копии»: `variant → (banner, maxData)` задаётся в двух местах одними и теми же
выражениями (`:544-545` и `:1055-1056`), `version` в обоих местах — `A_VERSION` (`:548` через `writeMsg`, `:1054`),
`writeMsg(CNXN, version, maxData, payload)` — `:548` и `:1059`.

---

## 3. Мелочи

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 5 | `attempt` не зависит от варианта | ✅ | `:1605` — `handshake(s, dis, os, attempt = 1, variant = effVariant, deadline = deadline)`; в `ADB_RX` вариант отдельным полем `:1068`, `:1071` |
| 6 | `ADB_UNIX skipped` — раздельные причины | ✅ | `:407-409`: `val why = if (listenerOwner == "adbd") "owner_adbd" else "owner_unknown"` → строка `ADB_UNIX skipped reason=$why listener_owner=$listenerOwner` |
| 7 | Ничего сверх перечисленного | ✅ | `git show --stat 98ae5b6` → один файл, `+40/−14`; новых файлов/свойств/записей в систему нет |

---

## 4. Запреты (grep по `app/src/main`, после правки)

`65010` 0 · `tcpip:5555` 0 · `android_usb` 0 · `setprop *.adb.tcp.port` 0 (только комментарий `AdbClient.kt:35`) ·
`ctl.restart adbd` — только комментарии `AdbClient.kt:35,310` (в `AppPerformance.kt` — чужие `ctl.restart zygote`,
не менялись) · записи в `adb_keys` 0 · `mt_gpio` — только комментарий и строка лога в `App.kt` ·
`ADB_TOGGLE` — только внутри невызываемой `adbToggleFix()` · новых `settings put` 0 (16 существующих —
`ScreenBrightness.kt`/`ToolsPanel.kt`, в diff итерации их нет).

---

## 5. Что изменилось в логе относительно 1.0.51 (iter30b)

| Строка | Было | Стало |
|---|---|---|
| `ADB_CHANNEL` | `host=… port=5555` | `host=… port=5555 variant=<0\|1>` (`:691`, `:1593`, `:1595`) |
| `ADB_TX cmd=CNXN` | `arg1=262144` всегда, без варианта | `arg0=0x01000000 arg1=<фактический> variant=<0\|1>` (`:1060`) |
| `ADB_RX` | `attempt=variant+1` (вариант 1 → «attempt=2» на первой попытке) | `attempt=1 variant=<0\|1>` (`:1068`, `:1071`) |
| `ADB_UNIX skipped` | `reason=listener_owner=adbd` | `reason=owner_adbd` / `reason=owner_unknown` + `listener_owner=…` (`:409`) |
| `ADB_DEST result=` (AUTH-ветка) | без варианта | `… auth=required … variant=<0\|1>` (`:708`) |

Новых строк-префиксов нет — только уточнённые поля в уже существующих.

---

## 6. Одной строкой (ожидаемый путь по коду, без прогона)

**Если на машине отвечает только вариант 1, приложение само закрепит этот шаблон и поднимет канал:**
`probeDestination` (v1, байты 1.0.43) → `ADB_DEST … variant=1` → `workingVariant=1` (`:689`) →
`fullProbe` (`:964`) → `execAdbSocket` с `effVariant=1` (`:1582`) → `handshake` шлёт **те же** байты
(`:1054-1060`) → `ADB_RX attempt=1 variant=1 cmd=CNXN` (`:1071`) → `channelUp` → `ADB_SELFTEST: port=5555 ok uid=0` (`:1040`).

---

## 7. Проверка (владельцу)

1. `adb disconnect` перед запуском (сессия ПК на 5555 мешает чистому замеру).
2. Установить `app-iter30c-v1.0.51.apk`, запустить, подождать 60–90 с.
3. Смотреть цепочку: `ADB_PROP` → `ADB_DEST attempt=… variant=…` → `ADB_DEST result=… variant=<0|1>` →
   `ADB_CHANNEL … variant=<0|1>` → `ADB_TX cmd=CNXN … arg1=… variant=…` → `ADB_RX attempt=1 variant=… cmd=CNXN` →
   `ADB_SELFTEST: port=5555 ok uid=0`; при провале — `ADB_LISTEN/ADB_OWNER/ADB_SS`, `ADB_UNIX verdict=…`,
   `ADB_VERDICT channel=down listener_owner=… rx_queue_last=… propwrite=…`.
4. Прислать файл лога целиком.
