# AVAS sound type: почему `0x21407057` — это id свойства (iter33p-fix2 §3)

**Что требовал REVIEW iter33p-fix1 §5:** «не делать вывод „id верный“ только потому, что с него
читается число»; представить воспроизводимое подтверждение соответствия нативных инструкций
смещениям и связи `Politica.h(7)` именно со sound type и областями.

Ниже — вся цепочка, каждое звено которой воспроизводится командой из репозитория.

## 0. Артефакты и контрольные суммы

| артефакт | md5 |
|---|---|
| ZIP-обёртка `CentralEXAuto_v3.3.9.zip` | `8a65c6c29749c64d4e335c57490a347a` |
| `CentralEXAuto_v3.3.9.apk` (внутри обёртки) | `c3f1664125953b9851bf4483b265135f` |
| `lib/arm64-v8a/libexpolitica.so` (внутри APK) | `316222cfc3e7080b81c4b426b7451a61` |

Скрипт сверяет их и печатает; подмена файла или разбор не того `.apk` видны сразу
(вложенный `assets/pip-overlay.apk` отвергается: в нём нет `libexpolitica.so`).

## 1. Нативная таблица собирается из ИНСТРУКЦИЙ, не из ручных offsets

`libexpolitica.so` → `Java_com_ex_auto_Politica_nativeCall` (`.dynsym`, размер 1712 Б).
Ветка `cmd == 5`:

```
0x0a10  adrp x9, #0                      ; страницы .rodata
0x0a14  movi v1.2s, #0x3d                ; buf[0] = 61 (количество записей)
0x0a1c  ldr  q0, [x9, #0x4e0]            ; 16 байт таблицы
...
0x0a34  stur q0, [x8, #0x14]             ; x8 = sp + 0x80 (кадр buf[248])
...
0x0ac4  movz/movk x9 -> 0x2140A0912140A655
0x0adc  stur x9, [x8, #0xe4]             ; два последних int вручную
...
0x0af4  movz w8, #0xa096 / movk -> 0x2140A096
0x0b00  str  w8, [sp, #0x174]            ; buf[0xf4]
0x0b04  blr  x9                          ; NewIntArray(0xf8) + SetIntArrayRegion
```

Скрипт `scripts/derive_avas_sound_prop.py` декодирует эти слова своим мини-дизассемблером
(`adrp/ldr q/stur q/stur x/movz/movk/movi/str`), отслеживает регистры и восстанавливает буфер.
Печатается каждая копия: `.rodata[0xNNN] → buf[0xNNN]` — то есть соответствие инструкций
смещениям проверяемо глазами, а не заявлено.

Ветки других команд (`cmd = 2,3,4,6,7`) пишут в тот же кадр, поэтому берётся строго окно
`cmd == 5` (от `movi #0x3d` до `blr` вызова `NewIntArray`).

## 2. Индекс → значение: `Politica.h(i) == buf[i+1]`

`Politica.smali` (наш разбор dex, файл в репозитории — `docs/agent/ref/Politica.smali`):

- `Politica.c(I)` строит массив: `new-array v4, v3` (размер = `buf[0]`), затем в цикле
  `mul-int/lit8 v5, v0, 0x4` → `add-int/lit8 v5, v5, 0x4` → `f(v5, buf)` → `aput v5, v4, v0`
  (строки 858–878) — то есть `arr[i] = f(4*i + 4, buf)`;
- `f(I[B)I` читает 4 байта little-endian начиная с `p0` (строки 368–406) — смещение `4*(i+1)`,
  значит `arr[i] = buf[i+1]`;
- `Politica.h(I)I` — обёртка над `c(I)` (строка 553).

Итог: `h(i) = buf[i + 1]`.

## 3. Смысл индекса 7: `w0/u.b` — sound type по областям {0,1}

`docs/agent/ref/w0/u.smali` (`<clinit>`):

```
:80   const/4 v0, 0x7
:82   invoke-static {v0}, Lcom/ex/auto/Politica;->h(I)I
:86   sput v0, Lw0/u;->b:I                 ; u.b = h(7)
:92   filled-new-array {v0, v1}, [I       ; v0=0, v1=1
:96   sput-object v2, Lw0/u;->c:[I         ; u.c = {0, 1}
```

`docs/agent/ref/w0/t2.smali` (`run()`, ветка записи):

```
:73   sget-object v7, Lw0/u;->c:[I          ; перебор областей
:82   sget v10, Lw0/u;->b:I                 ; id свойства
:84   invoke-static {cpm, v10, area, value}, Lw0/g1;->l(...)Z
:97   const-string "AVAS sound type write area="   ; строка лога референса
:133  const-string "avas_sound_type_saved"          ; ключ префа
```

`docs/agent/ref/w0/g1.smali:l(CarPropertyManager;III)Z` → `docs/agent/ref/w0/d1.smali:49`:
`invoke-virtual {v2, v3, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->setIntProperty(III)V`
— запись свойства по `(propId, area, value)`, возврат **void**.
Чтение — `docs/agent/ref/w0/z0.smali:95`: `getIntProperty(II)I`.

То есть `u.b` — это именно id свойства, которым референс пишет/читает **sound type AVAS**
по областям `u.c = {0, 1}`, и он равен `Politica.h(7)`.

## 4. Контроли методики (два полевых id нашего кода подряд)

| индекс | значение | что это |
|---|---|---|
| `h(0x15)` | `0x2140A377` | `PROP_AC_AMBIENT_TEMP` (`StatusIcons.kt`, полевая проверка на ГУ) |
| `h(0x16)` | `0x2140A379` | `PROP_AC_INSIDE_TEMP` (`TempProbeTable.kt`, csv:987) |
| `h(7)` | **`0x21407057`** | id свойства AVAS sound type |

Две независимо подтверждённые величины на соседних индексах — проверка того, что таблица собрана
и индексирована верно (прежняя ошибочная привязка `h(0x14) = 0x21408006` → «AC_AMBIENT_TEMP»
этой проверкой отвергнута).

## 5. Как воспроизвести

```bash
# 1) обёртка или сам APK — формат определяется по содержимому
python3 scripts/derive_avas_sound_prop.py /path/CentralEXAuto_v3.3.9.zip

# 2) с проверкой цепочки по smali (файлы уже в репозитории)
python3 scripts/derive_avas_sound_prop.py /path/CentralEXAuto_v3.3.9.apk --smali-dir docs/agent/ref
```

Ожидаемый хвост вывода:

```
ИТОГ: h(7) = 0x21407057 (557871191) — id свойства AVAS sound type, области {0, 1}
САМОПРОВЕРКА: OK
```

## 6. Чего это доказательство НЕ даёт

1. Оно не проверяет поведение на конкретной прошивке: если `getIntProperty(0x21407057, area)`
   не отвечает, приложение **не пишет** sound type и честно логирует
   `AVAS_SAVE … reason=sound_not_captured` / `result=unverified`.
2. Оно не утверждает, что «звук точно вернётся»: успех фиксируется только по read-back.
3. Оно ничего не говорит о значениях (какой режим «правильный») — сохраняются и возвращаются
   только те, что были прочитаны до рестарта.
