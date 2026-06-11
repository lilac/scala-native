# Editor Cursor Structs in Scala Native

Worked examples of modeling an editor cursor (a selection range with start
and end positions) as a native struct, comparing the two available
approaches: `CStructN` type aliases and the internal `@struct` annotation.

## Examples

| File | What it shows |
|------|---------------|
| [SimpleCursorExample.scala](SimpleCursorExample.scala) | Minimal cursor as `CStruct4[Int, Int, Int, Int]` with free-standing helper functions |
| [EditorCursorExample.scala](EditorCursorExample.scala) | Full-featured cursor: nested structs (`CStruct2[Position.Ref, Position.Ref]`), implicit-class accessors, selection, clamping, copying |
| [StructAnnotationExample.scala](StructAnnotationExample.scala) | The `@struct` annotation, side by side with the equivalent `CStruct` code |

Each file is a standalone [scala-cli](https://scala-cli.virtuslab.org) script:

```console
$ scala-cli run SimpleCursorExample.scala
$ scala-cli run EditorCursorExample.scala
$ scala-cli run StructAnnotationExample.scala
```

## Memory Layout

A cursor struct `CStruct4[Int, Int, Int, Int]` occupies 16 contiguous bytes:

```
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ startLine    │ startCol     │ endLine      │ endCol       │
│ (4 bytes)    │ (4 bytes)    │ (4 bytes)    │ (4 bytes)    │
│ offset 0     │ offset 4     │ offset 8     │ offset 12    │
└──────────────┴──────────────┴──────────────┴──────────────┘
```

## What the Compiler Generates (NIR)

### Stack allocation

```scala
val cursor = stackalloc[CStruct4[Int, Int, Int, Int]]()
```

```nir
%cursor = stackalloc[{i32, i32, i32, i32}] 1
```

- Allocates 16 bytes on the stack, returns a pointer to them
- Memory is zeroed out by default
- Automatically reclaimed when the function returns

### Field assignment

```scala
cursor._1 = 10  // Set startLine
```

```nir
%field_ptr = elem[{i32, i32, i32, i32}] %cursor, 0, 0
store[i32] %field_ptr, 10
```

`elem` computes the pointer to field 1 (offset 0); `store` writes `10` there.

### Field access

```scala
val line = cursor._1  // Get startLine
```

```nir
%field_ptr = elem[{i32, i32, i32, i32}] %cursor, 0, 0
%line = load[i32] %field_ptr
```

### Copying structs

```scala
val cursor2 = stackalloc[CStruct4[Int, Int, Int, Int]]()
!cursor2 = !cursor  // Copy entire struct
```

Copies all 16 bytes from `cursor` to `cursor2` (a struct-value load and
store, typically lowered to a `memcpy`).

## Key Concepts

1. **Value semantics** — structs are copied by value; no garbage-collection
   overhead, fast local operations.

2. **Allocation choices**

   ```scala
   stackalloc[T]()  // Fast, freed automatically when the function returns
   alloc[T]()       // Zone-scoped native memory, requires an implicit Zone
   ```

   Never let a `stackalloc` pointer escape the function that allocated it,
   and never use a `Zone`-allocated pointer after its zone is closed.

3. **Field access compiles to plain loads and stores**

   ```scala
   cursor._1        // load from (cursor + 0)
   cursor._2        // load from (cursor + 4)
   cursor._3        // load from (cursor + 8)
   cursor._4        // load from (cursor + 12)
   ```

4. **Zero-cost wrappers** — an implicit value class adds no runtime overhead:

   ```scala
   implicit class CursorOps(val ptr: Ptr[CursorStruct]) extends AnyVal
   ```

## Performance Characteristics

| Operation | Cost | Notes |
|-----------|------|-------|
| `stackalloc` | ~1 cycle | Stack pointer bump (plus zeroing) |
| Field read | 1 memory load | Direct memory access |
| Field write | 1 memory store | Direct memory access |
| Struct copy | ~4 stores | Or a single memcpy call |
| Helper call | Depends on inlining | Small helpers are inlined by the optimizer |

## Common Patterns

### Temporary computation

```scala
def processCursor(line: Int, col: Int): Result = {
  val cursor = stackalloc[CursorStruct]()
  cursor._1 = line
  cursor._2 = col
  // ... use cursor ...
  // reclaimed automatically here
}
```

### Zone-allocated cursor

```scala
Zone.acquire { implicit z =>
  val cursor = alloc[CursorStruct]()
  cursor._1 = line
  cursor._2 = col
  useCursor(cursor)
} // zone freed here; cursor must not be used afterwards
```

### Array of cursors

```scala
val cursors = stackalloc[CursorStruct](10)  // 10 contiguous cursors
cursors(0)._1 = 1
cursors(1)._1 = 2
```

## The @struct Annotation

Scala Native also has a `@struct` annotation that marks a class as an
immutable pass-by-value structure:

```scala
import scala.scalanative.runtime.struct

@struct class Position(val line: Int, val column: Int)
@struct class Cursor(val startLine: Int, val startColumn: Int,
                     val endLine: Int, val endColumn: Int)
```

A `@struct` class:

- compiles to a native struct (like a C struct), laid out with native
  alignment and padding rules
- is passed by value (copied, not referenced)
- must declare all fields as `val` (immutable)

Mutation requires going through a `Ptr[T]` and manual offset arithmetic,
because no field accessors are generated for pointer-based access:

```scala
val cursor = stackalloc[Cursor]()
val startLinePtr = cursor.asInstanceOf[Ptr[Int]]
val startColPtr  = (cursor.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Int]]
!startLinePtr = 0
!startColPtr = 5
```

### @struct vs CStruct

| Feature | `@struct` | `CStruct` |
|---------|-----------|-----------|
| Definition | `@struct class Foo(val x: Int)` | `type Foo = CStruct1[Int]` |
| Field access | Manual pointer arithmetic | `foo._1`, `foo._2`, `foo.at1` |
| Mutability | Immutable (`val` only) | Mutable through the pointer |
| Helper methods | Hard to add | Easy via implicit value class |
| Memory layout | Native struct layout | Native struct layout (same here) |
| Intended use | Internal / LLVM interop | Application and bindings code |

### Where @struct is used in the wild

Scala Native uses it internally for LLVM intrinsics
(`scala.scalanative.runtime.LLVMIntrinsics`):

```scala
@struct class IntOverflow(val value: Int, val flag: Boolean)
@struct class LongOverflow(val value: Long, val flag: Boolean)

def `llvm.sadd.with.overflow.i32`(a: Int, b: Int): IntOverflow = extern
```

That is the sweet spot for `@struct`: internal library code whose interface
must match an LLVM/C ABI exactly and where immutability is desired.

## Recommendation

For application code like a cursor, use `CStruct4[Int, Int, Int, Int]` with
an implicit value class:

- direct field access with `._1` … `._4` and `.at1` … `.at4`
- easy to add named accessors and helper methods
- mutable in place, which cursor state updates need
- identical memory layout to the `@struct` version

Reach for `@struct` only when writing internal library code, matching an
exact C/LLVM ABI, or when type-level immutability is a hard requirement.
