# iter33p-fix1 — отчёт (версия 1.0.62)

**База:** `28dcdfc` (ветка `fix/v2`, код 1.0.61 = `8b6ee74`). **Код этой итерации:** `8f4ceba` + тесты `8b24928`.
**Версия:** `versionCode = 62`, `versionName = "1.0.62"` (`app/build.gradle.kts:78-79`) — шаг по схеме владельца (iter33p = 61).
**Маркер:** `app/buildinfo.properties` → `SOURCE_SHA=8b24928` (пишет агент; `git rev-parse` в сборке не используется).
**Тесты:** было 164 → **174** (`@Test` в `app/src/test`); CI: **174 PASSED / 0 FAILED** (§7).
**Мандат:** `uploads/REVIEW_iter33p.md` — 4 блокирующих замечания (§1–§4) + 3 🟡.

Ниже — чек-лист с `файл:строка`. Полевой прогон JIT **не выполнялся** (условие ревьюера: только после повторного REVIEW и с логом).

---

## §1. Блокирующее 1 — сеть возвращается в ЛЮБОМ сценарии (в т.ч. при отказе)

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 1.1 | Без надёжно сохранённого состояния сети операция **не начинается** (никакого `svc wifi disable` «на авось») | ✅ | `AppPerformance.kt:1147-1166`: `if (netSaved == null)` → лог `JIT_APPLIED_REQUEST aborted reason=no_net_state` и `ToolsStep(status = BLOCKED)` **до** `JitNet.runPreRestart` (`:1175`) и до записи файла-намерения |
| 1.2 | Сохранение подтверждается чтением обратно (запись в префы ≠ сохранено) | ✅ | `JitNet.saveBeforeRestart` (`JitNet.kt:97-120`): `readState` → `Prefs2.putString` → `savedState(app)`; расхождение → `JIT_NET_PRE failed reason=save_not_confirmed` + `clearSaved` + `null` |
| 1.3 | Неизвестное состояние (`ap0` не отвечает) считается **неизвестным**, а не «сеть выключена» | ✅ | `JitNet.readState` (`:75-82`) и `apState` (`:84-95`): `ERR_*`/пустой вывод → `null`; лог `reason=state_unknown` |
| 1.4 | Отказ **после** отключения Wi-Fi компенсируется в той же операции, без рестарта фреймворка | ✅ | `AppPerformance.kt:1198-1209`: `JitNet.compensate(app, netSaved, why)` → `JIT_APPLIED … net_compensate=ok\|failed`; `JitNet.compensate` (`JitNet.kt:301-307`) — `JIT_NET_COMPENSATE start/done`, в логе прямо `zygote НЕ перезапускается` |
| 1.5 | Возврат сети **не зависит** от успеха JIT-верификации (был корень бага: «канал недоступен → сеть не вернули») | ✅ | `AppPerformance.kt:984` — `restoreNetAndScheduleAvas(app, where, "pending_verify")` вызывается **сразу** после решения `VERIFY`, до чтения props (`:985`) и до ожидания `JIT_ZYGOTE_DONE` (`:1022`); в ветке «канала нет» печатается `net_restore=done_before_verify` (`:1010`) |
| 1.6 | Устаревшее намерение (после перезагрузки) — тоже повод вернуть сеть | ✅ | `AppPerformance.kt:973` — `restoreNetAndScheduleAvas(app, where, "pending_stale")` в ветке `STALE` |
| 1.7 | Сохранённое состояние снимается **только** по подтверждённому возврату | ✅ | `AppPerformance.kt:925-931`: `if (res?.allConfirmed == true) clearSaved` иначе `JIT_NET_RESTORE keep_saved … (повторим на следующем старте)`; в компенсации — `:1204` |
| 1.8 | Просроченное состояние (>10 мин) не применяется (не откатываем ручные изменения владельца) | ✅ | `JitNet.SAVED_TTL_MS` (`:41`), `isExpired` (`:62`), `AppPerformance.kt:920-924` — `reason=expired` |
| 1.9 | «Запрос ≠ состояние»: в логе отдельно `request=`, `state=`, `link=` | ✅ | `JitNet.restoreWifi` (`:309-330`): `JIT_NET_RESTORE wifi=1 attempt=N/3 request=… state=true\|false\|unknown link=up\|down\|unknown`; успех — только `if (state == true)` |
| 1.10 | На обычном старте сети не касаемся | ✅ | `restoreNetAndScheduleAvas` вызывается только из `jitPendingVerify`, а тот выходит на `if (!pendFile.isFile) return false` (`AppPerformance.kt:953`); `jitBootProbe` (`:846-877`) сетевых вызовов не содержит |
| 1.11 | Тест проверяет реальные переходы, а не текст | ✅ | `NetRestoreTest` (7): round-trip/битое состояние, TTL, порядок «прерывание до сети», компенсация + отсутствие `ctl.restart` после неё, независимость возврата от верификации, отсутствие сети на старте, формат лога `request=/state=/link=` |

## §2. Блокирующее 2 — `ip rule` удаляется только по полному spec, чужие правила не трогаются

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 2.1 | Решение об удалении — чистая функция с явными отказами | ✅ | `JitNet.sanitizePlan` (`JitNet.kt:199-219`): `SKIP_MULTIPLE_OIF` (в строке >1 `oif`) → `SKIP_NOT_OIF_WLAN0` (`oif` нет или это не `wlan0`, в т.ч. `wlan0.1`) → `SKIP_DUPLICATE_PRIORITY` (priority встречается более одного раза) → только иначе `DELETE` |
| 2.2 | Удаление — по ПОЛНОМУ набору селекторов, не по priority | ✅ | `ip rule del ${r.spec}` (`JitNet.kt:215-216`); строки `ip rule del priority …` в коде нет (страж `NetSanitizePlanTest.exactRuleIsDeletedByFullSpec`) |
| 2.3 | On-device скрипт повторяет ту же логику и **перечитывает** список перед каждым удалением (защита от гонки) | ✅ | `JitNet.sanitizeScript` (`:221-258`): цикл `while [ $i -lt 8 ]`, на каждой итерации заново `ip rule show \| grep 'oif wlan0'`, проверка `dup`/`oifs`/`oif`, при неоднозначности — `JIT_NET_SANITIZE skipped reason=duplicate_priority\|multiple_oif\|not_wlan0` и `break` |
| 2.4 | Область не расширена, посторонние команды не добавлены | ✅ | только `ip rule del` + чтение `ip rule show`; в скрипте нет `flush`, `iptables`, `ip6tables` (страж того же теста); итог `JIT_NET_SANITIZE removed=… skipped=… left=… scope=oif_wlan0` |
| 2.5 | Тест на все исходы | ✅ | `NetSanitizePlanTest` (7): разбор строк, чужие правила, удаление полным spec, дубликат priority, несколько `oif`, похожий интерфейс `wlan0.1`, проверки скрипта |

## §3. Блокирующее 3 — id свойства AVAS sound type доказан, а не угадан

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 3.1 | Воспроизводимый разбор нативной таблицы id | ✅ | `scripts/derive_avas_sound_prop.py` (новый файл в репо): `build_table` из `libexpolitica.so` (cmd 5), `politica_h (h(i) = buf[i+1], buf[0]=61)`, `AVAS_INDEX=7` |
| 3.2 | Самопроверка на **реальных** id нашего кода, лежащих подряд | ✅ | контроль 1: `h(0x15) = 0x2140A377` = `AC_AMBIENT_TEMP`; контроль 2: `h(0x16) = 0x2140A379` = `AC_INSIDE_TEMP`; прогон: `ИТОГ: id свойства AVAS sound type = 0x21407057 (557871191), области {0, 1}`, `САМОПРОВЕРКА: OK` |
| 3.3 | В коде — константа с доказательством | ✅ | `AvasRestore.PROP_SOUND_TYPE = 0x21407057` (`AvasRestore.kt:48`, KDoc `:37-47` со ссылкой на скрипт), `AREAS = intArrayOf(0, 1)` (`:51`) |
| 3.4 | Прежняя ошибочная привязка (`0x14` → `AC_AMBIENT_TEMP`) отвергнута | ✅ | `h(0x14) = 0x21408006` ≠ `0x2140A377`; в отчёте iter33p это был 🟡, теперь доказано |
| 3.5 | Никаких «подсказок»/поиска свойства по имени | ✅ | в `AvasRestore.kt` нет `avas_sound_prop_id`, `getPropertyList` (страж `AvasRestoreTest.soundPropIdIsProvenAndDocumented`) |

## §4. Блокирующее 4 — сверка AVAS по read-back + контрольные окна

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 4.1 | Пишем только расхождения и только СОХРАНЁННЫЕ значения | ✅ | `AvasRestore.plan` (`:121-137`): `writeSound` = только разошедшиеся области; `needMode` — только при `modeNow != saved`; нечитаемое (`nowOf == null`) не пишется и отмечается в `modeUnreadable`/`soundUnreadable` |
| 4.2 | Вердикт — только по read-back; нечитаемое ≠ успех | ✅ | `AvasRestore.verdict` (`:139-153`): `!modeOk → FAILED`, `!allRead → UNVERIFIED`, `!allEqual → FAILED`, `wrote → OK`, иначе `ALREADY_OK`; тест `AvasRestoreTest.verdictIsReadBackBased` (6 исходов) |
| 4.3 | `setIntProperty` void → успех не по возврату | ✅ | `AvasRestore.writeSound` (`:421-431`) не парсит результат; подтверждение — повторное чтение в `restoreAttempt` (`:341-352`) |
| 4.4 | Снимок НЕ снимается на первом совпадении — контрольные окна до конца | ✅ | `AvasRestore.restoreAttempt` (`:346-352`): `lastWindow = attempt >= ATTEMPT_SCHEDULE_MS.size`; `clear` только при `OK/ALREADY_OK && lastWindow`; тест `controlWindowsSurviveEarlyMatch` |
| 4.5 | Расписание референса: ~5 с → ~12 с → 30 с ×5 | ✅ | `ATTEMPT_SCHEDULE_MS = [5 000, 12 000, 42 000, 72 000, 102 000, 132 000, 162 000]` (`:64`) |
| 4.6 | Планирование — на КАЖДЫЙ снимок (id), а не «один раз за процесс» | ✅ | `scheduleRestore` (`:290-312`): `if (scheduledId == snap.id) return`, `scheduledId = snap.id`, `AVAS_RESTORE scheduled id=…`; глобального флага нет |
| 4.7 | Строка попытки содержит и сохранённое, и фактическое | ✅ | `AVAS_RESTORE attempt=N id= mode_saved= mode_now= sound_saved= sound_now= wrote_mode= wrote_sound= result=ok\|already_ok\|unverified\|failed where=` (`attemptLine`, `:194-206`) |
| 4.8 | Тест проверяет поведение (план/вердикт/окна), а не наличие слов | ✅ | `AvasRestoreTest` (9): id из доказательства, round-trip снимка, TTL, план «только расхождения», 6 исходов вердикта, окна + условие снятия, планирование по id, границы записи (`setAVASMode` только сохранённым значением), ровно 2 call-site |

## §5. 🟡 (три пункта ревью)

| # | Требование | Статус | Доказательство |
|---|---|---|---|
| 5.1 | Атомарный ЕДИНЫЙ снимок AVAS (режим + sound + ts + boot) | ✅ | `SNAPSHOT_FILE = "avas_snapshot"` (`:54`), `encode/decode` (`:160-183`), `writeSnapshot` — temp + `renameTo` (`:252-266`), отдельного файла под sound нет |
| 5.2 | «Запрос ≠ состояние» и в AVAS | ✅ | `wrote_mode=`/`wrote_sound=` отделены от `mode_now=`/`sound_now=` (`:194-206`) |
| 5.3 | `LogFiles`: каталог проверяется реальной записью, при отказе — фоллбэк внутрь | ✅ | `LogFiles.dirFor` (`:72-83`) → `writable(d)` (`:85-93`: проба `.probe`), иначе `filesDir/logs`; `append` (`:120-142`) при сбое переключается на внутренний каталог и повторяет запись (`internal too`) |

## §6. Границы (не нарушены)

| # | Проверка | Статус | Доказательство |
|---|---|---|---|
| 6.1 | `ctl.restart zygote` — только внутри клика тумблера | ✅ | `JitBootTest`, `JitToggleChainTest` (зелёные в CI); компенсация сети рестарт не вызывает (`NetRestoreTest.failureAfterDisableCompensates`) |
| 6.2 | Сетевые команды — только внутри цепочки тумблера | ✅ | `JitToggleChainTest.netCallsStayInsideToggleChain`: `JitNet.` только в `JitNet.kt` + `AppPerformance.kt` |
| 6.3 | AVAS — только сохранённые значения, никакой «муты» | ✅ | `AvasRestoreTest.writesOnlySavedValues`: `setAVASMode` вызывается только с `value` из снимка; `setAVASMode(0/1/2/-1)` в коде нет; `setSoundType`, `audioserver`, `.wav`, `avas_muted_saved` отсутствуют |
| 6.4 | Ничего в `/system`, `setenforce`, `pm install`, туннели — не добавлено | ✅ | в `JitNet.kt` нет `setenforce`, `pm install`, `svc wifi enable` (страж `restoreLogsRequestVsState`) |

## §7. CI (реальный счёт)

- `42827223054` (`a36a2b7`) — ❌ 160 PASSED / 5 FAILED (база iter33p).
- `42828995241` (`8b6ee74`) — ✅ 164 / 0.
- `42830831600` (`3646901`), `42832827628` (`d33eaa5`), `42834993959` (`28dcdfc`) — ✅ 164 / 0.
- `42837804148` (`8f4ceba`, первая версия кода) — ❌ 172 PASSED / 2 FAILED:
  `BuildMarkerTest.versionIs61` (тест ждал версию 61) и `NetRestoreTest.abortWhenStateNotSaved`
  (страж ловил слово `svc wifi disable` в комментарии, а не команду) — оба исправлены.
- **`42840556489` (`8b24928`, HEAD; код + тесты iter33p-fix1) — ✅ 174 PASSED / 0 FAILED**,
  job `129914321552`, `BUILD SUCCESSFUL in 4m 16s`, артефакт `13775398418` (177 177 169 Б).

## §8. Известные ограничения (честно)

1. **Полевой прогон не выполнялся.** Условие ревьюера: JIT-прогон только после повторного REVIEW и с логом. Ни одна строка ниже не подтверждена на машине.
2. **Гарантии восстановления сети/AVAS нет.** Реализован механизм (сохранение → возврат → read-back → `keep_saved` при неудаче), но: Wi-Fi может не подняться при отказе `WifiManager`/прошивки (3 попытки × 3 с, лог честный), AP зависит от наличия `startSoftAp`, AVAS — от доступности `CarPropertyManager`/`setAVASMode` в конкретной прошивке. При недоступности пишем `unverified`/`failed` и **не** выдаём успех.
3. **Компенсация — 2 попытки** и только в той же операции; если процесс убьют между `svc wifi disable` и компенсацией, возврат произойдёт на следующем старте по `jit_net_saved` (TTL 10 мин), позже — состояние считается просроченным и не применяется (осознанно: не откатывать ручные изменения владельца).
4. **Доказательство id AVAS** опирается на разбор `libexpolitica.so` + две контрольные точки нашего кода (`h(0x15)`, `h(0x16)`). Это сильнее прежнего, но всё же косвенное подтверждение: при полевом прогоне ждём `AVAS_SAVE sound_type=<число>` — если звук читается, id верный; если `reason=no_readback` — писать не будем.
5. **Локальная сборка недоступна** (нет Android SDK), компиляция и тесты проверяются только CI.
6. `JitBootMark`/`jit_net_saved` живут в префах приложения; очистка данных приложения стирает план возврата.

## §9. Файлы

- `app/src/main/java/com/example/voiceapp3/tools/AvasRestore.kt` — переписан (§3, §4).
- `app/src/main/java/com/example/voiceapp3/tools/JitNet.kt` — переписан (§1, §2).
- `app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt` — §1 (прерывание, компенсация, ранний возврат сети).
- `app/src/main/java/com/example/voiceapp3/tools/LogFiles.kt` — §5.3.
- `scripts/derive_avas_sound_prop.py` — воспроизводимое доказательство id (§3).
- Тесты: `NetSanitizePlanTest` (новый, 7), `NetRestoreTest` (7), `AvasRestoreTest` (9), `LogFilesTest` (6), `JitToggleChainTest` (5).
