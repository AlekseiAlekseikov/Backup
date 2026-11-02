# REPORT iter24 — 1.0.46 (versionCode 46)

**Ветка:** `fix/v2` (новых веток не создавалось, `main` не тронут, `--force` не использовался)
**Коммит:** `713e152` — «iter24: single 5555 root channel, ADBDIAG diagnosis, ADBFIX healing (settings/tcpip/gpio151), wifi icon fit, version 1.0.46»
**CI:** workflow `401712847`, run **41497873248** — **success** (первая же сборка).
**Artifact:** id **13168556358** (`app-debug`, 177 011 087 B) → APK `/home/user/app-iter24-v1.0.46.apk`
(215 258 901 B, sha256 `ae1cbdda4bbea16fadb6e71c0a7afa46d34bed956f226c408728a6cf6b7d5a02`).
**Версия:** `app/build.gradle.kts:60` `versionCode = 46`, `:61` `versionName = "1.0.46"`.

Эталон: `CentralEXAuto 3.3.9` (`/home/user/cex/cex.apk` → распакован в `cex_inner/`, dex → `cex_dex/classes.dex`,
smali → `/home/user/cex_smali/`, java → `/home/user/cex_out/sources/`).

> Правило отчёта то же, что и раньше: `✅` — только когда есть код с `файл:строка` **и** строка лога/референс,
> которой это подтверждается. Лог 1.0.46 с машины ещё не снят, поэтому подтверждением служит **предыдущий**
> лог владельца (`uploads/voiceapp3 (11).log`, версия 1.0.45) для факта поломки и референс CEX для механизма.
> Всё, что требует нового лога, помечено 🟡.

---

## P0.3 — один порт 5555, 65010 удалён полностью

**РЕФЕРЕНС.** ADB-клиент эталона создаётся только с портом 5555: `Lkotlinx/coroutines/scheduling/h;-><init>(I)`
инстанцируется с `const/16 0x15b3` в `Lw0/k1;->…` (`cex_smali/w0/k1.smali:880`), `Lw0/d0;->…`
(`cex_smali/w0/d0.smali:62`), `Lw0/g2;->…` (`cex_smali/w0/g2.smali:231`), `Lw0/u1;->…`
(`cex_smali/w0/u1.smali:121`); внутри — `connect("127.0.0.1", port)` (`cex_smali/kotlinx/coroutines/scheduling/h.smali:294`)
и баннер `host::central-geely`.

**`ЭТАЛОН: МЁРТВЫЙ КОД Lw0/m;->l()` (ensurePortMigrated).** Доказательства:
1. метод пишет `service.adb.tcp.port`/`persist.adb.tcp.port` и делает `( sleep 1; setprop ctl.restart adbd )`
   (`cex_out/sources/w0/m.java:481-491`, smali префикс `ADB_PORT_MIGRATED`);
2. его вызовы — **только** из `Lw0/b5;->run` (`cex_smali/w0/b5.smali:434` и `:523`), и обе ветки закрыты
   pref-флагом `adb_port_migrated` (по умолчанию `false`) и маркером `"IBANES"`;
3. ADB-клиент эталона в него не ходит вообще (см. п. «РЕФЕРЕНС»);
4. по шести логам владельца (1.0.38/40/41/43/44/45) порт 65010 не дал root ни разу; в 1.0.41 — 6× `ECONNREFUSED`.
⇒ не переносим, и в 1.0.46 второго порта нет ни в коде, ни в UI.

**ФАКТ.**
- `AdbClient.kt:44-52` — константы: только `ADB_PORT_DEFAULT = 5555`; добавлены `PROBE_CONNECT_MS = 1500`,
  `PROBE_TOTAL_MS = 3000`, `PROP_DIAG`, `GPIO_PATH`, `GPIO_PIN`. `ADB_PORT_ROOT` удалён.
- `AdbClient.kt:81-86` — `ChannelState` без `ROOT_65010` (`IDLE`, `BOOTING`, `ROOT_5555`, `NO_ROOT_SH`, `PERM_FAIL`).
- `AdbClient.kt:58-62` — `PORTS = intArrayOf(ADB_PORT_DEFAULT)`; **alt-port retry удалён**: на `IOException`
  канал закрывается (`state.compareAndSet(ROOT_5555 → NO_ROOT_SH)`), а не переключается на другой порт (`:676-682`).
- `AdbClient.kt:265-292` — `bootstrap()`: одна проба, без ретраев.
- `AdbClient.kt:310-327` — `fullProbe()`: `connect` 1500 мс, бюджет 3000 мс, `id; id -Z; getenforce`.
- Grep-доказательства (по `app/src/main/java/`):
  `grep -rn "65010"` → **0 совпадений**; `grep -rn "PORT65010"` → **0**; `grep -rn "ctl.restart adbd"` → только
  комментарий `AdbClient.kt:33`; `grep -rn "setprop.*adb.tcp.port"` → только комментарий `AdbClient.kt:33`.
  Записей в `persist.adb.tcp.port`/`service.adb.tcp.port` нет — только чтение через `getprop`
  (`AdbClient.kt:600-608`, `logAdbPortReadback`).
- UI-тексты: `ui/ToolsPanel.kt:831` теперь `val ok = st == "ROOT_5555"`; `ui/UiBridge.kt:638` — префикс
  «ADB channel»; `tools/NetworkTtl.kt:111` — «канал недоступен — 5555 не отвечает (uid=1000)»;
  `tools/AppLog.kt:27` — описание модуля без второго порта.

**ПРИЁМКА (лог).** `ADB_PROBE port=5555 kind=… dt=…ms` один раз на старт; ни одной строки с миграцией порта.
**Статус: ✅ (код+грейп)** — механизм удалён, подтверждено grep’ом; поведение на машине — 🟡 (ждём лог 1.0.46).

---

## P0.2 — тип ошибки больше не глотается

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` — в эталоне клиент бросает исключения вызывающему коду без классификации.

**ФАКТ.**
- `AdbClient.kt:331-340` — `classifyProbeError()`: `ConnectException → refused`, `SocketTimeoutException → silent`,
  `NoRouteToHostException → no_route`, «CNXN timeout» → `silent`, «command timeout» → `opened_no_reply`,
  иначе `error:<Class>`.
- `AdbClient.kt:326` — текст исключения уходит в `Probe.err` и печатается в файл:
  `ADB_PROBE port=5555 <Class>: <msg> (kind=…)` (`:779` — в `tryProbePort`), плюс `err=` в итоговых строках.
- Было (1.0.45): `Log.d(TAG, "probe port=…")` → в файл не попадало, оставалось безликое `no CNXN response (dt=8007ms)`
  (подтверждение: `uploads/voiceapp3 (11).log`, 3× `ADB_SELFTEST: port=5555 no answer (attempt N, dt≈8007ms)`).

**ПРИЁМКА.** В логе появится класс исключения рядом с `kind=`.
**Статус: ✅ (код)** / 🟡 на машине.

---

## P0.1 — `ADBDIAG`: однозначный диагноз

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` (у CEX нет диагностики — он просто работает по 5555).

**ФАКТ.** `AdbClient.kt:353-397` `adbDiag(failed)` вызывается из `bootstrap()` сразу после неудачной пробы
(`:274`). Все строки — через `AppLog` (в файл), префикс `ADBDIAG`:
1. **контроль** закрытых портов `5556` и `65011`, connect 1500 мс, с классом исключения и `dt` (`:356-367`);
2. **сырое чтение от 5555** — `AdbClient.kt:400-440` `rawReadProbe()`: connect 1500 → `writeMsg(CNXN, …)`
   → `soTimeout = 30 000` → чтение первых 24 байт; печатается `n`, `hex`, `cmd` (`CNXN`/`AUTH`/…), `connectDt`, `dt`,
   отдельные ветки для EOF, короткого ответа и таймаута;
3. **таблицы ядра**: `/proc/net/tcp` **и** `/proc/net/tcp6` (`:369-376`), поиск по hex `:15B3` (не по «5555»),
   плюс `ss -ltn` и `netstat -ltn` (`:377-380`);
4. **снимок свойств** — только чтение: `service.adb.tcp.port`, `persist.adb.tcp.port`, `init.svc.adbd`,
   `ro.adb.secure`, `ro.secure` (`:382-386`);
5. **проверка записи наших props из uid=1000 без adb**: `setprop persist.sys.hvacdigital.diag 46` + readback (`:388-392`).

**ПРИЁМКА (лог).** Строки `ADBDIAG ctrl port=5556 …`, `ADBDIAG raw5555 rx n=… hex=…`, `ADBDIAG /proc/net/tcp6 …`,
`ADBDIAG prop init.svc.adbd …`, `ADBDIAG setprop persist.sys.hvacdigital.diag: rc=… readback=…`.
**Статус: ✅ (код)** / 🟡 на машине.

**Что уже известно из лога 1.0.45 (для 1.0.47).** `init.svc.adbd=running`, `persist.adb.tcp.port=5555`,
`ro.adb.secure=1`, `/dev/socket/adbd` — `Permission denied` для uid=1000; все пробы `dt≈8007 мс`, то есть
**connect к 5555 проходил, а adbd молчал** (на закрытом loopback-порту RST приходит мгновенно — в 1.0.43 на
втором порту было `dt=16 ms`). Гипотеза для 1.0.47: соединение принимается ядром (listen-backlog), но adbd его
не обслуживает. `ADBDIAG raw5555` это либо подтвердит (`SocketTimeoutException`, n=0), либо опровергнет
(`hex=41 55 54 48` = AUTH).

---

## P0.4 — лечение канала в этой же сборке (и порт, и root)

**Критерий успеха — не «порт слушает», а `uid=0`:** `ADBFIX … probe=ROOT_5555` + `ADBFIX verify=OK`.
`UP_NONROOT` успехом не считается, после него `adb_enabled` откатывается (чтобы штатный adbd не занял 5555).

**ФАКТ.** `AdbClient.kt:453-484` `adbFix()` — бюджет 60 с, шаги строго по порядку, после каждого — полная проба
и строка `ADBFIX step=<n> name=<…> … probe=<…> dt=<мс>` (`fixStep`, `:486-496`) плюс `ADBFIX who=<uid/context>`.

| # | Шаг | Код | Референс / основание | Результат в логе |
|---|---|---|---|---|
| 1 | `settings put global adb_enabled 1` | `AdbClient.kt:489-495` | штатный тумблер отладки; прежнее значение сохраняется и откатывается (`restoreAdbEnabled`, `:536-542`) | `ADBFIX settings_rc=… prev=… after=…` |
| 2 | `printf 'tcpip:5555\0' > /dev/socket/adbd` | `AdbClient.kt:498-503` | то же, что `adb tcpip 5555`; в эталоне fallback `"— vou tentar pelo ADB local"` (`MainActivity.java:2885`) | `ADBFIX tcpip_rc=… ok=… out=…` |
| 3 | `out 151 1` → `/sys/devices/platform/1000b000.pinctrl/mt_gpio` | `AdbClient.kt:506-528` | **референс CEX**: `Lcom/ex/auto/MainActivity;->b0(Z)Z` (cex_out/sources/com/ex/auto/MainActivity.java:2834-2903; поля `f1020i1` = путь, `f1022j1` = 151 — строки 456-457), запись файла `Lf2/d;->i0(File;String)`, чтение обратно `MainActivity;->V()`, лог `GPIO 151 por sysfs: pedi …, li …`; вызовы: `La1/i;->run` (`cex_smali/a1/i.smali:533`), `Lw0/ta;->run` (`cex_smali/w0/ta.smali:76`, pref `adb_gpio_enabled`) | `GPIO 151 before=… / por sysfs: pedi 1, li …` |

**Отличие от эталона (осознанное):** в CEX это переключатель (`xor 1`, `adb_gpio_enabled`), у нас — только
включение, и **сначала читаем пин**, пишем лишь если он выключен (`:509-514`). При отказе записи — лог
`GPIO 151 por sysfs falhou: … — vou tentar pelo ADB local`, состояние не меняется.

**Шаг 2 — честная оценка (важно для 1.0.47).** `/dev/socket/adbd` для uid=1000 недоступен: в логе 1.0.45
`ls -l /dev/socket/adbd` → `Permission denied`. Значит шаг 2 с большой вероятностью даст `tcpip_rc=1` и
**ничего не изменит** — это заложено (отказ безопасен), но успехом считаться не будет. Я не выдаю его за рабочий.

**`ADBFIX verify`** — `AdbClient.kt:555-580`: после успеха и повторно через 10 с
(`bootstrap`, `:283-287`) проверяются `id` (uid=0), `settings get system screen_off_timeout` (это место дало
в 1.0.45 380× `ERR_ROOT_CHANNEL_UNAVAILABLE`), `setprop persist.sys.hvacdigital.diag 46` + readback,
`iptables -t mangle -S POSTROUTING`. Итог — одна строка `ADBFIX verify[after_fix]=OK|PARTIAL …`.

**Отложенная работа после успеха** — `AdbClient.kt:582-589` `replayDeferred()`: `NetworkTtl.bootRestore(ctx)`
(правило TTL, если тумблер включён), `StatusIcons.bootRestoreHvacDigital(ctx)` (persist.sys.hvacdigital.* + плагин),
`StatusIcons.syncAll(ctx)`. Context приходит из сервиса: `AdbClient.attachContext()` (`:594-596`) вызывается в
`VoiceAssistantService.kt:340`.

**Если ни один шаг не дал root** — `ADBFIX result=none` (`:478`) и `ROOT_UNAVAILABLE reason=no_adb_root_channel`
(`:290`); ничего не выдумываем, дальше нужен шаг владельца (ПК `adb connect` / recovery).

**Запреты соблюдены:** нет `ctl.restart adbd`, нет записи `persist/service.adb.tcp.port`, нет действий с
`/sys/class/android_usb/**`.

**Статус: ✅ (код по мандату) / 🟡 (результат лечения — только по логу 1.0.46).**

---

## P2.5 — иконка Wi-Fi

**РЕФЕРЕНС.** `w0/kg#b` — геометрия текстовой иконки статус-бара (`padH = 10 dp`, высота
`(descent − ascent) / 0.61f`); у нас тот же расчёт в `StatusIcons.kt:531`.

**ФАКТ (диагноз из лога 1.0.45).** `uploads/voiceapp3 (11).log`:
`ICON_GEOM: kind=wifi w=66px h=23px … glyph=40,3dp` против `ICON_GEOM: kind=batt w=178px h=111px … glyph=67,8dp
textSize=56,0`. Причина ровно та, что в мандате: `Paint` создавался без `textSize`, `fontMetrics` — от дефолтных
12 px, поэтому `bmpH = (descent−ascent)/0.61 ≈ 23 px`, а глиф рисовался 40,3 dp → обрезка сверху/снизу.

**ФАКТ (исправление).** `StatusIcons.kt:556-566`:
- `this.textSize = textPx` **до** чтения `fontMetrics`;
- `fit = if (capPx > bmpH * 0.98f) (bmpH * 0.98f) / capPx else 1f`, рисование от `glyphPx = capPx * fit` (`:566-573`);
- в `ICON_GEOM` добавлены `fit=` и `textSize=` (`:590-591`).
Ожидаемая строка: `ICON_GEOM: kind=wifi w=66px h=111px glyph=40,3dp fit=1.00`.
Клик (`android.settings.WIFI_SETTINGS`), notifId 15024, дуги и уровни не тронуты.

**Статус: ✅ (код) / 🟡 (строка лога с машины).**

---

## «Не трогать» — соблюдено

- Панель уведомлений, батарея `69.2 %`, температура `9.3 °C` (0x2140A377), `0x11600703` — не менялись.
- `TtlScript.kt`, `WindowDoors.kt`, `HvacDigitalPlugin.kt` — по существу не менялись. В `HvacDigitalPlugin.kt:406`
  ровно одна строка: `haveRoot` теперь проверяет только `ROOT_5555` (иначе файл не компилируется после удаления
  `ROOT_65010`) — это следствие P0.3, а не правка логики.
- `ToolsPanel.kt:831` и `UiBridge.kt:638` — только тексты/условия, где упоминался второй порт.
- Блокировка main-thread на adb — не трогали (отдельная итерация).

---

## Что покажет лог 1.0.46 и что делать в 1.0.47

| Строка в логе 1.0.46 | Значение | 1.0.47 |
|---|---|---|
| `ADBDIAG raw5555 rx n=24 cmd=CNXN hex=4e 59 53 43` | порт жив, adbd отвечает — виноват наш клиент | правим клиента (OPEN/OKAY) |
| `ADBDIAG raw5555 rx n=24 cmd=AUTH hex=41 55 54 48` | adbd требует ключ | разбираем авторизацию |
| `ADBDIAG raw5555 rx … SocketTimeoutException`, `n=0` | соединяемся, adbd молчит | ищем способ разбудить adbd (в т.ч. шаг GPIO 151) |
| `ADBDIAG raw5555 … ConnectException … refused` | порт закрыт | лечение изнутри исчерпано → ПК/recovery |
| `ADBFIX step=<n> … probe=ROOT_5555` + `ADBFIX verify[after_fix]=OK` | канал вылечен шагом `<n>` | закрепляем шаг в bootstrap |
| `ADBFIX … probe=UP_NONROOT` | порт отвечает, но шелл не root | разбор, кто слушает |
| `ADBFIX result=none` | ни один шаг не помог | разбор с владельцем |
| `ICON_GEOM: kind=wifi … h=111px … fit=1.00` | иконка в масштабе batt/temp | — |

---

## Итог

| Пункт | Статус |
|---|---|
| P0.3 один порт 5555, 65010/миграция удалены | ✅ (код + grep) |
| P0.2 тип ошибки в файл лога | ✅ (код) |
| P0.1 `ADBDIAG` (5 блоков, включая tcp6 и hex) | ✅ (код) |
| P0.4 `ADBFIX` (settings → tcpip → GPIO 151 + verify) | ✅ (код) / 🟡 результат |
| P2.5 иконка Wi-Fi (textSize до метрик + fit) | ✅ (код) / 🟡 лог |
| Все пункты «на машине» | 🟡 — лог 1.0.46 ещё не снят |

Сборка: CI `41497873248` = success, artifact `13168556358`, APK `/home/user/app-iter24-v1.0.46.apk`.
