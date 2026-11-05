# REPORT iter33i — 1.0.54 fix1 (props = флаги, tempProbe по реальным ID, плагин без полоски)

* Ветка: **`fix/v2`**, база `b355c45`, коммиты итога — `25344d2` (код) и `3b30ba6` (комментарии props).
* Версия **не поднималась**: `versionCode = 54`, `versionName = "1.0.54"` (`app/build.gradle.kts:62-63`).
* Сборка только в CI, `.github/workflows/build.yml` не правился, `main` не пушился, `--force` не применялся.

## 0. Сводка

| пункт | статус | файл:строка | доказательство |
|---|---|---|---|
| P0-a.1 семантика `persist.sys.hvacdigital.*` разобрана до однозначного ответа | ✅ | `cex_out/sources/w0/x7.java:137-147`, `w0/ah.java:17,32-60`, `w0/u5.java:27,342`, `w0/o.java:73,79` | §1: таблица `prop → смысл → что пишем`; `x7.i()` пишет `z2?"1":"0"`; наши props читает только плагин референса (его APK CEX валидирует по этим же строкам, `ah.java:17`) |
| P0-a.2 записи приведены 1:1 к референсу (флаги `0\|1`, значение уставки не подсовываем) | ✅ | `tools/HvacDigitalProps.kt:33-57`, `tools/StatusIcons.kt:896-930` | §1 «до/после»: было `setprop …temp 9.5` (`67c7213^:StatusIcons.kt:789`), стало ровно `x7.i()`-строка |
| P0-a.3 `via` — маршрут в чужой prop не пишется | ✅ | `tools/AdbClient.kt:1005-1015` | §1, `grep -rn "persist.sys.hvacdigital.via" app/src` — в `setprop` только `bind`/`nenhum` из mount-скрипта (`HvacDigitalPlugin.kt:316,319`), значения `app`/«Домой» нет |
| P0-a.4 `@Test` «наше состояние → строка setprop» | ✅ | `app/src/test/java/com/example/voiceapp3/tools/HvacDigitalPropsTest.kt` | 5 тестов: 4 состояния × точная строка, «нет 9.5/71», fingerprint, `isFlagValue`, «via не пишем» |
| P0-b цифра уставки без стандартной полоски | ✅ (код) / ⚠ runtime не проверен | `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk` (пересобран), smali `HvacVehicleTempWidget` | §2: цитаты smali до/после, размеры, подпись platform-ключом; скрин — за владельцем |
| P1 `tempProbe` с настоящими ID, `noId()` удалён | ✅ | `tools/TempProbeTable.kt:39-97`, `tools/StatusIcons.kt:441-497` | §3: 10 свойств / 18 строк, `err=` только результат чтения |
| P1 `@Test` на таблицу id (hex↔id↔имя по CSV) | ✅ | `app/src/test/java/com/example/voiceapp3/tools/TempProbeTableTest.kt` | 7 тестов, эталон — `app/src/main/assets/ex2_carprops.csv` |
| P2.1 старые комментарии про «заглушку 0x11600703» вычищены | ✅ | `tools/StatusIcons.kt:83`, `tools/StatusIcons.kt:117-118` | §4 |
| P2.2 кнопка «Тест звука открытия (AVAS 29)» → «Тест звука открытия» | ✅ | `ui/ToolsPanel.kt:795` | §4, поведение `dev.testAvasSound`/MediaPlayer не менялось (`ui/UiBridge.kt:663-667`) |
| P2.3 вторая фикстура `MountParseTest` (`/system/priv-app/…`) | ✅ | `app/src/test/java/…/MountParseTest.kt:50-64` | §4 |
| CI | ✅ | — | §6 |

## 1. P0-a — семантика `persist.sys.hvacdigital.*`

### 1.1 Кто пишет и кто читает (цитаты)

**Пишет — референс CentralEXAuto 3.3.9**, `cex_out/sources/w0/x7.java:137-147`, метод `i(boolean z2, boolean z3, boolean z4)`:

```java
public static boolean i(boolean z2, boolean z3, boolean z4) {
    StringBuilder sb = new StringBuilder("setprop persist.sys.hvacdigital.temp ");
    sb.append(z2 ? "1" : "0");
    sb.append(" && setprop persist.sys.hvacdigital.batt ");
    sb.append(z3 ? "1" : "0");
    sb.append(" && setprop persist.sys.hvacdigital.exttemp ");
    sb.append(z4 ? "1" : "0");
    sb.append(" && echo FLAGS_OK");
```

Три prop — **флаги `0|1`** (включён ли соответствующий элемент в цифровом слоте), порядок `temp → batt → exttemp`, проверка успеха — `grep FLAGS_OK`.

**Читает — плагин, а не наше приложение.** Доказательство, что эти строки живут в APK плагина референса: CEX валидирует скачанный плагин поиском ровно этих байтовых строк внутри его zip-записей —
`cex_out/sources/w0/ah.java:17`:

```java
List<String> U = y1.f.U(new String[]{"persist.sys.hvacdigital.temp", "persist.sys.hvacdigital.batt", "persist.sys.hvacdigital.exttemp"});
```
и далее (`ah.java:35-60`) каждый вход APK читается и просматривается на вхождение этих байтов (`f3375a`) — «свой» плагин опознаётся по ним.

Второе чтение — восстановление/сброс состояния в «oficina»: `cex_out/sources/w0/u5.java:27`

```java
public static final List f4370c = y1.h.V("persist.sys.hvacdigital.temp", "persist.sys.hvacdigital.batt", "persist.sys.hvacdigital.exttemp");
```
и `u5.java:342-…` — итерация по `f4370c` в скрипте восстановления (сброс флагов в исходное состояние).

**Наш плагин эти props не читает вообще.** Проверка по нашему `res/raw`-APK (до правки): 2714 записей, `classes.dex` 6 799 536 Б, поиск `b'hvacdigital'` по каждой записи → **0 вхождений** (`python3`: `entries with hvacdigital: []`). Тот же ноль — по декомпиляции `plugin_out/sources` и `decompiled_flyme`. Значит: значения в этих prop наш слот не рисуют и рисовать не могут, а «наложение цифры на полоску» имеет другую причину (§2), и она в коде плагина, а не в prop.

**`via` — механизм установки плагина**, `cex_out/sources/w0/o.java:73,79`:

```java
String M3 = y7.M("persist.sys.hvacdigital.via");
...
if (str.equals("bind")) {
    V = "echo \"VIA_FORCADA=bind — pulando o pm install\"\n_FALHOU=1";
```
значение — `bind` либо `install` (ветки `setprop …via install` / `setprop …via bind` в том же скрипте). Никакого «маршрута»/«Домой» в этой prop у референса нет и быть не может.

### 1.2 Что значит «мусорное» значение (`9.5`)

У референса флаг проверяется на равенство `"1"`/`"0"`; `9.5` не равно ни тому, ни другому ⇒ элемент режима читается как **выключенный** (флаг не активен), т.е. значение-число в этих prop не «включает цифру», а ломает переключатель. На наш плагин это не влияло вовсе (он их не читает). Поэтому 1.0.53 (`temp=9.5`, `exttemp=9.5`, `batt=71`) не включала цифровой режим, а просто мусорила в чужом пространстве свойств — и, как следствие, режим слота оставался «полоска + цифра» одновременно.

### 1.3 Таблица `prop | смысл у референса | что пишем мы | почему`

| prop | смысл у референса (класс.метод + цитата) | что пишем мы (iter33i) | почему |
|---|---|---|---|
| `persist.sys.hvacdigital.temp` | флаг цифрового климат-слота, `w0.x7.i`: `sb.append(z2 ? "1" : "0")` (`x7.java:138-139`) | `0\|1` по тумблеру «Цифровой климат-контроль» (`PREF_HVAC_TEMP`), значение уставки — **нет** | 1:1 к референсу; уставку плагин читает сам (`HvacVehicleTempWidget.getPropertyId() → 358614275`, area 1) |
| `persist.sys.hvacdigital.batt` | флаг «% заряда», `x7.java:140-141` | `0\|1` (было `71`) | флаг, не число |
| `persist.sys.hvacdigital.exttemp` | флаг наружной, `x7.java:142-143`; единственный ключ (`ah.java:17`, `u5.java:27`) | `0\|1` (было `9.5`); `temp_ext` не пишем вовсе | флаг, не число; второй ключ — фантом 1.0.52 |
| `persist.sys.hvacdigital.via` | механизм установки плагина, `w0.o`: `setprop …via install` / `via bind` (`o.java:73-79,96-118`) | **только** `bind`/`nenhum` из mount-скрипта (`HvacDigitalPlugin.kt:316,319`); `app`/«Домой» — не пишем | prop принадлежит установщику; маршрутная пилюля — наша UI-функция, её место в наших префах |

### 1.4 До/после в коде приложения

Было (1.0.53, `67c7213^:app/src/main/java/com/example/voiceapp3/tools/StatusIcons.kt:785-800`):

```kotlin
val bv = if (battOn) fmt(lastBattPct) ?: return else "0"
sb.append("setprop $SYS_HVAC_BATT $bv; ")
val tv = if (tempOn) fmt(lastTempC) ?: return else "0"
sb.append("setprop $SYS_HVAC_TEMP $tv; ")
val ev = if (extOn) fmt(lastExtTempC) ?: return else "0"
sb.append("setprop $SYS_HVAC_EXT_TEMP_EXT $ev; setprop $SYS_HVAC_EXT_EXTTEMP $ev; ")
```
(`fmt` = `String.format(Locale.US, "%.1f", v)` — то есть «9.5» и «71».)

Стало (`tools/HvacDigitalProps.kt:42-47`, вызов — `tools/StatusIcons.kt:899-903`):

```kotlin
fun flagsCommand(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String {
    val t = if (tempOn) "1" else "0"
    val b = if (battOn) "1" else "0"
    val e = if (extOn) "1" else "0"
    return "setprop $PROP_TEMP $t && setprop $PROP_BATT $b && setprop $PROP_EXT $e && echo FLAGS_OK"
}
```
Строка совпадает с `w0/x7.i()` символ в символ (порядок, `&&`, `FLAGS_OK`); обратное чтение в лог — `temp=… batt=… exttemp=… via=…` (`StatusIcons.kt:917-926`).

`via` в приложении — `tools/AdbClient.kt:1005-1015`: было `setprop $PROP_VIA app; getprop $PROP_VIA` (значение «app» в чужой prop), стало только `getprop` + запись в лог `EXEC_PROP via=read only value=… (не пишем: проп принадлежит плагину)`.

### 1.5 `grep -n "SYS_HVAC_" tools/StatusIcons.kt` после правки

```
58:    private const val SYS_HVAC_BATT = HvacDigitalProps.PROP_BATT
63:    private const val SYS_HVAC_VIA = HvacDigitalProps.PROP_VIA                    // только ЧТЕНИЕ (диагностика)
65:    private const val SYS_HVAC_EXT_EXTTEMP = HvacDigitalProps.PROP_EXT              // F2: единственный ключ наружной
890:        Runtime.getRuntime().exec(arrayOf("getprop", SYS_HVAC_VIA)) …
977:        PREF_HVAC_BATT -> SYS_HVAC_BATT
978:        PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT_EXTTEMP
984:    private val SYS_HVAC_EXT_ALL = arrayOf(SYS_HVAC_EXT_EXTTEMP)
```
`SYS_HVAC_TEMP` как отдельной константы больше нет: флаг `temp` собирается в `HvacDigitalProps.flagsCommand` (единый источник имён).

### 1.6 `grep -rn "persist.sys.hvacdigital.via" app/src` — маршрут в prop не пишется

```
app/src/main/java/com/example/voiceapp3/tools/HvacDigitalProps.kt:29   (комментарий, ссылка на o.java:73)
app/src/main/java/com/example/voiceapp3/tools/HvacDigitalProps.kt:36   const val PROP_VIA = "persist.sys.hvacdigital.via"
app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt:100         private const val PROP_VIA = …
app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt:1008        комментарий: только чтение
app/src/main/java/com/example/voiceapp3/tools/StatusIcons.kt:888       комментарий: только чтение (getprop)
app/src/main/java/com/example/voiceapp3/tools/HvacDigitalPlugin.kt:316  setprop …via bind      ← механизм установки (как o.java)
app/src/main/java/com/example/voiceapp3/tools/HvacDigitalPlugin.kt:319  setprop …via nenhum    ← сброс установщика
app/src/main/java/com/example/voiceapp3/tools/HvacDigitalPlugin.kt:578,586,600  комментарии/`getprop` (диагностика)
app/src/test/java/com/example/voiceapp3/tools/HvacDigitalPropsTest.kt:77-78   тест «via не пишем»
```
Ни `app`, ни «Домой», ни одна маршрутная строка в `setprop` не встречаются. `bind`/`nenhum` пишет только mount-скрипт — это ровно язык референса (`install`/`bind`).

## 2. P0-b — полоска: цифра вместо неё

### 2.1 Что было (цитаты)

Раскладка слота, `res/layout/widget_vehicle_temp.xml` нашего плагина:

* строка 11: `<com.flyme.auto.hvacapi.widget.TemperatureView android:id="@id/tv_temp_layout" android:visibility="gone" …>` — стандартная **полоска**;
* строка 12: `<TextView android:id="@id/tv_temp_digital" … />` — **цифра**; обе view сидят на одних и тех же констрейнтах (`layout_constraintLeft/Right/Top/BottomTo…Of="0"`, `@dimen/x_132`), то есть занимают один и тот же слот.

Код, `plugin_out/sources/com/flyme/auto/hvacapi/widget/HvacVehicleTempWidget.java`:
* `:436-453` `updateDigitalTemp()` — `((TextView) findViewById).setText(str);` (цифра пишется в `tv_temp_digital`, которая видима всегда);
* `:455-465` `updateTextStyle()` — `this.tvTemp.setVisibility(0);` (полоска **принудительно показывается**, хотя в XML стоит `gone`);
* `:700-706` второй такой же блок (обработчик обратной связи CarService).

Смолы до правки (`smali/com/flyme/auto/hvacapi/widget/HvacVehicleTempWidget.smali`): `1587: invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V` и `2263: invoke-virtual {p1, v1}, …` где `const/4 v1, 0x0` = `VISIBLE`, `const/16 v2, 0x8` = `GONE`.

**Вывод:** цифра и полоска рисуются одновременно в одном слоте — это и есть наложение. Причина не в props: наш APK не содержит строк `hvacdigital` (0 вхождений, §1.1).

### 2.2 Что сделано (diff)

```diff
- invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V      # v1 = 0x0 VISIBLE (полоска)
+ invoke-virtual {v0, v2}, Landroid/view/View;->setVisibility(I)V      # v2 = 0x8 GONE
...
- invoke-virtual {p1, v1}, Landroid/view/View;->setVisibility(I)V
+ invoke-virtual {p1, p2}, Landroid/view/View;->setVisibility(I)V      # p2 = 0x8 GONE
```
Проверка после пересборки (дизассемблирование подписанного APK): `updateTextStyle` — `tvTemp → v2` в обеих ветвях, `tvOff` — `v2` (HVAC вкл) / `v1` (HVAC выкл); второй блок — `p1,p2`. То есть полоска не показывается никогда, цифра (`tv_temp_digital`) остаётся видимой.

### 2.3 Сборка и подпись (числа)

| шаг | инструмент | результат |
|---|---|---|
| декомпиляция | `apktool 2.9.3` (`d`) | 2714 ресурса, smali 80 МБ |
| пересборка | `apktool b --use-aapt2`, `-Xmx950m` (при `-Xmx3g` процесс убит OOM: в песочнице 1.9 ГБ RAM) | 18 069 174 Б без подписи |
| выравнивание | `zipalign -p -f 4` | — |
| подпись | `apksigner sign --key platform.pk8 --cert platform.x509.pem` (v1+v2+v3, ключ `work10/platform-keys/`) | 18 276 066 Б |

* Размер файла: **18 276 066 Б** — как у исходного (совпадение до байта по размеру, хеши разные: md5 `09184897…` → `993a518b…`).
* `classes.dex`: **6 799 536 → 6 799 912** Б (+376).
* `resources.arsc`: **3 934 708 → 3 934 708** Б — не менялся; `AndroidManifest.xml`: **11 424 → 11 424** Б.
* Записей в zip: **2714 → 2714**.
* Подпись: `Signer #1 certificate SHA-256 digest: c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` — та же, что у прежнего файла (`apksigner verify --print-certs`) и совпадает с `platform.x509.pem` (`sha256 Fingerprint=C8:A2:E9:…:2A:B8`).
* Имя ресурса (`res/raw/geely_auto_systemui_plugin_digital.apk`) и reflection-имя не менялись: `grep -n "geely_auto_systemui_plugin_digital" tools/HvacDigitalPlugin.kt` — строки не тронуты.

Файлы:
* `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk` — пропатченный подписанный APK (в git);
* `/home/user/artifacts/geely_auto_systemui_plugin_digital_1.0.53-stock.apk` — **исходный** (для откатов), вне git;
* `/home/user/artifacts/geely_auto_systemui_plugin_digital_1.0.54-i33i-patched-signed.apk` — пропатченный, вне git.

### 2.4 Что НЕ доказано (честно)

1. **APK плагина референса получить не удалось.** CEX тянет его с `https://raw.githubusercontent.com/swimapps/Outros/main/GeelyAutoSystemUIPlugin-digital.apk` (`cex_out/sources/w0/u.java:96`); на 05.11.2026 URL отдаёт `HTTP/2 404` (проверено `curl -sI`), в песочнице этого APK нет. Поэтому «как именно референс прячет полоску» цитатой из его dex подтвердить нельзя — подтверждено только, что его плагин содержит строки `persist.sys.hvacdigital.*` (§1.1), т.е. переключает режим по флагам.
2. **Поведение на устройстве не проверено** — ГУ в песочнице нет; приёмка «цифра без полоски» подтверждается только скриншотом владельца.
3. **Побочный эффект осознанно принят:** с патчем полоска не показывается никогда (цифра заполняется в `updateDigitalTemp` независимо от тумблера). Если нужен переключаемый режим «полоска ↔ цифра», это делается только в APK плагина референса (или отдельной правкой smali с чтением флага). Откат — вернуть stock-APK из `artifacts/`.

## 3. P1 — «Температуры: разведка» с настоящими ID

Таблица вынесена в `tools/TempProbeTable.kt:39-91` (данные) и `tempProbeLine()` (`:93`), сам проход — `tools/StatusIcons.kt:441-497`. `noId()` удалён, строк `err=no_id` больше не существует; `err=` — только результат чтения (`none`, `none(int)`, `none(float)`, имя исключения).

| `name=` (как в CSV) | hex | id | зоны | тип | источник |
|---|---|---|---|---|---|
| `ENV_OUTSIDE_TEMPERATURE` | `0x11600703` | 291505923 | 0,1,4,7 | FLOAT | `ex2_carprops.csv:45` + дамп + `TempStepperService.f1286d0={0,1,4,7}` |
| `AC_AMBIENT_TEMP` | `0x2140A377` | 557884279 | 0 | INT32 | `csv:985`, дамп `0=95` |
| `AC_AMBIENT_TEMP_INVALID` | `0x2140A376` | 557884278 | 0 | INT32 | `csv:984`, дамп `0=1` |
| `AC_INSIDE_TEMP` | `0x2140A379` | 557884281 | 0 | INT32 | `csv:987`, дамп `0=0` |
| `AC_INSIDE_TEMP_INVALID` | `0x2140A378` | 557884280 | 0 | INT32 | `csv:986`, дамп `0=0` |
| `HVAC_TEMPERATURE_CURRENT` | `0x15600502` | 358614274 | 1,4,0,7 | FLOAT | референс `TempStepperService` («Sensor cabina OK (HVAC_TEMPERATURE_CURRENT) area=0x%x») |
| `ZONED_TEMP_SETPOINT` | `0x15600503` | 358614275 | 1,49,68 | FLOAT | `csv:75` + дамп `areas=[49,68]` + плагин `getAreaId()→1` |
| `HVAC_IN_OUT_TEMP` | `0x2140101D` | 557846557 | 0 | INT32 | `csv:124`, дамп `0=0` |
| `HU_ENVIRONMENTAL_TEMP` | `0x21404515` | 557860117 | 0 | INT32 | `csv:453`, дамп `0=0` |
| `TEMPERATURE_STATE` | `0x2140A267` | 557884007 | 0 | INT32 | `csv:876`, дамп `0=0` |

18 строк на проход (4+1+1+1+1+4+3+1+1+1) + строка-подсказка. Пример строки (формат проверен тестом):

```
TEMP_PROBE hex=0x2140A379 name=AC_INSIDE_TEMP area=0x0 value=0 err=none
TEMP_PROBE hex=0x15600503 name=ZONED_TEMP_SETPOINT area=0x44 value=н/д err=IllegalArgumentException
```

Отклонение от текста мандата (осознанное): `0x15600503` названо `ZONED_TEMP_SETPOINT`, а не `HVAC_TEMPERATURE_SET` — так это имя в эталоне машины `ex2_carprops.csv:75`, а мандат требует писать `name=` как в CSV. Тест `TempProbeTableTest.everyEntryHasKnownIdAndCsvName` сверяет hex↔id↔имя с CSV программно; `csvIdsForMandateRows` отдельно фиксирует пять «мандатных» ID.

Тесты (`TempProbeTableTest`, 7 шт.): уникальность имён и непустые зоны; hex↔id; id↔CSV; «`AC_INSIDE_TEMP` ≠ `HVAC_TEMPERATURE_CURRENT`»; зоны уставки `{1,49,68}`; зоны наружной `{0,1,4,7}`; формат строки без `no_id`.

## 4. P2 — мелочи

1. `tools/StatusIcons.kt:83` — было `// 0x11600703 в этом списке нет: это заглушка прошивки (25.0), источник — 0x2140A377.`, стало `// 0x11600703 в этом списке не нужен: наружная читается отдельно, с зонами (см. readTempScan).`
   `tools/StatusIcons.kt:117-118` — было `// 0x11600703 (ENV_OUTSIDE_TEMPERATURE в прошивке) — ЗАГЛУШКА: … 25.0 …`, стало `// Резервные 0x2160A6E9/0x2140A673 и ambient — только диагностика; источник наружной — ENV_OUTSIDE_TEMPERATURE 0x11600703 с зонами (iter33h F1, итерация 33i ничего не меняет).`
   Противоречий с F1 в файле не осталось: `grep -n "ЗАГЛУШКА" tools/StatusIcons.kt` → пусто.
2. `ui/ToolsPanel.kt:795`: `soundButton("Тест звука открытия (AVAS 29)", 1)` → `soundButton("Тест звука открытия", 1)`. Обработчик тот же (`dev.testAvasSound`), `UiBridge.kt:663-667` не менялся.
3. `MountParseTest.kt:50-64` — новая фикстура `…userdata /system/priv-app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk ext4 rw,seclabel,…` (как в логе машины): `isOurMountLine` = true, `firstOurMountLine` возвращает её, `countOurMounts` на двух строках = 2, `umountCountOf` = 1.

## 5. Вопросы владельцу

1. **Маршрутная пилюля** (бывш. `via` в чужом prop): оставить её в нашем UI (в наших префах) или убрать совсем — в референсе её нет. Сейчас в prop не пишется.
2. **Полоска**: после установки этой сборки — скрин слота климата (цифра уставки без полоски). Если полоска всё же видна — пришлите скрин, буду искать дальше (в т.ч. другой виджет/раскладку SystemUI).

## 5.1 Что не трогали (принято в iter33h, мандат iter33i §0)

LockSound (`/vendor/etc/carlock/*.wav`), автомат `RootState`, `bootGateDecision`, шаг 0 только для legacy, `MOUNT_GREP`, убранный temp-push, порт `ENV_OUTSIDE_TEMPERATURE` `{0,1,4,7}` + `%.1f` (`tools/OutsideTemp.kt`), TTL `ENABLE=@ENABLE_VALUE@`, бюджет лога, `redactTrace`, JIT. Регресс-зоны (Wi-Fi `notifId 15024`/`WIFI_SETTINGS`/дуги, батарея-дробь `83.1`, TTL-тумблер, видео 60 fps) не менялись.

## 6. CI

* Прогон: **`42090458736`**, коммит **`3b30ba6`**, job `127467298483` (`build`) — **success**, `BUILD SUCCESSFUL in 4m 7s`.
* Шаги job: `Gradle build (debug)` (внутри `> Task :app:testDebugUnitTest`), `Upload APK` — оба success. `build.yml` не менялся.
* Тесты: **56 PASSED / 0 FAILED** (`grep -ac "PASSED"` = 56, `grep -a "FAILED"` = пусто). Было 43 в iter33h, +13 новых: `HvacDigitalPropsTest` — 5 (`allStatesMappingToFlags`, `noTemperatureValueEverWritten`, `fingerprintIsStableAndFlagBased`, `isFlagValueRejectsNumbers`, `viaIsNeverWrittenByApp`), `TempProbeTableTest` — 7 (`everyEntryHasKnownIdAndCsvName`, `noDuplicatesAndNoPlaceholders`, `acrossTempAndCurrentTempAreSeparateRows`, `setpointReadByThreeAreasFromReferenceAndDump`, `outsideTempUsesReferenceAreas`, `lineFormatHasNoNoId`, `csvIdsForMandateRows`), `MountParseTest` — +1 (`privAppMountLineIsOurMount`).
  Остальные зелёные: `BootGateTest` 3, `HvacVerdictTest` 6, `JitBootMarkTest` 15, `MountParseTest` 3 (было), `RedactTraceTest` 2, `ScriptChunkerTest` 4, `TempFormatTest` 3, `TtlScriptTest` 3, `VentOwnersTest` 3.
* Артефакт: **`13445912750`** (`app-debug`). Три размера раздельно (`stat -c%s` для zip, `unzip -l art_i.zip | awk '/app-debug.apk/{print "apk",$1} /manifest-merger/{print "report",$1}'`):

| файл | байт | команда |
|---|---|---|
| zip артефакта | **176 999 349** | `stat -c%s art_i.zip` (и `size_in_bytes` из API) |
| `apk/debug/app-debug.apk` | **215 470 097** | `unzip -l art_i.zip \| awk '/app-debug.apk/'` |
| `logs/manifest-merger-debug-report.txt` | **105 294** | `unzip -l art_i.zip \| awk '/manifest-merger/'` |

* Строка приложения при старте (`App.kt:23-24`): `BUILD: versionCode=54 versionName=1.0.54 commit=3b30ba6 ci_run=42090458736` (SHA 7 знаков из `BuildConfig.GIT_COMMIT`, номер прогона — `GITHUB_RUN_ID`, `app/build.gradle.kts:67,71`).
* Предыдущие прогоны этой итерации: `42089523005` (`25344d2`) — `cancelled` (новый push вытеснил его; это норма для workflow_dispatch+push), `42085172202` (`b355c45`) — success (база).


## 7. Честные пробелы

1. APK плагина референса недоступен (404) ⇒ «как референс прячет полоску» цитатой не подтверждено; подтверждено обратное — что он читает флаги (§1.1) и что наш APK их не читает.
2. Патч плагина проверен статически (smali до/после, размеры, подпись, дизассемблирование результата), но **не** на устройстве.
3. `bind`-mount плагина на живом ГУ не проверялся; откат описан в §2.3.
4. Семантика значений у *референсного* плагина (что он делает при `9.5`) выведена из сравнений на `"1"`/`"0"` в `x7.i()` и из отсутствия числовых записей у референса; прямой цитаты «else ⇒ off» из его dex нет (APK недоступен).
