# iter22 REPORT — 1.0.44 (доделка, номер НЕ поднят)

| Поле | Значение |
|---|---|
| versionCode / versionName | **44 / "1.0.44"** (`app/build.gradle.kts:60-61` не тронут) |
| head_sha | `1f18cfa354feece4e6fd4668e8fcda12afecacd0` |
| run_id (CI «Build APK») | `41338577131` — **success** |
| APK (скачать) | `/home/user/app-iter22-v1.0.44.apk` (215 224 613 Б) |
| sha256 | `54c46a507222a7cbef248816d519e537444614c1a0baaa66ac223d44eabb9e94` |
| Эталон | CentralEXAuto 3.3.9, декомпиляция `/home/user/cex/cex_out/sources/` |

---

## 1. ⛔ РАЗМЕР ИКОНОК В СТРОКЕ УВЕДОМЛЕНИЙ

РЕФЕРЕНС: `w0/kg#a(Context, String main, String suffix, int color, int padH, boolean, boolean, float)`
strings: `h(ctx,56)`, `0.61f` (суффикс), `h(ctx,i4)` = padH dp, `(int)((descent-ascent)/0.61f)`
shell: —

РЕФЕРЕНС: `w0/kg#j(Context,int notifId,int iconId,int rank,String,String,String,Icon,PendingIntent)`
strings: `"central_ex_status_temp"`, IMPORTANCE `2`, `setShowBadge(false)`, extras `flag_status_icon_*`
shell: —

ФАКТ:
- `StatusIcons.kt:463-505` — `makeTextIconCex()`: TEXT_DP=56, суффикс `0.61×`, padH=22dp, высота `max(1,(int)((descent-ascent)/0.61f))`, ширина `2*padH + measure(main) + gap + measure(suffix)`; отдаётся `Icon.createWithBitmap(...)`. Битмап **24×24 удалён**.
- `StatusIcons.kt:427-455` — `postNotif()` переписан под `w0/kg#j`: канал `central_ex_status_temp`, IMPORTANCE_LOW, `setShowBadge(false)`, `setSmallIcon(Icon)`, `setOngoing(true)`, `setShowWhen(false)`, все 10 extras `flag_status_icon_*`.
- `StatusIcons.kt:75-84` — константы как в эталоне: NOTIF_BATT=15020, NOTIF_TEMP=15021, ICON_ID_BATT=97, ICON_ID_TEMP=15, RANK_BATT=32, RANK_TEMP=22.
- `StatusIcons.kt:660-695` — **две отдельные нотификации** (15020 `"<n>%"` + `%`, 15021 `"<t>°C"` + `°C`), заголовок-склейка `"temp   batt   wifi"` и `postTempBattPill` удалены полностью.
- `StatusIcons.kt:507-534` — Wi-Fi (регресс-зона): те же дуги/клик `android.settings.WIFI_SETTINGS`/notifId 15024, но иконка CEX-геометрии.
- Лог: `KmIconProbe: postStatusIcon … notifId=… iconId=… rank=… text="…"` (`StatusIcons.kt:451`).

ПРИЁМКА: в логе две строки `iconId=97` и `iconId=15`; в строке состояния ГУ цифры по высоте сопоставимы с часами/сетью (было — квадрат 24dp с текстом ~13dp).

Чек: ✅ (код) / 🟡 (визуал подтверждает только скриншот с ГУ — приложите)

---

## 2. ⛔ ТЕМПЕРАТУРА — ЗНАЧЕНИЕ НЕ СКРЫВАТЬ

РЕФЕРЕНС: `com.geely.hvacstepperv2.TempStepperService#i(int area)`
strings: `getFloatProperty(291505923, area)`; при NaN `getIntProperty(291505923, area)`: `> -550 && < 700 → /10.0f`, `>= -45 && <= 65 → as-is`
РЕФЕРЕНС: `TempStepperService#g()` — зоны `f1286d0 = {0,1,4,7}`, первый валидный `> -55 && < 70` → `"Exterior OK (ENV_OUTSIDE_TEMPERATURE) area=0x%x → %.1f°C"`
shell: — (VHAL)

> **291505923 == 0x11600703**. Мандат §2.3 просил «убрать 0x11600703 полностью»; в эталоне это **и есть** ENV_OUTSIDE_TEMPERATURE. Убрать его = остаться без единственного источника эталона, поэтому ID оставлен, но чтение **приведено к схеме CEX** (§2.4), и он удалён из шумного `CANDCFG`-дампа. Ниже — как проверять; если панель ГУ показывает не то же число — вернёмся к вопросу.

ФАКТ:
- `StatusIcons.kt:320-332` — `PROP_ENV_OUTSIDE_TEMP = 291505923`, `CEX_OUTSIDE_AREAS = {0,1,4,7}`.
- `StatusIcons.kt:335-343` — `propConfigAccessAreaType()`: `getCarPropertyConfig(propId)` рефлексией (как CEX, `TempStepperService#…:309`) → `access`/`areaType`; если `access` не 1/3 — свойство не читаем (`#2.4`: выбор по access/areaType перед `getFloatProperty`).
- `StatusIcons.kt:345-392` — чтение ровно как `#i`+`#g`: float по зонам {0,1,4,7} → int-ветка `/10` → int как есть; всё в лог `TEMP_PROBE 0x11600703@<area> float=… / int=…`, успех — `Temp HAL=%.1f → UI=%.1f (area=0x…)`.
- `StatusIcons.kt:673-683` — **число показывается всегда**: `display = readTempScan() ?: lastTempC`. `"--"` — только если значения не было ни разу за жизнь сервиса.
- `StatusIcons.kt:684-694` — детектор залипания: при неизменном значении 5+ тиков — `WARN TEMP: value pinned at … (possibly VHAL default)`; значение при этом остаётся на экране.
- `StatusIcons.kt:213` — `0x11600703` убран из `inRange` дампа (мандат §2.3).
- `StatusIcons.kt:467-471` — формат `w0/kg#i`: `Math.round(x*10)/10` → целое или `"%.1f"` (батарея `42.4`, не `4.2`).
- `StatusIcons.kt:390-400` — 5 альтернативных ID (`0x2140A863/0x2140B009/0x2140B00D/0x2140A6DC/0x21402063`) остались **только диагностикой** (`TEMP_PROBE (diagnostics only, not a source)`), авто-выбора нет.

ПРИЁМКА: в логе `Temp HAL=<n> → UI=<n>°C (area=0x…)`; рядом с цифрой в `contentText` — источник (`HAL 0x11600703@0`); переходы значения совпадают с панелью климата ГУ.

Чек: ✅ (код) / 🟡 (совпадение с панелью — только по логу с ГУ)

---

## 3. ⛔ КНОПКИ В ИНТЕРФЕЙСЕ

РЕФЕРЕНС: `w0/kg#j` / штатные `Settings` — тумблера/сторожа в эталоне нет; кнопка — наше согласованное исполнение требования владельца (iter21 §3).
shell: `settings put system screen_off_timeout 2147483647`; `settings put global stay_on_while_plugged_in 7`

ФАКТ:
- `ToolsPanel.kt:850-874` — `addOneShot(...)`: строка «название + описание + кнопка ВЫПОЛНИТЬ», шлёт `settings.change {key, value:true}`, состояния не хранит.
- `ToolsPanel.kt:877-895` — `oneShotNote(...)`: печатает `summary` + хвост `actual/stdout` последнего шага (то есть `after=… stay_on=… rc=…` либо `RESTORE_OK`).
- `ToolsPanel.kt:244-246` — **«Не гасить ГУ (до перезагрузки)»** → `keepawake_btn` (страница «Экран»).
- `ToolsPanel.kt:281-283` — **«Вернуть панель SystemUI»** → `hvac_restore_btn` (страница «Строка состояния», сразу под тумблерами плагина).
- Маршруты существовали: `ToolsBackend.kt:904,908`; реализация `ScreenBrightness.kt:352-386`, `HvacDigitalPlugin.kt:275-320`.
- Логи: `settings.keepawake_btn=true` (`ToolsBackend.kt:681`), `KEEPAWAKE_BUTTON: before=… after=… stay_on=… rc=…` (`ScreenBrightness.kt:377`), `hvacdigital.restore`.

ПРИЁМКА: Инструменты → «Экран» → кнопка видна; нажатие → `settings get system screen_off_timeout` = 2147483647. Инструменты → «Строка состояния» → «Вернуть панель SystemUI» → шторка поднимается, в логе `RESTORE_OK`.

Чек: ✅ (код) / 🟡 (нажатие — на ГУ)

---

## 4. ПЛАГИН: ПЛАН Б КАК В ЭТАЛОНЕ

РЕФЕРЕНС: `w0/o#c` (case 9) — генератор скрипта для `CentralWatchdogService`
shell (дословно из dex): `setenforce 0 2>/dev/null || true`; `_OUT=$(pm install -r -g <apk> 2>&1 | tail -n1)`; `case "$_OUT" in *Success*) _FALHOU=0;; *) _FALHOU=1;; esac`; при `_FALHOU!=0` — `chown/chmod/chcon u:object_r:system_app_file:s0` + `for _i in 1 2 3 4 5 6` + `umount || umount -l` + `mount --bind`; вердикт по `pm path`: `*/data/app/*` ⇒ `VIA=install` + `setprop persist.sys.hvacdigital.via install`; иначе `grep -F " $_P " /proc/mounts` + `stat -c '%d:%i'` ⇒ `VIA=bind` + маркер `/data/local/tmp/fixes/hvac_em_bind`; иначе `NAO_VINGOU` + `nenhum`.

ФАКТ:
- `HvacDigitalPlugin.kt:92-197` — скрипт `enable()` переписан по этому порядку: `SEM_FONTE`/`SEM_ALVO`, план А `pm install -r -g`, план Б bind-mount с 6 попытками `umount`/`umount -l`, `restorecon`, вердикт `VIA=install|bind|nenhum`, маркер `hvac_em_bind`, `setprop persist.sys.hvacdigital.via …`.
- `HvacDigitalPlugin.kt:198-205` — скрипт идёт через `AdbClient.executeScript("hvac_enable", …)` (staging-файл), а не одной строкой.
- `HvacDigitalPlugin.kt:207-222` — вердикт **в лог**: `HVAC VERDICT: VIA=… PM=… PM_PATH=… SELINUX_BEFORE=… SELINUX_AFTER=… SEM_FONTE=… SEM_ALVO=…`.
- `HvacDigitalPlugin.kt:223-232` — статус шага вычисляется по `VIA` (`install`+`/data/app/` ⇒ OK; `bind`+mount/inode ⇒ OK; `nenhum` ⇒ FAILED).
- Идемпотентность сохранена: `ALREADY_MOUNTED (idempotent)` и SystemUI перезапускается максимум один раз (`NEED_RESTART`).

ПРИЁМКА: лог после включения тумблера: `HVAC VERDICT: VIA=bind|install|nenhum PM_PATH=/… SELINUX_BEFORE=Enforcing SELINUX_AFTER=Permissive`; при `nenhum` — кнопка «Вернуть панель SystemUI».

Чек: ✅ (код) / 🟡 (вердикт — из лога на ГУ)

---

## 5. TTL: ПРИЁМНИК СЕТИ + ЧИСЛО В ЛОГ

РЕФЕРЕНС: `w0/n` / `NetworkTtl` — ручной путь уже переведён в iter21 §7 (single-flight + 30 000 мс); эталонного «ресивера» в CEX нет, поэтому §5.1 = распространение нашей же защиты на `networkChange` (мандат).
shell: `TTL_CHECK: before=<n> after=<n> rc=<n> rule_ttl=<n>` — из `iptables -t mangle -S POSTROUTING | grep -oE -- '--ttl-set[[:space:]]+[0-9]+'`

ФАКТ:
- `NetworkTtl.kt:278-303` — `networkChange` теперь под тем же `synchronized(this@NetworkTtl)` + `ttlInFlight` + окно 30 000 мс; при отказе — лог `TTL_RECEIVER: skipped reason=in_flight|debounce <ms>` и запуск не производится.
- `NetworkTtl.kt:209-215` — из строки `TTL_CHECK` вынимаются числа и печатаются отдельной строкой: `TTL_CHECK: before=… after=… rc=… rule_ttl=…`.
- `TtlScript.kt:211-216` — сама строка расширена полями `before=` (значение `--ttl-set` ДО применения) и `after=` (ПОСЛЕ), `rc=` — код `iptables -C`; значения берутся из правила, не из числа строк.
- `AdbClient.kt:52-57` — основной канал 5555, 65010 — вторичная попытка (мандат §5.3).

ПРИЁМКА: включить TTL → в логе `TTL_CHECK: before=none after=65 rc=0 rule_ttl=65 …`; постучать по тумблеру сети — второй запуск даёт `TTL_RECEIVER: skipped reason=debounce`.

Чек: ✅ (код) / 🟡 (числа — из лога на ГУ)

---

## 6. ЧЕК-ЛИСТ

| Пункт | Статус |
|---|---|
| 1. Размер иконок по геометрии CEX | ✅ код (`StatusIcons.kt:463-505`, `:427-455`) · 🟡 скриншот ГУ |
| 2. Температура показывается числом | ✅ код (`StatusIcons.kt:673-694`) · 🟡 сверка с панелью |
| 3. Кнопки в UI | ✅ код (`ToolsPanel.kt:244,281`) · 🟡 нажатие на ГУ |
| 4. План Б плагина + VIA | ✅ код (`HvacDigitalPlugin.kt:92-232`) · 🟡 вердикт из лога |
| 5. TTL ресивер + числа | ✅ код (`NetworkTtl.kt:278-303`, `:209-215`) · 🟡 из лога |
| 6. Регресс: тема ГУ / строка состояния / окна-двери / Wi-Fi / звук закрытия / первый кадр / один `kill systemui` | ✅ код не затронут, Wi-Fi только размер иконки |

---

## ИТОГ ИТЕРАЦИИ

**Что изменилось для владельца:**
1. Иконки батареи/температуры в строке состояния теперь рисуются битмапом высотой в строку (56dp-геометрия CEX), а не 24×24 — цифры должны стать читаемыми.
2. Температура снова показывается числом; `"--"` остался только на случай полного отсутствия данных.
3. Обе кнопки («Не гасить ГУ», «Вернуть панель SystemUI») реально есть в «Инструментах» — нажать можно.
4. Включение плагина теперь имеет план Б (`pm install`) и печатает вердикт `VIA=`/`PM_PATH=`/SELinux — по логу будет видно, почему панель не встала.
5. TTL-приёмник сети больше не штормит; в логе есть числа `before/after/rc/rule_ttl`.

**Что не сделано и почему:**
- Скриншот строки состояния и лог ГУ приложить не могу — сборка делается в CI без устройства.
- §2.3 мандата («убрать 0x11600703 полностью») выполнен частично: ID убран из дампа `CANDCFG`, но оставлен источником, потому что в эталоне `TempStepperService#i` читает именно `291505923 == 0x11600703`. Удаление оставило бы нас без источника вовсе — это противоречие внутри ТЗ, вынесено сюда явно; решается после сверки с панелью ГУ.
- `StatusOverlay.kt` удалён целиком (оверлейный слой не возвращён), `postTempBattPill` (канал `temp_batt_pill_floater`, NOTIF 109) тоже удалён — вместо него две CEX-иконки.
