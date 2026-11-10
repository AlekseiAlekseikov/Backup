# Локальный JVM-прогон поведенческих тестов (без Android SDK и без Gradle)

Зачем: доказать, что тесты JIT-операции **реально исполняются** и проходят, а не просто
компилируются в CI. Всё, что они проверяют (`JitRef`, `JitOp`, `JitNet`-швы, `JitBootMark`),
не зависит от `android.*`: shell, префы и состояние сети подставляются интерфейсами
`JitShell` / `JitStore` / `JitNetState`.

Здесь лежат только **заглушки компилятора** (`android.*`, `org.junit.*`, `AppLog`, `AdbClient`),
чтобы собрать модуль без SDK. Gradle их не видит: каталог вне `app/src/**`, в сборку не входит.

## Как повторить

```sh
curl -sL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip
unzip -q /tmp/kotlin.zip -d /tmp

cd <repo>
/tmp/kotlinc/bin/kotlinc -nowarn -d /tmp/kcout/out scripts/localjvm/*.kt \
  app/src/main/java/com/example/voiceapp3/tools/{JitNetCore.kt,JitNet.kt,JitBootMark.kt,Prefs2.kt,BgWorker.kt} \
  app/src/test/java/com/example/voiceapp3/tools/{JitIsoStand.kt,JitRefTest.kt,JitChainScriptTest.kt,JitChainEndToEndTest.kt,JitBootTest.kt,JitRulesReadOnlyTest.kt,JitNoPersistTest.kt,JitBootMarkTest.kt,AvasIndependenceTest.kt,JitTogglePathTest.kt}

/tmp/kotlinc/bin/kotlinc -nowarn -cp /tmp/kcout/out -d /tmp/kcout/runner scripts/localjvm/Runner.kt

cd app
java -cp /tmp/kcout/out:/tmp/kcout/runner:/tmp/kotlinc/lib/kotlin-stdlib.jar:/tmp/kotlinc/lib/kotlin-reflect.jar \
  RunnerKt com.example.voiceapp3.tools.JitRefTest com.example.voiceapp3.tools.JitChainScriptTest \
           com.example.voiceapp3.tools.JitChainEndToEndTest com.example.voiceapp3.tools.JitBootTest \
           com.example.voiceapp3.tools.JitRulesReadOnlyTest com.example.voiceapp3.tools.JitNoPersistTest \
           com.example.voiceapp3.tools.JitBootMarkTest com.example.voiceapp3.tools.AvasIndependenceTest \
           com.example.voiceapp3.tools.JitTogglePathTest
```

Ожидаемый хвост (1.0.61, повтор iter33p): `ИТОГ: PASS=55 FAIL=0`.

## Что именно исполняется

| Тест | Что доказывает |
|---|---|
| `JitRefTest` | разбор `jit_restaurar` строго по формату референса, текст скрипта, формула сверки `bb/r7`, решение о продолжении операции, отказ второго клика |
| `JitChainScriptTest` | **настоящий `/bin/sh`** на изолированном стенде: порядок `svc wifi disable` → `ip rule show` (≤20) → `setprop dalvik.vm.usejit` → `setprop ctl.restart zygote` последней командой; шаг `umount -l` только при включённой константе |
| `JitChainEndToEndTest` | сквозная операция: снимок → скрипт → продолжение после zygote (AP, затем Wi-Fi, пауза 15000 мс) → сверка; отказ записи снимка, неизвестное состояние сети, чужой/старый снимок, потеря канала, 4 одновременных клика (общий store) |
| `JitBootTest` | обычный старт/загрузка: ноль команд, ноль рестартов; в коде нет `bootRestoreJit`, `jitPersistBackfill`, повтора через 30 с и собственных механизмов |
| `JitRulesReadOnlyTest` | `ip rule` только считается: ни `del`, ни `flush`, ни `ndc`, ни `settings` |
| `JitNoPersistTest` | нет записи `persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles`; старые значения только читаются |
| `AvasIndependenceTest` | AVAS вне JIT: механизм `AvasCore`/`AvasRestore` удалён, JIT-код на AVAS не ссылается, в цепочке нет ни одной AVAS-команды |
| `JitTogglePathTest` | путь `setAutoOptimize` → `jitApplyWithZygote` → `JitNet.click`: нет backfill-а, нет своей сборки скрипта и своего `AdbClient` |

## Границы этого прогона (честно)

- это **не** запуск на ГУ: `WifiManager`, `startSoftAp`, `ctl.restart zygote` здесь подменены;
  проверены переходы логики, фактические shell-строки и обработка отказов;
- интерфейсы `JitShell`/`JitStore`/`JitNetState` — шов для тестов: в бою их реализуют
  `AdbClient`, `Prefs2` и `WifiManager` (`JitNet.AndroidShell/AndroidStore/AndroidNetState`);
- `AppPerformance` и `VoiceAssistantService` целиком на JVM не собираются (Android-зависимости),
  их путь проверяется статически в `JitTogglePathTest`/`JitBootTest` + компиляцией в CI.
