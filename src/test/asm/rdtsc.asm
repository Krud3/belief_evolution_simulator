section .text
global Java_benchmarking_RdtscNative_rdtscNative
export Java_benchmarking_RdtscNative_rdtscNative

Java_benchmarking_RdtscNative_rdtscNative:
    rdtsc
    shl rdx, 32
    or rdx, rax
    mov rax, rdx
    ret
