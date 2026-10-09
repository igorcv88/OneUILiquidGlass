// Adds a DT_NEEDED entry at the head of an ELF64 executable's dynamic section, in place of its
// DT_DEBUG entry, so the new library is searched before every other dependency. The library name
// must already exist in .dynstr; the suffix of "libSurfaceFlingerProp.so" after "lib" is used.
// Prints the library file name to install. Exit 0 patched, 2 already patched, 1 error.
#include <elf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *ANCHOR = "libSurfaceFlingerProp.so";

static long vaddr_to_off(Elf64_Ehdr *eh, unsigned char *base, Elf64_Addr va) {
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++)
        if (ph[i].p_type == PT_LOAD && va >= ph[i].p_vaddr && va < ph[i].p_vaddr + ph[i].p_filesz)
            return (long) (va - ph[i].p_vaddr + ph[i].p_offset);
    return -1;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: dtneeded <elf>\n"); return 1; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 1; }
    fseek(f, 0, SEEK_END); long size = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *base = malloc(size);
    if (!base || fread(base, 1, size, f) != (size_t) size) { fprintf(stderr, "read failed\n"); return 1; }
    fclose(f);
    Elf64_Ehdr *eh = (Elf64_Ehdr *) base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) || eh->e_ident[EI_CLASS] != ELFCLASS64) { fprintf(stderr, "not ELF64\n"); return 1; }
    Elf64_Phdr *ph = (Elf64_Phdr *) (base + eh->e_phoff);
    Elf64_Dyn *dyn = NULL; size_t ndyn = 0;
    for (int i = 0; i < eh->e_phnum; i++)
        if (ph[i].p_type == PT_DYNAMIC) { dyn = (Elf64_Dyn *) (base + ph[i].p_offset); ndyn = ph[i].p_filesz / sizeof(Elf64_Dyn); }
    if (!dyn) { fprintf(stderr, "no PT_DYNAMIC\n"); return 1; }
    Elf64_Addr strtab = 0; Elf64_Xword strsz = 0;
    long debug = -1, first_needed = -1;
    for (size_t i = 0; i < ndyn && dyn[i].d_tag != DT_NULL; i++) {
        if (dyn[i].d_tag == DT_STRTAB) strtab = dyn[i].d_un.d_ptr;
        if (dyn[i].d_tag == DT_STRSZ) strsz = dyn[i].d_un.d_val;
        if (dyn[i].d_tag == DT_DEBUG && debug < 0) debug = (long) i;
        if (dyn[i].d_tag == DT_NEEDED && first_needed < 0) first_needed = (long) i;
    }
    long stroff = vaddr_to_off(eh, base, strtab);
    if (stroff < 0 || !strsz) { fprintf(stderr, "no dynstr\n"); return 1; }
    const char *str = (const char *) base + stroff;
    long anchor = -1;
    for (Elf64_Xword i = 0; i + strlen(ANCHOR) < strsz; i++)
        if (!strcmp(str + i, ANCHOR)) { anchor = (long) i; break; }
    if (anchor < 0) { fprintf(stderr, "anchor string missing\n"); return 1; }
    Elf64_Xword name = (Elf64_Xword) anchor + 3;
    if (first_needed >= 0 && dyn[first_needed].d_un.d_val == name) { printf("%s\n", str + name); return 2; }
    if (debug < 0 || first_needed < 0 || debug < first_needed) { fprintf(stderr, "no DT_DEBUG after DT_NEEDED\n"); return 1; }
    memmove(&dyn[first_needed + 1], &dyn[first_needed], (size_t) (debug - first_needed) * sizeof(Elf64_Dyn));
    dyn[first_needed].d_tag = DT_NEEDED;
    dyn[first_needed].d_un.d_val = name;
    f = fopen(argv[1], "wb");
    if (!f || fwrite(base, 1, size, f) != (size_t) size || fclose(f)) { fprintf(stderr, "write failed\n"); return 1; }
    printf("%s\n", str + name);
    return 0;
}
