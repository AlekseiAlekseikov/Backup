# REPORT iter30 — правка 1.0.51 (versionCode 51, номер версии НЕ менялся)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `14dc6a8` (основная часть iter30) → `2147389` (уточнение строки `ADB_DEST targets`) → отчёт этим коммитом
**Версия:** `app/build.gradle.kts` **не менялся** — `versionCode = 51`, `versionName = "1.0.51"`
(в diff итерации только один файл: `git show --stat 14dc6a8` → `tools/AdbClient.kt`, +406/−36)
**CI:** workflow `401712847`, run **41793783872** — **success**
**Artifact:** id **13305252463** → APK `/home/user/app-iter30-v1.0.51.apk`
(215 308 089 B, sha256 `d29d8c9b02546c6a0db135a061a6cd22423c558055a1d2a6c2e52fa9570aead7`, в манифесте `1.0.51`)
**Сбой по пути (не наш код):** run `41792071025` — `failure` с аннотацией раннера
`System.IO.IOException: No space left on device … Worker_20261104-072615-utc.log` (кончилось место на
раннере GitHub, шаг 11 `Gradle build (debug)`). Тот же коммит `2147389` перезапущен → `41793783872` success.

---

## 0. Что меняет разбор бинаря `adbd` в постановке задачи

В мандате §0.1 приведён разбор `/system/bin/adbd` (md5 `f3c40d8e0556aefeaab1b2abddef172c`):
`adbd_main` (`0x404fc0`) безусловно делает `strb wzr, [auth_required]` ⇒ `auth_required = 0`;
в бинаре **нет** `getpeersec`/`getpeercon` и строк `u:r:<domain>:s0`.

**Что из этого следует для кода (и что я честно фиксирую как расхождение внутри мандата):**

- §0.1 и §2а мандата противоречат друг другу. §0.1 (по бинарю): фильтра по контексту пира **нет**,
  и adbd обязан ответить `CNXN` на любой локальный `CNXN`. §2а: «adbd пускает локальных пиров только
  из доверенных доменов». Взять обе версии одновременно нельзя.
- Код сделан так, чтобы **не выбирать за владельца**: `ADB_SELF` (контекст и uid) пишется всегда, как
  требует §2а, но H1 **не считается доказанной**. Если в логе будет `ADB_SELF scontext=u:r:system_app:s0`
  и одновременно `ADB_ROW rx_queue=0` при `ADB_LISTEN` с живым листенером — это будет ровно та пара строк,
  которую §2а просит цитировать. Если же окажется `rx_queue>0` — версия «контекст» отпадает целиком,
  и работает §0.1: байты до adbd не доходят.
- Файл `/home/user/АНАЛИЗ_adbd_бинарник.md` в моём рабочем окружении **отсутствует** (в загрузках его нет),
  поэтому md5 `f3c40d8e…`, адрес `0x404fc0` и отсутствие `getpeersec` я цитирую по мандату, а не проверял сам.
  Проверка остаётся за владельцем: `adb pull /system/bin/adbd` + `md5sum` — если md5 не `f3c40d8e…`,
  разбор надо повторять, и выводы §0.1 к этой машине не относятся.

**Отдельно, важное для чтения лога:** в 1.0.43 один раз канал работал с пакетом
`CNXN arg1=4096` + баннер `host::central-geely` (мандат §0.2). В 1.0.51 проба идёт **другим** пакетом
(вариант A: `arg1=262144` + баннер с `features=`) — это предписано мандатом §1 («вариант A из 1.0.50»),
поэтому формат я не менял. Проба «старыми» байтами 1.0.43 в этой сборке **не делается** (это изменение
формата); если 1.0.51 не даст ответа, предлагаю в 1.0.52 добавить её как отдельный, третий вид попытки.

---

## 1. Чек-лист (✅ — только с доказательством `файл:строка`)

| # | Пункт мандата | Статус | Доказательство |
|---|---|---|---|
| P0-A | Порядок адресов: Wi-Fi IPv4 → `127.0.0.1` → `::1` → остальные | ✅ | `tools/AdbClient.kt:418-448` (`localTargets`), порядок — `:441-444`: `out.addAll(v4)` → `out.add("127.0.0.1")` (`:442`) → `out.add("::1")` (`:443`) → `out.addAll(v6)`; `.distinct()` `:445` |
| P0-A | Строка на адрес: `ADB_DEST addr=… connect=… rx=… cmd=… dt=…` | ✅ | `:479` (connect-ошибка), `:511` (silent/short), `:516` (24 Б → `cmd=… hex=…`, при `AUTH` + ` auth=required`), `:520` (прочее). Добавлено поле `attempt=<n>` и `err=EOF` — иначе 3 попытки в логе неразличимы |
| P0-A | Итог `ADB_DEST result=<ip|none> working=<ip>` | ✅ | `:627` (успех), `:645` (AUTH), `:357` (`result=none working=none`) |
| P0-A | Рабочий адрес запоминается и используется всеми каналами | ✅ | поле `@Volatile workingHost` `:120`, установка `:336`, единственная точка соединения `execAdbSocket` → `s.connect(InetSocketAddress(host, port), …)` `:1499`; `ADB_CHANNEL host=… port=5555` `:1492-1497` |
| P0-B | `ADB_LISTEN inode/uid/st/table` по `:15B3` | ✅ | `:659-672`, строка `:668`; перебор `/proc/net/tcp6` + `/proc/net/tcp` `:663`, листенер берётся по `st=0A` `:669` |
| P0-B | `ADB_OWNER` — поиск `socket:[inode]` по `/proc/<pid>/fd` | ✅ | `:674-702`: `android.system.Os.readlink` `:690`, `ADB_OWNER pid=… cmd=… fd=…` `:692`, `ADB_OWNER err=denied|not_found` `:700` |
| P0-B | `ADB_PS` (`ps -A`, фильтр adb/proxy/bridge/tunnel, ≤10 строк) | ✅ | `:704-708` |
| P0-B | `ADB_SS` (`ss -ltnp` и `netstat -ltnp`, строки `5555|15B3`, ≤5) | ✅ | `:710-718`, `has_adbd=` в `:717` |
| P0-B | Вызов только после первой неудачной пробы | ✅ | `:362` — внутри ветки `winner == null`, до `ADBDIAG` |
| P0-B2 | `ADB_SELF scontext=… uid=… pid=…` до первой пробы | ✅ | `:403-412`, строка `:411`; вызов первым действием `bootstrap()` `:319`; чтение `/proc/self/attr/current` с фоллбэком `cat …` `:406-409` |
| P0-C | Три снимка `ADB_ROW` на 0/500/2000 мс по `s.getLocalPort()` | ✅ | окна `:74-75` (500 + 1500 мс), снимки `:497`, `:500`, `:506`; сама строка — `:601`: `ADB_ROW t=…ms lport=… st=… rx_queue=… tx_queue=… found=… table=…`; поиск по `rem_address` `:581-598` (`qPair` `:564`); беспортовое чтение файла с фоллбэком `cat` — `:547-552` |
| P0-D | `logcat -d -b all -s adbd` (tail 20) + grep, ≤10 строк, `ADB_LOGCAT …` | ✅ | `:726-742`; искомые ключи (`cannot bind`, `Address already in use`, `new connection on fd`, `send_auth_request`, `failed to write apacket`) — `:737-738`; `ADB_LOGCAT src=… err=denied` `:734` |
| P0-E | `EXEC_PROP key=diag sent=<уникальное> got=<…> match=<…> rc=… out=… err=…` | ✅ | `:756-763`; уникальное значение `System.currentTimeMillis()` `:758`; **stderr отдельно** — `runShellCapture` `:802-820` |
| P0-E | То же для `batt`/`temp`/`ext_temp` + `EXEC_PROP keys=ok=<n>/3` | ✅ с оговоркой | `:766-783`; `keys=ok=…/3` `:783`. **Оговорка:** мандат §5 предлагает писать в них `42.4`. Эти три свойства — не флаги, а значения, которые плагин SystemUI показывает в статус-баре (`StatusIcons.kt:724-760` пишет «98.1», «22.0», «9.7»), и они в зоне регресса. Поэтому записывается **round-trip: текущее значение читается и пишется обратно тем же текстом** — право записи доказывается, индикация не меняется. Если значение ещё не публиковалось (`getprop` пуст) — строка `match=n/a reason=prop_empty` |
| P0-E | Включение плагина по ветке `via=app` при `match=true` | ✅ с оговоркой | `:787-798`. Ставится только если `persist.sys.hvacdigital.via` пуст или уже `app`; при `install`/`bind` — `skipped reason=already_set via=…`, потому что этим состоянием управляет `HvacDigitalPlugin`, который мандат §7 запрещает трогать |
| P0-F | 3 попытки на адрес, пауза 1 с | ✅ | `DEST_ATTEMPTS = 3` `:71`, `DEST_RETRY_PAUSE_MS = 1000L` `:72`, цикл `:608-641`, пауза `:633`; общий бюджет `32 с` `:73`, `:614` — иначе старт «повисает» на 4 IPv6-адресах |
| P0-F | Один отложенный ретрай через 60 с: `ADB_RETRY t=60s result=<…>` | ✅ | `:832-859`; поток `AdbDeferredRetry` `:857-858`; строки `:837` (`skip(channel_up)`), `:841` (`start`), `:844` (`silent`), `:854` (`up`), `:856` (иное) |
| P0-F | Итог `ADB_VERDICT channel=<up|down> listener_owner=<adbd|foreign|unknown> rx_queue_last=<n> propwrite=<ok|denied>` | ✅ | `:826-829`; вызовы `:352` (успех) и `:392` (провал); поля `lastRxQueue`/`listenerOwner`/`propWriteOk` — `:123-125` |
| §7 | Запреты | ✅ | `mt_gpio` в коде 0 (только комментарий и строка лога `App.kt:21,23`); `65010` 0; `tcpip:5555` 0; `setprop *.adb.tcp.port` 0 (только комментарий `AdbClient.kt:35`); `ctl.restart adbd` — только комментарии `:35`, `:302`; записей в `adb_keys` 0; `android_usb` 0; `ADB_TOGGLE` в bootstrap нет (вызовов `adbToggleFix(` — 0); новых `settings put` нет — в diff только `AdbClient.kt` |
| §7 | Панель уведомлений, `0x2140A377`, TTL, `WindowDoors`, `HvacDigitalPlugin`, `ToolsPanel`, `UiBridge` | ✅ не тронуты | `git show --stat 14dc6a8 2147389` → только `app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt` |

**Чего в этой сборке нет (честно):**
- Прогона на ГУ не было — все ✅ выше означают «код есть и скомпилирован в CI», а не «на машине сработало».
- H1/H2 не разрешены: их разрешит только лог 1.0.51.
- Проба «старыми байтами 1.0.43» не сделана (см. §0).
- Проверка «соединение из `su`/`shell`-контекста» приложением невозможна по определению — §2а сам это
  признаёт; в отчёте зафиксировано как задача владельца, не кода.

---

## 2. Что нового в логе 1.0.51 и как это читать

Ожидаемый порядок строк (сверху вниз):

```
ADB_SELF scontext=u:r:…:s0 uid=… pid=…
ADB_PUBKEY: … / ADB_PUBKEY_LEN=367
ADB_DEST targets=172.20.10.x,127.0.0.1,::1 port=5555 (connect 1500ms, окон чтения 500+1500ms, попыток на адрес 3, пауза 1000ms, CNXN arg0=0x01000000 arg1=262144)
ADB_TX(probe <ip>) cmd=CNXN … hex=<24 байта>
ADB_ROW t=0ms lport=… st=… rx_queue=… tx_queue=… found=…
ADB_ROW t=500ms …
ADB_ROW t=2000ms …
ADB_DEST attempt=1 addr=<ip> connect=1ms rx=0 cmd=silent dt=2003ms
ADB_DEST retry addr=<ip> next=2/3 in 1000ms (last cmd=silent)
…
ADB_DEST addr=<ip> attempts=3 last=silent connect=1ms
ADB_DEST result=none working=none
ADB_LISTEN inode=… uid=0 st=0A table=/proc/net/tcp6 local=…
ADB_OWNER pid=… cmd=… fd=… inode=…
ADB_PS rc=0 out=…
ADB_SS src=ss rc=0 has_adbd=false out=…
ADB_LISTEN verdict owner=foreign|adbd|unknown inode=…
ADBDIAG start iter29 …
ADB_LOGCAT src=tag_adbd hit=… keys=…
ADB_LOGCAT src=tag_adbd text=…
EXEC_PROP key=diag … match=…
EXEC_PROP key=batt … match=…
EXEC_PROP keys=ok=…/3 propwrite=ok|denied
EXEC_PROP via=app …
ADB_VERDICT channel=down listener_owner=… rx_queue_last=… propwrite=…
ADB_SELFTEST: failed targets=… 
ADB_RETRY t=60s result=…
```

### Таблица трактовки (мандат §9)

| Строка | Значение | Далее |
|---|---|---|
| `ADB_DEST addr=<Wi-Fi> … rx=24 cmd=CNXN` + `ADB_SELFTEST: port=5555 ok uid=0` | пускает только не-loopback адрес (H2) | канал есть, адрес зафиксирован в `ADB_CHANNEL`; протокол не трогаем |
| `ADB_SS` без `pid/adbd` при `init.svc.adbd=running` (`ADBDIAG prop init.svc.adbd val=running`) | порт держит не adbd | ищем виновника по `ADB_OWNER`/`ADB_LISTEN inode`; протокол не трогаем |
| `ADB_OWNER pid=<N> cmd=<имя>` | владелец порта найден | в отчёт с именем; если имя ≠ `adbd` — это и есть причина |
| `ADB_ROW rx_queue>0` | adbd (или посредник) не читает наши байты | транспорт не создаётся: смотреть `ADB_LOGCAT` (`cannot bind`/`Address already in use`) |
| `ADB_ROW rx_queue=0` + `cmd=silent` + `ADB_SELF scontext=u:r:system_app:s0` | байты прочитаны, ответа нет — ровно два факта §2а | это и есть цитата для версии «adbd молча роняет недоверенных»; проверка из `su`-контекста — за владельцем |
| `ADB_LOGCAT … keys=cannot bind` / `Address already in use` | adbd не смог занять 5555 | подтверждение «чужой на порту» |
| `EXEC_PROP match=true` / `keys=ok=1..3/3` | props пишутся без root | плагин можно включать напрямую (`via=app` уже выставляется, если `via` пуст) |
| `ADB_RETRY t=60s result=up` | канал поднялся сам через минуту | следующий шаг — не диагностика, а закрепление: почему он появился (USB-сессия ПК, Wi-Fi) |
| `ADB_DEST addr=… connect=EACCES/ECONNREFUSED` | интерфейс/порт недоступен | перебор продолжается автоматически |

---

## 3. Три возможных финальных вывода для владельца (по факту лога)

1. **«Канал поднят»** — `ADB_DEST addr=… rx=24 cmd=CNXN` + `ADB_SELFTEST: port=5555 ok uid=0 dt=…`.
   Дальше ничего делать не нужно: адрес подставляется автоматически, ключи не нужны (`ro.secure=0`).
2. **«Порт занят не adbd»** — `ADB_SS … has_adbd=false` при `ABDDIAG prop init.svc.adbd val=running`,
   плюс `ADB_LISTEN inode=…` и `ADB_OWNER pid=… cmd=…` (или `ADB_OWNER err=denied`).
3. **«adbd не читает новые соединения»** — `ADB_ROW t=… rx_queue=<n> st=01/ESTABLISHED`
   (n>0 — не читает; n=0 при `cmd=silent` — прочитал и молчит) + `ADB_LOGCAT` без `new connection on fd`.

---

## 4. Проверка (владельцу)

1. **До установки, с ПК** (это же и сравнение для `ADB_DEST`):
   ```
   adb connect 172.20.10.2:5555
   adb -s 172.20.10.2:5555 shell "id; cat /proc/self/attr/current; getprop ro.secure"
   adb -s 172.20.10.2:5555 shell "cat /proc/net/tcp6 | grep -i 15B3"
   adb -s 172.20.10.2:5555 shell "ps -A | grep -i adb"          # есть ли процесс adbd вообще
   adb -s 172.20.10.2:5555 shell "md5sum /system/bin/adbd"      # сверить с f3c40d8e0556aefeaab1b2abddef172c
   adb -s 172.20.10.2:5555 shell "ls -lZ /system/bin/adbd"
   adb disconnect 172.20.10.2:5555                              # ОБЯЗАТЕЛЬНО перед запуском приложения
   ```
2. Установить `app-iter30-v1.0.51.apk` через AppControl, **не перезагружая ГУ**.
3. Запустить, подождать **60–90 с** (проба: до 3 адресов × 3 попытки ≈ 30 с, затем диагноз, затем
   `ADB_RETRY t=60s`).
4. Снять лог приложения (файл `voiceapp3*.log`, путь прежний) и дополнительно:
   ```
   adb shell "logcat -d -b all | grep -iE 'ADB_SELF|ADB_DEST|ADB_ROW|ADB_LISTEN|ADB_OWNER|ADB_SS|ADB_LOGCAT|ADB_VERDICT|ADB_RETRY|ADB_SELFTEST'"
   ```
5. Прислать файл лога целиком (в нём `ADBDIAG`, `ADB_PUBKEY`, `EXEC_PROP`).

**Если канал поднимется по Wi-Fi-адресу** — для другого пользователя ничего делать не нужно: адрес
определяется сам (`localTargets`), ключи не требуются, записей в `adb_keys`/`setprop`/`tcpip` в сборке нет.

---

## 5. Стоимость и побочные эффекты (честно)

- Старт стал дольше: до ~30 с в худшем случае (3 адреса × 3 попытки). Если какой-то адрес ответит
  сразу, проба прекращается на нём и задержки нет. Ограничитель — бюджет 32 с (`DEST_BUDGET_MS`).
- Отложенный поток `AdbDeferredRetry` живёт 60 с и делает **одну** пробу на адрес (не три).
- `ADB_ROW` даёт до 9 строк на адрес — специально, чтобы видеть, читает ли adbd байты.
- `EXEC_PROP` не меняет значения на экране (round-trip), кроме собственного `persist.sys.hvacdigital.diag`.
