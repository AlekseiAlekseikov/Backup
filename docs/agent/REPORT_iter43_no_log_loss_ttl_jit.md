# Отчёт iter43 — «никакой бесследной потери»: журнал, TTL-снимки, JIT-оркестрация

**Ветка:** `fix/v2` (только она; `--force` не использовался, `main` не пушился).
**База:** `9debbd5` (iter42, CI `43274357006` success).
**Финальный код:** `ddabcab`; **HEAD ветки:** `0b9070a` (`6f1042b` — код+тесты, `9c311df` — маркер,
`ddabcab` — §1.4 остатки, `a538b5c` — WORKLOG + отчёт, `0b9070a` — маркер сборки).
**`SOURCE_SHA`** (`app/buildinfo.properties`): `a538b5c` — коммит, который содержит код и документацию.
**versionCode/versionName:** `61` / `1.0.61` — **не менялись** (установленная у владельца версия).
**Уровни доказательности:** код / JVM-модель / Android-интеграция / CI / ГУ. Ниже у каждого пункта указано,
чем именно он подтверждён. **На головном устройстве (ГУ) ничего не выполнялось**: JIT-клик, `umount`,
`mount`, `zygote`, `reboot`, `setprop`, `svc` не запускались.

---

## 1. Что изменилось и чем подтверждено

| § | Требование | Статус | Доказательство (уровень) |
|---|---|---|---|
| 1.1 | Нет подмены исходной строки уведомлением | ✅ | `AppLog.kt:400–470`; JVM: `check_applog.sh` → `APOLOG_TYPES_OK` (типы), CI: компиляция |
| 1.1 | Уведомление и событие — отдельные записи с учётом результата каждой | ✅ | `AppLog.kt:437–465` (`dropped:diag_budget`, `LOG_WRITE_FAILED`, `LOG_LEGACY_FAILED` каждый со своим `Result`) |
| 1.1 | Лимит скорости при наличии ротации (не ротировать каждые ~150 КиБ) | ✅ | `AppLog.budgetAllow` (`AppLog.kt:366`) — только проверка контекста |
| 1.1 | Тест production-пути с инъекцией каталога | 🟡 | `LogFiles.appendTierForTest`/`appendToDir` — JVM на реальных файлах (`JitIter42Test`, `JitIter43Test`). `AppLog` в JVM-стенде не компилируется (стаб), сам путь `AppLog → LogFiles` проверяется только типами (`check_applog.sh`) и CI-сборкой, но НЕ исполняется |
| 1.2 | Возрастающая нумерация частей, один активный файл | ✅ | `LogFiles.activeName/rotatedName/parseOwn`; тест `twelvePartsKeepUniqueIncreasingNumbersAndOneActive` |
| 1.2 | Лимиты по числу файлов и байтам, не по диапазону номеров | ✅ | `LogFiles.pruneTier`, `enforcePartsLimit`; тест `partsOfNeverTouchesImportantOrLegacy` |
| 1.2 | Не дописывать в закрытую часть | ✅ | выбор активной части под критической секцией (`LogFiles.kt:355`) |
| 1.2 | Реальные UTF-8 байты, а не `String.length` | ✅ | **найден и исправлен дефект**: `bytes[cut]` вместо `bytes[cut-1]`; тест `lineIsSplitByUtf8BytesNotByChars` (U+FFFD отсутствует) |
| 1.2 | Полный круг 6 частей + ≥10 ротаций | 🟡 | ротация по размеру на реальных файлах — `LogFiles42Test.rotationKeepsEveryLineExactlyOnce` (140×64 КиБ → 2 части); 13 частей с уникальными номерами — `twelveParts...`. «≥10 ротаций по 8 МиБ» (~88 МиБ записи) в прогон не включены, чтобы не съедать диск стенда — это ограничение теста, а не кода |
| 1.3 | Чёткие лимиты/ретеншн, сумма своих файлов | ✅ | `LogFiles.DirTotal`, `totalsOf` (`LogFiles.kt:616–700`), `LOG_TOTALS`/`LOG_TOTALS_SUM` |
| 1.3 | external+internal — не выдавать 64+16 МиБ за абсолютный предел | ✅ | `LOG_TOTALS_SUM … caps="… НА КАТАЛОГ: это не абсолютный предел, каталогов два"` |
| 1.3 | `partsOf` не задевает `voiceapp3-important.log` | ✅ | тест на фактических файлах (+ legacy `voiceapp3.log`) |
| 1.3 | Частичные отказы `delete`, ошибки листинга | ✅ | `pruneTier` считает удалённым только подтверждённое; `listed=false` вместо «0 байт» |
| 1.3 | Свободное место 0 и «неизвестно» | ✅ | `freeSpace` → `null` при отсутствии каталога (`LogFiles.kt:198`), `space:zero` vs `space:unknown`, `LOGFILES_SPACE_UNKNOWN` |
| 1.3 | `rotateNow` без подтверждённого маркера ≠ success | ✅ | `LogFiles.rotateNow` возвращает `true` только при `isFile && length>0` |
| 1.3 | fsync/flush — честная формулировка | ✅ | `runCatching { out.fd.sync() }` + комментарий «защита от смерти процесса, не от сбоя питания» |
| 1.3 | Legacy-писатель `AppFileLogger`/`voiceapp3.log` | 🟡 | учтён в `DirTotal.legacyBytes`; сам legacy-писатель (`AppLog.writeRawLine`/`writeRawSync`) оставлен как был — его объём по-прежнему не входит в квоту (это заявлено в отчёте, а не скрыто) |
| 1.4 | Никаких секретов/токенов/base64/payload в журнале | ✅ | `LogRedact` (`LogRedact.kt:23`), вызовы `AdbClient.kt:275,415,511,654,1898,1966,2221,2232` |
| 1.4 | Тесты с маркерами искусственных секретов | ✅ | `LogRedact43Test` (6 тестов) |
| 2.1 | `END` не выдаёт себя за записанный | ✅ | `END written=unconfirmed` + `_END_CONFIRM`; `InternetDiagTest.endSummaryIsFactsNotDiagnosis` (`written=unknown`, `write_reason=truncation_not_checkable`) |
| 2.1 | Тесты на фактических файлах (fallback на первой/последней DATA и на END, ротация на END) | 🟡 | fallback секции — `JitIter41DiagTest.fallbackInTheMiddleIsReportedAsTwoSinks`; **ротация ровно на `END`** отдельным тестом не покрыта: `END` пишется через `AppLog`, который в JVM-стенде не исполняется |
| 2.2 | Разделение статусов и критерии `completed` | ✅ | `completed` ⟺ канал ∧ секции ∧ `verdict=full`; причины `sections_not_ok`, `write_failed`, `truncation_not_checkable`, `budget_truncated_this_block` |
| 2.3 | Ограниченный retry, отмена устаревшей страховки, без параллельного сбора | ✅ | `JitRef.DiagRunner` (`JitNetCore.kt:1003`); `DiagRunner43Test` (6 тестов, синтетический планировщик) |
| 3 | pre — bounded «до» или честная метка | ✅ | `pre_confirmed`/`pre_overlap`/`pre_late`/`pre_unknown` (`JitNetCore.kt:1178`), `TtlSnapshotFlow43Test` |
| 3 | post — после фактического завершения TTL | ✅ | `post_after_apply` vs `post_without_confirmed_apply`; пара заказывается в `ToolsBackend.Toggle.TTL_65` |
| 3 | on/off не скрывать rate-limit'ом | ✅ | отдельная квота `userMaxPerHour=12`, `tryAcquireUser*`; тест `ownerActionsHaveTheirOwnQuota` |
| 3 | trailing snapshot при значимом изменении Wi-Fi | ✅ | `JitNet.requestEventSnapshot(..., scheduleTrailing)`, `status=trailing_scheduled`; `remainingMs()` |
| 3 | Сеть/TTL не меняются | ✅ | в §3 нет ни `iptables`, ни `setprop`, ни записи TTL; только чтение |
| 4 | `Prefs2.dps` не скрывает исключение возвратом CPS | ✅ | `Prefs2.dpsOrNull` (`Prefs2.kt:33`), использование `JitNet.kt:116`; «DPS == CPS» пишется как `dps_distinct=false` |
| 4 | Fail closed при изменении набора хранилищ | ✅ | `SnapshotWrite.appeared/absent` + `JitNet` блокирует клик; `JitIter42Test.storeThatVanishesBetweenReadAndWriteBlocksEverything` |
| 4 | Rollback только своих ключей, честный частичный отказ | ✅ | `JitNet.restorePrevious` (`ok/attempted` + `detail`), при `prev_unknown` ключи не трогаются |
| 4 | Обратимость `umount` | ⛔ | обратимость не доказана. **ИСПРАВЛЕНО в iter44 §1.1:** фраза «шаг остаётся выключенным» была НЕВЕРНОЙ — `umount -l` исполняется на каждом клике (`JitNetCore.kt:722-725`, вызов из `chainScript`, `MOUNT_STEP_NOTE="mount_step=present"`); см. `REPORT_iter44_...` §1.1 |
| 5 | `assembleRelease` без тестов, отдельный `testDebugUnitTest` | ✅ | привязка снята в iter42 (`app/build.gradle.kts`), CI гоняет только `assembleDebug` |
| 5 | Реальный `--dry-run` | ❌ | **не выполнен**: в песочнице ~2 ГБ RAM, Gradle daemon падает (OOM). Семантика проверена на мини-проекте Gradle 9.1 (iter42). Выдавать это за прогон реального проекта нельзя |
| 5 | CI-тесты | 🟡 | workflow запускает только `assembleDebug`; прав на `.github/workflows/` нет (scope `repo`, без `workflow`) — предложен `docs/agent/ci-unit-tests.diff` |
| 5 | D8 metadata 2.4.0 vs 2.3.0 | 🟡 | в логе CI **4649** предупреждений `WARNING: D8` о разборе kotlin metadata (R8 9.0.32 не знает metadata Kotlin 2.4.0). Сборка при этом `BUILD SUCCESSFUL`; официальную таблицу совместимости в этой итерации не перепроверял — пункт остаётся открытым |

## 2. Воспроизводимые результаты ротации (JVM, реальные файлы)

1. `LogFiles42Test.rotationKeepsEveryLineExactlyOnce`: 140 строк по 64 КиБ (≈8.9 МиБ) → ≥2 части,
   каждая строка присутствует **ровно один раз**, в логе есть `LOG_ROTATE tier=main`, строки не обрезаны.
2. `LogFiles43PartsTest.lineIsSplitByUtf8BytesNotByChars`: строка 140 000 × `я` (280 000 байт UTF-8,
   `String.length` = 140 000) → записана частями с `LOG_LINE_SPLIT n=1/2`, файл декодируется без `U+FFFD`.
3. `LogFiles43PartsTest.twelvePartsKeepUniqueIncreasingNumbersAndOneActive`: 13 частей (номера 0…12),
   номера уникальны, активная — ровно одна (`…12.log`), удаление старой части номер не переиспользует.
4. `LogFiles43TotalsTest.partsOfNeverTouchesImportantOrLegacy`: `pruneTier` удаляет только свои
   просроченные части; `voiceapp3-important.log` и `voiceapp3.log` остаются на месте.

Как забрать все части владельцу: `adb pull /sdcard/Android/data/com.example.voiceapp3/files/logs/`
(и `…/files/logs/` для внутреннего каталога при фоллбэке) — файлы `voiceapp3-DD.MM.YYYY[.N].log`
и `voiceapp3-v-DD.MM.YYYY[.N].log`.

## 3. Последовательность сетевых снимков (что теперь пишется)

```
SNAP reason=ttl_on_pre  phase=pre  label=pre_confirmed|pre_overlap|pre_late|pre_unknown
TTL_SNAP_PAIR op=ttl-N want=true applied_at=…
SNAP reason=ttl_on_post phase=post label=post_after_apply|post_without_confirmed_apply
SNAP reason=net_change  status=trailing_scheduled delay_ms=<остаток интервала>
DIAG_ORCH reason=… status=started|completed|retry_scheduled|exhausted|skipped|stale_result
```
`pre_*` и `post_*` считаются по фактическим временам (`appliedAt`), а не по намерению; отсутствие
подтверждённого применения даёт `post_without_confirmed_apply`, а не «после».

## 4. Открытые дефекты (честно, отдельно)

1. **`--dry-run` реального проекта не выполнен** (OOM песочницы) — §5.
2. **CI без unit-тестов**: workflow = `assembleDebug`; diff для добавления `testDebugUnitTest` не применён
   (нет прав на workflows).
3. **`AppLog → LogFiles` не исполняется в JVM-стенде** (стаб `object AppLog`): путь проверен типами и
   компиляцией, поведение — только на файлах через `LogFiles` напрямую.
4. **Ротация ровно на строке `END`** не покрыта отдельным тестом.
5. **Legacy `voiceapp3.log`** вне квоты: объём показан (`legacy=`), но не ограничен — сознательно, чтобы не
   ломать существующий путь; требует решения владельца.
6. **`umount -l`**: обратимость не доказана. **ИСПРАВЛЕНИЕ iter44 §1.1:** утверждение «шаг выключен» ложно —
   шаг исполняется (`JitNetCore.kt:714-726`, `umount -l` по точкам из `grep ' /data/app/' /proc/mounts` в том
   shell, откуда запущен скрипт), до него идут `svc wifi disable` и дренаж `ip rule`, после — `setprop
   dalvik.vm.usejit` и `ctl.restart zygote`. Условие исполнения — клик существующего тумблера JIT и успешная
   проверка блокировок; план проверки на ГУ — только по согласованию.
7. **D8/kotlin metadata 2.4.0** — предупреждения не устранены (4649 шт.), сборка успешна.

## 5. CI (факты)

| run | SHA | результат | время |
|---|---|---|---|
| `43274357006` | `9debbd5` (база, iter42) | success | — |
| `43288136880` | `9c311df` | **success**, `BUILD SUCCESSFUL in 3m 36s`, 43 задачи | job `131426893391` |
| `43290598424` | `ddabcab` | **success** | — |
| `43293595763` | `0b9070a` (HEAD: отчёт + `SOURCE_SHA`) | **success** | — |

Локально: `bash /home/user/runtests.sh` → `OK (351 tests)`; `bash /home/user/scripts/localjvm/check_applog.sh`
→ `APOLOG_TYPES_OK`.

## 6. Что НЕ доказано (и не заявляется)

- Работа на ГУ: `LogFiles.append(context)`, реальные `usableSpace`/`SharedPreferences`/`WifiManager`,
  поведение при полном диске, выживание при убийстве процесса — не проверялось.
- Android-интеграция пары TTL-снимков (нужен реальный тумблер на устройстве).
- Отсутствие секретов в логе — не гарантия: `LogRedact` снижает утечку, но не доказывает её отсутствие.
  IP-адреса и маршруты намеренно не маскируются (нужны для диагностики сети) — в публичный отчёт они
  не копировались.
