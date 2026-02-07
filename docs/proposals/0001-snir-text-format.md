# Proposal: SNIR — S-Expression Text Format for NIR

| Field       | Value                                       |
|-------------|---------------------------------------------|
| **Title**   | SNIR: A Human-Readable, Human-Writable Text Format for NIR |
| **Status**  | Draft                                       |
| **Authors** |                                              |
| **Created** | 2026-02-07                                  |

## Table of Contents

- [Proposal: SNIR — S-Expression Text Format for NIR](#proposal-snir--s-expression-text-format-for-nir)
  - [Table of Contents](#table-of-contents)
  - [1. Motivation](#1-motivation)
  - [2. Background](#2-background)
    - [2.1 NIR (Native Intermediate Representation)](#21-nir-native-intermediate-representation)
    - [2.2 HNIR (.hnir) — Current Text Dump Format](#22-hnir-hnir--current-text-dump-format)
    - [2.3 NIR Data Model](#23-nir-data-model)
    - [2.4 Name Mangling System](#24-name-mangling-system)
    - [2.5 Prior Art: The Removed Legacy Text Parser](#25-prior-art-the-removed-legacy-text-parser)
  - [3. Problem Statement](#3-problem-statement)
    - [3.1 Mangled Names Are Unreadable](#31-mangled-names-are-unreadable)
    - [3.2 The Syntax Is Parser-Unfriendly](#32-the-syntax-is-parser-unfriendly)
    - [3.3 Information Lost in HNIR](#33-information-lost-in-hnir)
  - [4. Prior Art: WAT (WebAssembly Text Format)](#4-prior-art-wat-webassembly-text-format)
    - [4.1 Key Design Principles](#41-key-design-principles)
    - [4.2 WAT Syntax Examples](#42-wat-syntax-examples)
    - [4.3 Why WAT Works](#43-why-wat-works)
  - [5. Evaluation: Extend HNIR vs. S-Expression Format](#5-evaluation-extend-hnir-vs-s-expression-format)
    - [5.1 Option A: Extend HNIR with a Parser](#51-option-a-extend-hnir-with-a-parser)
    - [5.2 Option B: S-Expression Format (SNIR)](#52-option-b-s-expression-format-snir)
    - [5.3 Comparison Matrix](#53-comparison-matrix)
    - [5.4 Recommendation](#54-recommendation)
  - [6. SNIR Format Specification](#6-snir-format-specification)
    - [6.1 Lexical Structure](#61-lexical-structure)
    - [6.2 Module Structure](#62-module-structure)
    - [6.3 Names and Identifiers](#63-names-and-identifiers)
      - [6.3.1 Top-Level Names](#631-top-level-names)
      - [6.3.2 Member Names](#632-member-names)
      - [6.3.3 Overload Disambiguation](#633-overload-disambiguation)
      - [6.3.4 Signature Kinds](#634-signature-kinds)
      - [6.3.5 Scope](#635-scope)
      - [6.3.6 Local Variables](#636-local-variables)
    - [6.4 Types](#64-types)
      - [6.4.1 Primitive Types](#641-primitive-types)
      - [6.4.2 Reference Types](#642-reference-types)
      - [6.4.3 Aggregate Types](#643-aggregate-types)
      - [6.4.4 Managed Array Type](#644-managed-array-type)
      - [6.4.5 Mutable Variable Type](#645-mutable-variable-type)
    - [6.5 Values (Literals)](#65-values-literals)
    - [6.6 Definitions](#66-definitions)
      - [6.6.1 Class](#661-class)
      - [6.6.2 Trait](#662-trait)
      - [6.6.3 Module (Scala Object)](#663-module-scala-object)
      - [6.6.4 Variable](#664-variable)
      - [6.6.5 Constant](#665-constant)
      - [6.6.6 Declaration (External/Forward)](#666-declaration-externalforward)
      - [6.6.7 Function Definition](#667-function-definition)
    - [6.7 Instructions](#67-instructions)
      - [6.7.1 Labels (Basic Block Entry)](#671-labels-basic-block-entry)
      - [6.7.2 Let (Operation Binding)](#672-let-operation-binding)
      - [6.7.3 Control Flow](#673-control-flow)
    - [6.8 Operations](#68-operations)
      - [6.8.1 Arithmetic (Binary)](#681-arithmetic-binary)
      - [6.8.2 Comparison](#682-comparison)
      - [6.8.3 Conversions](#683-conversions)
      - [6.8.4 Memory Operations](#684-memory-operations)
      - [6.8.5 High-Level Object Operations](#685-high-level-object-operations)
      - [6.8.6 Array Operations](#686-array-operations)
      - [6.8.7 Mutable Variable Operations](#687-mutable-variable-operations)
    - [6.9 Control Flow](#69-control-flow)
      - [6.9.1 Branch Targets (`Next`)](#691-branch-targets-next)
      - [6.9.2 Unwind on Let](#692-unwind-on-let)
    - [6.10 Attributes](#610-attributes)
    - [6.11 Debug Information](#611-debug-information)
      - [6.11.1 Source Positions](#6111-source-positions)
      - [6.11.2 Lexical Scopes](#6112-lexical-scopes)
      - [6.11.3 Local Variable Names](#6113-local-variable-names)
    - [6.12 Linktime Conditions](#612-linktime-conditions)
  - [7. Complete Example](#7-complete-example)
  - [8. Comparison: HNIR vs. SNIR Side-by-Side](#8-comparison-hnir-vs-snir-side-by-side)
    - [8.1 Class Definition](#81-class-definition)
    - [8.2 Method Definition](#82-method-definition)
    - [8.3 Virtual Dispatch](#83-virtual-dispatch)
    - [8.4 Type References](#84-type-references)
  - [9. Implementation Plan](#9-implementation-plan)
    - [Phase 1: Core Parser \& Printer](#phase-1-core-parser--printer)
    - [Phase 2: Integration](#phase-2-integration)
    - [Phase 3: Tooling](#phase-3-tooling)
  - [10. Open Questions](#10-open-questions)

---

## 1. Motivation

Scala Native compiles Scala source code to native binaries via an intermediate
representation called **NIR**. NIR exists in a binary format (`.nir` files) that
is efficient for machine processing but completely opaque to humans. A text dump
format (`.hnir`) exists for debugging, but it was designed as a **one-way,
read-only** representation — there is no parser to convert text back to NIR.

We want a text format for NIR analogous to **WAT** (WebAssembly Text Format)
for WebAssembly:

- **Human-writable**: A developer should be able to write NIR by hand, for
  testing, debugging, prototyping compiler passes, or crafting test fixtures
  without going through the full Scala compilation pipeline.
- **Human-readable**: The format should be clearer than the current `.hnir`
  dump, particularly with respect to mangled identifiers.
- **Round-trippable**: Text → binary NIR → text should preserve all semantic
  information.
- **Parser-friendly**: The syntax should be trivially parseable without
  context-dependent grammar rules or complex lookahead.

## 2. Background

### 2.1 NIR (Native Intermediate Representation)

NIR is Scala Native's primary intermediate representation. It is an SSA-like IR
with both low-level operations (pointer arithmetic, loads/stores) and
high-level operations (virtual dispatch, class allocation, array operations).

The compilation pipeline:

```
Scala Source → (nscplugin) → .nir (binary) → (linker) → linked NIR
    → (optimizer/Interflow) → optimized NIR → (codegen) → LLVM IR → native binary
```

NIR is serialized per-class into `.nir` files by `BinarySerializer` in the
compiler plugin (`nscplugin`) and deserialized by `BinaryDeserializer` in the
linker. The binary format uses interning, section-based layout with offset
tables, and compact encoding.

### 2.2 HNIR (.hnir) — Current Text Dump Format

The `.hnir` format is a **human-readable text dump** produced by
`nir.Show.dump()`. The "h" stands for "human-readable." It is an LLVM-IR-like
textual representation generated at two points in the pipeline:

1. After **linking** (reachability analysis) → `linked.hnir`
2. After **optimization** (Interflow) → `optimized.hnir`

Triggered by setting `nativeConfig ~= { _.withDump(true) }` in `build.sbt`.

`Show.dump` iterates over all definitions, calls `.show` on each (which
returns a `String` via pattern-matching traversal of the NIR AST), and writes
the result to a file with a `PrintWriter`.

**The format is one-way** — there is no parser. The legacy text parser was
explicitly removed in v0.4.1 (see `docs/changelog/0.4.x/0.4.1.md`, line 147:
"Remove legacy textual NIR parser").

### 2.3 NIR Data Model

The NIR AST consists of these core types:

**Definitions (`Defn`)** — top-level declarations:

| Variant       | Key Fields                                           |
|---------------|------------------------------------------------------|
| `Defn.Var`    | `attrs`, `name: Global.Member`, `ty: Type`, `rhs: Val` |
| `Defn.Const`  | `attrs`, `name: Global.Member`, `ty: Type`, `rhs: Val` |
| `Defn.Declare`| `attrs`, `name: Global.Member`, `ty: Type.Function`    |
| `Defn.Define` | `attrs`, `name: Global.Member`, `ty: Type.Function`, `insts: Seq[Inst]`, `debugInfo: DebugInfo` |
| `Defn.Trait`  | `attrs`, `name: Global.Top`, `traits: Seq[Global.Top]` |
| `Defn.Class`  | `attrs`, `name: Global.Top`, `parent: Option[Global.Top]`, `traits: Seq[Global.Top]` |
| `Defn.Module` | `attrs`, `name: Global.Top`, `parent: Option[Global.Top]`, `traits: Seq[Global.Top]` |

All definitions carry implicit `pos: SourcePosition`.

**Types (`Type`)** — 20 variants:

| Category | Types |
|----------|-------|
| Primitive | `Bool`, `Ptr`, `Size`, `Char`, `Byte`, `Short`, `Int`, `Long`, `Int128`, `Float`, `Double` |
| Aggregate | `ArrayValue(ty, n)`, `StructValue(tys)`, `Function(args, ret)` |
| Reference | `Ref(name, exact, nullable)`, `Array(ty, nullable)`, `Null`, `Unit`, `Nothing` |
| Special   | `Vararg`, `Virtual`, `Var(ty)` |

**Values (`Val`)** — 22 variants covering literals (`Int`, `Long`, `Float`,
`Double`, `Char`, `Byte`, `Short`, `Size`, `Bool`, `Int128`), composites
(`StructValue`, `ArrayValue`, `ByteString`), references (`Local`, `Global`),
and special values (`Null`, `Unit`, `Zero`, `Const`, `String`, `Virtual`,
`ClassOf`).

**Operations (`Op`)** — 30+ variants split into:

- **Low-level**: `Call`, `Load`, `Store`, `Elem`, `Extract`, `Insert`,
  `Stackalloc`, `Bin`, `Comp`, `Conv`, `Fence`
- **High-level**: `Classalloc`, `Fieldload`, `Fieldstore`, `Field`, `Method`,
  `Dynmethod`, `Module`, `As`, `Is`, `Copy`, `SizeOf`, `AlignmentOf`, `Box`,
  `Unbox`, `Var`, `Varload`, `Varstore`, `Arrayalloc`, `Arrayload`,
  `Arraystore`, `Arraylength`

**Instructions (`Inst`)** — SSA instructions:

- `Label(id, params)` — basic block entry
- `Let(id, op, unwind)` — operation with optional unwind handler
- Control flow: `Ret`, `Jump`, `If`, `Switch`, `Throw`, `Unreachable`, `LinktimeIf`

**Branch targets (`Next`)**: `Label(id, args)`, `Unwind(exc, next)`,
`Case(value, next)`, `None`.

**Binary/Comparison/Conversion operations**: `Bin` (18 variants: `Iadd`,
`Fadd`, `Isub`, ..., `Xor`), `Comp` (16 variants: `Ieq`, `Ine`, ..., `Fle`),
`Conv` (14 variants: `Trunc`, `Zext`, ..., `Bitcast`).

**Attributes (`Attrs`)**: Bundle of 17+ flags and annotations including inline
hints (`MayInline`, `InlineHint`, `NoInline`, `AlwaysInline`), specialization
hints, optimization state, `Extern(blocking)`, `Link(name)`, `Alignment`,
`Abstract`, `Final`, `Volatile`, `SafePublish`, `Stub`, `Dyn`, etc.

**Debug information (`DebugInfo`)**: `localNames: Map[Local, String]` and
`lexicalScopes: Seq[LexicalScope]` where each `LexicalScope` has
`(id: ScopeId, parent: ScopeId, srcPosition: SourcePosition)`.

### 2.4 Name Mangling System

NIR uses a length-prefixed, prefix-coded mangling scheme for symbol names.

**Globals**: `Global.Top("java.lang.String")` mangles to `T16java.lang.String`.
`Global.Member(top, sig)` mangles to `M<top><sig>`.

**Signatures**: Encoded with prefix characters:

| Prefix | Sig Variant | Components |
|--------|-------------|------------|
| `D`    | `Method`    | name, param types, return type, scope |
| `F`    | `Field`     | name, scope |
| `R`    | `Ctor`      | param types |
| `I`    | `Clinit`    | (none) |
| `P`    | `Proxy`     | name, param types |
| `C`    | `Extern`    | name |
| `G`    | `Generated` | name |
| `K`    | `Duplicate` | original sig, types |

**Type encoding**: Single characters — `i` = Int, `j` = Long, `f` = Float,
`d` = Double, `z` = Bool, `u` = Unit, etc. Reference types use length-prefixed
class names.

**Example**: `java.lang.String::length()Int` →

```
M                    Member prefix
16java.lang.String   owner (16-char identifier)
D                    Method signature
6length              method name (6-char identifier)
i                    return type = Int
E                    end of type list
O                    Public scope
```

Full mangled form: `M16java.lang.StringD6lengthiEO`

**Round-trip support**: `Unmangle.scala` provides complete inverse parsing:
`unmangleGlobal`, `unmangleType`, `unmangleSig`. All human-readable components
(class name, method name, parameter types, return type, scope) are fully
recoverable from mangled form.

### 2.5 Prior Art: The Removed Legacy Text Parser

Scala Native historically had a textual NIR parser that was removed in
v0.4.1 (`docs/changelog/0.4.x/0.4.1.md`, line 147). The removal suggests the
old text format was not considered worth maintaining — possibly because it was
tightly coupled to the HNIR dump format and suffered from the same
human-writability problems.

## 3. Problem Statement

The current `.hnir` format has two fundamental problems that prevent it from
serving as a human-writable text format (analogous to WAT):

### 3.1 Mangled Names Are Unreadable

Current `.hnir` output:
```
def @"M5Main$D4mainAL21java.lang.String[]EuO" : (ref @"T21java.lang.String[]") => unit {
  %0(%args : ref):
    %1 = module @"T8Predef$"
    %2 = method %1 : ref, "D7printlnL16java.lang.ObjectuEO"
    %3 = call[(!?ref @"T16java.lang.Object") => unit] %2 : ptr, %0 : ref
    ret %3 : unit
}
```

A human cannot write `M5Main$D4mainAL21java.lang.String[]EuO`. The
length-prefixed, prefix-coded encoding is designed for binary efficiency, not
for human authoring. Compare WAT where you write `$add` or `(call $my_func)`.

Mangled names appear in:
- Definition names (`@"M..."`)
- Method/dynmethod signature arguments (`"D7println..."`)
- Global references in field operations (`@"M16java.lang.String..."`)

### 3.2 The Syntax Is Parser-Unfriendly

The HNIR format uses **context-dependent, mixed-delimiter syntax** that
requires significant ad-hoc parsing logic:

| Problem | Example | Why It's Hard |
|---------|---------|---------------|
| **Context-dependent `:`** | `%5 : int` vs `class @"..." : @"..."` | Means "has type" in one place, "extends" in another |
| **Mixed delimiters** | `@""`, `:`, `{ }`, `[ ]`, `,`, `( )`, `=>` | No uniform structure |
| **`!?` type prefixes** | `!?ref @"..."` | `!` = exact, `?` = NOT nullable (inverted semantics) |
| **Implicit val types** | `int 42` | Is `int` the op keyword or the type? Context-dependent |
| **Separator in nested constructs** | `structvalue {int 1, int 2}` | `,` separates fields but also appears within vals |
| **Indentation-sensitive blocks** | Labels at col 0, instructions indented | No explicit block delimiters |
| **Float/double precision** | `float 3.14` | `toString` may lose NaN payloads, signed zero |
| **MemoryOrder inconsistency** | `onAcqrel`, `onSeqcst` | Prefix `on` on only 2 of 6 variants (bug in `Show.scala`) |

### 3.3 Information Lost in HNIR

The HNIR dump completely omits several categories of data present in binary NIR:

| Missing Information | In Binary | In HNIR | Impact |
|---------------------|-----------|---------|--------|
| `SourcePosition` on definitions | Full (file, line, col) | Absent | Cannot reconstruct debug info |
| `SourcePosition` on instructions | Full (file, line, col) | Absent | No instruction-level source mapping |
| Lexical scope tree | Full tree with parent links | Only scope ID on `Let` | Cannot produce DWARF debug info |
| Float/double bit-exact values | Raw int bits | Decimal `toString` | NaN payloads, `-0.0` lost |
| Version/magic header | Yes | None | No format versioning |
| Entry point flag | Yes | None | Minor |

## 4. Prior Art: WAT (WebAssembly Text Format)

WAT is the text format for WebAssembly. It is designed to be both human-
readable and human-writable, with a round-trip path to/from the binary format.

### 4.1 Key Design Principles

1. **Uniform S-expression structure**: Every construct is `(keyword ...)`.
   Parsing is trivial: match parens, dispatch on keyword, recurse.

2. **Named identifiers with `$` prefix**: `$add`, `$my_func`, `$counter`.
   Human-chosen, human-readable. The binary format uses numeric indices; names
   are syntactic sugar.

3. **Keyword-first**: The first token after `(` always identifies the node type.
   No ambiguity about what a construct is.

4. **Self-describing**: Type annotations are explicit inline —
   `(param $x i32)`, `(result i32)`. No implicit context-dependent interpretation.

5. **Dual notation**: Instructions can be written in flat stack form or folded
   S-expression form:
   ```wat
   ;; Flat
   local.get $x
   i32.const 2
   i32.add

   ;; Folded
   (i32.add (local.get $x) (i32.const 2))
   ```

### 4.2 WAT Syntax Examples

```wat
(module
  ;; Type declaration
  (type $binop (func (param i32 i32) (result i32)))

  ;; Function with named params and locals
  (func $add (param $a i32) (param $b i32) (result i32)
    (i32.add (local.get $a) (local.get $b)))

  ;; Class-like structure (GC proposal)
  (type $point (struct (field $x f64) (field $y f64)))

  ;; Control flow
  (func $example (param $n i32) (result i32)
    (if (result i32) (i32.eqz (local.get $n))
      (then (i32.const 1))
      (else
        (i32.mul
          (local.get $n)
          (call $example (i32.sub (local.get $n) (i32.const 1)))))))

  ;; Export
  (export "add" (func $add)))
```

### 4.3 Why WAT Works

- **Trivial lexer**: Tokens are `(`, `)`, strings, numbers, and keywords.
- **Trivial parser**: `readSExpr = '(' keyword sexprs* ')'`. Dispatch table on keyword.
- **No ambiguity**: Parens resolve all grouping. No precedence, no associativity.
- **Tool support**: Paren-matching, rainbow brackets, structural editing
  (paredit) work out of the box.
- **Error messages**: "Unexpected token at paren depth 3" — always precise.

## 5. Evaluation: Extend HNIR vs. S-Expression Format

### 5.1 Option A: Extend HNIR with a Parser

Add source positions as comment directives, fix syntactic warts, and write a
recursive-descent parser for the existing LLVM-IR-like syntax.

**Pros:**
- ~85% of structural info is already present
- `Show.scala` printer exists (needs minor fixes)
- LLVM IR familiarity for some contributors

**Cons:**
- Mangled names remain the primary barrier to human writability — adding an
  unmangling layer to the printer is possible but makes the format diverge from
  what `Show` currently produces
- Parser complexity: ~2000 lines of ad-hoc recursive descent with
  context-dependent rules
- Multiple delimiter types and positional ambiguities require careful handling
- The legacy parser was already removed once, suggesting maintainability issues

### 5.2 Option B: S-Expression Format (SNIR)

Design a new WAT-inspired S-expression format with unmangled identifiers.

**Pros:**
- Parser complexity: ~500 lines — generic S-expr parser + keyword dispatch table
- Mangled names eliminated: `$java.lang.String::length` instead of
  `M16java.lang.StringD6lengthiEO`
- Zero syntactic ambiguity — parens resolve everything
- Self-describing — every node tagged with keyword
- Excellent editor/tool support (paren matching, structural editing)
- WAT has proven this approach works for a comparable IR

**Cons:**
- ~30-40% more verbose (parens + explicit keywords)
- New printer needed alongside existing `Show`
- Different syntax style than LLVM IR (S-expr vs. register-transfer)
- Learning curve for contributors used to LLVM-style IR

### 5.3 Comparison Matrix

| Dimension                 | Extend HNIR         | S-Expression (SNIR)   |
|---------------------------|---------------------|-----------------------|
| Human writability         | Poor (mangled names)| Good                  |
| Human readability         | Decent              | Good (more verbose)   |
| Parser complexity         | ~2000 lines         | ~500 lines            |
| Parser ambiguity          | Several cases       | Zero                  |
| Existing printer          | Yes (`Show.scala`)  | New required          |
| Name readability          | Mangled blobs       | Qualified identifiers |
| Editor support            | None special        | Paren matching, paredit |
| Error reporting           | Complex             | Trivial (paren depth) |
| Format verbosity          | Compact             | ~30-40% larger        |
| Incremental adoption      | Same `.hnir` ext    | New `.snir` extension |
| Proven precedent          | LLVM `.ll`          | WAT                   |

### 5.4 Recommendation

**S-expression format (Option B)** is the clear choice for a human-writable
format. The primary use case — being able to *write* NIR by hand — is not
served by extending HNIR because the fundamental problem is mangled names and
context-dependent syntax, not missing features. An S-expression format solves
both problems structurally.

The existing HNIR dump (`Show.dump`) should be kept as-is for its current
debugging purpose. SNIR is a separate format with a separate extension
(`.snir`), co-existing with `.hnir`.

## 6. SNIR Format Specification

### 6.1 Lexical Structure

```
;; Line comments start with ;;
(; Block comments use (; ... ;) nesting ;)

Tokens:
  LPAREN    = '('
  RPAREN    = ')'
  DOLLAR_ID = '$' identifier        ;; named references
  PERCENT   = '%' integer           ;; local variable references
  INTEGER   = [+-]? digit+
  FLOAT     = [+-]? digit+ '.' digit+ ([eE] [+-]? digit+)?
  HEX_FLOAT = '0x' hex_digit+      ;; bit-exact float representation
  STRING    = '"' (escape | char)* '"'
  KEYWORD   = letter (letter | digit | '-' | '_' | '.')*
```

**Identifier rules**: Dollar-identifiers (`$name`) follow Java qualified name
conventions with `::` as the member separator:

```
$java.lang.String               ;; Global.Top
$java.lang.String::length       ;; Global.Member (simple — unambiguous method)
$Main$                          ;; Scala module (object) — Top
$Main$::main                    ;; Module member
```

**Whitespace**: All whitespace (spaces, tabs, newlines) is insignificant except
inside string literals. Indentation is conventional but not required.

### 6.2 Module Structure

A SNIR file wraps all definitions in a top-level `(module ...)`:

```scheme
(module
  ;; Optional metadata
  (@nir-version 1 0)

  ;; Definitions
  (class ...)
  (trait ...)
  (define ...)
  ...)
```

The `(module ...)` wrapper may be omitted for convenience — bare definitions
are also valid (like WAT's module-less abbreviation).

### 6.3 Names and Identifiers

#### 6.3.1 Top-Level Names

Top-level types use `$fully.qualified.Name`:

```scheme
$java.lang.Object                ;; class
$java.io.Serializable            ;; trait
$scala.Predef$                   ;; module (Scala object)
$java.lang.String[]              ;; array type name
```

#### 6.3.2 Member Names

Members use `$Owner::member`:

```scheme
$java.lang.String::length        ;; method
$java.lang.String::value         ;; field
$Main$::main                     ;; method of module
```

#### 6.3.3 Overload Disambiguation

When multiple overloads exist for the same method name, a type signature
annotation is appended. This is needed because NIR distinguishes overloads by
their full type signature:

```scheme
;; Unambiguous — no suffix needed
$PrintStream::flush

;; Ambiguous — disambiguate with type sig
$PrintStream::println             ;; println()
$PrintStream::println/ref/unit    ;; println(Object): Unit
$PrintStream::println/i32/unit    ;; println(int): Unit
```

The `/`-separated type suffix mirrors the types in the signature. This is only
needed when referencing a definition, not when defining it (definitions carry
their full signature inline).

#### 6.3.4 Signature Kinds

The signature kind is encoded as a keyword prefix:

```scheme
(field $String::value private)             ;; Sig.Field
(method $String::length public)            ;; Sig.Method
(ctor (param (ref $Object)))               ;; Sig.Ctor
(clinit)                                   ;; Sig.Clinit
(extern $strlen)                           ;; Sig.Extern
(generated $reflect$apply)                 ;; Sig.Generated
```

#### 6.3.5 Scope

Scope is declared as a keyword following the member name:

```scheme
public                                     ;; Sig.Scope.Public
public-static                              ;; Sig.Scope.PublicStatic
(private $java.lang.String)                ;; Sig.Scope.Private(in)
(private-static $java.lang.String)         ;; Sig.Scope.PrivateStatic(in)
```

#### 6.3.6 Local Variables

Locals use `%n` (numeric index) with optional name annotations:

```scheme
%0                               ;; Local(0)
%5                               ;; Local(5)
```

### 6.4 Types

#### 6.4.1 Primitive Types

Bare keywords, following WAT convention:

```scheme
bool        ;; Type.Bool        1-bit
i8          ;; Type.Byte        8-bit signed
i16         ;; Type.Short       16-bit signed
i32         ;; Type.Int         32-bit signed
i64         ;; Type.Long        64-bit signed
i128        ;; Type.Int128      128-bit signed
f32         ;; Type.Float       32-bit IEEE 754
f64         ;; Type.Double      64-bit IEEE 754
char        ;; Type.Char        16-bit unsigned (UTF-16)
size        ;; Type.Size        platform word size
ptr         ;; Type.Ptr         raw pointer
unit        ;; Type.Unit
null        ;; Type.Null
nothing     ;; Type.Nothing
vararg      ;; Type.Vararg
virtual     ;; Type.Virtual
```

> **Design note**: We use `i32`/`i64`/`f32`/`f64` instead of HNIR's
> `int`/`long`/`float`/`double` to align with WAT convention and avoid
> confusion with Scala/Java type names. `i8`/`i16` replace `byte`/`short`
> for consistency.

#### 6.4.2 Reference Types

```scheme
;; Default: nullable, non-exact
(ref $java.lang.String)

;; Non-nullable
(ref nonnull $java.lang.String)

;; Exact (sealed / final dispatch)
(ref exact $java.lang.String)

;; Both
(ref exact nonnull $java.lang.String)
```

Compare HNIR: `!?ref @"T16java.lang.String"` — where `!` means exact and `?`
means NOT nullable (inverted!). SNIR uses explicit readable keywords.

#### 6.4.3 Aggregate Types

```scheme
;; Fixed-size array: ArrayValue(ty, n)
(array.value i32 10)                     ;; [i32 x 10]

;; Struct: StructValue(tys)
(struct i32 ptr f64)                     ;; {i32, ptr, f64}

;; Function type: Function(args, ret)
(func (param i32 i32) (result i32))      ;; (i32, i32) => i32
(func (param (ref $String)) (result unit)) ;; (ref String) => unit
```

#### 6.4.4 Managed Array Type

```scheme
;; Array(ty, nullable=true)
(array (ref $java.lang.Object))

;; Array(ty, nullable=false)
(array nonnull i32)
```

#### 6.4.5 Mutable Variable Type

```scheme
;; Var(ty)
(var.type i32)
```

### 6.5 Values (Literals)

Every value is a tagged S-expression `(type-keyword literal)`:

```scheme
;; Booleans
(bool true)
(bool false)

;; Null / Unit
(null)
(unit)

;; Integer types
(i8 42)
(i16 1000)
(i32 42)
(i64 100)
(i128 340282366920938463463374607431768211455)
(char 65)                                ;; UTF-16 code unit
(size 10)

;; Floating point — decimal or hex literal for bit-exactness
(f32 3.14)
(f64 2.718281828)
(f32 0x7FC00000)                         ;; NaN with specific payload
(f64 0x8000000000000000)                 ;; -0.0

;; Zero-initialized value of given type
(zero i32)
(zero (ref $java.lang.Object))

;; Composite values
(struct.value (i32 1) (i32 2) (f64 3.0))
(array.value i32 (i32 1) (i32 2) (i32 3))
(bytes "Hello\00World")                  ;; byte string with escapes

;; References
%5                                        ;; local variable (shorthand)
(local %5 i32)                           ;; local with explicit type
(global $Main$::counter i32)             ;; global reference
(string "hello world")                   ;; interned string constant
(const (i32 42))                         ;; constant wrapper
(class.of $java.lang.String)             ;; class literal (classOf)
```

> **Design note**: Hex float literals (`0x...`) ensure bit-exact representation
> of NaN payloads, negative zero, and denormalized numbers — critical for
> round-trip correctness. The parser interprets hex literals as the raw IEEE 754
> bit pattern (via `Float.intBitsToFloat` / `Double.longBitsToDouble`).

### 6.6 Definitions

#### 6.6.1 Class

```scheme
(class $java.lang.String
  (parent $java.lang.Object)
  (trait $java.io.Serializable)
  (trait $java.lang.Comparable)
  (attr final)
  (@pos "rt/java/lang/String.scala" 25 1))
```

#### 6.6.2 Trait

```scheme
(trait $java.io.Serializable
  (@pos "rt/java/io/Serializable.scala" 3 1))

(trait $java.lang.Comparable
  (trait $java.io.Serializable)             ;; super-traits
  (@pos "rt/java/lang/Comparable.scala" 5 1))
```

#### 6.6.3 Module (Scala Object)

```scheme
(module $Main$
  (parent $java.lang.Object)
  (@pos "Main.scala" 1 1))
```

#### 6.6.4 Variable

```scheme
(var $Main$::counter
  (type i32)
  (init (i32 0))
  (@pos "Main.scala" 3 5))
```

#### 6.6.5 Constant

```scheme
(const $Main$::MAX_SIZE
  (type i32)
  (init (i32 1024))
  (@pos "Main.scala" 4 5))
```

#### 6.6.6 Declaration (External/Forward)

```scheme
(declare $libc::strlen
  (type (func (param ptr) (result size)))
  (attr extern)
  (@pos "libc.scala" 10 3))
```

#### 6.6.7 Function Definition

```scheme
(define $Main$::add
  (param %0 i32)
  (param %1 i32)
  (result i32)
  (attr inline-hint)
  (@pos "Main.scala" 5 3)

  ;; Debug info
  (scope 1 (parent 0) (@pos "Main.scala" 5 3))
  (name %0 "a")
  (name %1 "b")

  ;; Body: sequence of basic blocks
  (block %entry (param %0 i32) (param %1 i32)
    (let %2 (i32.add %0 %1))
    (ret %2)))
```

### 6.7 Instructions

#### 6.7.1 Labels (Basic Block Entry)

```scheme
(block %0)                                ;; no params
(block %0 (param %1 i32) (param %2 i32))  ;; with params
(block %entry (param %0 (ref $String[]))) ;; named for readability
```

> **Design note**: Basic blocks use `(block ...)` instead of HNIR's
> `%0(...):` syntax. The keyword-first principle ensures the parser
> always knows what construct it is reading.

#### 6.7.2 Let (Operation Binding)

```scheme
(let %3 (i32.add %1 %2))                  ;; simple
(let %4 (call ...) (unwind %exc %handler)) ;; with unwind handler
```

Scope annotations on `let`:
```scheme
(let %3 (scope 2) (i32.add %1 %2))        ;; in lexical scope 2
```

#### 6.7.3 Control Flow

```scheme
;; Return
(ret %5)
(ret (unit))

;; Unconditional jump
(jump %target)
(jump %target %arg1 %arg2)                ;; with block arguments

;; Conditional branch
(if %cond
  (then (jump %true_block))
  (else (jump %false_block)))

;; Switch
(switch %tag
  (default (jump %otherwise))
  (case (i32 0) (jump %case0))
  (case (i32 1) (jump %case1))
  (case (i32 2) (jump %case2)))

;; Exception throw
(throw %exc)
(throw %exc (unwind %exc2 %handler))      ;; with unwind

;; Unreachable
(unreachable)
(unreachable (unwind %exc %handler))

;; Linktime conditional (compile-time branch)
(linktime-if
  (condition "scala.scalanative.meta.linktimeinfo.isWindows" ieq (bool true))
  (then (jump %windows_impl))
  (else (jump %unix_impl)))
```

### 6.8 Operations

#### 6.8.1 Arithmetic (Binary)

```scheme
;; Integer arithmetic
(i32.add %1 %2)          ;; Bin.Iadd on Type.Int
(i64.sub %1 %2)          ;; Bin.Isub on Type.Long
(i32.mul %1 %2)          ;; Bin.Imul
(i32.sdiv %1 %2)         ;; Bin.Sdiv (signed division)
(i32.udiv %1 %2)         ;; Bin.Udiv (unsigned division)
(i32.srem %1 %2)         ;; Bin.Srem
(i32.urem %1 %2)         ;; Bin.Urem

;; Floating-point arithmetic
(f32.add %1 %2)          ;; Bin.Fadd on Type.Float
(f64.mul %1 %2)          ;; Bin.Fmul on Type.Double
(f64.div %1 %2)          ;; Bin.Fdiv
(f64.rem %1 %2)          ;; Bin.Frem

;; Bitwise operations
(i32.shl %1 %2)          ;; Bin.Shl
(i32.lshr %1 %2)         ;; Bin.Lshr (logical shift right)
(i32.ashr %1 %2)         ;; Bin.Ashr (arithmetic shift right)
(i32.and %1 %2)          ;; Bin.And
(i32.or %1 %2)           ;; Bin.Or
(i32.xor %1 %2)          ;; Bin.Xor
```

> **Design note**: Following WAT, the type is prefixed to the operation name
> (`i32.add`) rather than passed as a bracket parameter (`iadd[int]`). This
> makes every operation self-contained — no separate type annotation needed.

#### 6.8.2 Comparison

```scheme
;; Integer comparisons
(i32.eq %1 %2)           ;; Comp.Ieq
(i32.ne %1 %2)           ;; Comp.Ine
(i32.sgt %1 %2)          ;; Comp.Sgt (signed greater than)
(i32.sge %1 %2)          ;; Comp.Sge
(i32.slt %1 %2)          ;; Comp.Slt
(i32.sle %1 %2)          ;; Comp.Sle
(i32.ugt %1 %2)          ;; Comp.Ugt (unsigned)
(i32.uge %1 %2)          ;; Comp.Uge
(i32.ult %1 %2)          ;; Comp.Ult
(i32.ule %1 %2)          ;; Comp.Ule

;; Float comparisons
(f64.eq %1 %2)           ;; Comp.Feq
(f64.ne %1 %2)           ;; Comp.Fne
(f64.gt %1 %2)           ;; Comp.Fgt
(f64.lt %1 %2)           ;; Comp.Flt
```

#### 6.8.3 Conversions

```scheme
(trunc i32 %val)         ;; Conv.Trunc — narrow integer
(zext i64 %val)          ;; Conv.Zext — zero-extend
(sext i64 %val)          ;; Conv.Sext — sign-extend
(fptrunc f32 %val)       ;; Conv.Fptrunc — f64 → f32
(fpext f64 %val)         ;; Conv.Fpext — f32 → f64
(fptoui i32 %val)        ;; Conv.Fptoui — float → unsigned int
(fptosi i32 %val)        ;; Conv.Fptosi — float → signed int
(uitofp f64 %val)        ;; Conv.Uitofp — unsigned int → float
(sitofp f64 %val)        ;; Conv.Sitofp — signed int → float
(ptrtoint i64 %val)      ;; Conv.Ptrtoint
(inttoptr ptr %val)      ;; Conv.Inttoptr
(bitcast ptr %val)       ;; Conv.Bitcast
(ssizecast i32 %val)     ;; Conv.SSizeCast
(zsizecast i32 %val)     ;; Conv.ZSizeCast
```

#### 6.8.4 Memory Operations

```scheme
;; Load / Store
(load i32 %ptr)
(load i32 %ptr (order acquire))           ;; atomic load
(store i32 %ptr (i32 42))
(store i32 %ptr %val (order release))      ;; atomic store

;; Stack allocation
(stackalloc i32 (i64 1))                   ;; 1 element
(stackalloc (array.value i8 256) (i64 1))  ;; buffer

;; GEP-like element pointer
(elem (struct i32 ptr f64) %ptr (i32 0) (i32 2))  ;; pointer to 3rd field

;; Aggregate extract / insert
(extract %aggr 0)                          ;; extract element at index 0
(insert %aggr (i32 42) 0)                  ;; insert value at index 0

;; Fence
(fence seq-cst)
```

**Memory ordering keywords**: `unordered`, `monotonic`, `acquire`, `release`,
`acq-rel`, `seq-cst`.

#### 6.8.5 High-Level Object Operations

```scheme
;; Class instantiation
(classalloc $java.lang.StringBuilder)
(classalloc $Point (zone %z))              ;; zone-allocated

;; Field access
(field.load i32 %obj $java.lang.String::count)
(field.store i32 %obj $java.lang.String::count (i32 5))
(field %obj $java.lang.String::value)      ;; field pointer

;; Virtual method dispatch
(method %obj $toString                     ;; resolve method
  (sig (func (param (ref $Object)) (result (ref $String)))))
(dynmethod %obj $apply                     ;; dynamic dispatch
  (sig (func (param (ref $Object)) (result (ref $Object)))))

;; Call
(call %func_ptr
  (type (func (param (ref $Object)) (result unit)))
  %arg1 %arg2)

;; Module singleton access
(module $scala.Predef$)

;; Type operations
(as (ref $String) %obj)                    ;; cast (asInstanceOf)
(is (ref $String) %obj)                    ;; type test (isInstanceOf)
(sizeof i32)                               ;; size of type in bytes
(alignof i32)                              ;; alignment of type

;; Boxing/unboxing
(box (ref $java.lang.Integer) %x)
(unbox i32 %obj)

;; Copy
(copy %val)
```

#### 6.8.6 Array Operations

```scheme
(array.alloc (ref $Object) (i32 10))       ;; allocate array
(array.alloc i32 (i32 5) (zone %z))        ;; zone-allocated array
(array.load (ref $Object) %arr (i32 0))    ;; load element
(array.store (ref $Object) %arr (i32 0) %val) ;; store element
(array.length %arr)                        ;; get length
```

#### 6.8.7 Mutable Variable Operations

```scheme
(var i32)                                  ;; allocate mutable local
(var.load %slot)                           ;; read from var
(var.store %slot (i32 42))                 ;; write to var
```

### 6.9 Control Flow

Control flow is expressed through terminators and branch targets.

#### 6.9.1 Branch Targets (`Next`)

```scheme
;; Jump to label with arguments
(jump %target)
(jump %target %arg1 %arg2)

;; Unwind handler
(unwind %exc %handler)                     ;; exc = exception val, handler = target label

;; Switch case
(case (i32 0) (jump %handler))
```

#### 6.9.2 Unwind on Let

Operations that can throw are annotated with an unwind target:

```scheme
(let %result
  (call %func (type (func (param i32) (result i32))) %arg)
  (unwind %exc %catch_block))
```

### 6.10 Attributes

Attributes are expressed as `(attr keyword)` clauses within definitions:

```scheme
;; Inline hints
(attr may-inline)
(attr inline-hint)
(attr no-inline)
(attr always-inline)

;; Specialization hints
(attr may-specialize)
(attr no-specialize)

;; Optimization state
(attr unopt)
(attr noopt)
(attr didopt)
(attr bailopt "reason message")

;; Linkage
(attr extern)
(attr extern blocking)
(attr link "z")                            ;; native library link
(attr link-cpp-runtime)
(attr define "SOME_MACRO")
(attr dyn)                                 ;; dynamic linkage
(attr stub)                                ;; stub implementation

;; Modifiers
(attr abstract)
(attr volatile)
(attr final)
(attr safe-publish)
(attr linktime-resolved)
(attr uses-intrinsic)

;; Alignment
(attr align 16)
(attr align 32 "group-name")
```

### 6.11 Debug Information

#### 6.11.1 Source Positions

Source positions use the `@pos` directive — a convention borrowed from WAT's
custom annotations:

```scheme
(@pos "relative/path/to/File.scala" 42 10)  ;; file, line, column
```

Positions can appear on:
- Definitions (classes, methods, vars, etc.)
- Blocks (label position)
- Individual `let` instructions

```scheme
(define $Main$::example
  (@pos "Main.scala" 5 3)                   ;; definition position
  (param %0 i32)
  (result i32)

  (block %entry (param %0 i32)
    (@pos "Main.scala" 6 5)                  ;; label position
    (let %1 (@pos "Main.scala" 7 5) (i32.add %0 (i32 1)))
    (ret %1)))
```

#### 6.11.2 Lexical Scopes

```scheme
(define $Main$::example
  ...
  ;; Scope tree: id, parent, source position
  (scope 1 (parent 0) (@pos "Main.scala" 5 3))
  (scope 2 (parent 1) (@pos "Main.scala" 8 7))

  (block %0 (param %0 i32)
    (let %1 (scope 1) (i32.add %0 (i32 1)))     ;; in scope 1
    (let %2 (scope 2) (i32.mul %1 (i32 2)))      ;; in scope 2
    (ret %2)))
```

#### 6.11.3 Local Variable Names

```scheme
(define $Main$::example
  ...
  (name %0 "x")
  (name %1 "y")
  (name %3 "result")
  ...)
```

### 6.12 Linktime Conditions

```scheme
;; Simple condition
(linktime-if
  (condition "scala.scalanative.meta.linktimeinfo.isWindows"
    ieq (bool true))
  (then (jump %windows))
  (else (jump %unix)))

;; Complex condition (conjunction/disjunction)
(linktime-if
  (condition.complex and
    (condition "is64Bit" ieq (bool true))
    (condition "isLinux" ieq (bool true)))
  (then (jump %linux64))
  (else (jump %other)))
```

## 7. Complete Example

The following example shows a complete Scala module in SNIR format:

**Scala source:**
```scala
object Main {
  def main(args: Array[String]): Unit = {
    val x = 1 + 2
    println(x)
  }
}
```

**SNIR representation:**

```scheme
(module
  (@nir-version 1 0)

  ;; Type hierarchy
  (module $Main$
    (parent $java.lang.Object)
    (@pos "Main.scala" 1 1))

  ;; main method
  (define $Main$::main
    (param %0 (ref $java.lang.String[]))
    (result unit)
    (scope public)
    (@pos "Main.scala" 2 3)

    ;; Debug info
    (scope 1 (parent 0) (@pos "Main.scala" 2 3))
    (scope 2 (parent 1) (@pos "Main.scala" 3 5))
    (name %0 "args")
    (name %3 "x")

    ;; Body
    (block %entry (param %0 (ref $java.lang.String[]))

      ;; val x = 1 + 2
      (let %1 (scope 2) (@pos "Main.scala" 3 13)
        (i32.add (i32 1) (i32 2)))

      ;; println(x)
      (let %2 (scope 1) (@pos "Main.scala" 4 5)
        (module $scala.Predef$))
      (let %3 (scope 1) (@pos "Main.scala" 4 5)
        (method %2 $println
          (sig (func (param (ref $java.lang.Object)) (result unit))
               public)))
      (let %4 (scope 1) (@pos "Main.scala" 4 5)
        (box (ref $java.lang.Integer) %1))
      (let %5 (scope 1) (@pos "Main.scala" 4 5)
        (call %3
          (type (func (param (ref nonnull exact $java.lang.Object)) (result unit)))
          %4)
        (unwind %exc %unwind_handler))

      (ret (unit))))

  ;; Clinit
  (define $Main$::<clinit>
    (result unit)
    (scope public-static)
    (@pos "Main.scala" 1 1)

    (block %entry
      (let %0 (@pos "Main.scala" 1 1)
        (classalloc $Main$))
      (ret (unit)))))
```

## 8. Comparison: HNIR vs. SNIR Side-by-Side

### 8.1 Class Definition

**HNIR:**
```
final class @"T16java.lang.String" : @"T16java.lang.Object", @"T22java.io.Serializable"
```

**SNIR:**
```scheme
(class $java.lang.String
  (parent $java.lang.Object)
  (trait $java.io.Serializable)
  (attr final))
```

### 8.2 Method Definition

**HNIR:**
```
def @"M16java.lang.StringD6lengthiEO" : () => int {
  %0(%this : ref):
    %1 = fieldload[int] %0 : !?ref @"T16java.lang.String", @"M16java.lang.StringF5countiO"
    ret %1 : int
}
```

**SNIR:**
```scheme
(define $java.lang.String::length
  (param %0 (ref nonnull exact $java.lang.String))
  (result i32)
  (scope public)

  (block %entry (param %0 (ref nonnull exact $java.lang.String))
    (let %1 (field.load i32 %0 $java.lang.String::count))
    (ret %1)))
```

### 8.3 Virtual Dispatch

**HNIR:**
```
%2 = method %1 : ref, "D7printlnL16java.lang.ObjectuEO"
%3 = call[(!?ref @"T16java.lang.Object") => unit] %2 : ptr, %0 : ref
```

**SNIR:**
```scheme
(let %2 (method %1 $println
          (sig (func (param (ref $java.lang.Object)) (result unit))
               public)))
(let %3 (call %2
          (type (func (param (ref nonnull exact $java.lang.Object)) (result unit)))
          %0))
```

### 8.4 Type References

| Concept | HNIR | SNIR |
|---------|------|------|
| Nullable ref | `ref @"T16java.lang.String"` | `(ref $java.lang.String)` |
| Non-nullable | `?ref @"T16java.lang.String"` | `(ref nonnull $java.lang.String)` |
| Exact | `!ref @"T16java.lang.String"` | `(ref exact $java.lang.String)` |
| Both | `!?ref @"T16java.lang.String"` | `(ref exact nonnull $java.lang.String)` |
| Function | `(int, int) => int` | `(func (param i32 i32) (result i32))` |
| Struct | `{int, ptr, double}` | `(struct i32 ptr f64)` |
| Array val | `[int x 10]` | `(array.value i32 10)` |

## 9. Implementation Plan

### Phase 1: Core Parser & Printer

1. **S-expression lexer** — tokenize `(`, `)`, `$id`, `%n`, keywords,
   integers, floats, hex floats, strings.
2. **S-expression parser** — recursive descent: `readSExpr = '(' keyword
   sexprs* ')'`. Build generic S-expression tree.
3. **SNIR→NIR converter** — dispatch on keyword, construct NIR AST nodes.
   Uses `Mangle` to convert `$qualified::name` back to mangled `Global`/`Sig`.
4. **NIR→SNIR printer** — new `SNIRShow.scala` alongside existing `Show.scala`.
   Uses `Unmangle` to convert mangled names to `$qualified::name`.

Estimated: ~500 lines for lexer+parser, ~400 lines for converter, ~500 lines
for printer.

### Phase 2: Integration

5. **CLI tool**: `snir` subcommand for Scala Native CLI:
   - `snir assemble input.snir -o output.nir` — text to binary
   - `snir disassemble input.nir -o output.snir` — binary to text
   - `snir dump classes.jar -o output.snir` — dump classpath NIR as text
6. **Dump option**: Add `withDumpSNIR(true)` to `NativeConfig` alongside
   existing `withDump(true)`.
7. **Test fixtures**: Convert existing binary `.nir` test fixtures to `.snir`
   for readability.

### Phase 3: Tooling

8. **Editor support**: TextMate/VS Code grammar for `.snir` syntax highlighting.
9. **Validation**: `snir check input.snir` — parse and validate without
   producing output.

## 10. Open Questions

1. **Overload disambiguation syntax**: The `/type/type` suffix for overloaded
   methods is one option. Alternatives include inline `(sig ...)` annotations
   at every call site, or a WAT-like `(type $typedef)` indirection. The current
   proposal uses inline signatures at reference sites, which is verbose but
   unambiguous.

2. **Folded expressions**: WAT allows folded S-expressions where operands are
   nested: `(i32.add (i32.const 1) (i32.const 2))`. Should SNIR support this
   as syntactic sugar for `let` + flat instructions? This would improve
   readability for simple expressions but adds parser complexity.

3. **Module wrapper**: Should `(module ...)` be required or optional? WAT
   allows both. For single-class files, omitting it reduces noise.

4. **Compatibility with existing `Show`**: The existing `Show.scala` and `.hnir`
   format should remain unchanged. SNIR is a separate, additive format. Both
   can coexist.

5. **Signature encoding in names**: The current proposal requires explicit
   `(sig ...)` annotations at method reference points for disambiguation.
   An alternative is to encode the full signature into the name string
   (like `$String::length/i32`) but this reintroduces a form of mangling.

6. **Interplay with the optimizer**: After optimization, new synthetic
   definitions appear (duplicates, specializations). Their names use
   `Sig.Duplicate` and `Sig.Generated`. The SNIR name format needs to handle
   these: e.g., `$String::length$dup$1` or `(generated dup-of $String::length)`.

---

*This proposal is a draft. Feedback and refinements are welcome.*
