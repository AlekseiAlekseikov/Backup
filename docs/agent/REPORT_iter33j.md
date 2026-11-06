# REPORT iter33j — 1.0.55 (плагин референса как есть, честный маунт, температура по эталону, JIT без zygote)

* Ветка **`fix/v2`**, база `3b30ba6` (iter33i). Коммиты: `b767e5f` (основное) → **`5dcf60b`** (фикс сборки: вернул удалённые хелперы, кавычки, `${}`→`@@{}` в скрипте).
* Версия поднята: **`versionCode = 55`**, **`versionName = "1.0.55"`** (`app/build.gradle.kts:67-68`) — 1.0.54 установлена и проверена владельцем 06.11.
* `.github/workflows/build.yml` не менялся, `main` не пушился, `--force` не применялся, сборка — только CI.
* **Правило владельца 06.11 (высший приоритет) соблюдено:** в системе ничего не правится. В этой итерации **нет** ни одного smali/apktool-патча; приложение только монтирует готовый бинарь референса и пишет флаги `0|1`. Откат = `umount`/снятие флага/удаление приложения.

## 0. Сводка

| пункт | статус | файл:строка | доказательство |
|---|---|---|---|
| 0. Правило «только монтирование» | ✅ | весь диф `b355c45…5dcf60b` | §2: в дереве нет ни `smali`, ни `apktool`, ресурс заменён на бинарь референса (sha256 сверен) |
| P0.1 Плагин референса скачан, sha256 сверен, лежит в проекте без правок | ✅ | `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk` | §1: `sha256 = de4b4742…4d24d0fd03`, 18 280 162 Б |
| P0.2 Маунт: свежий `umount` → `mount --bind` → inode **и** md5, вердикт FAILED при расхождении | ✅ (код) | `tools/HvacDigitalPlugin.kt:329-380` | §2.1: строка `HVAC_MOUNT_RESULT mount=… inode_match=… md5_match=… verdict=…` |
| P0.3 Стейдж `/data/local/hvac-digital/sysui.apk` + CONFLICT без молчаливой перезаписи | ✅ | `tools/HvacDigitalPlugin.kt:262-278` | §2.2 |
| P0.4 `HVAC_PROBE`/`checkStatus` опознают плагин по размеру/md5 | ✅ | `HvacDigitalPlugin.kt:167-176` (`checkStatus`), `:436-449` (`enable`), `:474-479` (`HVAC_PROBE`) | §2.3 |
| P0.5 `@Test` на разбор `HVAC_MOUNT_RESULT` (FAILED при `md5_match=false`) | ✅ | `app/src/test/java/…/MountResultTest.kt` (8 тестов) | §2.4 + §6 (имена PASSED) |
| P0/P1 Слоты batt%/наружная/климат по тумблерам | 🟡 (до владельца) | `StatusIcons.kt:896-930` (флаги не тронуты) | §3: флаги не менялись; оживление слотов = установка плагина + взгляд владельца |
| P1 Температура: `AC_AMBIENT_TEMP` (raw−80)/2, `ENV_OUTSIDE` — только fallback, `Locale.US` | ✅ (код) | `OutsideTemp.kt:14,17`; `StatusIcons.kt:397-433` | §4: `TEMP_DECODE raw=86 → 3.0°C (formula (raw-80)/2 source=AC_AMBIENT_TEMP)` |
| P1 `@Test` на decode + «fallback только при ошибке» | ✅ | `app/src/test/java/…/TempDecodeTest.kt` (5 тестов) | §4.2 |
| P1.4 JIT: `ctl.restart zygote` удалён вообще (включая boot) | ✅ | `AppPerformance.kt:213-266`, `:824-864` | §5.1: `grep -rn "ctl.restart zygote" app/src` → пусто |
| P1.4 Раунд оптимизации `JIT_ROUND` вместо `compile_all_apps` | ✅ | `AppPerformance.kt:533-590` | §5.2 |
| P1.4 `-m speed` — отдельная кнопка с предупреждением | ✅ | `AppPerformance.kt:664-688`, `ToolsPanel.kt:820` | §5.2 |
| P1.4 Восстановление: `ctl.restart adbd` (одна попытка + проба `id`), `ctl.restart CarplayMonitor` | ✅ | `AppPerformance.kt:594-663`, `ToolsPanel.kt:821-822`, `UiBridge.kt:717-724` | §5.3 |
| P1.4 BUILD-строка: реальный SHA из CI, иначе `unknown` | ✅ (код) | `app/build.gradle.kts:39-52`, `App.kt:22-24` | §5.4 |
| P1.5 Вычистка числового мусора в `persist.sys.hvacdigital.*` | ✅ (код) | `tools/PersistCleaner.kt`, `App.kt:42` | §5.5 |
| P1.6 `SOC null`/`no CarPropertyManager` на старте → `VHAL_WAIT`; `/proc/uptime` | ✅ | `StatusIcons.kt:795-806`, `BootReceiver.kt:44`, `ScreenBrightness.kt` | §5.6 |
| CI | ✅ | — | §6 |

## 1. P0.1 — плагин референса, без правок

```
$ sha256sum app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk
de4b47422cf7b4b90385dd0dc371cc5c6b3c9a3928b5bd8e2bf00b4d24d0fd03  app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk
$ stat -c %s app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk
18280162
$ md5sum app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk
2fa3d11d3a5350cd4a427cc69617482b  app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk
```
Источник — манифест `hvacdigital.json` CentralEXAuto (коммит `99c4fe0ce61b786e25e67b1f85df14db365994a9`), ссылка и sha256 из мандата совпали с фактическими.

**Патчей нет — доказательство:**
```
$ git diff --stat b355c45 HEAD -- app/src/main/res/raw/
 .../res/raw/geely_auto_systemui_plugin_digital.apk | Bin 18276066 -> 18280162 bytes
 1 file changed, 0 insertions(+), 0 deletions(-)
$ git ls-files | grep -E "smali|apktool"
(пусто)
```
То есть байты ресурса заменены целиком на опубликованный бинарь референса (18 276 066 → 18 280 162 Б), никакой декомпиляции/пересборки в этой итерации не было.

«До/после» вне git (`/home/user/artifacts/`):
* `geely_auto_systemui_plugin_digital_1.0.53-stock.apk` — заводской OEM-плагин (был в 1.0.53);
* `geely_auto_systemui_plugin_digital_1.0.54-i33i-smalipatched-signed.apk` — патч iter33i (полоска `GONE`); **больше не используется** — smali-патчи запрещены.

Состав бинаря референса (проверка dex, что это именно он): `BatterySocView` ×5, `DockOSDTemperatureView` ×1, `HvacDigitalHelper` ×2, `HVAC_TEMPERATURE_SET` ×1, `358614275` ×2; `persist.sys.hvacdigital` — **0 вхождений** (подтверждает п.0.1.3 мандата: флаги читает сам SystemUI, плагин только поставляет виджеты).

## 2. P0.2-P0.5 — маунт: честный вердикт

### 2.1 Скрипт (`HvacDigitalPlugin.kt:329-380`)

Порядок ровно по мандату: **свежий umount** (`UMOUNT_COUNT`) → `mount --bind` → сверка **inode и md5 обоих концов** → одна строка вердикта:

```
SRC_INODE=$(stat -c '%i' $STAGE_APK); TGT_INODE=$(stat -c '%i' "$TGT")
SRC_MD5_NOW=$(md5sum $STAGE_APK | awk '{print $1}'); TGT_MD5_NOW=$(md5sum "$TGT" | awk '{print $1}')
INODE_MATCH=false; [ "$SRC_INODE" = "$TGT_INODE" ] && INODE_MATCH=true
MD5_MATCH=false;   [ "$SRC_MD5_NOW" = "$TGT_MD5_NOW" ] && MD5_MATCH=true
VERDICT=FAILED; [ "$MOUNTED" = "1" ] && [ "$INODE_MATCH" = "true" ] && [ "$MD5_MATCH" = "true" ] && VERDICT=OK
echo "HVAC_MOUNT_RESULT mount=$MOUNTED inode_match=$INODE_MATCH md5_match=$MD5_MATCH verdict=$VERDICT"
```
При `verdict=FAILED` — `setprop …via nenhum`, `HVAC_DIGITAL_FALHOU`, `exit 3`. Старый баг «OK при `inode:false`» закрыт: в Kotlin вердикт берётся **только** из этой строки (`HvacDigitalPlugin.kt:431-449`), а `HvacVerdict` больше не решает, смонтировано ли.

### 2.2 Стейдж и чужой файл (`HvacDigitalPlugin.kt:262-278`)

```
STAGE_MD5 old=… new=… path=/data/local/hvac-digital/sysui.apk
CONFLICT другой файл, не затираю молча: /data/local/hvac-digital/sysui.apk old=… new=… action=replace_by_owner_click
STAGE_COPY done md5=…
```
Системные файлы не трогаются ни при каких условиях — только `mount --bind` поверх (`cp` идёт лишь в `/data/local/hvac-digital/`).

### 2.3 Опознание по содержимому

`PLUGIN_APK_SIZE = 18_280_162L`, `PLUGIN_APK_MD5`, `PLUGIN_APK_SHA256` (`HvacDigitalPlugin.kt:27-29`). `checkStatus` читает `stat -c %s` точки монтирования (дёшево, вызывается часто) → `pluginContentOk`; `HVAC_PROBE` печатает `apk_size=… apk_expected=18280162 apk_size_ok=… md5_match=…`; boot-ветка пропускает установку только при `overlayMounted && inode match && pluginContentOk`.

### 2.4 `@Test`

`MountResultTest` (8 тестов): `okRequiresBothMatches`, `md5MismatchIsFailed`, `inodeMismatchIsFailed`, `contradictoryOkIsDowngraded` (скрипт сказал `OK`, а `md5_match=false` ⇒ FAILED), `noMountIsFailed`, `missingLineIsNull`, `detailSizeParsed`, `stagingConflictIsNotSilent` (регресс: строка `CONFLICT другой файл, не затираю молча` не должна исчезнуть).

## 3. Флаги слотов — не тронуты

`HvacDigitalProps.kt:42-47` (без изменений с iter33i, 1:1 к `w0/x7.java:137-147`):

```kotlin
fun flagsCommand(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String {
    val t = if (tempOn) "1" else "0"
    val b = if (battOn) "1" else "0"
    val e = if (extOn) "1" else "0"
    return "setprop $PROP_TEMP $t && setprop $PROP_BATT $b && setprop $PROP_EXT $e && echo FLAGS_OK"
}
```
`git log -S"flagsCommand" --oneline -- .../HvacDigitalProps.kt` — единственный коммит `25344d2` (iter33i); в iter33j файл не менялся.

Приёмка слотов («Процент заряда» → % с одним знаком, «Наружняя температура» → наружная, «Цифровой климат-контроль» → цифра без полоски) — **🟡 до владельца**: она проверяется глазами на машине после установки 1.0.55, в песочнице ГУ нет.

## 4. P1 — наружная температура по эталону

### 4.1 Код

`OutsideTemp.kt:14` — `decodeFlymeTemp(raw) = (raw − 80) / 2` (порт `TemperatureReader.kt` владельца), `:17` — коридор правдоподобия −55…+70.

`StatusIcons.kt:397-433` (`readTempScan`):
1. основной: `getIntProperty(0x2140A377, 0)` → `TEMP_DECODE raw=86 → 3.0°C (formula (raw-80)/2 source=AC_AMBIENT_TEMP)`;
2. при живом ambient: `ENV_OUTSIDE_TEMPERATURE ignored: 25.0 (source=AC_AMBIENT_TEMP)`;
3. fallback (только если ambient ошибка/неправдоподобен): `0x11600703`, area 0 → `TEMP_DECODE env_outside=… (fallback, source=ENV_OUTSIDE_TEMPERATURE)`;
4. диагностика: `AC_INSIDE_TEMP 0x2140A379` тем же decode (`PROP_AC_INSIDE_TEMP`, `StatusIcons.kt:111`), `AC_AMBIENT_TEMP_INVALID` — **не гейт**, только строка `invalid=…(не гейт)`;
5. все форматы — `String.format(Locale.US, "%.1f", …)` (запятой `25,0°C` больше нет);
6. старый «основной» порт зон `{0,1,4,7}` удалён — константы `ENV_OUTSIDE_AREAS` в коде нет (`TempDecodeTest.fallbackConstantIsEnvOutside` проверяет это по тексту файла).

### 4.2 Тесты (`TempDecodeTest`, 5)

`ownerReferenceNumbers` (86→3.0, 95→7.5, 93→6.5, 0→−40.0), `nullRawGivesNull`, `plausibleRangeMatchesReference`, `decodeOfGarbageIsNotPlausible`, `fallbackConstantIsEnvOutside`.

### 4.3 Честно

Фактические значения на машине (`raw` и итог °C) появятся в `app.log` строками `TEMP_DECODE`; сверить с приборкой — за владельцем. `+12°` на стоковой шильде не трогали (заглушка).

## 5. P1 — JIT и живучесть по модели референса

### 5.1 zygote удалён полностью

```
$ grep -rn "ctl.restart zygote" app/src/
(пусто, rc=1)
```
* Тумблер JIT (`AppPerformance.setAutoOptimize`, `:213-266`): два `setprop` (`dalvik.vm.usejit`, `dalvik.vm.usejitprofiles`) + persist-маркер `persist.sys.dalvik.vm.usejit`, чтение обратно, лог
  `JIT: runtime_before=… persist=… action=setprop_only zygote_restart=never apply="применится после перезагрузки ГУ"`.
  Удалены также `svc wifi disable`, `umount /data/app/*`, `sleep`, `pidof zygote` и проверка «ZYGOTE_RESTARTED».
* Boot-ветка (`:824-849`): только persist-backfill и одна строка `JIT_BOOT: … zygote_restart=never action=persist_only`; файлы-намерения `jit_pending_verify` больше не создаются, старый — удаляется (`checkPendingZygoteVerify`, `:851-862`).
* Разрешённые точечные рестарты остались как есть: `ctl.restart mediacodec` (видео, по клику) — `AppPerformance.kt:423,486`.

### 5.2 Раунд оптимизации вместо `compile_all_apps`

`jitRoundSteps()` (`:545-583`): поочерёдное `cmd package compile -m speed-profile <pkg>` по списку «всегда активные/избранные» (`getKeepAliveApps`), на пакет одна строка
`JIT_ROUND pkg=… mode=speed-profile → OTIMIZADO|NAO_OTIMIZADO dt=…ms`; `-a` (все приложения) больше не используется; неудача одного пакета не красит раунд в красный.
Кнопка **«Раунд оптимизации (speed-profile)»** — `ToolsPanel.kt:819`, обработчик `perf.jitRound` — `UiBridge.kt:703-709`.
Полная компиляция: `optimizeAllApps` → режим `speed` (`JIT_FULL_MODE`), предупреждение в первом шаге, кнопка **«Ускорить всё (полная, долго)»** — `ToolsPanel.kt:820`.

### 5.3 Точечное восстановление

* `recoverAdbd(reason)` (`:598-618`): одна фоновая попытка `( sleep 1; setprop ctl.restart adbd ) &`, пауза 4 с, повторная проба `id`; лог `ADB_RECOVERY action=restart_adbd reason=… root_before=… root_after=… result=OK|FAILED`. Кнопка «Восстановить root-канал» (`ToolsPanel.kt:821`).
* `restartCarplayMonitor()` (`:642-662`): ровно одна команда `setprop ctl.restart CarplayMonitor`, лог `CARPLAY_RESTART … result=…`. Кнопка «Восстановить CarPlay» (`ToolsPanel.kt:822`), авто-предложений нет.
* «Перезагрузка ГУ как лечение» из кода не вызывается (проверено: `grep -rn "reboot" app/src/main/java` — только чтение/комментарии).

### 5.4 BUILD-строка

`app/build.gradle.kts:39-52`: SHA берётся **сначала** из `GITHUB_SHA` (в Actions есть всегда, `.git` не нужен), затем из `git rev-parse`; иначе честное `unknown`. Печать — `App.kt:22-24` (`commit=<7> ci_run=<id>`). Значение `fc55245` в репозитории не встречается (`grep -rn "fc55245" .` → пусто), так что после установки этой сборки строка будет содержать реальный SHA прогона §6.

### 5.5 Мусор persist

`PersistCleaner.kt` (+ вызов из `App.kt:42`, фоном, один раз за процесс):
* легаси-ключ `persist.sys.hvacdigital.temp_ext` → в пусто (`reason=legacy_key`);
* числовое значение в `temp`/`batt`/`exttemp` → `0` (`reason=numeric_not_flag`);
* чужие `persist.*` не трогаются — тест `PersistCleanerTest.scriptOnlyTouchesHvacDigitalProps` проверяет по префиксу `persist.sys.hvacdigital.`;
* строки в лог: `PERSIST_CLEAN prop=… was=9.5 now= reason=legacy_key`, при отсутствии мусора — `PERSIST_CLEAN none …`.

### 5.6 Мелочи из лога

* `BATT: SOC null` / `TEMP: no CarPropertyManager` на старте: вместо ошибки — одна строка `VHAL_WAIT: CarPropertyManager не готов — чтение батареи/температуры отложено` не чаще раза в 30 с, чтения пропускаются, старая иконка не снимается (`StatusIcons.kt:795-806`, `:826-828`, `:845-847`).
* `cat: /proc/uptime: Permission denied`: `BootReceiver.kt:44` и `ScreenBrightness.kt` (2 места) → `cat /proc/uptime 2>/dev/null || echo unavailable`.

## 6. CI

* Прогон: **`42194780607`**, коммит **`5dcf60b`**, job **`127815183599`** (`build`) — **success**, `BUILD SUCCESSFUL in 4m 21s`.
* Тесты: **72 PASSED / 0 FAILED** (`grep -ac "PASSED"` = 72, `grep -a "FAILED"` = пусто). Было 56, +16 новых: `MountResultTest` — 8, `TempDecodeTest` — 5, `PersistCleanerTest` — 3.
  Новые имена: `MountResultTest > {okRequiresBothMatches, md5MismatchIsFailed, inodeMismatchIsFailed, contradictoryOkIsDowngraded, noMountIsFailed, missingLineIsNull, detailSizeParsed, stagingConflictIsNotSilent}`, `TempDecodeTest > {ownerReferenceNumbers, nullRawGivesNull, plausibleRangeMatchesReference, decodeOfGarbageIsNotPlausible, fallbackConstantIsEnvOutside}`, `PersistCleanerTest > {garbageDetection, scriptOnlyTouchesHvacDigitalProps, legacyKeyIsEmptiedAndFlagValuesResetToZero}`.
  Регресс зелёный: `BootGateTest` 3, `HvacDigitalPropsTest` 5, `HvacVerdictTest` 6, `JitBootMarkTest` 15, `MountParseTest` 4, `RedactTraceTest` 2, `ScriptChunkerTest` 4, `TempFormatTest` 3, `TempProbeTableTest` 7, `TtlScriptTest` 3, `VentOwnersTest` 3.
* Артефакт: **`13492735092`** (`app-debug`), три размера раздельно:

| файл | байт | команда |
|---|---|---|
| zip артефакта | **177 108 547** | `stat -c%s art_k.zip` = `size_in_bytes` из API |
| `apk/debug/app-debug.apk` | **215 485 345** | `unzip -l art_k.zip \| awk '/app-debug.apk/{print $1}'` |
| `logs/manifest-merger-debug-report.txt` | **105 294** | `unzip -l art_k.zip \| awk '/manifest-merger/{print $1}'` |

* Предыдущая (красная) попытка этой итерации: прогон `42193578514` (`b767e5f`) — `BUILD FAILED`; причины по логу: `Unresolved reference 'jitPersistBackfill'` (переписывая boot-ветку, я вырезал хелперы), `Literal must be surrounded by whitespace` (вложенные кавычки в `JIT_FULL_ROUND`), `Unresolved reference 'OLD_MD5'` (в raw-строке `"${OLD_MD5:-absent}"` — Kotlin видел интерполяцию). Всё исправлено в `5dcf60b`.
* Строка приложения при старте ожидается такой: `BUILD: versionCode=55 versionName=1.0.55 commit=5dcf60b ci_run=42194780607` (значения подставляет `BuildConfig`; в CI `GITHUB_SHA` теперь основной источник).

## 7. Вопросы владельцу

1. **Приёмка трёх слотов** после установки 1.0.55: «Процент заряда» (один знак), «Наружняя температура» (≈ факту, не «25»), «Цифровой климат-контроль» (цифра без полоски) — скрин или пара строк из `app.log` (`HVAC_MOUNT_RESULT`, `TEMP_DECODE`).
2. **Pull `/data/local/hvac-digital/sysui.apk` с ПК** — по желанию, только чтобы подтвердить, что смонтировано ровно `sha256 de4b4742…` (задача от этого не зависит). Оригинал `/system/app/GeelyAutoSystemUIPlugin/…` не запрашиваем и не копируем.
3. **Таймлайн падения рута**: рут пропал сразу после включения JIT или позже? Это нужно, чтобы подтвердить, что виновником был именно рестарт zygote (в 1.0.55 его нет вообще).
4. Маршрутная пилюля — вопрос **снят** мандатом (элемента в UI нет; в `hvacdigital.via` только `bind`/`nenhum`).

## 8. Честные пробелы

1. Материалы `РАЗБОР_1.0.54_на_машине_06-11.md` и `uploads/voiceapp3-10.log`, на которые ссылается мандат, **в рабочее окружение не переданы** (в `uploads/` их нет). Всё, что мандат цитирует из них, использовано как есть; фактическая сверка по живому логу 1.0.54 не выполнялась — при следующей итерации прошу приложить файл.
2. Поведение на ГУ не проверено: маунт, слоты, температура, JIT-раунды проверяются на машине владельцем (в песочнице нет ни ГУ, ни VHAL).
3. `checkStatus` опознаёт плагин по **размеру** (частота вызова), а md5 сверяется в самом скрипте `enable` — при совпадении размера, но другом содержимом, md5-строка `HVAC_MOUNT_RESULT` всё равно даст `FAILED`; расхождения между этими двумя проверками возможны только в сторону «строже».
4. «Раунд оптимизации» идёт по списку «всегда активные/избранные» (`getKeepAliveApps`); если список пуст, печатается `JIT_ROUND skipped reason=empty_package_list` — это не ошибка, но и не ускорение.
