# ios-jvm-aot

Ahead-of-time compiler that turns Java 21 bytecode into native iOS arm64 code. No JIT, no interpreter, no VM.

## Why

iOS forbids JIT at the code signing level, which blocks every existing way of running Minecraft Java Edition on iPhone. Precompiling bytecode to native machine code is the only path that does not need JIT at runtime.

Existing AOT runtimes cannot carry modern Minecraft:

| Runtime | Ceiling | Reason |
|---|---|---|
| RoboVM / MobiVM | Java 16 bytecode | bundles ASM 9.0 |
| Avian | Java 8 | class library subset |
| GraalVM Native Image | no iOS target | platform not supported |

Minecraft 1.20.5+ needs Java 21 bytecode (major 65) plus the Java 21 class library, so the toolchain has to be built from scratch.

## Pipeline

```
.class (Java 21)
   -> ASM 9.7.1 tree parse
   -> stack machine IR
   -> LLVM IR text
   -> clang -target arm64-apple-ios
   -> Mach-O object / dylib / executable
```

## Current state

Stage 0, the link is proven end to end:

- static and instance methods
- int parameters and int returns
- locals, operand stack, branches, loops
- string constants, `System.out.println` for String and int
- `Math.abs` / `max` / `min`

Not there yet: objects, arrays, exceptions, GC, threads, the Java class library, LWJGL.

## Build

```
mvn package
javac -d build/classes testdata/*.java
java -jar target/ios-jvm-aot.jar -cp build/classes -o build/out.ll
clang -O1 build/out.ll -o build/hello
./build/hello
```

For iOS:

```
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
clang -O1 -c -target arm64-apple-ios16.0 -isysroot "$SDK" build/out.ll -o build/hello.o
```

CI runs on `macos-14` and produces macOS, iOS simulator and iOS device objects from the same IR.

## Roadmap

1. object model, virtual dispatch, arrays
2. exceptions and stack traces
3. garbage collector (Boehm or a precise collector)
4. threads and monitors
5. Java 21 class library, bootstrapped by compiling OpenJDK's own `java.base` sources with this compiler
6. LWJGL 3 over gl4es, then Minecraft

## License

Apache-2.0