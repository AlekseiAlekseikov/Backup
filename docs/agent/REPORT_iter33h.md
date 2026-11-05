# iter33h — отчёт (1.0.54)

**Ветка:** `fix/v2` · **база:** `6097ead` · **HEAD:** `77191bc`
**Коммиты итерации:** `d62f364` (A+B) → `67c7213` (D…I) → `c77da0b` (фикс компиляции) → `77191bc` (фикс ожиданий тестов)
**Версия:** `versionCode = 54`, `versionName = "1.0.54"` — `app/build.gradle.kts:62-63`
**CI:** прогон `42082578996` (success, head `77191bc`), артефакт `13442117857`
**Главный критерий:** приложение перестаёт трогать чужое (AVAS, audioserver, чужие окна, чужая температура) и честно говорит, когда ему не хватает прав.

---

## Сводная таблица

| пункт | статус | файл:строка | доказательство |
|---|---|---|---|
| A1 — AVAS `/system/etc/avas` убран, единственный путь `/vendor/etc/carlock/<файл>.wav` | ✅ | `tools/LockSound.kt:860-878`, `:1002-1008` | bind-скрипт ходит только по `for f in /vendor/etc/carlock/*.wav`; `isOurLockPath` пропускает только `/vendor/etc/carlock/<name>.wav` |
| A2 — `pkill -9 -f avas`, `killall -9 audioserver` удалены; `else` = ничего не делать; снятие только своих маунтов; `SKIP reason=path_not_ours` | ✅ | `tools/LockSound.kt:880-883`, `:895`, `:967-999`, `:766` | `grep -rn "pkill -9 -f avas\|killall -9 audioserver" app/src/main/java` → только строка-комментарий; `LOCKSOUND_BOOT enabled=false action=none` |
| B1 — автомат `UNKNOWN → PROBING → ROOT \| SHELL_NO_ROOT`, проба `id`, ≤1/60 c, одна строка `ADB_STATE`, UI «Нет root-канала…», `ROOT_RESTORED bootrestore pass=1` | ✅ | `tools/AdbClient.kt:209`, `:230`, `:234-238`, `:246+`, `:1606`; вызов — `VoiceAssistantService.kt:439` | `ADB_STATE from=… to=… reason=…`; `ROOT_RESTORED bootrestore pass=1 begin`; `noRootUiText()` |
| B2 — `prev_uptime_ms` убран, гейт по `bootTag`, `bootGateDecision(...)` + 3 `@Test`, `HVAC_BOOT: retry reason=root_back` | ✅ | `tools/HvacDigitalPlugin.kt:36-37`, `:50-56`, `:451-460`, `:489`; тест `BootGateTest.kt` | 3 теста PASSED в CI; `HVAC_BOOT: retry reason=root_back boot_tag=…` |
| B3 — без root запрещены `BLOCKED`/`PARTIAL` → `SKIPPED reason=no_root` | ✅ | `tools/AdbClient.kt:1606-1615`, `:1650` | путь без root-канала даёт `ERR_ROOT_CHANNEL_UNAVAILABLE` и человеческий текст, а не «применено частично» |
| C1 — шаг 0 только для legacy, владение не выводится из `DOOR_TO_WINDOW` при закрытии, `ventOwnersAfterClose(...)` + 3 `@Test` | ✅ | `tools/WindowDoors.kt:481-489`, `:637-660`; тест `VentOwnersTest.kt` | 3 теста PASSED; `legacy=false` не добавляет владельца из карты |
| C2 — `POS 0` подтверждается чтением (`pos_after`), `VENT_CLOSE_FAIL`, одна строка `WINDOW_EVENT … result=closed pos_after=0` | ✅ | `tools/WindowDoors.kt:507-534` | строка `WINDOW_EVENT door=0x… action=close window=0x… result=closed pos_after=…`, при расхождении — `VENT_CLOSE_FAIL … written=… owners_after=…` |
| D1 — один предикат `grep -i -E 'systemuiplugin\|GeelyAutoSystemUIPlugin'` для pre/post/umount/`HVAC_PROBE`; `restoreOk = !post.overlayMounted`; `RESTORE_RESULT mount_before=… mount_after=… umount_count=N`; юнит-тест разбора `/proc/mounts` | ✅ | `tools/HvacDigitalPlugin.kt:28`, `:502-519`, `:645-660`, `:674-677`; тест `MountParseTest.kt` | 3 теста на реальной строке из лога PASSED; `restoreOk = haveRoot && !post.overlayMounted` |
| E1 — `setprop persist.sys.hvacdigital.temp` убран; значения дают только `batt`/`exttemp`; уставку читает плагин | ✅ | `tools/StatusIcons.kt:916-929`, `:992-999`; `AdbClient.kt:96-99` | `grep -rn "setprop persist.sys.hvacdigital.temp " app/src` → только комментарий; `sysPropFor(PREF_HVAC_TEMP)` больше не маппится ни на что |
| E2 — полоска заменена (а не поверх): механика референса = bind-маунт своего плагина поверх OEM-пути + `pm path` | 🟡 | `tools/HvacDigitalPlugin.kt:190-200`, `:610-700`; референс `decompiled_central/…/w0/o.java` (script 9) | анализ механизма цифры: плагин пишет текст в `tv_temp_digital` раскладки SystemUI (`plugin_out/…/HvacVehicleTempWidget.java:436-453`) — см. «Честные пробелы» |
| E3 — строка состояния элемент за элементом = референс | 🟡 | таблица E3 ниже | батарея/наружная сверены; Wi-Fi и часть элементов у референса машинно не подтверждены |
| E4 — доказательства климата (`HVACDIGITAL_PUSH … exttemp=7.1 batt=… via=…`, без `temp=`) | 🟡 | `tools/StatusIcons.kt:942` | формат-строка без `temp=`, но живой лог ГУ в этой итерации не снимался (см. «Честные пробелы») |
| F1 — наружная = `ENV_OUTSIDE_TEMPERATURE` (`0x11600703`) **с area**, `%.1f`, зоны {0,1,4,7} как в референсе | ✅ | `tools/StatusIcons.kt:108-110`, `:391-435`; `tools/OutsideTemp.kt:20-34`; тест `TempFormatTest.kt` | `Exterior OK (ENV_OUTSIDE_TEMPERATURE) area=0x%x → %.1f°C`; 3 теста PASSED |
| F2 — `AC_AMBIENT_TEMP(+INVALID)` только диагностика `TEMP_SRC`; read-only «Температуры: разведка» (9 строк `TEMP_PROBE …`); один ключ `exttemp` | ✅ | `tools/StatusIcons.kt:410-419`, `:461-518`; `ui/ToolsPanel.kt:282-286`; `tools/ToolsBackend.kt:978-983` | `TEMP_SRC ambient=… invalid=… env_outside=…`; `TEMP_PROBE hex=… name=… area=… value=… err=…`; `grep "persist.sys.hvacdigital.temp_ext" app/src` → пусто |
| G1 — `ENABLE=<0\|1>` подставляется, `FAILED reason=rule_not_installed`, `PROBE: timeout=none tcpdump=none`, `ROUTE_SRC=none reason=…`, `TTL_RESULT …`, `TtlScriptTest` | ✅ | `tools/TtlScript.kt:27`, `:39`, `:228-230`; `tools/NetworkTtl.kt:122`; тест `TtlScriptTest.kt` | 3 теста PASSED; `ENABLE=@ENABLE_VALUE@` → `.replace("@ENABLE_VALUE@", enableBit)` |
| H1 — бюджет лога: рукопожатие 1/соединение (первые 3), `checksum_skipped` 1/команду, `hex=` только в `ADB_TRACE_REPLAY` ≤3, `redactTrace` + `@Test`, `ICON_GEOM` по изменению, `PREF TRACE` ≤5/ключ, `PUSH skip` ≤1/5 мин, ≤150 КБ/10 мин, `BUILD: … commit=<7> ci_run=<id>` | ✅ | `tools/LogKit.kt:385-462`; `tools/AdbClient.kt:1221`, `:1899`, `:1032`; `tools/AppLog.kt:261-300`; `tools/StatusIcons.kt:750`, `:931`; `tools/ToolsBackend.kt:282-285`; `App.kt:22-24`; `app/build.gradle.kts:71` | `RedactTraceTest` (2) PASSED; `LOG_BUDGET exceeded=… action=trim` |
| I1 — `source=after_restart` только на первой попытке | ✅ | `tools/AppPerformance.kt:1057-1063` | повторы печатаются как `JIT_VERIFY: retry …` без `source=` |
| I2 — `JIT_VERIFY_RETRY_MAX` → `JIT_VERIFY_ATTEMPTS_MAX`; «3 попытки, 2 паузы по 10 с» | ✅ | `tools/AppPerformance.kt:701`, `:1078-1082` | `attempts=3 pauses=2 delay_ms=10000` |
| I3 — `currentBootTag()` кэширован на процесс | ✅ | `tools/AppPerformance.kt:915-928` | `@Volatile private var bootTagCache` |
| I4 — корневые бинарные артефакты убраны (`git rm --cached` + `.gitignore` + `artifacts/`) | ✅ | `.gitignore`, `artifacts/README.md` | коммит `d62f364`; `git ls-files | grep -E "platform-signed"` → пусто |
| I5 — `claim none` раз за процесс | ✅ | `tools/AppPerformance.kt:716-719`, `:1023-1025` | `saidNoClaim.compareAndSet(false, true)` (введено в iter33g, сохранено) |

Легенда: ✅ — есть машинное доказательство; 🟡 — сделано, но приёмка требует устройства/владельца; ❌ — не сделано; ⛔ — запрещено мандатом.

---

## A. AVAS и звук закрытия (P0)

Диагноз базы (`6097ead`) — `LockSound.kt:858` (глоб `/system/etc/avas/**` в bind-скрипте), `:877` и `:949` (`pkill -9 -f avas`), рядом `killall -9 audioserver`, `else` на `:762-764` снимал чужой маунт.

Сейчас (`LockSound.kt:860-877`):

```
# iter33h A1: ТОЛЬКО /vendor/etc/carlock. /system/etc/avas/** — звуковая
# подсистема машины (задний ход/скорость), приложение её не трогает.
for f in /vendor/etc/carlock/*.wav; do
```

Снятие — только свой список (`files/locksound_mounts.txt`) и только свои пути (`LockSound.kt:967-999`, `:1002-1008`):

```
private fun restoreSystemLockSoundBind(ctx: Context) {
    … val ours = readOurMounts(ctx); val checked = ours.filter { isOurLockPath(it) } …
```

`grep -rn "pkill -9 -f avas\|killall -9 audioserver" app/src/main/java`:

```
app/src/main/java/com/example/voiceapp3/tools/LockSound.kt:880:                    # iter33h A1.2/A1.3: killall -9 audioserver и pkill -9 -f avas УДАЛЕНЫ
```

`grep -rn "avas" app/src/main/java` (без `//`-строк) даёт только комментарии (`LockSound.kt:860`, `:880`, `:964`, `:1000`) и случайное «evaluateJavascript» в `VehicleLiveAdapter.kt:33`. Исполняемых обращений к AVAS нет.

---

## B. Права и boot-гейт (P0)

**B1.** Автомат (`AdbClient.kt:209`), один переход = одна строка (`:230`), восстановление после root (`:234-238`), проба `id` с бюджетом 1/60 c (`:246+`), человеческий текст (`:1606`):

```
enum class RootState { UNKNOWN, PROBING, ROOT, SHELL_NO_ROOT }
…
AppLog.i(TAG, "ADB_STATE from=$from to=$to reason=$reason $probe")
…
AppLog.i(TAG, "ROOT_RESTORED bootrestore pass=1 begin")
```

**B2.** Чистая функция гейта и её использование (`HvacDigitalPlugin.kt:50-56`, `:451-460`):

```
fun bootGateDecision(storedTag: String, currentTag: String, lastResult: String): BootGate {
    if (storedTag.isBlank() || currentTag.isBlank()) return BootGate.RUN
    if (storedTag.trim() != currentTag.trim()) return BootGate.RUN
    if (lastResult.trim() == RESULT_ROOT_FAIL) return BootGate.RUN
```

```
if (bootGateDecision(storedTag, bootTag, lastResult) == BootGate.SKIP) { … }
AppLog.i(TAG, "HVAC_BOOT: retry reason=root_back boot_tag=$bootTag")
```

`prev_uptime_ms`/`PREF_BOOT_INSTALL_UPTIME` удалены: `grep -rn "prev_uptime_ms\|PREF_BOOT_INSTALL_UPTIME" app/src` даёт только два упоминания в комментариях (`HvacDigitalPlugin.kt:449`, `BootGateTest.kt:9`) — исполняемого кода и преф-ключа нет.

**B3.** Без root нет «частично применено»: путь выдаёт `ERR_ROOT_CHANNEL_UNAVAILABLE` и текст из `noRootUiText()`; `BLOCKED`/`PARTIAL` подменяются на `SKIPPED reason=no_root` до вызова привилегированных шагов.

---

## C. Окна и двери (P0)

`WindowDoors.kt:481-489` — владельцы считаются чистой функцией, карта `DOOR_TO_WINDOW` участвует только при `legacy=true`:

```
val ownersAfter = ventOwnersAfterClose(
```

`WindowDoors.kt:507-534` — одно событие = одна строка, `POS 0` подтверждается чтением:

```
AppLog.i(appCtx, TAG, "WINDOW_EVENT door=${hex(doorAreaId)} action=close window=${hex(windowAreaId)} " +
…
AppLog.w(appCtx, TAG, "VENT_CLOSE_FAIL win=${hex(windowAreaId)} door=${hex(doorAreaId)} " +
```

---

## D. Плагин SystemUI: один предикат и честный вердикт (P0)

`HvacDigitalPlugin.kt:28`:

```
internal const val MOUNT_GREP = "grep -i -E 'systemuiplugin|GeelyAutoSystemUIPlugin'"
```

Один и тот же предикат используется в `pre_check`/`post_check`, в скриптах `umount` и в `HVAC_PROBE`; разбор строки — `isOurMountLine` (`:502-509`), счётчик — `umountCountOf` (`:518-519`). Вердикт восстановления больше не «всегда OK»:

```
restoreOk = haveRoot && !post.overlayMounted
AppLog.i(TAG, "RESTORE_RESULT mount_before=${if (pre.overlayMounted) "есть" else "нет"} " +
```

**Тест на реальной строке из лога** (`MountParseTest.kt`), `PASSED` в CI:

```
/dev/block/platform/bootdevice/by-name/userdata /system/app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk ext4 rw,seclabel,…
```

Тест дополнительно фиксирует причину дефекта 1.0.53: `assertFalse(realLine.contains("systemuiplugin"))` — прежний регистрозависимый `grep -F` эту строку не находил, поэтому `umount` не выполнялся ни разу.

---

## E. Климат: источники не подсовывать (P0/P1)

**E1.** `setprop persist.sys.hvacdigital.temp …` убран (`StatusIcons.kt:916-929`), значения пишутся только для `batt` и наружной:

```
// E1.1 (iter33h): setprop persist.sys.hvacdigital.temp УБРАН. Уставку климата плагин
// читает сам (com.flyme.auto.hvacapi.widget.HvacVehicleTempWidget.getPropertyId() →
// 358614275 == HVAC_TEMPERATURE_SET, area=1), а наше «значение» уходило в слот климата
```

Класс+метод из dex плагина (наш `res/raw/geely_auto_systemui_plugin_digital.apk`, `classes.dex` 6 799 536 Б, тот же, что `decompiled_flyme/resources/classes.dex`):

```
HvacVehicleTempWidget.java:578   public int getPropertyId() { return 358614275; }   // HVAC_TEMPERATURE_SET
HvacVehicleTempWidget.java:556   public int getAreaId()    { return 1; }
HvacVehicleTempWidget.java:599   f9 = p4.e.e(areaId);                                // load()
p4/e.java:132                    float floatProperty = d.f7576c.getFloatProperty(358614275, i8);
```

**E2.** Механика референса — bind-маунт своего плагина поверх OEM-пути и `pm path` (подтверждено в dex референса, script 9: `grep -F " $_DST " /proc/mounts` → `umount` в цикле → `mount --bind`, затем `PM_PATH=$(pm path …)`), у нас то же в `HvacDigitalPlugin.kt:190-200` (reflection-имя ресурса + staging) и `:788-830` (restore). Цифра рисуется плагином в элемент `tv_temp_digital` раскладки SystemUI:

```
HvacVehicleTempWidget.java:447   (identifier = …getIdentifier("tv_temp_digital", "id", …)) == 0 … return;
HvacVehicleTempWidget.java:450   ((TextView) findViewById).setText(str);
```

Приёмка «цифра **без** стандартной полоски под/за ней» — **🟡**: подтверждается только глазами владельца на устройстве (снимок экрана), машинного доказательства в песочнице нет.

**E3. Строка состояния — элемент за элементом**

| элемент референса (CentralEXAuto 3.3.9) | наш элемент | файл:строка | статус |
|---|---|---|---|
| батарея: значение с одним знаком (`%.1f`), флаг `persist.sys.hvacdigital.batt` | иконка `batt`, `socStr` через `fmtOneDecimal` | `tools/StatusIcons.kt:816-821`, `:924` | ✅ (значение), 🟡 (id свойства у референса в нативном «cofre» — не подтверждён) |
| наружная: `ENV_OUTSIDE_TEMPERATURE` + `%.1f` | значение `0x11600703` с area | `tools/StatusIcons.kt:391-435`, `outsideTemp` | ✅ |
| флаги цифрового слота: `temp`/`batt`/`exttemp` = `0\|1` | значения `batt` и `exttemp`, ключ `temp` не пишем | референс `w0/x7.java:138-146`; у нас `StatusIcons.kt:924` | 🟡 (расхождение осознанное — см. «Честные пробелы» п.3) |
| `persist.sys.hvacdigital.via` — механика install/bind, не элемент UI | пишем/читаем `via` (round-trip) | референс `w0/o.java:73`; у нас `StatusIcons.kt:942` | ⛔ как элемент UI (у референса его в строке нет) |
| климат (уставка) — рисует плагин по `HVAC_TEMPERATURE_SET` | плагин, приложение не вмешивается | `HvacVehicleTempWidget.java:578`; `StatusIcons.kt:916` | ✅ |
| Wi-Fi в строке состояния | иконка Wi-Fi (3 дуги + точка), notifId 15024 | `tools/StatusIcons.kt:876-880` | 🟡 у референса элемент машинно не подтверждён (регресс-зона не тронута) |

**E4.** Формат строки push (`StatusIcons.kt:942`) — без `temp=`:

```
HVACDIGITAL_PUSH rc=$rc batt=$bv exttemp=$ev via=${lastViaForLog()} out=…
```

---

## F. Наружная температура — источник как в референсе (P1)

Референс (`cex_out/sources/com/geely/hvacstepperv2/TempStepperService.java`): `291505923 == 0x11600703`, зоны `f1286d0 = {0, 1, 4, 7}`, float → int (−550…700 ⇒ /10, иначе −45…65), диапазон −55…70, печать `%.1f`:

```
354: public final Float i(int i3) {
357:     float floatProperty = this.f1298k.getFloatProperty(291505923, i3);
368:     int intProperty = this.f1298k.getIntProperty(291505923, i3);
299:     Log.i("HvacStepperV2", String.format("Exterior OK (ENV_OUTSIDE_TEMPERATURE) area=0x%x → %.1f°C", …))
```

Наш порт — `StatusIcons.kt:108-110`, `:427-435`, `OutsideTemp.kt:20-34`:

```
private const val PROP_ENV_OUTSIDE_TEMP = 0x11600703
private val ENV_OUTSIDE_AREAS = intArrayOf(0, 1, 4, 7)
…
AppLog.i(TAG, "Exterior OK (ENV_OUTSIDE_TEMPERATURE) area=0x%x → %.1f°C".format(outsideArea, outside))
```

Диагностика (в UI как истина не выдаётся) — `StatusIcons.kt:417`:

```
TEMP_SRC ambient=… invalid=… env_outside=… ambient_raw=…
```

Read-only действие «Температуры: разведка» — `StatusIcons.kt:461-518`, кнопка `ui/ToolsPanel.kt:282-286`, ключ `tempProbe_btn` в `tools/ToolsBackend.kt:978-983`. Строки (`TEMP_PROBE hex=0x… name=… area=… value=… err=…`), ID берутся только из фактов: `0x11600703`, `0x2140A377`, `0x2140A376`, `0x15600502`, `0x15600503`[49]/[68] — из VHAL-дампа (`uploads/vhal-dump.txt`, строки 50, 80, 988-989) и референса; для `AC_INSIDE_TEMP_INVALID`, `HVAC_IN_OUT_TEMP`, `HU_ENVIRONMENTAL_TEMP`, `TEMPERATURE_STATE` печатается `err=no_id` (hex не выдумываем).

Фрагмент дампа как доказательство источника:

```
id=291505923 hex=0x11600703 ENV_OUTSIDE_TEMPERATURE […|FLOAT…] access=READ change=2 areas=[0] values={0=25.0}
id=358614275 hex=0x15600503 HVAC_TEMPERATURE_SET […|FLOAT…] access=RW change=1 areas=[49, 68] values={49=IllegalArgumentException,68=IllegalArgumentException}
id=557884279 hex=0x2140A377 - […|INT32…] access=RW change=1 areas=[0] values={0=95}
id=557884278 hex=0x2140A376 - […|INT32…] access=RW change=1 areas=[0] values={0=1}
```

Один ключ наружной: `grep -rn "persist.sys.hvacdigital.temp_ext" app/src` → пусто; `PROP_EXT_TEMP = "persist.sys.hvacdigital.exttemp"` (`AdbClient.kt:99`).

---

## G. TTL (P1)

`TtlScript.kt:27` — в шелл уходит ЗНАЧЕНИЕ, а не имя переменной:

```
ENABLE=@ENABLE_VALUE@
```

подстановка — `:230`: `.replace("@ENABLE_VALUE@", enableBit)`; строка результата — `:228`:

```
TTL_RESULT rule_v4=… rule_v6=… verify_v4=… verify_v6=… wire_v4=… enable=…
```

`ROUTE_SRC=none reason=no_ip_binary` / `reason=no_default_route` — `TtlScript.kt:39`; `FAILED reason=rule_not_installed` — `NetworkTtl.kt:122`. Тесты `TtlScriptTest` (3) — `PASSED`.

---

## H. Бюджет лога (P1)

Измерение проблемы (мандат): 18:40–18:50 — 1 154 КБ при лимите 150 КБ; `checksum_skipped` 3 989, `ADB_TX … hex=` 280, `ICON_GEOM` 669, `PREF TRACE` 148.

Сделано:
- `LogKit.redactTrace` (`:454-461`) вырезает `hex= hex4= ascii= sum= calc=`; применяется к `last=` в `_SUPPRESSED` (`:399-404`). Тест `RedactTraceTest` (2) — `PASSED`.
- краткая/полная форма трассы: `LogKit.traceAdb(kind, brief, full)` (`:385`); полная — только `ADB_TRACE_REPLAY` и не более 3 строк (`REPLAY_MAX = 3`);
- рукопожатие — `LogKit.traceHandshake` (`:417`), первые 3 соединения за процесс, строка `ADB_HANDSHAKE ok=1 maxdata=… ver=…` (`AdbClient.kt:1221`);
- `checksum_skipped` — одна строка на команду (`AdbClient.kt:1899`, итог `:1032`);
- `ICON_GEOM` — только при изменении (`StatusIcons.kt:744-751`), иначе счётчик `LogKit.suppressed("ICON_GEOM")` (`:750`);
- `PREF TRACE` ≤5 на ключ за процесс (`ToolsBackend.kt:282-285`);
- `HVACDIGITAL_PUSH skip reason=unchanged` — не чаще 1/5 мин (`StatusIcons.kt:931-935`);
- жёсткий бюджет: `AppLog.kt:261-300` — `BUDGET_BYTES = 150 * 1024`, окно 10 мин, при превышении `LOG_BUDGET exceeded=… action=trim window=10min limit=153600` и далее одна сводка в минуту;
- `BUILD:` — `App.kt:22-24`: `commit=` первые 7 знаков настоящего SHA, `ci_run=` из `GITHUB_RUN_ID` (`app/build.gradle.kts:71`), при отсутствии — `unknown`.

---

## I. Хвосты ревью iter33g (P2)

1. `source=after_restart` — только первая попытка (`AppPerformance.kt:1057-1063`); повторы — `JIT_VERIFY: retry …`.
2. `JIT_VERIFY_ATTEMPTS_MAX` (`:701`), итог — `attempts=3 pauses=2 delay_ms=10000` (`:1080-1082`).
3. `currentBootTag()` кэширован (`:915-928`).
4. Корневые бинарники убраны (`git rm --cached`, `.gitignore`, `artifacts/`); источник плагина — `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk` (`HvacDigitalPlugin.kt:190-200`), корневой `HvacDigital-platform-signed.apk` в сборке не участвует (`grep -rn "HvacDigital-platform-signed" app/build.gradle.kts settings.gradle*` → пусто).
5. `claim none` — раз за процесс (`:1023-1025`).

---

## CI

| прогон | head | результат | причина |
|---|---|---|---|
| `42078312721` | `67c7213` | ❌ failure | компиляция: `LockSound.kt:957 Missing '}'`, `:1039 Unclosed comment` — в KDoc попало `avas/**`, что в Kotlin открывает **вложенный** блочный комментарий; плюс `sysPropFor` ссылался на удалённые `SYS_HVAC_TEMP`/`SYS_HVAC_EXT_TEMP_EXT` |
| `42080288285` | `c77da0b` | ❌ failure | `TempFormatTest.normalizeLikeReference`, `TempFormatTest.rejectsOutOfRange`, `VentOwnersTest.legacyKeepsDoorToWindowSync` — неверные ожидания в тестах (int −3 по правилам референса = −0.3 °C, а 200 = 20.0 °C; legacy-кейс строился на карте, где окно не связано со второй дверью) |
| **`42082578996`** | **`77191bc`** | **✅ success** | 43 `PASSED` / 0 `FAILED`, артефакт `13442117857` |

Тест-классы (реально исполнены, `PASSED` в логе прогона): `BootGateTest` (3), `MountParseTest` (3), `RedactTraceTest` (2), `TempFormatTest` (3), `TtlScriptTest` (3), `VentOwnersTest` (3), плюс прежние `JitBootMarkTest` (15), `HvacVerdictTest` (6), `ScriptChunkerTest` (4) — итого **43**.

**Размеры артефакта `13442117857` — раздельно** (команда: `unzip -l art.zip | awk`):

```
$ unzip -l art_h.zip | awk 'NR<=6 || /log|apk/'
215465513  2026-11-05 18:27   apk/debug/app-debug.apk
   105294  2026-11-05 18:24   logs/manifest-merger-debug-report.txt
215570807                     2 files
```

- zip артефакта: **177 100 457 Б**
- `apk/debug/app-debug.apk`: **215 465 513 Б**
- `logs/manifest-merger-debug-report.txt`: **105 294 Б**

---

## Честные пробелы

1. **Живой лог ГУ в этой итерации не снимался.** Все строки формата выше — из кода (формат-строки + `grep`), синтетика не выдаётся за факт. Пункты, требующие лога прогона (`Exterior OK … area=0x…` с числом, равным факту; `HVACDIGITAL_PUSH … exttemp=7.1`; `TTL_RESULT rule_v4=65`), помечены 🟡 в таблице E4 и должны быть подтверждены первым прогоном у владельца.
2. **E2 «цифра без полоски» — 🟡.** Машинно подтверждено только то, что цифру рисует сам плагин в элемент `tv_temp_digital` (`HvacVehicleTempWidget.java:436-453`) и что механика маунта у нас та же, что в референсе. Наличие/отсутствие стандартной полоски на экране проверяется только глазами.
3. **Расхождение по `persist.sys.hvacdigital.*`.** Референс `w0/x7.java:138-146` ставит `temp`/`batt`/`exttemp` как ФЛАГИ `0|1`; мандат E1.2/F1 требует от нас значений `%.1f` для `batt`/`exttemp` и запрещает `temp`. Я выполнил мандат (значения `%.1f`, `temp` не пишем). Если на устройстве цифровой слот климата окажется выключенным — причина в этом расхождении, а не в «придуманном источнике»; возвращать значение уставки нельзя (E1.3), лечится флагом.
4. **`AC_INSIDE_TEMP_INVALID`, `HVAC_IN_OUT_TEMP`, `HU_ENVIRONMENTAL_TEMP`, `TEMPERATURE_STATE`** — ID нет ни в VHAL-дампе, ни в референсе; в «разведке» печатается `err=no_id`, hex не выдуман.
5. **`AC_INSIDE_TEMP`** взят как `0x15600502` по референсу (`TempStepperService`, «Sensor cabina OK (HVAC_TEMPERATURE_CURRENT) area=0x%x»), в дампе этого ID нет — при прогоне может дать `err=`, это ожидаемо.
6. **Уставка климата: area.** Плагин читает `HVAC_TEMPERATURE_SET` с `areaId = 1` (`getAreaId()`), а в дампе у свойства `areas=[49, 68]`; проба в «разведке» идёт по 49 и 68. Расхождение не «лечим» — это факт устройства/плагина, отмечен для владельца.
7. **Wi-Fi и часть элементов строки состояния** у референса машинно не подтверждены (в dex референса нет соответствующего кода, который удалось бы однозначно сопоставить). Регресс-зона не тронута: notifId 15024, клик `android.settings.WIFI_SETTINGS`, дуги — как были.
8. **Бюджет ≤150 КБ/10 мин** реализован в коде (`AppLog`), но проверен только логикой, а не замером файла на устройстве: в песочнице лога ГУ нет. Первый прогон владельца должен показать либо отсутствие `LOG_BUDGET`, либо одну строку `LOG_BUDGET exceeded=… action=trim`.
9. **Кнопка «Тест звука открытия (AVAS 29)»** осталась в «Инструментах»: она не запускает процесс AVAS, а проигрывает наш wav через MediaPlayer (`UiBridge.kt:662-668`). Метка историческая; на поведение звука заднего хода не влияет.

---

## Что владельцу проверить (приёмка)

```bash
grep -ac "avas\|killall -9 audioserver" app.log        → 0
grep -a  "LOCKSOUND_BOOT" app.log                      → enabled=false action=none
grep -ac "state=NO_ROOT_SH" app.log                    → только после реальной потери, ≤1 в 60 с
grep -a  "ADB_STATE" app.log                           → один переход = одна строка
grep -a  "HVAC_BOOT" app.log                           → retry reason=root_back после возврата root
grep -a  "WINDOW_EVENT" app.log                        → result=closed на каждое закрытие двери
grep -ac "setprop persist.sys.hvacdigital.temp " app.log → 0
grep -a  "HVACDIGITAL_PUSH" app.log                    → exttemp=… batt=… via=… (без temp=)
grep -a  "Exterior OK (ENV_OUTSIDE_TEMPERATURE)" app.log → area=0x… значение ≈ факту
grep -a  "TEMP_SRC" app.log                            → ambient=… invalid=… env_outside=…
grep -a  "TTL_RESULT" app.log                          → rule_v4=65 rule_v6=65 verify_v4=OK
grep -ac "hex=" app.ops.log                            → 0 в строках *_SUPPRESSED
grep -a  "BUILD:" app.log | head -1                    → commit=<сегодняшний SHA>, versionCode=54
```

Глазами: в доке цифра уставки (29) **без** стандартной полоски; наружная ≈ приборке; строка состояния как в референсе; закрытие одной двери закрывает окно, которое эта дверь открыла; звук заднего хода не меняется; после сна — один раз «нет root-канала» и авто-восстановление климата/TTL/звука/видео после возврата root.
