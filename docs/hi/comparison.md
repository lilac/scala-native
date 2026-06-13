# Hi for Programmers Coming From Other Languages

> A cheat sheet and design comparison. Hi is a statically-typed, expression-oriented, **native-AOT + garbage-collected** language built as a frontend over Scala Native (see [spec.md](spec.md)). This doc maps Hi's concepts onto the languages you already know and explains the one design decision people ask about most: **why Hi has no `impl`, and how it does typeclasses, traits, extensions, and dynamic dispatch.**

---

## 1. Where Hi sits

| | Manual / no GC | Garbage-collected |
|---|---|---|
| **Native AOT binary** | C, C++, Rust, Swift, Zig | **Hi**, Go, D, Crystal, Nim |
| **Managed VM / JIT** | — | Java, Scala, Kotlin, C# |

Hi targets the **GC + native-AOT** quadrant (Go's quadrant) but aims to be far more expressive than Go: sum types with exhaustive matching, a real trait/typeclass system, generics, expression-orientation, structural records, and contextual abstraction — while keeping one self-contained native binary, fast startup, and goroutine-style virtual threads (no `async`/`await` coloring). Its surface borrows from **OCaml** (lightweight application `f x y`), **Scala 3** (traits, `given`/`using`, `extension`, union/intersection types), **Kotlin** (`fun`), and **Swift** (the `struct`/`class` value/reference split).

---

## 2. The part everyone asks about: traits, instances, and dispatch

Hi deliberately has **one** keyword for each distinct idea, and **no `impl`**. Rust's `impl Trait for Type` and Scala 3's `given` both solve "make a type satisfy a trait," and Hi found that `given` strictly dominates: it also does *conditional* instances and *value/primitive* types uniformly, which `impl` cannot. So Hi keeps `given` and drops `impl`. Trait satisfaction then splits cleanly along the axis that actually matters — **do you own the type, and do you need static or dynamic dispatch?**

### The four axes

| Mechanism | Use it for | Dispatch | Owns the type? | Hi keyword |
|---|---|---|---|---|
| **Nominal conformance** | "I'm defining this type; it implements these traits" | **Dynamic** (itable) | Yes | `class C <: Show` |
| **Typeclass instance** | "Make a type I don't own (or a primitive/value type) satisfy a trait; conditional instances" | **Static** (dictionary) | No | `given Show[Int] { … }` |
| **Extension methods** | "Add `.helper` syntax to a type — no trait, no conformance" | **Static** (direct call) | No | `extension (x: T) { … }` |
| **Existential trait object** *(deferred — [§2.2](spec.md))* | "A `List[dyn Show]` of mixed payloads I don't own, dispatched per element" | **Dynamic** (carried dictionary) | No | `dyn Show` |

Two rules make this coherent:

1. **`.m` resolution is a fixed three-tier lookup** ([spec §6.10](spec.md)): (1) a real member / itable method → (2) an in-scope `extension` → (3) a trait method witnessed by an in-scope `given`. Members beat extensions beat typeclass methods; ambiguity *within* a tier is an error. So `3 .show` works when `given Show[Int]` is in scope, with a defined meaning and no magic.
2. **Coherence is closed-world.** At most one `given` per `(Trait, type-head)` across the whole link — the one real guarantee Rust's `impl` provided, recovered for free because Hi links closed-world.

### Why not just copy Swift?

Swift's single `extension` keyword does **both** "add methods" and "declare protocol conformance" (`extension Int: Show {}`). The cost is the infamous gotcha: a method that's a *protocol requirement* dispatches dynamically, but a method living *only* in a protocol extension dispatches **statically** — same call syntax, two dispatch rules, decided invisibly. Hi refuses to conflate them: `extension` is *only* static sugar, conformance is *only* `<:`/`given`. One call site, one dispatch rule.

Hi still gets Swift's useful feature — **protocol-extension default methods** — but through plain concrete trait members:

```hi
trait Ord[A] {
  fun lt  (self: A) (o: A): Bool                 // abstract requirement (no body)
  fun gte (self: A) (o: A): Bool = !(self .lt o)  // default method (has a body)
}
```

A bodiless `fun` is abstract; a `fun` with a body in a trait is a default. Both dispatch the same way — no static/dynamic split.

---

## 3. "Coming from…" quick translation

### Rust

| Rust | Hi | Notes |
|---|---|---|
| `trait Show { … }` | `trait Show[A] { fun show (self: A): String }` | trait gains an explicit self-type param |
| `impl Show for Foreign` | `given Show[Foreign] { … }` | retroactive → dictionary |
| `impl Show for MyType` (you own it) | `class MyType <: Show { … }` | nominal → real itable |
| `impl<A: Show> Show for Vec<A>` | `given showList[A] (using Show[A]): Show[List[A]] = …` | conditional instance |
| `fn f<T: Show>(x: T)` | `fun f[A] (x: A) (using Show[A])` | **no** context bound; explicit `using` |
| `dyn Show` / `Box<dyn Show>` | `dyn Show` *(deferred)* — meanwhile hand-roll (§4) | existential = value + dictionary |
| `enum E { A, B(i32) }` | `type E = \| A \| B(Int)` | ADT |
| `match` | `e match { \| pat => … }` | **postfix**; braces delimit arms |
| ownership / borrow / lifetimes | — | GC; no borrow checker |
| `Result` / `?` | `throw` / `try e catch { … }` | exceptions, not result types |

The key reframe: Rust's `dyn Trait` is **not** a method added to the foreign type's vtable — it's a fat pointer `(data, vtable)` where the vtable is attached at the coercion. That's dictionary passing. Hi already has the dictionary (`given`); `dyn` is just the box.

### Swift

| Swift | Hi | Notes |
|---|---|---|
| `protocol P { … }` | `trait P[A] { … }` | |
| `extension Foreign: P {}` (conformance) | `given P[Foreign] { … }` | **split**: conformance ≠ helper methods |
| `extension T { func helper() … }` | `extension (x: T) { fun helper () … }` | helper methods only, always static |
| protocol-extension default | concrete trait member `fun m (self) = …` | no dispatch gotcha |
| `any P` | `dyn P` *(deferred)* | boxed existential |
| `some P` (opaque return) | `fun f[A] … (using P[A])` | generic + `using` |
| `struct` (value) / `class` (ref) | `struct` / `class` | same split; Hi structs can't hold managed refs in MVP ([§6.3](spec.md)) |
| `enum` with associated values | `type E = \| …` | ADT |
| `T?` optionals | `Option[T]` | ADT, no special `?` sugar |

### Scala 3

Closest relative — most things map 1:1.

| Scala 3 | Hi | Notes |
|---|---|---|
| `given Show[Int] with { … }` | `given Show[Int] { … }` | drop `with` |
| `given f[A](using Show[A]): Show[List[A]] = …` | `given f[A] (using Show[A]): Show[List[A]] = …` | same idea |
| `def f[A](x: A)(using Show[A])` | `fun f[A] (x: A) (using Show[A])` | `def` → `fun` |
| `extension (x: T) def m = …` | `extension (x: T) { fun m () = … }` | braced body, `fun` members |
| `extension (x: A)(using Show[A]) def …` | *(deferred — [§2.2](spec.md))* | use a trait member instead |
| `trait` / `sealed trait` + `case`s | `trait` / `type E = \| …` | ADTs are a `type` with leading `\|` |
| `case class Point(x: Int, y: Int)` | `class Point (x: Int, y: Int)` or record `(x = 1, y = 2)` | |
| `class C extends D with E` | `class C <: D, E` | `<:`, comma-separated |
| implicit conversions | — | not in MVP |

### Go, Java/Kotlin, OCaml (briefly)

- **Go:** `interface` → `trait`, but conformance is **explicit** (`<:` or `given`), not structural. `go f()` → `Thread.ofVirtual.start { => f () }` (same direct, blocking style — virtual threads, no function coloring). Errors are exceptions, not return values.
- **Java/Kotlin:** `interface` → `trait`; `implements`/`extends` → `<:`; default methods → trait default methods; sealed hierarchies / `data class` → ADTs / records. `fun` is curried by default.
- **OCaml:** application `f x y`, `match`, variants, and records all carry over directly; `match` is postfix (`e match { … }`). No functors/modules — use `object`/packages. Type inference is local/bidirectional, **not** Hindley-Milner — public `fun` signatures need annotations.

---

## 4. Worked example: retroactive dynamic dispatch (pair-by-hand → `dyn`)

The one capability that *seems* lost by dropping `impl` is "dynamically dispatch a method on a type I don't own, in a heterogeneous collection." You don't lose it — it's an **existential**, and every language implements it the same way: pair each value with a witness dictionary. Hi will spell that `dyn Trait`; until it ships, you build the pair by hand.

### Before `dyn` — pair by hand

```hi
package examples.dynshow

import std.io.printf

trait Show[A] { fun show (self: A): String }

given Show[Int]  { fun show (self: Int):  String = "an Int"  }
given Show[Bool] { fun show (self: Bool): String = "a Bool" }

// A hand-rolled existential: this class has *forgotten* its payload's type,
// keeping only a thunk that closed over (value, dictionary) while both were known.
class AnyShow (render: Unit -> String) {
  fun show (self: AnyShow): String = self.render ()
}

// Factory: at the call site A and its `given Show[A]` are both in scope,
// so we can capture them before A is erased.
fun anyShow[A] (x: A) (using s: Show[A]): AnyShow =
  AnyShow(render = { u => s.show x })

object Main {
  fun main (args: Array[String]): Unit = {
    val xs = [ anyShow 3, anyShow true, anyShow 7 ]   // heterogeneous: Array[AnyShow]
    var i = 0
    while i < xs.length do {
      printf "%s\n" [xs.get i .show]                    // dynamic, per element
      i = i + 1
    }
  }
}
```

The closure `{ u => s.show x }` captures both the value `x` and the resolved dictionary `s`. Each `AnyShow` is a uniform reference type, so `Array[AnyShow]` is fine, and `xs.get i .show` calls through the captured dictionary. This is exactly a `Box<dyn Show>` / `any Show` — built explicitly. *(Iterating by index with `.length`/`.get` because the fluent collections API, including `Array.foreach` and `for … in` over arrays, is deferred to the collections milestone — [§2.2](spec.md).)*

### After `dyn` — compiler-synthesized

```hi
package examples.dynshow

import std.io.printf

trait Show[A] { fun show (self: A): String }

given Show[Int]  { fun show (self: Int):  String = "an Int"  }
given Show[Bool] { fun show (self: Bool): String = "a Bool" }

object Main {
  fun main (args: Array[String]): Unit = {
    val xs: Array[dyn Show] = [ 3, true, 7 ]   // each element boxed with its given automatically
    var i = 0
    while i < xs.length do {
      printf "%s\n" [xs.get i .show]            // dynamic dispatch through the carried dictionary
      i = i + 1
    }
  }
}
```

`dyn Show` is the existential `AnyShow` was emulating: each element is packed with its `given Show[…]` at the coercion (the array literal), and `.show` dispatches through the carried dictionary. It lowers to a synthesized class holding payload + dictionary — **no new NIR**, same machinery you wrote by hand. (Owned types never need this: `class C <: Show` already gives per-element dynamic dispatch via the real itable.)

---

## 5. Syntax gotchas worth knowing up front

These trip up newcomers regardless of source language ([spec §4](spec.md)):

- **Whitespace decides the dot.** `a.f` (tight) is tightest-precedence selection; `a .f` (space before `.`) is *chain selection* at application precedence — `3.add 4 .times 5` means `((3.add 4).times 5)`. Put a space before every chaining dot.
- **Juxtaposition curries; parens build one tuple.** `f x y` passes two curried args; `f (x, y)` passes **one** tuple. (OCaml model — `(` is not whitespace-significant.)
- **`else` is mandatory.** `if c then a else b` is an expression; there is no one-armed `if`.
- **`match` is postfix.** `e match { | pat => … }`, and it chains after method pipelines: `xs .map f match { … }`.
- **Significant newlines, no indentation rule.** A newline ends a statement (with continuation rules); indentation never opens a scope. A line starting with `(`/`[`/`+`/`-` is a *new* statement — wrap long calls in parens.
- **Lambdas use braces, never `fun`.** `{ x => e }`, `{ (x, y) => e }`; `fun` is only for named declarations.
- **No `new`.** Construction is application: `Point(x = 1, y = 2)`.
- **Arrays, not generic indexing.** `[1, 2, 3]` is an array literal; `xs.get i` / `xs.set i v` / `xs.length` — `xs[i]` is reserved (parses as type application).
- **Case decides meaning.** Lowercase head = value/binding/type-variable; Uppercase head = type/constructor. `f x` is a call; `Point x` is construction.

---

*See [spec.md](spec.md) for the normative definitions. This doc is an orientation aid, not a specification.*
