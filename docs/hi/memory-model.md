# Hi Memory Model — Value Types, References, and GC Safety

> A companion to [spec.md](spec.md) (non-normative; the spec wins on any conflict). It explains one rule that surprises people — **why a Hi `struct` may not hold a managed reference** — how the garbage collector makes that rule load-bearing, and why Hi's *safe* `struct` is **not** the same thing as Scala Native's `scala.scalanative.unsafe.CStruct`, which looks similar but carries the opposite contract.

---

## 1. `struct` vs `class`: the value/reference split

Hi has two aggregate kinds (the Swift split):

- **`struct`** — a **value** type: copied by value, no identity, no inheritance, lives on the stack or *embedded inside* its enclosing object. For small, copyable bundles: `Point`, `Vec3`, `Complex`, a color, a fixed-point number.
- **`class`** — a **reference** type: heap-allocated, has identity, single inheritance + traits, passed by reference.

The MVP rule that catches people ([spec §6.3](spec.md#L862)):

> A `struct` field's declared type may be **only** a primitive, `Ptr`, or another all-primitive/`Ptr` struct. A managed reference — `class`, `String`, `Array`, ADT, trait, record, union/intersection, **or a box class** (`java.lang.Integer`, …) — is a **compile error at the declaration site.**

```hi
struct Vec3 (x: Double, y: Double, z: Double)   // ✅ all primitive
struct Tagged (id: Int, name: String)           // ❌ compile error: String is a managed reference
class  Tagged (id: Int, name: String)            // ✅ use a class instead
```

This is not arbitrary strictness — it is the GC being honest about what it can scan.

---

## 2. Why the restriction exists (the GC mechanics)

Scala Native's default Immix/Commix collector scans **heap-object fields precisely** using a per-type reference-offset bitmap from RTTI. As implemented in `MemoryLayout.referenceFieldsOffsets`, that bitmap records:

- the offsets of **top-level `RefKind` fields**, plus
- exactly **one** synthetic shape — `StructValue(RefKind :: ArrayValue(Byte, n) :: Nil)`, a single reference + alignment padding.

It does **not** descend into a general multi-field `StructValue` to record the offsets of references *buried inside it*. So if a managed reference lived inside a by-value struct that is itself a field of a heap object, that reference would be **invisible to the precise scan** — the collector wouldn't trace it, and the pointed-to object could be freed while still in use. That is a use-after-free, produced silently. The language forbids the shape that would cause it.

(The **stack** is scanned *conservatively* — any stack word that looks like a heap pointer is treated as a root — so a reference in a stack-resident struct would actually be found. But "this struct never escapes to the heap" isn't a free property to prove: storing it in a class/array field, boxing it, or using it as an erased generic argument all move it to the heap. Enforcing stack-confinement is its own feature, so it's deferred.)

The takeaway is the simple rule: **if a value must hold a managed reference, make it a `class`.** A `class` gets a full reference-offset bitmap covering its `RefKind` fields ([spec §6.3](spec.md#L880)), so it may hold references freely. Heap + GC is the default for the GC+native niche anyway; reaching for a `class` costs you nothing you were trying to keep.

*Lift paths (post-MVP, both contained):* make `referenceFieldsOffsets` recurse into nested `StructValue`s and emit `outer + inner` offsets, **or** have the frontend flatten ref-carrying structs into their enclosing class (SROA, semantics-preserving since structs have no identity). `Array[struct-with-refs]` stays deferred either way — typed-array classes carry no per-element ref map.

---

## 3. The lookalike trap: `scala.scalanative.unsafe.CStruct`

A reader who knows Scala Native interop will recognize `CStructN[T1, …, TN]` (see the [interop guide](../user/interop.md)) and reasonably ask: *does `CStruct` enforce the same no-reference rule?*

**No — and it does not even error.** `CStruct2[String, Int]` **compiles cleanly.** Here is exactly why, from the source.

A `CStructN` field type only needs a [`Tag`](../../nativelib/src/main/scala/scala/scalanative/unsafe/Tag.scala#L20) (a `Tag` supplies size/alignment/load/store). The struct's `Tag` is materialized by demanding a `Tag` per field:

```scala
implicit def materializeCStruct2Tag[T1: Tag, T2: Tag]: Tag.CStruct2[T1, T2]   // Tag.scala:2479
```

And **every reference type has a `Tag`** — a pointer-sized one, derived from its `ClassTag`:

```scala
final case class Class[T <: AnyRef](of: java.lang.Class[T]) extends Tag[T] {   // Tag.scala:62
  def size = SizeOfPtr;  def alignment = SizeOfPtr
  override def load(p)     = loadObject(p).asInstanceOf[T]
  override def store(p, v) = storeObject(p, v.asInstanceOf[Object])
}
implicit def materializeClassTag[T <: AnyRef: ClassTag]: Tag[T] = Tag.Class(...)  // Tag.scala:2407
```

So `materializeCStruct2Tag[String, Int]` happily finds a `Tag.Class` for `String`, and the type checks. There is no type-level rejection of reference fields.

**It compiles, but it is memory-unsafe.** A `CStruct` lives in the `unsafe` package, is **off-heap**, and is reached only through a raw `Ptr` backed by `malloc` / `stackalloc` / `Zone` memory — memory the **GC never scans**. A reference whose only root sits inside a `CStruct` can therefore be collected, leaving a dangling pointer. Scala Native permits this on purpose: `unsafe` makes no safety promise and delegates correctness to the programmer (the same package gives you `!ptr`, use-after-free, and explicitly "undefined behavior"). If you do store object pointers in a `CStruct`, you must keep those objects reachable through a *managed* root elsewhere.

> Unrelated limit, easy to confuse: the interop table's "`struct { int x, y; }` → Not supported" is about passing a struct **by value** to an extern C function (only `Ptr[CStructN]` is supported). That is not about reference fields.

---

## 4. Side by side

| | `scala.scalanative.unsafe.CStruct` | Hi `struct` |
|---|---|---|
| Namespace / promise | `unsafe` — **no** safety guarantee | safe, managed; **no** `unsafe` opt-out |
| Where the value lives | off-heap, behind a raw `Ptr` (`malloc`/`stackalloc`/`Zone`) | GC heap (embedded in a class) or stack |
| Reference-typed field | **compiles** (via `Tag.Class`) | **compile error** at declaration |
| GC scans the storage? | no (off-heap) | yes (precise bitmap when heap-embedded) |
| Who guarantees safety | the programmer | the compiler |
| Purpose | C-ABI layout / FFI | safe, copyable value aggregate |

Same-looking idea, opposite contracts. The enforcement differs *because* the guarantees differ: `CStruct` already opted out of safety, so it can permit references; Hi's `struct` promises safety and may be embedded in a GC object, so it **must** be precisely scannable and cannot push the burden onto the user.

---

## 5. Practical guidance

- **Use `struct` for all-primitive bundles** — geometry, colors, fixed-point, small numeric tuples. These are exactly the cases the MVP allows, and they avoid heap allocation and GC pressure.
- **Need a `String`, `Array`, another object, or an ADT inside it? Use a `class`.** Nothing is unexpressible — it's a representation choice, and a heap object is the natural default here.
- **`CStruct` is for C interop only** — reach for it through `Ptr` in `unsafe` code, and if you stash object pointers in one, keep the objects alive via a managed reference yourself.

---

*Normative rules live in [spec.md §6.3](spec.md#L862). Cross-language framing is in [comparison.md](comparison.md). This doc is an orientation aid, not a specification.*
