# iter33n — отчёт (версия 1.0.59)

**База:** `e70e615` (закрытие iter33m = 1.0.58), ветка `fix/v2`.
**Код итерации:** `056d14e0e2144dab57949de111970d1ec429f7e5`.
**Маркер:** `app/buildinfo.properties` → `SOURCE_SHA=056d14e` (пишет агент; `git rev-parse` в сборке запрещён).
**Версия:** `versionCode = 59`, `versionName = "1.0.59"` (`app/build.gradle.kts:78-79`).
**CI:** прогон `42499097146` (`056d14e`) — **success, 129 PASSED / 0 FAILED**, job `128787726449`,
артефакт `13624524776` (177 142 576 Б), `BUILD SUCCESSFUL in 3m 18s`.

Факты прогона 1.0.58 (`voiceapp3 (25).log`, `run=23126235` / `8ef25794`) взяты из лога владельца
и не перепроверялись: `TTL_SANITIZED removed=18`; `VERIFY TTL bootRestore … FAILED (… v6=null)`;
`EXT_PROBE_V2 step=1/3` и тишина; `JIT_VERIFY: skipped reason=zygote_restart_removed`;
`SESSION_START … ver=1.0.53`; `FLAG_ALIGN push rc=1 … ERR_MAIN_THREAD_FORBIDDEN`.

---

## §1 (P0) Слоты: разбор плагина по байткоду (доказательная база)

Источник разбора: `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk`, md5
`2fa3d11d3a5350cd4a427cc69617482b`, 18 280 162 Б — **это тот же файл, что у референса**
(`GeelyAutoSystemUIPlugin-digital.apk` из `swimapps/CentralEXAuto`). Разбор: androguard 4.1.4,
smali всех методов перечисленных классов (`/tmp/smali.txt`).

### 1.1(а) Условия приёма уведомления — `StatusIconManager.isStatusBarIconNotification`

Обязательны ВСЕ условия (порядок проверок в байткоде):

| # | Проверка | Что значит для нас |
|---|---|---|
| 1 | `entry != null`, `entry.getSbn() != null`, `sbn.getNotification() != null`, `notification.extras != null` | обычное уведомление |
| 2 | `extras.getBoolean("flag_status_icon_notification", false) == true` | ключ обязателен |
| 3 | `extras.getParcelable("flag_status_icon_remote_view") != null` **ИЛИ** `extras.getParcelable("flag_status_icon_icon") != null` | иконка (Icon) ИЛИ RemoteViews обязательна |
| — | фильтра по пакету отправителя и по каналу **НЕТ** | любой пакет может прислать иконку |
| — | иначе лог `"<app> send notification, is not a status notification"` + `"Notification entry has some null -> …"` | причина «не приняли» видна в logcat плагина |

Дополнительно `notification/entry/FlymeAutoNotificationEntry.checkStatusBarParameter()`:
`flag_status_icon_id` обязан быть **Integer**; иначе
`"checkStatusBarParameter: Exception! String cannot be cast to Integer,use default 0 :"` и id станет `0`.

### 1.1(б) `id`/`rank` → позиция: `StatusIconManager.crateAppStatusIconInfo` + `initialize()`

Маппинг полей `StatusIconInfo` (метод `crateAppStatusIconInfo`, читает extras в этом порядке):

| Поле | extra | Значение по умолчанию |
|---|---|---|
| `id` | `flag_status_icon_id` (`getInt`) | 0 |
| `createTime` | — | `System.currentTimeMillis()` |
| `describe` | `flag_status_icon_describe` (`getString`) | null |
| `spaceX` | `flag_status_icon_space_x` (`getInt`) | **1** |
| `rank` | `flag_status_icon_rank` (`getInt`) | **−1**, НО см. таблицу `mIdRankMap` ниже |
| `isPickOn` | `flag_status_icon_is_pick_on` (`getBoolean`) | false |
| `icon` / `pressIcon` | `flag_status_icon_icon` / `flag_status_icon_pressed_icon` | null |
| `remoteViews` | `flag_status_icon_remote_view` | null |
| `hide` | `flag_status_icon_hide` | false |
| `pendingIntent` | `notification.contentIntent` | — |
| `width` | `flag_status_icon_specific_width` (`getInt`) | 0 → далее `width = spaceX * mCellWidth` |
| `direction` | — | **жёстко `LEFT`, из extras НЕ читается** |

`initialize()` заполняет `mIdRankMap` (id → ПРИНУДИТЕЛЬНЫЙ rank; при попадании id в карту
присланный `flag_status_icon_rank` ИГНОРИРУЕТСЯ, в лог идёт `"<id> - <describe>use default rank -> N"`):

`{7:0, 18:1, 19:1, 2:24, 3:3, 4:26, 5:25, 6:4, 8:5, 9:11, 14:21, 16:7, 20:6, 21:19, 22:5, 23:20, 100:2, 101:3, 102:0, 103:1}`

Идём по таблице CEX: `15` (наружняя), `97` (батарея), `96`, `94`, `93` — **в карте НЕТ**, значит наши
`rank=22/32` доходят до плагина как есть. `updateAppStatusIconInfo` (обновление существующей иконки)
переписывает все поля extras и меняет rank только если `StatusIconManagerUpdateRankCallBack.canUpdateRank()`
вернул `true` и id нет в карте (иначе лог `"Rank can not update"`).

### 1.1(в) Почему «стек» не получается и почему всё оказывается СЛЕВА — `StatusContentLayout.addIcon`

```
info.getDirection() == this.mDirection   // иначе return, иконка не добавляется
… mPositionManager.insertAndArrangePosition(info) → иначе "Add status icon fail !!!"
```
Контейнеров два (`StatusContentLayout` с `mDirection = LEFT` и `= RIGHT`, задаётся `setContentDirection`).
А `crateAppStatusIconInfo` **всегда** ставит `LEFT` для уведомлений от приложений. «Родные» иконки плагина
(`addDefaultStatusIcon`) распределены явно:

* **LEFT**: `101 StatusIcon_TrailerMode`, `9 StatusIcon_Notification`, `23 StatusIcon_HostPot`,
  `2 StatusIcon_Charge`, `5 StatusIcon_Authority`, `4 StatusIcon_Usb`;
* **RIGHT**: `100 StatusIcon_WlanWorkshopMode`, `102 StatusIcon_car_play`, `103 StatusIcon_android_auto`,
  `3 StatusIcon_FlymeLink`, `24 StatusIcon_DMS_OMS`, `22 StatusIcon_Bluetooth`, `16 StatusIcon_VOLUME`,
  `21 StatusIcon_Time`.

**Вывод (❌ для «справа»):** уведомлениями физически нельзя попасть в правый кластер — направление
не читается из extras. Именно поэтому в 1.0.58 `51.9%`, `5°C`, Wi-Fi встали слева сверху
(скрин владельца IMG_1455), хотя extras 1:1 как у CEX. Порядок ВНУТРИ левого кластера задаётся
`rank` (меньше — левее/приоритетнее) и `spaceX`; стек «одно над другим» (`IsRemoteViews`/общая Position)
этим API не предусмотрен — у плагина одна строка на кластер.
Своих обходов не делаю: правка плагина (smali) запрещена, оверлеи запрещены.

### 1.1(г) Роль `SmartBarController` (`systembar/smartbar/SmartBarController`)

Это контроллер **дока** (SmartBar), не строки состояния: `barId`, `requestPluginID`, `requestSpaceID`,
`switchTipId`, `charingVaule`, `onPluginDisconnected smartBarID=`. Плитки дока приходят от внешних
`com.flyme.auto.plugin.systemui.SmartBarPlugin` (`ACTION = com.flyme.auto.plugin.action.PLUGIN_SMART_BAR`,
`getSmartBarView()`, `updateStatus(int, Bundle)`, `setMiniMode`). Встроенные id дока:
`SMART_BAR_CHARGE_ID`, `_HOME_ID`, `_MEDIA_ID`, `_NAVIGATION_ID`, `_PARKING_ID`, `_SCENE_*`.

**Следствие для «правой ячейки дока со стеком `°C` над `%`»:** это либо вид плагина, либо SmartBar-плитка
(~ `360` в доке). В статус-иконках такого механизма нет.

### 1.2 Сверка нашего уведомления с `kg.j`

| Требование `kg.j` | У нас (`tools/StatusIcons.kt:619-640` `postNotif`, канал `:71`, таблица `:566/606`) | Итог |
|---|---|---|
| канал `central_ex_status_temp`, IMPORTANCE_LOW(2), `setShowBadge(false)` | `CHANNEL_STATUS`, `IMPORTANCE_LOW`, `setShowBadge(false)` | ✅ |
| `setSmallIcon(<Icon>)`, `setOngoing(true)`, `setShowWhen(false)`, title/text/intent | есть | ✅ |
| 10 ключей extras в порядке `notification → id → describe → icon → pressed_icon → hide → rank → space_x → is_pick_on → specific_width` | теперь через единую таблицу `StatusIconContract`/`contractExtras` | ✅ |
| `flag_status_icon_id` — Integer | `putInt` | ✅ |
| иконка обязательна (`remote_view` ИЛИ `icon`) | `flag_status_icon_icon` кладётся всегда | ✅ |
| таблица слотов CEX `15020/97/32` и `15021/15/22` | `cexTable()` (`:600-603`), константы `:73-80`; Wi-Fi `15024/15024/42` (`:966`) | ✅ (`StatusIconContractTest > cexSlotTable PASSED`) |

Чего у нас НЕТ и не должно быть: `flag_status_icon_remote_view` (NULL — валидно), поля направления
(его в протоколе нет). Итог §1.2: **расхождений по extras нет**; причина «слева» — §1.1(в), не контракт.

### 1.3 Эксперимент с позицией

`rank`/`id`/`space_x` уже 1:1 с таблицей CEX (проверено тестом). Менять нечего: в байткоде позиция
внутри кластера считается `StatusIconPositionManager` по `rank` (`RankComparator`) и `createTime`
(`CreateTimeComparator`), а кластер задаётся направлением, которое для уведомлений жёстко `LEFT`.
Пробовать «подкрутить rank, чтобы уехало вправо» бессмысленно — направление другое поле.

### 1.4 Пропы

`x7.i` / `x7.b` / recarga / mount не тронуты (мандат §1.4). Дополнительно найдено:
`hvacapi/widget/HvacVehicleTempWidget.updateDigitalTemp()` → `HvacDigitalHelper.update(View, String)`
ищет в разметке `tv_temp_digital` и ставит туда строку уставки; сам режим «цифровой климат» читается
**из VHAL** (`p4/f.n()` → `Lp4/a.a(354419984, 5)`), а не из `persist.sys.hvacdigital.*`.
Строк `persist.sys.hvacdigital` в APK нет вообще (проверено по всем 60 029 строкам dex).
**Загадка `persist.sys.hvacdigital.*` закрыта наполовину:** плагин их не читает; они остаются
контрактом приложения-референса (и нашим), на отрисовку панели не влияют.

## §2 (P0) `EXT_PROBE_V2`: тихая смерть после шага 1 — закрыта

| Требование | Где сделано | Доказательство |
|---|---|---|
| каждый шаг в try/catch с обязательной строкой | `tools/HvacFlagsApply.kt:326-388` — `EXT_PROBE_V2_ERROR step=i/3 err=<класс>:<msg> thr=<поток>` на любой ветке (и на `rc!=0`, и на броске), плюс общий `step=loop` | `ExtProbeV2Test > pauseIsFifteenSecondsAndNothingIsDeleted PASSED` (в тесте пинятся строки `EXT_PROBE_V2_ERROR step=`, `props_follow_prefs=unknown error=`) |
| пауза 15 с видна и не «висит» | `tools/HvacFlagsApply.kt:417-436` (`pauseLogged`: лог `EXT_PROBE_V2 wait …` до сна, сон отрезками по 2.5 с, лог `wait_done … interrupted=…` после) | `ExtProbeV2Test … PASSED` (`EXT_PROBE_V2 wait step=`, `wait_done step=`, `Thread.sleep(minOf(slice, left))`) |
| проба ВСЕГДА доходит до `props_follow_prefs` | восстановление и финальная строка вынесены в `finally` (`:390-406`); при падении восстановления — `props_follow_prefs=unknown error=…` | тот же тест |
| строки действий не режутся лог-бюджетом | `tools/AppLog.kt:283-291` — `isActionLine()` + `ACTION_PREFIXES` (`OP_`, `TOGGLE`, `TTL_`, `EXT_PROBE`, `FLAG_`, `JIT_`, `ADB_`, `BUILD:`, `SESSION_START`, `LOG_BUDGET`, `CHANNEL_LOST`, `ADB_RECOVERY`, `SCRIPT_UPLOAD`); такие строки **не** проходят через `budgetAllow` | статически: `tools/AppLog.kt:338` → `val exempt = isActionLine(msg)`; строка действия не уходит в `budgetAllow` |

Почему умерло в 1.0.58 — по логу: причина не в 15-секундном сне, а в том, что **не было ни одной строки
после шага**, т.е. исключение в потоке `CENTRAL_Bg-3` (recarga/read) не логировалось ничем, а `finally`
с восстановлением стоял вне цикла. Обе дырки закрыты: теперь лог пишется и на `Throwable` (включая
`InterruptedException` от остановки пула), а финальная строка гарантирована.

## §3 (P0) JIT: проба обязательна на каждом старте

| Требование | Где | Доказательство |
|---|---|---|
| `JIT_BOOT` печатается ВСЕГДА | `tools/AppPerformance.kt:870-897` — `jitBootProbe()`, один раз за процесс, без условий по префу/каналу; вызывается из `App.kt:51-53` (`BgWorker.post`) | `JitBootTest > jitBootLineIsUnconditional PASSED`, `calledOnEveryStart PASSED` |
| состав строки | `runtime=` (`dalvik.vm.usejit`), `persist=` (`persist.dalvik.vm.usejit`), `profiles=` (`dalvik.vm.usejitprofiles`), `init=`, `expect_runtime_true_after_reboot=`, `marker=` (`persist.sys.dalvik.vm.usejit`), `key=`, `pref=on|off` | `JitBootTest > keyIsPersistDalvikVmUsejit PASSED` |
| §3.2 диагностика | при `runtime != true` и `persist|marker == true` — вторая строка `JIT_BOOT runtime=… persist=… reason=init_did_not_carry marker=…` (`AppPerformance.kt:889-891`) | тот же тест |
| JIT-запись не тронута | `persist.dalvik.vm.usejit` пишется как и было (`AppPerformance.kt:730` — ключ `persist.dalvik.vm.usejit`, запись не менялась) | §5 мандата |

## §4 Мелочи (P1)

| # | Требование | Где | Доказательство |
|---|---|---|---|
| 4.1 | `SESSION_START ver=` — реальная версия | `tools/LogKit.kt:34` — `val VER: String = com.example.voiceapp3.BuildConfig.VERSION_NAME` (используется в `:238`) (было `const "1.0.53"`) | статически; в следующем логе ждём `ver=1.0.59` |
| 4.2 | при `v4=0&&v6=0` печатать `result=OK`/`OFF_OK`, без «не выполнился» и без `v6=null` | `tools/NetworkTtl.kt:88-104` — строгий разбор `TTL_V4=(\d+)` / `TTL_V6=(\d+)` / `VERIFY_V4=([A-Za-z0-9_-]+)` вместо `parseKv` (он и давал `v4="0 TTL_V6=0 VERIFY_V4=OFF_OK …"`, `v6="null"`); условие OFF-провала срабатывает только при реальном остатке правил (`:124-127`) | `TtlScriptTest > offMessageIsOkWhenNoRules PASSED` + прогон скрипта на заглушках: OFF → `TTL_OFF v4=0 v6=0 result=OK`, `VERIFY_V4=OFF_OK VERIFY_V6=OFF_OK` |
| 4.4 | ON не плодит дубликаты | `tools/TtlScript.kt:125-139` — после двух `-A` идёт `dedupe()`: пока копий `>1`, снимается `-D POSTROUTING $SCOPE …`; печатается `TTL_ADD_COUNT n=<n> v6=<m> scope="…"` (`:138`). Boot-restore уже = `sanitize()` + один `add` | `TtlScriptTest > onKeepsSingleRule PASSED`; прогон на заглушках из состояния «6 копий» (как в логе 08.11): `TTL_SANITIZED removed=12 v4_removed=6 v6_removed=6` → `TTL_ADD_COUNT n=1 v6=1` → в mangle ровно одно правило |
| 4.3 | `FLAG_ALIGN` не с main thread | `tools/StatusIcons.kt:1005-1031` — если вызвано с main, попытка не «сгорает» (`alignDone=false`) и работа уходит в `BgWorker`, строка `FLAG_ALIGN result=… thr=bg` | статически + `ToggleNoLieTest > syncDoesNotPushPeriodically PASSED` (выравнивание осталось одноразовым) |
| 4.5 | exempt лог-бюджета | `tools/AppLog.kt:283-291` + `:338` | см. §2 |

## §5 Не тронуто (диффом `e70e615..056d14e`, 11 файлов)

`recargaScript()` (`x7.g`), `flagsCommand()` (`x7.i`), `via`, mount-мониторинг плагина,
`reconnectRoot`/`CHANNEL_LOST`/сериализация заливок, TTL-логика iter33m (uplink-only, санация),
JIT-запись `persist.dalvik.vm.usejit`, `StatusIcon_*`/ресурсы/раскладка — без изменений.

## §6 Тесты — 119 → **129 PASSED / 0 FAILED**

| Файл | Тестов | Что держит |
|---|---|---|
| `StatusIconContractTest` (новый) | 5 | ключи extras 1:1 `kg.j`, значения (`id` Int, `space_x=1`, `hide=false`, `is_pick_on=false`, `specific_width=0`), таблица `15020/97/32`+`15021/15/22`, канал `central_ex_status_temp` + `IMPORTANCE_LOW`, отсутствие «своего» направления |
| `JitBootTest` (новый) | 3 | `JIT_BOOT` без ранних выходов, `runtime/persist/profiles/init/expect_*`, ключи `dalvik.vm.usejit`/`persist.dalvik.vm.usejit`, `reason=init_did_not_carry`, вызов из `App.onCreate` |
| `ExtProbeV2Test` | 5 | три имени и порядок, шаг одной командой, порядок чтения, формат строки, пауза с логом + `EXT_PROBE_V2_ERROR` + финальная строка |
| `TtlScriptTest` | 10 (было 8) | +`onKeepsSingleRule` (`TTL_ADD_COUNT`, `dedupe()`), +`offMessageIsOkWhenNoRules` |
| `BuildMarkerTest` | 3 | `versionCode=59`, `versionName=1.0.59`, честный `SOURCE_SHA` |
| остальные (11 файлов) | 103 | без изменений |

Провальные прогоны этой итерации (показаны честно):

| Прогон | SHA | Итог | Причина |
|---|---|---|---|
| `42497296670` | `4a64bc7` | ❌ failure | компиляция: я продублировал `PROP_USEJIT_PROFILES` (уже был в файле) → `Conflicting declarations` |
| `42498093676` | `60689c9` | ❌ failure | компиляция теста: в сообщении ассерта стоял неэкранированный `$SCOPE` → `Unresolved reference 'SCOPE'` |
| **`42499097146`** | **`056d14e`** | ✅ **success** | **129 PASSED / 0 FAILED**, job `128787726449`, артефакт `13624524776` (177 142 576 Б), `BUILD SUCCESSFUL in 3m 18s` |
| **`42500110715`** | **`28fdac1`** (handoff: отчёт + WORKLOG + `SOURCE_SHA=056d14e`) | ✅ **success** | **129 PASSED / 0 FAILED**, job `128790823798`, артефакт `13625751211` (177 142 520 Б), `BUILD SUCCESSFUL in 2m 53s`, `BuildMarkerTest > commitSourceIsHonest PASSED` |

## §7 Что проверить владельцу

1. **Тумблеры слотов** — скрин ДО/ПОСЛЕ каждого: «Процент заряда», «Цифровая наружная», «Цифровой климат».
   Ожидаемо по байткоду: иконки появляются в **левом** кластере строки состояния (правый кластер занят
   родными иконками плагина: CarPlay/BT/громкость/часы). Если «справа» обязательно — нужно решение
   владельца: это возможно только через `SmartBarPlugin` (плитка дока, API плагина) или правку APK
   плагина, что запрещено.
2. **«Проба слотов (наружняя)»** — теперь в логе обязаны быть ВСЕ шаги:
   `EXT_PROBE_V2 write step=1/3`, `recarga`, `step=1/3 …`, `wait …`/`wait_done`, затем 2/3 и 3/3,
   и финальная `EXT_PROBE_V2 props_follow_prefs=…`. На каком шаге экран меняется — сказать по скрину.
3. **JIT** — после ребута в логе первой же строкой `JIT_BOOT runtime=… persist=… profiles=… init=…`;
   если `runtime=false` при `persist=true` будет `reason=init_did_not_carry`.
4. **TTL** — при живом интернете ON → `TTL_ADD_COUNT n=1` + `TTL_CHECK … → SCOPED_OK`, CarPlay жив;
   OFF → `TTL_OFF v4=0 v6=0 result=OK` (без «не выполнился»).
5. Прислать `voiceapp3.log` + скрины (до/после каждого тумблера).

## Границы (не делалось)

Правок системы/smali/`setenforce 0`/`pm install`/`ctl.restart zygote`/перезагрузок ГУ — нет; глобального
TTL-правила нет; оверлеев и своей раскладки строки состояния нет; APK собирает и подписывает владелец.
