#!/bin/sh
# Stage CXT · commit 1: проверка, что новое меню «Инструменты» и дизайн-система
# CentralEXAuto действительно попали в СОБРАННЫЙ APK и грузятся WebView-шеллом.
# Запуск из корня проекта:  ./check-centralex-tools.sh [путь_к_apk]
# (по умолчанию app/build/outputs/apk/release/app-release.apk)
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
if [ ! -f "$APK" ]; then echo "НЕТ APK: $APK"; exit 1; fi
ok=1
UI=$(unzip -p "$APK" assets/ui/index.html 2>/dev/null)
if [ -z "$UI" ]; then echo "FAIL в APK нет assets/ui/index.html — шелл не тот"; exit 1; fi

has() { if printf '%s' "$UI" | grep -qF -e "$1"; then echo "OK  $2"; else echo "FAIL $2 — НЕ в APK"; ok=0; fi; }
hasnt() { if printf '%s' "$UI" | grep -qF -e "$1"; then echo "FAIL $2 — старое осталось в APK"; ok=0; else echo "OK  $2"; fi; }

echo "── WebView: структура и дизайн-система ──"
has "var TOOLS_TREE=[" "дерево категорий §3 (Интерфейс/Автомобиль/Климат и окна/Безопасность/Быстродействие)"
has "screen:cxPageScreen" "страница «Экран» зарегистрирована"
has "notifications:cxPageNotifications" "страница «Панель уведомлений» зарегистрирована (§5)"
has "status:cxPageStatus" "страница «Строка состояния» зарегистрирована (§6)"
has "var CX_S={" "строки и описания CentralEXAuto (values-ru)"
has "--cx-green:#34C759" "акцент панели = цвет тумблеров шелла (.sw)"
has "body[data-cx=dark]{--cx-green:#30D158" "ночная пара акцента (ярче на тёмном)"
has "--cx-surface:var(--card)" "токены выведены из палитры GeelyTools (блок = теме приложения)"
has "--cx-divider:var(--line)" "разделители из палитры (текст и блоки не сливаются)"
has "--cx-surface-inset:var(--card2)" "сегменты/плитки на --card2"
has "body{" "токены объявлены на body: var() палитры резолвятся там, а не на :root"
has ".contentCard.cxmode{padding:26px 30px" "карточка вкладки: штатный фон .card + свои отступы"
has ".cx-nav{width:340px" "левая панель 340dp"
has ".cx-item.sel .cx-mark{background:var(--cx-green)}" "зелёный вертикальный индикатор выбранного пункта"
has ".cx-toggle{position:relative;display:inline-block;width:56px;height:32px" "MasterToggleView 56×32dp"
has ".cx-page{flex:1;min-width:0;display:flex;flex-direction:column;overflow-y:auto" "независимый скролл правой панели"
has "function toolsWire(){" "проводка контролов"
has "native:tools.state" "приём снимка состояния из native"

echo "── WebView: правки по замечаниям с ГУ ──"
has ".main.cxw{grid-template-columns:minmax(380px,.8fr) minmax(460px,1.7fr)}" "карточка машины не скрыта, правая колонка шире"
has ".cx-items{padding-left:20px}" "отступ 1-го уровня вложенности"
has ".cx-items .cx-items{padding-left:28px}" "отступ 2-го уровня вложенности"
has ".cx-cat[aria-expanded=true] .cx-chev svg,.cx-item[aria-expanded=true] .cx-chev svg" "стрелка поворачивается и у вложенных групп"
has ".cx-nav>.cx-div{margin:10px 2px}" "воздух у разделителя между категориями (не липнет к плашке пункта)"
has ".cx-toggle input:focus-visible+u" "видимый фокус тумблера — только для навигации с кнопок"
has "if(k in SET)SET[k]=i.checked;" "тумблер пишет в общий SET — полоса климата не отстаёт"
has "if(typeof applyClim==='function')applyClim();" "снимок реестра применяет состояние полосы климата"
has "var confirmed=d.last&&d.last.action==='settings.theme'" "выбор палитры принимается из снимка только как подтверждение плиток"
has "if(!TOOLS.themeSeeded||confirmed)" "действующая палитра (follow+тёмная ГУ) не затирает выбор"
has "SET.follow&&SET.gu==='dark'" "перекрывание темы ГУ считается в applyTheme, а не пишется в выбор"

echo "── WebView: срез №2 — яркость, запоминание по фарам, «Время заставки» ──"
has "cxSliderRow({key:'bright',title:'Яркость',min:5,max:100,unit:'%'" "слайдер «Яркость» 5..100% (MasterToolsBrightness)"
has "cxToggleRow({key:'remember',title:'Запоминать яркость по фарам'" "тумблер «Запоминать яркость по фарам» (master_remember_brightness)"
has "cxSegRow({key:'rest',title:'Время заставки'" "«Время заставки» в секции «Экран»"
has "['0','Выкл'],['10','10 с'],['30','30 с'],['60','1 мин'],['120','2 мин']" "пять вариантов: Выкл / 10 с / 30 с / 1 мин / 2 мин"
has "data-cxslider]').forEach" "слайдер синхронизируется снимком tools.state (откат оптимизма)"
has "data-unit=" "подпись «%» обновляется при драге (data-unit)"
has "cxNote(o.key)" "статус шага под контролом (cxNote)"

echo "── WebView: переключает только тумблер, обводка от нажатия не остаётся ──"
hasnt "[data-cxrow]').forEach" "обработчик клика по строке (значение меняет только тумблер)"
hasnt "cx-row clk" "курсор-подсказка «строка кликабельна»"
hasnt ".cx-row:focus-within" "зелёная обводка строки от нажатия на тумблер"

echo "── WebView: старое меню и заглушки удалены ──"
hasnt "TOOL_TABS" "старый сегментный тулбар (Система/Экран/CarPlay)"
hasnt "data-ttool" "переключатели старого меню"
hasnt "tool.run" "кнопки-демо «Очистка дисплея/Логи на USB/Перезагрузка ГУ»"
hasnt "Память ГУ" "мок-строка «9 ГБ свободно»"
hasnt "--cx-surface:#FFFFFF" "фиксированная белая подложка CentralEXAuto (ломала тему)"
hasnt "background:var(--cx-window)" "собственный фон карточки вкладки (не совпадал с темой)"
for s in "ещё не реализована" "не реализовано" "Управление недоступно" "placeholder" "функция будет доступна"; do
  if printf '%s' "$UI" | grep -F -e "$s" | grep -vE '^[[:space:]]*(\*|//|/\*)' >/dev/null 2>&1; then
    echo "FAIL пользовательская строка-заглушка «$s» найдена в APK"; ok=0
  fi
done
echo "OK  пользовательских заглушек в APK не найдено"

if printf '%s' "$UI" | grep -qF "toggle('full',['carplay','aa','telemetry','dashcam','navi','tools']"; then
  echo "FAIL «Инструменты» всё ещё в full-списке (карточка машины скрыта)"; ok=0
else
  echo "OK  «Инструменты» убраны из full-режима — carCard виден"
fi

echo "── Kotlin: реестр настроек и классы ──"
DEX=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null)
hasd() { if printf '%s' "$DEX" | grep -aqF "$1"; then echo "OK  $2"; else echo "FAIL $2 — НЕ в APK"; ok=0; fi; }
hasnd() { if printf '%s' "$DEX" | grep -aqF "$1"; then echo "FAIL $2 — найдено в APK"; ok=0; else echo "OK  $2"; fi; }
hasd "com/example/voiceapp3/tools/ToolsBackend" "ToolsBackend (единый реестр настроек вкладки)"
hasd "com/example/voiceapp3/tools/ToolsResult" "ToolsResult (структурированный результат шага §12)"
hasd "com/example/voiceapp3/tools/ToolsHost" "ToolsHost (живые эффекты окна и движка)"
hasd "app_immersive_mode_enabled" "ключ «Полный экран» не переименован"
hasd "app_launch_on_wake_enabled" "ключ «Запуск при пробуждении» не переименован"
hasd "app_climate_bar_enabled" "ключ «Полоса климата» не переименован"
hasd "app_projection_beta_enabled" "ключ CarPlay/Android Auto не переименован"
hasd "ui_head_unit_theme_mode" "новый ключ темы ГУ"
hasd "ui_theme_effective_id" "Kotlin: отдельный ключ действующей палитры (выбор не затирается)"
hasd "tools.state" "канал снимка состояния в JS"

echo "── Kotlin: срез №2 — яркость и «Время заставки» ──"
hasd "com/example/voiceapp3/tools/ScreenBrightness" "ScreenBrightness (порт BrightMem: яркость/запоминание/заставка)"
hasd "/sys/class/leds/lcd-backlight/brightness" "sysfs-путь яркости как в APK"
hasd "persist.sys.geely.screen_brightness" "свойство CentralEXAuto"
hasd "bright_day" "корзина «день» (фары выключены)"
hasd "bright_night" "корзина «ночь» (фары включены)"
hasd "remember_brightness" "ключ запоминания не переименован"
hasd "screen_rest_timeout_sec" "ключ «Время заставки»"
hasd "screen_brightness" "запись Settings.System как в APK"
hasd "ACTION_SCREEN_OFF" "событие сна: снятие текущего уровня (порт z)"
hasd "boot_restore" "boot-ветка восстановления яркости (порт w)"
hasnd "setenforce" "setenforce 0 не переносится (решение GeelyTools)"

echo "── Kotlin: срез №3 — статус-иконки и строка состояния (§5, §6) ──"
hasd "com/example/voiceapp3/tools/StatusIcons" "StatusIcons (порт w0.kg: батарея/температура/wifi)"
hasd "central_ex_status_temp" "канал уведомлений батареи/температуры"
hasd "central_ex_status_wifi" "канал уведомлений Wi-Fi"
hasd "status_battery_enabled" "ключ батареи на панели как в APK"
hasd "status_temp_ext_enabled" "ключ температуры на панели как в APK"
hasd "status_wifi_enabled" "ключ Wi-Fi на панели как в APK"
hasd "flag_status_icon_notification" "Flyme flag_status_icon_* extras"
hasnd "status_signal_enabled" "«Сигнал на панели» НЕ переносится (§5)"
hasd "persist.sys.hvacdigital.batt" "свойство строки состояния hvac_digital_batt"
hasd "persist.sys.hvacdigital.temp" "свойство строки состояния hvac_digital_temp"
hasd "persist.sys.hvacdigital.ext_temp" "свойство строки состояния hvac_digital_ext_temp"

echo "── Kotlin: срез №4 — звук блокировки замка (§7 ТЗ) ──"
hasd "com/example/voiceapp3/tools/LockSound" "LockSound (порт AVAS lock sound pipeline)"
hasd "lock_car_audio_enable" "ключ настроек lock_car_audio_enable как в APK"
hasd "/data/local/tmp/avas_preview/preview.wav" "staging путь preview.wav как в APK"
hasd "/system/etc/avas/sound_type_1" "OEM AVAS sound_type_1 путь как в APK"

echo "── Kotlin: срез №5 — окна и двери (§9 ТЗ) ──"
hasd "com/example/voiceapp3/tools/WindowDoors" "WindowDoors (порт логики стекол и дверей w0.s2)"
hasd "window_driver_on_door" "ключ window_driver_on_door как в APK"
hasd "window_opposite_on_door" "ключ window_opposite_on_door как в APK"

echo "── Kotlin: срез №6 — сеть и TTL 65 ──"
hasd "com/example/voiceapp3/tools/NetworkTtl" "NetworkTtl (порт TTL 65 iptables mangle)"
hasd "ttl_65_ativo" "ключ ttl_65_ativo как в APK"
hasd "ttl-set 65" "iptables mangle правило ttl-set 65"
hasd "hl-set  65" "ip6tables mangle правило hl-set 65"

echo "── Kotlin: срез №7 — быстродействие и фоновая работа ──"
hasd "com/example/voiceapp3/tools/AppPerformance" "AppPerformance (порт JIT, видеодекодера и Keep-Alive)"
hasd "perf_auto_otimizar" "ключ perf_auto_otimizar как в APK"
hasd "dalvik.vm.usejit" "системное свойство dalvik.vm.usejit"
hasd "background_keep_alive_prefs" "SharedPreferences background_keep_alive_prefs"
hasd "vcodec642" "подмена декодера видео vcodec642"

echo "── сборки-артефакты ──"
if unzip -l "$APK" | grep -q "assets/ui/index.html"; then echo "OK  assets/ui/index.html упакован"; else echo "FAIL assets/ui/index.html отсутствует"; ok=0; fi
[ $ok -eq 1 ] && echo "=== ВСЕ ПРАВКИ COMMIT №1–7 В APK ===" || echo "=== ЕСТЬ ПРОБЛЕМЫ — APK собран не из этих исходников ==="
exit $((1 - ok))
