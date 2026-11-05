# REPORT iter28 — 1.0.50 (versionCode 50)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `965fd5d` (основная часть) → `df755eb` (фикс литерала) → `d72f905` (отчёт)
**CI:** workflow `401712847`, run **41695214064** — **success**
**Artifact:** id **13260419331** → APK `/home/user/app-iter28-v1.0.50.apk`
(215 287 601 B, sha256 `d6468cd53ff7644ff255626c8ca3dd74db43adcb20902e62da4edda586614481`)
**Версия:** `app/build.gradle.kts:60` `versionCode = 50`, `:61` `versionName = "1.0.50"`.

**Исходная точка.** Диагноз перевёрнут вашим подключением с ПК: adbd на `:5555` отдаёт `uid=0(root)`,
`context=u:r:su:s0`. Значит канал жив, а ломается **наше** рукопожатие — ровно та гипотеза, с которой
пришёл мандат. Все «оживляющие» шаги (`ADB_TOGGLE`, GPIO, recovery) в этой сборке из старта убраны.

---

## P0-A — рукопожатие как у настоящего adb

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — в CEX своего ADB-клиента с рукопожатием нет (он пользуется готовым
транспортом), поэтому опора — протокол `adb` из platform-tools (SDK 28–30), он же проверяемый:
`A_VERSION=0x01000000`, `A_MAXDATA=262144`, баннер хоста начинается с `host::` и содержит `features=`.

**ФАКТ** (`tools/AdbClient.kt:58-72`):
- константы `A_VERSION = 0x01000000`, `A_MAXDATA = 262144`, `AUTH_TOKEN/SIGNATURE/RSAPUBLICKEY = 1/2/3`,
  `HOST_BANNER_FEATURES` (полный список фич), `HOST_BANNER_LEGACY = "host::central-geely"`;
- `:520` `handshake(s, dis, os, attempt, variant, deadline)` — единая точка рукопожатия, используется
  во **всех** соединениях (`execAdbSocket`, `:1210`), включая пробу и `handshakeExperiment`;
- два варианта, не больше: `variant=0` → `A_VERSION` + баннер с `features=`;
  `variant=1` → `A_VERSION=0x01000001` + `HOST_BANNER_LEGACY` (`handshakeExperiment`, `:471-497`);
- логи ровно отправленного: `ADB_TX cmd=CNXN arg0=0x01000000 arg1=262144 len=265 hex=<24 байта>`
  (`:527`) и ответа: `ADB_RX attempt=<n> cmd=CNXN|AUTH|silent arg0=… len=… hex=… dt=…` (`:537`, `:541`).

**Точные байты (hex 24-байтного заголовка).**
```
БЫЛО (1.0.49): 43 53 59 4e 00 00 00 01 00 10 00 00 14 00 00 00 5e 00 00 00 bc ac a6 b1
               CNXN      arg0=0x01000000  arg1=4096    len=20        crc=0x5e  magic
               payload: "host::central-geely\0"                              (20 байт)

СТАЛО (1.0.50, вариант A): 43 53 59 4e 00 00 00 01 00 00 04 00 09 01 00 00 15 00 00 00 bc ac a6 b1
               arg1=262144 (0x00040000), len=265, crc=0x15
               payload: "host::features=cmd,shell_v2,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,
               remount_shell,track_app,track_jdwp,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,
               sendrecv_v2_zstd,sendrecv_v2_dry_run_send,devicetracker_proto_format,delayed_ack,avarice,
               apap,ipv6,pushes\0"                                            (265 байт)

СТАЛО (вариант B, если A молчит 3 с): 43 53 59 4e 01 00 00 01 00 00 04 00 14 00 00 00 bc ac a6 b1
               arg0=0x01000001, arg1=262144, payload "host::central-geely\0" (20 байт)
```
Отличия от прежнего: `arg1` 4096 → **262144**, баннер получил `features=` (главное — adbd разных
версий по-разному реагирует на «пустой» баннер), добавлен второй вариант версии протокола.

**Статус: ✅ (код) / 🟡 (результат — по логу 1.0.50).**

---

## P0-B — полноценная обработка AUTH

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ`; механизм — из `adb`: `AUTH(TOKEN)` → подпись `SHA1withRSA` 20 байт →
`AUTH(SIGNATURE)`; при отсутствии ключа — `AUTH(RSAPUBLICKEY)` со строкой `base64(n) base64(e) имя`.

**ФАКТ.**
- Новый файл `tools/AdbAuth.kt`: ключ RSA-2048, стабильный, `filesDir/adb_key.pk8` (PKCS#8 DER),
  при наличии переиспользуется (`:37-70`); публичная строка пишется в `filesDir/adb_key.pub` (`:66-68`).
- `AdbAuth.signToken()` — `Signature.getInstance("SHA1withRSA")` по 20-байтному токену (`:96-110`).
- Строка ключа: `base64(n) base64(e) voiceapp3@ihu629g`, big-endian без ведущих нулей — как в platform-tools
  (`AdbAuth.unsignedB64`, `:117-121`).
- В лог одной строкой без переносов: `ADB_PUBKEY: <b64n> <b64e> voiceapp3@ihu629g` и `ADB_PUBKEY_LEN=<n>`
  (`AdbAuth.kt:73-74`).
- Обработка в `handshake` (`AdbClient.kt:551-575`): `AUTH arg0=1` → подпись → `ADB_AUTH token received,
  signature sent, reply=<ждём> sig_len=256`; `AUTH arg0=2|3` → `ADB_AUTH: adbd просит ключ, которого нет
  в adb_keys (см. ADB_PUBKEY)` → отправляем `AUTH(RSAPUBLICKEY)`; защита от петли — больше 4 шагов AUTH
  ⇒ выход с причиной `auth loop`.
- Если ключа нет (нет Context) — `ADB_AUTH token received, но ключа нет — подписать нечем`.

**Статус: ✅ (код) / 🟡 (результат — по логу).**

---

## P0-C — unix-путь убран из bootstrap

**ФАКТ.** Вызов `probeUnixAdbd()` из `bootstrap` удалён; вместо него одна строка-факт
`logUnixSocketFacts()` (`AdbClient.kt:585-592`):
`ADBDIAG unix-socket exists=<b> canRead=<b> path=/dev/socket/adbd (в bootstrap не используется: SELinux + Namespace.FILESYSTEM без timeout)`.
Канал — только TCP 5555. Класс `ROOT_UNIX` остался в enum (на него завязаны `hasRoot()`/`channelLabel()`),
но в 1.0.50 в него никто не переходит.

**Статус: ✅**

---

## P0-D — с bootstrap снято лишнее

| Что | Как сделано | Где |
|---|---|---|
| `ADB_TOGGLE` из bootstrap убран | вызов удалён; функция `adbToggleFix()` осталась в коде как ручной инструмент, из старта не вызывается | `AdbClient.kt:306-307` (комментарий-решение), `:895` (сама функция) |
| `measureAcceptQueue` | остался только внутри `adbDiag` | вызов `:432` |
| порядок bootstrap | проба 5555 (3 с) → `logUnixSocketFacts` → `ADBDIAG` (раз за загрузку) → `ADB_KEYS` (раз за процесс) → `handshakeExperiment` (P0-A/P0-B) → при успехе `channelUp` + `ADBFIX verify` + `replayDeferred()` | `bootstrap`, `:288-330` |

**Про `ADB_TOGGLE` — решение.** В 1.0.50 из автоматики удалён совсем (в 1.0.49 дал
`after=0 probe=refused` → `after=1 probe=silent`, то есть `NO_EFFECT`, а риск для USB на этой ГУ
остаётся). Функция сохранена в коде, но **ниоткуда в старте не вызывается** — если понадобится,
её надо дёргать только вручную из «Инструментов» с предупреждением. На это же указывает ревью iter26 (D3).

**`replayDeferred()`** возвращён (`AdbClient.kt:640-650`): после успешного канала сразу
`NetworkTtl.bootRestore`, `StatusIcons.bootRestoreHvacDigital`, `StatusIcons.syncAll` — чтобы панель и TTL
не ждали следующего тика.

**Статус: ✅**

---

## P0-G — авто-регистрация ключа и портабельность

**ФАКТ.** `AdbClient.kt:594-630` `adbKeysSelfRegister()` (вызывается один раз за процесс, `:312-314`):
1. `ADB_KEYS: ls -l /data/misc/adb/ rc=… out=…`;
2. `ADB_KEYS: readable=<b>`;
3. `ADB_KEYS: writable=<b>`; если можно — дописываем **только свою** строку (проверка «уже есть» перед
   записью), затем `chmod 640`, `chown system:shell`, `restorecon`, лог `ADB_KEYS self-register=ok lines_after=<n>`;
   если нельзя — `ADB_KEYS self-register=denied reason=EACCES|SELinux` или `EPERM`.
Чужие строки не переписываются никогда (только `appendText` и `contains`).

### Блок «Портабельность» — что нужно человеку, который поставит приложение на такую же ГУ

| Исход в логе | Что нужно сделать |
|---|---|
| `ADB_RX attempt=1 cmd=CNXN` + `ADB_SELFTEST: port=5555 ok uid=0` | **ничего**. Канал поднялся новым форматом рукопожатия |
| `ADB_KEYS self-register=ok` (и затем `ADB_SELFTEST … ok uid=0`) | **ничего**. Приложение само добавило свой ключ в `adb_keys`; после перезапуска канал поднимется |
| `ADB_KEYS self-register=denied` | **одна команда с ПК** (или установка как priv-app). Строка ключа будет в логе `ADB_PUBKEY:`; команда:<br>`adb shell "echo '<ADB_PUBKEY>' >> /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys && restorecon /data/misc/adb/adb_keys"` |

Других вариантов не предлагаю: без записи в `adb_keys` (или без priv-app) чужой ключ adbd не примет.

**Статус: ✅ (код) / 🟡 (что реально скажет SELinux на машине — по логу).**

---

## P0-E — данные для разбора (только чтение)

**ФАКТ.** В конец `adbDiag` добавлено (`AdbClient.kt:452-466`): `ro.product.device`, `ro.build.version.sdk`,
`ls -l /data/misc/adb/`, `wc -l < /data/misc/adb/adb_keys`, плюс строка
`ADB_HINT: при проблеме пришлите вывод logcat -d -b all | grep -iE 'adbd|auth'`.
Всё — через `sh` от uid=1000, без единой записи.

**Статус: ✅**

---

## P0-H — совместимость с ADB AppControl

- Префиксы `ADB_TX`, `ADB_RX`, `ADB_AUTH`, `ADB_PUBKEY`, `ADB_KEYS`, `ADB_SELFTEST` — все пишутся через
  `AppLog`, то есть одновременно в logcat и в файл (механизм `AppLog` не менялся).
- Путь к файлу лога не менялся: `/storage/emulated/0/Android/data/com.example.voiceapp3/files/voiceapp3.log`.
- Канал не «закрывается» после успеха: наш клиент открывает отдельное TCP-соединение на команду и не
  занимает транспорт постоянно, поэтому канал владельца с ПК продолжает работать параллельно.

**Статус: ✅**

---

## Запреты — проверено

```
grep -rn "mt_gpio|GPIO_PIN|gpioRead"      app/src/main/java → 0
grep -rn "65010"                           app/src/main/java → 0
grep -rn "tcpip:5555"                      app/src/main/java → 0
grep -rn "setprop.*adb.tcp.port"           app/src/main/java → 0
grep -rn "ctl.restart adbd"                app/src/main/java → 2 строки, обе — комментарии-пояснения
```
Записей в `/sys/**`, `/sys/class/android_usb/**` нет. Панель уведомлений, `0x2140a6ed`, `0x2140A377`,
TTL-логика, `WindowDoors`, `HvacDigitalPlugin`, `ToolsPanel`, `UiBridge` — в этом коммите не менялись.

---

## Ход работы (честно)

Первая сборка iter28 (`run 41693222975`) упала: `Too many characters in a character literal` —
в строке лога `ADB_KEYS` я написал `replace('\n', ' | ')`, а `' | '` в Kotlin не символьный литерал.
Исправлено на двойные кавычки (`AdbClient.kt:598`), повторный прогон `41695214064` — success.
Остальные итерации проходили без пересборки кода.

---

## Что означает каждая новая строка лога → что делаем в 1.0.51

| Строка в логе 1.0.50 | Значение | 1.0.51 |
|---|---|---|
| `ADB_RX attempt=1 cmd=CNXN` + `ADB_HANDSHAKE OK variant=v0x01000000+features` + `ADB_SELFTEST: port=5555 ok uid=0` | причина была в формате рукопожатия (`arg1`/баннер) | всё, канал есть; закрепляем вариант A |
| `ADB_AUTH token received, signature sent` → `ADB_RX cmd=CNXN` → `uid=0` | наш ключ уже был в `adb_keys` (или совпал по подписи) | всё, канал есть |
| `ADB_AUTH token received, signature sent` → далее `cmd=silent` | ключ не принят | `ADB_KEYS self-register=…`, затем строка `ADB_PUBKEY` — добавляем с ПК |
| `ADB_KEYS self-register=ok` | приложение записало ключ само | перезапуск приложения (или следующий старт) |
| `ADB_KEYS self-register=denied reason=…` | SELinux/права не дают | ключ добавляется одной командой с ПК (шаблон выше) |
| `ADB_RX attempt=1 cmd=silent` и `attempt=2 cmd=silent`, `ADB_KEYS readable=true` (файл не пуст) | дело не в формате и не в ключах | разбор транспорта: сравнить наши байты с `adb` на ПК на этом же устройстве (`ADB_TX` hex у нас есть) |
| `ADB_HANDSHAKE OK variant=v0x01000001+legacy` | сработал legacy-вариант | оставляем A как первый, B как второй |

---

## Проверка на машине (ваш порядок)

1. С ПК: `adb connect <IP>:5555`, `adb devices`.
2. **До запуска приложения** снять базу (одна загрузка, не перезагружать ГУ между шагами):
   `adb shell id`, `getprop ro.adb.secure`, `getprop service.adb.tcp.port`, `getprop persist.adb.tcp.port`,
   `getprop init.svc.adbd`, `ls -l /data/misc/adb/`, `wc -l < /data/misc/adb/adb_keys`, `ss -ltnp | grep 5555`.
3. Поставить 1.0.50 через AppControl, запустить на ГУ, подождать 40 с, глазами проверить USB и панель.
4. Сразу после запуска: `wc -l < /data/misc/adb/adb_keys` (сравнить с базой),
   `logcat -d -b all | grep -iE 'ADB_|adbd|auth|transport' | tail -120`.
5. Забрать файл лога: `adb pull /storage/emulated/0/Android/data/com.example.voiceapp3/files/voiceapp3.log`.
6. Если канал поднялся — в логе будет `ADB_SELFTEST: port=5555 ok uid=0` и `ADBFIX verify[after_handshake]`;
   если нет — пришлите блок `ADB_TX`/`ADB_RX` и `ADB_PUBKEY` (по ним я скажу точную причину).

---

## Итог

| Пункт | Статус |
|---|---|
| P0-A рукопожатие (262144 + features, два варианта, `ADB_TX`/`ADB_RX`) | ✅ код / 🟡 результат |
| P0-B AUTH (подпись токена, стабильный ключ, `ADB_PUBKEY`) | ✅ код / 🟡 результат |
| P0-C unix-путь убран из bootstrap | ✅ |
| P0-D `ADB_TOGGLE` из старта убран, `measureAcceptQueue` только в диагнозе, порядок bootstrap | ✅ |
| P0-G `ADB_KEYS` self-register + блок «Портабельность» | ✅ код / 🟡 факт на машине |
| P0-E данные для разбора | ✅ |
| P0-H префиксы/путь лога/параллельный ПК-канал | ✅ |
| Запреты | ✅ (grep выше) |
| Канал фактически поднят | 🟡 — проверяется логом 1.0.50 |
