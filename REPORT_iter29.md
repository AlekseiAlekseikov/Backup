# REPORT iter29 — 1.0.51 (versionCode 51)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `8733ca4` (код итерации) → `d1bcb8b` (этот отчёт + `WORKLOG.md`)
**CI:** workflow `401712847`, run **41774001800** — **success** (дубль-диспатч `41773999518` отменён GitHub,
это нормальное поведение очереди, не сбой кода)
**Artifact:** id **13296771475** → APK `/home/user/app-iter29-v1.0.51.apk`
(215 284 077 B, sha256 `1fbdadeae91f98367efe3b5a3a334d4f571a1ac1c02539e22304e6f3b9f7141a`)
**Версия:** `app/build.gradle.kts:60` `versionCode = 51`, `:61` `versionName = "1.0.51"`.

**Исходная точка.** В 1.0.50 наше рукопожатие доказанно корректно (`ADB_TX cmd=CNXN arg0=0x01000000
arg1=262144 len=265`), подключение мгновенное, а ответа нет ни в одном из двух вариантов
(`ADB_RX attempt=1 cmd=silent dt=321ms/2999ms`, `ADB_RX attempt=2 cmd=silent dt=2998ms`). Ключи и `AUTH`
из уравнения выведены: у ГУ `/data/misc/adb` пуст, `adb_keys` отсутствует, но ПК получает `uid=0(root)`.
Поэтому итерация не трогает формат пакета, а ищет **признак, по которому adbd отбраковывает клиента**:
контекст пира (H1) и адрес/интерфейс (H2). Обе гипотезы проверяются в одном прогоне.

---

## 1. Почему тема AUTH/ключей закрыта навсегда (чтобы не возвращалась)

Доказательства (вывод владельца с ПК, root-шелл, 1.0.50):

| Факт | Значение |
|---|---|
| `ls -l /data/misc/adb/` | `total 0` — каталог пуст, ключей нет |
| `wc -l < /data/misc/adb/adb_keys` | `No such file or directory` — файла нет вовсе |
| `getprop ro.secure` (из лога 1.0.50: `ADBDIAG prop ro.secure rc=0 val=0`) | `0` — adbd работает root'ом и **авторизацию не требует** |
| `adb connect <ip>:5555` с ПК | `uid=0(root) … u:r:su:s0` — root-шелл без всяких ключей |

Вывод: `AUTH`/подпись токена/`adb_keys` **не являются и не могут быть** причиной молчания. В 1.0.51:

- `AdbAuth.init()` вызывается ровно один раз и только чтобы получить строку `ADB_PUBKEY`
  (`AdbAuth.kt:72-73`; вызов — `AdbClient.kt:309`) — она полезна в отчёте и стоит 0 сетевых действий;
- `adbKeysSelfRegister()` **удалён целиком** (в 1.0.50 дал `ADB_KEYS self-register=denied reason=EACCES|SELinux`,
  т.к. `ls /data/misc/adb/ → Permission denied`). В bootstrap вместо него одна строка-факт:
  `ADB_KEYS: n/a (ro.secure=0 — adbd не требует ключей)` (`AdbClient.kt:343`);
- ветка `AUTH` внутри `handshake()` **оставлена как есть** (`AdbClient.kt:588`, обработка `arg0=1/2/3`
  в `:616-633`) — она реактивная, на старте не срабатывает и ничему не мешает (мандат §2);
- из `ADBDIAG` убраны `ls -l /data/misc/adb/` и запись-проба; осталась одна строка
  `ADBDIAG wc adb_keys: n/a` либо `wc -l` **если файл вообще читается** (`AdbClient.kt:569-576`).
  Записей в `adb_keys` в коде нет ни одной (grep: `grep -rn 'adb_keys' app/src/main` → только комментарии,
  `File(...).canRead()` и `wc -l`).

---

## 2. Чек-лист итерации

| # | Пункт | Статус | Файл:строка / доказательство |
|---|---|---|---|
| P0-A0 | `ADB_SELF: uid=… pid=… scontext=…` **первой** строкой после старта `AdbWorker` | ✅ (код) / 🟡 (нет прогона на ГУ) | `AdbClient.kt:366-374` (`logSelfContext`), вызов — первым действием `bootstrap()` `:300`; чтение `/proc/self/attr/current` с фоллбэком `cat /proc/self/attr/current` `:370-372` |
| P0-A | `localTargets()`: не-loopback IPv4 (Wi-Fi) → `127.0.0.1` → IPv6 → `::1` | ✅ | `AdbClient.kt:380-407`, порядок и `distinct()` — `:401-406` |
| P0-A | Проба по списку: `connect` 1500 мс → CNXN вариант A → чтение 24 Б окном 3 с | ✅ | `probeDestination()` `:418-473`: `connect(…, PROBE_CONNECT_MS)` `:427`, `writeMsg(os, CNXN, A_VERSION, A_MAXDATA, …)` `:444`, окно `s.soTimeout = RAW_READ_MS` `:441` |
| P0-A | Строка на каждый адрес `ADB_DEST addr=<ip> connect=<ms|E…> rx=<n> cmd=<CNXN|AUTH|silent> dt=<мс>` | ✅ | `:433` (connect-ошибка), `:459` (silent/short), `:464` (24 Б → `cmd=… hex=…`, при `AUTH` добавляется `auth=required`), `:468` (прочее) |
| P0-A | Итог `ADB_DEST result=<ip|none> working=<ip>` | ✅ | `discoverDestination()` `:476-494`: `:484` (CNXN), `:490` (AUTH), `ADB_DEST result=none working=none` `:334` |
| P0-A | При успехе — `volatile workingHost`, все команды через него | ✅ | поле `:107` (`@Volatile private var workingHost`), установка `:318`, использование в единственной точке соединения `execAdbSocket` `:1121`, `:1128` (`s.connect(InetSocketAddress(host, port), …)`) — через неё идут `execute`, `executeSmart`, `runOnChannel`, `fullProbe`, `adbFixVerify` |
| P0-A | `ADB_CHANNEL host=<ip> port=5555` при каждом канале | ✅ | `:1123-1127`: смена адреса — уровень `i`, дальше — `d` (чтобы не залить файл лога) |
| P0-B | `AdbAuth.init()` не в «старте ради AUTH»; `ADB_PUBKEY` оставлен | ✅ (осознанное уточнение) | `AdbClient.kt:309`; `AdbAuth.kt:72-73`. Отличие от буквы мандата: `init()` всё же вызывается — иначе строки `ADB_PUBKEY` не будет (она формируется внутри `init`). Ни подписи токенов, ни отправки `AUTH`, ни `adb_keys` на старте нет |
| P0-B | `adbKeysSelfRegister()` убран из bootstrap, одна строка-факт | ✅ | функция удалена (была `:594-630` в 1.0.50), флаг `keysChecked` удалён; строка-факт `:343` |
| P0-B | Порядок bootstrap: `ADB_DEST` первым, `ADBDIAG` — только если ни один адрес не ответил | ✅ | `bootstrap()` `:299-360`: `ADB_SELF` `:300` → `AdbAuth` `:309` → `ADB_DEST targets=` `:312` → `discoverDestination` `:315` → успех = `channelUp`+`adbFixVerify`+`replayDeferred` `:321-326`; иначе `logUnixSocketFacts`/`ADB_KEYS`/`ADBDIAG` `:332-350` |
| P0-C | Ответ `AUTH` → `auth=required` | ✅ | `:464` (там же `auth=required`), `:490` |
| P0-C | Все адреса `silent` → пометка «без сессии ПК» | ✅ | `ADB_DEST note=probe-without-pc-session` `:335` |
| P0-D | `ADBDIAG` сжат: только `/proc/net/tcp6` (`:15B3`), 5 `getprop`, `wc -l adb_keys`; ≤3 с | ✅ | `adbDiag()` `:552-579` — tcp6 `:555-561`, свойства `:563-567`, ключи `:569-576`; длительность = 1×3 с (awk) + 5×getprop(≈20 мс) — бюджет укладывается |
| P0-D | Убраны `measureAcceptQueue`, `smartSocketProbe`, `5556/65011`, `ps adbd`, `/proc/net/unix`, raw-чтения | ✅ | функции удалены (см. §5 «что удалено»), вызовов не осталось (grep пуст) |
| P0-D | `ADB_TOGGLE` не в bootstrap | ✅ | `:345` — только комментарий; `adbToggleFix()` осталась приватной и **нигде не вызывается** (`grep -rn 'adbToggleFix('` → только объявление `:855`) |
| Запреты | `mt_gpio`, `65010`, `tcpip:5555`, `setprop *.adb.tcp.port`, `ctl.restart adbd`, запись в `adb_keys` | ✅ | `mt_gpio` — 0 в коде (2 вхождения: комментарий в `App.kt:21` и строка лога `App.kt:23`); `65010` — 0; `tcpip:5555` — 0; `setprop *.adb.tcp.port` — 0 (только комментарий `AdbClient.kt:35`); `ctl.restart adbd` — только комментарии `AdbClient.kt:35,285` (в `AppPerformance.kt` `setprop ctl.restart zygote` — чужая функциональность, ADB не касается); `android_usb` — 0 |
| Регресс | панель уведомлений, `0x2140a6ed`, `0x2140A377`, TTL, `WindowDoors`, `HvacDigitalPlugin`, `ToolsPanel`, `UiBridge` | ✅ не тронуты | в diff только `AdbClient.kt` + `app/build.gradle.kts` (`git diff --stat 4cdf30a HEAD` → 2 файла) |
| — | Единственное касание UI-кода | ⚠️ к сведению | `channelLabel()` `:1267` теперь показывает реальный адрес: `"TCP $workingHost:$ADB_PORT_DEFAULT"` вместо жёсткого `127.0.0.1` — иначе при работе через Wi-Fi подпись врала бы. Правка ровно в той строке, которая уже менялась в iter27 (D2), новых элементов UI нет |

---

## 3. Что именно изменено в коде (карта 1.0.51)

`app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt` (1307 строк; было 1405):

- `:101-108` — `@Volatile workingHost` + `channelLoggedHost` (канал больше не константа);
- `:299-360` — новый `bootstrap()`: `ADB_SELF` → `ADB_PUBKEY` → `ADB_DEST targets` → проба → успех/диагноз;
- `:364-374` — `logSelfContext()` (`ADB_SELF`);
- `:380-407` — `localTargets()`;
- `:408-415` — `data class Dest`;
- `:418-473` — `probeDestination()` (`ADB_DEST addr=…`);
- `:476-494` — `discoverDestination()` (`ADB_DEST result=…`);
- `:552-579` — сжатый `adbDiag()`;
- `:588-638` — `handshake()` **не менялся** (единственная точка CNXN/AUTH, пишет `ADB_TX`/`ADB_RX`);
- `:657-665` — `logUnixSocketFacts()` (одна строка `exists/canRead`, канал по unix не строится);
- `:667-676` — `replayDeferred()` (после успеха: TTL restore → HVAC restore → `syncAll`);
- `:1113-1159` — `execAdbSocket()`: подключение к `workingHost`, `ADB_CHANNEL host=… port=…`.

`app/build.gradle.kts:60-61` — `51` / `"1.0.51"`.

**Формат пакета не менялся** (важно для разбора): CNXN варианта A остался ровно тем, что доказал себя
корректным в 1.0.50 —

```
43 53 59 4e 00 00 00 01 00 00 04 00 09 01 00 00 15 66 00 00 bc ac a6 b1
cmd=CNXN arg0=0x01000000 arg1=262144 len=265
```

Второй вариант (`arg0=0x01000001` + `host::central-geely`) из старта **убран**: в 1.0.50 он тоже дал
`silent`, держать его в прогоне нет смысла (в `handshake()` параметр `variant` сохранён, значение по умолчанию 0).

---

## 4. Таблица «строка лога 1.0.51 → значение → что делаем в 1.0.52»

| Строка | Значение | Далее |
|---|---|---|
| `ADB_DEST addr=172.20.10.x connect=… rx=24 cmd=CNXN` + `ADB_SELFTEST: port=5555 ok uid=0` | adbd пускает только по внешнему адресу, loopback режется (H2) | рабочий адрес уже закреплён в `workingHost`; канал готов, ничего не меняем — фиксируем в отчёте, что лечение найдено |
| `ADB_DEST addr=127.0.0.1 … cmd=CNXN` | дело было не в адресе | сверяем с `ADB_SELF scontext=…` (H1) и с отсутствием сессии ПК |
| все адреса `cmd=silent` + `ADB_SELF scontext=u:r:system_app:s0` | работает фильтр по контексту пира (H1) | в 1.0.52 ищем путь «доверенного» контекста (priv-app/system_server-мост, `adbd` через `su`-контекст); ключи/AUTH не трогаем |
| все адреса `silent`, `ADB_SELF scontext=(unreadable)` | данных о H1 нет | проверяем гипотезу «один транспорт»: повтор без ПК (пометка `note=probe-without-pc-session` уже в логе) |
| `ADB_DEST addr=… connect=EACCES` (или `ECONNREFUSED`) | SELinux/сеть не пускает на этот интерфейс | идём по списку дальше — это делает сам `discoverDestination` |
| `ADB_DEST addr=… rx=24 cmd=AUTH` / `auth=required` | adbd потребовал авторизацию (при `ro.secure=0` не ожидается) | тогда (и только тогда) возвращаемся к ключам — строка `ADB_PUBKEY` в логе уже есть, и владельцу нужна одна команда на ПК |

---

## 5. Что удалено (и почему это не потеря)

| Удалено | Почему |
|---|---|
| `adbKeysSelfRegister()` (~37 строк) | 1.0.50: `ls /data/misc/adb/ → Permission denied`, `self-register=denied reason=EACCES|SELinux`; ключи не нужны при `ro.secure=0` |
| `handshakeExperiment()` + `variantName()` | второй вариант CNXN доказанно молчит (лог 1.0.50, `attempt=2 cmd=silent`); проба теперь одна — зато по всем адресам |
| `measureAcceptQueue()` | ответ получен: `recvQ=0 … state=ESTABLISHED` (лог 1.0.50) |
| `smartSocketProbe()` (5037 `host:version`/`host:devices`) | 1.0.50: `status=FAIL len=0` — на ГУ adb-сервера нет |
| `rawReadProbe()` / `rawReadOnce()` | заменены на `probeDestination()` (то же самое, но с адресом и с `ADB_DEST`) |
| контрольные порты `5556/65011`, `ps adbd`, `/proc/net/unix`, `ss`, `netstat` в `ADBDIAG` | все дали `ECONNREFUSED`/`(empty)`/`rc=1` в 1.0.47–1.0.50; диагноз сжат до ≤3 с |
| константа `ADB_SERVER_PORT = 5037` | использовалась только удалённым `smartSocketProbe()` |

Осталось как есть (мандат §2): ветка `AUTH` в `handshake()`, `hasRoot()`, `channelLabel()`, весь unix-путь
(`execAdbLocalSocket`, `:800`) — он **недостижим** (`ROOT_UNIX` больше не выставляется, bootstrap его не зовёт),
помечен как мёртвый ещё в iter28. Это не лечение, а исторический код; в 1.0.52 его можно вырезать.

---

## 6. Для владельца: что делать другим пользователям

Если канал поднимется по Wi-Fi-адресу — **ничего делать не нужно**. Адрес определяется автоматически
(`localTargets()` + `ADB_DEST`), закрепляется в `workingHost`, ключи не нужны (`ro.secure=0`), никаких
`adb_keys`, `setprop`, `tcpip:5555` и рестартов adbd в сборке нет. Приложение само находит рабочий адрес
при каждом старте и дальше ходит только через него.

---

## 7. Проверка (владельцу, по шагам, без перезагрузки между шагами)

1. **Базовая линия с ПК ДО установки** (нужна, чтобы `ADB_DEST` было с чем сравнивать):
   ```
   adb connect 172.20.10.2:5555
   adb -s 172.20.10.2:5555 shell id; adb -s 172.20.10.2:5555 shell "cat /proc/self/attr/current"
   adb -s 172.20.10.2:5555 shell "ls -lZ /system/bin/adbd /sbin/adbd"   # §7 мандата
   adb -s 172.20.10.2:5555 shell "cat /proc/net/tcp6 | grep -i 15B3"
   adb -s 172.20.10.2:5555 shell ip -4 addr show     # какой у ГУ Wi-Fi-адрес
   adb disconnect 172.20.10.2:5555                   # ОБЯЗАТЕЛЬНО: проба должна идти без сессии ПК
   ```
   Записать: адрес ГУ, `uid=`, `context=`, строку `:15B3` (LISTEN/ESTABLISHED, rx_queue).
2. Установить `app-iter29-v1.0.51.apk` через AppControl (или `adb install -r`), **не перезагружая ГУ**.
3. Запустить приложение, подождать **40 секунд** (проба по 3 адресам: до ~3 с на молчащий адрес).
4. Снять лог приложения (путь прежний, файл `voiceapp3*.log`) и посмотреть ключевые строки:
   ```
   adb shell "logcat -d -b all" | grep -iE 'ADB_SELF|ADB_DEST|ADB_CHANNEL|ADB_PROBE|ADB_SELFTEST|ADBDIAG|adbd|auth|transport'
   ```
   Ожидаемый минимум в логе: `ADB_SELF: uid=… scontext=…`, `ADB_DEST targets=…`, по строке `ADB_DEST addr=…`
   на каждый адрес, итог `ADB_DEST result=…`.
5. `adb pull` файла лога приложения и прислать его целиком (в нём `ADBDIAG`, `ADB_PUBKEY`, `ADB_KEYS`);
   число строк `adb_keys` на этой ГУ не важно — ключей нет и они не нужны.
6. Если понадобится: `adb -s 172.20.10.2:5555 pull /system/bin/adbd` (§7 мандата) — это независимая
   проверка H1 по строкам внутри бинаря; мой код от неё не зависит и ей не противоречит.

**Чего в сборке нет** (проверено grep, см. §2 «Запреты»): записей в `/sys/**` и `/sys/class/android_usb/**`,
`mt_gpio`, `65010`, `tcpip:5555`, `setprop *.adb.tcp.port`, `ctl.restart adbd`, записей в `adb_keys`.

---

## 8. Честные ограничения

- Всё, что помечено ✅ в §2, — это **код, скомпилированный в CI** (run `41774001800`), но поведение на ГУ
  в 1.0.51 ещё **не проверено**: прогона на устройстве не было. Логических подтверждений «канал поднялся»
  в этой итерации нет и быть не может — их даст только ваш лог.
- Основная развилка (H1 или H2) остаётся открытой; 1.0.51 сделана так, чтобы **одним** прогоном получить
  ответ по обеим гипотезам и не потерять ни одной строки-улики.
- `ADB_DEST` при трёх молчащих адресах добавляет к старту до ~9–10 с (3 × 3 с). Это осознанная плата за
  проверку адресов; после первой успешной пробы адрес запоминается и все команды идут по нему без задержек.
- `adbToggleFix()` остаётся в коде приватной и невызываемой (для ручного эксперимента владельца);
  из bootstrap она убрана ещё в 1.0.50 и не возвращалась.
