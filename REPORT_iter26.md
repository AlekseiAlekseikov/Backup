# REPORT iter26 — (A) recovery-пакет диагностики + (B) сборка 1.0.48

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `c561e17` (часть B + пакет A) → `6b52c00` (инструкция к пакету) → `094b8ec` (фикс компиляции) → `3bc8efe` (отчёт)
**CI:** workflow `401712847`, run **41667388778** — **success**
**Artifact:** id **13248025345** → APK `/home/user/app-iter26-v1.0.48.apk`
(215 267 897 B, sha256 `5ea979d87a218f8d760d64f3551598ed8fd3a1f3acaa64fc4429c98087e1d42e`)
**Версия:** `app/build.gradle.kts:60` `versionCode = 48`, `:61` `versionName = "1.0.48"`.

---

# Часть A — `ota/PERSISTENT_PROPS_READ.zip`

**Файл:** `ota/PERSISTENT_PROPS_READ.zip` (828 981 B, sha256 `55ce8d61997ffb9e7040c3b7857a7fdccf5387e7ec85c899aca2a9c826d743ba`),
инструкция владельцу — `ota/README_PERSISTENT_PROPS_READ.md`.

## A.0 — базовый пакет сверен по sha256 (все совпали, иначе не собирали бы)

| Файл | Ожидалось | Получено |
|---|---|---|
| `ota_base.zip` (Drive) | `1690b8321e2c6f968cc5aea8fe56146df68bae505aaee62c39e8af628547fdbd` | ✅ совпало (832 670 B) |
| `…/OS/update.zip` | `f55ae666a671aeb22665f5c149c6a3fe5b32b12b7a6c6fa850ea177a552f4cc4` | ✅ совпало (831 807 B) |
| `META-INF/com/google/android/update-binary` | `656512a91f688bd88ba9f17b076b0fe294947db7b0a767466103beeeb537bcf6` | ✅ (1 816 760 B) |
| `META-INF/com/google/android/updater-script` | `3069aecd3715cef6cf38281ad8924af9e78d95988cb71194ce211c2683f86128` | ✅ (3 445 B) |
| `META-INF/com/android/otacert` | `9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14` | ✅ (1 675 B) |
| `META-INF/com/android/metadata` | `e16ef005dab7a8544dfff4ddd1bc447a0d85957b6ec41a2b06cf81398f4c9805` | ✅ (282 B) |

`cmp -s META-INF/com/android/otacert platform-keys/platform.x509.pem` → **equal bytes: True**
(sha256 сертификата `9837de02…20d14`, sha256 ключа `platform.pk8` = `1ad8ef55…62875`).
Метаданные пакета владельца подтверждены чтением: `pre-device=IHU629G`,
`post-build=GEELY/full_IHU629G/IHU629G:9/PQ2A.190405.003/111:user/test-keys`, `ota-type=BLOCK`, `post-sdk-level=28`.

## A.1/A.2 — что внутри нового пакета

```
Archive: ota/PERSISTENT_PROPS_READ.zip    (unzip -l показывает: "signed by SignApk")
  Length    Name
  1827      META-INF/com/google/android/updater-script
  1816760   META-INF/com/google/android/update-binary
  1675      META-INF/com/android/otacert
  282       META-INF/com/android/metadata
```
- `update-binary` — **байт-в-байт** из пакета владельца: sha256 внутри нового пакета
  `656512a91f688bd88ba9f17b076b0fe294947db7b0a767466103beeeb537bcf6` = sha256 в пакете владельца. Не пересобирался.
- `otacert` и `metadata` — тоже байт-в-байт (`9837de02…`, `e16ef005…`).
- Изменён **только** `updater-script` (sha256 `180a475d8bbf6b5cb5c5ea5e06c009783a643dc1176c6e56a3d0aa0340824715`, 1 827 B).
- В скрипте: `ui_print`-шапка, `getprop("ro.product.device") == "IHU629G" || abort(...)`,
  `set_progress(0.10)`, `mount("ext4","EMMC","/dev/block/platform/bootdevice/by-name/userdata","/data")`,
  `is_mounted("/data") || abort(...)`, `set_progress(0.30)`, `run_program("/system/bin/sh","-c","…")`,
  `set_progress(1.00)`, `ui_print` результатов и `abort("DIAG DONE - read-only, intentional stop")`.
- **Опасных команд нет:** `grep -n "format(\|delete(\|write_raw_image"` → пусто. Слово «wipe» встречается
  ровно один раз — в тексте сообщения `ui_print("'Installation aborted' below is NORMAL. Nothing was wiped.")`,
  то есть это надпись для владельца, а не команда.
- `run_program` получает **ровно три аргумента**; внутри строки экранирование как в пакете владельца:
  `\\{4,6\\}` (двойной бэкслеш → shell получает `\{4,6\}`), кавычки `\"`. Fallback’и на месте:
  `(od -c $f || hexdump -C $f || busybox od -c $f)`.

## A.1 — подпись

`publish-ota.sh`/`release.sh` OTA-пакеты не подписывают (первый — только версию/APK, второй — APK;
там `apksigner --key/--cert`, не тот формат). `signapk.jar` в репозитории и в sandbox отсутствует,
поэтому подпись собрана **точно по формату `SignApk -w`** (исходник AOSP `tools/signapk/SignApk.java`
скачан для сверки: `signWholeFile()` / `WholeFileSignerOutputStream` / `writeSignatureBlock`).

Что сделано (и почему это не «свой формат», а копия эталонного):
1. zip собран с EOCD без комментария (22-байтный EOCD, comment length = 0);
2. SHA-1 считается по диапазону `data[0 : size-2-comment_len]` — то есть по всему файлу **кроме**
   2-байтного поля длины комментария и самого комментария. Диапазон проверен **эмпирически на пакете
   владельца**: его подпись `RSA verifyrecover` даёт ровно `DigestInfo(SHA-1)` от `data[0 : 830071]`
   (`size-2-comment_len`), остальные варианты (с текстом «signed by SignApk» и без) — **не** дают;
3. PKCS#7 `SignedData` (detached, без `signedAttrs`) — взят **готовый DER владельца** (1 710 B) и в нём
   заменены только 256 байт `encryptedDigest` на нашу подпись (ключ тот же — `platform.pk8`, сертификат
   тот же — `platform.x509.pem`, поэтому все ASN.1-поля остаются валидными);
4. хвост как у владельца: `[2B comment_len=1734]["signed by SignApk\0"][DER 1710][2B 1716][FF FF][2B 1734]`.

**Проверка подписи нового пакета** (независимая, `openssl pkeyutl -verifyrecover` по публичному ключу
из `platform.x509.pem`):
`SIG OK`, digest `58974227c54c36d29f1c44f28e5ef49a12b62f8b`, `comment_len=1734`, `sig_start=1716`,
в блоке подписи нет ложной последовательности `PK\x05\x06` (проверяется в сборщике).

**Чем ещё проверялся пакет:** `unzip -l` (видит «signed by SignApk» и 4 записи), `cmp` с `platform.x509.pem`,
совпадение sha256 трёх неизменённых файлов с пакетом владельца, `grep` по опасным командам,
самопроверка подписи. `check-fix.sh` и `check-centralex-tools.sh` **OTA не валидируют** — они про APK/CEX,
поэтому для пакета не использовались.

## Что НЕ сделано осознанно

- Пакет-лечение (перезапись `persistent_properties`) **не собирался** — по мандату A.4, только после разбора.
- Шаблон на будущее зафиксирован в мандате; добавлять его в репозиторий сейчас не стал, чтобы никто
  случайно не поставил.

**Статус A: ✅** (пакет собран, подпись проверена, три из четырёх файлов — байт-в-байт из пакета владельца)
/ 🟡 (реальный приём recovery проверится только на машине).

---

# Часть B — 1.0.48

## B.1 — `ADB_TOGGLE` (запись `Settings.Global.ADB_ENABLED`)

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — CEX adbd не перезапускает. Механизм штатный Android:
system_server наблюдает `Settings.Global.ADB_ENABLED` и вызывает `UsbDeviceManager.setAdbEnabled()`.
Наше приложение — `android.uid.system` (`app/src/main/AndroidManifest.xml:5`) с
`WRITE_SECURE_SETTINGS` (`:17`), поэтому запись разрешена.

**ФАКТ** (`tools/AdbClient.kt`):
- `:737` `adbToggleFix()`: `before` (`Settings.Global.getInt`, лог `ADB_TOGGLE before=`), затем
  `putInt(0)` → пауза 3 с → полная проба (лог `ADB_TOGGLE after=0 probe=`) → `putInt(1)` → пауза 5 с →
  полная проба (`after=1 probe=`), при `KIND_ROOT` — `channelUp(p)` и `result=OK`, иначе `NO_EFFECT`.
- `:766` — обязательная строка-предупреждение
  `ADB_TOGGLE: если после этого шага отвалится USB — виноват этот шаг, откатывать его первым`.
- **Восстановление исходного значения — в `finally`** (`:783-795`), то есть при любом исходе, включая
  исключение; лог `ADB_TOGGLE restore=<before> readback=<факт>`.
- Одна попытка за старт: флаг `toggleTried` (`:735`), повторный вызов пишет `ADB_TOGGLE skipped`.
- `:807` `adbFixVerify(when_)` — после успеха: `id`→`uid=0`, `settings get system screen_off_timeout; echo rc=$?`,
  `setprop persist.sys.hvacdigital.diag 48` + readback, и `iptables … >/dev/null 2>&1; echo rc=$?`
  (**`$?` берётся сразу после iptables**, не после `head`). Итог одной строкой `ADBFIX verify[...]=OK|PARTIAL`.

## B.2 — `probeUnixAdbd()`: канал без TCP

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` (CEX ходит только по 5555). Механизм стандартный: adbd слушает abstract
unix-сокет `adbd`, к нему подключаются через `android.net.LocalSocket`.

**ФАКТ.** `AdbClient.kt:640` `probeUnixAdbd()`: `LocalSocket.connect(LocalSocketAddress("adbd", ABSTRACT), 2000)`
→ `CNXN` (`0x4E595343`, `0x01000000`, 4096, `host::central-geely\0`) → `soTimeout = 3000` → `readMsg`.
Логи: при ошибке `ADBDIAG unix-socket connect=EACCES|ECONNREFUSED|EIO… rx=0 dt=…`, при ответе
`ADBDIAG unix-socket connect=ok rx=<n> cmd=CNXN|AUTH hex=<…> dt=…`. При `CNXN` — `OPEN shell:id` через
тот же сокет (`execAdbLocalSocket`, `:685`): если `uid=0`, канал считается найденным:
state → `ROOT_UNIX` (`:93`, новый член `ChannelState`), лог `ADB_SELFTEST: unix-socket ok uid=0 …`.
Дальше команды идут через unix-сокет (`runCommand`, `:849`), TCP не трогается.
Вызывается первым делом в `bootstrap()` (`:285`) — до любой диагностики.

## B.3 — замер `Recv-Q`

**ФАКТ.** `AdbClient.kt:482` `measureAcceptQueue()`: держит открытое соединение к 5555 (connect + CNXN),
ждёт 700 мс и читает `/proc/net/tcp6` **и** `/proc/net/tcp` по `:15B3`, разбирает поля
(`st`, `tx_queue:rx_queue`, inode) и пишет
`ADBDIAG accept: recvQ=<n> sendQ=<n> state=LISTEN|ESTABLISHED|… established=<b> inode=<…> table=<…>`.
Вызывается из `adbDiag` (`:424`).

## B.4 — стоимость диагностики срезана

| Что | Было (1.0.47) | Стало (1.0.48) | Где |
|---|---|---|---|
| окно сырого чтения | 8 000 мс | **3 000 мс** | `AdbClient.kt:50` `RAW_READ_MS` |
| соединений в multi-probe | 3 | **2** | `AdbClient.kt:410-414` |
| полный `ADBDIAG` | каждый старт | **один раз за загрузку** | `AdbClient.kt:810` `diagAllowedThisBoot()` + prefs-ключ `adbdiag_boot_id`, сверка с `/proc/sys/kernel/random/boot_id` |
| при повторном старте | — | только проба (3 с) | `bootstrap`, `:288-292` |

## B.5 — запреты соблюдены

```
grep -rn "mt_gpio|GPIO_PIN|gpioRead"   app/src/main/java → 0
grep -rn "65010"                        app/src/main/java → 0
grep -rn "tcpip:5555"                   app/src/main/java → 0
grep -rn "setprop.*adb.tcp.port"        app/src/main/java → 0
grep -rn "ctl.restart adbd"             app/src/main/java → 2 строки, обе — комментарии-пояснения
                                        (AdbClient.kt:33 «в коде этого нет», :264 «эталонный мёртвый ensurePortMigrated»)
```
Единственная новая запись в систему — `Settings.Global.ADB_ENABLED`, обратимая, восстанавливается в `finally`.
Панель уведомлений, `0x2140a6ed`, `0x2140A377`, TTL-логика, `WindowDoors`, `HvacDigitalPlugin`,
`ToolsPanel`, `UiBridge` — не изменялись (в этом коммите их нет вообще).

## Известные ограничения этой сборки (говорю прямо)

1. **`ROOT_UNIX` виден не всем потребителям.** Баннер в «Инструментах» (`ToolsPanel.kt`) и
   `HvacDigitalPlugin.haveRoot` проверяют только `ROOT_5555`. Мандат запретил менять эти файлы, поэтому при
   успехе unix-канала баннер останется красным, а «Вернуть панель SystemUI» скажет «канал недоступен».
   Функционально канал при этом работает. **Правка — первым делом в 1.0.49** (2 строки в двух файлах).
2. `ADBDIAG` теперь один раз за загрузку: если владелец захочет полный диагноз повторно, нужна перезагрузка
   ГУ (или сброс prefs).

---

## Ход работы (честно)

1. Первая сборка iter26 (`run 41665587128`) упала: `Unresolved reference 'set'` — я вызвал
   `.set(true)` у `Boolean` (по привычке от `AtomicBoolean`). Исправлено на `unixChannelOk = true`
   (`AdbClient.kt:661`), повторный прогон `41667388778` — success.
2. Два промежуточных прогона (`41665555304`, `41665556907`) получили `cancelled` — это штатное поведение
   workflow при двух пушах подряд (новый прогон отменяет предыдущий), не ошибка кода.

---

## Что означает каждая новая строка лога → что делаем в 1.0.49

| Строка в логе 1.0.48 | Значение | 1.0.49 |
|---|---|---|
| `ADB_SELFTEST: unix-socket ok uid=0 … ROOT_UNIX` | канал найден **без TCP** | переводим все root-команды на unix-сокет, TCP-пробу оставляем как fallback; правим баннер и `haveRoot` |
| `ADBDIAG unix-socket connect=EACCES rx=0` | system-uid не пускают к сокету adbd (SELinux) | ищем обход: `LocalSocket` из `su`-домена невозможен, значит остаётся только TCP-канал |
| `ADBDIAG unix-socket connect=ok rx=… cmd=AUTH` | сокет жив, но требует ключ | разбираем авторизацию (`ro.adb.secure=1`) |
| `ADB_TOGGLE result=OK` + `ADBFIX verify[after_toggle]=OK` | перезапуск adbd через настройку сработал | закрепляем `ADB_TOGGLE` как штатный шаг bootstrap |
| `ADB_TOGGLE result=OK`, но `verify=PARTIAL` | порт/шелл вернулись, а права неполные | разбираем, что именно не прошло (в строке перечислено) |
| `ADB_TOGGLE result=NO_EFFECT` | перезапуск не помог (adbd молчит по другой причине) | **ставим OTA-пакет**, разбираем `.raw` (пункт 3 порядка ниже) |
| `ADB_TOGGLE: если после этого шага отвалится USB …` **и USB реально отвалился** | виноват `ADB_ENABLED` на этой прошивке | откатывать первым: `Settings.Global.ADB_ENABLED` обратно, шаг удалить |
| `ADBDIAG accept: recvQ=0 … established=false` | adbd не забирает из accept-очереди (не вызывает `accept()`) | искать причину в состоянии adbd (PID/`init.svc.adbd`), пробовать `ADB_TOGGLE` сразу после старта |
| `ADBDIAG accept: recvQ>0 … established=true` | соединение принято и установлено, но не отвечает | значит проблема внутри adbd (транспорт/ключи), а не в очереди — копать в сторону авторизации |
| `ADBDIAG skipped: уже выполнялся в этой загрузке` | диагноз срезан по B.4 | норма |

---

## Порядок для владельца

1. **Ставим 1.0.48** (5 минут, откатываемо). Смотрим лог: есть ли `ADB_SELFTEST: unix-socket ok uid=0 …`
   (лучший исход), `ADB_TOGGLE result=OK` или хотя бы новые строки `ADBDIAG accept:`.
   Отдельно проверяем, что **USB живой** (флешка видна) — если нет, см. строку про `ADB_TOGGLE` в таблице.
2. Если канала нет — ставим **`ota/PERSISTENT_PROPS_READ.zip`** в recovery тем же пунктом меню, что и
   `3C6025_…/OS/update.zip`, забираем `voiceapp3_persist_diag.txt` и `persistent_properties.raw`,
   присылаем оба (инструкция — `ota/README_PERSISTENT_PROPS_READ.md`).
3. Лечение на основе `.raw` (перезапись `persistent_properties`, бэкап рядом) — отдельной итерацией,
   только после разбора файлов и вашего письменного согласия.

---

## Итог

| Пункт | Статус |
|---|---|
| A. Пакет `PERSISTENT_PROPS_READ.zip`, подпись + sha256 сверки | ✅ (проверено; приём recovery — 🟡) |
| B.1 `ADB_TOGGLE` + `ADBFIX verify` | ✅ (код; результат — 🟡) |
| B.2 `probeUnixAdbd` → `ROOT_UNIX` | ✅ (код; результат — 🟡) |
| B.3 `ADBDIAG accept: recvQ=…` | ✅ (код) |
| B.4 диagnostика срезана (3 с / 2 соединения / раз за загрузку) | ✅ |
| B.5 запреты | ✅ (grep выше) |
| `ROOT_UNIX` в UI и `haveRoot` | ❌ — мандат запретил трогать `ToolsPanel`/`HvacDigitalPlugin`, зафиксировано как задача 1.0.49 |
