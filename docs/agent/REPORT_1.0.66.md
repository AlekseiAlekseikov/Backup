# Отчёт 1.0.66 (versionCode 66) — доставка цепочки JIT: диагноз по логам ГУ и правки

Ветка `fix/v2`, база `cfdef06` (1.0.65). Задание — `PROMPT_JIT_FIX_1.0.66.md`.
Данные: логи `voiceapp3-22.11.2026*.log` (архив по ссылке промпта), снимки `sh6_pre.txt` / `sh6_post-2.txt`,
фото панели `IMG_1475.jpg` (16:49). Референс по ссылке — тот же APK 3.3.9 (MD5 архива `8a65c6c2…`), что
уже декомпилирован в 1.0.65.

## 1. Сборка

| Что | Значение |
|---|---|
| Версия | `versionCode = 66`, `versionName = "1.0.66"` |
| SOURCE_SHA | `dbe6c4772ca945390dc3da33efd51730c60d5896` |
| CI | run **344** (`7125186`) — success (`./gradlew assembleDebug`: `AdbClient`, `AppPerformance`, `JitMechAndroid` компилируются в настоящей сборке) |
| Стенд | `OK (884)` (локальная JVM, kotlinc 1.9.24) |
| Установка | только `adb install -r`, подпись владельца, без «Очистить данные» |

| Коммит | Что |
|---|---|
| `dab244a` | `JitMech`: строка `JIT_CHAIN_SENT want=X` по подтверждению доставки; `delivered=` в `JIT_CHAIN_RETURNED` (+2 теста) |
| `af365d6` | транспорт: `OKAY` adbd на `OPEN shell:<цепочка>` = доставка (`AdbOpenHook`, `AdbClient.executeNotifyOpened`) (+5) |
| `d6c81ce` | доказательство рестарта: `JIT_ZYGOTE before/after … restarted=`; `JIT_WIFI_CONFIRM` (+3) |
| `477a576` | неизвестный факт `getprop` после старта перечитывается 5/15/30/60 с (+4) |
| `dbe6c47` | версия 66 / 1.0.66 |
| `7125186` | SOURCE_SHA |

## 2. Диагноз: цепочка 1.0.65 доставлялась и выполнялась полностью

Промпт исходил из `JIT_CHAIN_SENT=0`, `JIT_OP_PHASE=0` и `zygote_pid=457` без изменений. Логи ГУ показывают другое.

### 2.1. Клик 22.11 14:15:52 — полный цикл

```
14:15:52.744 JIT_CLICK want=true jit_pedido=true perf_auto_otimizar=true jit_restaurar=wifi=1 prefs=ok
14:15:52.782 dalvik.vm.usejit -> true; reiniciando o framework
14:15:53.863 TTL_RECEIVER … wifi=false                      ← svc wifi disable выполнен
             (строк больше нет: процесс приложения погиб)
14:16:00.765 AppLog.init pid=25418                           ← новый процесс (был 17877), uptime ГУ не сброшен
14:16:02.866 JIT_WATCHDOG jit_restaurar=wifi=1 removed=true restore_in_ms=15000
14:16:15.299 JIT_BOOT runtime=ERR_ADB_TIMEOUT after 8000ms   ← канал ADB поднимается после рестарта
14:16:16.575 JIT_VERIFY skipped reason=getprop_unread (jit_pedido остаётся)
14:16:19.070 JIT_VERIFY ok=true not_applied=false pedido_removed=true
14:16:19.106 JIT_PROBE ok=true pedido=true getprop=true maps=true
14:16:19.316 JIT: restaurado após o restart (wifi=true); wifi agora=false
```
`sh6_post-2` (14:20): `dalvik.vm.usejit=true`, Wi-Fi включён, ping есть, `jit_restaurar` и `jit_pedido` удалены.

`maps=true` — это решающий факт. Исполняемый JIT-кэш есть только у процесса, который форкнулся от zygote,
запущенного уже с `dalvik.vm.usejit=true`. Ни новый процесс, ни `maps=true` невозможны без рестарта
zygote, из которого запускается приложение. Приложение — системное (uid 1000), и просто так оно не умирает.

### 2.2. Почему `zygote_pid=457` не изменился

На ГУ два zygote: строка `pidof zygote; pidof zygote64` из `voiceapp3 4.log` вернула `len=7`, т.е. два pid.
В конфигурации `zygote64_32` init-сервис **`zygote`** — это 64-битный процесс с именем **`zygote64`**,
а 32-битный `zygote_secondary` называется **`zygote`**. `ctl.restart zygote` перезапускает `zygote64`
(вместе с system_server и всеми 64-битными приложениями), а `zygote_secondary` остаётся жить со
своим pid. Скрипт sh6 считает `zygote_pid` по процессу `zygote`, т.е. по 32-битному, — отсюда
неизменные 457. `ro.boottime.zygote` — read-only свойство времени загрузки, при рестарте не меняется.

### 2.3. Почему `JIT_CHAIN_SENT=0`, `JIT_OP_PHASE=0`, `JIT_OP_PROBE=0`

Это счётчики маркеров 1.0.64 (claim/lock/фазы). В 1.0.65 этот код удалён вместе с маркерами, поэтому
нули ожидаемы. Строка `JIT_CHAIN_SENT` возвращена в 1.0.66 с новым смыслом (см. §3.1).

### 2.4. Настоящий дефект: тумблер «состояние неизвестно» после загрузки (фото 16:49)

```
15:48:43 SESSION_START … uptime=14015                        ← перезагрузка ГУ
15:49:01.902 CHANNEL_LOST reason=timeout:8000ms; ADB_RECOVERY action=reconnect
15:49:01.963 JIT_BOOT runtime=ERR_ADB_TIMEOUT after 8000ms rc=137
15:49:05.917 VERIFY EXEC … getprop dalvik.vm.usejit → SUCCESS ← ответ пришёл через 4 с и выброшен
```
Факт для тумблера читался на старте один раз. После одного таймаута он оставался `UNKNOWN (rc_nonzero)`
до следующего успешного чтения из панели. Отсюда на фото 16:49: «состояние неизвестно · getprop не прочитан:
rc_nonzero», а положение тумблера — последнее известное (вкл).

### 2.5. Блокирующее ожидание после отправки — не причина

Референс делает то же самое: `la.run` → `H1(40000, …)` ждёт ответа в пуле потоков; процесс погибает внутри
ожидания. Ожидание оставлено: если zygote НЕ перезапустится, вернувшийся ответ — единственный способ это
увидеть (`JIT_CHAIN_RETURNED … delivered=…`). Флаг busy снимается в `finally`.

## 3. Правки

### 3.1. `JIT_CHAIN_SENT` — доказательство доставки (критерий 2)
`OKAY` от adbd на `OPEN shell:<цепочка>` означает: adbd запустил shell с цепочкой на устройстве. Это
единственное подтверждение, которое успевает прийти до гибели процесса.
- `AdbOpenHook` — одноразовый обработчик, только для своей команды (служебные пробы `id` его не тратят).
- `AdbClient`: у `Job` появилось поле `onOpened`. Рабочий поток взводит обработчик перед `runCommand`
  и снимает в `finally`. Вызов стоит в ветке `OKAY` и TCP 5555, и unix-канала. Новый метод
  `executeNotifyOpened` (ожидание и таймаут — как у `execute`). Остальные вызовы транспорта не меняются.
- `JitMech.Shell.execNotify` (по умолчанию — обычный `exec`). `click` пишет ровно одну строку:
  `JIT_CHAIN_SENT want=X transport=open_okay (…)`, после `reiniciando o framework`.
- Цепочка не изменилась: тот же литерал из DEX, одна команда, `ctl.restart zygote` — последней.

### 3.2. `JIT_ZYGOTE` — доказательство рестарта (критерий 3)
Родитель процесса приложения (`android.system.Os.getppid()`) — zygote, из которого его запустили. Это
именно тот zygote64, который перезапускает `ctl.restart zygote`. Shell не нужен.
- Клик: `JIT_ZYGOTE before pid=N`; pid сохраняется в one-shot префе `jit_zygote_antes` (ключ приложения,
  не системное свойство). Если клик отклонён `busy`, pid не пишется.
- Watchdog после рестарта: `JIT_ZYGOTE after pid=M before=N restarted=true|false|unknown`, ключ удаляется.
- `JIT_WIFI_CONFIRM wifi_now=…` через 10 с после возврата Wi-Fi — только чтение. Строка
  `wifi agora=` у референса пишется сразу после `setWifiEnabled(true)`, когда Wi-Fi ещё включается;
  отсюда `wifi agora=false` в логе 14:16:19.

### 3.3. Факт после загрузки (критерий 10, фото 16:49)
Если первое чтение на старте не удалось, `JitMech.retryFact` перечитывает `getprop dalvik.vm.usejit`
через 5/15/30/60 с и останавливается на первом известном значении (`JIT_FACT_RETRY attempt=…`, затем
`gave_up`). Если факт уже прочитала панель, shell не вызывается. После прочтения — обычная сверка.
Только чтение, без циклов. После перезагрузки тумблер покажет «выкл» по факту `getprop=false`.

## 4. Критерии приёмки §8

| № | Критерий | Статус | В коде / тестах | Что смотреть в логе ГУ |
|---|---|---|---|---|
| 1 | 66 / 1.0.66 | ✅ | `app/build.gradle.kts`, `BuildMarkerTest` | `BUILD: versionCode=66` |
| 2 | `JIT_CHAIN_SENT want=true` | ✅ | `Iter65JitMechTest.chainSentIsLogged…`, `Iter66ChainSentTest` | строка сразу после `reiniciando o framework` |
| 3 | zygote_pid сменился | ✅ (было и в 1.0.65; теперь видно в логе) | `Iter66ZygoteEvidenceTest` | `JIT_ZYGOTE after … restarted=true`; в shell — `pidof zygote64` |
| 4 | `dalvik.vm.usejit=true` | ✅ | литерал DEX без изменений | `JIT_PROBE … getprop=true` |
| 5 | `JIT_PROBE ok=true getprop=true maps=true` | ✅ (уже 22.11 14:16:19) | `Iter65JitMechTest.verify*` | строка `JIT_PROBE` |
| 6 | Wi-Fi вернулся через ~15 с | ✅ | `watchdog*`, `JIT_WIFI_CONFIRM` | `JIT_WIFI_CONFIRM wifi_now=true` |
| 7 | `jit_restaurar` one-shot | ✅ | `watchdogRestoresWifiAfter15sAndRemovesKey` | префы после клика |
| 8 | Нет `persist.dalvik.vm.usejit` | ✅ | `chainHasNoForbiddenCommands`, `noAutoApplyNoPersistNoClaimAnywhere` | — |
| 9 | Нет `JIT_CLAIM_LIB` | ✅ | `noAutoApplyNoPersistNoClaimAnywhere` | — |
| 10 | Нет авто-JIT при загрузке | ✅ | `JitBootBoundaryTest`; `retryFact` — только чтение | после перезагрузки `JIT_BOOT`/`JIT_FACT_RETRY … getprop=false`, тумблер «выкл» |

Тесты этапа 3 промпта (литерал, порядок, первая/последняя команда, запреты, `jit_restaurar`, формула, maps)
есть с 1.0.65 (`Iter65JitMechTest`, 20 тестов). Новые: `Iter66ChainSentTest` (5), `Iter66ZygoteEvidenceTest` (3),
`Iter66FactRetryTest` (4) и 2 теста в `Iter65JitMechTest`. Существующие тесты не изменялись, кроме ожидаемой версии
в `BuildMarkerTest`.

## 5. Для владельца: проверка на ГУ

1. Собрать из `fix/v2`, подписать, `adb install -r`.
2. В снимке sh6 заменить `pidof zygote` на **`pidof zygote64`** (или смотреть оба). Счётчики `JIT_OP_*`,
   `JIT_CLAIM`, `JIT_LOCK`, `JIT_RESTORE_*` относятся к удалённому коду 1.0.64 и всегда будут 0. Считать
   нужно `JIT_CLICK`, `JIT_CHAIN_SENT`, `JIT_ZYGOTE`, `JIT_WATCHDOG`, `JIT_WIFI_CONFIRM`, `JIT_VERIFY`,
   `JIT_PROBE`, `JIT_FACT_RETRY`.
3. Ожидаемый лог клика:
   `JIT_ZYGOTE before pid=N` → `JIT_CLICK …` → `dalvik.vm.usejit -> true; reiniciando o framework` →
   `JIT_CHAIN_SENT want=true transport=open_okay` → (рестарт фреймворка, экран гаснет) →
   `JIT_WATCHDOG jit_restaurar=wifi=1 removed=true` → `JIT_ZYGOTE after pid=M before=N restarted=true` →
   `JIT_PROBE ok=true … getprop=true maps=true` → `JIT: restaurado …` → `JIT_WIFI_CONFIRM wifi_now=true`.
4. Остатки 1.0.64 в `/data/local/tmp/voiceapp3_jit*` и ключи `jit_restore_marker` / `jit_we_disarmed_wifi`
   в префах (видны в sh6) ни на что не влияют: 1.0.66 их не читает. Удалять их приложение не стало, чтобы
   не добавлять команд записи; при желании владелец удалит их вручную.
