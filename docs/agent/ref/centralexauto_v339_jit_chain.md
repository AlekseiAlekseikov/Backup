# CentralEXAuto 3.3.9 — JIT-цепочка по коду (выписки)

Источник: `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f`) из ZIP
md5 `8a65c6c29749c64d4e335c57490a347a`. Инструменты: jadx 1.4.7 + baksmali 2.5.2
(два метода jadx не декомпилировал — `w0/j9.onClick`, `CentralWatchdogService.U(...)`).

## 1. Клик — `w0/j9.onClick` (в `w0/b9`), ветка включения

```java
WifiManager wm = (WifiManager) ctx.getSystemService("wifi");
boolean wifi = wm.isWifiEnabled();                       // try/catch -> false
NetworkInterface ap0 = NetworkInterface.getByName("ap0");
boolean ap = ap0 != null && ap0.isUp() && ap0.getInetAddresses().hasMoreElements();
prefs.edit()
     .putBoolean("jit_pedido", target)
     .putBoolean("perf_auto_otimizar", target)
     .putString("jit_restaurar", "wifi=" + wifi + ",ap=" + ap)
     .commit();                                          // ДО любых изменений
log("dalvik.vm.usejit -> " + target + "; reiniciando o framework");
pool.execute(new la(activity, target, progress));
```

## 2. Единственный скрипт — `w0/la.run` (case 0)

```java
StringBuilder sb = new StringBuilder("\n        svc wifi disable\n        _n=0\n        while [ $_n -lt 20 ]; do\n          [ \"$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n          sleep 1; _n=$((_n + 1))\n        done\n        for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do\n          umount -l \"$_m\" 2>/dev/null\n        done\n        setprop dalvik.vm.usejit ");
sb.append(target ? "true" : "false");
sb.append("\n        setprop ctl.restart zygote\n    ");
activity.H1(40000, o2.m.V(sb.toString()));               // root-транспорт, 40 с
activity.handler.postDelayed(new ua(progress, 0, activity), 45000L);
```

Наблюдения: нет `usejitprofiles`, нет отдельного watcher-а/задержки, рестарт — последняя команда.
`umount -l` точек `/data/app/` в наш код НЕ переносится (границы владельца).

## 3. Возврат сети — `CentralWatchdogService.onCreate` → `w0/e2.run`

```java
String s = prefs.getString("jit_restaurar", null);
Matcher/Pattern p = compile("^wifi=([01]),ap=([01])$");
if (s != null && p.matcher(s).matches()) {
    oe restauro = new oe(wifi, ap);                      // Restauro(wifi=…, ap=…)
    prefs.edit().remove("jit_restaurar").apply();        // снимается ДО выполнения
    handler.postDelayed(new e2(restauro, service, 0), 15000L);
}
// e2 case 0: if (CentralWatchdogService.T2) new Thread(new e2(oe, service, 1)).start();
// e2 case 1:
if (ap)   service.U("pós-JIT", false);                   // garantirApAberto: AP ПЕРВЫМ
if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true); // затем Wi-Fi
log("JIT: restaurado após o restart (wifi=" + wifi + " ap=" + ap + ")");
```

Состояние: `c1()` = `isWifiEnabled() || getWifiState()==2`; `v()` = `ap0` + адреса.
AP — `d(String)`: рефлексия `startSoftAp(WifiConfiguration)`.

## 4. Проверка результата — `w0/bb.run` → `w0/r7.run`

```java
String out = activity.H1(8000, "getprop dalvik.vm.usejit");
boolean getprop = out.trim().equals("true");
Boolean maps = /* есть ли dalvik-jit-code-cache в /proc/self/maps; ошибка -> null */;
// r7:
toggle.setActive(getprop);
if (prefs.contains("jit_pedido")) {
    boolean pedido = prefs.getBoolean("jit_pedido", false);
    boolean ok = pedido == getprop && (!pedido || maps != Boolean.FALSE);
    log("JIT pedido=" + pedido + " getprop=" + getprop + " code-cache-executavel=" + maps);
    prefs.edit().remove("jit_pedido").apply();
    if (!ok && pedido) toast(master_perf_jit_not_applied);
}
```

**Рестарт zygote в референсе не подтверждается** (ни pid, ни файл) — у нас подтверждение добавлено
и помечено как наша часть.

## 5. AVAS

- `z()`: `if (!prefs.getBoolean("avas_muted_saved", false)) return;` → `getAVASMode()` (рефлексия),
  при `mode != 0` → `setAVASMode(0)`. Это возврат **муты**, не активного режима.
- `A()`: `int saved = prefs.getInt("avas_sound_type_saved", -1); if (saved <= 0) return;` →
  по областям `w0/u.c = {0,1}` читает `g1.c(cpm, w0/u.b, area)` и при расхождении пишет
  `setIntProperty(u.b, area, saved)` (метод **void**), лог «AVAS tipo de som restaurado … após boot».
- `w0/u.b = Politica.h(7)` = `0x21407057` (native id из `libexpolitica.so`), проверка —
  `scripts/derive_avas_sound_prop.py`.
- График `5 × 30 с` в коде относится к AEB observer, не к AVAS: «точным AVAS-графиком референса»
  наши окна не являются.

## 6. Чего в референсе нет

- удаления правил `ip rule` (только `grep -c 'oif wlan0'`);
- подтверждения рестарта фреймворка;
- привязки снимка к операции/загрузке ГУ (только строка `jit_restaurar`, снимается до выполнения);
- откатa `dalvik.vm.usejit` при неудаче.
