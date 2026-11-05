# REPORT iter27 — 1.0.49 (закрытие замечаний ревью iter26: D1, D2, D3, D4)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммит:** `8677282` — «iter27 (review iter26): D1 both unix namespaces, D2 hasRoot()/channelLabel(), D3 toggle pause 1.5s + usb_state, D4 accept-queue note; version 1.0.49»
**CI:** workflow `401712847`, run **41670466620** — **success**
**Artifact:** id **13248522532** → APK `/home/user/app-iter27-v1.0.49.apk`
(215 272 193 B, sha256 `941917ae6e2b6e1f9cfac7f0f4c29293f550bd06963b6df278e730f9be06c2dc`)
**Версия:** `app/build.gradle.kts:60` `versionCode = 49`, `:61` `versionName = "1.0.49"`.

Ревью прочитано целиком. Ниже — по каждому пункту: что было, что сделано, где именно, чем проверено.
Пункт A (пакет) не менялся — ревью подтвердило его независимо, файл в репозитории тот же
(`ota/PERSISTENT_PROPS_READ.zip`, sha256 `55ce8d61…d743ba`).

---

## ❌ D1 → ✅ — `probeUnixAdbd()` пробует ОБА пространства имён

**Замечание ревью было верным.** В 1.0.48 стояло только
`LocalSocketAddress("adbd", Namespace.ABSTRACT)` (`AdbClient.kt:53,644` в сборке 1.0.48) — а adbd создаёт
**файловый** сокет `/dev/socket/adbd`. Абстрактное имя `@adbd` в этой прошивке может не существовать,
и мы получили бы ложный `ECONNREFUSED` → ложный вывод «доступа нет».

**Что сделано** (`tools/AdbClient.kt`):
- `:54` добавлена константа `ADBD_SOCKET_PATH = "/dev/socket/adbd"`;
- `:653` перед подключением пишется состояние файла:
  `ADBDIAG unix-socket ns=filesystem path=/dev/socket/adbd exists=<b> canRead=<b>` — теперь «EACCES»
  не спутать с «сокета нет»;
- `:655-658` попытки идут по порядку: **сначала** `Namespace.FILESYSTEM` + путь, **потом**
  `Namespace.ABSTRACT` + `"adbd"`;
- каждая попытка вынесена в `tryUnixChannel(nsName, addr, t0)` (`:665`), и **все** строки лога несут
  префикс `ns=filesystem|abstract`: `connect=ok rx=… cmd=… hex=…`, `connect=EACCES|ECONNREFUSED|EIO…`,
  `uid=0 (работаем через unix…)`;
- сработавшее пространство запоминается в `unixNs` (`:711`) и используется дальше в
  `execAdbLocalSocket(command, timeoutMs, nsName = unixNs…)` (`:714-721`) — то есть рабочие команды идут
  ровно через тот сокет, который ответил, а не «наугад».

**Приёмка по логу:** либо `ADBDIAG unix-socket ns=filesystem connect=ok … cmd=CNXN`, либо `ns=abstract …`,
либо две строки с разными `ns=` и разными причинами отказа — и тогда видно, какой именно вариант недоступен.

---

## ❌ D2 → ✅ — единый `hasRoot()`, UI больше не врёт

**Замечание было верным и важным:** при успешном unix-канале владелец увидел бы красное
«канал недоступен» — ложный отрицательный результат.

**Что сделано:**
- `tools/AdbClient.kt:1175-1194` — новый публичный API:
  - `@JvmStatic fun hasRoot(): Boolean` = `state in {ROOT_5555, ROOT_UNIX}`;
  - `@JvmStatic fun channelLabel(): String` — человеческое имя канала
    (`"TCP 127.0.0.1:5555"`, `"unix-сокет ns=filesystem"`, `"нет (uid=1000, без root)"`, …);
  - `executeSmart()` (`:627`) теперь тоже опирается на `hasRoot()`, а не на одно состояние.
- `ui/ToolsPanel.kt:831-836` — баннер:
  `val ok = st == "ROOT_5555" || st == "ROOT_UNIX"`, а для unix-канала текст
  **`ROOT: канал готов — unix-сокет «adbd» (TCP не используется)`**.
- `tools/HvacDigitalPlugin.kt:405-408` — `val haveRoot = AdbClient.hasRoot()`;
  подпись шага `restore` исправлена на честную: `umount + kill systemui (нужен root-канал: 5555 или unix-adbd)`.

Точечное снятие запрета «не трогать `ToolsPanel`/`HvacDigitalPlugin`» — ровно на эти строки, как предложило ревью;
никакой другой логики в этих файлах не менялось.

**Приёмка:** при `ROOT_UNIX` баннер зелёный, «Вернуть панель SystemUI» выполняет `restore` (а не `BLOCKED`).

---

## 🟡 D3 → ✅ — `ADB_TOGGLE` стал безопаснее для USB

**Что сделано** (`tools/AdbClient.kt:800-822`):
- пауза с выключенным `ADB_ENABLED` уменьшена **3000 → 1500 мс** (`:801-803`), в лог добавлено пояснение,
  зачем она минимальна;
- сразу после восстановления значения читается USB-состояние и пишется одной строкой:
  `ADB_TOGGLE usb_state=<sys.usb.state> usb_config=<sys.usb.config>` (`:818-820`) — при следующем отваливании
  USB будет видно, изменилось ли состояние;
- восстановление по-прежнему в `finally` (`:810-817`), то есть при любом исходе и при исключении;
- в отчёте и в логе зафиксировано: **если USB отвалится — первым откатывать именно `ADB_TOGGLE`**
  (строка-предупреждение `:758`, она печатается до любых записей).

---

## 🟡 D4 → ✅ — замер `Recv-Q` больше не вводит в заблуждение

`tools/AdbClient.kt:486` — перед замером печатается
`ADBDIAG accept: (держим 1 соединение, пока читаем таблицы)`.
Закрывать сокет до чтения таблиц нельзя (нужен `ESTABLISHED`), поэтому выбран именно этот вариант —
пометка в логе, как и предлагало ревью.

---

## Что НЕ менялось

- Пакет `ota/PERSISTENT_PROPS_READ.zip` — без изменений (ревью подтвердило подпись независимо:
  `Verification successful`, наш сертификат, тот же формат, что у принятого пакета владельца).
- Запреты соблюдены. `grep -rn "mt_gpio|65010|tcpip:5555" app/src/main/java` → **2 строки, обе — текст
  лога `USB_GUARD` в `App.kt:21,23`** («запись в mt_gpio отключена»). Ни одного исполняемого обращения нет.
- Панель уведомлений, `0x2140a6ed`, `0x2140A377`, TTL, `WindowDoors`, `TtlScript` — не тронуты.
- `ctl.restart adbd`, `setprop *.adb.tcp.port` — по-прежнему отсутствуют (только комментарии-пояснения).

---

## Таблица «строка лога 1.0.49 → значение → 1.0.50»

| Строка | Значение | Что делаем в 1.0.50 |
|---|---|---|
| `ADBDIAG unix-socket ns=filesystem … connect=ok rx=24 cmd=CNXN` + `ADB_SELFTEST: unix-socket ok ns=filesystem uid=0` | канал найден через файловый сокет | закрепляем filesystem-путь первым, TCP оставляем fallback; UI уже показывает «канал готов» |
| `ns=abstract … uid=0` | сработал абстрактный `@adbd` | то же, но фиксируем abstract как предпочтительный |
| `ns=filesystem connect=EACCES`, затем `ns=abstract connect=ECONNREFUSED` | оба варианта закрыты для system-uid | остаётся только TCP-путь → смотрим на `ADB_TOGGLE` |
| `ns=filesystem exists=false` | сокета по пути нет (другой init) | искать имя сокета через `/proc/net/unix` (уже логируется) |
| `ADB_TOGGLE usb_state=adb` после шага | USB-режим не изменился | спокойно оставляем `ADB_TOGGLE` в bootstrap |
| `ADB_TOGGLE usb_state=` пусто/`none` + USB отвалился | шаг ломает USB на этой прошивке | **откатывать `ADB_TOGGLE` первым**, шаг убрать из bootstrap |
| `ADB_TOGGLE result=OK` + `ADBFIX verify[after_toggle]=OK` | канал вылечен перезапуском adbd | закрепляем как штатный шаг |
| `ADBDIAG accept: (держим 1 соединение…)` + `recvQ=0 established=false` | adbd не вызывает `accept()` | копать состояние adbd; если ничего — ставим OTA-пакет и смотрим `.raw` |

---

## Итог

| Пункт ревью | Было | Стало |
|---|---|---|
| D1 namespace unix-сокета | ❌ только abstract | ✅ оба, с логом `ns=` и `exists/canRead` |
| D2 `ROOT_UNIX` в UI/`haveRoot` | ❌ ложное «канал недоступен» | ✅ `hasRoot()` + честный текст баннера |
| D3 пауза `ADB_TOGGLE` + USB | 🟡 3 с, без контроля USB | ✅ 1.5 с + `ADB_TOGGLE usb_state=` |
| D4 замер очереди | 🟡 | ✅ пометка в логе |
| A. Пакет | ✅ | без изменений |

Сборка: CI `41670466620` = success, artifact `13248522532`, APK `/home/user/app-iter27-v1.0.49.apk`.
Порядок для владельца тот же, что был: сначала 1.0.49 (5 минут, откатываемо, USB-риск минимизирован),
и только если канала нет — OTA-пакет диагностики в recovery.
