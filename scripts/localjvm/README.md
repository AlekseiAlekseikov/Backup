# Локальный JVM-прогон поведенческих тестов (без Android SDK и без Gradle)

Зачем: доказать, что тесты `JitNetCoreTest`, `AvasCoreTest`, `AvasContractTest`,
`JitToggleChainTest`, `NetSanitizePlanTest`, `BuildMarkerTest` **реально исполняются** и проходят,
а не просто компилируются в CI. Всё, что они проверяют (`JitNetCore`, `JitNetModel`, `JitFlow`,
`AvasCore`, `AvasRestore`), не зависит от `android.*`: внешние эффекты подставляются лямбдами.

Здесь лежат только **заглушки компилятора** (`android.*`, `org.junit.*`, `AppLog`, `AdbClient`),
чтобы собрать модуль без SDK. Gradle их не видит: каталог вне `app/src/**`, в сборку не входит.

## Как повторить

```sh
# 1. компилятор Kotlin (любой 2.x), например:
curl -sL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
unzip -q /tmp/kotlin.zip -d /tmp

# 2. компиляция «ядра + тестов» с заглушками
cd <repo>/app/src/main/java/com/example/voiceapp3/tools
/tmp/kotlinc/bin/kotlinc -nowarn -d /tmp/kc/out \
  <repo>/scripts/localjvm/*.kt JitNetCore.kt JitNet.kt AvasCore.kt AvasRestore.kt Prefs2.kt BgWorker.kt \
  <repo>/app/src/test/java/com/example/voiceapp3/tools/{JitNetCoreTest,AvasCoreTest,AvasContractTest,JitToggleChainTest,NetSanitizePlanTest,BuildMarkerTest}.kt

# 3. запуск (важно: cwd = <repo>/app — тесты читают src/main/...)
cd <repo>/app
/tmp/kotlinc/bin/kotlinc -nowarn -cp /tmp/kc/out -d /tmp/kc/runner <repo>/scripts/localjvm/Runner.kt
java -cp /tmp/kc/out:/tmp/kc/runner:/tmp/kotlinc/lib/kotlin-stdlib.jar:/tmp/kotlinc/lib/kotlin-reflect.jar \
  RunnerKt com.example.voiceapp3.tools.AvasCoreTest com.example.voiceapp3.tools.JitNetCoreTest \
           com.example.voiceapp3.tools.AvasContractTest com.example.voiceapp3.tools.JitToggleChainTest \
           com.example.voiceapp3.tools.NetSanitizePlanTest com.example.voiceapp3.tools.BuildMarkerTest
```

Ожидаемый хвост: `ИТОГ: PASS=50 FAIL=0`.

## Границы этого прогона (честно)

- это **не** запуск на ГУ: Android-поведение (Car API, `WifiManager`, `ctl.restart`) здесь
  подменено, проверяются переходы логики и обработка отказов;
- `AppPerformance.kt` (оркестратор) в этот прогон не входит: он целиком на `android.*`;
  его контракты проверяются текстовыми тестами (`JitToggleChainTest`, `AvasContractTest`) и
  компиляцией CI, а переходы — через вынесенные в `JitFlow`/`JitNetCore`/`AvasCore` функции;
- официальный прогон всё равно CI (`./gradlew testDebugUnitTest`), локальный нужен как быстрое
  доказательство исполнимости тестов и как отладка.
