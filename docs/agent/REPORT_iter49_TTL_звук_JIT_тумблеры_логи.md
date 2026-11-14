# REPORT iter49 — довести TTL, звук, JIT, тумблеры, логи

**Ветка:** `fix/v2` · **версия:** `versionCode=63`, `versionName=1.0.63` (**не повышалась**) ·
**SOURCE_SHA:** `f33fbc6` (коммит с кодом этой итерации; маркерные коммиты отчёта в него не входят)
· **На ГУ ничего не запускалось**, APK владелец собирает и подписывает сам.

Мандат: `uploads/ПРОМПТ_iter49_ДОВЕСТИ_TTL_ЗВУК_JIT_ТУМБЛЕРЫ_ЛОГИ.md` (§0–§11).
Уровни проверки, использованные ниже:

| уровень | что это | чего не доказывает |
|---|---|---|
| JVM-тесты (`bash runtests.sh`) | 493 теста, компиляция `kotlinc` + `android.jar`-стабы | не Android runtime, не ГУ |
| bash-эмулятор iptables | модель вывода `-S`, сценарии S1–S12 | не ядро ГУ, не target `TTL` |
| чтение кода | `файл:строка` | не поведение на машине |
| CI `Build APK` | только сборка | тесты CI не запускает |

---

## 1. Чек-лист §5 — TTL

| требование мандата | статус | где |
|---|---|---|
| `grep "-j VAPP_TTL"` совпадает с `VAPP_TTL_OTHER` → сравнивать целое имя target | ✅ | `TtlScript.kt` `jump_specs()`: awk сравнивает токен после `-j` (`a[i]=="-j" && a[i+1]==our`) |
| учитывать `-g` (goto), пробелы/quotes, синтаксис конкретного iptables | 🟡 | `-j` учтён; `-g` **не** считается нашим переходом, но и наша цепочка с `-g` не распознаётся как наша → безопасный отказ; цитаты/match → `TTL_DEL … rc=refused reason=quoted_spec_needs_owner` |
| отказ безопаснее неверного разбора | ✅ | `quoted_spec_needs_owner`, `reason=inode_mismatch` (звук), `READ_UNKNOWN` |
| имя `VAPP_TTL` само по себе не доказательство (`-F` мог очистить коллидирующую цепочку) | ✅ | `chain_state()` → `absent|empty|ours|collision|unknown`; владение = содержимое РОВНО `RULE_V4/V6` |
| при конфликте не менять чужое | ✅ | `TTL_TRY action=skip_all reason=family_not_owned`, `TTL_OWN … action=keep`; `-F`/`-X` не выполняются |
| legacy `--ttl-set 65` / совпадение интерфейса не доказывает владение | ✅ | `TTL_LEGACY … action=keep reason=ownership_unproven`, чужие правила только перечисляются (`TTL_FOREIGN`) |
| несколько переходов, дубликаты | ✅ | ON снимает только точные наши переходы и сводит к одному (сценарий S5 эмулятора) |
| отказ каждого шага | ✅ | `TTL_ADD … rc=`, `TTL_DEL … rc=0|nonzero|refused`, `chain_flush_rc`, `chain_del_rc` |
| TTL только на проверенном uplink, не p2p/rndis/ncm/usb/AP | ✅ | `TTL_UPLINK_REJECT uplink=p2p… reason=carplay_iface`; тест `scriptDeclaresItsOwnLimits` |
| §5.2 `2>/dev/null` + пустой результат ≠ «правил нет» | ✅ | `READ_V4/READ_V6=ok|failed`, `count_ttl_rules()` = `-1` при отказе, OFF → `result=UNKNOWN reason=read_failed` |
| OFF подтверждать только успешным считыванием | ✅ | `TTL_OFF … result=OK` только при `VERIFY_*=OFF_OK` и `READ_*=ok` |
| ON проверять по точным значениям target/65/HL, всем прыжкам, дубликатам, интерфейсу | ✅ | `VERIFY_V4=OK` требует `chain=ours && J4=1 && JS4=="-o <uplink> -j VAPP_TTL"` |
| не объявлять scoped success при частичной очистке | ✅ | `VERIFY_* = FAIL|COLLISION|UNKNOWN|NOT_APPLIED`; вердикты `SCOPED_OK|SCOPED_V4_ONLY|CHAIN_COLLISION|READ_UNKNOWN|APPLY_FAILED|NOT_APPLIED` |
| «счётчик/правило ≠ TTL на проводе», «ассоциация Wi-Fi ≠ интернет» | ✅ | `TTL_CHECK … real=UNKNOWN method=rules_not_wire reason=wire_not_measured`, `TTL_RESULT wire_v4=not_measured` |
| §5.3 намерение+поколение одной транзакцией | ✅ | `Prefs2.putBooleanAndBumpLongEx` (один `commit()` в каждую копию, readback обоих ключей), `NetworkTtl.beginIntent` возвращает `-1` при неподтверждённости |
| failed commit → не `applied` | ✅ | `gen=-1` → `TtlRunner` не публикует `applied` |
| `ReentrantLock` процесс-локальный — не заявлять межпроцессную гарантию | ✅ | KDoc `TtlRunner`/`ProcLock`; межпроцессно работает только lock-файл `ProcLock` |
| реальные root-ready/network события, идемпотентность, жизнь после lifecycle | ✅ (iter48) | `AdbClient.onChannelReadyOnce`, `NetworkTtl` receiver; повторно проверено чтением кода |

**Сценарии bash-эмулятора (модель `-S` с цитатами и порядком строк).** Проверено: чистая машина
ON→OFF (`SCOPED_OK`→`OFF_OK`, правила и цепочки пусты); чужая `VAPP_TTL_OTHER` + чужое `ttl-set 64`
(остаются байт-в-байт, OFF → `PARTIAL reason=foreign_rules_remain`); одноимённая чужая цепочка
(`collision`, `skip_all`, ничего не создаётся); отказ чтения (`READ_UNKNOWN`, OFF → `UNKNOWN`);
наш переход без `-o` (снимается и ставится scoped); наша цепочка с двумя переходами (сводится к
одному); отказ `-A` (полный откат, `APPLY_FAILED`, состояния не остаётся); переход с кавычками
(`rc=refused`, цепочка не чистится, `FAILED reason=own_rules_left`); отсутствие `ip6tables`
(`VERIFY_V6=SKIPPED`, `SCOPED_V4_ONLY`, OFF → `OFF_OK v6=SKIPPED`).

🟡 **Честно:** `-g` (goto) не трактуется как наш переход — при наличии `-g VAPP_TTL` мы увидим
цепочку как `ours`, но переход не снимем и честно получим `FAILED/partial`, а не «OK».
Это осознанный выбор «отказ безопаснее», а не доказанная полнота разбора.

## 2. Чек-лист §4 — звук (монтирования)

| требование | статус | где |
|---|---|---|
| `root` mountinfo — путь от корня ФС, literal-префикс не доказательство | ✅ | `LockMountScripts.own_layer()`: корень = staging И `stat -c '%d:%i'` цели = staging; иначе `FOREIGN_MOUNT … reason=ownership_not_proven` |
| связывать stage-файл с underlying mount/root, device/inode в нужном namespace | ✅ | `STAGE_DEV_INO`, `OWN_PROOF … method=dev_ino`; namespace проверяется до действий |
| `tail -1` не всегда верхний слой | ✅ (признано) | `MOUNT_ORDER method=mountinfo_order top_guaranteed=no`; решение опирается ещё и на dev:inode |
| проверка namespace ДО действий | ✅ | `LockSound.kt:1195–1215`: `NS_PRE … checked_before_action=yes`; при различии `action=none`, реестр цел |
| `foreignLeft` больше не исключается из `unproven` | ✅ | `pending_foreign_layer_above`, запись остаётся в реестре |
| `Mount → MOUNT_VERIFY_FAIL`, реестр пишется только на `MOUNTED_OK` | 🟡 | реестр по-прежнему пополняется только проверенными `MOUNTED_OK`; частичная/неопределённая операция (`MOUNT_VERIFY_FAIL`) в реестр НЕ попадает → при смерти процесса между bind и verify слой может остаться неучтённым. Не исправлено, вынесено в §6 отчёта |
| OFF/lazy `umount` только с readback, различать detach и освобождение | ✅ | `UMOUNTED_OK` / `UMOUNT_DETACHED … reason=lazy_table_entry_may_remain` / `UMOUNT_FAILED` |
| не обещать возврат к стоку после uninstall | ✅ | в отчёте и логах: «к стоку не заявляем», staging удаляется только при `OWN_LAYERS_TOTAL=0` |

## 3. Чек-лист §6 — настройки и видимость

| требование | статус | где |
|---|---|---|
| lock-файл не зависит от CPS/DPS-контекста | ✅ | `Prefs2.lockDir()` (device-protected `filesDir`, иначе обычный) — раньше boot и UI брали РАЗНЫЕ lock-файлы |
| `confirmed` без успешного `commit` | ✅ | `Save.confirmed` = re-read + ревизия; `conflict` теперь исключает подтверждение в `putStringLocked` |
| различать durable/partial/error/conflict/unknown по всем типам | 🟡 | boolean/string/long — да (`getBooleanOrNull`, `getLongOrNull`, `put*Ex`); `putInt/getInt` остались «двузначными» (§6 отчёта) |
| `ToolsBackend` больше не выдаёт `default` как факт | ✅ | `<key>State` + `prefsUnknown` |
| `*State/prefsUnknown` доходят до видимого UI | ✅ | `ToolsPanel.addToggle`: `prefsState == "unknown"` → подпись «состояние не подтверждено» (без красного текста, без новых кнопок) |
| холодное открытие, поздний ответ, пересоздание Activity | ✅ (iter45–48) | `acceptsSeq`, `stateLoaded`/`snapshotKeys`, `rebuild()` |
| зависимость «Процент заряда» → «Цифровая наружная» | ❌ | **не проверено и не менялось**: в коде такой зависимости нет (`ToolsPanel.kt:357–361` — независимые строки). Утверждать, что она нужна, по коду нельзя; требуется наблюдение на ГУ |
| число процессов / direct boot | 🟡 | приложение одно­процессное (в манифесте нет `:remote`); DPS-доступность обрабатывается `dpsOrNull`. Многопроцессная корректность SharedPreferences (кэш другого процесса не инвалидируется) **не заявляется** |

## 4. Чек-лист §7 — JIT

| требование | статус | где |
|---|---|---|
| разрешать клик по старту zygote/таймеру — исправить ПОЛИТИКУ, не текст | ✅ | `JIT_OP_ABANDONED exit=zygote_after_mark|window_expired late_answer_possible=1 clicks=allowed snapshot_reused=no` |
| лог не утверждает невозможность позднего исполнения | ✅ | формулировка «команда не может оставаться в очереди» удалена; тест `expiredUndefinedIsAnExplicitExitNotAProofOfNoLateRun` |
| проверяемая модель владения с id операции | ✅ | `onLateOutcome` сверяет `cur.mark.op == opId`; иначе `JIT_CHAIN_LATE … attributed=no abandoned_op=…` |
| конкретный выход из неопределённости, без авто-`zygote`, без бессрочного запрета | ✅ | выход только по клику владельца; автоматических запусков нет |
| `ZygoteStart.kt` CLK_TCK, PID reuse, оба zygote, boot identity, коррекция часов — только диагностика | ✅ | сигнал не доказывает выполнение нашей команды; прямо написано в логе |
| транспорт: dispatch/очередь/поздний ответ/timeout/разрыв root/убийство/reboot | 🟡 | «команда завершилась» доказывает только поздний callback; отсутствие callback не доказывает ничего — это записано и в логах, и в отчёте |

## 5. Чек-лист §8 — логи

| требование | статус | где |
|---|---|---|
| state machine ротации: `.new` + дозапись в active → дубликат | ✅ | `FileLogCore.writeUnderLock`: если в `.new` были данные до записи — строка остаётся в `.new` (`rotate_rename_failed:kept_in_new`), в активный не дублируется |
| гарантии `ATOMIC_MOVE` по API/ФС | 🟡 | пробуем `ATOMIC_MOVE`, при отказе — обычный `REPLACE_EXISTING`; обе гарантии не заявляются |
| `:348–357` при отказе переноса `.drain` исключение скрывается и `.drain` удаляется | ✅ | `.drain` удаляется только при подтверждённом возврате в spill (`if (restored) … delete()`) |
| spill по PID/.drain: смерть процесса, PID reuse | 🟡 | spill привязан к PID процесса; после переиспользования PID чужой spill может быть слит — данные при этом не теряются, но и «наша строка» не доказывается |
| `AppLog → LogFiles` (датированная ротация, lock, NUL) vs legacy `AppFileLogger` | 🟡 | оба канала разделены и описаны; `LogFiles` не менялся в этой итерации |
| NUL внутри файла, не только в хвосте | ✅ | `FileLogCore.nulInside()` + `LOG_NUL_INSIDE`/`LOG_NUL_SCAN_FAILED` при инициализации |

## 6. Чек-лист §9 — окна и прочее

| требование | статус | где |
|---|---|---|
| `WindowDoors.kt:550–562` owners ≠ ABA | ✅ | `ventGen` (поколение набора): сверяется содержимое И поколение, `aba_checked=yes` |
| AVAS — только read-only диагностика | ✅ | `AvasIndependenceTest`; AVAS не участвует в JIT |
| строка состояния — плагин, не убивать | ✅ | не менялось |
| цифровой климат — уставка | ✅ | не менялось |
| `SCREEN_ON/USER_PRESENT` не в main thread | ✅ (iter48) | через `BgWorker`/`AdbClient.execute` |

## 7. Открытые пункты (⛔/❌ — не сделано и не заявляется)

1. ❌ **Зависимость видимости «Процент заряда» → «Цифровая наружная»** — в коде отсутствует,
   менять без подтверждения поведения нельзя (проверять только на ГУ).
2. 🟡 **`MOUNT_VERIFY_FAIL` не попадает в реестр** (`LockSound.kt:988–1008`): смерть процесса
   между bind и verify может оставить наш слой неучтённым. Нужен «журнал намерения bind» +
   сверка на старте — объём больше одной правки, в iter49 не делалось.
3. 🟡 **`Preis2.putInt/getInt`** остались двузначными (не критичны для тумблеров, но
   неопределённость там не выражается).
4. 🟡 **`-g`/goto** не снимается и не распознаётся как наш переход (безопасный отказ).
5. 🟡 **Многопроцессность SharedPreferences**: кэш чужого процесса не инвалидируется, поэтому
   межпроцессная достоверность настроек не заявляется. Приложение однопроцессное.
6. 🟡 **PID reuse в spill-логах**: строка может быть слита из «чужого» spill, но не теряется.
7. ❌ **Проверка на ГУ** (звук слышен, TTL реально 65, JIT-компиляция, тумблеры) — не делалась.

## 8. Таблица реальных тумблеров

Состав панели (`ToolsPanel.addToggle`) и enum `ToolsBackend.Toggle` не менялись в iter49 —
см. `REPORT_iter48_TTL_звук_JIT_логи_тумблеры.md` §8 (20 ключей в панели, 14 в enum).
Единственное изменение поведения: при `State=unknown` подпись тумблера говорит
«состояние не подтверждено» вместо «состояние ещё не прочитано», а положение берётся из
сохранённого намерения и НЕ выдаётся за факт.

## 9. История версий: факты отдельными строками

| | 1.0.62 | 1.0.63 |
|---|---|---|
| TTL владение | по подстроке `grep "-j VAPP_TTL"` (задевало `VAPP_TTL_OTHER`) | по содержимому цепочки + точному target |
| TTL отказ чтения | 0 правил (выглядело как «правил нет») | `READ_*=failed`, `UNKNOWN reason=read_failed` |
| намерение+поколение | две записи без проверки | одна транзакция, `gen=-1` при отказе |
| lock prefs | `app.filesDir` (разный для CPS/DPS) | device-protected каталог (один и тот же) |
| звук: владение слоем | literal-префикс | root+dev:inode; namespace ДО действий |
| звук: чужой слой сверху | исключался из `unproven` | остаётся pending |
| JIT выход из неопределённости | «команда не может быть в очереди» | `JIT_OP_ABANDONED … late_answer_possible=1` |
| логи: ротация | могла дать дубль при `.new` | `.new` не дублируется в активный |
| логи: NUL | только хвост | по всему файлу |
| окна: поздний readback | сравнение содержимого (ABA) | содержимое + поколение |
| проверка на ГУ | нет | **нет** |

## 10. Референс (пересчёт md5)

В этой итерации референс **не пересчитывался и не скачивался**: изменений, требующих сверки с
`w0/e2.run`/`la`, не вносилось (JIT-скрипт не трогали). Ранее зафиксировано: ZIP
`8a65c6c2…`, `CentralEXAuto_v3.3.9.apk` `c3f16641…`, плагин 1.4.1 `5aab306b…`. Ветка
восстановления AP в референсе **есть** (не заявлять обратное).

## 11. Безопасный план испытания на ГУ (владельцу)

1. Установить APK, собранный из `SOURCE_SHA=f33fbc6` (подпись — своя).
2. **Логи:** открыть «Инструменты» → включить подробный лог; перезапустить приложение. Проверить
   в `voiceapp3-verbose`: нет `LOG_NUL_INSIDE`, нет `rotate_rename_failed` (кроме ожидаемого
   `.1`-каталога), `LOCKSOUND_UNMOUNT NS_PRE … same=true` до любых `UMOUNTED_OK`.
3. **TTL:** при подключённом Wi-Fi включить «TTL 65». Ожидаемо `TTL_CHECK … → SCOPED_OK`,
   `method=rules_not_wire` (это НЕ измерение на проводе). Выключить → `result=OK`.
   Если на машине есть чужие `ttl-set` — увидите `PARTIAL reason=foreign_rules_remain`
   (**это норма, чужое не удаляем**). Если `CHAIN_COLLISION` — TTL не применялся и ничего не
   сломано; снимок `iptables -t mangle -S` до/после приложить к логу.
4. **Звук:** выбрать WAV, включить. Ожидаемо `MOUNTED_OK` + `VERIFY … SUCCESS`; «слышно/не слышно»
   проверяется ухом. При выключении — `LOCKSOUND_UNMOUNT_RESULT proven=N foreign_left=M`;
   при `pending_foreign_layer_above` наши слои могли остаться под чужим — сообщите, реестр цел.
5. **JIT:** один клик тумблера. При `JIT_CHAIN_ERROR reason=channel_lost` **не повторять клик
   сразу**: сначала выход из неопределённости (`JIT_OP_ABANDONED`) и `JIT_CHAIN_LATE`.
   Перезагрузка ГУ лечением не считается.
6. Не использовать: `setenforce 0`, `pm install`, туннели/5555, `ctl.restart adbd`,
   `CarplayMonitor`. Точку доступа ГУ и CarPlay не трогать.

## 12. Тесты и среда

```
$ bash runtests.sh          # /home/user, клон fix/v2
=== compile ===
=== run ===
OK (493 tests)
```

- JVM: `kotlinc 1.9.24`, `junit-4.13.2`, `android.jar` (API-стабы), `-jvm-target 11`.
- В JVM-прогон входят: `TtlScript`, `TtlVerify`, `TtlRunner`, `JitNetCore`, `MountInfo`,
  `LockMountScripts`, `FileLogCore`, `LogFiles`, `WindowDoors` (частично), тесты `Iter46–Iter49`.
- **Не входят** (Android-зависимости, проверяются только CI-сборкой и на устройстве):
  `Prefs2`, `NetworkTtl`, `LockSound`, `ToolsBackend`, `ToolsPanel`. Правки в них проверены
  компиляцией `Prefs2.kt` отдельным прогоном `kotlinc` (с заменой API-24 вызова, которого нет в
  локальном `android.jar`) и чтением кода — **это не равно проверке на Android**.
- bash-эмулятор iptables: сценарии S1–S12 (см. §1). Эмулятор — стенд, не код.

## 13. Коммиты итерации

| SHA | что |
|---|---|
| `65da39e` | §5: TTL — владение по содержимому, отказ чтения как неизвестность (+`Iter49Test`) |
| `8123481` | §5.3/§6/§7: транзакция намерения, durable-чтение prefs, честный выход JIT |
| `1894b0a` | §4/§8/§9: dev:inode, namespace до действий, ротация без дублей, ABA окон |
| `f33fbc6` | §6: unknown доходит до видимого тумблера (SOURCE_SHA) |
| отчёт | этот файл + `WORKLOG.md` + `buildinfo.properties` |

## 14. CI

| run | HEAD | что | результат |
|---|---|---|---|
| `43604537501` | `9b98812` (этот отчёт + код `f33fbc6`) | `Build APK` | **success** |
| `43597905837` | `46c30cf` | `Build APK` | success |
| `43597186136` | `eda773a` | `Build APK` | success |

CI **не запускает тесты** (`bash runtests.sh` — локальный стенд, `OK (493 tests)`); успех сборки
не равен успеху тестов и тем более не равен поведению на ГУ.
