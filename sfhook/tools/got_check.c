// Host check of the GOT hook's relocation walk (relocs.h) on an ELF pulled from the device, e.g.
// /system/bin/surfaceflinger: every relocation naming glShaderSource or eglGetProcAddress, the table
// it is in and its type, and the number of relocations per table. What liboulg_sf.so would patch,
// before anything is injected. Usage: got_check ELF [symbol...]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../relocs.h"

static unsigned char *img;
static size_t img_size;
static Elf64_Ehdr *eh;
static const Elf64_Sym *symtab;
static const char *strtab;
static const char **names;
static int nnames, table_count[3], hits;
static const char *table_name;

/** File offset of a link-time address, through the PT_LOAD segments; or NULL. */
static const void *at(uint64_t vaddr) {
    const Elf64_Phdr *ph = (const Elf64_Phdr *) (img + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++)
        if (ph[i].p_type == PT_LOAD && vaddr >= ph[i].p_vaddr && vaddr < ph[i].p_vaddr + ph[i].p_filesz)
            return img + ph[i].p_offset + (vaddr - ph[i].p_vaddr);
    return NULL;
}
static const char *type_name(uint32_t t) {
    switch (t) {
        case R_AARCH64_JUMP_SLOT: return "JUMP_SLOT";
        case R_AARCH64_GLOB_DAT: return "GLOB_DAT";
        case R_AARCH64_ABS64: return "ABS64";
        case R_AARCH64_RELATIVE: return "RELATIVE";
        default: return "other";
    }
}
static int current;
static void seen(uint64_t off, uint64_t info, void *ctx) {
    (void) ctx;
    table_count[current]++;
    uint32_t sym = ELF64_R_SYM(info);
    if (!sym) return;
    const char *n = strtab + symtab[sym].st_name;
    for (int i = 0; i < nnames; i++)
        if (strcmp(n, names[i]) == 0) {
            printf("%-14s %-9s offset=0x%llx %s\n", table_name, type_name(ELF64_R_TYPE(info)), (unsigned long long) off, n);
            hits++;
        }
}

int main(int argc, char **argv) {
    static const char *defaults[] = { "glShaderSource", "eglGetProcAddress" };
    if (argc < 2) { fprintf(stderr, "usage: %s ELF [symbol...]\n", argv[0]); return 2; }
    names = argc > 2 ? (const char **) argv + 2 : defaults;
    nnames = argc > 2 ? argc - 2 : 2;
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }
    fseek(f, 0, SEEK_END); img_size = (size_t) ftell(f); fseek(f, 0, SEEK_SET);
    img = malloc(img_size);
    if (!img || fread(img, 1, img_size, f) != img_size) { fprintf(stderr, "read failed\n"); return 2; }
    fclose(f);
    eh = (Elf64_Ehdr *) img;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 || eh->e_ident[EI_CLASS] != ELFCLASS64) { fprintf(stderr, "not ELF64\n"); return 2; }
    const Elf64_Phdr *ph = (const Elf64_Phdr *) (img + eh->e_phoff);
    const Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < eh->e_phnum; i++) if (ph[i].p_type == PT_DYNAMIC) dyn = (const Elf64_Dyn *) (img + ph[i].p_offset);
    if (!dyn) { fprintf(stderr, "no PT_DYNAMIC\n"); return 2; }
    const Elf64_Rela *rel[2] = {0, 0};
    size_t relsz[2] = {0, 0}, packedsz = 0;
    const uint8_t *packed = NULL;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB: symtab = at(dyn->d_un.d_ptr); break;
            case DT_STRTAB: strtab = at(dyn->d_un.d_ptr); break;
            case DT_JMPREL: rel[0] = at(dyn->d_un.d_ptr); break;
            case DT_PLTRELSZ: relsz[0] = dyn->d_un.d_val; break;
            case DT_RELA: rel[1] = at(dyn->d_un.d_ptr); break;
            case DT_RELASZ: relsz[1] = dyn->d_un.d_val; break;
            case DT_ANDROID_RELA: packed = at(dyn->d_un.d_ptr); break;
            case DT_ANDROID_RELASZ: packedsz = dyn->d_un.d_val; break;
        }
    }
    if (!symtab || !strtab) { fprintf(stderr, "no dynamic symbols\n"); return 2; }
    current = 0; table_name = "DT_JMPREL"; oulg_rela_each(rel[0], relsz[0], seen, NULL);
    current = 1; table_name = "DT_RELA"; oulg_rela_each(rel[1], relsz[1], seen, NULL);
    long n = 0;
    current = 2; table_name = "DT_ANDROID_RELA";
    if (packed) n = oulg_aps2_each(packed, packedsz, seen, NULL);
    printf("tables: jmprel=%d rela=%d android_rela=%d%s hits=%d\n", table_count[0], table_count[1], table_count[2],
           packed && n < 0 ? " (packed table MALFORMED)" : "", hits);
    return packed && n < 0 ? 1 : 0;
}
