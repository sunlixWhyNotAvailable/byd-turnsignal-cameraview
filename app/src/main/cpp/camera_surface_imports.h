#pragma once
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>
#include <errno.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>

// Resolve only the already-loaded libgui owning the OEM import; no private dlopen or offsets.
namespace byd_camera {
inline int replace(void** slot,void* value) {
    FILE* maps=fopen("/proc/self/maps","r");if(!maps)return -errno;
    char line[1024],perm[5];uintptr_t start,end;int protection=-1;
    while(fgets(line,sizeof(line),maps)) {
        if(sscanf(line,"%lx-%lx %4s",&start,&end,perm)==3 && uintptr_t(slot)>=start && uintptr_t(slot)<end) {
            protection=(perm[0]=='r'?PROT_READ:0)|(perm[1]=='w'?PROT_WRITE:0)|(perm[2]=='x'?PROT_EXEC:0);break;
        }
    }
    fclose(maps);
    if(protection<0||(protection&PROT_EXEC))return -EINVAL;
    long pageSize=sysconf(_SC_PAGESIZE);
    if(pageSize<=0)return -EINVAL;
    void* page=reinterpret_cast<void*>(uintptr_t(slot)&~(uintptr_t(pageSize)-1));
    if(mprotect(page,pageSize,protection|PROT_WRITE))return -errno;
    __atomic_store_n(slot,value,__ATOMIC_RELEASE);
    if(mprotect(page,pageSize,protection))return -errno;
    return 0;
}
struct Import { const char* name;void** slot=nullptr;int count=0; };
inline int findImport(dl_phdr_info* info,size_t,void* data) {
    auto& found=*static_cast<Import*>(data);
    const char* name=strrchr(info->dlpi_name,'/');name=name?name+1:info->dlpi_name;
    if(strcmp(name,"libbmmcamera.so"))return 0;
    const ElfW(Dyn)* dynamic=nullptr;
    for(int i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_DYNAMIC)
        dynamic=reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr+info->dlpi_phdr[i].p_vaddr);
    if(!dynamic)return 0;
    auto ptr=[&](uintptr_t p){return p<info->dlpi_addr?p+info->dlpi_addr:p;};
    const ElfW(Sym)* symbols=nullptr;const char* strings=nullptr;const ElfW(Rela)* relocations=nullptr;
    size_t bytes=0;int type=0;
    for(auto d=dynamic;d->d_tag!=DT_NULL;d++)switch(d->d_tag) {
        case DT_SYMTAB:symbols=reinterpret_cast<const ElfW(Sym)*>(ptr(d->d_un.d_ptr));break;
        case DT_STRTAB:strings=reinterpret_cast<const char*>(ptr(d->d_un.d_ptr));break;
        case DT_JMPREL:relocations=reinterpret_cast<const ElfW(Rela)*>(ptr(d->d_un.d_ptr));break;
        case DT_PLTRELSZ:bytes=d->d_un.d_val;break;
        case DT_PLTREL:type=d->d_un.d_val;break;
    }
    if(!symbols||!strings||!relocations||type!=DT_RELA)return 0;
    for(size_t i=0;i<bytes/sizeof(*relocations);i++) {
        auto r=relocations[i];
        if(ELF64_R_TYPE(r.r_info)!=R_AARCH64_JUMP_SLOT)continue;
        if(!strcmp(strings+symbols[ELF64_R_SYM(r.r_info)].st_name,found.name)) {
            ++found.count;found.slot=reinterpret_cast<void**>(info->dlpi_addr+r.r_offset);
        }
    }
    return 0;
}
template<size_t N> struct GuiSymbols {
    const char* names[N];
    void* values[N]{};
    void* anchor=nullptr;
    int matches=0;
    std::string error{},path{};
};
template<size_t N> int findLoadedGui(dl_phdr_info* info,size_t,void* data) {
    auto& result=*static_cast<GuiSymbols<N>*>(data);
    const char* name=strrchr(info->dlpi_name,'/');name=name?name+1:info->dlpi_name;
    if(strcmp(name,"libgui.so"))return 0;
    auto executable=[&](uintptr_t address) {
        for(int i=0;i<info->dlpi_phnum;i++) {
            const auto& p=info->dlpi_phdr[i];uintptr_t start=info->dlpi_addr+p.p_vaddr;
            if(p.p_type==PT_LOAD&&(p.p_flags&PF_X)&&address>=start&&address-start<p.p_memsz)return true;
        }
        return false;
    };
    // Select the actual OEM import's owner, even if another namespace has a second libgui.
    if(!executable(reinterpret_cast<uintptr_t>(result.anchor)))return 0;
    ++result.matches;result.path=info->dlpi_name;
    const ElfW(Dyn)* dynamic=nullptr;size_t dynamicCount=0;
    for(int i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_DYNAMIC) {
        dynamic=reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr+info->dlpi_phdr[i].p_vaddr);
        dynamicCount=info->dlpi_phdr[i].p_memsz/sizeof(*dynamic);
    }
    auto ptr=[&](uintptr_t p){return p<info->dlpi_addr?p+info->dlpi_addr:p;};
    const ElfW(Sym)* symbols=nullptr;const char* strings=nullptr;
    const uint32_t* gnu=nullptr;size_t count=0,stringBytes=0,symbolBytes=0;
    for(size_t i=0;i<dynamicCount&&dynamic[i].d_tag!=DT_NULL;i++) {
        const auto& d=dynamic[i];switch(d.d_tag) {
            case DT_SYMTAB:symbols=reinterpret_cast<const ElfW(Sym)*>(ptr(d.d_un.d_ptr));break;
            case DT_STRTAB:strings=reinterpret_cast<const char*>(ptr(d.d_un.d_ptr));break;
            case DT_STRSZ:stringBytes=d.d_un.d_val;break;
            case DT_SYMENT:symbolBytes=d.d_un.d_val;break;
            case DT_HASH:count=reinterpret_cast<const uint32_t*>(ptr(d.d_un.d_ptr))[1];break;
            case DT_GNU_HASH:gnu=reinterpret_cast<const uint32_t*>(ptr(d.d_un.d_ptr));break;
        }
    }
    if(!count&&gnu&&gnu[0]&&gnu[2]) {
        const auto* buckets=reinterpret_cast<const uint32_t*>(reinterpret_cast<const ElfW(Addr)*>(gnu+4)+gnu[2]);
        const auto* chains=buckets+gnu[0];uint32_t last=0;
        for(uint32_t i=0;i<gnu[0];i++)if(buckets[i]>last)last=buckets[i];
        count=gnu[1];
        if(last>=gnu[1]) {
            // GNU hash chains end at the low-bit marker; no section headers or binary offsets.
            while(!(chains[last-gnu[1]]&1))++last;
            count=size_t(last)+1;
        }
    }
    if(!symbols||!strings||!stringBytes||!count||symbolBytes!=sizeof(*symbols)) {
        result.error="Missing/invalid loaded ELF dynamic symbol tables: "+result.path;return 0;
    }
    for(size_t i=0;i<count;i++) {
        const auto& s=symbols[i];unsigned binding=ELF64_ST_BIND(s.st_info);
        if(s.st_shndx==SHN_UNDEF||ELF64_ST_TYPE(s.st_info)!=STT_FUNC||
                (binding!=STB_GLOBAL&&binding!=STB_WEAK)||s.st_name>=stringBytes)continue;
        const char* symbol=strings+s.st_name;
        if(!memchr(symbol,0,stringBytes-s.st_name))continue;
        for(size_t j=0;j<N;j++)if(!strcmp(symbol,result.names[j])) {
            uintptr_t address=info->dlpi_addr+s.st_value;
            if(executable(address))result.values[j]=reinterpret_cast<void*>(address);
        }
    }
    return 0;
}
}
