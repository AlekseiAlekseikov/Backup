# ITER58 — ОТЧЁТ (восстановленная итерация)

**Версия:** `versionCode=63`, `versionName="1.0.63"` (`app/build.gradle.kts:78–79`) — **не повышалась**.
**База:** `026e2f491b022770baa12c69399e8f8defa2c2f5` (`iter57: точные имена тестов в матрице отчёта`).

> **Почему итерация «восстановленная».** Песочница потеряла `.git` и весь `app/src` рабочего дерева
> между сообщениями; коммиты iter58 существовали только как запись (см. `ITER58_COMMITS.md`).
> Итерация **повторена заново** на свежем клоне `fix/v2`: те же правки применены теми же скриптами,
> тесты написаны заново и исполнены. **SHAs отличаются** от прежней записи — воспроизвести хэши
> невозможно (они зависят от точного содержимого и времени). Ничего из прежней записи не выдаётся
> за выполненное здесь без повторного прогона.

```
b7ae415f  iter58 §2.1 JIT-lock: admin-файл живого владельца не снимается; release и self-mark под admin
bb5fb154  iter58 §4 delivery: код возврата снимается один раз; terminal .done больше не врёт
d88ba313  iter58 §3 звук: агрегатор возвращает Pending/Conflict и не обходит сверку
a99f2d7f  iter58 §5 TTL: маркер владения с проверкой чтения и привязкой к поколению
263df2bf  iter58 §2.3 JIT: исполнимые проверки root/потери ответа/Wi-Fi/отказа возврата
```

**Локальный прогон:** `OK (921 tests)` (база `026e2f4` тем же стендом — `OK (890 tests)`).

**CI — зелёный, но не с первого раза (важно):**
1. push `c95f7aa` → run [`43846209389`](https://github.com/AlekseiAlekseikov/Backup/actions/runs/43846209389)
   — **failure**: `LockSound.kt:1835 Unresolved reference 'evalLog'` и
   `LockSound.kt:1929 Condition type mismatch: Unit but Boolean`. Это ровно тот класс ошибок,
   который локальный JVM-стенд не видит (`LockSound.kt` в него не входит).
2. Исправлено коммитом `7ddee6b`: вместо несуществующего `evalLog` — запись в `AppLog`;
   `applySystemLockSoundBind` возвращает `Unit` (работа уходит в `BgWorker`), поэтому «применено»
   больше не заявляется — возвращается `Pending("bind_scheduled")`.
3. Добавлен страж `typecheck_lock.sh` (сравнение ошибок компиляции ВСЕГО пакета `tools` с базой
   `026e2f4`): на `c95f7aa` он воспроизводит обе ошибки CI и падает, на исправлении — проходит.
4. push `7ddee6b` → run [`43849065192`](https://github.com/AlekseiAlekseikov/Backup/actions/runs/43849065192)
   — **success**; job `133236610256`, шаги «Gradle build (debug)» = success, «Upload APK» = success,
   артефакт `app-debug` (177 449 429 байт).

**Границы CI:** workflow собирает только **debug** и загружает APK. Юнит-тесты (921) в CI **не
исполняются**, release не собирается, на устройстве ничего не запускается.

---

## 1. Чек-лист

| Пункт | Статус | Где (файл:строка) |
|---|---|---|
| §2.1 admin-lock: живой владелец не снимается; смерть доказывается положительно | ✅ код + 7 тестов на `/bin/sh` | `JitOwn.kt` (`_jit_death_proven`, `_jit_admin_enter`, `_jit_release`, `_lock_self_mark`, `_jit_may_takeover`) |
| §2.1 `release` под admin, повторное чтение, удаление только своей строки | ✅ | `JitOwn.kt` `_jit_release` |
| §2.1 `_lock_self_mark` определена ДО вызова | ✅ | `JitOwn.kt` (общие helper'ы `ownHelpers`) |
| §3.1 агрегатор не обходит сверку | ✅ код + 11 тестов | `LockSound.kt` (`val gate = LockOp.recoveryGate(`, `return gate`), `LockOp.kt` `recoveryGate` |
| §3.2 restore только после разрешения и с подготовленным адресом | ✅ код + тесты | `LockOp.kt` `reconStep` (`previous_restore_unresolved`, `restore_evidence_not_prepared`) |
| §3.2 отложенный reread — read-only | ✅ | `LockSound.kt` `allowRestore = false` |
| §4 `.done` = код payload (7/127), отказ записи — `written=0` | ✅ доказано на реальном `/bin/sh` | `ScriptDelivery.kt` `directScript` |
| §5 TTL: маркер с проверкой чтения, поколение, раздельные семейства | ✅ код + 4 теста (2 падают на iter57) | `TtlScript.kt` `ownmark_ok`, запись маркера, `clear_family` |
| §2.3 JIT: root/потеря ответа/Wi-Fi/отказ возврата | ✅ JVM+fake-shell · 🟡 реальный zygote/интернет | `Iter58JitNetTest`, `Iter57JitE2eTest` |
| §6 Логи/UI | 🟡 не правились в этой итерации | — |
| Release-сборка | ⛔ Gradle 9 требует JVM 17 (в песочнице 11) | — |
| Действия на ГУ | ⛔ не выполнялись | протокол §5 |

---

## 2. Assertion → код → результат

### 2.1 §2.1 JIT-lock

*Assertion 1:* `_jit_admin_enter` НЕ снимает admin-файл живого владельца.
*Код:* `_jit_admin_enter` → `_jit_death_proven "$_ad_cur"` (rc 0 = смерть доказана) вместо
отрицания функции с обратной полярностью (дефект iter57).
*Результат:* `Iter58JitLockTest.adminFileOfALivingOwnerIsNeverRemoved` — настоящий `sleep` как
владелец, реальные `/proc/<pid>/stat`: вход отклонён (`ENTER_RC=1`), файл не изменён побайтно.

*Assertion 2:* смерть доказанного владельца позволяет войти.
*Результат:* `adminFileOfADeadOwnerIsTakenOver` — процесс завершён ⇒ `ENTER_RC=0`, файл наш.

*Assertion 3:* `pid reuse` — смерть; живой pid с той же identity — нет.
*Результат:* `pidReuseIsDeathButTheSameStartTimeIsALivingOwner` (три случая: совпавшая identity,
другой `pstart`, другая `boot_id`).

*Assertion 4:* `_jit_release` не удаляет чужой lock.
*Результат:* `releaseNeverDeletesAnotherOwnersLock` (`REL_RC=1`, файл не изменён).

*Assertion 5:* release × takeover с барьером — ровно один владелец.
*Результат:* `releaseAndTakeoverLeaveExactlyOneOwner` — претендент ставит свой lock только после
освобождения; admin-файла не остаётся.

*Assertion 6:* crash после захвата lock не блокирует следующий claim.
*Результат:* `crashAfterTakingTheLockDoesNotBlockTheNextClaim` — `kill -9` держателя, затем
production `claimCmd` → `CL_STATUS=taken`, `CL_TAKEOVER … owner_dead`, claim принадлежит новому.

*Assertion 7:* два настоящих shell одновременно — один победитель.
*Результат:* `twoRealShellsWithABarrierYieldExactlyOneOwner` — один `taken`, второй обязан назвать
причину (`busy`/`stale`/`failed`) и не заявлять успех; транзиентный lock снят.

### 2.2 §4 Delivery

*Assertion:* payload `exit 7` ⇒ `.done` = `rc=7`, внешний код 7; `exit 127` не схлопывается;
отказ записи ⇒ `written=0` и файла нет.
*Код:* `ScriptDelivery.directScript` — `_VRC` снимается ОДИН раз сразу после payload; в
`terminal`-хвосте больше нет второго `_VRC=$?` (он захватывал код собственного `echo` обёртки).
*Результат:* `Iter58DeliveryRcTest` (4 теста, настоящий `/bin/sh`): `rc=7` / `rc=127` / `rc=0` /
`written=0` при отсутствующем каталоге.

### 2.3 §3 Звук

*Assertion:* при `unresolved`/`lateShell`/`conflict`/read-only проходе не должно быть bind,
отметки OFF и `Ready`.
*Код:* `LockOp.recoveryGate(unresolved, conflict, lateShell, readOnlyPass)` — единственное правило;
`LockSound.reconcileLocked` присваивает `val gate = …` и делает `return gate` до
`applySystemLockSoundBind` и `markApplied(..., "reconciled_off")`.
*Результат:* `Iter58SoundTest` (11 тестов): правило и его старшинство, проверка порядка вызовов в
production-файле, `previous_restore_unresolved` (0 отправок), пустой `prepareRestore` ⇒ 0 отправок,
доказанный bind + адрес ⇒ ровно один restore, недоказанный bind ⇒ `UNDEFINED` даже при «похожем на
сток» readback, `restore_not_sent` ⇒ повтор возможен без заявления побочных действий.
🟡 Реальные `mount/umount` и Android-сервис не исполнялись.

### 2.4 §5 TTL

*Assertion 1:* маркер не записался ⇒ ни правила, ни перехода, ни `write=ok`.
*Результат:* `unpersistedOwnMarkerStopsThePassAndRemovesTheChainWeMade` — **на коде iter57 падает**
(проверено откатом `TtlScript.kt` к `026e2f4`), на `a99f2d7f` проходит; вывод содержит
`write=failed`, `reason=ownmark_not_persisted`, в нашей цепи нет `-A`, цепь не осталась сиротой.

*Assertion 2:* OFF одного семейства не стирает маркер другого.
*Результат:* `removingOneFamilyKeepsTheOtherFamilysOwnershipRecord` — **на iter57 падает**
(`rm -f` файла целиком), сейчас: `clear_family=v4 remaining=yes`, в файле остаётся `v6=created` и
шапка.

*Assertion 3:* маркер прошлого поколения владение не доказывает (`ownmark_ok`: цепь + поколение +
существование цепи). Результат: `markerOfAnotherGenerationIsNotProofOfOwnership` — состояние
`unproven/collision`, ни `-F`, ни `-X`, таблица не изменена.

---

## 3. Что осталось открытым

| Пункт | Статус | Причина |
|---|---|---|
| §6 логи/UI (Pending/Conflict в интерфейсе без красных текстов и выдуманных кнопок) | 🟡 | в этой итерации не правились |
| Release-артефакт и matrix-сборка | ⛔ | Gradle 9 требует JVM 17 |
| CI на iter58 | ⛔ | нет учётных данных для push |
| Реальный `zygote`/`svc wifi`/`iptables`/`mount` на ГУ | ⛔ | на ГУ ничего не выполнялось |
| Построчное сопоставление `w0/j9·la·e2·bb·r7·sa·j5` | 🟡 | сверено чтением кода + существующими тестами |

---

## 4. Разделение доказательств

* **Исполнено и проверено здесь:** JVM-тесты + настоящий `/bin/sh` + настоящие процессы `/proc` +
  подставные `iptables`/`ip6tables` (argv и таблица — файлы).
* **НЕ проверено:** реальный Android/toybox, `zygote`, `svc wifi`, DNS/маршрут/страница на ГУ,
  реальный `umount` звуковых слоёв. **Ассоциация Wi‑Fi не является доказательством интернета.**

## 5. Протокол владельцу (на согласование; на ГУ не исполнять)

1. Установить 1.0.63. До любых действий записать: `getprop dalvik.vm.usejit`, состояние Wi‑Fi,
   `ip rule show | grep -c 'oif wlan0'`, `iptables -t mangle -S`. Включить подробные логи.
2. Один клик JIT ON, ждать 30–40 с, повторно не нажимать (вторая цепочка будет отклонена).
   После возврата: `getprop dalvik.vm.usejit` = `true`, Wi‑Fi включён, в логе
   `JIT_APPLIED_RESULT sent=true … restart=not_claimed`.
3. Проверять **страницу**, а не только ассоциацию. Если ассоциация есть, а страница не
   открывается — это отдельный сетевой дефект, **не** доказательство влияния TTL.
4. Один клик JIT OFF, снова проверить свойство и Wi‑Fi.
5. TTL ON (10 минут, с раздачей) → TTL OFF; до/после — `iptables -t mangle -S` и `ip rule show`.
   Ожидается: наша цепь и переход исчезли, чужие правила не изменены. Если что-то осталось — не
   повторять клик, снять логи (`TTL_RESULT` назовёт причину).
6. Безопасный выход: если Wi‑Fi не вернулся — включить его штатно в настройках Android.
   Перезагрузка как «лечение» не предлагается.
7. Прислать логи из `getExternalFilesDir(null)/logs/` и время кликов. IP/MAC/SSID/токены и
   сетевые конфиги присылать не нужно.

Ограничения не снимаются: гарантий «интернет будет» без испытания нет; TTL не объявляется причиной
прошлых проблем с интернетом; AVAS/AP/плагин JIT не касаются.

## 6. Воспроизведение

```bash
bash /home/user/runtests58.sh                 # клон /tmp/iter58 → OK (921 tests)
cd /tmp/iter58 && git log --oneline 026e2f4..HEAD
git show HEAD:app/buildinfo.properties
git diff --name-only <SOURCE_SHA>..HEAD
```
