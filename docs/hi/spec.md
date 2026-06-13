# Hi Language Specification — v0.3

> A statically-typed, expression-oriented, native-compiled language implemented as a new frontend that emits Scala Native's NIR and reuses the entire Scala Native backend.

---

## Table of Contents

1. [Overview & Design Principles](#1-overview--design-principles)
2. [MVP Scope](#2-mvp-scope)
3. [Lexical Structure](#3-lexical-structure)
4. [Precedence & the Bounded-Application Rule](#4-precedence--the-bounded-application-rule)
5. [Grammar (EBNF)](#5-grammar-ebnf)
6. [Type System](#6-type-system)
7. [Expressions, Statements & Semantics](#7-expressions-statements--semantics)
8. [Desugarings & NIR Lowering](#8-desugarings--nir-lowering)
9. [Example Programs](#9-example-programs)
10. [Implementation & Build Integration](#10-implementation--build-integration)
11. [Open Decisions](#11-open-decisions)

**Appendix:** [Changelog](#changelog)

---

## 1. Overview & Design Principles

### 1.1 Why Hi Exists — Motivation & Niche

Hi targets a specific, under-served point in the language-design space: **native ahead-of-time compilation with a garbage collector**. Most languages sit in a different quadrant:

| | Manual / no GC | Garbage-collected |
|---|---|---|
| **Native AOT binary** | C, C++, Rust, Swift, Zig | **Hi**, Go, D, Crystal, Nim |
| **Managed VM / JIT** | — | Java, Scala, Kotlin, C# |

The reasoning behind that target:

- **You rarely need C-level performance, and rarely want to pay for it.** For server-class software — microservices, CLIs, glue, network daemons — Rust/Swift-grade manual memory management (or borrow-checking) is overhead the problem does not require. A garbage collector is the right default; the programmer should not be managing memory by hand. (Rust and Swift already serve the *no-GC, C-level-perf* niche well; Hi does not compete there.)
- **But you also don't want a managed VM.** Java/Scala/Kotlin/C# give you GC and a rich type system at the cost of shipping and warming a runtime VM. In a containerized world (Docker/Kubernetes) the deployment *environment* is already provided by the image, so the historical "compile once, run on any VM" benefit is largely moot — what you want instead is a single self-contained native binary with fast startup and a flat memory profile.
- **So: GC, but compiled straight to a native executable.** This is broadly Go's quadrant. Hi aims to keep Go's operational story (one native binary, no VM, simple ops) while being **more expressive and elegant** than Go: sum types with exhaustive matching, a real generic / trait / typeclass system, expression-orientation, structural records, and contextual abstraction.

**Concurrency without colored functions.** Hi reuses Scala Native's Loom-style **virtual threads**. Concurrent code is written in a direct, blocking style — `Thread.ofVirtual.start { => work () }` — with no `async`/`await` split, and therefore no [two-color-function](https://journal.stuffwithstuff.com/2015/02/01/what-color-is-your-function/) problem: any function may block, and the runtime multiplexes virtual threads onto OS threads. This is the goroutine ergonomic, delivered by backend reuse rather than a bespoke runtime.

**Lineage, briefly.** Hi takes GC + native AOT + lightweight green threads from **Go**, a rich GC'd type system and ecosystem reach from **Java**, and expressiveness — ADTs, traits/givens, expression-orientation, lightweight application — from **Scala/OCaml**. It is built as a frontend over Scala Native precisely so it can inherit a production native + GC + virtual-thread backend instead of reimplementing one (§1.2).

**Who it is *not* for (non-goals).** Hi is **not** chasing Go's deliberately-minimal, "boring syntax" audience, and it is **not** competing with Rust/C/C++ on zero-overhead or no-GC workloads. Its closest reference point is **GraalVM native-image + Loom** (a GC'd, AOT-compiled, virtual-threaded JVM stack); Hi pursues the same operational profile from a smaller, expression-oriented, FP-leaning surface with no VM heritage. The intended audience is programmers who want OCaml/Scala-style expressiveness but with a native binary and effortless concurrency.

### 1.2 What Hi Is

**Hi** is a statically-typed, expression-oriented, natively-compiled programming language. It is implemented as a **new compiler frontend** that emits Scala Native's **NIR** (Native Intermediate Representation) and reuses the **entire Scala Native backend** unchanged: the closed-world linker / reachability analysis, the Interflow optimizer, the LLVM code generator, the Immix/Commix garbage collector, the C/native runtime, and the concurrency machinery (OS threads plus Loom-style virtual threads).

Hi is **not** Scala, and it is **not** a fork of Scala Native. It has its own surface syntax and its own typechecker, but it lowers to exactly the same NIR contract that the Scala Native `nscplugin` (the scalac/dotty compiler plugin) targets. The Hi compiler produces `Seq[nir.Defn]` per top-level type, serializes those definitions to binary `.nir` files using `scala.scalanative.nir.serialization.serializeBinary`, and then drives the existing `scala.scalanative.build.Build` pipeline to produce a native binary. Because Hi depends on the in-repo `tools` artifact rather than forking it, the NIR format version is always matched at compile time, and Hi has direct access to internal symbols such as `nir.Rt`, `nir.Defn`, `nir.Sig`, and `nir.Global`.

The architectural consequence is leverage: Hi inherits a production-grade, multi-platform native backend (memory management, exception unwinding, reflection metadata/RTTI, threading, FFI) for free. Hi's authors build only a frontend and a *lowering*; they do not build a runtime, an optimizer, or a code generator.

### 1.3 Language Character

Hi's surface synthesizes ideas from several languages, all reconciled under static typing and native compilation:

- **OCaml-style lightweight function application**: whitespace application `f x y`, with `.`-tightest field access and whitespace-sensitive **chain selection** (`x .m`, a dot preceded by whitespace) for fluent method pipelines.
- **Scala-3-style type system and contextual abstraction**: local/bidirectional type inference (explicitly *not* Hindley-Milner), subtyping by subsumption, traits, `given`/`extension`, and union/intersection types as a frontend-only feature.
- **Kotlin-style `fun` declarations**: named, curried functions; `fun` is *only* for named declarations, never for lambdas (lambdas use `{ x => e }`).
- **Swift-style value/reference split**: `struct` is a copied-by-value aggregate with no identity and no inheritance; `class` is a heap-allocated reference type with identity, single inheritance, and trait implementation.
- **TypeScript-like structural records / named tuples**: `(x = 1, y = 2)` literals and `(x: Int, y: Int)` structural types whose identity is the field-name-and-type set (order-independent), plus nominal `struct`/`class` construction `Point(x = 1, y = 2)`.

### 1.4 The Two Arrow Tokens (governing convention)

Two arrow tokens are used and never confused. This rule governs the entire document:

- **`=>`** introduces a **closure body** and a **match/try arm body**.
- **`->`** is the **function-type constructor** only (`A -> B`, right-associative). It never appears in term position.

### 1.5 Design Principles

The following principles are authoritative and shape every later section.

1. **Reuse, do not reinvent.** Hi is a frontend. The linker, Interflow optimizer, LLVM codegen, GC, runtime, exception handling, RTTI, and threads are the existing Scala Native backend, consumed via the `tools` artifact. No backend code is forked or duplicated.

2. **NIR is the contract.** The frontend's output is `Seq[nir.Defn]`. Anything Hi expresses must be expressible as NIR definitions (`Defn.Class`, `Defn.Module`, `Defn.Trait`, `Defn.Define`, `Defn.Declare`, `Defn.Var`, `Defn.Const`) that the existing linker/optimizer/codegen already understand. Hi introduces **no new NIR nodes and no new runtime intrinsics**.

3. **Semantics follow verified backend behavior, not wishful design.** The clearest case is GC safety: the default GC scans heap-object fields *precisely* via an RTTI ref-offset bitmap that covers only bare reference fields, and scans the stack *conservatively*. Therefore a `struct` (value type) in the MVP may hold only primitives, raw pointers (`Ptr`), and nested structs of the same — never managed references (`class`/`String`/`Array`/ADT). Anything that must hold a managed reference must be a `class`. This is a *language-level* constraint precisely because the backend behavior makes it unsafe otherwise.

4. **Local/bidirectional typing, no global unification.** Public and top-level `fun` signatures generally require explicit annotations; local bindings are inferred. Subtyping is resolved by subsumption. There is no principal-type guarantee and no global unification pass (Scala-3 style, *not* Hindley-Milner).

5. **Static typing with native, ahead-of-time compilation.** No JIT, no managed VM. All dispatch resolution, layout, and reachability are decided at link time under the closed-world assumption.

6. **Expression-oriented and immutable-by-default.** `val` is the default binding; `var` is kept minimal (§2). `if`/`match`/blocks are expressions; `while`/`for` loops type to `Unit`; statements are newline-terminated (§3.4).

7. **Small, orthogonal surface.** Each construct has one clear meaning and one lowering. Operators with subtle interactions (tight `.` vs chain ` .m` vs whitespace application; functional update vs intersection) are given a single precise definition each, and ambiguities are resolved by explicit precedence rather than heuristics.

8. **Deterministic, bounded-lookahead parseability.** The grammar is designed to be parsed by a recursive-descent / Pratt parser with a Scala-3-style newline filter and a fixed set of *bounded* lookahead gates (closure-vs-block, the leading-`(` forms, type-context `(`, `with`-continuations) — **no general backtracking and no type-directed parsing**. Whitespace-sensitive token classification (prefix-vs-infix `-`, tight-`.` vs chain-` .m`, tight-`[` vs spaced-`[`) is decided locally by the lexer with one token of lookbehind, never by parser feedback (§4, §5.6). Where two readings would otherwise compete, the surface is shaped so the ambiguity cannot arise (e.g. braced `match`/`catch` arm-blocks close the dangling-arm question; `[` is whitespace-classified into type-args vs array literal, while `(` is uniformly grouping/application so it needs no such rule).

9. **Erase frontend-only types.** Union (`A | B`) and intersection (`A & B`) types, like Scala 3, exist only in the frontend and are erased at the NIR level; in the MVP they are restricted to reference types.

10. **Defer rather than approximate.** Features that cannot be lowered safely or completely with the current backend (row polymorphism, algebraic effects, value-backed enums, value-structs holding managed references, higher-kinded types, macros, etc.) are explicitly out of scope and named as such.

11. **Always format-version-matched.** Because Hi lives in-repo and depends on `tools`/`nir`, the emitted NIR is guaranteed binary-compatible with the backend it links against; there is no separate NIR version-negotiation surface.

---

## 2. MVP Scope

This table is the authoritative MVP feature boundary. "Judgment call" rows mark decisions the prior design review left open; the decision and its rationale are stated here and used consistently throughout.

### 2.1 In Scope

| Area | In MVP | Notes / Lowering |
| --- | --- | --- |
| Bindings | `val`, `var` | `val` immutable (default). `var` **is in the MVP** but kept minimal: a mutable **local** binding only. **Class/struct fields may also be `var`. Object/module-level `var` fields are deferred** (model module mutable state as a `class` with a `var` field). *Judgment call:* `var` plus `while`/`for` (§7.12) cover imperative iteration and accumulators. |
| Functions | named `fun` (curried, multi-param-list); nullary `fun` permitted | Saturated call → single flat `Defn.Define`; under-application / bare `recv.m` on a non-nullary method → eta-expanded closure. A **nullary** method/getter (zero remaining argument lists) is always saturated and invoked by selection. |
| Lambdas | `{ x => e }`, `{ (x, y) => e }`, `{ (x: Int, y: String) => e }` | Closures only; `fun` is never used for lambdas. *Judgment call:* multi-param closures use a parenthesized list, are **uncurried** (a single N-arg function object, tuple-applied), distinct from curried `fun`. |
| Declaration kinds | `object`, `trait`, `type` (aliases + ADTs), `struct`, `class`, `extension`, `given` | Map to `Defn.Module`/`Defn.Trait`/`Defn.Class` plus `Defn.Define`s. There is **no** `impl` keyword: a type satisfies a trait either *nominally* at its definition via the `<:` clause (dynamic dispatch, owned types) or *retroactively* via `given` (dictionary, foreign/value types). See §6.10. |
| Value vs reference types | `struct` (value), `class` (reference) | `struct`: no identity, no inheritance; fields limited to primitives, `Ptr`, and nested all-primitive/`Ptr` structs (§6.3). `class`: heap, identity, single inheritance + traits, participates in `<:`. |
| ADTs | sealed variants `type Option[A] = \| Some(A) \| None` (paren payloads) | Lower to a reference-backed tagged **class** hierarchy (sealed base `class` + case subclasses); `match` → class-id range-test decision tree. |
| Pattern matching | `e match { \| pat => e2 … }` (postfix, braced arm-block), exhaustiveness checking | Sealed-ADT exhaustiveness is a **compile error** (§6.7, §7). Postfix `match`; braces delimit the arms (§5.4). |
| Control flow | `if c then a else b`; one-armed `if c then a` allowed **iff `a : Unit`** (implicit `else ()`), the guard-clause form `if cond then return`; `while c do e` and `for x in e do body` loops; blocks `{ stmt* }` of newline-terminated statements; `return` = early exit from the enclosing `fun` | Expression-oriented; significant newlines (§3.1, §3.4). Block value = final expression statement, else `Unit`. Loops type to `Unit`; `for` desugars to `foreach` (§7.12). |
| Error handling | `throw expr`, `try e catch { \| Pattern => handler … }` | Exceptions only; maps to NIR unwind / `Throwable`. No `finally`. |
| Tuples & records | tuples `(1, 2)`; named tuples `(x = 1, y = 2)`, one-field `(x = 1)`; structural record TYPES `(x: Int, y: Int)`, one-field `(x: Int)` | Structural identity = field-name set + types, order-independent, deterministic canonical layout. Trailing commas permitted, never required (§5.7). |
| Functional update | `base with (field = v, …)` — one form for records, structs, and classes | Type-directed sugar over a base of known static type; desugars to full rebuild copying unchanged fields. No row polymorphism. |
| Union / intersection types | `A \| B`, `A & B` | Frontend-only / erased; **restricted to reference types**. Intersection over structural records = field-set union; same-name/different-type collision is a compile error. |
| Generics | `[A, B]`, type application `List[Int]`, arrows `A -> B -> C` (right-assoc) | First-order generics only; **upper bounds only** (`[A <: Bound]`); **invariant**; **no variance, no context bounds**. |
| Operators | `.` (tightest, field/method select), chain selection ` .m` (whitespace-preceded dot; application precedence, left-assoc), whitespace application over bounded args | Unbounded args (`if`/`match`/`while`/`for`/`throw`/`try`/`return`/infix) must be parenthesized. |
| Arrays & varargs idiom | array literals `[1, 2, 3]` (spaced `[`); `xs.get i`, `xs.set i v`, `xs.length` | Lower to `Op.Arrayalloc`/`Op.Arrayload`/`Op.Arraystore`/`Op.Arraylength` (§8.9). "Varargs" = pass one array literal: `sum [1, 2, 3]`. Tight `f[T]` remains type application (§5.6). |
| Closure → SAM conversion | a closure checked against a single-abstract-method trait/interface | Synthesized closure class implements that interface instead of `scala.FunctionN` (§7.6, §8.1); covers javalib functional interfaces (`Runnable`, `Comparator`, …). |
| Modules | `package a.b`, `import a.b.C` | Map to NIR `Global.Top` naming. |
| Entry point | `object Main { fun main (args: Array[String]): Unit = ... }` | Lowers to NIR module `Main$` exposing the static `main([Ljava.lang.String;)Unit` discovered via `nir.Rt.ScalaMainSig`. |
| String literals | plain `"..."`; **C-string literal `c"..."`** (the one prefixed-literal exception) | `c"..."` has type `CString` (≈ `Ptr[Byte]`), for libc interop (§3.6). No interpolation, no triple-quoted/raw strings. |
| Runtime / stdlib reference | `java.lang.{Object,String,Class,Throwable,Thread}`, `scala.scalanative.runtime.*`, `scala.FunctionN`, primitive box classes, typed array classes | Referenced by canonical name from published nativelib/javalib/scalalib NIR; linker prunes to reachable set. |
| Concurrency | OS threads (pthreads); virtual threads via javalib reuse | Closure→SAM conversion lets a Hi closure satisfy `Runnable`, so `Thread`/`Thread.ofVirtual` are ergonomically callable (§7.6, §9.6 note). |
| Hi standard library (minimal) | `std.io.{print, println, printf}` | Hi-authored sources shipped with the compiler, compiled to NIR; delegate to javalib `System.out`/`String.format` (§10.2). `printf` takes `(fmt: String) (args: Array[Object])`; format semantics = `java.util.Formatter`. |

### 2.2 Out of Scope (explicitly deferred)

| Feature | Why deferred |
| --- | --- |
| **Row polymorphism** | Functional update is type-directed sugar over a base of *known* full static type. A generic "update any record preserving the rest" function is not expressible without row variables. |
| **Algebraic effects / user-defined effect handlers** | The backend *has* delimited continuations, but the MVP surface exposes none. Error handling is exceptions only. |
| **Value-backed enums** | ADTs lower to a reference-backed tagged class hierarchy; unboxed/value-backed enum representations are deferred. |
| **Value structs holding managed references** | GC safety rule: a managed ref embedded in a by-value struct that becomes a field of a heap object is not scanned precisely and may be collected prematurely. Stack-only value structs with refs would need per-shape RTTI; deferred. *Lift path (post-MVP):* recurse `MemoryLayout.referenceFieldsOffsets` into nested `StructValue`s (the GC markers already walk arbitrary offset arrays), or flatten ref-carrying structs into their containers in the frontend; `Array[struct-with-refs]` stays deferred either way (§6.3). |
| **Higher-kinded types; variance; lower bounds; context bounds** | Generics are first-order, invariant, upper-bounds-only. Contextual requirements use explicit `using` parameters. |
| **`dyn Trait` (existential trait objects)** | Retroactive *dynamic* dispatch on a type you do not own — an `Array[dyn Show]` of mixed payloads. The principled replacement for Rust's `dyn`/Swift's `any`: an existential box pairing a payload with its resolved `given` dictionary, lowering to a synthesized `Defn.Class` (no new NIR). Distinct from a bare trait-as-type: `Array[Show]` is plain **nominal subtyping** (holds values that conform via `<:`, no box) and already works; `Array[dyn Show]` is the **existential** that also admits retroactive `given` instances. Coercion *into* `dyn Trait` is **implicit and type-directed** (no `as` cast) — assigning `[3, true, 7]` to an `Array[dyn Show]` packs each element — keeping the type explicit (cost-visible, the lesson behind Swift's `any`) while preserving ergonomics. Deferred because the owned-type case is covered by `<:` and the foreign-heterogeneous case is rare; until it ships, pair value + dictionary by hand in a one-field wrapper class. Keyword `dyn` is reserved (§3.5). |
| **`using`-constrained extensions** (`extension (x: A) (using Show[A]) { … }`) | Conditional/typeclass-keyed extension methods (Scala 3's `extension (x: A)(using …)`, Swift's `where`-constrained extensions). Deferred: typeclass-keyed methods surface through `trait` + `given` instead (the trait-method-via-given tier of `.m` resolution, §6.10), and plain `extension` stays unconditional. A clean future extension point. |
| **Macros / compile-time metaprogramming** | No metaprogramming surface. |
| **`async`/`await`; `break`/`continue`; non-local `return` from closures** | Concurrency is via OS threads and reused virtual threads — no built-in async surface. `while`/`for` and early-exit `return` are in the MVP (§7.12, §7.13); loop `break`/`continue` and `return` crossing a closure boundary are deferred (keywords reserved, §3.5). |
| **Implicit conversions** | `given`/`extension` provide contextual abstraction, but implicit *coercion* between unrelated types is not in the MVP. |
| **Union/intersection over value structs** | Unions and intersections are restricted to reference types. |
| **User-defined symbolic/backtick operators; indexing sugar `xs[i]`** | The operator lexicon is a fixed closed set. Array *literals* are in the MVP via the spaced-`[` rule (§5.6); *tight* `xs[i]` indexing remains reserved (it parses as type application and fails — by design). Use `xs.get i` (§6.2). |
| **Idiomatic native collections API (`List`/`Array`/`Map` with `map`/`filter`/`fold`/`foreach`)** | **Decided-deferred.** Hi *will* offer a native collections surface, but it is to be **built on Scala Native's existing collection library** (reachable via the `scalalib` NIR Hi already links — see §10) rather than reinvented. Deferring it keeps the MVP a clean vertical slice; until it lands, only the ADT/recursion/loop forms of §9 are guaranteed expressible, and the fluent collection-pipeline from the original Hi tour is **not** part of the MVP examples. (`for x in e do …` already works with any type exposing a `foreach`, §7.12.) |

---

## 3. Lexical Structure

These rules are purely *frontend* concerns: they determine how source text is tokenized and parsed into the Hi AST before that AST is type-checked and lowered to NIR. None change the NIR contract.

### 3.1 Source representation

Hi source is UTF-8 text; the lexer operates over Unicode scalar values. Only the ASCII subset is significant to the grammar (keywords, operators, delimiters); non-ASCII letters are permitted inside identifiers and string/char literals. Line terminators are LF (`U+000A`) or CRLF (`U+000D U+000A`); a bare CR is normalized to a line terminator. Space (`U+0020`) and horizontal tab (`U+0009`) are token separators.

> **Judgment call (significant newlines; no layout).** Hi is newline-terminated but **not** indentation-sensitive: a newline may end a statement (per the §3.4 rules), while indentation never opens or closes a scope (no offside rule, no layout blocks). Spaces and tabs separate tokens and additionally feed exactly three whitespace-*sensitivity* rules: prefix-vs-infix `-` (§4.3); tight-`.` selection vs chain-` .m` selection (§4.2); tight-`[` type application vs spaced-`[` array literal (§5.6). (Parentheses are *not* whitespace-significant in v0.3 — `(` is uniformly grouping/application, §4.3, §5.6.) A `;` is an explicit statement separator equivalent to a significant newline (useful for one-liners), never required.

### 3.2 Comments

```
LineComment   ::= "//" { any-char-except-line-terminator }
BlockComment  ::= "/*" { any-char | BlockComment } "*/"
```

- `//` begins a line comment running to (but not including) the next line terminator.
- `/* ... */` is a **nestable** block comment.
- A comment never produces a token. A line comment ends at (and does not consume) the line terminator. A block comment that *contains* a line terminator counts as one for §3.4's statement-termination rules; a single-line block comment is plain inter-token whitespace (`a /* x */ b` is the application `a b`).

### 3.3 Identifiers and the casing convention

**The first significant letter of an identifier decides its syntactic class.** A leading underscore defers to the first letter; an identifier with no letters is lower (value) class.

```
ident-start    ::= letter | "_"
ident-cont     ::= letter | digit | "_"
RawIdent       ::= ident-start { ident-cont }
```

Each `RawIdent` that is not a keyword (§3.5) is classified into exactly one of:

- **LOWER_ID** — first letter (skipping leading underscores) is lowercase, **or** the identifier contains no letter (`_`, `_1`). Denotes: value/`val`/`var` bindings, `fun` names, parameters, record/struct fields, `given` names, **type variables**.
- **UPPER_ID** — first letter (skipping leading underscores) is uppercase. Denotes: types, `class`, `struct`, `trait`, `object`, ADT constructors, type aliases.

This convention also tells the elaborator how to lower an application: in `f x`, `f` is a value (LOWER_ID), so this is a plain call; in `Point(...)`, `Point` is a type/constructor (UPPER_ID), so the same application syntax lowers to construction (§7.7). Both are *parsed* identically as application (v0.3); only the head's case decides the lowering.

**No user-defined symbolic operators.** The operator lexemes are a fixed, closed set (§4). Symbolic/backtick operator definitions are out of MVP scope; this keeps the precedence table total and statically known.

### 3.4 Statement termination (significant newlines)

Statements are **newline-terminated**. A token filter between the lexer and the parser (one-token lookahead, exactly as Scala 3 synthesizes `NL`) decides which line breaks are statement terminators; the parser then sees an explicit terminator token `term`. Any expression may stand as a statement — there is no leading-keyword requirement and no `do`/`return` wrapping.

> **Statement-termination rules (normative).**
>
> 1. **Suppression contexts (innermost delimiter wins).** Newline significance is decided by the *innermost* enclosing delimiter: inside `( )` and `[ ]`, newlines are plain whitespace — multi-line constructions `T(…)`, argument groups, and array literals need no continuation marks — while inside `{ }` bodies and at top level, each newline is a candidate terminator. A brace body nested within parentheses regains newline significance.
> 2. **Continuation by previous token.** A candidate newline is suppressed when the previous token cannot end a statement: any infix or prefix operator, `,` `=` `=>` `.` `..` `:` `<:` `@`, an opening bracket, the arm bar `|`, or a keyword that requires a continuation (`if` `then` `else` `try` `catch` `with` `while` `for` `in` `do` `throw` `val` `var` `fun` `type` `import` `package` `using` `given` `extension` `struct` `class` `trait` `object`). (`return` *may* end a statement — a bare `return` returns `()`; its operand, if any, must start on the same line.)
> 3. **Continuation by next token.** A candidate newline is suppressed when the next token cannot begin a statement: `then` `else` `match` `catch` `with` `in` `do` `=>` `=` `<:` `,` `)` `]` `}`, the arm bar `|`, any infix-only operator (`*` `/` `%` `^` `==` `!=` `<` `<=` `>` `>=` `&&` `||`), or a **leading `.`** (the multi-line chain form, §4.2). A line starting with `+` `-` `!` `(` `[` `{`, an identifier, a literal, or a statement-capable keyword begins a **new** statement.
> 4. **Application never crosses a terminator.** The arguments of a whitespace application must lie on the same logical line as the callee. For a multi-line call, parenthesize the whole call (rule 1 then suppresses the inner newlines) or pass a brace-delimited closure/block whose `{` sits on the call line (braces self-delimit and may span lines).

**Examples (normative).**

```
setup ()
if verbose then log "ready" else ()
result                                    // bare expression statements are legal

val n = a +                               // '+' cannot end a statement: continues
        b

xs.filter { x => x > 0 }
  .map double                             // leading '.': chain continuation (§4.2)
  .sum

run tasks { t => exec t }                 // same line: closure is an argument
run tasks
{ t => exec t }                           // NEW statement: a bare block (value discarded; compiler warns)
```

**Block value.** A block's value is the value of its **final** statement when that statement is an expression; otherwise `Unit` (§5.4, §7.3). Non-final expression statements are evaluated for effect and their values discarded — a compiler should warn when the discarded type is not `Unit`. Tail-position `return` is unnecessary: write the bare expression. `return` itself is **early exit** from the enclosing `fun` (§7.13).

**Known hazards (documented; Kotlin/Swift-style).** A line beginning with `(` or `[` is always a *new* statement, never a continuation of the previous application — wrap the whole call in parentheses to break long argument lists. A line beginning with `+`/`-` is a new (prefix-operator) statement — leave the operator at the end of the previous line instead.

> **`;` is an explicit separator.** `val a = 1; val b = 2` puts two statements on one line; `;` is exactly equivalent to a significant newline and never required.

> **Judgment call (trailing commas).** Trailing commas are permitted in tuples, named tuples, array literals, argument lists, parameter lists, and type-argument lists — and never required. In particular the one-field record forms are simply `(x = 1)` and `(x: Int)`: they are unambiguous because bare assignment is forbidden inside `( )` grouping (§5.4, §5.7) and arrow domains are unlabeled (§5.9).

### 3.5 Keywords

**Reserved keywords (MVP, active):**

```
val   var   do   return   fun   object   trait   type   struct   class
extension   given   using   with   if   then   else   match   import   package
throw   try   catch   while   for   in   true   false
```

- `val`/`var` — immutable / mutable local binding; `fun` — function declarations. Statements are newline-terminated (§3.4) and any expression may stand as a statement.
- `return` — **early exit** from the enclosing `fun` with the given value (`()` if omitted); type `Nothing` (§7.13). It never exits merely a block, and may not cross a closure boundary in the MVP.
- `while c do e` / `for x in e do body` — loops (§7.12); **`do` introduces a loop body** (its only role).
- `class`/`struct` — reference/value split. `struct` is a by-value aggregate restricted to primitives, `Ptr`, and nested such structs. A `class`/`object` declares the traits it satisfies *nominally* in its `<:` clause (§5.3); there is **no** separate `impl Trait for Type` form — retroactive trait satisfaction is `given` (§6.10).
- `extension` — `extension (x: T) { ... }`.
- `given`/`using` — contextual instances and contextual parameters.
- `with` — functional update `base with (...)` only (records/structs/classes, §6.6). Parent lists use commas (`<: A, B`, §5.3); `match` arms live in a braced arm-block (§5.4). Neither uses `with`.
- `throw`/`try`/`catch` — the only error-handling forms: `try e catch { | P => h … }` (§7.11).
- `true`/`false` — boolean literals lexed as keywords.

> **Note.** `new` is **not** a keyword. Construction is constructor application `T(...)` (§7.7); there is no `new`.

**Reserved-for-future keywords** (lexed as keywords, rejected by the parser with a "reserved for future use" diagnostic):

```
enum   effect   handle   resume   break   continue
yield   lazy   inline   mutable   as   dyn
```

> **Judgment call (`dyn` reserved).** `dyn` is reserved for the deferred existential trait-object form `dyn Trait` (§2.2) — retroactive *dynamic* dispatch on a foreign/value type, lowering to a payload-plus-`given`-dictionary box. It is not active in the MVP; the owned-type dynamic case is served by the `<:` clause (§6.10).

> **Judgment call.** `then`/`else` are full keywords. `then` is required after an `if` condition; `else` is optional — a one-armed `if c then e` is legal when `e : Unit` (§7.4). `extends`/`derives` are *not* reserved; inheritance uses the `<:` clause (§5).

### 3.6 Literals

```
IntLit    ::= DecInt | HexInt | BinInt
DecInt    ::= digit { digit | "_" }
HexInt    ::= "0x" hexdigit { hexdigit | "_" }
BinInt    ::= "0b" ("0"|"1") { ("0"|"1") | "_" }
LongLit   ::= IntLit ("L" | "l")

FloatLit  ::= DecInt "." DecInt [ Exp ] [ FloatSuffix ]
            | DecInt Exp [ FloatSuffix ]
            | DecInt FloatSuffix
Exp       ::= ("e"|"E") [ "+" | "-" ] DecInt
FloatSuffix ::= "f" | "F" | "d" | "D"

CharLit    ::= "'" ( CharElem | Escape ) "'"
StringLit  ::= "\"" { StringElem | Escape } "\""
CStringLit ::= "c" StringLit
BoolLit    ::= "true" | "false"
```

- **Integers.** Underscores are digit-group separators, stripped by the lexer. Default literal type is `Int` (NIR `Int`, 32-bit signed); a trailing `L`/`l` makes it `Long`. Hex/binary forms are unsigned bit patterns of the inferred width.
- **Floats.** A literal with a `.`, an exponent, or an `f`/`d` suffix is floating point. Default literal type is `Double`. `f`/`F` → `Float`; `d`/`D` → `Double`.
- **Char.** Single-quoted; type `Char` (NIR `Char`, 16-bit unsigned).
- **String.** Double-quoted; type `String`, lowered to `java.lang.String` (the canonical 4-field layout `value, offset, count, cachedHashCode`). **No string interpolation; no triple-quoted/raw strings.**
- **C-string.** `c"..."` is the **one prefixed-literal form in the MVP**. It has type `CString` (a libc-interop alias for `Ptr[Byte]`), lowering to a NUL-terminated byte sequence reachable as a raw pointer. It exists for direct libc/FFI interop (`scala.scalanative.libc`); the §9 examples use Hi's `std.io` (§10.2) instead. It is *not* a general interpolation mechanism.
- **Bool.** `true`/`false`, type `Bool`.
- **Escapes** (char and string): `\n \r \t \b \f \\ \" \' \0`, plus `\uXXXX`. Unknown escapes are a lexical error.

> **Judgment call.** No hex-float literals. Default literal types are `Int` and `Double`, matching NIR convention so arithmetic lowers without surprise widening.

---

## 4. Precedence & the Bounded-Application Rule

### 4.1 The single normative precedence table

This is **the** precedence table (the grammar of §5 encodes exactly this; where §5 shows productions, they are the operational realization of these levels). **Lower number = binds tighter (tightest first).** Whitespace application and chain selection ` .m` share one left-associative level.

| # | Level | Forms | Associativity |
|---|-------|-------|---------------|
| 1 | **Field/method selection (TIGHTEST)** | `a.b` (field or single method selection) | left |
| 2 | **Type application** | `T[..]`, `f[..]` (tight `[` only) | left |
| 3 | **Whitespace application *and* chain selection ` .m`** | `f x`, `f x y` (curried); `lhs .m a b` (whitespace-preceded dot, §4.2) | **left** (one shared level) |
| 4 | **Prefix (unary) operators** | `-e`, `+e`, `!e` | prefix (non-assoc) |
| 5 | **Multiplicative** | `*` `/` `%` | left |
| 6 | **Additive** | `+` `-` | left |
| 7 | **Bitwise xor** | `^` | left |
| 8 | **Relational** | `<` `<=` `>` `>=` | left (non-chaining) |
| 9 | **Equality** | `==` `!=` | left (non-chaining) |
| 10 | **Logical and** | `&&` | left (short-circuit) |
| 11 | **Logical or** | `\|\|` | left (short-circuit) |
| 12 | **Control / leaf forms (LOOSEST)** | `if … then … else …`, postfix `e match { … }`, `throw e`, `try e catch { … }`, `while … do …`, `for … in … do …`, `return e?` | n/a (unbounded; must be parenthesized to be an operand of 1–11) |

Notes:

- **Closures `{ … => … }` and blocks `{ … }` are bounded** (brace-delimited, self-delimiting). They are **not** in the level-12 unbounded set: they may appear bare as a whitespace-application argument *and* as an operand of any operator. (`a + { x => e }` is legal.)
- `==`/`!=` (level 9) and relational (level 8) **do not chain**: `a < b < c` is a type error (§6).
- **`-` is prefix (level 4)** at the start of an expression / after another operator / after `(` `[` `,` `=` `=>`; it is infix additive (level 6) after a complete operand. Prefix binds *looser* than application: `-f x` ≡ `-(f x)`.
- **Bitwise `|`/`&`, shifts `<<`/`>>`/`>>>`, and unary `~` are not fixed operators.** They are provided as built-in chain-selection methods — `.or`, `.and`, `.xor`, `.shl`, `.shr`, `.ushr`, `.not` — on all integer primitive types (§7.15). This avoids extending the fixed operator table and follows the general idiom (§4.2) that any single-argument method is usable as a binary operator via `a .method b`.

**Type-context operators (separate precedence ladder).** `&`, `|`, and `->` are **type-context only** and never participate in the value-operator climb. In *type* contexts the ladder is (loosest → tightest), matching §5's type grammar:

```
->  (function arrow, RIGHT-associative, loosest)
|   (union)
&   (intersection)
type application  (List[Int], F[G[X]])
atoms (tightest)
```

The lexer emits one token for each of `&`/`|`/`->`; the parser selects the type interpretation by context. In *expression* contexts `|` appears only as the arm bar inside arm-blocks (§5.4); `&` never appears as a standalone value operator (intersection is a type; functional update uses `with`, §6.6). Bitwise AND/OR operations use the chain-selection methods `.and`/`.or` instead (§7.15).

### 4.2 Selection (tight `.`) and chain selection (` .m`)

**Whitespace decides between the two dots.** A `.` that is *not* preceded by whitespace (and is not inside a float literal, §3.6) is **selection** — level 1, tightest — binding to the immediately preceding primary. A `.` that *is* preceded by whitespace or a newline (and followed by a `LOWER_ID`) is **chain selection** — level 3, application precedence, left-associative, sharing the single application level with whitespace application.

- **Tight `.` is tightest (level 1).** `a.f x.g y` parses as `(a.f) (x.g) y`: `a.f` is a selection yielding a callable, then whitespace-applied to args `x.g` and `y`.
- **`.` selects; method args arrive by application.** Selection `a.f` yields a callable; you apply it by juxtaposition `a.f x y` (curried) or with a tuple `a.f (x, y)`. **`(x, y)` is always a single tuple value** (not a Java-style argument list), so `a.f(x, y)` passes *one* argument — the tuple `(x, y)`. A multi-field parameter list `fun f (a: Int, b: Int)` receives and destructures that single tuple; the call `f (1, 2)` looks like Java but passes one value, not two. For two curried args write `a.f x y`; `a.f(x)` ≡ `a.f x` (the parens are grouping). A field or **nullary** method/getter `a.f` is invoked by the selection alone; a non-nullary method named bare (`recv.m` with no args) eta-expands to a closure (§6, §7). A nullary call is written `a.f` (no `()`).
- **Construction is ordinary application** (v0.3): a constructor `T` is a first-class function, so `T(a)` / `T (a)` are applications, *not* a tighter primary. A field selected directly off a fresh construction therefore needs a chain dot or parentheses — `Point(x=1) .x` or `(Point(x=1)).x` — exactly like `(f x).g` (tight `.` never binds to an application result, §4.3).
- **Chain selection ` .m` re-threads the accumulated value.** Operationally, after a primary is parsed, a left-to-right loop consumes *application tails*: a bounded argument applies the current callee to one more argument; a ` .m` step makes the accumulated value the **receiver** and selects method/extension `m`, whose subsequent bounded arguments (further tails) become its arguments. This realizes both `foo bar .m` and `3.add 4 .times 5 .neg` as genuinely same-level left-associative.

**General infix-call pattern.** Chain selection `a .m b` is the idiomatic way to call any single-argument method in operator style. The method name is a regular `LOWER_ID`, not an operator token, so there is no need to extend the fixed operator table (§4.1) for new binary operations — library and built-in methods alike use this same form. Examples: `a .or b` (bitwise OR), `a .and b` (bitwise AND), `str .startsWith "Hi"`, `xs .filter pred .map f`. This is why bitwise and shift operations are provided as methods rather than dedicated operator lexemes (§7.15).

**Worked example (normative).**

```
3.add 4 .times 5 .neg
```

parses (left-assoc, application precedence) and is **equivalent in call structure** to:

```
((3.add 4).times 5).neg
= ((3 + 4) * 5) negated
= (7 * 5) negated
= -35
```

`add`/`times`/`neg` resolve as **methods/extensions on the receiver's type**, never as free functions. Each step's accumulated result becomes the next receiver.

> **Normative note (spacing matters).** In the flat string `3.add 4.times 5.neg` the later dots are *tight*, so `4.times` selects on `4`, not on `(3.add 4)` — a different call shape, which typically fails arity/type checking rather than silently misbehaving. Write a space (or newline) before every chaining dot. Only a *whitespace-preceded* `.` re-threads the accumulated result as the next receiver.

A newline before the dot is the idiomatic multi-line pipeline (§3.4 rule 3):

```
xs.filter { x => positive x }
  .map double
  .sum
```

> *(History: v0.1 spelled chain selection as a dedicated `/.` operator; ` .m` is its exact respelling — same precedence, associativity, resolution, and lowering.)*

### 4.3 The bounded-application rule

Whitespace application (level 3) applies a callable to a sequence of **bounded arguments**. An argument is *bounded* iff its extent is self-delimiting. The bounded-argument forms are exactly:

```
BoundedArg ::= Literal                     // 1, 3.0, 'c', "s", c"s", true
             | LOWER_ID | UPPER_ID         // identifiers (incl. nullary names)
             | "(" Expr ")"                // parenthesized expression (any expr)
             | Tuple | NamedTuple          // (1, 2)   (x=1, y=2)   (x=1)
             | ArrayLit                    // [1, 2, 3]   (spaced '[', §5.6)
             | Closure                      // { x => e }  { (x, y) => e }  { (x: Int) => e }
             | Block                         // { stmt* }
```

Everything else — `if`/`match`/`while`/`for`/`throw`/`try`/`return`, any **infix**/**prefix** operator expression, **and any application** (including a construction `T(...)`, since constructors are ordinary functions, §4.2) — is **unbounded** and must be parenthesized to be used as an argument.

> **`(` is not whitespace-significant (v0.3).** A `(` after a callee — tight *or* spaced — is application to the parenthesized argument; only `[` and `.` are whitespace-classified (§5.6). So `f(x, y)` ≡ `f (x, y)` applies `f` to the *tuple* `(x, y)` (one argument), whereas the curried spine `f x y` passes two. This is the OCaml model: juxtaposition curries, parentheses build one tuple/record argument. **Tight vs spaced `[`:** a `[` glued (no whitespace) to an identifier is a type-argument list bound to that callee — never a standalone argument; any other `[` opens an **array literal**, which *is* a bounded argument (full token-level classification table in §5.6). **Arm-blocks are not bounded arguments:** a `{` whose first significant token is `|` is an **arm-block** (§5.4), legal only after the postfix `match` keyword or after `catch`; greedy application stops in front of it, so `f x match { | … }` has scrutinee `f x` and `f x { y => e }` still passes a trailing closure.

**An application result used as an argument must be parenthesized.** `f (describe x)`, not `f describe x` (which is `(f describe) x`).

**Valid / canonical parses:**

```
f x + y          ===  (f x) + y            // application tighter than +
f x y            ===  ((f x) y)            // curried saturation
g a (b + c)                                // (b + c) bounded by its parens
h (if p then 1 else 2)                     // if-expr must be parenthesized
map xs { x => x + 1 }                      // closure is bounded
make (Point(x=1, y=2))                      // construction is an application -> parenthesize as an arg
xs.map Some                                // constructor Some passed as a first-class function
3.add 4 .times 5 .neg                      // == ((3.add 4).times 5).neg  == -35
sum [1, 2, 3]                              // spaced '[': array-literal argument
map[Int] xs                                // tight '[': type application, then application
a.f x.g y        ===  (a.f) (x.g) y        // tight '.' tighter than application
print (x = 1)                              // one-field named-tuple arg
a + { x => e }                             // closure is a bounded operand (not parenthesized)
```

**Invalid (rejected; require parentheses):**

```
f if p then a else b        // ERROR: if is unbounded -> f (if p then a else b)
map xs x => x + 1           // ERROR: bare closure body -> map xs { x => x + 1 }
f throw e                   // ERROR: throw unbounded -> f (throw e)
f describe x                // parses (f describe) x; for f applied to (describe x) write f (describe x)
f e match { | _ => 0 }      // parses (f e) match {…} (postfix); for a match-result arg write f (e match {…})
f while c do g              // ERROR: while is unbounded -> f (while c do g)
```

> **Judgment call (ambiguous `f -1`).** `f -1` (space before `-`, none after) is **rejected as ambiguous**: write `f (-1)` for the negative-literal argument or `f - 1` for subtraction. `a - 1` (spaces around) is always subtraction.

> **Judgment call (no leading-dot floats).** There are no leading-dot float literals (§3.6): write `0.5`, never `.5`. A whitespace-preceded `.` is chain selection (§4.2), so `xs .5` and `a / .5` are parse errors (chain selection expects a `LOWER_ID`); write `a / 0.5`.

> **Float dot vs selection.** A `.` immediately preceded by digits and immediately followed by a digit is part of a `FloatLit` (`3.0`). Otherwise `.` is selection (`3.field` ≡ `(3).field`).

> **Judgment call (multi-param closures, cross-ref §6/§7).** A multi-parameter closure `{ (x, y) => e }` is a **single** N-parameter (uncurried) function value, lowering to one NIR `scala.FunctionN`, applied with a tuple (`g (a, b)`). It is *not* sugar for `{ x => { y => e } }`. `f { (x, y) => e }` passes one binary function, never two arguments. Currying is reserved for named `fun` declarations or explicit nested closures.

---

## 5. Grammar (EBNF)

This section is the **single source of truth for Hi's surface syntax**. Where any *earlier* prose (§1–§4) conflicts with a production here, the production wins. The grammar is a Pratt-style expression parser over an LL/LALR(1)-friendly item grammar with explicit precedence (§4).

> **Disambiguation prose within §5 is normative.** Some productions are deliberately permissive and are resolved by a bounded-lookahead *procedure* given in prose alongside them — specifically §5.4's arm-block recognition and closure-vs-block scan, §5.6's tight-vs-spaced `[`/`(` resolution, and §5.7's leading-`(` resolution. Where such a procedure specifies how to resolve an otherwise-ambiguous parse, it **takes precedence over the literal grammar**; implementers MUST follow the procedure, not naïve EBNF derivation. (This is the same reason §4's precedence table, not the flat `infix_expr` production, governs operator associativity.)

### 5.1 Notation

- `A ::= ...` — production for `A`.
- `|` — alternation at the **metalevel** (the Hi type-union/ADT/match bar appears quoted as `"|"`).
- `X*`, `X+`, `X?` — repetition / optionality. `( ... )` — metagrouping. `"..."` — literal terminal.
- `sepBy(X, s)` = `( X ( s X )* )?`; `sepBy1(X, s)` = `X ( s X )*`. Trailing separators per §3.4.
- Lexical terminals (from §3): `LOWER_ID`, `UPPER_ID`, `INT_LIT`, `FLOAT_LIT`, `STRING_LIT`, `CSTRING_LIT`, `CHAR_LIT`, `BOOL_LIT`, `UNIT_LIT` (`()`).
- **`term`** — the statement terminator: a significant newline per the §3.4 filter, or an explicit `;`. Newlines inside `( )`/`[ ]` are never `term`. `CHAIN_DOT` is the whitespace-preceded `.` of §4.2 (the tight `.` appears in `postfix_expr`).

### 5.2 Program and items

```
program     ::= package_clause? import_clause* items_block EOF

package_clause ::= "package" qual_name
import_clause  ::= "import" import_path
import_path    ::= qual_name ( "." "{" sepBy1(import_sel, ",") "}" | "." "*" )?
import_sel     ::= ident ( "=>" ident )?            // rename
qual_name      ::= ident ( "." ident )*
ident          ::= LOWER_ID | UPPER_ID

items_block ::= item*

item        ::= val_decl | var_decl | fun_decl | type_decl
             |  struct_decl | class_decl | trait_decl
             |  extension_decl | given_decl | object_decl
```

Each `item` begins with a distinct introducing keyword (`val`/`var`/`fun`/`type`/`struct`/`class`/`trait`/`extension`/`given`/`object`), so items are self-delimiting with no separator token; the parser ends an item when it sees the next item keyword or the enclosing `}`. `items_block` is reused as the body of `object`, `trait`, and the braced `given`. (Top-level items do **not** include bare expression statements or `return`: executable statements live only inside `fun` bodies and blocks, §5.4. Top-level `fun`/`val` items directly inside a `package` lower to a synthesized `<package>.package$` module, §8.5.)

### 5.3 Declarations

#### Value / variable bindings

```
val_decl    ::= "val" pattern_no_alt type_ann? "=" expr
var_decl    ::= "var" LOWER_ID         type_ann? "=" expr
type_ann    ::= ":" type
```

`val` may bind a (nested) irrefutable pattern. A refutable pattern in a `val` is a compile error (use `match`). `var` binds a single mutable name; assignment is via `assign_expr` (§5.5). A type annotation is optional on locals (inferred) and required where §6.1 demands it.

#### Functions (curried; nullary permitted)

```
fun_decl    ::= "fun" LOWER_ID type_params? param_list* ( ":" type )? ( "=" expr )?

type_params ::= "[" sepBy1(type_param, ",") "]"
type_param  ::= UPPER_ID ( "<:" type )?            // upper bound only; NO variance, NO context bounds
param_list  ::= "(" sepBy(param, ",") ")"
param       ::= "using"? LOWER_ID type_ann? ( "=" expr )?
```

- **Body optional ⇒ abstract vs. concrete.** A `fun` *with* a `= expr` body is a concrete definition (`Defn.Define`); a `fun` *without* a body is an **abstract member** (`Defn.Declare`) and requires an explicit return type. An abstract `fun` is legal **only** as a `trait` member; a bodiless `fun` anywhere else (top level, `class`/`struct`/`object` body, block) is a compile error. A *concrete* `fun` in a `trait` is a **default method** — inherited by every implementor unless overridden — and dispatches through the same mechanism as any other trait member (no Swift-style "extension method dispatches statically" split, §6.10). This is what lets `trait Show[A] { fun show (self: A): String }` declare an abstract member while `trait Ord[A] { fun lt (self: A) (o: A): Bool;  fun gte (self: A) (o: A): Bool = !(self .lt o) }` mixes an abstract requirement with a default.
- `param_list*` realizes both multiple parameter lists (`fun f (a) (b) = …`) **and the nullary form** (`fun neg: Int = …`, zero lists). A nullary `fun` is a 0-ary method/getter: it is always *saturated* and is invoked by selection (`x.neg`, `x .neg`) — it never eta-expands.
- An **empty parameter list** `()` declares exactly **one parameter of type `Unit`** (the OCaml convention): `fun ping (): Unit = …` is unary and is saturated by applying the Unit literal — `ping ()`. This is distinct from the zero-list nullary getter above; without this rule, `f ()` (application to the bounded argument `()`) could never saturate a "zero-parameter" list.
- A `using`-marked parameter (or a whole trailing list of them) is a contextual parameter, filled by `given` search (§6.11).
- `type_param` admits **only** an optional upper bound `<: type`. **There is no variance and no `:` context-bound syntax** (those would conflict with §6.10's invariant, upper-bounds-only model); contextual requirements use explicit `using` parameters.

#### Receiver convention for methods (`self`)

Inside a `trait`, `class`, `struct`, or braced `given` body, a method may take its receiver as an explicit first parameter named `self`. **`self` is the one parameter exempt from the §6.1 annotation requirement**: when written without an annotation, its type defaults to the enclosing type (for a generic enclosing type, the fully-applied self-type, e.g. inside `trait Show[A]` the self is `A`). It may be annotated explicitly (`fun show (self: A): String`) and the two spellings are equivalent. Bare `self` (unannotated) is the **idiomatic** spelling; write the annotation only when it aids the reader. Extension methods do not use `self`; their receiver is bound by the `extension` header.

> **Why `self` is explicit (and not an implicit `this`).** Hi's traits are **parameter-based** (`trait Show[A]`, like Haskell `class Show a` and Scala 3 typeclasses), not Self-based (Rust/Swift implicit `Self`). In a parameter-based trait the *dictionary* (the `Show[A]` value) and the *operated-on value* (`A`) are distinct, so an implicit `this` would be ambiguous — it would name the dictionary, not the value. Naming the receiver `self: A` makes one trait declaration serve **both** satisfaction paths (§6.10): nominal `<:` binds `self` to the instance; retroactive `given` binds it to the operand. It also lets a member **omit** `self` to become an *associated* (static) member with no receiver — `trait Monoid[A] { fun empty: A;  fun combine (self: A) (o: A): A }` — and supports multi-parameter classes (`trait Convert[A, B] { fun convert (self: A): B }`), neither of which an implicit `Self` expresses.

#### Type declarations: aliases and ADTs

A `type` head covers both **aliases** and **ML-style sealed variant ADTs**, disambiguated by a **required leading `"|"`** for variants.

```
type_decl       ::= type_alias_decl | variant_decl
type_alias_decl ::= "type" UPPER_ID type_params? "=" type
variant_decl    ::= "type" UPPER_ID type_params? "=" ( "|" variant_case )+
variant_case    ::= UPPER_ID variant_payload?
variant_payload ::= "(" sepBy1(payload_elem, ",") ")"      // 'Some(A)'  /  'Node(left: Tree, value: A)'
payload_elem    ::= ( LOWER_ID ":" )? type                 // positional 'Tree' or named 'left: Tree'
```

**Payload construction/matching (normative).** A **positional** payload `C(T1, …, Tn)` is constructed and matched positionally with synthetic field names `_1..._n`: construct `C(e1, …)`, match `C(p1, …)` (a single field `Some(A)` uses `_1`). A **named** payload `C(f: T, …)` uses named construction/patterns: `C(f = e, …)`, `C(f = p, …)`. Mixing positional and named within one payload is a compile error. Since `C` is a first-class constructor (§7.7), construction is ordinary application of `C`. Variants lower to a reference-backed tagged class hierarchy (§6.7, §8.3).

#### Struct (value) and class (reference)

```
struct_decl ::= "struct" UPPER_ID type_params? ctor_params struct_body?
class_decl  ::= "class"  UPPER_ID type_params? ctor_params class_parents? class_body?

ctor_params ::= "(" sepBy(field_param, ",") ")"
field_param ::= ( "val" | "var" )? LOWER_ID ":" type ( "=" expr )?
             // fields are 'val' by default; 'var' makes them mutable

class_parents ::= "<:" sepBy1(parent, ",")                          // comma-separated; '<:' kept (subtype notation)
parent        ::= type                                              // trait, or argument-less class/trait parent
               |  UPPER_ID type_args? "(" sepBy(super_arg, ",") ")" // parent CLASS with super-ctor args
super_arg     ::= named_arg | expr                                  // named or positional, as in construction

struct_body ::= "{" items_block "}"                        // methods, vals; NO inheritance
class_body  ::= "{" items_block "}"
```

- `parent` admits a **super-constructor call** `UPPER_ID(...args)`: `class Dog (name: String) <: Animal(name)` invokes `Animal`'s constructor with `name` (§8.2 specifies where the parent-ctor args are supplied). A bare `type` parent (no args) is for traits or for argument-less class parents.
- **Locked constraints (enforced in typing, not grammar):** a `struct` has no `class_parents` (no inheritance); a `struct` field's declared (unboxed) type must be a primitive, `Ptr`, or another all-primitive/`Ptr` struct — a managed reference (incl. a box class such as `java.lang.Integer` or `scala.scalanative.unsafe.Ptr`) is a compile error (§6.3). A `class` allows exactly one class parent (with optional super-ctor args) plus any number of comma-listed traits — `class Dog (name: String) <: Animal(name), Runnable, Comparable[Dog]`. (Parent-list commas are top-level; commas inside a super-call `Animal(a, b)` sit at a deeper paren depth, so the two never collide.)

#### Trait, extension, given, object

```
trait_decl    ::= "trait" UPPER_ID type_params? trait_parents? "{" items_block "}"
trait_parents ::= "<:" sepBy1(type, ",")

extension_decl ::= "extension" type_params? "(" LOWER_ID ":" type ")" "{" ext_member* "}"
ext_member     ::= fun_decl                            // self-delimiting (keyword-introduced); no separator

given_decl    ::= "given" given_head? type ( "=" expr | "{" items_block "}" )
given_head    ::= ( LOWER_ID )? type_params? ( "(" sepBy(param, ",") ")" )? ":"

object_decl   ::= "object" UPPER_ID object_parents? "{" items_block "}"
object_parents::= "<:" sepBy1(type, ",")
```

`given_head` is detected by a bounded scan: if a depth-0 `:` occurs before the first `=` or `{`, the head (optional name, type params, params) is present; otherwise the `given` begins directly with the instance type. An instance **with members** uses the braced form `given T { items }`; `given T = expr` binds an existing value as the instance.

An `extension` body holds only `fun` members (no `val`): an extension adds no storage to the receiver's type, so a "computed property" is just a nullary getter `fun sign: Int = …` (§5.3 receiver convention; invoked by selection, §4.2). The extension's receiver binder (`(n: Int)`) plays the role `self` plays in a `trait`/`class` body.

The entry point is an `object_decl` named `Main` whose body declares `fun main (args: Array[String]): Unit = …` (a linker convention, not special grammar). **Object/module-level `var` fields are rejected in MVP** (§2, §6).

### 5.4 Expressions — control & leaf forms

```
expr        ::= assign_expr

assign_expr ::= if_expr
             |  throw_expr
             |  try_expr
             |  while_expr
             |  for_expr
             |  return_expr
             |  closure
             |  block
             |  infix_expr "=" assign_expr            // assignment: LHS an assignable var path
             |  infix_expr ( "match" arm_block )*      // bare expr, optionally POSTFIX-matched (lowest precedence)

if_expr     ::= "if" expr "then" expr ( "else" expr )? // 'else' optional; one-armed iff then-branch : Unit (§7.4)
while_expr  ::= "while" expr "do" expr                 // Unit; §7.12
for_expr    ::= "for" LOWER_ID "in" expr "do" expr     // sugar for e.foreach { x => body }; §7.12
return_expr ::= "return" expr?                         // early exit from the enclosing fun (§7.13);
                                                       // the operand, if any, starts on the same line

// 'match' is POSTFIX (Scala-style): the scrutinee is the preceding expression.
// 'e match { … } match { … }' chains left-associatively. To match the result of a
// control form (if/throw/while/for/return/try), parenthesize it: '(if c then a else b) match { … }'.
arm_block   ::= "{" ( "|" match_arm )+ "}"              // braces required; '{' on the same line as 'match'/'catch'
match_arm   ::= pattern guard? "=>" expr
guard       ::= "if" infix_expr                         // operator-level only: a guard can
                                                        // never swallow the arm's '=>'

throw_expr  ::= "throw" expr
try_expr    ::= "try" expr "catch" arm_block            // 'catch', not 'with' (§7.11)

block       ::= "{" stmts "}"
stmts       ::= ( stmt ( term stmt )* term? )?
stmt        ::= val_decl | var_decl | fun_decl | expr   // bare expression statements (§3.4)

closure     ::= "{" closure_params "=>" stmts "}"
closure_params ::= ε                                    // '{ => e }'      : Function0
                |  LOWER_ID                             // '{ x => e }'    : Function1 (single bare param)
                |  "(" sepBy(closure_param, ",") ")"    // '{ (x: Int, y: String) => e }' : FunctionN (uncurried)
closure_param  ::= LOWER_ID type_ann?                   // type annotation optional
```

> **Postfix match & arm-block recognition (normative).** `match` is a **postfix** operator at the loosest precedence (level 12): its scrutinee is the preceding `infix_expr`, so `f x match { … }` ≡ `(f x) match { … }` and `a + b match { … }` ≡ `(a + b) match { … }`. The scrutinee is parsed first (greedy application included), the `match` keyword is the explicit separator, and the **arm-block** follows. An arm-block — a `{` whose first significant token is `|` — is never a bounded argument, block, or closure; it is legal only after `match` or `catch`. (The `{ |` signal is still useful: it keeps a `catch` arm-block from being read as a trailing closure, since `|` can begin neither a statement nor a `closure_params` list.) The arm-block's `{` opens on the **same logical line** as its `match`/`catch` keyword (a line-leading `{` starts a new statement, §3.4 rule 3 — the Go convention), while a line-leading `match` continues the previous line (§3.4 rule 3), giving the multi-line `xs.map f` ⏎ `match { … }`. Braces make arm ownership explicit, so the dangling-arm hazard (an outer arm silently captured by an inner `match`) cannot arise.

> **Judgment call (multi-param closures).** A multi-parameter closure uses a **parenthesized** list `{ (x, y) => e }` / `{ (x: Int, y: String) => e }` (types optional) and is a **single** N-parameter (uncurried) closure → one `scala.FunctionN`, applied with a tuple `f (a, b)` (§7.6). Single-param is the bare `{ x => e }`; the empty form `{ => e }` is a `scala.Function0`. There is **no** comma-without-parens form (`{ x, y => e }` is removed). Currying is reserved for named `fun` (multi param list) or explicit nesting `{ x => { y => e } }`. A closure body is a `stmts` sequence like any block: `{ x => log x; x + 1 }` (the last expression is the value).

> **Closure-vs-block disambiguation (bounded lookahead).** On a `{` whose first significant token is **not** `|` (otherwise it is an arm-block, above), scan the brace body at depth 0 for `"=>"` *before* the first `term`, the matching `}`, or any token that cannot occur in `closure_params` (a bare `LOWER_ID`, or a single parenthesized `( … )` group). If `"=>"` is reached first **and** the prefix matches `closure_params`, it is a `closure`; otherwise a `block`. This is bounded (O(params) lookahead) because a real closure's `=>` precedes the first statement boundary.

> **Block value and statement rules.** A block's statements execute in order; statement boundaries are `term`s (§3.4). The block's value is the value of its **final** statement when that statement is an expression; a block whose final statement is a declaration (or an empty block) has value `Unit`. A **non-final** expression statement's value is discarded (a compiler should warn when the discarded type is not `Unit`). Statements after an unconditional `return`/`throw` are an **"unreachable code"** compile error.

> **Assignment.** `infix_expr "=" assign_expr` is the `var` assignment; it is well-typed only when the left side is an assignable mutable path (a local `var` or a `.`-selection of a `var` field). Bare `var`-assignment inside `( )` grouping is forbidden (so `(` followed by `LOWER_ID =` is unambiguously a record/named-tuple, see §5.7).

### 5.5 Expressions — operator / application / selection layer

```
infix_expr  ::= unary_expr ( infix_op unary_expr )*    // resolved by §4 levels 5..11
infix_op    ::= "+" | "-" | "*" | "/" | "%" | "^"
             |  "==" | "!=" | "<" | "<=" | ">" | ">="
             |  "&&" | "||"
unary_expr  ::= prefix_op unary_expr                   // level 4
             |  app_expr
prefix_op   ::= "-" | "+" | "!"

// ONE shared, left-associative application level (whitespace app AND chain selection):
app_expr    ::= postfix_expr app_tail*
app_tail    ::= call_arg                               // apply current callee to one more arg
             |  CHAIN_DOT LOWER_ID type_args?          // ' .m' — accumulated value becomes receiver (§4.2)

postfix_expr ::= primary ( "." selector )*             // level 1, '.' tightest; args via application (§4.2)
selector    ::= LOWER_ID type_args?                    // field / nullary method / method to be applied
             |  UPPER_ID                               // nested object/companion selection

type_args   ::= "[" sepBy1(type, ",") "]"              // explicit type application (suffix only)
```

How `app_tail*` realizes §4.2:

- `postfix_expr` greedily forms tight-`.` selections first, so `a.f` and `x.g` are formed before any application (`a.f x.g y` ⇒ `(a.f) (x.g) y`).
- Processing `app_tail*` left-to-right: a `call_arg` applies the current accumulated callee to one more argument; a `CHAIN_DOT LOWER_ID` step makes the accumulated value the receiver and selects the named method, whose following `call_arg` tails become its arguments. Thus `foo bar .m` and `3.add 4 .times 5 .neg` are same-level left-associative.
- Because application (level 3) binds tighter than `+` (level 6), `f x + y` ⇒ `(f x) + y`.

```
call_arg    ::= literal
             |  arg_path                       // LOWER_ID/UPPER_ID with '.' tail; takes no whitespace args
             |  paren_or_tuple
             |  named_tuple_lit
             |  array_lit
             |  closure
             |  block
```

`arg_path` is the argument-position restriction of `postfix_expr`: it may take tight-`.` selectors but may not itself absorb whitespace arguments. A bare constructor name (`Some`, `Node`) is an `arg_path` and so may be passed as a first-class function (`xs.map Some`); an *applied* construction `T(...)` is an application, hence not a `call_arg` — parenthesize it (`f (Point(x=1))`). **`type_args` is NOT a `call_arg`**: a **tight** `[` (no preceding whitespace) is type application bound to the immediately preceding callee; a **spaced** `[` begins an `array_lit` argument (§5.6).

### 5.6 Primary expressions and literals

```
primary     ::= literal
             |  UNIT_LIT                        // ()
             |  paren_or_tuple
             |  named_tuple_lit
             |  array_lit
             |  path                            // incl. UPPER_ID = constructor function value

literal     ::= INT_LIT | FLOAT_LIT | STRING_LIT | CSTRING_LIT | CHAR_LIT | BOOL_LIT

path        ::= ( LOWER_ID | UPPER_ID ) ( "." LOWER_ID | "." UPPER_ID )* type_args?

paren_or_tuple ::= "(" expr ( "," expr )+ ")"   // tuple, arity >= 2
                |  "(" expr ")"                  // grouping, arity 1

named_tuple_lit ::= "(" named_arg ( "," named_arg )* ","? ")"   // arity >= 1; (x = 1) is one-field
named_arg   ::= LOWER_ID "=" expr

array_lit   ::= "[" sepBy(expr, ",") "]"           // spaced '[' in value position (§3.1, §4.3)

update_expr ::= postfix_expr "with" "(" sepBy1(named_arg, ",") ")"   // 'base with (field = v)'
                                                                      // ONE form: records, structs, classes
```

**Construction is application (v0.3).** There is no dedicated `construct` production. A type name `T` in value position is its **constructor function**; `T(args)` and `T (args)` are ordinary applications (§5.5) that the elaborator lowers to allocation when the head statically resolves to a constructor (§8.2). Positional `T(e1, …, en)` applies the constructor to the tuple of fields (in declaration order); named `T(f = e, …)` applies it to the field record. Used bare, `T` is a first-class value: `xs.map Some`, `val mk = Node`, `Node a` (where `a`'s static type is the payload). The construction argument is therefore just an ordinary `paren_or_tuple` / `named_tuple_lit` — no special grammar. (Parent super-calls in a `<:` clause keep an explicit arg list, `super_arg`, §5.3.)

`update_expr` attaches at the postfix level inside the larger expression machinery; the parser admits `base with (…)` wherever a `postfix_expr` is followed by the `with` keyword and a `( named_arg, … )` group — and since `match` arms moved into arm-blocks, an expression-position `with` is **always** an update. (The v0.1 spread form `(..base, …)` and its `..` token are removed; `with` is the single update spelling for records, structs, and classes alike.)

**Bracket classification (normative, token-level).** Only `[` is whitespace-classified; `(` is uniform. Each is decided by **one token of lookbehind plus the intervening-whitespace flag** — no parser feedback, no semantic information:

| glued tightly after… | `[` means | `(` (tight **or** spaced) means |
|---|---|---|
| `UPPER_ID` / `LOWER_ID` (path/selector/callee tail) | type arguments (`map[Int]`, `T[..]`) | **application** to the parenthesized argument — construction when the head is a constructor (`Point(x=1)`), a curried-step call otherwise |
| `)` `]` or a literal | **parse error** — looks like indexing; insert a space | **application** to the parenthesized argument (`(f x)(y)`) |
| anything else (`=` `,` `(` `[` operators, keywords, statement start) — or whitespace before `[` | **array literal** | grouping / tuple / record (§5.7) |

Consequences: `val a=[1, 2, 3]` is an array literal (the `[` follows `=`); `map[Int] xs` type-applies; tight `xs[0]` is a type-argument parse that fails (`0` is not a type) — indexing sugar is reserved (§2.2), use `xs.get i` / `xs.set i v` / `xs.length` (§6.2). `Point(x = 1)` ≡ `Point (x = 1)` both construct; `f(x, y)` ≡ `f (x, y)` both apply `f` to the tuple `(x, y)` — *not* a parse error and *not* two arguments (write `f x y` for two). A mismatch (e.g. passing a tuple where a curried first arg was expected) is caught by the **typechecker**, as in OCaml. In *type* context every `[` is a type-argument block (types have no array literals); in *pattern* context `Some(x)` and `Some (x)` are equivalent.

An empty literal `[]` requires an expected type `Array[T]` (check mode); in synthesis position it is a compile error. A non-empty `array_lit` synthesizes `Array[T]` where every element checks against one element type `T`; in check mode against `Array[Object]`, primitive elements box (§6.8).

### 5.7 Disambiguating the leading `(`

`named_tuple_lit`, `paren_or_tuple`, and (in type context) the type forms all start with `(`. Resolution is **operational and bounded**:

- First two tokens are `LOWER_ID "="` → commit to **named-tuple / record-literal** parsing: one or more `,`-separated `named_arg`s, trailing comma permitted. `(x = 1)` is the one-field record literal — it is never a silently-typed assignment because bare `var`-assignment is forbidden inside `( )` grouping (§5.4).
- Otherwise → `paren_or_tuple`.

### 5.8 Patterns

```
pattern        ::= pattern_no_alt ( "|" pattern_no_alt )*   // alternatives only in match/try arms
pattern_no_alt ::= wildcard_pat | literal_pat | binding_pat
                |  ctor_pat | named_tuple_pat | tuple_pat | typed_pat
                |  "(" pattern ")"

wildcard_pat   ::= "_"
literal_pat    ::= literal | UNIT_LIT
binding_pat    ::= LOWER_ID ( "@" pattern_no_alt )?
typed_pat      ::= pattern_no_alt ":" type                  // type test: 'x: Cat', 'ex: DivByZero'

// ONE unified production for ADT variants AND nominal struct/class destructuring:
ctor_pat       ::= UPPER_ID ( "(" sepBy(pattern_field, ",") ")" )?
pattern_field  ::= ( LOWER_ID "=" )? pattern                // positional OR named, uniformly

named_tuple_pat::= "(" named_pat ( "," named_pat )* ","? ")" // structural record, arity >= 1
named_pat      ::= LOWER_ID "=" pattern
tuple_pat      ::= "(" pattern ( "," pattern )+ ")"          // positional tuple, arity >= 2
```

- `ctor_pat` is **one context-free production** for both ADT variants (`Some(x)`, `Node(l = l, r = r)`) and nominal struct/class destructuring. It accepts positional and named sub-patterns uniformly. **Mixing** positional and named within one `ctor_pat`, and choosing the right nominal type, are resolved in **typing**, not parsing — there is no type-directed parse fork. (Single-field positional vs multi-field named is a *style* recommendation, §9, not a grammar constraint.)
- A bare `LOWER_ID` is always a **binder**, never a constant comparison; to compare an existing value, match a literal or use a guard.
- Guards attach at `match_arm`/`try` level.

### 5.9 Types

```
type            ::= function_type
function_type   ::= union_type ( "->" function_type )?        // RIGHT-assoc
                 |  param_types "->" function_type            // '(A, B) -> C'
param_types     ::= "(" sepBy(type, ",") ")"                  // arrow domain = parenthesized type list (UNLABELED)

union_type      ::= intersection_type ( "|" intersection_type )*   // union looser than intersection
intersection_type ::= application_type ( "&" application_type )*

application_type ::= atom_type type_arg_block*
type_arg_block  ::= "[" sepBy1(type, ",") "]"

atom_type       ::= type_path | tuple_type | named_tuple_type | "(" type ")"
type_path       ::= UPPER_ID ( "." UPPER_ID )*                // qualified type name
                 |  LOWER_ID                                  // type variable in scope
tuple_type      ::= "(" type ( "," type )+ ")"                // arity >= 2
named_tuple_type::= "(" named_field_t ( "," named_field_t )* ","? ")"  // arity >= 1; (x: Int) is one-field
named_field_t   ::= LOWER_ID ":" type
```

**Type-level disambiguation (normative).** The four `(`-leading type forms are resolved by parsing the parenthesized group and then looking at what follows the matching `)` (this is **not** LL(1); the parser uses a backtracking gate / Pratt lookahead to the matching close `+1`):

- First two tokens are `LOWER_ID ":"` → `named_tuple_type` (arity ≥ 1; `(x: Int)` is the one-field record type, trailing comma permitted). **Arrow domains are unlabeled** (`param_types` holds bare types), so a labeled group is never an arrow domain.
- After the matching `)`, if `->` follows → the parenthesized contents are an **arrow domain** (`param_types`), e.g. `(A, B) -> C`, and the single-type case `(A) -> B` is a one-argument arrow distinct from grouping `(A)`.
- Multiple comma-separated bare types not followed by `->` → `tuple_type`.
- A single bare type not followed by `->` → grouping.

Right-associativity of `->` and the layering union-looser-than-intersection-looser-than-application are encoded by the production layering.

### 5.10 Worked parse checks (normative)

1. **Tight `.` tighter than application:** `a.f x.g y` ⇒ `(a.f) (x.g) y`.
2. **Chain selection at application precedence, left-assoc:** `3.add 4 .times 5 .neg` ⇒ call structure `((3.add 4).times 5).neg` (= −35), and `foo bar .m` ⇒ chain on `(foo bar)`.
3. **Application tighter than `+`:** `f x + y` ⇒ `(f x) + y`.
4. **Unbounded arg must be parenthesized:** `f (if c then a else b)` parses; `f if c then a else b` does not.
5. **One-field named tuple:** `(x = 1)` is a one-field record literal (assignment is forbidden inside `( )` grouping); `(x = 1,)` is the same with a permitted trailing comma.
6. **Closure vs block:** `{ x => x + 1 }` is a closure; `{ val a = 1; a }` is a block; `{ => e }` is a `Function0` closure.
7. **Update vs selection:** `base with (x = 2)` is functional update (records, structs, and classes alike); `base.x` is selection.
8. **Bracket classification:** `map[Int] xs` type-applies `map` then applies it to `xs`; `map [1, 2]` applies `map` to the array literal `[1, 2]`; `val a=[1, 2, 3]` is an array literal (the `[` follows `=`, not an identifier); `f(x, y)` ≡ `f (x, y)` applies `f` to the tuple `(x, y)` (one argument; write `f x y` for two).
9. **Newline termination:** `f x ⏎ (y)` is two statements (a `(`-led line never continues an application); `xs.filter p ⏎ .map f` is one chain (leading-`.` continuation, §3.4).
10. **Spacing around `.`:** `x.m` is tight selection on `x`; `x .m` is chain selection on the accumulated value to its left.
11. **Postfix match vs trailing closure:** `f x match { | A => 1 | B => 2 }` — the scrutinee is the application `f x`, then postfix `match`; `f x { y => e }` (no `match`) still passes a trailing closure.

### 5.11 Out of MVP scope (not in this grammar)

- User-defined effect handlers (only `throw`, `try … catch`).
- Row-polymorphic / generic record update (no syntax).
- Value-backed enums; value structs holding managed references.
- `break`/`continue`; non-local `return` from closures; user symbolic operators; tight indexing sugar `xs[i]`.
- Variance annotations and `:`-context-bounds on type parameters (use `using`).

---

## 6. Type System

Everything here is checked at compile time; Hi arrives at the backend **fully type-checked and pre-erased**. The emitted NIR carries no Hi-level union/intersection/generic information. NIR grounding comes from `nir/.../Types.scala`, `nir/.../Rt.scala`, and `tools/.../codegen/MemoryLayout.scala`.

### 6.1 Inference: Local / Bidirectional

Hi does **not** use Hindley–Milner. There is no global unification, no let-generalization, and no principal-type guarantee. Inference is Scala-3-style *local* and *bidirectional*; subtyping is discharged by **subsumption**.

- **Check mode** `Γ ⊢ e ⇐ T`: an expected type `T` is pushed into `e`. Used for: function arguments, the body of an annotated binding/function, both `if` branches, all `match`/`try` arms, annotated returns.
- **Synthesis mode** `Γ ⊢ e ⇒ S`: `S` is computed from `e` alone. Used for: a `match` scrutinee, the receiver of a (tight or chain) selection, the RHS of an un-annotated `val`/`var`, the head of an application.

The modes meet at subsumption: in check mode, synthesize `S`, then require `S <: T`.

#### Where annotations are required vs. inferred

| Position | Annotation | Rationale |
|---|---|---|
| Top-level / public `fun` parameter types | **Required** | Signatures are the inference boundary. |
| Top-level / public `fun` return type | **Required** | Each definition checkable in isolation; no cross-module body inference. |
| The receiver parameter `self` | **Exempt** (defaults to enclosing type; §5.3) | Receiver is structurally determined. |
| `struct` / `class` field types | **Required** | Fields define layout/ABI. |
| `trait` member signatures | **Required** | Interface contract. |
| `type` alias / ADT constructor argument types | **Required** | Define the nominal shape. |
| Local `val` / `var` | **Inferred** from RHS (synthesis), unless annotated. |
| Closure parameters | **Inferred** from the expected function type — or SAM-interface signature (§7.6) — in check mode; **required** otherwise. Bare `{ x => e }` in synthesis position is an error. |
| `using` parameters | **Required** type; the *value* is found by search. |
| Generic type arguments | **Inferred** at call sites where possible; may be supplied explicitly (`f[Int] x`). |

> **Judgment call (return-type annotations).** A top-level or public `fun` (including nullary getters) must annotate its return type. A *local* `fun` may omit it and synthesize from its body.

> **Judgment call (recursion).** Recursive and mutually-recursive top-level `fun`s check against their declared signatures without fixed-point inference. A local recursive `fun` with an omitted return type used in a result-typed position is a compile error ("recursive value/function needs result type").

#### What inference will not do

- No generalization: `val id = { x => x }` is an error (closure param needs a type); `val id: Int -> Int = { x => x }` checks.
- No cross-statement unification.
- No "best type" guarantee. When a join is needed (`if`/`match`/`try`), the **LUB** rules of §6.9 apply; if branches share no supertype but `Object`, that is the result.

### 6.2 Primitive types and NIR counterparts

| Hi type | NIR type | Width | Notes |
|---|---|---|---|
| `Bool` | `Type.Bool` | 1 | boolean type is named **`Bool`** (box class `java.lang.Boolean`) |
| `Byte` | `Type.Byte` | 8, signed | |
| `Short` | `Type.Short` | 16, signed | |
| `Char` | `Type.Char` | 16, unsigned | UTF-16 code unit |
| `Int` | `Type.Int` | 32, signed | default integer literal type |
| `Long` | `Type.Long` | 64, signed | |
| `Float` | `Type.Float` | 32 | IEEE-754 |
| `Double` | `Type.Double` | 64 | default floating literal type |
| `Size` | `Type.Size` | word | platform-word signed size |
| `Ptr` | `Type.Ptr` | word | raw pointer; **not** GC-managed |
| `CString` | `Type.Ptr` | word | libc-interop alias for `Ptr[Byte]`; the type of `c"..."` |
| `Unit` | `Type.Unit` | — | `RefKind` (boxed unit `scala.runtime.BoxedUnit`); value written `()` |
| `Nothing` | `Type.Nothing` | — | bottom; `Nothing <: T` for all `T` |

- **No implicit numeric widening** (`Int → Long`, etc.); conversions are explicit methods (`n.toLong`). *Judgment call*: kept minimal to avoid coercion complexity local inference handles poorly.
- **Bitwise & shift methods** — All integer primitive types have built-in methods `.and`, `.or`, `.xor`, `.shl`, `.shr`, `.ushr`, `.not` that lower directly to NIR `Op.Bin` nodes (§7.15). These are compiler intrinsics, always available without an import. Following the general chain-selection idiom (§4.2), they are used in operator style: `34 .or 1`, `flags .and mask`, `x .shl 2`.
- **Boxing**: a primitive used where a reference type is expected (stored in `Array[Object]`, a union, or a generic at a reference type) is boxed to its canonical box class (`java.lang.Integer`, …) per the NIR box/unbox tables; inserted by the elaborator.
- `String` is the reference type `java.lang.String` (4 fields `value, offset, count, cachedHashCode`). `Array[T]` is `Type.Array(elemTy)`; arrays are built with literals `[e1, …, en]` (§5.6) or runtime allocation, and accessed with the built-in members `xs.get i`, `xs.set i v`, `xs.length` (lowering to `Op.Arrayload`/`Op.Arraystore`/`Op.Arraylength`, §8.9). Indexing sugar `xs[i]` is reserved (§2.2).
- **`Null` and nullability (surface decision).** MVP reference types are **nullable** (mirroring the backend `Type.Ref.nullable`). `null` arises only from interop/runtime, has type `Null <: R` for every reference type `R`, and dereferencing it throws `NullPointerException` (§7).

### 6.3 Struct (value) vs. Class (reference)

#### `struct` — value type

```hi
struct Vec2 (x: Double, y: Double)
```

- **Semantics:** copied by value; no identity; no inheritance; not a subtype of anything, and nothing is a subtype of it. Equality is structural (all fields equal).
- **NIR lowering:** toward an unboxed aggregate `Type.StructValue(...)`.
- **MVP GC-safety restriction (load-bearing).** A `struct` field's declared (unboxed) static type may be **only** a primitive, `Ptr`, or another all-primitive/`Ptr` struct. A managed reference — `class`, `String`, `Array`, ADT, structural record, trait, union/intersection, **or a box class** (e.g. `java.lang.Integer`, `scala.scalanative.unsafe.Ptr`) — is a **compile error** at the declaration site.

  *Why (verified backend behavior).* The Immix/Commix GC scans heap-object fields precisely using the RTTI reference-offset bitmap. As implemented in `MemoryLayout.referenceFieldsOffsets`, that bitmap records offsets of **top-level `RefKind` fields** plus exactly one synthetic shape — `StructValue(RefKind :: ArrayValue(Byte, n) :: Nil)`, a single ref + alignment padding emitted by `ofAlignedFields`. It does **not** descend into a general multi-field `StructValue` to record interior reference offsets. A managed reference buried in a by-value struct field of a heap object would therefore be invisible to the precise scan and could be collected prematurely. (The stack is scanned conservatively, so *stack-only* value structs with refs would be safe but require per-shape RTTI — deferred.) Anything that must hold a managed reference must be a `class`.

  *Lift path (post-MVP, contained).* The markers (immix/commix `Marker.c`) walk an arbitrary `-1`-terminated offset array from RTTI, so this restriction can be removed without touching GC C code: make `referenceFieldsOffsets` recurse into nested `StructValue` fields and emit `outer + inner` offsets for interior references. Alternatively the frontend can flatten ref-carrying structs into their enclosing class/record (SROA — semantics-preserving because structs have no identity or interior pointers). `Array[struct-with-refs]` remains deferred either way (typed-array classes carry no per-element ref maps).

- **Construction:** constructor application `Vec2(x = 1.0, y = 2.0)` (§7.7 — `Vec2` is a first-class constructor function). **Functional update:** `base with (x = 3.0)` (§6.6).

#### `class` — reference type

```hi
class Counter (var n: Int) { ... }
class Box (value: Object)        // legal: managed ref lives on the heap
```

- **Semantics:** heap-allocated; has identity; single-class inheritance + trait implementation; participates in `<:`.
- **NIR lowering:** `Defn.Class` with `Defn.Var` fields and `Defn.Define`/`Defn.Declare` methods; instances are `Type.Ref`. Every class roots at `java.lang.Object`. It gets a precise reference-offset bitmap covering its `RefKind` fields, so it may freely hold managed references.
- **Construction:** constructor application `Counter(n = 0)` (§7.7). **Functional update:** `base with (field = v)` produces a *new* instance (§6.6).

#### Subtyping `<:` (reference types only)

- `C <: D` if `class C` extends `class D`; `C <: T` / `Tr <: T` if `C`/`Tr` (transitively) implements trait `T`.
- `Null <: R` for every reference type `R`; `Nothing <: T` for all `T`; every reference type `<: Object`.
- **Structs do not participate:** for `struct S`, neither `S <: T` nor `T <: S` for any `T ≠ S`.

Subsumption is the only consumer of `<:`.

#### Identity: nominal vs. structural

- `struct` and `class` are **nominal**: two declarations with identical fields are distinct, incompatible types.
- Named-tuple record *types* are **structural** (§6.5): `(x: Int, y: Int)` ≡ `(y: Int, x: Int)` (field-name set + per-field type, order-independent; canonical sorted layout).

### 6.4 Structural records

Type `(x: Int, y: Int)`; literal `(x = 1, y = 2)`; one-field forms `(x = 1)` / `(x: Int)` (trailing comma permitted, §5.7).

- **Identity** = set of `(field-name, field-type)` pairs, order-independent. **No width subtyping, no row polymorphism:** `(x: Int, y: Int)` is *not* a subtype of `(x: Int)`.
- **NIR lowering:** records are **reference types** (they may hold managed refs and so are heap-backed). Each distinct canonical shape lowers to a deterministically-named heap **class** with fields sorted by name (§8.6), giving it a precise GC bitmap.
- **Field access:** `e.f` is well-typed iff `e` synthesizes a record type containing `f`; the result is that field's type. Selecting an absent field is a compile error.

### 6.5 Tuples as `_1`/`_2` records

```
(a, b, c)            ≡   (_1 = a, _2 = b, _3 = c)
(A, B, C) as a type  ≡   (_1: A, _2: B, _3: C)
```

Tuples are structural records with synthesized names `_1.._n`; all record rules apply. Element access is field access (`t._1`). `()` is the `Unit` value. A one-element tuple is not a distinct type (`(a)` is grouping); the one-field *named* record `(x = a)` is the arity-1 form.

### 6.6 Type-directed functional update

Sugar resolved at compile time, rebuilding a record/struct/class from a base plus overrides. One form for all three: `base with (field = v, …)`.

**Typing rule (base's full static type must be known):**

```
Γ ⊢ base ⇒ R    R is a record / nominal struct / class with fields { f1:T1, …, fn:Tn }
overrides = { g1 = v1, …, gk = vk }     { g1,…,gk } ⊆ { f1,…,fn }
for each gi:  Γ ⊢ vi ⇐ type-of(gi in R)
──────────────────────────────────────────────────────────────  (update)
Γ ⊢ base with (g1 = v1, …)  ⇒  R
```

- Result type is exactly `R`; adding a field not in `R` is an error.
- Desugars to a **full rebuild** copying every unchanged field; for a `class` it constructs a new instance. Override is last-wins.
- **No row polymorphism:** a generic "update any record preserving the rest" function is not expressible.
- **Distinct from `&`** (§6.9): update is last-wins override over one known base; `&` merges field sets and forbids name collisions.

### 6.7 ADTs

```hi
type Option[A] = | Some(A) | None
type Tree[A] = | Leaf | Node(left: Tree[A], value: A, right: Tree[A])
```

- **Lowering:** a sealed base `class` (the type name) plus one case subclass per variant. Each case carries its payload as fields. A **nullary variant (`None`, `Leaf`) lowers to a singleton module instance** (§8.3). ADTs are reference types (may hold managed refs; may not appear inside a `struct`).
- **Payload convention (normative):** positional payload `C(T, …)` → positional construction/match `C(e, …)` / `C(p, …)`, with implicit fields `_1..._n`. Named payload `C(f: T, …)` → named `C(f = e, …)` / `C(f = p, …)`.
- **`match` lowering:** a class-id range-test decision tree (§8.3), not a tag field.
- **Exhaustiveness (compile error).** Because the base is sealed, a `match` over an ADT that does not cover every variant (with no catch-all `_`/binder arm) is a **compile error**, as is a redundant/unreachable arm. (Runtime `MatchError` arises only for genuinely non-sealed/open scrutinees — see §7.)
- **Generics** are checked nominally and erased (§6.10).

### 6.8 Generics, bounds, and erasure

#### Generics

- Declared with `[A, B, …]` on `fun`, `class`, `struct` (subject to §6.3), `trait`, `type`/ADT.
- Type application is explicit (`List[Int]`) or inferred at call sites from argument types.
- **Bounds:** **upper bounds only** (`[A <: Bound]`). **No lower bounds, no variance (invariant), no view/context bounds via `:`.** Contextual requirements use explicit `using` parameters (§6.11). A type argument is checked against its bound by `<:` at instantiation.

#### Erasure (Hi arrives pre-erased)

NIR has no generics/union/intersection. After full type checking:

- A type parameter `A` erases to its **upper bound** if present, else `Object`. `List[Int]` and `List[String]` share one NIR class; `A`-typed fields become `Object` fields.
- A primitive at an erased reference position is **boxed** at the boundary and unboxed on exit.
- `A | B` erases to `lub(A, B)`; `A & B` to its merged representative (§6.9).
- `Array[T]` is the element-type-preserving exception: `Type.Array(erase(T))`, with primitive element types selecting the typed array classes (`IntArray`, `DoubleArray`, …) rather than boxing.
- Casts inserted by erasure are checked-cast NIR ops where a runtime guarantee is needed.

All generic/union/intersection guarantees are compile-time only.

### 6.9 Union `|` and Intersection `&`

Both are **frontend-only and erased** (like Scala 3), and **restricted to reference types** (classes/traits/structural records). Using `|`/`&` on a `struct` or primitive is a compile error.

#### Intersection `A & B`

- **Classes/traits:** values that are both `A` and `B`; `x : A & B` satisfies `x <: A` and `x <: B`. Chiefly "a class implementing several traits."
- **Structural records:** the **field-set union**: `(x: Int) & (y: String) ≡ (x: Int, y: String)`. A field-name collision with **differing** types is a compile error; identical-typed same-name fields merge once. (Contrast update §6.6, which is last-wins.)
- **Erased representative:** for record intersections, the merged canonical class; for class/trait, the most specific common carrier (class component, with extra trait memberships available for static checking, dispatched via itable at use sites).

#### Union `A | B`

- Values that are either `A` or `B`; `A <: A|B` and `B <: A|B`.
- Introduced by subsumption; **eliminated by `match`** (no implicit downcast).
- **Branch joins do not fabricate unions.** Where a single type must be synthesized from branches (`if`/`match`/`try`), Hi computes the **LUB**:
  - `lub(Nothing, T) = T`; `lub(T, T) = T`.
  - For two reference types, the most specific common supertype via `parent`/`traits`; else `Object`. The LUB is a **single nominal supertype**, not a fabricated `A | B`.
  - **Mixing a value type and a reference type in a branch join is a compile error** (no implicit boxing at joins). Two value-struct branches must have identical value type.
  - An explicit `A | B` arises only when the programmer writes it as an expected type. Where a union value is fed to an expected `T`, the checker requires `A <: T` *and* `B <: T`.
- **Erased representative:** `A | B` erases to `lub(A, B)`; member discrimination at runtime is via the underlying objects' class tags in `match`.

### 6.10 Traits, extensions, given/using, and `.m` resolution

Hi has **three** abstraction mechanisms, each with **exactly one** dispatch semantics — they do not overlap, and there is no `impl` keyword (its Rust-borrowed role is split between `<:` and `given`, below). A fourth, deferred, mechanism (`dyn Trait`) recovers retroactive *dynamic* dispatch; see the note at the end of this section.

#### `trait` — interface, optionally with default methods

```hi
trait Show[A] { fun show (self: A): String }            // one abstract member
trait Ord[A] {
  fun lt  (self: A) (o: A): Bool                        // abstract requirement (no body)
  fun gte (self: A) (o: A): Bool = !(self .lt o)        // default method (concrete body)
}
```

A `trait` is a reference-type interface → `Defn.Trait`. A **bodiless** member is abstract → `Defn.Declare`; a member **with a body** is a *default method* → `Defn.Define`, inherited by every implementor unless overridden (§5.3). Default methods give Swift "protocol-extension defaults" with **no** Swift-style dispatch split: a trait member dispatches the same way whether or not it has a default — dynamically through the itable when the receiver satisfies the trait *nominally* (`<:`), or through the dictionary when satisfied *retroactively* (`given`). Checked: the receiver's static type satisfies the trait and signatures match.

**Two ways a type satisfies a trait:**

- **Nominally, at definition (`<:`) — dynamic dispatch.** A `class`/`object` you own lists its traits in its `<:` clause (§5.3); methods install into the vtable/itable and a `List[Show]` dispatches per element at runtime. This is the path for types you control.
- **Retroactively (`given`) — dictionary.** For a type you do *not* own (`Int`, `String`, a foreign class) or a value type/primitive (no header to dispatch through), a `given` supplies the instance as a compile-time-resolved dictionary. Uniform across reference, value, and primitive types, and the **only** mechanism that expresses *conditional* instances.

#### `extension (x: T) { ... }` — statically resolved sugar

```hi
extension (n: Int) { fun double (): Int = n + n }
```

An extension adds `.method` / ` .method` (chain) syntax to a type **without** declaring any trait conformance — pure additive sugar, **always statically resolved** from the receiver's static type and in-scope extensions, no vtable/itable entry, no dynamic dispatch. `x.double` compiles to a direct static call (§8.7). Keeping conformance *out* of `extension` (unlike Swift, where `extension T: P {}` both adds methods and declares conformance) is what avoids Swift's "is this call static or dynamic?" hazard. Extension bodies hold only `fun` members (§5.3); conditional/typeclass-keyed methods are deferred (`extension … using`, §2.2) — surface them as trait members instead.

#### `given` / `using` — dictionary passing

```hi
given Show[Int] { fun show (self: Int): String = ... }       // instance with members: braced form
given showList[A] (using s: Show[A]): Show[List[A]] = ...     // conditional instance (head with `using`)
fun render[A] (x: A) (using s: Show[A]): String = s.show x
render 42                              // s found by given search
```

`using` parameters are implicit value parameters filled by **contextual search** over in-scope `given`s. The found value is a **dictionary** passed **explicitly at the NIR level** (no runtime resolution). Exactly one most-specific candidate is required; ambiguous-given, no-given-found, and divergent recursive search are all compile errors. A `given_head` carrying its own `using` parameters expresses a *conditional* instance (`Show[List[A]]` given `Show[A]`) — the capability `impl` could not express. After resolution the dictionary is an ordinary argument appended to the flattened NIR parameter list (§8.8).

**Coherence (closed-world).** At most **one** `given` may exist per `(Trait, type-head)` across the closed-world link; a second is a compile error. Because Hi links under the closed-world assumption (§1.5), this global coherence is *checkable* at link time — recovering Rust/Haskell-style coherence (the one real guarantee `impl` provided) on the `given` mechanism, and preserving §1.5's determinism. An orphan `given` (where neither the trait nor the type head is declared in the current package) is reported so instances stay discoverable.

#### Unified `.m` resolution (normative)

A selection `recv.m` or chain step `recv .m` (§4.2) resolves `m` against the receiver's static type in **this fixed order**; a lower tier fires only when every higher tier misses, and ambiguity *within* a tier is a compile error:

1. an **intrinsic / itable member** declared on the receiver's type (including traits satisfied via `<:`, and built-in primitive methods §7.15);
2. an in-scope **`extension`** method on that type;
3. a **trait method witnessed by an in-scope `given`** for that type — i.e. `Show[Int]` in scope makes `show` callable as `3 .show`, lowering to a dictionary call.

This is the single rule behind §4.2's statement that `.m` steps and `a.f` selections "resolve as methods/extensions on the receiver's type": *methods* = tier 1, *extensions* = tier 2, *trait-via-given* = tier 3. Members beat extensions (the Scala 3 rule); nothing here is type-directed beyond the receiver's own static type.

> **Note (a trait as a type: nominal `Array[Show]` vs existential `Array[dyn Show]`).** Using a trait in type position has **two** meanings, kept as distinct types so one syntax never has two representations:
> - **`Array[Show]` — nominal subtyping (works today).** Holds references that conform via `<:` (`class Dog <: Show`); each carries its own itable, dispatch is dynamic, no box. `val xs: Array[Show] = [Dog(...), Cat(...)]` type-checks by subsumption. `3` cannot go here — `Int` is not a subtype of `Show`.
> - **`Array[dyn Show]` — existential (deferred, §2.2).** Each element is a box pairing the payload with its resolved `given` dictionary, so it admits *retroactive* instances (`given Show[Int]`): `val xs: Array[dyn Show] = [3, true, 7]`. Coercion into `dyn Show` is **implicit and type-directed** — driven by the expected type, no `as` cast and no general implicit conversion — exactly how Rust coerces to `dyn` and Swift to `any`. The cost (allocation + indirect dispatch) stays visible in the type, which is why Swift moved from an implicit `[Show]` existential to an explicit `any` and Hi keeps `dyn`.
>
> Tiers 2–3 above are **static**: `3 .show` via a `given` dispatches on the *static* receiver, as Haskell/Rust/Scala typeclasses do. The existential is the only path to per-element dynamic dispatch over a *foreign* type. Neither Rust nor Swift mutates the foreign type's own vtable; both attach an external dictionary at the existential boundary — the dictionary Hi already has. Until `dyn` ships, pair payload + dictionary by hand in a one-field wrapper `class` (worked example in `comparison.md`).

### 6.11 Summary of compile-time guarantees

- All expressions typed in check/synthesis mode; subsumption uses `<:`.
- Top-level/public `fun`s, fields, trait members, ctor arg types are annotated; `self` is exempt.
- `struct` fields are primitives/`Ptr`/nested all-primitive structs only (checked on the declared, unboxed type).
- Structural-record identity, field presence on `.`, type-preserving update over a fully-known base.
- ADT `match` exhaustiveness and non-redundancy (errors).
- `|`/`&` restricted to reference types; intersection field-collision is an error; joins resolved by checked `<:`/`lub`.
- Generic upper-bound checks, then full erasure before NIR.
- Trait coverage when a type satisfies a trait (nominal `<:` or `given`); closed-world `given` coherence (one per `(Trait, type-head)`); single-applicable extension resolution; unambiguous non-empty `given` search; the tiered `.m` resolution order (§6.10).

---

## 7. Expressions, Statements & Semantics

Hi is **expression-oriented**: every construct produces a value (possibly `Unit`). The statement forms are `val`/`var` bindings, nested `fun` declarations, and **bare expression statements** — any expression may stand as a newline-terminated statement (§3.4); a non-final statement's value is discarded, and a block's final expression statement supplies its value. `return` exits the enclosing `fun` early (§7.13); `while`/`for` are the loop forms (§7.12). Evaluation is **call-by-value** and **strictly left-to-right** unless a construct short-circuits (`if`, `match`, `&&`/`||`). The backend's NIR is a strict, ordered SSA form; Hi performs no reordering of effectful subexpressions.

### 7.1 Evaluation model

- **Strictness:** every operand is fully evaluated before the compound's operation; no lazy/by-name params.
- **Order:** source order, left to right. For `f a b`: `f`, then `a`, then `b`, then the call. For `recv.m arg`: `recv`, then `arg`, then dispatch.
- **Exceptions propagate** by unwinding: once a subexpression throws, no later sibling in the same compound is evaluated.
- **Determinism:** deterministic given program + inputs; nondeterminism comes only from explicit concurrency (OS threads).

### 7.2 `val` and `var` bindings

- **`val`** binds an immutable name; the RHS is evaluated once when control reaches it. A `val`'s left side may be an irrefutable pattern; a refutable pattern is a **compile error** (use `match`).
- **`var`** binds a mutable cell; reassignment `name = expr` has type `Unit`. **`var` is local-only** in the MVP: object/module-level mutable `var` fields are deferred (model module state as a `class` with a `var` field). Class/struct **fields** may be `var`. Local `var` lowers to an SSA `Op.Var` slot; a mutable field lowers to `Op.Fieldstore`.

Immutability is *binding-level*, not deep: a `val` to a mutable `class` still permits its fields to mutate. Value `struct`s are copied on bind/assign.

```hi
val n = 10
var acc = 0
acc = acc + n           // assignment is an ordinary expression statement (type Unit)
val (x, y) = (1, 2)     // irrefutable tuple pattern
```

Local annotations are optional (inference); an annotation, if present, is the expected type pushed into the RHS.

### 7.3 Blocks and sequencing

A block executes each statement in order; non-final expression statements are evaluated for effect, their values discarded (a compiler should warn when the discarded type is not `Unit`). The block's value is its **final expression statement's** value, else `Unit`. Names from `val`/`var`/`fun` are scoped to the block remainder and shadow outer names lexically. Statement boundaries are significant newlines or `;` (§3.4); indentation carries no meaning.

```hi
val r = {
  val a = compute ()
  log a                  // effectful statement (value discarded)
  a * 2                  // final expression = the block's value
}
```

### 7.4 `if`

`if c then a else b` evaluates `c : Bool`, then exactly one branch (short-circuit). The result type is the **LUB** of the two branch types (§6.9): mixing a value-type branch with a reference-type branch is a compile error (no implicit boxing at joins), and two value-struct branches must have identical value type. Lowering is `Inst.If` with both branches jumping to a join label carrying the result (SSA phi).

**One-armed `if` (`else` optional).** `else` may be omitted **iff the then-branch has type `Unit`**; `if c then e` is then exactly `if c then e else ()` and has type `Unit`. This is the conditional-effect / guard-clause form — `if cond then return`, `if missing then log "…"` — without the noise of an explicit `else ()`. To produce a **non-`Unit`** value, both branches are required, so the LUB above is always defined: a one-armed `if` is a statement-shaped effect, a two-armed `if` is a value. (Because `return`/`throw` have type `Nothing <: Unit`, `if a > 10 then return` and `if bad then throw e` are well-typed one-armed forms.)

> **Dangling `else` (normative).** An `else` binds to the **nearest** preceding `then` that lacks one. So `if a then if b then x else y` parses as `if a then (if b then x else y)` — the inner `if` takes the `else`, and the outer is one-armed (requiring its then-branch, the inner `if`, to be `Unit`). This is decided by one token of lookahead after the then-branch (peek for `else`); no backtracking, consistent with §1.5's bounded-lookahead rule.

```hi
val sign = if n < 0 then -1 else if n > 0 then 1 else 0   // two-armed: value (LUB Int)
if missing then log "not found"                           // one-armed: effect (Unit)
```

### 7.5 `match`

Canonical (and only) form: postfix `e match { | pat => e2 | … }` — the scrutinee `e` precedes the `match` keyword, which precedes a braced **arm-block** (§5.4); the `{` opens on the same logical line as `match`. The scrutinee evaluates once, left-to-right before any arm. Arms are tried top to bottom; the first whose pattern matches (and whose optional guard, evaluated only after a structural match, is `true`) is selected. The result type is the LUB of all arm bodies (subject to the §7.4 join rules).

| Pattern | Matches | Binds |
|---|---|---|
| `_` | anything | nothing |
| `x` (lowercase) | anything | `x` |
| literal | structural equality | nothing |
| `Ctor(p…)` / `Ctor` | an ADT variant / nominal type | sub-patterns |
| `(p1, p2, …)` | a tuple | sub-patterns |
| `(x = p1, …)` | a structural record by field name | sub-patterns |
| `T(field = p, …)` | a nominal struct/class by type + fields | sub-patterns |
| `p : T` (typed) | `p` and runtime type `<: T` (reference types) | `p`'s bindings |
| `p1 \| p2` | either; both sides bind the same names/types | shared names |

A bare lowercase identifier is always a **binder**. A name may not be bound twice in one pattern. **Sealed-ADT exhaustiveness is a compile error** if any variant is uncovered with no catch-all (§6.7). A genuinely **non-sealed/open** scrutinee (e.g. a `class`/trait match without a catch-all) may fall through at runtime to throw `MatchError` (a `Throwable` from the reused runtime). ADT matches lower to a class-id range-test decision tree (§8.3); literal patterns to `Inst.If`/`Op.Comp`.

```hi
type Shape = | Circle(Double) | Rect(w: Double, h: Double)

fun area (s: Shape): Double =
  s match {
  | Circle r            => 3.14159 * r * r
  | Rect (w = w, h = h) => w * h
  }
```

### 7.6 Closures

Forms: `{ x => e }` (single), `{ (x, y) => e }` / `{ (x: Int, y: String) => e }` (parenthesized list, types optional), `{ => e }` (nullary). **`fun` is never used for lambdas.** A closure captures free names lexically (`val`/value-`struct` by value, `class` by reference). Evaluating a closure literal produces a first-class function value immediately; the body runs on each application.

**Multi-param closures are UNCURRIED.** `{ (x, y) => e }` has type `(A, B) -> R`, takes both arguments at once, and lowers to one `scala.FunctionN`, *not* `{ x => { y => e } }`. It is **applied with a tuple** — `add (1, 2)` — consistent with the v0.3 application model (juxtaposition curries, parens build one tuple/record argument, §7.7). *Justification:* it matches Scala Native's closure runtime 1:1, is what SAM conversion needs (Java functional interfaces are uncurried), and keeps closure types aligned with how they are applied. A user who wants currying writes a named `fun` or nests closures.

```hi
val add  = { (x, y) => x + y }    // (Int, Int) -> Int, applied as: add (1, 2)
val inc  = { x => x + 1 }         // Int -> Int,        applied as: inc 41
val pred = { (x: Int) => x > 0 }
```

Argument count must match arity; supplying fewer arguments to a closure value is a type error (closure values are not auto-curried). Partial application is a property of named `fun`s (§7.7).

**SAM conversion (in MVP).** In check mode against a **trait/interface type with exactly one abstract method** — including javalib functional interfaces such as `java.lang.Runnable` or `java.util.Comparator` — a closure synthesizes an anonymous class implementing that interface: the closure's parameters and body check against the SAM's signature, and the lowering (§8.1) is identical to the `FunctionN` case except that the synthesized class implements the target interface. In synthesis position a closure still yields a `scala.FunctionN`. `return` inside the body remains illegal (§7.13).

```hi
val task: Runnable = { => doWork () }     // closure checked against a SAM interface
```

### 7.7 Whitespace application; currying & eta-expansion

Application is juxtaposition: `f x y`. Only **bounded** arguments may appear unparenthesized (§4.3); unbounded args must be parenthesized. `f x + y` ≡ `(f x) + y`; `f x.g` ≡ `f (x.g)` (`.` is tighter). Evaluation order: callee, then args left-to-right, then call.

**Juxtaposition curries; parentheses build one argument (v0.3).** A spine `f a b` is curried application — one argument at a time — so it partially applies (named `fun`s) and pairs with the right-associative arrow `A -> B -> R`. A parenthesized `f (a, b)` passes a **single tuple** argument `(a, b)`; `f(a, b)` (tight) means the same. So `f a b` (two args) and `f (a, b)` (one tuple) are different, exactly as in OCaml. This is also how uncurried closures and SAMs are applied (`g (a, b)`) and how constructors are called (`Point(x = 1, y = 2)` applies the constructor to its field record).

**Constructors are first-class functions.** A type name `T` denotes its constructor, a value of type `payload -> T` (the payload is the positional field tuple or the named field record). Hence `xs.map Some`, `val mk = Node`, and `Node a` (with `a` the payload value) all work; applied directly (`Some x`, `Point(x=1)`) it lowers straight to allocation, and used as a bare value it eta-expands like any under-applied function (§8.1–§8.2). Nullary variants (`None`, `Leaf`) are singleton *values*, not functions.

**Currying & saturation.** A named `fun` may declare multiple parameter lists; `f` has type `A -> B -> R`.

- A **saturated** call lowers to a single flat NIR method call (`Op.Call`, all args one list). No intermediate closures.
- An **under-applied** call — fewer lists than declared, including the bare selection `recv.m` (for a **non-nullary** `m`) — **eta-expands** to a closure (a `scala.FunctionN`) capturing supplied args/receiver, whose later application invokes the underlying flat method.
- A **nullary** method/getter (zero remaining lists) is always saturated and invoked by selection (`x.neg`, `x .neg`); it never eta-expands and has no `()` spelling.

```hi
fun add (a: Int) (b: Int): Int = a + b
val s   = add 3 4        // saturated -> single flat call, 7
val inc = add 1          // under-applied -> closure (Int -> Int)
val v   = inc 41         // 42
```

### 7.8 Chain selection ` .m`

Chain selection — a whitespace-preceded `.` — is the fluent method-chain form at **application precedence**, **left-associative**, sharing one level with whitespace application (§4.2). In `lhs .m a b`, the fully-evaluated `lhs` becomes the **receiver** of method/extension `m`, then trailing bounded args apply; `m` resolves on the receiver's type, never as a free function. Evaluation: leftmost `lhs` first; each ` .m args` step evaluates args left-to-right and dispatches; the result becomes the next receiver. A leading `.` on a new line continues the chain (§3.4 rule 3).

```
3.add 4 .times 5 .neg   ≡ call structure ((3.add 4).times 5).neg = -35
```

Each step lowers to an `Op.Method` dispatch (class method) or a direct static `Op.Call` (extension), followed by argument application.

### 7.9 Field access and selection via `.`

`.` is tightest (§4). `e.x` evaluates `e`, then selects:

- a **field** → `Op.Fieldload` (class/record) or `Op.Extract` (value struct);
- a **nullary** method/extension → invoked immediately;
- a **non-nullary** method/extension → a *selection yielding a callable* (`a.f x.g y` ≡ `(a.f) (x.g) y`); the bare selection `recv.m` eta-expands to a closure (§7.7).

A field/method access on `null` throws `NullPointerException`.

### 7.10 Construction and functional update

**Construction.** A type name is a constructor function (§7.7), applied like any function: nominal `struct`/`class`/variant via `T(field = v, …)` (named, → field record) or `T(e, …)` (positional, → field tuple); structural records via `(field = v, …)` (one-field `(field = v)`). All fields must be supplied (no partial records). Initializers evaluate left-to-right, then allocation: value struct → unboxed aggregate (`Op.Insert`); class → `Op.Classalloc` + field stores; structural record → deterministic canonical heap class. Because construction is application, a constructed value used as a juxtaposed argument or selected from needs parentheses / a chain dot — `f (Point(x=1))`, `Point(x=1) .x` (§4.2–§4.3).

**Functional update** (type-directed sugar over a base of known static type): `base with (field = v, …)` — one form for records, structs, and classes. It **desugars to a full rebuild**: evaluate `base` once, then construct a fresh value of `base`'s static type, copying every unchanged field and applying overrides (last-wins). The static type is exactly `base`'s; no fields added/removed. **No row polymorphism**, so a generic "update any record" function is not expressible. Update is **distinct from `&`** (last-wins override vs. field-set merge with collision forbidden). The base is not mutated (value semantics for structs; a fresh object for records/classes).

```hi
val p2 = p with (y = 9)            // Point(x = 1, y = 9)
val r2 = r with (x = 10, z = 30)   // (x = 10, y = 2, z = 30)
```

### 7.11 Exceptions: `throw e` and `try e catch { | P => h }`

The MVP has no algebraic effects; error handling is exceptions only.

> **Judgment call (`catch` + braced arm-block).** v0.1 spelled the handler `try e with | …`; v0.2 uses **`catch`** followed by a braced arm-block (§5.4), like `match`. Besides familiarity (Scala/Java), this removes a real parse collision — `try base with (x = 1) …` needs no lookahead to distinguish a handler from a postfix update — and the braces make handler ownership explicit when `try`s nest inside `match` arms (and vice versa). `with` is reserved for functional update only (parent lists use commas, §5.3).

**`throw e`.** `e` must statically be `<: Throwable`. `throw e` raises the value, unwinding to the nearest dynamically-enclosing handler. As an expression it has type `Nothing` (assignable anywhere). Construction uses `T(...)` (no `new`): `throw IllegalArgumentException("negative")`. Lowering: `Inst.Throw(value, unwind)`.

```hi
fun checked (n: Int): Int =
  if n >= 0 then n else throw IllegalArgumentException("negative")
```

**`try e catch { | P => h … }`.** Evaluate `e`. On normal completion its value is the result. If `e` throws `v <: Throwable`, handler clauses are tried top to bottom; each `| P => h` matches `v` (typically a typed pattern `ex : SomeException`, but any `match` pattern form is allowed). The first match binds and evaluates `h`; its value is the result. If none match, `v` re-propagates. The result type is the LUB of `e` and all handler bodies (subject to §7.4 join rules). **No `finally` in the MVP.** A clause pattern whose type is not `<: Throwable` is a compile error.

```hi
val v =
  try parse input
  catch {
  | _ : NumberFormatException => 0
  | e : RuntimeException      => { log e; -1 }
  }
```

Lowering uses NIR landing pads (`Next.Unwind`), dispatching the caught value through the clause patterns exactly as a `match` (type/structural tests via `Op.Is`/`Op.As`), re-throwing if no clause matches. Reuses the existing exception machinery.

### 7.12 Loops: `while` and `for`

- **`while c do e`** evaluates `c : Bool`; while it is `true`, evaluates `e` (value discarded) and re-tests. Result type `Unit`. Lowering: a header block testing `c` (`Inst.If`) and a body block ending in a back-edge `Inst.Jump` to the header; mutable locals are `Op.Var` slots (§8.1), so no phi-threading is required.
- **`for x in e do body`** is **pure sugar**, desugared before typing to `e.foreach { x => body }`. It therefore works for any receiver whose type has a `foreach` method or extension of type `(T -> Unit) -> Unit` — no collections library required. Result type `Unit`. Multiple generators, guards, pattern binders, and `for … yield` are deferred to the collections milestone (§2.2).
- **`break`/`continue` do not exist** in the MVP (keywords reserved, §3.5); exit early with `return`, a flag `var`, or restructuring.
- **No tail-call guarantee.** Hi does not guarantee tail-call elimination (Interflow may inline or optimize, but it is not a language guarantee). Use `while`/`for` for unbounded iteration; recursion is for naturally tree-shaped or bounded-depth structure.

```hi
fun sumTo (n: Int): Int = {
  var i = 0
  var acc = 0
  while i <= n do {
    acc = acc + i
    i = i + 1
  }
  acc
}
```

### 7.13 `return` — early exit from the enclosing function

`return e` (or bare `return`, which returns `()`) immediately exits the **innermost enclosing `fun`** with value `e`, checked against that function's declared or inferred result type. As an expression, `return e` has type `Nothing`, so it composes in branches: `if n < 0 then return -1 else n` checks at type `Int`.

- `return` exits a *function*, never just a block; a block's value is its final expression (§7.3), so tail-position `return` is legal but redundant.
- **Closure boundary:** a `return` inside a closure body is a compile error in the MVP (non-local return is deferred, §2.2). Closures yield their body value.
- Statements after an unconditional `return` are an "unreachable code" compile error (§5.4).
- Lowering: `Inst.Ret(v)` at the point of occurrence — NIR functions may carry multiple return blocks — with no unwinding interaction (`try` has no `finally` in the MVP).

```hi
fun indexOf (xs: Array[Int]) (x: Int): Int = {
  var i = 0
  while i < xs.length do {
    if xs.get i == x then return i              // one-armed guard clause (§7.4)
    i = i + 1
  }
  -1
}
```

### 7.14 Evaluation-order summary (normative)

| Construct | Order |
|---|---|
| Block | statements top-to-bottom; value = final expression statement, else `Unit` |
| `val`/`var` | RHS once, eagerly, at the binding point |
| `if c then a else b` | `c`, then one branch (short-circuit) |
| `while c do e` | `c`; if `true`: `e`, then re-test (loop) |
| `for x in e do b` | desugars to `e.foreach { x => b }` |
| `return e` | `e`, then exit the enclosing `fun` |
| `e match { … }` | `e` once; arms top-to-bottom; guard after structural match |
| `f a b` | `f`, then `a`, then `b`, then call |
| `recv.m args` | `recv`, then args L-to-R, then dispatch |
| `lhs .m args` | `lhs`, then args L-to-R, then dispatch; result is next receiver |
| construction (constructor application) / update | base (if any), then inits L-to-R, then build |
| `throw e` | `e`, then unwind (no later siblings) |
| `try e catch { … }` | `e`; on throw, clauses top-to-bottom |

### 7.15 Built-in operator semantics (MVP)

- **Arithmetic** `+ - * / %` is defined on operands of one matching numeric primitive type (no implicit widening, §6.2). Integer arithmetic wraps (two's complement, JVM-style). Integer `/`/`%` with a zero divisor throws `ArithmeticException`: the backend's `Lower` already inserts the divisor check (`checkDivisionByZero` → `throwDivisionByZero`), so Hi inherits this with no frontend work. Float arithmetic is IEEE-754; `NaN`/`±Inf` propagate and nothing throws.
- **Relational** `< <= > >=` apply to numeric primitives and `Char` only; they do not chain (§4.1).
- **Equality `==`/`!=` (judgment call, interim).** On primitives: value comparison (`Op.Comp(Ieq/Feq)`). On value `struct`s: structural field-wise equality (§6.3). On reference types: **null-safe `equals` dispatch** — `a == b` lowers to "if `a` is the null reference, test whether `b` is too; otherwise `Op.Method`-dispatch `java.lang.Object.equals`" (the Scala/Kotlin rule). Reusing javalib means `String` compares by content and box classes by value for free; a class that does not override `equals` inherits identity comparison from `Object`. Auto-derivation of `equals`/`hashCode`/`toString` for classes/records/ADTs remains Open Decision §11.3. Comparing a value type against a reference type is a compile error; reference-*identity* comparison has no operator surface in the MVP.
- **Bitwise & shifts** (integer primitives). Provided as built-in chain-selection methods on all integer primitive types, each lowering directly to the corresponding NIR `Op.Bin` node:

  | Method | Operation | NIR `Op.Bin` | Example |
  |--------|-----------|--------------|---------|
  | `.and` | bitwise AND | `And` | `34 .and 1` → 0 |
  | `.or` | bitwise OR | `Or` | `34 .or 1` → 35 |
  | `.xor` | bitwise XOR | `Xor` | `34 .xor 1` → 35 |
  | `.shl` | left shift | `Shl` | `1 .shl 8` → 256 |
  | `.shr` | signed (arithmetic) right shift | `Shr` | `-1 .shr 1` → -1 |
  | `.ushr` | unsigned (logical) right shift | `Ushr` | `-1 .ushr 1` → `Int.MaxValue` |
  | `.not` | bitwise NOT (unary) | `Not` | `x .not` flips all bits |

  These methods are recognized by the compiler as intrinsics and are always available on all integer primitive types without an explicit import or extension definition. The `^` operator (level 7) remains available as the infix XOR form on `Int`/`Long`; `.xor` provides an equivalent method-based spelling consistent with `.and`/`.or` across all integer types.

---

## 8. Desugarings & NIR Lowering

This section specifies how **Hi core** (typed, desugared, name-resolved) lowers to **NIR**. Hi lives in-repo as `scala.scalanative.hi` and **depends on `tools`**, constructing `nir.*` case classes directly. Grounding: `nir/.../{Defns,Types,Sig,Global,Ops,Insts,Rt}.scala`, the plugin's `NirGenExpr.scala#genClosure`/`genMatch`, `codegen/Generate.scala`, and `codegen/{MemoryLayout,RuntimeTypeInformation}.scala`.

### 8.0 The NIR target vocabulary

Per top-level type, Hi emits a `Seq[nir.Defn]`. The only `Defn` shapes Hi produces:

- `Defn.Class(attrs, name, parent, traits)` — reference types, ADT nodes, closures.
- `Defn.Module(attrs, name, parent, traits)` — `object`s, `Main$`, ADT nullary-variant singletons.
- `Defn.Trait(attrs, name, traits)` — Hi traits.
- `Defn.Var(attrs, name, ty, rhs)` — instance fields (zero-initialized).
- `Defn.Define(attrs, name, ty, insts, debug)` — methods (always a **single flat parameter list**).
- `Defn.Declare(attrs, name, ty)` — abstract members.
- `Defn.Const` — interned literal payloads (rare).

Member identity is `Global.Member(owner, sig)`. Signatures: `Sig.Method(id, types :+ ret, scope)`, `Sig.Ctor(argTypes)`, `Sig.Field(id, scope)`, `Sig.Clinit`.

> **Judgment call.** Hi method `id`s use the Hi source name verbatim; overload/extension disambiguation rides entirely on the mangled parameter-type list (no JVM-style name munging), safe because `Sig` equality is on the mangled string.

### 8.1 `fun` / currying → flat `Defn.Define` + eta-expanded closure

**Saturated calls.** A multi-list `fun f (a: Int) (b: Int): Int = a + b` lowers to **one** `Defn.Define` with all parameters flattened: `Type.Function(Seq(<self?>, Int, Int), Int)`. A saturated `f 1 2` lowers to a single `Op.Call`. Top-level `fun`s in an `object` are instance methods of `Foo$` (first param `Type.Ref(Foo$)`, reached via module load §8.5); genuinely static functions use `Sig.Scope.PublicStatic` and omit self.

**Under-application / eta-expansion** (mirroring `NirGenExpr.genClosure`):

1. Synthesize `Defn.Class(Attrs.None, <Owner>$$Lambda$<n>, parent = Some(Rt.Object.name), traits = Seq(scala.FunctionN, …any extra interface parent traits))`, where N is the closure arity (`Types.typeToName` maps `Function(args, _)` to `scala.Function${args.length}`).
2. For each captured free variable (and `recv` in the `recv.m` case), a `Defn.Var(... Sig.Field("capture"+i) ...)`.
3. A constructor `Defn.Define` (`Sig.Ctor(captureTypes)`) calling `Object`'s ctor then `Op.Fieldstore`-ing each capture.
4. The SAM body method (`apply`) loads captures via `Op.Fieldload` and tail-calls the underlying flat `Defn.Define`, supplying captured + missing args; erased generic slots use `Rt.Object` with `Op.Box`/`Op.Unbox` at the boundary (§8.9).
5. At the use site: `Op.Classalloc(<Lambda>, zone = None)` then an `Op.Call` of the ctor.

Later application of an eta-expanded value goes through the SAM `apply` (`Op.Method` virtual dispatch on the function trait), matching how Scala Native invokes `scala.FunctionN`.

> **Judgment call.** Hi closures `{ (x, y) => e }` lower to a single `scala.FunctionN` SAM (not nested `Function1`s); named `fun` currying still flattens to one method.

**Blocks, expression statements, `return`, and loops.** A block lowers to a straight-line sequence of NIR instructions in textual order; a non-final expression statement is evaluated and its result discarded (no extra cost). `val`/`var` bindings introduce SSA locals (`var` becomes a mutable `Op.Var` slot). The block's value is its final expression statement's value (else `Unit`), feeding the enclosing `Inst.Ret` or the join-point SSA value. **`return e`** lowers to `Inst.Ret(v)` at the point of occurrence — NIR functions may carry multiple return blocks; there is no cleanup interaction because the MVP has no `finally`. **`while`** lowers to a header block (`Inst.If` on the condition) and a body block ending in a back-edge `Inst.Jump` to the header; mutated locals live in `Op.Var` slots, so no phi-threading is needed. **`for`** is desugared to a `foreach` call before lowering (§7.12).

**SAM conversion (§7.6).** When a closure is checked against a single-abstract-method trait `T`, step 1 above sets `traits = Seq(T)` (instead of `scala.FunctionN`) and step 4's body method takes `T`'s abstract-method signature; captures and the constructor are unchanged. This is the same shape dotty emits for Java functional interfaces — NIR needs nothing new — and it is what makes `Runnable`-taking javalib APIs (threads, executors, virtual threads) directly callable with Hi closures.

### 8.2 `struct` and `class`; the GC ref-offset rule

**`struct` → unboxed aggregate.** `struct S (a: Int, b: Double)` → `Type.StructValue(Seq(Int, Double))`; no `Defn.Class`, no header, no identity. Constructed via `Op.Insert`/`Val.StructValue`; fields read with `Op.Extract`. Field order is declaration order.

**MVP value-struct restriction (hard rule).** A `struct` may contain only primitives, `Ptr`, and nested all-primitive/`Ptr` structs (checked on each field's declared, unboxed type; box classes are rejected). *Grounding:* `MemoryLayout.referenceFieldsOffsets` collects offsets only for top-level `RefKind` fields plus the one synthetic shape `StructValue(RefKind :: ArrayValue(Byte, n) :: Nil)` (ref + alignment padding from `ofAlignedFields`); it does **not** descend into a general multi-field `StructValue`. `fieldOffsets` likewise treats a `StructValue` as one offset slot. A managed ref inside a general value struct that is a heap-object field is invisible to the precise scan and may be collected prematurely. Stack-only value structs with refs are deferred.

**`class` → `Defn.Class`.** `class C (x: Int, next: C)` lowers to:

- `Defn.Class(Attrs.None, C, parent = Some(<super or Rt.Object.name>), traits = <impl traits>)`. With no `<:`, the parent defaults to `Rt.Object.name`.
- One `Defn.Var(..., Val.Zero(ty))` per field (zero-initialized; ctor assigns real values).
- A constructor `Defn.Define` (`Sig.Ctor(paramTypes)`) that (a) calls the parent ctor on `self` — **the parent-ctor arguments are exactly the `parent` super-call args from the `<:` clause** (§5.3); for a bare-type parent or `Object` they are empty — then (b) `Op.Fieldstore`s each ctor parameter.
- Accessors: getter `Op.Fieldload`; for a `var` field a setter `Op.Fieldstore` (`val` fields emit no setter).

Instantiation `C(1, n)` → `Op.Classalloc(C, zone = None)` then a ctor `Op.Call`. Class fields are bare `nir.Type` slots, so the RTTI bitmap covers their managed references precisely — the reason managed refs must live in a `class`.

> **Constructor as a first-class value (v0.3).** Because `T` is a constructor function (§7.7), a **direct** application `C(1, n)` lowers straight to `Op.Classalloc` + ctor `Op.Call` (no closure). When `T` is used **bare** as a value (`xs.map Some`, `val mk = Node`), it eta-expands exactly like an under-applied `fun` (§8.1): a synthesized `scala.FunctionN` whose `apply` performs the `Op.Classalloc` + ctor. Positional `T(e1, …, en)` fills fields in declaration order; named `T(f = e, …)` by name; both must cover every field. Nullary variants stay singleton modules (§8.3), not functions.

> **Judgment call.** Hi `var` is in the MVP, minimal: mutable field → `Defn.Var` + setter; mutable local → `Op.Var`/`Op.Varstore`/`Op.Varload`. Object/module-level user `var` fields are rejected (§8.5).

### 8.3 ADTs → sealed `Defn.Class` hierarchy; `match` → class-id range-test decision tree

A sealed ADT `type Option[A] = | Some(A) | None` lowers to:

- A sealed/abstract base `Defn.Class(Attrs.None, Option, parent = Some(Rt.Object.name), traits = Nil)` (sealed marked via `Attrs` so the linker knows the closed set). **No synthetic `$tag` field.**
- One subclass per variant: `Defn.Class(... Option$Some, parent = Some(Option) ...)` with a payload field per ctor argument (`Some`'s `_1: A` erased to `Rt.Object`, §8.9) and a ctor; `Defn.Class(... Option$None ...)` for the nullary case.

> **Judgment call.** A nullary variant (`None`, `Leaf`) lowers to a **singleton module** `Option$None$` (reusing the module-accessor machinery, §8.5), mirroring Scala 3 `case object`s and avoiding per-use garbage.

**`e match { | Some x => a | None => b }`** lowers exactly the way the backend already discriminates sealed types — **not** via a tag field/integer switch. The plugin emits `Inst.Switch` *only* for literal/primitive-value scrutinees; constructor/sealed-type discrimination uses `Op.Is`/`Op.As` type tests, which `Lower` compiles to a **class-id range check** against the linker-assigned id interval in RTTI (`idRangeUntil`). Therefore:

- For each non-default arm, emit `Op.Is(Type.Ref(Option$Some), e)` (a single class-id range comparison over the sealed hierarchy's contiguous id range), branch with `Inst.If`.
- On the matched branch, `Op.As(Type.Ref(Option$Some), e)` then `Op.Fieldload` to bind payload fields.
- Nested patterns produce a **decision tree** (sequential type tests / `Op.Comp` equalities); literal patterns compile to `Inst.If`/`Op.Comp(Comp.Ieq, …)` (and a primitive-value scrutinee may use `Inst.Switch` on the value itself).
- A non-exhaustive sealed `match` is a front-end error; an open scrutinee's `default` arm throws (`Inst.Throw`).

Value-backed enums are deferred.

### 8.4 Union / intersection → erased reference representation

Front-end-only, erased, reference-types-only.

- `A | B` erases to the nearest common nominal supertype/trait `Type.Ref`, falling back to `Rt.Object` when none exists. A `match`/typed check lowers to `Op.Is`/`Op.As`.
- `A & B` of nominal types erases to the carrier the backend needs (typically the class component; traits contribute itable membership). Dispatch picks the appropriate `Op.Method`.
- An intersection of structural records is the field-set union → one merged canonical record class (§8.6); a colliding field with differing types is a compile error.

### 8.5 `object` / module → `Defn.Module` with lazy init

`object Foo { ... }` → `Defn.Module(Attrs.None, Foo$, parent = Some(Rt.Object.name), traits = …)`. Methods become instance `Defn.Define`s (self `Type.Ref(Foo$)`); `val`s become `Defn.Var` fields set by the module ctor `Sig.Ctor(Seq.empty)`. **A user-declared module-level `var` field is rejected in MVP.**

Module access and lazy init are provided by the backend (`Generate.genModuleAccessors`): Hi emits only `Op.Module(Foo$)` at use sites. The synthesized `module$Gload` accessor checks a per-module slot; on first access it `Op.Classalloc`s and calls the ctor; under multithreading it routes through the extern `__scalanative_loadModule(slot, rtti, size, ctor)` (`LoadModuleSig = Function(Seq(Ptr, Ptr, Size, Ptr), Ptr)`) for safe one-time init.

> **Lazy-init note (cross-ref §8.9).** Top-level `object` `val`s initialize via the module's `Sig.Ctor(Seq.empty)`, run **lazily on first `Op.Module(name)`**, *not* eagerly at program start. Only members with an explicit `Sig.Clinit` are invoked eagerly by `genMain`'s class-initializer calls.

> **Top-level (package-level) `fun`/`val` items** (outside any `object`) lower to a synthesized module `<package>.package$` (Scala-3-style top-level definitions); imports resolve such members through it. Hi's `std.io.{print, println, printf}` (§10.2) are package-level `fun`s of `std.io.package$`.

> **Cyclic-init diagnostic — judgment call.** The **static front-end dependency-graph cycle check is the sole mechanism**: the module-forces-module graph is analyzed at compile time, and a cycle is a compile error. A runtime sentinel guard is **not** viable on the multithreaded path: the Hi-emitted ctor receives only `(self)` and does not control the slot-write ordering relative to the C loader (`__scalanative_loadModule` provides thread-safe one-time init but exposes no re-entrancy hook). A debug-only "initializing" sentinel is possible *only* on single-threaded builds and cannot piggyback on the multithreaded loader; it is not relied upon.

### 8.6 Structural records & functional update

**Structural records → canonical nominal record class.** Each distinct structural shape lowers to a `Defn.Class`:

- **Deterministic, cross-unit-stable name** `scala.scalanative.hi.record.R$<hash>`, where `<hash>` is over the **sorted** sequence of `(fieldName, Type.mangle(fieldType))` pairs. Sorting before hashing makes the name order-independent, so `(x: Int, y: Int)` and `(y: Int, x: Int)` produce the same `Global.Top`; the closed-world linker deduplicates identical definitions across units.
- **Canonical physical layout:** fields stored in sorted field-name order, so the RTTI ref-offset bitmap is identical regardless of source order. Access `r.x` → `Op.Fieldload`.
- A literal `(x = 1, y = 2)` → `Op.Classalloc(R$<hash>)` + ctor with args reordered into canonical order. One-field `(x = 1)` is the arity-1 record.

**Functional update.** `base with (y = 9)` requires `base`'s full static type (no row polymorphism). Lowering is a full rebuild: evaluate `base` into a temp, construct a fresh instance of the same canonical record class / struct, copying unchanged fields (`Op.Fieldload`/`Op.Extract`) and substituting overrides. Class → `Op.Classalloc` + ctor; value struct → `Op.Insert`s on a fresh `Val.StructValue`. Override is last-wins.

### 8.7 Extension methods → static calls

`extension (x: T) { fun m (a) = ... }` lowers each method to a **static `Defn.Define`** whose first parameter is the receiver `T` (`Sig.Method("m", Seq(T, A, Ret), Sig.Scope.PublicStatic)`). A call `v.m a` or chain `v .m a` lowers to a direct static `Op.Call` — no virtual dispatch, no boxing of the receiver. `3.add 4 .times 5 .neg` lowers to nested calls `neg(times(add(3, 4), 5))`; each step is a static `Op.Call` (extension) or `Op.Method` (class method), never a free-function lookup.

### 8.8 `given` / `using` → dictionary passing

- A `given` instance of trait `T` is an ordinary value of type `Type.Ref(T)` (a class/object implementing `T`); a `given` object is reached via `Op.Module`.
- A `using` parameter is an **explicit extra parameter** in the flattened signature: `fun f (a: A)(using o: Ord[A]): X` → `Defn.Define` of `Type.Function(Seq(<self>, A, Type.Ref(Ord-erased)), X)`. The front end resolves and passes the dictionary positionally.
- Resolution is a **single** search over in-scope `given`s (there is no `impl` form and so no prior tier to consult): pick the unique most-specific candidate, threading nested `using` requirements of a conditional `given` recursively. The result feeds tier 3 of `.m` resolution (§6.10) and any explicit `using` argument.
- Calls through a dictionary (`o.compare a b`) are `Op.Method` dispatch on the trait `Type.Ref` (or static `Op.Call` if monomorphized). Type-class generics erase to `Rt.Object` with box/unbox at boundaries.

No runtime implicit search; resolution is entirely compile-time. *(When the deferred `dyn Trait` lands, an existential value lowers to a synthesized `Defn.Class` holding the payload plus this same dictionary — no new NIR; see §2.2.)*

### 8.9 Entry point and well-known runtime symbols

**Entry point.** `object Main { fun main (args: Array[String]): Unit = ... }` → `Defn.Module(Attrs.None, Main$, Some(Rt.Object.name), …)` containing a method whose signature **is** `nir.Rt.ScalaMainSig` — `Sig.Method("main", Seq(Type.Array(Rt.String), Type.Unit), Sig.Scope.PublicStatic)`. Hi references `nir.Rt.ScalaMainSig` directly (in-repo), so it cannot drift. Hi emits **no** C-level `main`: the backend's `Generate.genMain` discovers and validates the entry, then synthesizes the extern `main(Int, Ptr): Int` that runs GC init, calls explicit `Sig.Clinit` class initializers, loads the runtime module via `Op.Module(Runtime.name)`, converts `argc/argv` into a `scala.scalanative.runtime.ObjectArray`, and finally `Op.Call`s `Main$`'s `ScalaMainSig`. (Top-level `object` `val`s initialize lazily on first module access, not at startup — §8.5.)

**Well-known runtime symbols by canonical name** (referenced, never shipped; pruned by closed-world reachability; all from `nir.Rt`/`nir.Type`):

- `java.lang.Object` = `Rt.Object`; `java.lang.Class` = `Rt.Class`; `java.lang.Throwable` = `Rt.Throwable` (target of `throw`/`try`); `java.lang.Thread` (OS threads; virtual threads via `Thread.ofVirtual()` are an opt-in reuse).
- `java.lang.String` = `Rt.String`, with **exactly four fields** `value, offset, count, cachedHashCode` (`Rt.jlStringFields`). Hi string literals lower to instances of this class and must honor the 4-field layout.
- **`scala.FunctionN` (N = 0..22)** — the function/SAM traits used for closures and eta-expansion (`Types.typeToName` ↦ `scala.Function${arity}`).
- Primitive **box classes** and box/unbox maps from `Type.box`/`Type.unbox` (`java.lang.Integer ↔ Type.Int`, `scala.scalanative.unsafe.Ptr ↔ Type.Ptr` = `Rt.BoxedPtr`). Boxing `Op.Box(refTy, v)`; unboxing `Op.Unbox(boxTy, v)`. (A *boxed* primitive/`Ptr` is a managed reference and is therefore rejected as a `struct` field — §8.2.)
- **Typed arrays**: `Type.Array(elemTy)` selects the runtime array class (`IntArray`, …, `ObjectArray`) via `Rt.arrayAlloc`. Ops: `Op.Arrayalloc`/`Op.Arrayload`/`Op.Arraystore`/`Op.Arraylength`. An **array literal** `[e1, …, en]` (§5.6) lowers to `Op.Arrayalloc` (with a constant `Val.ArrayValue` initializer when all elements are constants) followed by `Op.Arraystore`s; `xs.get i` / `xs.set i v` / `xs.length` map 1:1 onto the load/store/length ops with the runtime's existing bounds-check semantics.
- `scala.scalanative.runtime.*` (the `Runtime` module, primitive markers, `BoxedUnit`); `Type.Unit ↔ Rt.BoxedUnit`.

Because Hi is the in-repo module compiled against `tools`, it is always NIR-format-version matched and reads these constants from `nir.Rt` rather than re-declaring names.

### 8.10 Non-trivial lowerings (flagged)

1. **Eta-expansion** (§8.1) — closure-class synthesis, capture analysis, SAM forwarding, box/unbox at the erased boundary.
2. **`match` decision trees** (§8.3) — class-id range-test trees, nested/literal pattern compilation, exhaustiveness.
3. **Structural-record canonicalization** (§8.6) — deterministic cross-unit naming via sorted-signature hash and canonical layout, plus intersection-merge diagnostics (§8.4).
4. **Value-struct/GC safety enforcement** (§8.2) — a front-end well-formedness pass forbidding managed refs (incl. box classes) inside `struct`s.
5. **Module cyclic-init diagnostic** (§8.5) — static dependency-graph cycle detection (the only mechanism).

Everything else (saturated calls, class/field/ctor emission, modules, entry point, runtime-symbol references) is a direct, mechanical construction of existing `nir.Defn`/`nir.Op` shapes.

---

## 9. Example Programs

Six complete programs using only locked features and kept conventions. Output uses Hi's minimal standard library **`std.io`** (§10.2): `println (line: String): Unit` and the curried `printf (fmt: String) (args: Array[Object]): Unit`, whose format semantics are `java.util.Formatter`'s. Arguments are passed as **one array literal** (the varargs idiom, §5.6) — inside the brackets, elements are full comma-separated expressions and primitives box against `Array[Object]` (§6.8). Raw libc and `c"..."` remain available for FFI but are not used here. Statements are newline-terminated (§3.4); a block's last expression is its value; an application result used as a (non-bracketed) argument is parenthesized (§4.3).

> **Style recommendation (not a grammar rule).** Single-field ADT variants use positional payloads (`Num(2.0)` / `Num(n)`); multi-field variants use named record payloads (`Add(l = …, r = …)`).

### 9.1 Hello world

```hi
package examples.hello

import std.io.println

object Main {
  fun main (args: Array[String]): Unit = {
    println "Hello, Hi!"
  }
}
```

- `object Main` → NIR module `Main$` exposing `main([Ljava.lang.String;)Unit` (`Rt.ScalaMainSig`), the reachability root.
- `println "Hello, Hi!"` is a bare expression statement (§3.4): whitespace application to one bounded `String` argument; its `Unit` value is the block's — and `main`'s — value.
- `println` is a package-level `fun` of `std.io` (§10.2), delegating to javalib `System.out`; the linker prunes everything unreachable.

### 9.2 Value structs, records, functional update

```hi
package examples.records

import std.io.printf

// Value type: only primitives, so GC-safe as a by-value aggregate (§6.3).
struct Point (x: Int, y: Int)

// Structural record TYPE: identity is the field-name set + types, order-independent.
fun describe (p: (name: String, score: Int)): String =
  p.name

object Main {
  fun main (args: Array[String]): Unit = {
    val origin = Point(x = 0, y = 0)
    val shifted = origin with (x = 10)          // Point(x = 10, y = 0); 'origin' unchanged

    val player = (name = "Ada", score = 99)
    val promoted = player with (score = 100)    // (name = "Ada", score = 100)

    var i = 0
    var sum = 0
    while i < 3 do {
      sum = sum + promoted.score
      i = i + 1
    }

    printf "%d,%d -> %s:%d (sum=%d)\n" [shifted.x, shifted.y, describe promoted, promoted.score, sum]
  }
}
```

- `origin` and `shifted` are distinct values (structs have no identity, copied).
- `shifted` is `Point(x = 10, y = 0)` (full rebuild overriding only `x`); `promoted` is `(name = "Ada", score = 100)` while `player` still reads `99`.
- The `while` loop (§7.12) accumulates with `var`s; the assignments are bare expression statements (§3.4).
- The `printf` arguments arrive as **one array literal**: inside `[...]` newlines/commas delimit, so `describe promoted` needs no extra parentheses; `Int`s box against `Array[Object]` (§6.8), and `%s` with a managed `String` is fine — formatting happens in Hi/javalib, not in C.
- Output: `10,0 -> Ada:100 (sum=300)`.

### 9.3 ADTs, pattern matching, exhaustiveness

```hi
package examples.adt

import std.io.printf

type Tree[A] =
  | Leaf
  | Node(left: Tree[A], value: A, right: Tree[A])

fun insert (t: Tree[Int]) (x: Int): Tree[Int] =
  t match {
  | Leaf => Node(left = Leaf, value = x, right = Leaf)
  | Node(left = l, value = v, right = r) =>
      if x < v then Node(left = insert l x, value = v, right = r)
      else if x > v then Node(left = l, value = v, right = insert r x)
      else t
  }

fun sumTree (t: Tree[Int]): Int =
  t match {
  | Leaf => 0
  | Node(left = l, value = v, right = r) => sumTree l + v + sumTree r
  }

object Main {
  fun main (args: Array[String]): Unit = {
    val t0: Tree[Int] = Leaf
    val t1 = insert t0 5
    val t2 = insert t1 3
    val t3 = insert t2 8
    printf "sum = %d\n" [sumTree t3]
  }
}
```

- Dropping any `match` arm is a **compile error** (non-exhaustive sealed ADT).
- Each `match` lowers to a class-id range-test decision tree; `Node(...)` binds the named record-payload fields.
- Curried `insert t0 5` is saturated → a single flat NIR call, not a closure.
- `sumTree t3` needs no parentheses as an array-literal element (the brackets bound it).
- Recursion suits this tree-shaped data; unbounded iteration should prefer loops, since Hi guarantees no tail-call elimination (§7.12).
- Output: `sum = 16` (3 + 5 + 8).

### 9.4 Traits, given, extensions, and chain selection

```hi
package examples.chains

import std.io.printf

trait Show[A] {
  fun show (self: A): String
}

given Show[Int] {                    // retroactive instance for a foreign type: dictionary, not vtable
  fun show (self: Int): String = "Int(...)"
}

extension (n: Int) {
  fun add (m: Int): Int = n + m
  fun times (m: Int): Int = n * m
  fun neg: Int = 0 - n              // nullary getter: invoked by selection, never eta-expands
}

object Main {
  fun main (args: Array[String]): Unit = {
    // chain selection ' .m' at application precedence, left-associative:
    //   3.add 4 .times 5 .neg  ===  call structure ((3.add 4).times 5).neg
    val chained = 3.add 4 .times 5 .neg          // -35
    printf "chain = %d\n" [chained]

    // multi-line chains continue on a leading '.' (§3.4 rule 3):
    val chained2 =
      3.add 4
        .times 5
        .neg
    printf "chain2 = %d\n" [chained2]

    val s = 3 .show                              // tier-3 .m resolution: trait method via given Show[Int]
    printf "shown = %s\n" [s]

    // bitwise operations via chain-selection methods (§7.15):
    val flags = 0b0011
    val mask  = 0b0101
    printf "or=%d and=%d shl=%d\n" [flags .or mask, flags .and mask, flags .shl 2]
  }
}
```

- `3.add 4 .times 5 .neg` = `-35`: each whitespace-preceded `.` re-threads the accumulated result as the next receiver (§4.2). `add`/`times`/`neg` resolve as extensions on `Int`.
- `neg` is a **nullary** extension getter (`fun neg: Int = …`, zero parameter lists): always saturated, used as a zero-arg chain step yielding `Int`, never a closure.
- `3 .show` (or tight `3.show` — identical here, the receiver is a primary) resolves through tier 3 of `.m` resolution (§6.10): `Int` has no `show` member and no extension `show`, so the in-scope `given Show[Int]` witnesses the trait method, lowering to a dictionary call. Dispatch is on the *static* type `Int` — for per-element dynamic dispatch over a foreign type you would reach for the deferred `dyn Show` (§2.2, and the worked example in `comparison.md`).
- `flags .or mask` and `flags .and mask` use the built-in bitwise methods on `Int` (§7.15), following the same chain-selection pattern as `add`/`times` — any single-argument method works as a binary operator via `a .method b` (§4.2).
- Output:
  ```
  chain = -35
  chain2 = -35
  shown = Int(...)
  or=7 and=1 shl=12
  ```

### 9.5 Reference class with inheritance vs. struct

```hi
package examples.classes

import std.io.printf

class Animal (name: String) {
  fun speak (self): String = "..."          // 'self' type defaults to Animal
}

class Dog (name: String) <: Animal(name) {   // super-ctor call supplies 'name'
  fun speak (self): String = "woof"
}

class Cat (name: String) <: Animal(name) {
  fun speak (self): String = "meow"
}

// Value type: copied, no identity; only primitives.
struct Tally (count: Int)

object Main {
  fun main (args: Array[String]): Unit = {
    val rex: Animal = Dog(name = "Rex")
    val mimi: Animal = Cat(name = "Mimi")

    // Dynamic dispatch on the runtime class of each receiver:
    printf "%s says %s\n" [rex.name, rex.speak]     // 'rex.speak' is nullary selection
    printf "%s says %s\n" [mimi.name, mimi.speak]

    val t0 = Tally(count = 0)
    val t1 = t0 with (count = t0.count + 1)
    printf "tally t0=%d t1=%d\n" [t0.count, t1.count]
  }
}
```

- `rex`/`mimi` typecheck because `Dog <: Animal` and `Cat <: Animal` (subsumption).
- `speak` is declared `fun speak (self): String` (one self list, nullary in arguments): `rex.speak` is a nullary selection invoked immediately and dispatched dynamically — `woof` / `meow`.
- `<: Animal(name)` is a super-constructor call (§5.3, §8.2) supplying `name` to `Animal`'s ctor.
- `Tally` holds only an `Int`, legal under the value-struct rule; `t0 with (count = …)` builds a fresh `Tally`, and `t0.count` still reads `0`.
- A `name: String` field is legal on the **class** (heap, precise RTTI) but would be **rejected inside `struct Tally`** (§6.3).
- `%s` with managed `String`s is fine: `std.io.printf` formats in Hi/javalib, not in C.
- Output:
  ```
  Rex says woof
  Mimi says meow
  tally t0=0 t1=1
  ```

### 9.6 A tiny expression evaluator

```hi
package examples.eval

import std.io.printf

type Expr =
  | Num(Double)
  | Add(l: Expr, r: Expr)
  | Sub(l: Expr, r: Expr)
  | Mul(l: Expr, r: Expr)
  | Div(l: Expr, r: Expr)

// Exceptions only (no effect handlers). A managed-ref-carrying error must be a class.
class DivByZero (msg: String) <: Throwable(msg)

fun eval (e: Expr): Double =
  e match {
  | Num(n)            => n
  | Add(l = a, r = b) => eval a + eval b
  | Sub(l = a, r = b) => eval a - eval b
  | Mul(l = a, r = b) => eval a * eval b
  | Div(l = a, r = b) => {
      val rv = eval b
      if rv == 0.0 then throw DivByZero("division by zero")
      else eval a / rv                     // final expression = the block's value
    }
  }

fun safeEval (e: Expr): (ok: Bool, value: Double) =
  try (ok = true, value = eval e)
  catch {
  | ex : DivByZero => (ok = false, value = 0.0)
  }

object Main {
  fun main (args: Array[String]): Unit = {
    // ((2 + 3) * 4) / 2 == 10.0
    val good =
      Div(
        l = Mul(l = Add(l = Num(2.0), r = Num(3.0)), r = Num(4.0)),
        r = Num(2.0))

    val bad = Div(l = Num(1.0), r = Num(0.0))

    val r1 = safeEval good
    val r2 = safeEval bad

    printf "good: ok=%s value=%s\n" [r1.ok, r1.value]
    printf "bad:  ok=%s value=%s\n" [r2.ok, r2.value]
  }
}
```

- `eval` is exhaustive over the five variants (omitting any arm is a compile error). The `match` lowers to a class-id range-test decision tree.
- `safeEval good` returns `(ok = true, value = 10.0)`; `safeEval bad` throws `DivByZero`, caught by the **typed** `catch` pattern `ex : DivByZero`, returning `(ok = false, value = 0.0)`. (`try parse … catch …` continues across the line break: `catch` cannot begin a statement, §3.4 rule 3.)
- `DivByZero` is a reference `class` extending `Throwable` via the super-ctor call `<: Throwable(msg)` (only classes carry managed references). Construction uses `T(...)` (no `new`).
- The result type is the structural record `(ok: Bool, value: Double)` (`Bool`, not `Boolean`).
- Output (boxed values render via `Formatter` `%s`):
  ```
  good: ok=true value=10.0
  bad:  ok=false value=0.0
  ```

> **Note (virtual threads).** With closure→SAM conversion in the MVP (§7.6, §8.1), a Hi closure satisfies `java.lang.Runnable`, so the backend's Loom-style virtual threads are directly reusable: `Thread.ofVirtual.start { => doWork () }` — `Thread.ofVirtual` is a (nullary) javalib static selected as a path, and `.start` takes the closure as its `Runnable`. A worked concurrency example is deferred to the collections/I-O milestone.

---

## 10. Implementation & Build Integration

### 10.1 Module layout

Hi is a **new in-repo module** mirroring the `cli` module's place in the build, in package **`scala.scalanative.hi`**. In-repo placement gives compile-time access to internal backend symbols (`nir.Rt`, `nir.Defn`, the `scala.scalanative.build` pipeline) and guarantees NIR-format-version matching.

The module is defined with the repo's `MultiScalaProject` helper and depends on the **JVM** variant of the tools stack (the Hi compiler is a JVM-hosted tool that *produces* native artifacts):

```scala
// project/Build.scala (sketch, mirroring toolsJVM)
lazy val hi =
  MultiScalaProject("hi")
    .dependsOn(toolsJVM)   // brings in nirJVM + utilJVM transitively
    .withCommonTools
    // ... standard settings parallel to the cli module
```

`toolsJVM` already `.dependsOn(nirJVM, utilJVM)`, so depending on `toolsJVM` gives Hi the full backend API surface: `nir.*` (definitions, serialization, `Rt`), the linker, the Interflow optimizer, codegen, and `scala.scalanative.build.{Build, Config, NativeConfig, Discover}`. Hi depends on `toolsJVM` (not the native-compiled `tools`), exactly as the CLI does.

### 10.2 Runtime dependency

Hi does **not** ship a minimal runtime. It depends on the published / in-repo **nativelib + javalib + scalalib NIR**, referencing standard symbols by canonical name:

- `java.lang.{Object, Class, Throwable, Thread}`.
- `java.lang.String` — relied upon to have exactly four fields `value, offset, count, cachedHashCode` (the layout `nir.Rt`/codegen assume).
- `scala.FunctionN` (the closure/SAM traits, §8.1).
- `scala.scalanative.runtime.*`, the primitive box classes, and the typed array classes.

These appear on the classpath as `.nir` entries; the closed-world linker prunes them to exactly what the program uses. Hi adds its own emitted `.nir` to that classpath.

#### Hi standard library (`std.*`)

Hi additionally ships a small **Hi-authored standard library**, compiled to NIR once and placed on the same classpath. MVP surface — `std.io`:

```hi
package std.io

fun print   (s: String): Unit = ...                          // delegates to java.lang.System.out
fun println (s: String): Unit = ...
fun printf  (fmt: String) (args: Array[Object]): Unit = ...  // java.util.Formatter semantics
```

These are package-level `fun`s lowering to `std.io.package$` (§8.5); they delegate to javalib (`java.lang.System.out`, `java.lang.String.format`) by canonical name, so the linker prunes whatever is unused. The varargs idiom is an explicit `Array[Object]` parameter — Hi has no variadic functions; callers write `printf "x=%d\n" [x]` (§5.6), and primitives box at the literal (§6.8). C-variadic functions (libc `printf`) remain reachable via `c"..."`/`scala.scalanative.libc` for FFI but are not part of `std.io`.

### 10.3 The handoff: `Seq[nir.Defn]` → native binary

The frontend produces a `Seq[nir.Defn]` per top-level type and writes binary NIR via the existing serializer:

```scala
import scala.scalanative.nir
import scala.scalanative.nir.serialization.serializeBinary

val channel: java.nio.channels.WritableByteChannel = ...
serializeBinary(defns, channel)   // writes one binary .nir file
```

The `.nir` files are placed on a classpath alongside the runtime NIR. Hi then constructs a `scala.scalanative.build.Config` (clang/clangpp via `Discover`; GC/mode/multithreading via `NativeConfig`; the classpath, work directory, module name, and main class `Main`) and drives the backend:

```scala
import scala.scalanative.build.{Build, Config}
import scala.util.Using

Using.resource(...) { implicit scope =>
  val artifact: java.nio.file.Path = Build.buildCachedAwait(config)  // -> native binary
}
```

`Build.buildCachedAwait` runs the complete pipeline (link → optimize → codegen → system linker), caching when inputs are unchanged. The entry point is discovered as the static `main([Ljava.lang.String;)Unit` (`nir.Rt.ScalaMainSig`) on `Main$`. From the backend's perspective, Hi-emitted NIR and `nscplugin`-emitted NIR are indistinguishable.

### 10.4 Compiler pipeline

```
Hi source (.hi)
   │
   ▼
1. Lex + filter   tokenize; synthesize statement terminators per §3.4 (significant
   │              newlines; `;` explicit); classify tight vs spaced '.' and '['
   ▼
2. Parse          build the Hi AST per §5; encode tight-'.' / chain-' .m' / whitespace-app
   │              precedence and the bounded-argument rule
   ▼
3. Name resolve   resolve packages/imports, bind identifiers, establish scopes
   ▼
4. Typecheck      local/bidirectional inference (§6): annotated public signatures,
   │              inferred locals, subsumption, given/extension, erase |/& to refs
   ▼
5. Exhaustiveness check 'match' coverage over sealed ADTs; report non-exhaustive /
   │              unreachable arms (compile errors)
   ▼
6. Desugar        functional update -> full rebuild; ADTs -> sealed base + cases;
   │              'for' -> foreach; chain selections -> nested receiver calls;
   │              curried 'fun' -> flat method + eta sites; closures (incl. SAM)
   ▼
7. NIR emit       Seq[nir.Defn]; respect the GC field-layout rule and canonical
   │              runtime symbol names; serialize via serializeBinary
   ▼
8. Hand off to tools
                  place .nir on classpath, build Config, call Build.buildCachedAwait
                  -> link (reachability) -> Interflow -> LLVM codegen -> system link
```

Phases 1–7 are Hi's new code. Phase 8 is entirely the existing Scala Native backend, invoked through `toolsJVM` with no modification. The single integration seam — a `Seq[nir.Defn]` crossing into `serializeBinary` and then `Build.buildCachedAwait` — is what lets Hi avoid forking the backend.

---

## 11. Open Decisions

The following are genuine choices still left to the language designer. (Items the prior review left open but that this spec *decided* — e.g. `var` inclusion, uncurried multi-param closures, return-type annotations, exhaustiveness-as-error — are noted in §2/§6/§7 as judgment calls and are **not** repeated here. The native collections API was decided-deferred (§2.2); closure→SAM conversion was subsequently **promoted into the MVP** (§2.1, §7.6).)

1. **I/O surface beyond `std.io`, and companion-`apply` sugar.** `std.io.{print, println, printf}` (§10.2) is the decided MVP entry; the fuller I/O design (stdin, files, errors) and whether `T(...)` on a type with a companion is sugar for a factory `apply` remain unresolved.

2. **Numeric conversions.** With no implicit widening, the exact set and naming of explicit conversion methods (`toLong`, `toDouble`, …) and whether unsigned/`Size`/`Ptr` arithmetic is exposed is unspecified.

3. **`equals`/`hashCode`/`toString` and structural equality semantics.** Structs are defined to have structural equality and records a canonical layout, but the surface contract for value equality, hashing, and string conversion of classes/records/ADTs (auto-derived vs. user-provided) is open.

4. **Visibility / access control.** The spec distinguishes "public/top-level" from "local" for inference purposes but defines no `private`/`internal` modifier surface or module-visibility rules.

5. **Effect/concurrency surface beyond OS threads.** The MVP exposes none of the backend's delimited continuations or virtual threads as language features. If a future version surfaces structured concurrency or effects, the reserved keywords (`effect`, `handle`, `resume`, `yield`) anticipate it but the design is open. (Note: ergonomic *reuse* of the backend's existing virtual threads is unblocked — closure→SAM conversion is in the MVP, §7.6.)

6. **Pattern-matching depth.** Whether single-field reference classes support positional destructuring (`DivByZero(_)`) in addition to typed patterns (`ex : DivByZero`), nested guards beyond per-arm `if`, and named-binding combinations (`x @ p`) at full generality are partially specified; the exact accepted pattern algebra for nominal classes may need tightening.

7. **Cross-unit canonical-record hashing collisions.** §8.6 derives record class names from a hash of the sorted signature; the hash width and collision-resolution policy (fall back to full mangled signature on collision?) is left to the implementer.

8. ~~**Bitwise and shift operators.**~~ **Decided.** The bitwise/shift surface uses built-in chain-selection methods on all integer primitive types — `.and`, `.or`, `.xor`, `.shl`, `.shr`, `.ushr`, `.not` (§7.15) — rather than new operator lexemes. This avoids extending the fixed operator set with tokens that parse ambiguously alongside type-level `|`/`&` and follows the general idiom that any single-argument method is usable as a binary operator via chain selection `a .method b` (§4.2).

---

## Changelog

*Newest first. Each entry is a delta against the previous spec version; `§` references point into the sections above. New versions append a `###` subsection here.*

### v0.3 (from v0.2)

- **One-armed `if` (`else` now optional).** `else` may be omitted when the then-branch is `Unit` (`if c then e` ≡ `if c then e else ()`), enabling guard clauses like `if a > 10 then return`; a non-`Unit` value still requires both branches, so the LUB stays defined. `else` binds to the nearest `then`. (§2.1, §3.5, §5.4, §7.4)
- **Clarify `self` and `dyn`.** Bare `self` (unannotated) is documented as idiomatic, with the rationale for explicit-`self` over implicit `this` (parameter-based traits; associated/multi-param members). A trait used as a type is split: `Array[Show]` is nominal subtyping (works today), `Array[dyn Show]` is the deferred existential, and coercion into `dyn` is implicit/type-directed (no `as`). (§2.2, §5.3, §6.10)
- **`impl` removed; one typeclass mechanism.** The Rust-borrowed `impl Trait for Type` form is gone — it was a second surface over the same dictionary search as `given`, strictly *less* expressive (no conditional instances), and required the "`impl` first, then `given`" tie-break. Trait satisfaction now has exactly two paths: *nominal* at the definition via the `<:` clause (dynamic dispatch via itable, owned types) and *retroactive* via `given` (dictionary, foreign/value/primitive types, conditional instances). Coherence — `impl`'s one real guarantee — is recovered as a **closed-world `given` uniqueness** rule (one per `(Trait, type-head)`), checkable thanks to the closed-world linker. The `impl` keyword is dropped from the reserved set. (§2.1, §3.5, §5.2, §5.3, §6.10, §6.11, §8.8, §9.4)
- **Unified `.m` resolution order.** `recv.m` / `recv .m` resolves in a fixed three-tier order — (1) intrinsic/itable member, (2) in-scope `extension`, (3) trait method witnessed by an in-scope `given` — with ambiguity *within* a tier an error. This makes the prior "methods/extensions on the receiver's type" prose precise and gives `3 .show`-style trait calls a defined meaning without `impl`. (§4.2, §6.10)
- **Traits get default methods; `fun` bodies are optional.** `fun_decl`'s body is now optional: bodiless ⇒ abstract member (`Defn.Declare`, trait-only), with-body ⇒ concrete. A concrete trait member is a *default method* — yielding Swift "protocol-extension defaults" while dispatching through the same mechanism as any trait member (no Swift-style static/dynamic split). (§5.3, §6.10)
- **`extension` is pure static sugar.** Extension bodies hold only `fun` members (the incoherent `val` member is removed — extensions add no storage); conformance is deliberately *not* expressible via `extension` (unlike Swift), avoiding the static-vs-dynamic dispatch hazard. `using`-constrained extensions are deferred. (§5.3, §6.10, §2.2)
- **`dyn Trait` reserved and deferred.** Retroactive *dynamic* dispatch on a foreign/value type (Rust's `dyn`, Swift's `any`) is named as a deferred existential — a payload-plus-`given`-dictionary box lowering to a synthesized `Defn.Class`, no new NIR. The keyword `dyn` is reserved; until it ships, pair value + dictionary by hand. (§2.2, §3.5, §6.10, §8.8)
- **Bitwise & shift methods on integer primitives.** Bitwise AND/OR/XOR/NOT, left/right/unsigned-right shift are provided as built-in chain-selection methods `.and`/`.or`/`.xor`/`.not`/`.shl`/`.shr`/`.ushr` on all integer primitive types, rather than adding new operator lexemes that would parse ambiguously with type-level `|`/`&`. Chain selection `a .method b` is documented as the general operator-style calling convention for any single-argument method. (§4.1, §4.2, §6.2, §7.15, §9.4, §11.8)
- **Clarify multi-field param list call model.** `fun f (a: Int, b: Int)` receives and destructures a single tuple argument; `f (1, 2)` passes one value, not two — parens always build a single tuple. (§4.2)
- **OCaml-style application & first-class constructors.** Parentheses are no longer whitespace-significant — `f(x, y)` ≡ `f (x, y)` applies `f` to the *tuple* `(x, y)`, while curried juxtaposition `f x y` is unchanged. Constructors are ordinary **first-class functions** (`map Some`, `val mk = Node`, `Node a`); the level-2 construction special case is gone, so a constructed value used as a juxtaposed argument or selected from needs parentheses or a chain dot (`f (Point(x=1))`, `(Point(x=1)).x` or `Point(x=1) .x`) — exactly as for any application result. Closures take a parenthesized, optionally-typed parameter list `{ (x: Int, y: String) => e }` and stay uncurried (one `FunctionN`, tuple-applied). (§4, §5.4, §7.6)
- **ADT payloads use parentheses, not `of`.** `type Option[A] = | Some(A) | None`, `| Node(left: Tree, value: A, right: Tree)` — positional `C(T…)` or named `C(f: T, …)`, mirroring construction and struct/class fields; the `of` keyword is removed. (§5.3, §6.7)
- **Postfix `match`.** The scrutinee comes first — `e match { | … }` (Scala-style), a lowest-precedence postfix. It reads naturally after method chains (`xs.map f match { … }`) and removes the cramped adjacent-brace case when the scrutinee ends in a callback. `match` becomes a continuation keyword (a line-leading `match` joins the previous line); `try e catch { … }` is unchanged. (§4.1, §5.4, §7.5)
- **Comma-separated parent lists.** Inheritance keeps `<:` (consistent with the `[A <: Bound]` subtype-bound notation; `>:` stays reserved for a future lower bound) but lists parents with commas instead of repeated `with` — `class Dog (name: String) <: Animal(name), Runnable, Comparable[Dog]`. `with` is now reserved for functional update only. (§5.3)

### v0.2 (from v0.1)

- **Significant newlines.** Statements are newline-terminated (§3.4); the `do` effect-marker and tail-`return` value-marker are removed; a block's last expression is its value.
- **`return` is early function exit.** Java/Rust semantics (§7.13).
- **`while`/`for` loops** join the MVP (§7.12) — `while c do e`, `for x in e do body`; `do` now introduces loop bodies.
- **Chain selection ` .m`** (whitespace-preceded dot) replaces the `/.` operator (§4.2).
- **Array literals** `[1, 2, 3]` (spaced `[`) and the array-argument varargs idiom join the MVP (§5.6); tight `f[T]` remains type application.
- **Closure → SAM conversion** joins the MVP (§7.6, §8.1), unblocking `Runnable`/virtual threads.
- **Minimal Hi standard library** `std.io` (`print`/`println`/`printf`) replaces raw libc `printf` in the examples (§10.2).
- **`try e catch | …`** replaces `try e with` (§7.11), removing the handler-vs-struct-update collision.
- **One-field records** need no trailing comma: `(x = 1)` / `(x: Int)` (§5.7).
- **Built-in operator semantics** defined (§7.15); the struct GC-restriction lift path documented (§6.3).
- **Braced arm-blocks**: `match e { | … }` and `try e catch { | … }` — braces delimit the arms, eliminating the dangling-arm ambiguity; `with` no longer introduces match arms.
- **Unified functional update**: `base with (f = v, …)` is the single form for records, structs, and classes; the spread form `(..base, …)` and the `..` token are removed.
- **Parser-precision fixes**: innermost-delimiter newline rule; guards are operator-level expressions; an empty parameter list declares one `Unit` parameter; bracket classification is token-level (§5.6 table) — `val a=[1,2,3]` is an array literal (the construction / paren-call rules introduced here were superseded by v0.3's OCaml application model, above); `given` instances with members use the braced form.
