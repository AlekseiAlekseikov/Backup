#!/usr/bin/env python3
"""iter33p-fix1 §3 — воспроизводимое доказательство id свойства AVAS sound type.

Зачем: в отчёте iter33p id свойства sound type был «не установлен», и реализация либо угадывала
его (префы/поиск по имени), либо пропускала запись. REVIEW потребовал доказать источник.
Числа свойств в CentralEXAuto 3.3.9 приходят НЕ из dex: `com.ex.auto.Politica.h(i)` →
`Politica.c(i)` → `nativeCall(5, пусто)` → нативная таблица в `lib/arm64-v8a/libexpolitica.so`.
Этот скрипт извлекает таблицу из .so и печатает её вместе с проверкой методики.

Как это работает (по дизассемблеру `Java_com_ex_auto_Politica_nativeCall`, cmd=5):

  * функция собирает буфер `int[248]` в стеке (sp+0x80) из 16-байтовых векторов .rodata
    (`ldr q*, ... / stur q*, [x8, #off]`), дописывает три литеральных значения
    (`mov/movk`: 0x2140a655/0x2140a091/0x21409005/0x2140a300/0x2140a096);
  * `buf[0] = 0x3d = 61` — количество записей; `NewIntArray(0xf8=248)`,
    `SetIntArrayRegion(0, 248, buf)`;
  * `Politica.c(i)`: `n = buf[0]` → `new int[n]`, затем `arr[i] = buf[i + 1]`
    (`f(i*4+4, buf)` для i < n). Значит `Politica.h(i) == arr[i] == buf[i + 1]`.

Проверка методики (две НЕЗАВИСИМО проверенные на машине величины из нашего кода):
  * `Politica.h(0x15)` = buf[22] = 0x2140A377 — полевой `PROP_AC_AMBIENT_TEMP` (`StatusIcons.kt`);
  * `Politica.h(0x16)` = buf[23] = 0x2140A379 — полевой `PROP_AC_INSIDE_TEMP`
    (`StatusIcons.kt`/`TempProbeTable.kt`, csv:987).
  Два известных id подряд на соседних индексах — совпадением быть не может; в `w0/sg.smali`
  `h(0x15)` действительно читается как температура (`f(prop, 0)` → Integer → Float).

Вывод: `w0/u.b = Politica.h(7) = buf[8]` — id свойства sound type (в `w0/qa` пишется/читается
по областям `w0/u.c = {0, 1}`; восстановление — `restoreSavedAvasSoundType area=`).

Запуск:  python3 scripts/derive_avas_sound_prop.py <path-to-CentralEXAuto_v3.3.9.apk>
"""
import struct
import sys
import zipfile

SO_PATH = "lib/arm64-v8a/libexpolitica.so"
COUNT = 61            # buf[0]
AVAS_INDEX = 7         # Politica.h(7) == w0/u.b (AVAS sound type)
AMBIENT_INDEX = 0x15   # Politica.h(0x15) == AC_AMBIENT_TEMP   (контроль 1, полевой id)
INSIDE_INDEX = 0x16    # Politica.h(0x16) == AC_INSIDE_TEMP    (контроль 2, полевой id)


def build_table(so: bytes) -> list:
    """.rodata == файловое смещение (addr == offset для этого .so), собираем buf[248]."""
    buf = bytearray(248 * 4)

    def put(off: int, data: bytes) -> None:
        buf[off:off + len(data)] = data

    # пары «адрес в .rodata → смещение в буфере» из дизассемблера (все 16-байтовые)
    pairs = [
        (0x04, 0x590), (0x14, 0x4e0), (0x24, 0x530), (0x34, 0x4b0), (0x44, 0x4c0),
        (0x54, 0x520), (0x64, 0x570), (0x74, 0x500), (0x84, 0x580), (0x94, 0x510),
        (0xa4, 0x4d0), (0xb4, 0x4f0), (0xc4, 0x550), (0xd4, 0x540),
    ]
    for dst, src in pairs:
        put(dst, so[src:src + 16])
    put(0xe4, struct.pack("<II", 0x2140A655, 0x2140A091))
    put(0xec, struct.pack("<II", 0x21409005, 0x2140A300))
    put(0xf4, struct.pack("<I", 0x2140A096))
    put(0x00, struct.pack("<I", COUNT))
    return [struct.unpack_from("<I", buf, i * 4)[0] for i in range(COUNT + 1)]


def politica_h(table: list, i: int) -> int:
    """Politica.h(i): arr[i] == buf[i + 1] (см. docstring)."""
    return table[i + 1]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    with zipfile.ZipFile(sys.argv[1]) as apk:
        inner = [n for n in apk.namelist() if n.endswith(".apk")]
        if inner:  # иногда референс упакован в zip-обёртку
            import io
            with zipfile.ZipFile(io.BytesIO(apk.read(inner[0]))) as inner_apk:
                so = inner_apk.read(SO_PATH)
        else:
            so = apk.read(SO_PATH)
    table = build_table(so)
    assert table[0] == COUNT, f"элемент 0 должен быть количеством записей: {table[0]}"
    print(f"таблица: {len(table) - 1} значений (buf[0]={table[0]})")
    for i in range(0, len(table) - 1):
        mark = ""
        if i == AVAS_INDEX:
            mark = "  <== AVAS sound type (w0/u.b)"
        if i == AMBIENT_INDEX:
            mark = "  <== AC_AMBIENT_TEMP (контроль 1)"
        if i == INSIDE_INDEX:
            mark = "  <== AC_INSIDE_TEMP (контроль 2)"
        print(f"  h({i:2d} = 0x{i:02x}) = 0x{politica_h(table, i):08X} = {politica_h(table, i)}{mark}")
    ambient = politica_h(table, AMBIENT_INDEX)
    inside = politica_h(table, INSIDE_INDEX)
    sound = politica_h(table, AVAS_INDEX)
    print()
    print(f"контроль 1: h(0x15) = 0x{ambient:08X} — ожидается AC_AMBIENT_TEMP 0x2140A377")
    print(f"контроль 2: h(0x16) = 0x{inside:08X} — ожидается AC_INSIDE_TEMP  0x2140A379")
    print(f"ИТОГ: id свойства AVAS sound type = 0x{sound:08X} ({sound}), области {{0, 1}}")
    ok = ambient == 0x2140A377 and inside == 0x2140A379 and sound == 0x21407057
    print("САМОПРОВЕРКА:", "OK" if ok else "FAILED — таблица разобрана неверно")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
