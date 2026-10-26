// Symbol relocations of one ELF64 object: plain RELA tables (DT_JMPREL, DT_RELA) and Android's
// packed table (DT_ANDROID_RELA, "APS2"). Shared by oulg_sf.c, which walks them in memory, and
// tools/got_check.c, which walks a binary pulled from the device. Address-taken imports (Skia's
// GL interface takes core GL functions by address: GLOB_DAT) are often only in the packed table.
#ifndef OULG_RELOCS_H
#define OULG_RELOCS_H

#include <elf.h>
#include <stddef.h>
#include <stdint.h>

#ifndef DT_ANDROID_RELA
#define DT_ANDROID_RELA 0x60000011
#endif
#ifndef DT_ANDROID_RELASZ
#define DT_ANDROID_RELASZ 0x60000012
#endif

typedef void (*oulg_reloc_fn)(uint64_t r_offset, uint64_t r_info, void *ctx);

static inline void oulg_rela_each(const Elf64_Rela *r, size_t size, oulg_reloc_fn fn, void *ctx) {
    for (size_t k = 0; r && k < size / sizeof *r; k++) fn(r[k].r_offset, r[k].r_info, ctx);
}

struct oulg_sleb { const uint8_t *p, *end; int bad; };
static inline int64_t oulg_sleb_next(struct oulg_sleb *d) {
    int64_t value = 0;
    unsigned shift = 0;
    uint8_t byte;
    do {
        if (d->p >= d->end || shift >= 64) { d->bad = 1; return 0; }
        byte = *d->p++;
        value |= (int64_t) (byte & 0x7f) << shift;
        shift += 7;
    } while (byte & 0x80);
    if (shift < 64 && (byte & 0x40)) value |= -((int64_t) 1 << shift);
    return value;
}

/**
 * Bionic's packed relocation format (linker/linker_reloc_iterators.h): "APS2", then SLEB128 values:
 * the count and the first offset, then groups of (size, flags, [offset delta], [info], [addend
 * delta]) followed by their members. Returns the number of relocations, or -1 if the table is
 * malformed (nothing is reported past that point).
 */
static inline long oulg_aps2_each(const uint8_t *table, size_t size, oulg_reloc_fn fn, void *ctx) {
    enum { BY_INFO = 1, BY_OFFSET_DELTA = 2, BY_ADDEND = 4, HAS_ADDEND = 8 };
    if (!table || size < 4 || table[0] != 'A' || table[1] != 'P' || table[2] != 'S' || table[3] != '2') return -1;
    struct oulg_sleb d = { table + 4, table + size, 0 };
    int64_t count = oulg_sleb_next(&d);
    uint64_t offset = (uint64_t) oulg_sleb_next(&d), info = 0;
    if (d.bad || count < 0) return -1;
    for (int64_t done = 0; done < count;) {
        int64_t group = oulg_sleb_next(&d), flags = oulg_sleb_next(&d), delta = 0;
        if (d.bad || group <= 0) return -1;
        if (flags & BY_OFFSET_DELTA) delta = oulg_sleb_next(&d);
        if (flags & BY_INFO) info = (uint64_t) oulg_sleb_next(&d);
        if ((flags & HAS_ADDEND) && (flags & BY_ADDEND)) oulg_sleb_next(&d);
        for (int64_t i = 0; i < group && done < count; i++, done++) {
            offset += (flags & BY_OFFSET_DELTA) ? (uint64_t) delta : (uint64_t) oulg_sleb_next(&d);
            if (!(flags & BY_INFO)) info = (uint64_t) oulg_sleb_next(&d);
            if ((flags & HAS_ADDEND) && !(flags & BY_ADDEND)) oulg_sleb_next(&d);
            if (d.bad) return -1;
            fn(offset, info, ctx);
        }
    }
    return (long) count;
}

#endif
