# JIT: сравнение 1.0.64 с референсом CentralEXAuto 3.3.9 (решения для 1.0.65)

## 1. Источник и как получен

- Внешний APK: `/home/user/decompiled_central/resources/CentralEXAuto_v3.3.9.apk`, MD5
  `c3f1664125953b9851bf4483b265135f` (совпадает с промптом). ZIP `apks/CentralEXAuto.apk` —
  `8a65c6c29749c64d4e335c57490a347a` (совпадает).
- `classes.dex` из внешнего APK, JADX 1.4.7 (`--show-bad-code`; для `w0.z1`, `w0.f2` — `-m fallback`,
  обычный режим их не разбирает: «Method not decompiled»).
- В DEX строка `setprop dalvik.vm.usejit ` встречается **один раз** (в `w0/la`), `getprop dalvik.vm.usejit` —
  один раз (в `w0/bb`). Строк `persist.dalvik.vm.usejit` / `usejitprofiles` в DEX нет.

## 2. Что показала декомпиляция (точные цитаты)

### 2.1 Клик — `w0/b9` → анонимный `w0.j9.onClick` (кнопка OK диалога подтверждения), case 0
```java
i24 = ((WifiManager) systemService).isWifiEnabled();   // catch Throwable → i24 = 0
NetworkInterface byName = NetworkInterface.getByName("ap0"); ... // ap
mainActivity24.o0().edit().putBoolean("jit_pedido", z4).putBoolean("perf_auto_otimizar", z4)
    .putString("jit_restaurar", b.f(i24, i27, "wifi=", ",ap=")).commit();
mainActivity24.w("Desempenho", "dalvik.vm.usejit -> " + z4 + "; reiniciando o framework");
mainActivity24.C.execute(new la(mainActivity24, z4, mainActivity24.J0(string7, "")));  // J0 = прогресс-диалог
```
Уточнения к промпту: (а) клику предшествует диалог подтверждения; (б) busy-флаг `f1030n1 = true`
стоит в ДРУГОЙ ветке того же класса (case `default`, не JIT); (в) ошибка `isWifiEnabled` ⇒ `wifi=0`.

### 2.2 Цепочка — `w0/la.run` case 0
```java
StringBuilder sb = new StringBuilder("\n        svc wifi disable\n        _n=0\n        while [ $_n -lt 20 ]; do\n ...
    setprop dalvik.vm.usejit ");
sb.append(z4 ? "true" : "false");
sb.append("\n        setprop ctl.restart zygote\n    ");
mainActivity.H1(40000, o2.m.V(sb.toString()));          // o2.m.V = trimIndent; таймаут 40 000 мс
mainActivity.f1045v.postDelayed(new ua(iVar, 0, mainActivity), 45000L);
```
После `trimIndent` — ровно литерал из промпта §3.2, 392 символа для `true`, 393 для `false`.
Пример кода в промпте §5 шаг 4 содержит опечатки (`\$_n=\$((…))`, `for \$_m`) — они сломали бы скрипт;
реализован литерал из DEX, не пример.

### 2.3 Восстановление — `CentralWatchdogService.onCreate` (строки ~4215–4227 jadx)
```java
String string3 = W().getString("jit_restaurar", null);
Pattern compile = Pattern.compile("^wifi=([01]),ap=([01])$");
... совпало → W().edit().remove("jit_restaurar").apply();  postDelayed(new e2(oeVar, this, 0), 15000L);
```
`e2` case 0: `if (CentralWatchdogService.T2) new Thread(new e2(..., 1)).start();` (`T2` — сервис жив;
ставится в `onCreate`, снимается в `onDestroy`). case 1: AP (`U("pós-JIT")`), затем
`if (z3) { if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true); }` и лог
`JIT: restaurado após o restart (wifi=… ap=…); wifi agora=… ap agora=…`.
Не совпало — ключ НЕ удаляется и ничего не делается.
`perf_auto_otimizar` в `onCreate` запускает через 300 с `z1` case 16 → `f2` case 26 =
`getInstalledApplications` (раунд оптимизации приложений), **не JIT**.

### 2.4 Сверка — `w0/bb.run` (default) → `w0/r7.run` (default)
```java
String H1 = mainActivity2.H1(8000, "getprop dalvik.vm.usejit");
boolean a3 = trim(H1).equals("true");
bool = j5.d(строки /proc/self/maps, где contains("jit-cache") || contains("dalvik-jit-code-cache"));  // ошибка → null
// r7:
masterToggleView.setActive(z4);                       // z4 = getprop
if (prefs.contains("jit_pedido")) {
    boolean z5 = prefs.getBoolean("jit_pedido", false);
    ok = z5 == z4 && (!z5 || !Boolean.FALSE.equals(bool));
    w("Desempenho", "JIT pedido=" + z5 + " getprop=" + z4 + " code-cache-executavel=" + bool);
    prefs.edit().remove("jit_pedido").apply();
    if (ok || !z5) return;  toast(master_perf_jit_not_applied);
}
```
`j5.d`: непустые строки; пусто ⇒ `null`; есть совпадение `\br[-w]x[ps]\b` ⇒ `true`, иначе `false`.
Уточнения: тумблер рисуется по `getprop` (maps влияет только на вердикт); тост — только если
`pedido == true` и не ok.

## 3. Что было в 1.0.64 (по коду и по логу 21.11)

Лог клика 19:13:00–19:13:17 и снимок `sh6_post.txt`:
- claim, установка библиотеки (`JIT_CLAIM_LIB`), цепочка 15 339 Б файлом (`JIT_CHAIN_DELIVERY path=script`);
- процесс приложения умер в 19:13:07–19:13:17 (новая сессия `run=7bf095a2`);
- **zygote НЕ перезапущен**: `zygote_pid=453`, `zygote_start_ms=1795124022120` — через 5 с после загрузки ГУ
  (`uptime_s=153852` на `time_s=1795277870`);
- `dalvik.vm.usejit=true` выставлен; в `.term` только `START` и `TRAP rc=0` — ни `UMOUNT_*`, ни `END`;
- после этого `JIT_UNDEFINED … clicks=blocked`, `JIT_PEDIDO kept reason=chain_undefined`.

## 4. Таблица решений

| Компонент | Наше состояние (1.0.64) | Референс | Решение | Обоснование |
|---|---|---|---|---|
| Цепочка команд | свой скрипт ~15 КБ: guard claim/lock, trap EXIT, `.term`, исключение своего каталога из `umount`, файловая доставка | литерал 392 симв., `H1(40000, …)` | **изменить** | Литерал из DEX байт в байт. 392 Б влезают в один ADB-пакет — файловая доставка не нужна. На машине наш вариант не перезапустил zygote. |
| Порядок шагов | тот же порядок, но внутри guard и доп. шагов | `svc wifi disable` → drain ≤20 → `umount -l` `/data/app/` → `setprop usejit` → `ctl.restart zygote` | **изменить** (литерал) | Ни одной команды сверх литерала. |
| Таймаут цепочки | 40 000 | 40 000 | оставить | совпадает |
| Владение claim/lock, библиотека `jitlib`, `JitOwn.kt` | есть (iter52–67c) | нет | **удалить** | Промпт §3.1 и критерий 13; решение владельца «заменить и удалить». |
| Отметка операции `jit_restore_marker`, `JitUnblock`, `JitPair`, блокировка кликов `clicks=blocked` | есть | нет | **удалить** | Именно они держали клики заблокированными после 21.11 (`JIT_UNDEFINED`). |
| `jit_we_disarmed_wifi`, `restoreDecision`, `JitNetDecision` | есть | нет | **удалить** | Восстановление — только по `jit_restaurar`, как в `e2`. |
| Префы клика | `jit_pedido`, `perf_auto_otimizar`, `jit_restaurar=wifi=X` + marker + seq | 3 ключа, один `commit` | **изменить** | Только 3 ключа. Пишутся в оба хранилища проекта (`Prefs2` cps/dps) — у нас два хранилища, читается cps→dps. |
| Формат `jit_restaurar` | `wifi=X` | `wifi=X,ap=Y` | оставить `wifi=X` | Решение владельца: AP в JIT не переносится. Разбор принимает и `wifi=X,ap=Y` (поле `ap` игнорируется). |
| Ошибка `isWifiEnabled` | — | `wifi=0` | **как в референсе** | `catch Throwable → 0`. |
| Восстановление Wi-Fi | в `VoiceAssistantService` через `JitOp.onProcessStart` (+claim с устройства, restoreDecision) | `CentralWatchdogService.onCreate` → 15 с → `e2` | **добавить** `JitWatchdogService` | Решение владельца: отдельный сервис. Удалить ключ → через 15 с, если сервис жив, в фоне: `wifi=1` и выключен → `setWifiEnabled(true)`; лог `JIT: restaurado após o restart (wifi=X); wifi agora=Y`. |
| Запуск сервиса | — | системный старт watchdog | **добавить** | `VoiceAssistantService.onCreate` → `startService(JitWatchdogService)`; если система запретит старт — та же логика выполняется прямо в `onCreate` (строка `JIT_WATCHDOG path=inline`). |
| Сверка | `JitOp.verifyApplied` + `JitRef.verify` (OK / NOT_APPLIED / CACHE_MISSING / UNKNOWN), pedido «kept» при неизвестности | `getprop` 8 с + maps, формула, `remove(jit_pedido)` всегда | **изменить** | Формула референса. Одно отличие: если `getprop` не прочитан (rc≠0 / вывод не `true`/`false`), сверка откладывается и `jit_pedido` остаётся — сразу после рестарта ADB-канал ещё поднимается; референс считал бы это «false» и выдал ложное «не применилось». |
| Сверка во время собственной цепочки | — | процесс со сверкой умирает вместе с zygote | **добавить флаг** | Пока клик в полёте (тот же процесс), сверка пропускается — иначе она сняла бы `jit_pedido` до рестарта. Это аналог busy-флага, не фаза. |
| Чтение maps | `JitRef.cacheExecutableFromMaps` | `j5.d` + фильтр `sa` case 3 | **изменить** (копия `j5.d`) | Подстроки `jit-cache` / `dalvik-jit-code-cache`, regex `\br[-w]x[ps]\b`, пусто ⇒ `null`. |
| Тумблер | по `getprop` (`JitRef.switchPosition`) | `setActive(getprop)` | оставить | Совпадает с DEX. Промпт говорит «getprop+maps» — в DEX maps на тумблер не влияет (см. §2.4), сделано по DEX. |
| Тост «не применилось» | строка-подсказка | `master_perf_jit_not_applied`, только `pedido==true` и не ok | **изменить** | Подсказка показывается по тому же условию. |
| Диалог подтверждения и прогресс-диалог | нет | есть | не добавлять | Новых элементов UI не добавляем (standing rules); состояние видно в строке статуса JIT. |
| `postDelayed(45 s)` UI | — | дочистка диалога | не добавлять | Диалога нет — дочищать нечего. |
| Авто boot-restore | нет (с iter33p) | нет | оставить | `setprop dalvik.vm.usejit` — только в цепочке клика. |
| `persist.*` JIT | только ЧТЕНИЕ в `jitBootProbe` (`JIT_BOOT persist=…`) | нет | **убрать** | Проба читает только `dalvik.vm.usejit`. |
| `jit_boot_restore_done` | не используется | нет | оставить | нечего удалять |
| Read-only диагностика (mount/internet diag, TTL-снимки сети) | есть | нет | оставить | Не участвует в клике и ничего не пишет; TTL — принятая функция, её трогать запрещено. |

## 5. Что этим НЕ доказывается

- Почему на 21.11 zygote не перезапустился, по логу установить нельзя: `.term` оборван после `TRAP rc=0`.
  Литерал референса проверяется только на стенде (настоящий `/bin/sh` с заглушками `svc`/`ip`/`setprop`/…).
- `setWifiEnabled(true)` из сервиса на ГУ не проверялся.
