# decompiled/ — декомпилированные артефакты CentralEXAuto

В этой директории лежат **ключевые файлы**, на которые опирается порт Инструментов.
Полный jadx-вывод (≈500 МБ) лежит в `/home/user/reverse/jadx_out/sources/` на машине агента и
не добавляется в репозиторий целиком; здесь собраны файлы, которые прямо цитируются в
`docs/centralexauto-map.md` и `AUDIT.md`.

## centralex/w0/ (декомпиляция CentralEXAuto APK)

| Файл | Описание |
|---|---|
| `m.java` | `ToolsBackend`-shell dispatcher: `l()` = ensurePortMigrated; `y(8000,SCRIPT)` = миграция порта; `f3919a` — кэш рабочего порта; `x()` — big-script base64-staging для скриптов >3500 байт |
| `n.java` | Shell-раннер: case 0 = основной ADB-сокет с порядком проб портов `{65010, 5555}`; case 1/4/13 = force port 5555; case 9 = QuickAccessFloaterService; case 10/11/12 = zf.a (другие руты) |
| `kotlinx/coroutines/scheduling/h.java` | Низкоуровневый ADB-протокол: CNXN (0x4E584E43), AUTH (0x48545541), OPEN/OKAY/WRTE/CLSE, баннер `host::central-geely\0`, maxpayload=4096 |
| `rg.java` | VhalProviderService — подписчик на VHAL property (SOC=d, outTemp=p, odo=f) |
| `kg.java` | Status icons publisher (прототип наших `StatusIcons`) — notification IDs 15020/15021/15024, отдельные каналы |
| `z8.java` | TTL set (iptables -t mangle POSTROUTING -j TTL --ttl-set 65; HL для IPv6) |
| `la.java` | JIT/zygote restart case 0: svc wifi disable; umount /data/app/*; setprop dalvik.vm.usejit; ctl.restart zygote |
| `qd.java` | QuickAccessFloaterService (плавающая кнопка быстрого доступа) |
| `fh.java` | Обработка клика по Wi-Fi-иконке |

## Как пересобрать полный вывод

```sh
jadx --no-res -d reverse/jadx_out <path-to-CentralEXAuto.apk>
apktool d <path-to-CentralEXAuto.apk> -o reverse/apktool_out
```

## Дамп прошивки ЭБУ GEELY

Дамп (по новой ссылке из `prompt_continue.md`) лежит на диске у пользователя. Ключевые
выдержки, которые использованы для порта:

- EV_BATTERY_LEVEL = `0x11600309` (Float, процент заряда 0..100)
- ED_EV_BATTERY_PERCENTAGE = `0x2140a6ed` (Int, x10 — делим на 10)
- ENV_OUTSIDE_TEMPERATURE = `0x11600703` (Float °C)
- `persist.sys.hvacdigital.exttemp` — наружная температура из плагина климата
  (исправлено со старого неверного `persist.sys.hvacdigital.temp_ext`)

## HvacDigital-platform-signed.apk

Сам APK в корне репозитория — это подписанный плагин (цифровой климат в статус-баре).
Его манифест/AIDL не декомпилированы повторно в этой сессии (при необходимости добавить — можно вытащить из `/home/user/reverse/HvacDigital_jadx/`).
