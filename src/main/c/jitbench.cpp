#include <llvm/ExecutionEngine/Orc/LLJIT.h>
#include <llvm/ExecutionEngine/Orc/ExecutionUtils.h>
#include <llvm/ExecutionEngine/Orc/JITTargetMachineBuilder.h>
#include <llvm/ExecutionEngine/Orc/ThreadSafeModule.h>
#include <llvm/IRReader/IRReader.h>
#include <llvm/Support/SourceMgr.h>
#include <llvm/Support/TargetSelect.h>
#include <llvm/Support/DynamicLibrary.h>
#include <llvm/Support/raw_ostream.h>
#include <cstdio>
#include <cstdint>
#include <ctime>
#include <cstdlib>

using namespace llvm;
using namespace llvm::orc;

extern "C" {
int64_t *gc_roots;
int64_t gc_root_count;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <file.ll> [funcname [intarg]]\n", argv[0]);
        return 2;
    }
    InitializeNativeTarget();
    InitializeNativeTargetAsmPrinter();
    InitializeNativeTargetAsmParser();

    auto jtmb = JITTargetMachineBuilder::detectHost();
    if (!jtmb) {
        fprintf(stderr, "no host target\n");
        return 1;
    }
    jtmb->setCodeGenOptLevel(CodeGenOptLevel::Aggressive);

    auto jit = LLJITBuilder().setJITTargetMachineBuilder(std::move(*jtmb)).create();
    if (!jit) {
        fprintf(stderr, "jit create failed\n");
        return 1;
    }
    LLJIT &j = **jit;
    auto &es = j.getExecutionSession();

    auto gen = DynamicLibrarySearchGenerator::GetForCurrentProcess(
        j.getDataLayout().getGlobalPrefix());
    if (!gen) {
        fprintf(stderr, "searchgen failed\n");
        return 1;
    }
    j.getMainJITDylib().addGenerator(std::move(*gen));

    gc_roots = (int64_t *)calloc(1 << 20, sizeof(int64_t));
    gc_root_count = 0;

    SMDiagnostic err;
    auto ctx = std::make_unique<LLVMContext>();
    auto mod = parseIRFile(argv[1], err, *ctx);
    if (!mod) {
        err.print("parse", errs());
        return 1;
    }
    ThreadSafeContext tsctx(std::move(ctx));
    ThreadSafeModule tsm(std::move(mod), tsctx);
    if (auto e = j.addIRModule(std::move(tsm))) {
        fprintf(stderr, "addIRModule failed\n");
        return 1;
    }

    if (argc < 3) {
        printf("module loaded ok\n");
        return 0;
    }

    auto sym = es.lookup({&j.getMainJITDylib()}, es.intern(argv[2]));
    if (!sym) {
        fprintf(stderr, "lookup failed: %s\n", argv[2]);
        return 1;
    }
    int32_t arg = argc > 3 ? atoi(argv[3]) : 0;
    int32_t (*fn)(int32_t) = sym->getAddress().toPtr<int32_t (*)(int32_t)>();
    struct timespec t0;
    struct timespec t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int32_t acc = 0;
    for (int i = 0; i < 3; i++) {
        acc = fn(arg);
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long long us = ((t1.tv_sec - t0.tv_sec) * 1000000LL + (t1.tv_nsec - t0.tv_nsec) / 1000) / 3;
    printf("addr=0x%llx result=%d avg_us=%lld\n",
           (unsigned long long)sym->getAddress().getValue(), acc, us);
    return 0;
}