# Dev Setup

## Prerequisites

- **Java 25** (GraalVM recommended) - `sdk install java 25.0.1-graal`
- **Gradle 9.2+** - `sdk install gradle 9.2.1`
- **Nix** (with flakes enabled) - [nixos.org/download](https://nixos.org/download)

```bash
sdk env install
```

The Rust toolchain and protobuf compiler are provided by the flake's
`devShells.default`; `nix develop` drops you into a shell with both. They are
not required globally. Rust is still useful locally for ad-hoc `cargo update`
or `cargo edit` runs against `core-bridge/rust/` — see `core-bridge/README.md`.

> **First-run note:** the flake declares `temporal-kt.cachix.org` as a binary
> substituter. On the first `nix build` (or first `gradle build` that triggers
> Nix), Nix will prompt you to accept it — say yes to pull pre-built artifacts
> from CI instead of compiling sdk-core from source. Trusted users on NixOS
> have it accepted automatically; non-trusted users who decline will fall back
> to a from-source build (slow, but correct).

### Supported Platforms

Temporal-KT publishes native libraries for:

* macOS aarch64
* Linux x86_64 (glibc)
* Linux aarch64 (glibc)
* Windows x86_64

> **Note:** macOS x86_64 is not supported. Alpine Linux and other musl libc
> distributions are not currently supported either. For containerized
> deployments, use a glibc-based image (e.g., `debian`, `ubuntu`) instead of
> Alpine.

Native libraries are built via the repo's Nix flake. A single Linux host can
cross-compile all three Linux-reachable classifiers (linux-x86_64-gnu,
linux-aarch64-gnu, windows-x86_64 via mingw); macOS aarch64 must be built
natively on an Apple Silicon host. See `core-bridge/README.md` for the full
matrix.


## Cloning

This repo uses git submodules. Clone with:

```bash
git clone --recurse-submodules https://github.com/snipesy/temporal-kt.git
```

Or if already cloned:

```bash
git submodule update --init --recursive
```

## Building

```bash
./gradlew build
```

## Core Foundation

The core foundation of temporal-kt is built around 2 APIs, Declarative and Imperative (although they work together).


### Imperative API [TKT-0001](./proposals/TKT-0001-annotations.md)

Currently only TKT-0001 is supported until the core foundation and core-bridge is stable. It is unclear if we will
support TKT-0002 (i.e. interface proxy stubs) like Java

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! Count: ${arg.count}"
    }
}

class MyActivity {
    @Activity
    suspend fun ActivityContext.greet(name: String): String = "Hello, $name"
}

fun TemporalApplication.module() {
    install(SerializationPlugin) { json() }
    taskQueue("my-queue") {
        workflow<MyWorkflow>()
        activity(MyActivity())
    }
}
```

### Declarative API [TKT-0003](./proposals/TKT-0003-inline.md)

Temporal TKT-0003 is currently not implemented, and will require Kotlin FIR integration in the form of [a compiler-plugin](../compiler-plugin/)

```kotlin
fun TemporalApplication.module() {
    install(SerializationPlugin) { json() }
    taskQueue("my-queue") {
        workflow<WorkflowArg>("MyWorkflow") { arg ->
            val greeting = activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
            "$greeting! Count: ${arg.count}"
        }
    }
}
```

## Proposals

The main goal of this project is to provide powerful kotlin-first APIs to develop Temporal workflows and activities.

These niceties are collected in various TKT (Temporal Kotlin) proposals.

See [proposals/](./proposals/) for API design proposals:

| Proposal                                                 | Description |
|----------------------------------------------------------|-------------|
| [TKT-0001](./proposals/TKT-0001-annotations.md)          | Annotation-based definitions (primary) |
| [TKT-0002](./proposals/TKT-0002-interfaces.md)           | Interface-based definitions (interop) |
| [TKT-0003](./proposals/TKT-0003-inline.md)               | Inline declarative definitions |
| [TKT-0004](./proposals/TKT-0004-dependency-injection.md) | Dependency injection |
| [TKT-0005](./proposals/TKT-0005-modules.md)              | Module registration |
| [TKT-0006](./proposals/TKT-0006-dsl-scope.md)            | DSL scope safety |
| [TKT-0007](./proposals/TKT-0007-determinism.md)          | Determinism checks |
| [TKT-0008](./proposals/TKT-0008-non-suspending.md)       | Non-suspending annotations/interfaces |
| [TKT-0009](./proposals/TKT-0009-routing.md)              | Routing |
| [TKT-0010](./proposals/TKT-0010-testing.md)              | Test application environment |
| [TKT-0011](./proposals/TKT-0011-payload-codec.md)        | Payload codec (encryption/compression) |
| [TKT-0012](./proposals/TKT-0012-payload-serializer.md)   | Payload serializer |
| [TKT-0013](./proposals/TKT-0013-ktor-subroutine.md)      | Ktor subroutine (unified JAR) |
| [TKT-0014](./proposals/TKT-0014-coroutine-scopes.md)     | Coroutine scope management |
| [TKT-0015](./proposals/TKT-0015-context-resolution.md)   | Workflow context resolution |
