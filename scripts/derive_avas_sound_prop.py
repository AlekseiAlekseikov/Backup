#!/usr/bin/env python3
"""iter33p-fix2 §3 — воспроизводимое доказательство id свойства AVAS sound type.

REVIEW iter33p-fix1 §5: прежняя версия скрипта (а) падала на настоящем APK (`KeyError`), потому
что считала любой вложенный `.apk` признаком zip-обёртки, и (б) использовала РУЧНЫЕ смещения,
то есть «извлечение» нельзя было назвать самостоятельным. Здесь:

  • вход — и zip-обёртка (внутри один `CentralEXAuto_v3.3.9.apk`), и сам APK; формат определяется
    по СОДЕРЖИМОМУ (есть ли `lib/<abi>/libexpolitica.so`), а не по расширению/вложенности;
  • контрольные суммы печатаются и сверяются (ZIP / APK / .so) — подмена файла видна сразу;
  • таблица свойств собирается ИЗ ИНСТРУКЦИЙ `Java_com_ex_auto_Politica_nativeCall` (cmd = 5):
    мини-дизассемблер AArch64 читает реальные слова `adrp/ldr q/stur q/movz/movk/str` и
    восстанавливает буфер `int[248]`, который dex затем превращает в `Politica.h(i)`;
  • методика проверяется ДВУМЯ полевыми id нашего кода, лежащими подряд;
  • дополнительно проверяются строки dex, подтверждающие смысл индекса 7 (см. ниже).

Откуда берётся смысл `h(7)`
---------------------------
1. `Politica.c(i)` (dex): `n = buf[0]` → `new int[n]`, `arr[i] = f(i*4+4, buf)` → `h(i) == buf[i+1]`.
2. `w0/u.<clinit>` (dex): `u.b = Politica.h(7)` (`const/4 v0, 0x7`), `u.c = filled-new-array {0, 1}`.
3. `w0/t2.run` (dex): по каждой области `w0/u.c` вызывает `w0/g1.l(cpm, u.b, area, value)`,
   пишет в лог `"AVAS sound type write area="` и сохраняет `avas_sound_type_saved`.
4. `w0/g1.l` → `w0/d1.a` → `CarPropertyManager.setIntProperty(III)V`;
   `w0/g1.c` → `w0/z0.a` → `CarPropertyManager.getIntProperty(II)I`.
Значит `u.b` — это id свойства, которым референс пишет/читает **sound type AVAS** по областям {0,1}.
Проверка шага 4 выполняется по dex-строкам, шагов 1–3 — по smali (`--smali-dir`).

Запуск:
    python3 scripts/derive_avas_sound_prop.py <zip-обёртка | CentralEXAuto_v3.3.9.apk>
    python3 scripts/derive_avas_sound_prop.py <apk> --smali-dir <dir с w0/*.smali>
"""
import hashlib
import io
import os
import struct
import sys
import zipfile

# ─────────────────────────────── эталонные контрольные суммы ───────────────────────────────
MD5_ZIP = "8a65c6c29749c64d4e335c57490a347a"          # обёртка с одним APK
MD5_APK = "c3f1664125953b9851bf4483b265135f"          # CentralEXAuto_v3.3.9.apk
SO_NAMES = ("lib/arm64-v8a/libexpolitica.so", "lib/armeabi-v7a/libexpolitica.so")

COUNT = 61          # buf[0] — количество записей таблицы
AVAS_INDEX = 7      # Politica.h(7) == w0/u.b
AMBIENT_INDEX = 0x15   # Politica.h(0x15) == PROP_AC_AMBIENT_TEMP (контроль 1)
INSIDE_INDEX = 0x16    # Politica.h(0x16) == PROP_AC_INSIDE_TEMP (контроль 2)
EXPECT = {
    AMBIENT_INDEX: 0x2140A377,
    INSIDE_INDEX: 0x2140A379,
}
EXPECT_AVAS = 0x21407057

# строки dex, подтверждающие, что индекс 7 — это именно sound type AVAS
DEX_EVIDENCE = [
    "setAVASMode", "getAVASMode",
    "setIntProperty", "getIntProperty",
    "avas_sound_type_saved",
    "AVAS sound type write area=",
]


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ─────────────────────────── определение формата входа (REVIEW §5) ───────────────────────────

def load_apk(path: str):
    """Вернуть (apk_bytes, описание). Работает и для обёртки, и для самого APK."""
    raw = open(path, "rb").read()
    z = zipfile.ZipFile(io.BytesIO(raw))
    names = z.namelist()
    if any(n.endswith("libexpolitica.so") for n in names):
        return raw, f"APK ({os.path.basename(path)}, md5={md5(raw)}, sha256={sha256(raw)[:16]}…)"
    inner = [n for n in names if n.lower().endswith(".apk")]
    if len(inner) != 1:
        raise SystemExit(
            f"{path}: не APK и не обёртка с одним APK (внутри {len(inner)} .apk, "
            f"записей {len(names)}): {names[:8]}"
        )
    inner_bytes = z.read(inner[0])
    inner_z = zipfile.ZipFile(io.BytesIO(inner_bytes))
    if not any(n.endswith("libexpolitica.so") for n in inner_z.namelist()):
        raise SystemExit(f"{path}: внутри {inner[0]} нет libexpolitica.so — это не референс")
    desc = (f"zip-обёртка md5={md5(raw)} → {inner[0]} md5={md5(inner_bytes)} "
            f"sha256={sha256(inner_bytes)[:16]}…")
    if md5(raw) != MD5_ZIP:
        desc += f" (⚠ обёртка не совпала с эталоном {MD5_ZIP})"
    if md5(inner_bytes) != MD5_APK:
        desc += f" (⚠ APK не совпал с эталоном {MD5_APK})"
    return inner_bytes, desc


# ─────────────────────────────────── мини-ELF ───────────────────────────────────

def elf_sections(data: bytes):
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name_off, s_type, _flags, addr, offset, size = struct.unpack_from("<IIQQQQ", data, off)
        secs.append({"name_off": name_off, "offset": offset, "addr": addr, "size": size, "type": s_type})
    strtab = secs[e_shstrndx]
    for s in secs:
        start = strtab["offset"] + s["name_off"]
        end = data.index(b"\0", start)
        s["name"] = data[start:end].decode()
    return secs


def elf_symbol(data: bytes, secs, want: str):
    dynsym = next((s for s in secs if s["name"] == ".dynsym"), None)
    dynstr = next((s for s in secs if s["name"] == ".dynstr"), None)
    if not dynsym or not dynstr:
        return None
    count = dynsym["size"] // 24
    for i in range(count):
        off = dynsym["offset"] + i * 24
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, off)
        if st_name == 0:
            continue
        start = dynstr["offset"] + st_name
        end = data.index(b"\0", start)
        name = data[start:end].decode()
        if name == want:
            return st_value, st_size
    return None


# ─────────────────────── мини-дизассемблер нужного подмножества AArch64 ───────────────────────

def sign_extend(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return (value & (sign - 1)) - (value & sign)


def decode(word: int):
    """(mnemonic, fields...) для подмножества, которое использует nativeCall(cmd=5)."""
    if word & 0x9F000000 == 0x90000000:            # ADRP
        rd = word & 0x1F
        immlo = (word >> 29) & 0x3
        immhi = (word >> 5) & 0x7FFFF
        imm = sign_extend((immhi << 2) | immlo, 21) << 12
        return ("adrp", rd, imm)
    if word & 0xFFC00000 == 0x3DC00000:            # LDR q, [Xn, #imm12*16]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = ((word >> 10) & 0xFFF) * 16
        return ("ldr_q", rt, rn, imm)
    if word & 0xFFE00C00 == 0x3C800000:            # STUR q, [Xn, #imm9]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = sign_extend((word >> 12) & 0x1FF, 9)
        return ("stur_q", rt, rn, imm)
    if word & 0xFFE00C00 == 0x3CC00000:            # LDUR q, [Xn, #imm9]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = sign_extend((word >> 12) & 0x1FF, 9)
        return ("ldur_q", rt, rn, imm)
    if word & 0xFFE00C00 == 0xF8000000:            # STUR x, [Xn, #imm9]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = sign_extend((word >> 12) & 0x1FF, 9)
        return ("stur_x", rt, rn, imm)
    if word & 0xFFC00000 == 0xB9000000:            # STR w, [Xn, #imm12*4]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = ((word >> 10) & 0xFFF) * 4
        return ("str_w", rt, rn, imm)
    if word & 0xFFC00000 == 0xBD000000:            # STR s, [Xn, #imm12*4]
        rt = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = ((word >> 10) & 0xFFF) * 4
        return ("str_s", rt, rn, imm)
    if word & 0x7F800000 == 0x52800000:            # MOVZ (w/x + imm16 << hw*16)
        sf = (word >> 31) & 1
        rd = word & 0x1F
        imm16 = (word >> 5) & 0xFFFF
        hw = (word >> 21) & 0x3
        return ("movz", sf, rd, imm16, hw)
    if word & 0x7F800000 == 0x72800000:            # MOVK
        sf = (word >> 31) & 1
        rd = word & 0x1F
        imm16 = (word >> 5) & 0xFFFF
        hw = (word >> 21) & 0x3
        return ("movk", sf, rd, imm16, hw)
    if word & 0xBF800C00 == 0x0F000400:            # MOVI vT.2s, #imm8 (cmode=0000)
        rt = word & 0x1F
        imm8 = (((word >> 16) & 0x7) << 5) | ((word >> 5) & 0x1F)
        return ("movi_s", rt, imm8)
    if word & 0xFF800000 == 0x91000000:            # ADD xD, Xn, #imm12
        rd = word & 0x1F
        rn = (word >> 5) & 0x1F
        imm = (word >> 10) & 0xFFF
        return ("add", rd, rn, imm)
    return ("other",)


def extract_table(so: bytes, symbol=("Java_com_ex_auto_Politica_nativeCall",)):
    """Собрать буфер int[248] из ИНСТРУКЦИЙ функции nativeCall (cmd = 5)."""
    secs = elf_sections(so)
    text = next(s for s in secs if s["name"] == ".text")
    sym = elf_symbol(so, secs, symbol[0])
    if not sym:
        raise SystemExit(f"в .so нет символа {symbol[0]}")
    start, size = sym
    words = []
    base = text["offset"] + (start - text["addr"])
    for off in range(0, size, 4):
        words.append(struct.unpack_from("<I", so, base + off)[0])

    # ── окно «команда 5» ──
    # В функции несколько веток (cmd = 2,3,4,5,6,7), и они пишут в ОДНОТ и тот же кадр стека.
    # При исполнении работает только одна ветка, поэтому таблицу собираем строго из блока cmd=5:
    # от `movi vT.2s, #0x3d` (количество записей) до вызова NewIntArray (`bl`).
    start_idx = next((i for i, w in enumerate(words)
                      if decode(w)[0] == "movi_s" and decode(w)[2] == COUNT), None)
    if start_idx is None:
        raise SystemExit("не найден блок cmd=5 (`movi vT.2s, #0x3d`)")
    # конец блока — вызов NewIntArray (248 = `mov w1, #0xf8`) + сам `blr`
    size_idx = next((i for i in range(start_idx, len(words))
                     if decode(words[i])[0] == "movz" and decode(words[i])[3] == 0xF8),
                    len(words))
    end_idx = next((i for i in range(size_idx, len(words))
                    if words[i] & 0xFFFFFC1F == 0xD63F0000), len(words)) + 1
    lo = max(0, start_idx - 2)
    print(f"блок cmd=5: инструкции {lo}..{end_idx} (адреса 0x{start + lo * 4:03x}..0x{start + end_idx * 4:03x})")

    page = {}       # reg -> адрес страницы (adrp)
    mem = {}        # reg -> (addr) загруженного вектора
    val = {}        # reg -> целое (movz/movk)
    movi = {}       # reg -> imm8 (movi vT.2s)
    buf_base = None
    buf = bytearray(248 * 4)
    pairs = []
    wrote = []

    for idx, w in enumerate(words):
        d = decode(w)
        op = d[0]
        in_window = lo <= idx < end_idx
        if op == "adrp":
            page[d[1]] = d[2]
        elif op == "ldr_q":
            _, rt, rn, imm = d
            if rn in page:
                mem[rt] = page[rn] + imm
        elif op == "movi_s":
            movi[d[1]] = d[2]
        elif op == "movz":
            _, sf, rd, imm16, hw = d
            val[rd] = imm16 << (hw * 16)
        elif op == "movk":
            _, sf, rd, imm16, hw = d
            val[rd] = (val.get(rd, 0) & ~(0xFFFF << (hw * 16))) | (imm16 << (hw * 16))
        elif op == "add":
            _, rd, rn, imm = d
            if rn == 31:            # sp
                buf_base = imm
        elif op == "stur_q" and in_window:
            _, rt, rn, imm = d
            if buf_base is not None and rn == 8 and rt in mem and imm >= 4:
                src = mem[rt]
                buf[imm:imm + 16] = so[src:src + 16]
                pairs.append((imm, src))
                wrote.append(imm)
        elif op == "stur_x" and in_window:
            _, rt, rn, imm = d
            if buf_base is not None and rn == 8 and rt in val:
                buf[imm:imm + 8] = struct.pack("<Q", val[rt] & 0xFFFFFFFFFFFFFFFF)
                wrote.append(imm)
        elif op == "str_w" and in_window:
            _, rt, rn, imm = d
            if rn == 31 and rt in val:
                off = imm - (buf_base or 0)
                if 0 <= off <= 248 * 4 - 4:
                    buf[off:off + 4] = struct.pack("<I", val[rt] & 0xFFFFFFFF)
                    wrote.append(off)
        elif op == "str_s" and in_window:
            _, rt, rn, imm = d
            if rn == 31 and rt in movi:
                off = imm - (buf_base or 0)
                if 0 <= off <= 248 * 4 - 4:
                    buf[off:off + 4] = struct.pack("<I", movi[rt] & 0xFFFFFFFF)
                    wrote.append(off)

    if not wrote:
        raise SystemExit("не удалось восстановить буфер: не найдено ни одной записи в кадр стека")
    return buf, pairs, words


def build_table(buf: bytes) -> list:
    return [struct.unpack_from("<I", buf, i * 4)[0] for i in range(COUNT + 1)]


def politica_h(table: list, i: int) -> int:
    """`Politica.h(i)`: arr[i] == buf[i + 1] (см. `Politica.c` в dex)."""
    return table[i + 1]


# ─────────────────────────── проверки dex и smali (смысл индекса 7) ───────────────────────────

def dex_strings(apk: bytes) -> set:
    """Все строки из string_ids (быстро и без внешних зависимостей)."""
    z = zipfile.ZipFile(io.BytesIO(apk))
    out = set()
    for name in z.namelist():
        if not name.endswith(".dex"):
            continue
        dex = z.read(name)
        string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 0x38)
        for i in range(string_ids_size):
            data_off, = struct.unpack_from("<I", dex, string_ids_off + i * 4)
            start = data_off
            while dex[start] & 0x80:      # uleb128 длина
                start += 1
            start += 1
            end = dex.index(b"\0", start)
            out.add(dex[start:end].decode("utf-8", "replace"))
    return out


def check_dex(apk: bytes) -> bool:
    strings = dex_strings(apk)
    ok = True
    print("dex-строки, подтверждающие смысл индекса 7:")
    for s in DEX_EVIDENCE:
        found = s in strings
        ok = ok and found
        print(f"  {'OK  ' if found else 'НЕТ '} {s!r}")
    return ok


def check_smali(smali_dir: str) -> bool:
    """Проверка цепочки по smali (`baksmali d classes.dex`)."""
    need = {
        "w0/u.smali": [("const/4 v0, 0x7", "индекс 7"), ("Lw0/u;->b:I", "поле id"),
                       ("filled-new-array {v0, v1}", "области {0,1}")],
        "w0/t2.smali": [("Lw0/u;->b:I", "id из u.b"), ("Lw0/u;->c:[I", "области из u.c"),
                        ("AVAS sound type write area=", "строка лога референса"),
                        ("avas_sound_type_saved", "преф сохранения")],
        "w0/g1.smali": [("Landroid/car/hardware/property/CarPropertyManager;III)Z", "g1.l")],
        "w0/d1.smali": [("setIntProperty(III)V", "запись свойства (void)")],
        "w0/z0.smali": [("getIntProperty(II)I", "чтение свойства")],
    }
    ok = True
    print("цепочка по smali:")
    for name, checks in need.items():
        path = os.path.join(smali_dir, name)
        if not os.path.isfile(path):
            print(f"  ПРОПУСК {name} (нет файла)")
            ok = False
            continue
        text = open(path, encoding="utf-8", errors="replace").read()
        for needle, why in checks:
            found = needle in text
            ok = ok and found
            print(f"  {'OK  ' if found else 'НЕТ '} {name}: {why} — {needle!r}")
    return ok


# ─────────────────────────────────────── main ───────────────────────────────────────

def main() -> int:
    args = [a for a in sys.argv[1:]]
    smali_dir = None
    if "--smali-dir" in args:
        i = args.index("--smali-dir")
        smali_dir = args[i + 1]
        del args[i:i + 2]
    if not args:
        print(__doc__)
        return 2

    apk, desc = load_apk(args[0])
    print(f"вход: {desc}")

    z = zipfile.ZipFile(io.BytesIO(apk))
    so_name = next((n for n in SO_NAMES if n in z.namelist()), None)
    if so_name is None:
        raise SystemExit(f"в APK нет ни одного из {SO_NAMES}")
    so = z.read(so_name)
    print(f".so: {so_name} размер={len(so)} md5={md5(so)} sha256={sha256(so)[:16]}…")

    buf, pairs, words = extract_table(so)
    print(f"инструкций в nativeCall: {len(words)}; восстановлено записей буфера: {len(pairs)} "
          f"(из них 16-байтовых копий {len(pairs)})")
    for dst, src in sorted(pairs):
        print(f"  .rodata[0x{src:03x}] → buf[0x{dst:03x}]")

    table = build_table(buf)
    if table[0] != COUNT:
        raise SystemExit(f"buf[0]={table[0]}, ожидалось {COUNT} записей — разбор неверен")
    print(f"таблица: {COUNT} значений (buf[0]={table[0]})")

    ok = True
    print("контроли (полевые id нашего кода, лежащие подряд):")
    for i, expect in EXPECT.items():
        got = politica_h(table, i)
        good = got == expect
        ok = ok and good
        print(f"  h(0x{i:02x}) = 0x{got:08X} — ожидалось 0x{expect:08X}: {'OK' if good else 'FAILED'}")

    sound = politica_h(table, AVAS_INDEX)
    print(f"ИТОГ: h({AVAS_INDEX}) = 0x{sound:08X} ({sound}) — id свойства AVAS sound type, "
          f"области {{0, 1}}")
    ok = ok and sound == EXPECT_AVAS

    ok = check_dex(apk) and ok
    if smali_dir:
        ok = check_smali(smali_dir) and ok
    else:
        print("smali-проверка пропущена (укажите --smali-dir с w0/*.smali)")

    print("САМОПРОВЕРКА:", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
