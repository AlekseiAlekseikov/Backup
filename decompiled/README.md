# decompiled/ — декомпилированные артефакты CentralEXAuto

Источник: **/home/user/reverse/jadx_out/sources/** (вне репозитория, т.к. ~500 МБ).

Ключевые файлы, которые использованы для порта:

| Путь в jadx_out | Назначение |
|---|---|
| `w0/m.java` | `ToolsBackend`-shell dispatcher: `l()` (ensurePortMigrated), `y(8000,SCRIPT)` миграция порта, `x()` big-script base64-staging, `f3919a` кэш порта |
| `w0/n.java` | Shell-раннер: case 0 — ADB сокет с порядком проб `{65010, 5555}`; case 1/4/13 force 5555; case 9 QuickAccessFloaterService; case 10/11/12 zf.a |
| `kotlinx/coroutines/scheduling/h.java` | Низкоуровневый ADB-протокол: CNXN (0x4E584E43), AUTH (0x48545541), OPEN/OKAY/WRTE/CLSE, баннер `host::central-geely\0` |
| `w0/a3.java` | VHAL provider: подписка на CarPropertyManager (SOC/odometer/outTemp) через ThreadPoolExecutor |
| `w0/la.java` case 0 | JIT/zygote restart (`svc wifi disable; umount; setprop dalvik.vm.usejit; ctl.restart zygote`) |
| `w0/kg.j` | Status icons publisher: notification IDs 15020 (batt), 15021 (temp), 15024 (km), 15022 (trip), отдельные каналы |
| `w0/rg.java` | VHAL data fields: d=soc, p=outTemp, f=odometer |
| `w0/z8.java`/`w0/s8.java` | TTL set (iptables -t mangle POSTROUTING -j TTL --ttl-set 65, HL для IPv6) |

Дамп прошивки ЭБУ GEELY: `/home/user/dump/extracted/geely_dump/` —
`vhal/rvi_veh_prop.json` (property IDs/типы/минимальный ID),
`car_service_dump.txt` (значения SOC/outTemp на момент дампа: EV_BATTERY_LEVEL=100,
ENV_OUTSIDE_TEMPERATURE=6.0, ED_EV_BATTERY_PERCENTAGE=1000).

## Полная перегенерация

```
jadx --no-res -d reverse/jadx_out <path-to-CentralEXAuto.apk>
```
