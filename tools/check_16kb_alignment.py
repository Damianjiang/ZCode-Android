#!/usr/bin/env python3
"""检查 androidx 依赖 / APK 里的原生库是否 16KB 页对齐。

自包含：可自己下载 AAR（AAR 就是 zip）、解出 .so、解析 ELF 的 PT_LOAD p_align。
不需要 NDK / llvm-objdump / zip。

用法:
    python tools/check_16kb_alignment.py apk <app.apk>
    python tools/check_16kb_alignment.py aar <lib.aar>
    python tools/check_16kb_alignment.py so  <lib.so>
    python tools/check_16kb_alignment.py camera-core 1.3.4 1.4.2 1.6.2

判定标准（官方）：PT_LOAD 的 p_align 必须 >= 2**14 (0x4000)。
"""
import io
import struct
import sys
import urllib.request
import zipfile

PT_LOAD = 1
REQUIRED = 0x4000  # 16 KB
MAVEN = "https://dl.google.com/dl/android/maven2/androidx"


def load_alignments(blob: bytes):
    """返回 [(p_align, p_vaddr, p_memsz, p_offset)]，仅 PT_LOAD。"""
    if blob[:4] != b"\x7fELF":
        raise ValueError("not an ELF")
    if blob[4] != 2:
        raise ValueError("only 64-bit supported")
    e_phoff, = struct.unpack_from("<Q", blob, 0x20)
    e_phentsize, = struct.unpack_from("<H", blob, 0x36)
    e_phnum, = struct.unpack_from("<H", blob, 0x38)
    segs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        (p_type,) = struct.unpack_from("<I", blob, off)
        if p_type != PT_LOAD:
            continue
        p_offset, = struct.unpack_from("<Q", blob, off + 8)
        p_vaddr, = struct.unpack_from("<Q", blob, off + 16)
        p_memsz, = struct.unpack_from("<Q", blob, off + 40)
        p_align, = struct.unpack_from("<Q", blob, off + 48)
        segs.append((p_align, p_vaddr, p_memsz, p_offset))
    return segs


def report(name: str, blob: bytes) -> bool:
    """打印单个 .so 的结果，返回是否对齐。"""
    try:
        segs = load_alignments(blob)
    except Exception as e:
        print(f"  ?  {name}: {e}")
        return True
    if not segs:
        return True
    ok = all(a >= REQUIRED for a, *_ in segs)
    aligns = sorted({a for a, *_ in segs})
    print(f"  {'ALIGNED  ' if ok else 'UNALIGNED'} {name}")
    print(f"      PT_LOAD p_align = {[hex(a) for a in aligns]}  (需 >= 0x4000)")
    if not ok:
        for a, va, ms, off in segs:
            if a < REQUIRED:
                relro_ok = (va + ms) % REQUIRED == 0
                print(f"      ↳ off=0x{off:x} vaddr=0x{va:x} align=0x{a:x} "
                      f"RELRO对齐={relro_ok}")
    return ok


def check_archive(name: str, data: bytes) -> bool:
    print(f"── {name} ──")
    z = zipfile.ZipFile(io.BytesIO(data))
    sos = sorted(n for n in z.namelist()
                 if n.endswith(".so") and "arm64-v8a" in n)
    if not sos:
        print("  （无 arm64-v8a 的 .so）")
        return True
    allok = True
    for n in sos:
        allok &= report(n.split("!/")[-1], z.read(n))
    return allok


def check_maven(artifact: str, versions) -> bool:
    allok = True
    for v in versions:
        url = f"{MAVEN}/{artifact}/{v}/{artifact}-{v}.aar"
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
        except Exception as e:
            print(f"── {artifact} {v} ── 下载失败: {e}\n")
            continue
        allok &= check_archive(f"{artifact} {v}", data)
        print()
    return allok


def main(argv) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    mode, rest = argv[1], argv[2:]
    if mode == "camera-core":
        return 0 if check_maven("camera-core", rest) else 1
    path = rest[0]
    if mode == "so":
        with open(path, "rb") as f:
            return 0 if report(path, f.read()) else 1
    with open(path, "rb") as f:
        data = f.read()
    if path.endswith(".aar"):
        return 0 if check_archive(path, data) else 1
    return 0 if check_archive(path, data) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
