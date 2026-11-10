# iter33p — отчёт (версия 1.0.61, fix2)

**База:** `c9c3e9f` (ветка `fix/v2`; код 1.0.60 = `a155e79`, установлен на машине).
**Версия:** `versionCode = 61`, `versionName = "1.0.61"` (`app/build.gradle.kts:78-79`).
**Маркер:** `app/buildinfo.properties` → `SOURCE_SHA=<SHA>` (пишет агент; `git rev-parse` в сборке запрещён).
**Тесты:** база 147 → **164** (не уменьшено), CI — в §6 ниже.

Мандат: `uploads/PROMPT_iter33p_1.0.61.md`. Содержание — чек-лист с `файл:строка`.

---

## §1. P0 — авто-восстановление JIT при загрузке УДАЛЕНО (решение владельца 09.11)

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 1.1 | `jitBootRestoreArm` вырезан | ✅ | функции нет: `grep -rn jitBootRestoreArm app/src/main` → пусто (единственное упоминание — страж `JitBootTest.kt:107`) |
| 1.2 | `bootRestoreDecision`, `JIT_BOOT_RESTORE_MARKER/_DELAY_MS` вырезаны | ✅ | там же; констант в коде нет |
| 1.3 | `route="boot_restore"` и `Where.BOOT_RESTORE` вырезаны | ✅ | `AppPerformance.jitApplyWithZygote` — сигнатура без `route` (`:1105-1110`), `AvasRestore.Where` = `{JIT_TOGGLE, APP_START}` (`AvasRestore.kt:52-58`) |
| 1.4 | Вызов из `App.kt` убран | ✅ | `App.kt:54-56` — комментарий вместо поста; `grep jitBootRestoreArm App.kt` → пусто |
| 1.5 | `ctl.restart zygote` — ТОЛЬКО в клике тумблера | ✅ | единственный литерал: `AppPerformance.kt:1078`, внутри блока `JIT_ZYGOTE_BEGIN/END`; страж `JitBootTest.zygoteRestartOnlyInsideJitToggleBlock` + счётчик вызовов `jitApplyWithZygote(` = **2** (1 определение + 1 вызов `:217`) |
| 1.6 | Текст `JIT_BOOT` без упоминания авто-восстановления | ✅ | `AppPerformance.kt:886-887`: `jit=off_after_reboot toggle_to_enable (setprop не персистентен — как у референса)` |
| 1.7 | Тест «boot-restore отсутствует» | ✅ | `JitBootTest.bootRestoreIsGone` (`JitBootTest.kt:100-119`) |

## §2. P0 — цепочка тумблера по референсу (сеть вокруг zygote)

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 2.1 | Перед рестартом (только чтение): `wifi`, `ap`, AVAS mode+sound → `JIT_NET_PRE wifi=… ap=…` | ✅ | `JitNet.saveBeforeRestart` (`JitNet.kt:74-84`: `isWifiEnabled` + `apIsUp()` по `ip -o addr show ap0`), вызов `AppPerformance.kt:1121` **до** подготовки сети |
| 2.2 | Скрипт: `svc wifi disable` → цикл ≤20 с `ip rule show \| grep -c 'oif wlan0'` → `setprop usejit/usejitprofiles` | ✅ | `JitNet.preRestartScript` (`JitNet.kt:91-114`), порядок держит `JitToggleChainTest.preScriptOrderIsStrict`; окно — `DRAIN_TIMEOUT_S = 20` (`JitNet.kt:31`), лог `JIT_NET_PRE ip_rule_wait=… drained=yes\|no` (`JitNet.kt:136-140`) |
| 2.3 | Рестарт строго ПОСЛЕ слива правил | ✅ | prep выполняется раньше записи/запуска watcher-а: `AppPerformance.kt:1133` → `:1135` (prep) → `:1160` (launch); `JitToggleChainTest.restartHappensAfterNetworkPrep` |
| 2.4 | После подъёма: санация зависших `oif wlan0` → `JIT_NET_SANITIZE removed=… left=…` | ✅ | `JitNet.sanitizeScript` (`JitNet.kt:149-161`) — удаление только по выборке `grep 'oif wlan0'`, `scope=oif_wlan0`; вызов `JitNet.restoreAfterRestart` (`:167-190`) из `AppPerformance.kt:1004-1007` |
| 2.5 | Wi-Fi: `wifi=1 && !isWifiEnabled` → `setWifiEnabled(true)`, до 3 попыток × ~3 с, лог `JIT_NET_RESTORE wifi=… attempt=… result=…` | ✅ | `JitNet.restoreWifi` (`JitNet.kt:193-214`), `WIFI_RESTORE_ATTEMPTS = 3` (`:34`), `WIFI_RESTORE_PAUSE_MS = 3_000` (`:35`) |
| 2.6 | AP: `ap=1 && ap0 down` → `startSoftAp` (как `U("pós-JIT")`), недоступно — честный лог | ✅ | `JitNet.restoreAp` (`JitNet.kt:217-232`): рефлексия `startSoftAp(WifiConfiguration)`, `result=already_up`/`no_startSoftAp` |
| 2.7 | Верификация — как была (getprop + `/proc/self/maps`), итог `JIT_APPLIED … result=failed` | ✅ | `AppPerformance.jitPendingVerify` (`:1046-1055`, `:1030-1041`) без изменений логики |
| 2.8 | Сеть — только внутри цепочки тумблера | ✅ | страж `JitToggleChainTest.netCallsStayInsideToggleChain`: `JitNet.` встречается ровно в `JitNet.kt` + `AppPerformance.kt` |
| 2.9 | Wi-Fi больше НЕ трогается в watcher-скрипте (был `svc wifi enable` сразу после подъёма) | ✅ | `AppPerformance.kt:1090-1093` — только фиксация факта; возврат делает приложение с ретраями |

## §3. P0 — AVAS «в точности как в референсе»

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 3.1 | Сохранение: `mode = getAVASMode()` + `sound_type` по областям {0,1} | ✅ | `AvasRestore.save` (`AvasRestore.kt:139-162`), `AREAS = intArrayOf(0, 1)` (`:42`) — как `w0/u.c` в байткоде; снимок `soundSnapshot` (`:277-292`) через `getIntProperty(propId, area)` |
| 3.2 | Лог `AVAS_SAVE mode=… sound_type=… where=…` | ✅ | `AvasRestore.saveLine` (`:107-113`); при отсутствии свойства — `sound_type=unavailable reason=…` |
| 3.3 | Восстановление НЕ сразу: ~5 с → ~12 с → до 5 ретраев по 30 с | ✅ | `ATTEMPT_SCHEDULE_MS = [5000, 12000, 42000, 72000, 102000, 132000, 162000]` (`AvasRestore.kt:48`), планировщик `scheduleRestore` (`:193-208`), тест `AvasRestoreTest.scheduleMatchesReference` |
| 3.4 | Каждая попытка: факт → запись при расхождении → перечитать; стоп по подтверждению | ✅ | `restoreAttempt` (`:213-249`), вердикт `verdict` (`:90-104`), файл-намерение снимается только при `ok/already_ok` (`:246`) |
| 3.5 | Лог `AVAS_RESTORE attempt=N mode_saved=… mode_now=… sound_saved=… sound_now=… result=ok\|failed\|already_ok` | ✅ | `attemptLine` (`:118-130`) |
| 3.6 | Пишем ТОЛЬКО сохранённые значения: `setAVASMode(saved)`, `setIntProperty(saved)` по тем же областям | ✅ | `writeMode(saved)` (`:227`), `writeSound(SoundValue)` (`:348-362`, лог `restoreSavedAvasSoundType area=…`); констант/муты нет — страж `AvasRestoreTest.writesOnlyInAvasRestoreAndOnlySavedValues` |
| 3.7 | Call-sites ровно 2: цепочка тумблера и старт приложения | ✅ | `AppPerformance.kt:1119` (`saveBeforeRestart`), `:875` и `:1014` (`scheduleRestore`); страж `AvasRestoreTest.callSitesAreOnlyAllowed` (всего 2 файла) |
| 3.8 | Метод/Car API недоступны → `skip reason=…`, цепочка JIT продолжается | ✅ | `skipLine("no_getAVASMode")` (`:216-225`), `AVAS_SAVE skipped reason=…`; намерение снимается на последней попытке, чтобы не жечь попытки каждый старт |

**Идентификатор свойства sound type (важно, честно).** В референсе число приходит из **нативной
таблицы** (`Politica.h(7)` → `nativeCall`), в dex его нет — статически восстановить нельзя
(проверено: `libexpolitica.so` 6656 Б, таблица зашифрована в native). Поэтому реализовано:
(а) закреплённое значение из префов `avas_sound_prop_id`; (б) поиск в `getPropertyList()` по имени
«avas», если прошивка отдаёт имена; (в) иначе — `sound_type=unavailable reason=no_sound_prop`
и **никаких записей**. Режим (`getAVASMode`/`setAVASMode`) при этом работает штатно.
🟡 Если в логе будет `reason=no_sound_prop` — владельцу достаточно один раз сообщить id свойства
(или я добавлю discovery по изменению значения), поведение режима от этого не зависит.

## §4. P1 — лог-файлы по датам

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 4.1 | Имя `voiceapp3-DD.MM.YYYY.log`, каталог приложения `…/files/logs/` | ✅ | `LogFiles.PREFIX = "voiceapp3-"` (`LogFiles.kt:31`), `fileNameOf` (`:41`), `dirFor` = `getExternalFilesDir(null)/logs` с фоллбэком `filesDir/logs` (`:66-71`). Точный путь на ГУ: `/sdcard/Android/data/com.example.voiceapp3/files/logs/voiceapp3-DD.MM.YYYY.log` |
| 4.2 | Один файл на сутки: append в течение дня, новый при смене даты | ✅ | имя считается от текущего момента на каждой записи (`LogFiles.append`, `:99-112`); тест `LogFilesTest.rotationIsDaily` |
| 4.3 | Те же строки, что в logcat (с таймстампами), дублирование сохранено | ✅ | хук в единственном писателе `AppLog.writeRaw` (`AppLog.kt:386-388`), строка уже с `[yyyy-MM-dd HH:mm:ss.SSS]` |
| 4.4 | Retention 14 суток при старте; ошибка записи — тихий фоллбэк | ✅ | `RETENTION_DAYS = 14` (`:33`), `expired`/`prune` (`:55-80`), вызов в `prepare` из `AppLog.init` (`AppLog.kt:100-104`); запись под `runCatching`, ошибка — `Log.w` |
| 4.5 | `LogFilesTest`: формат, ротация, retention, создание каталога | ✅ | `LogFilesTest.kt` (5 тестов, включая реальную чистку во временном каталоге) |

## §5. Что осталось из 1.0.60 без изменений

✅ `PLUGIN_DIAG phase=pre_mount/post_mount`, TTL retry uplink (2×3 с, только `no_uplink`, ON),
`JIT_BOOT`-проба безусловная, watcher `JIT_ZYGOTE_DONE` + ожидание второй строки, recarga,
mount-мониторинг, рут-reconnect, `TtlScript.kt`, `kg.j`, плагин 1.4.1 (`5aab306b…`), слоты,
`BatterySocView`-дизайн. Файлы не тронуты (кроме `AppPerformance.kt`/`App.kt`/`AppLog.kt` по §1/§2/§4).

## §6. Тесты и CI

Новые/переписанные тесты:

| Тест | Что держит | Кол-во |
|---|---|---|
| `JitBootTest` (переписан частично) | единственный call-site zygote, отсутствие boot-restore | 6 (было 6) |
| `AvasRestoreTest` (переписан) | расписание 5/12/30×5, вердикт по read-back, mode+sound, 2 call-site, запреты | 9 (было 7) |
| `JitToggleChainTest` (новый) | порядок `svc wifi disable` → drain → setprop; рестарт после подготовки; сохранение сети до | 5 |
| `NetRestoreTest` (новый) | возврат только из сохранённого, ≤3 попыток Wi-Fi, санация только `oif wlan0`, AP честно | 5 |
| `LogFilesTest` (новый) | имя `voiceapp3-DD.MM.YYYY.log`, ротация, retention 14, каталог | 5 |
| `BuildMarkerTest` | 61 / `1.0.61` | 3 |

**Итого: 164 теста** (147 база + 17; ни один тест не удалён — вместо удалённого теста
boot-restore добавлен страж `bootRestoreIsGone`).

### §6.1 CI (логи джоб)

| Прогон | Коммит | Итог |
|---|---|---|
| `42827223054` | `a36a2b7` | ❌ 160 PASSED / **5 FAILED** — стражи: `AvasRestoreTest.logLinesFollowReference` (ожидание `mode_now` в тесте), `AvasRestoreTest.writesOnlyInAvasRestoreAndOnlySavedValues` (слишком широкий поиск `setIntProperty` — он есть и в окнах/VHAL), `AvasRestoreTest.callSitesAreOnlyAllowed` (в выборку попадал одноимённый `JitNet.saveBeforeRestart`), `JitToggleChainTest.restartHappensAfterNetworkPrep` (base64 watcher-а собирался ДО подготовки сети) |
| **`42828995241`** | **`8b6ee74`** | ✅ **164 PASSED / 0 FAILED**, job `129873731857`, артефакт `13771626397` (177 172 215 Б) |
| **`42830831600`** | **`3646901` (HEAD)** | ✅ **164 PASSED / 0 FAILED**, job `129880075141`, артефакт `13771533798` (177 172 323 Б), `BUILD SUCCESSFUL in 4m 17s` |

| `42832827628` | `d33eaa5` (HEAD) | ✅ 164 PASSED / 0 FAILED, job `129887092945`, артефакт `13772668459` (177 172 241 Б) |

**Код итерации для сборки: `8b6ee74`** (маркер `SOURCE_SHA=8b6ee74`); коммиты `3646901` и далее — только docs (отчёт/WORKLOG).

Исправления после прогона `42827223054` (коммит `8b6ee74`): base64-кодирование watcher-скрипта
перенесено ПОСЛЕ `JitNet.runPreRestart` (`AppPerformance.kt:1133-1138`), границы sound type
держатся по маркерам `restoreSavedAvasSoundType`/`avas_sound_saved` (общий `setIntProperty`
существует в `WindowDoors.kt:545` и `car/*`), call-sites считаются по `AvasRestore.*`.

## §7. Что проверить владельцу (после сборки 1.0.61)

1. Установка поверх 1.0.60 → в логе `BUILD: versionCode=61 versionName=1.0.61 commit=<SHA>`.
2. **Тумблер JIT** → экран гаснет 10–20 с → подъём: **интернет ЖИВ**, **AVAS играет нормально**,
   CarPlay жив. В логе: `JIT_NET_PRE wifi=… ap=…` → `JIT_NET_PRE ip_rule_wait=… drained=yes` →
   `JIT_APPLIED … via=pending_verify` → `JIT_NET_SANITIZE removed=… left=0` →
   `JIT_NET_RESTORE wifi=1 attempt=1 result=ok` → `AVAS_RESTORE attempt=1 … result=ok`
   (допустимы attempt=2..N, ретраи 30 с).
3. **Перезагрузка ГУ**: никаких авто-рестартов (экран сам не гаснет), тумблер JIT off, сеть и AVAS в норме.
4. TTL ON/OFF — как раньше, CarPlay жив.
5. **Лог-файл по дате**: `/sdcard/Android/data/com.example.voiceapp3/files/logs/voiceapp3-DD.MM.YYYY.log`
   создан и содержит строки прогона (теперь лог не теряется).
6. Лог + скрины (лог обязателен).

## Риски

- 🟡 `AVAS_SAVE sound_type=unavailable reason=no_sound_prop` — id свойства sound type в референсе
  нативный; режим AVAS при этом восстанавливается штатно.
- 🟡 `JIT_NET_RESTORE ap=1 … reason=no_startSoftAp` — если прошивка не отдаёт `startSoftAp`
  через рефлексию; Wi-Fi и санация от этого не зависят.
- 🟡 `JIT_NET_PRE … drained=no` (правила `oif wlan0` не ушли за 20 с) — рестарт всё равно
  выполняется (как в референсе), но после подъёма санация снимет зависшие правила.
- ⛔ Не делалось: мута AVAS, смена sound type «на глаз», правки системы/smali, изменения TTL-правил,
  авто-рестарты zygote, своя раскладка.
