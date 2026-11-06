# REPORT iter33k **fix1** — 1.0.56 (до установки)

**База:** `68eb22a` (iter33k). **Правка владельца 06.11:** «Нужно сделать точную реализацию референса,
а не придумывать какие-то кнопки».
**Код fix1:** `09c7f87` · **buildinfo (маркер):** `09c7f87` · ветка `fix/v2`, версия остаётся
`versionCode = 56` / `versionName = "1.0.56"`.

```
BUILD: versionCode=56 versionName=1.0.56 commit=09c7f87 ci_run=local
```

---

## 1. Что было не так (моя ошибка в базовой итерации)

В 1.0.56 (iter33k) я сделал **свой** механизм применения слотов вместо референса:

| Было (выдумано) | Почему неверно |
|---|---|
| «мягкий apply» = `kill $(pidof com.flyme.auto.systemuiplugin)` | в дексе референса `Lw0/x7;` **нет** pidof процесса плагина, они его не убивают никогда |
| кнопка «Применить слоты (SystemUI)» | референс не имеет такой кнопки: применение встроено в клик тумблера |
| предупреждение «CarPlay и музыка прервутся на ~10 сек» как отдельный сценарий | у референса это штатное поведение клика, а не отдельное действие |

Всё это удалено. Отклонений от байткода референса по слотам не осталось.

## 2. Что делает референс (по `cex339/classes.dex`) и что теперь у нас

Путь тумблера: `Lw0/x7;->j(Context, Z, Z, Z, Lw0/y8;)Z` (обёртки `w0/ab`, `w0/ib`, `w0/ua` — по одной
на тумблер).

| Шаг референса | У нас | Где |
|---|---|---|
| `x7.i(temp,batt,ext)` — одна команда трёх `setprop` + `echo FLAGS_OK` | **без изменений** (`HvacDigitalProps.flagsCommand`) + `setprop …via` | `HvacFlagsApply.kt:117-127` |
| `x7.b(...)` — их «Ar Digital» (md5 локальной копии, маршрут `visual.aplicarArDigital`) | нет (у нас нет их CDN/копии; флаги + `via` полностью заменяют) — **обоснование в §5** | — |
| `x7.g("tela")` — **recarga панели**: `p=$(pidof com.android.systemui); [ -z "$p" ] \|\| kill $p`, до **20 попыток × 500 мс**, общий таймаут **20 с**, проверка состояния после | реализовано 1:1: `HvacFlagsApply.recargaScript()` | `HvacFlagsApply.kt:83-99` |
| их лог `Ar Digital [tela]: recarga=… plugin=…`, при провале `barra NAO reiniciou` | наш: `FLAG_APPLY method=bar_recarga tries=… result=ok\|fail dt=…ms`, при провале — `bar_recarga fail (barra NAO reiniciou) tries=…` | `HvacFlagsApply.kt:57-63,148-149` |
| их формулировка механизма: `única (bind + reinício)` | записано в отчёт и в шапку файла | `HvacFlagsApply.kt:1-25` |

Скрипт recarga (то, что реально уходит на машину):

```sh
PB=$(pidof com.android.systemui)
[ -z "$PB" ] || kill $PB
i=0
while [ $i -lt 20 ]; do
  sleep 0.5
  i=$((i+1))
  PA=$(pidof com.android.systemui)
  if [ -n "$PA" ] && [ "$PA" != "$PB" ]; then
    echo "RECARGA_OK pid_before=${PB:-none} pid_after=$PA tries=$i"
    exit 0
  fi
done
PA=$(pidof com.android.systemui)
echo "RECARGA_FAIL pid_before=${PB:-none} pid_after=${PA:-none} tries=$i (barra NAO reiniciou)"
```

Проверка после recarga — по факту смены pid панели (как у референса проверяется состояние плагина
через `x7.e()`); таймаут скрипта в `AdbClient` = 20 с + 4 с запаса (`HvacFlagsApply.kt:137`).

## 3. Что удалено

| Удалено | Было в |
|---|---|
| кнопка «Применить слоты (SystemUI)» | `ToolsPanel.kt:295-299` (база) — удалено |
| ветка обработчика `hvac_apply_sysui_btn` | `ToolsBackend.kt:988-992` (база) — удалено |
| `StatusIcons.applySlotsSystemUi` | `StatusIcons.kt:1074-1090` (база) — удалено |
| `HvacFlagsApply.applySystemUi()` | `HvacFlagsApply.kt:120-133` (база) — удалено |
| `HvacFlagsApply.pluginRestartScript()` + `PLUGIN_PKG` + `applyResult()` | `HvacFlagsApply.kt:68-79` (база) — удалено |

**Осталось без изменений (как требует §1.4):** формат `flagsCommand` (`x7.i`), `via=bind|nenhum`,
`FLAGS_SYNC` из `getprop`, кнопка «Монтировать плагин» (аналог их установщика), кнопки
«Восстановить root-канал»/«Восстановить CarPlay» (`ctl.restart adbd`/`CarplayMonitor`), TTL со скоупом,
авто-восстановление рута, маркер `buildinfo`, отсутствие красных текстов. Версия — **1.0.56**.

## 4. Поведение для владельца (вписать в отчёт, §2 мандата fix1)

Клик по тумблеру слота = флаги + **перезапуск панели (SystemUI)** — ровно как в референсе
(их «bind + reinício»). **Интерфейс мигает на несколько секунд — это их штатный механизм, не дефект.**
Отдельной кнопки применения нет и не будет.

Ожидаемые строки в `voiceapp3.log` на один клик:

```
HVACDIGITAL_PUSH rc=0 temp=0 batt=1 ext=0 via=bind out=FLAGS_OK
RECARGA out=RECARGA_OK pid_before=1174 pid_after=20740 tries=3
FLAG_APPLY method=bar_recarga tries=3 result=ok dt=1620ms
TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=bar_recarga result=ok | ✓
```

При неудаче (нет root-канала / панель не поднялась за 10 с):

```
FLAG_APPLY method=bar_recarga tries=20 result=fail dt=10420ms
bar_recarga fail (barra NAO reiniciou) tries=20
TOGGLE [hvacBatt] → ON | flags=… | via=bind | apply=bar_recarga result=fail | ✗
```

## 5. Обоснование единственного отклонения от референса

`x7.b(...)` (их «Ar Digital») не реализован: это валидация md5 **их** локальной копии плагина и маршрут
`visual.aplicarArDigital` с их CDN-манифестом (`hvacdigital.json`, версия `1.1.2-batt-1-decimal`).
У нас плагин ставится своим bind-mount (кнопка «Монтировать плагин» + boot-маршрут, `HVAC_MOUNT_RESULT
mount=1 inode_match=true md5_match=true verdict=OK`), поэтому валидация md5 у нас уже есть внутри
маунта, а маршрут `visual.aplicarArDigital` относится к их установщику (`com.swimapps.hvacdigital`),
который по решению владельца в git не возвращается. На поведение клика это не влияет: флаги + `via`
пишутся тем же `x7.i()`, дальше — та же recarga.

## 6. Чек-лист fix1 §3 (фактический вывод)

```
$ grep -rn "Применить слоты" app/src/main/java                  → 0   (только в тесте NoApplyButtonTest)
$ grep -rn "com.flyme.auto.systemuiplugin" app/src --include=*.kt
      ToolsCatalog.kt:17,81,145  — тексты помощи
      car/HuClimate.kt:21,30,36  — имя пакета/экшена/сервиса для VHAL-запроса
      HvacDigitalPlugin.kt:18    — OEM_PKG (pm path / маунт / валидация)
      → ни одного kill процесса плагина
$ grep -rn "pidof com.android.systemui" app/src
      HvacFlagsApply.kt:13,80 (док), :84,90,96 (recarga-скрипт)
      HvacDigitalPlugin.kt:384-389,620-625 (монтажный скрипт, аналог установщика)
      StatusIcons.kt:1058 (описание шага)
$ grep -rn "pidof com.flyme.auto.systemuiplugin\|pluginRestartScript" app/src/main → 0
$ grep -rn "kill " app/src/main/java (код)
      HvacFlagsApply.kt:85        — kill панели в recarga (как x7.g)
      HvacDigitalPlugin.kt:386,621,726 — kill панели в монтаже/restore (кнопки)
      StatusIcons.kt:1058         — та же команда в описании шага
$ grep -rn "ошибка:" app/src/main/java → 0
```

## 7. Тесты

| Тест | Что держит | Результат |
|---|---|---|
| `HvacFlagsApplyTest` (9) | `via`, `flagsSegment`, `parseFlags`, строка `TOGGLE`, `FLAG_APPLY method=bar_recarga tries=… dt=…ms`, `recargaFailLine`, `recargaScript` (панель, `[ -z ] \|\| kill`, `sleep 0.5`, смена pid, `RECARGA_OK`, `20` попыток, **нет** пакета плагина), `recargaResult` | ✅ 9/9 |
| `NoApplyButtonTest` (3) — новый | в `ToolsPanel`/`ToolsBackend` нет строки «Применить слоты» и `hvac_apply_sysui_btn`; в `src/main/java` нет `pidof com.flyme.auto.systemuiplugin`/`pluginRestartScript`; recarga — единственный перезапуск панели из клика | ✅ 3/3 |
| `TtlRulesTest` (6), `TtlScriptTest` (6), `BuildMarkerTest` (3) | без изменений | ✅ 15/15 |

## 8. CI

| Прогон | SHA | Итог |
|---|---|---|
| `42263967081` | `60c8a0b` | ✅ **success**, **94 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 18s`, job `128050870657`, артефакт `13525909172` (177 120 961 Б) |
| `42267658779` | `04910f8` (только доки, код `09c7f87`) | ✅ success, **94 PASSED / 0 FAILED**, job `128063673059` |
| **`42265910234`** | **`dcf80b1`** (код `09c7f87` + маркер + отчёты) | ✅ **success**, **94 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 29s`, job `128057633161`, артефакт `13527033977` (177 120 994 Б); `BuildMarkerTest > commitSourceIsHonest PASSED` — маркер `SOURCE_SHA=09c7f87` доходит до `BuildConfig.GIT_COMMIT` |

Логи ключевых тестов (джобы `128050870657` и `128057633161`):

```
HvacFlagsApplyTest > recargaScript PASSED
HvacFlagsApplyTest > recargaResult PASSED
NoApplyButtonTest > noApplyButtonInUi PASSED
NoApplyButtonTest > pluginProcessNeverKilled PASSED
NoApplyButtonTest > recargaIsTheOnlyPanelRestart PASSED
```

(94 против 91 в базовой итерации: +3 `NoApplyButtonTest`, `HvacFlagsApplyTest` 7 → 9.)

## 9. Что владельцу

1. Собрать APK из `fix/v2` (версия остаётся 1.0.56, маркер buildinfo уже указывает на код fix1).
2. Кликнуть тумблер слота — панель мигнёт на несколько секунд, это норма (референс ведёт себя так же).
3. Прислать `voiceapp3.log`: нужны строки `HVACDIGITAL_PUSH …`, `RECARGA out=…`, `FLAG_APPLY
   method=bar_recarga tries=… result=…`, `TOGGLE …`.
4. Если `bar_recarga fail (barra NAO reiniciou)` — почти наверняка нет root-канала на момент клика:
   смотреть `ADB_STATE`/`ADB_RECOVERY` рядом в логе.

Открытые пункты базовой итерации (раскладка ≠ скриншот 2, boot-маунт) остаются как были — их fix1 не касается.
