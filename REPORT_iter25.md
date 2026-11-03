# REPORT iter25 — 1.0.47 (HOTFIX, versionCode 47)

**Ветка:** `fix/v2` (новых веток нет, `main` не тронут, `--force` не использовался)
**Коммиты:** `9044044` (hotfix) → `??` (возврат трёх функций, см. «Ход работы»)
**CI:** workflow `401712847`, run **41588731417** — **success**
**Artifact:** id **13209098988** (`app-debug`, 177 009 551 B) → APK `/home/user/app-iter25-v1.0.47.apk`
(215 253 757 B, sha256 `2c7048b5b9f983623d58391c58b53725a7ecd77e6bac3a366a4d461c27fe271d`)
**Версия:** `app/build.gradle.kts:60` `versionCode = 47`, `:61` `versionName = "1.0.47"`.

> Примечание по логу: в мандате указан `uploads/voiceapp3-5.log`, фактически прислан `uploads/voiceapp3-4.log`
> (784 строки, версия `1.0.46`). Все цитаты ниже — из него; расхождений с текстом мандата нет.

---

## P0-A — запись в `mt_gpio` удалена полностью

**ЭТАЛОН: CEX `Lcom/ex/auto/MainActivity;->b0(Z)Z` (cex_out/sources/com/ex/auto/MainActivity.java:2834-2903)
и `La1/i;->run` (cex_smali/a1/i.smali:533) — РУЧНОЙ переключатель по 2-секундному удержанию пальца
(`w0/n9` → тост «atalho do ADB: dedo na tela, contando 2s», `MainActivity.java:2763`: `adb_gpio_enabled` xor 1),
НЕ автоматика на старте; на нашей ГУ пин 151 гасит USB (лог 1.0.46 + поведение машины) — не переносим.**

**Доказательство из лога 1.0.46** (`uploads/voiceapp3-4.log`):
`08:10:29.891 ADBFIX gpio path=/sys/devices/platform/1000b000.pinctrl/mt_gpio pin=151 before=0`;
`08:10:29.902 GPIO 151 por sysfs: pedi 1, li 0`; `08:10:32 ADBFIX step=3 … gpio_write=ok read=0 … probe=silent`.
Запись ушла вслепую (обратное чтение не подтвердило), и после неё USB на машине перестал определяться.

**ФАКТ (код).**
- Удалены: `fixStepGpio()`, `gpioRead()`, константы `GPIO_PATH` и `GPIO_PIN`, шаг 3 из `ADBFIX`
  (`tools/AdbClient.kt` — см. grep ниже).
- `grep -rn "mt_gpio" app/src/main/java` → только комментарий-пояснение `App.kt:21` и строка журнала `App.kt:23`.
- `grep -rn "GPIO_PIN\|gpioRead\|fixStepGpio" app/src/main/java` → **0 совпадений**.
- `grep -rn "/sys/devices/platform\|/sys/class/android_usb" app/src/main/java` → **0 совпадений**.

**Статус: ✅**

---

## P0-B — шаг `tcpip:5555` удалён

**РЕФЕРЕНС.** `НЕТ В ЭТАЛОНЕ` (у CEX `tcpip` не реализован вовсе; в его UI — только GPIO-переключатель).
Механизм был неверен by construction: запись `>` в inode unix-сокета = `open(2)`, что даёт `ENXIO`/`EACCES`
даже от root. Подтверждение из лога 1.0.46: `ADBFIX tcpip_5555 rc=0 out=RC=1 sh: can't create /dev/socket/adbd:
Permission denied`.

**ФАКТ.** `fixStepTcpip()` и шаг 2 удалены; `grep -rn "tcpip:5555" app/src/main/java` → **0 совпадений**.

**Статус: ✅**

---

## P0-C — шаг `settings put global adb_enabled 1` удалён

**ФАКТ.** Удалены `fixStepSettings()`, `restoreAdbEnabled()`, поле `adbEnabledPrev` и сам `adbFix()`.
`grep -rn "adb_enabled" app/src/main/java` → **0 совпадений**. По логу 1.0.46 шаг был no-op:
`ADBFIX step=1 name=adb_enabled settings_rc=0 prev=1 after=1`.

Итоговая строка вместо лечения — `AdbClient.kt:284`:
`ADBFIX disabled (iter25: gpio/settings/tcpip отключены — лог 1.0.46: GPIO 151 гасит USB)`.

**Статус: ✅**

---

## P0-D — полезное из 1.0.46 сохранено

| Что | Где | Статус |
|---|---|---|
| `ADBDIAG` целиком (`adbDiag`, `rawReadProbe`, обе таблицы ядра, снимок свойств) | `AdbClient.kt:347-421`, `:490-525` | ✅ |
| `classifyProbeError()` | `AdbClient.kt:325-332` | ✅ |
| Иконка Wi-Fi (`textSize` до метрик + `fit`) | `StatusIcons.kt:556-566` | ✅ (владелец подтвердил форму и размер) |
| Одна проба без ретраев 1500/3000 | `AdbClient.kt:44-52`, `:270-292` | ✅ |
| Окно `rawReadProbe` 30 000 → **8 000 мс** | `AdbClient.kt:52` `RAW_READ_MS = 8000`, используется в `:430` и `:498` | ✅ |

---

## P0-E — диагноз углублён (только чтение)

**ФАКТ.** В `adbDiag()` добавлено (`AdbClient.kt:387-414`):
1. **`ADBDIAG raw5555 multi:`** — 3 соединения подряд, по каждому `n`, `hex`, `abort`, `connectDt`, `dt`
   (`rawReadOnce()`, `:424-451`). Если молчат все три — adbd не обслуживает accept-очередь.
2. **`ADBDIAG 5037 host:version` / `5037 host:devices`** — smart-socket запрос к найденному в логе 1.0.46
   `127.0.0.1:5037 LISTEN`: `4 hex-цифры длины` + строка, лог `status`, `len`, тело, `dt`
   (`smartSocketProbe()`, `:453-486`).
3. **`ADBDIAG ps adbd` / `ADBDIAG unix adbd`** — `ps -A | grep -i adbd`, `cat /proc/net/unix | grep -i adbd` (`:396-402`).
4. **`ADBDIAG prop sys.usb.state / persist.sys.usb.config / sys.usb.config`** — только чтение (`:405-408`).

Записи нет ни в одном пункте: единственная запись в диагностике — `setprop persist.sys.hvacdigital.diag 46`
(наш собственный props, `:384-387`), она осталась с 1.0.46 и глобальных настроек не касается.

**Статус: ✅**

---

## P0-F — отметка «безопасной» сборки в логе

**ФАКТ.** `App.kt:23` — сразу после `BUILD:`-строки:
`USB_GUARD: iter25 — запись в mt_gpio отключена (1.0.46 гасила USB), sysfs/USB не трогаем`.

**Статус: ✅**

---

## Дополнительно найдено и обезврежено: запись `persist.switch.usbmode` на старте

**Это не было в мандате, но это ровно «влияние на USB», поэтому говорю прямо.**

`handlers/OpenAppHandler.kt` строил карту Intent’ов в конструкторе (`customAppMap`), а в ней
`getEngineerIntent()` — который (при `CarModel.isE5 = false`, то есть всегда, `tools/CarModel.kt:23`)
писал `Settings.Global.putString(..., "persist.switch.usbmode", "true")`. Карта строится при создании
`IntentHandler` (`IntentHandler.kt:63`), то есть **на каждом старте приложения** — глобальная настройка
режима USB переписывалась без запроса. В 1.0.45/1.0.46 это тоже происходило, но к поломке USB привёл GPIO.

**Что сделано** (`handlers/OpenAppHandler.kt:41-72`, `:88-105`):
- `getEngineerIntent()` больше ничего не пишет — только собирает Intent;
- запись вынесена в отдельный `prepareEngineerUsbMode()`, который вызывается **только** при фактическом
  запросе «инженер» (`intent0 === ENGINEER_INTENT` в `handle()`);
- в карте вместо готового Intent лежит маркер `ENGINEER_INTENT`.

**Статус: ✅**

---

## Подтверждение: после 1.0.47 приложение не может влиять на USB

Полный список мест, где код вообще пишет за пределы своего каталога (`grep` по `app/src/main/java`):

| Место | Что пишет | Влияет на USB? |
|---|---|---|
| `tools/ScreenBrightness.kt:140` | `/sys/class/leds/lcd-backlight/brightness` — подсветка экрана | нет |
| `tools/ScreenSaverOverlay.kt:146` | тот же backlight (заставка) | нет |
| `handlers/OpenAppHandler.kt:63` | `Settings.Global persist.switch.usbmode` — **только по запросу «инженер»**, не на старте | нет (ручное действие) |
| `tools/AdbClient.kt` | `setprop persist.sys.hvacdigital.diag` (свой props, диагностика) | нет |
| `tools/AppPerformance.kt` (кнопки производительности) | `setprop dalvik.vm.usejit`, `ctl.restart zygote`, `umount /data/app/*`, `svc wifi` — **только по явной кнопке** владельца | нет |
| `nav/routing/GraphManager.kt`, `car/VehiclePropertyDumper.kt`, `tools/StatusIcons.kt:216`, `media/MetadataAdapter.kt`, `car/CarAudioPlayer.kt`, `AppPerformance.kt` | файлы в своём каталоге (`filesDir`/`cacheDir`) | нет |

Записей в `/sys/devices/platform/**`, `/sys/class/android_usb/**`, `mt_gpio` — **нет** (`grep` → 0).
Ни одна ветка старта не пишет в sysfs и не меняет глобальные настройки.

**Grep-доказательства для отчёта:**
```
grep -rn "mt_gpio\|GPIO_PIN\|gpioRead\|fixStepGpio" app/src/main/java   → только App.kt:21,23 (комментарий + строка лога)
grep -rn "adb_enabled"        app/src/main/java                          → 0
grep -rn "tcpip:5555"         app/src/main/java                          → 0
grep -rn "65010"              app/src/main/java                          → 0
grep -rn "ctl.restart adbd"   app/src/main/java                          → 0 (осталось только «ctl.restart zygote» в AppPerformance — по кнопке)
grep -rn "setprop.*adb.tcp.port" app/src/main/java                       → 0
```

---

## Ход работы (честно)

Первая сборка iter25 (`run 41587541445`) **упала** на компиляции: широкое удаление блока «лечение»
захватило и три нужные функции — `logAdbPortReadback`, `shellLocal`, `executeSmart`
(`e: Unresolved reference 'shellLocal'` из `HvacDigitalPlugin.kt:51,65,90,378,393`). Это тот же класс ошибки,
что был в iter22 при удалении диапазона строк. Функции восстановлены (`AdbClient.kt:536, 550, 558`),
повторный прогон `41588731417` — success. Впредь перед удалением блока сверяю список `fun` с предыдущим коммитом.

---

## Что означает каждая новая строка `ADBDIAG` → что делаем в 1.0.48

| Строка в логе 1.0.47 | Значение | 1.0.48 |
|---|---|---|
| `ADBDIAG raw5555 #1 n=0 abort=SocketTimeoutException`, и `#2`, `#3` так же | adbd не обслуживает accept-очередь (порт слушает, но не отвечает) | искать, кто держит `:15B3` в tcp6 при `init.svc.adbd=running`; проверить, не adb-сервер ли это на 5037 |
| `ADBDIAG raw5555 #1 timeout`, а `#2/#3 cmd=CNXN` | первый клиент «висящий» мешает | закрывать/переиспользовать первое соединение, не открывать новое |
| `ADBDIAG 5037 host:devices → body=…` непустое | на устройстве уже работает транспорт adb-сервера | работать через 5037 вместо прямого 5555 |
| `ADBDIAG 5037 … ECONNREFUSED` | сервер не поднят (был в 1.0.46 — значит поднимается кем-то ещё) | выяснить владельца порта через `ADBDIAG ps adbd`/`unix adbd` |
| `ADBDIAG ps adbd` пусто, `unix adbd` пусто | процесс adbd не наш / скрыт от uid=1000 | опираться на 5037 и на `getprop` |
| `ADBDIAG prop sys.usb.state=…` | снимок USB-режима ГУ | сопоставить с поведением USB, если снова отвалится |
| `USB_GUARD:` в начале лога | сборка «безопасная», sysfs/USB не трогается | — |
| `ADBFIX disabled …` | лечения нет (как задумано) | — |

---

## Итог

| Пункт | Статус |
|---|---|
| P0-A убрана запись в `mt_gpio` | ✅ |
| P0-B убран `tcpip:5555` | ✅ |
| P0-C убран `settings put global adb_enabled` | ✅ |
| P0-D сохранены `ADBDIAG`, `classifyProbeError`, Wi-Fi, проба 1500/3000; окно 8 с | ✅ |
| P0-E multi-probe / 5037 / ps adbd / usb-props | ✅ |
| P0-F `USB_GUARD` в логе | ✅ |
| USB на машине больше не гасится | 🟡 — подтверждается только следующей поездкой/логом |
| Root-канал | ⛔ — лечения нет by design, диагноз продолжается |

Сборка: CI `41588731417` = success, artifact `13209098988`, APK `/home/user/app-iter25-v1.0.47.apk`.
