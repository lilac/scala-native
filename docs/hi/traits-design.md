# Hi Trait Model — Self-based vs Parameterized (design notes)

> A companion to [spec.md](spec.md) (non-normative; the spec wins on any conflict). It records **why Hi's traits are Self-based** rather than parameter-based — the design discussion that produced the v0.5 pivot — including the alternatives that were explored and rejected, so the reasoning is not lost to a one-line changelog entry. Cross-language framing is in [comparison.md](comparison.md); the normative rules are [spec §5.3](spec.md) and [§6.10](spec.md).

---

## 1. The question: one construct, two roles

A `trait` in Hi is asked to play two roles that introductory treatments usually keep apart:

| | **OO interface** | **Typeclass** |
|---|---|---|
| methods live in | the object's itable (per *value*) | an external dictionary (per *type*) |
| value vs witness | the value **is** the witness | value ≠ witness (a separate dictionary) |
| who can conform | only types you define (`<:`) | anything, retroactively, conditionally, incl. primitives |
| dispatch | dynamic (runtime type) | static (compile-time, on the static type) |
| multiplicity | one per type (its itable) | many, scope-selected |
| examples | `Drawable`, `Collection[T]` | `Show`, `Ord`, `Monoid` |

The tension that started this: is it sound to serve both from one construct, and if so, how do we keep the seams from leaking?

---

## 2. How other languages resolve it

The instinct "these are different, split them into two keywords" is, empirically, the road **not** taken:

| Language | Interface role | Typeclass role | One construct? |
|---|---|---|---|
| **Rust** | `dyn Trait` (trait object) | `T: Trait` bound (monomorphized) | **Yes** — one `trait`, split at *use* |
| **Swift** | `any P` | `some P` / generic `<T: P>` | **Yes** — one `protocol`, split at use |
| **Scala 3** | `trait` + subtyping | `trait` + `given`/`using` | **Yes** — one `trait`, split at use |
| **Haskell** | — (no subtyping) | `class` | one role only |
| **Go** | `interface` (structural) | — | one role only |

So the modern, mainstream answer (Rust/Scala/Swift) is **one construct, role chosen at the use site**. Languages that "keep them separate" (Haskell, Go) did so by *dropping a role*, not by shipping two parallel constructs. A language with two near-identical trait-like keywords is essentially unheard of, and it would violate Hi's "small, orthogonal surface" principle ([spec §1.5](spec.md)) while still forcing the two to interoperate. **Conclusion: keep one `trait`.**

---

## 3. Two encodings of "the conforming type"

With one construct settled, the real fork is *how the trait names the type that conforms*:

### Parameter-based (Haskell `class Show a`, Scala 3 typeclasses)

The conformer is a **type parameter**:

```
trait Show[A] { fun show (self: A): String }     // A is the conforming type
```

- **Strength:** retroactive instances on foreign/primitive types are natural — `given Show[Int]` makes the dictionary a first-class value `Show[Int]`. Conditional instances and multi-type relations (`Convert[A, B]`) fall out.
- **Weakness:** the conformer is mixed in with ordinary parameters. For an *interface* like a collection, which parameter is the conformer and which is the element? `trait Collection[T]` — is `T` the conformer or the element? You can only tell from `self`'s type, and the trait-as-type/wildcard story gets tangled (see §4).

### Self-based (Rust/Swift/Java)

The conformer is an **implicit, distinguished `Self`**; type parameters are **auxiliary**:

```
trait Show { fun show: String }                              // Self = the conformer (receiver implicit)
trait Collection[T] { fun size: Int;  fun get (i: Int): T }  // Self = collection, T = element
```

- **Strength:** "the conformer" is always `Self`, never a parameter. Interfaces are unambiguous; the value carries its own methods (matches OO intuition and the backend's itables). The receiver is **implicit** (Scala/Swift), so signatures read like ordinary OO interfaces.
- **Weakness:** retroactive instances need the dictionary to be a value even though `Self` is abstract — solved by synthesizing a witness class for `given Show for Int` (representable on the erased/dictionary backend). Symmetric multi-parameter type classes (no privileged conformer) aren't expressible — pick one type as `Self` + auxiliary params (Rust's compromise).

Both are viable. Hi originally chose parameter-based (matching Scala, its closest relative and backend lineage). The pivot to Self-based was forced by the roadmap, below.

---

## 4. The decisive reason Hi went Self-based

Hi's roadmap wants **two** features as first-class, composable types:

- **Parameter wildcards** (Java/Scala): `Collection[?]` — "a collection of *some* element type."
- **Trait-object existentials** (Rust/Swift): `Dyn[Show]` — "*some* value that is a Show, boxed with its dictionary."

These are two *different* existential quantifications:

- `?` quantifies a **type parameter** (the element).
- `Dyn` quantifies the **conformer**.

They can coexist cleanly **only if "the conformer" and "a parameter" occupy different syntactic slots.**

Under **parameter-based** traits the conformer **is** a parameter (`Show[A]`). So the moment you write `Show[?]`, it is ambiguous:

- wildcard-over-the-parameter (the Java reading), **or**
- existential-over-the-conformer (the trait-object reading)?

Same brackets, same slot, two incompatible meanings — and **no choice of spelling fixes it**, because the collision is structural, not lexical. (This is precisely what doomed the bracket markers explored in §5: `Show[?]` for "nominal existential" vs `Show[*]` for "boxed existential" only papered over the deeper conflict, and broke entirely for parameterized interfaces where the bracket already means "element," e.g. `Collection[?]`.)

Under **Self-based** traits the conformer is `Self` (never a parameter), so the two quantifications live in different slots and never collide:

- `Collection[?]` — `?` over the auxiliary parameter `T`.
- `Dyn[Show]` — `Dyn` over `Self`.
- `Dyn[Collection[?]]` — both, composed, unambiguous.

That is the decisive reason. Self-based is the *only* model under which both roadmap features fit without a structural collision. As bonuses, it also:

- makes `Collection[T]` unambiguously an interface (the "is `T` the conformer or the element?" question never arises);
- matches the OO intuition (a value carries its own methods) and maps nominal dispatch directly onto Scala Native's **itables**;
- still expresses associated members (`static fun empty: Self`) and multi-type relations (`trait Convert[B]`, `Self` = source).

---

## 5. What we explored and rejected (so we don't relitigate it)

The path to Self-based passed through several parameter-based patches. Each is recorded here with why it was abandoned:

1. **Parameter-based with an F-bound sugar `<: Show[Self]`.** To use a parameter-based `Show[A]` as an interface (`class Dog <: Show`), we made bare `<: Show` mean `<: Show[Dog]`. It worked for one-parameter traits but needed a special "self-parameter is the sole parameter" rule, broke for multi-parameter traits, and was pure reconciliation machinery. *Gone* — Self-based needs no such sugar (`<: Show` binds `Self = Dog` directly).

2. **The self-default rule "first type parameter = `self`'s type."** Convenient for `Show[A]` (self defaults to `A`) but **wrong** for parameterized interfaces: `trait Collection[T]` would default `self` to the *element* `T`, giving the nonsensical `size (self: T)`. The rule was internally contradictory. *Gone* — Self-based defaults `self` to `Self` (= the collection), always correct.

3. **Bracket existential markers `Show[?]` (nominal) and `Show[*]` (boxed).** An attempt to spell both existentials inside the type-argument brackets. It reused `?`/`*` as conformer-quantifiers, which (a) collides with the Java meaning of `?` as a *parameter* wildcard, (b) is meaningless on a parameterized interface (`Collection[*]` — boxed over what?), and (c) sits in the very slot §4 shows is already taken by the conformer under parameter-based traits. *Gone* — Self-based frees `?` to mean the parameter wildcard and uses `Dyn[…]` (a constructor, different slot) for the conformer existential.

4. **A prefix keyword `dyn Trait`** (Rust/Swift spelling). Workable, but it would be Hi's only prefix *type* operator (an irregularity), needs a precedence slot, and re-reserves `dyn`. *Gone* — `Dyn[Trait]` is ordinary type application (a compiler-known constructor like `Array`), needs zero new grammar, obeys the UPPER_ID-for-types casing rule, and composes with `&` for multi-trait objects (`Dyn[Show & Drawable]`).

5. **Global coherence (one conformance per `(Trait, type)` across the link).** Considered to recover Rust/Haskell-style coherence. *Rejected* in favour of Scala's **scope-based** resolution — it forbade the legitimately useful case of two instances (e.g. ascending and descending `Ord for Int`). Determinism comes from deterministic per-scope lookup instead; the trade-off (instances can diverge across scopes) is accepted, as in Scala.

6. **Explicit `self` parameter (Rust/Go/Python style), `fun show (self): String`.** Self-based traits can spell the receiver either way: explicit (Rust `&self`) or implicit (Swift/Scala `this`/`self`). Explicit `self` has one nicety — the *absence* of a `self` parameter marks an associated member with no extra keyword. *Rejected* in favour of the **implicit** receiver (`fun show: String`, receiver is the keyword `self` in the body) because Hi is **OO-native** and draws its surface from Scala/OCaml/Swift, all of which take the receiver implicitly; explicit `self` everywhere reads less like the OO interfaces Hi wants to make easy. The lost nicety is recovered with a `static` keyword (`static fun empty: Self`, Swift's `static func`) — a small, familiar cost. The receiver still lowers to the first NIR parameter; only the source omits it.

---

## 6. The final design (summary; normative in [spec §6.10](spec.md))

**Declaration** — implicit `Self`, auxiliary params:

```hi
trait Show { fun show: String }                              // receiver implicit; `self : Self` in body
trait Ord  { fun lt (o: Self): Bool;  fun gte (o: Self): Bool = !(self .lt o) }
trait Collection[T] { fun size: Int;  fun get (i: Int): T }
trait Convert[B] { fun convert: B }                          // Self = source, B = target
trait Monoid { static fun empty: Self;  fun combine (o: Self): Self }   // `static` = associated (no receiver)
```

**Conformance** — nominal or retroactive:

```hi
class Dog <: Show { fun show: String = "woof" }              // nominal; Self = Dog; itable; dynamic
class LinkedList[T] <: Collection[T] { … }                   // nominal parameterized interface
given Show for Int { fun show: String = "…" }                 // retroactive; Self = Int; dictionary (self : Int)
given [A: Show] Show for List[A] { … }                        // conditional
```

**Typeclass use** — a `[A: Trait]` bound (dictionary threaded implicitly):

```hi
fun render[A: Show] (x: A): String = x.show
fun max[A: Ord]    (x: A) (y: A): A = if x .gte y then x else y
val z = Int.empty                                             // associated member, type-qualified
```

**Trait in type position** — four orthogonal forms (the payoff):

| Form | Meaning | Box? | Status |
|---|---|---|---|
| `[A: Show]` (bound) | "this `A` conforms" | no | MVP |
| bare `Show` / `Collection[Int]` | nominal existential (reference conformers, itable) | no | MVP |
| `Dyn[Show]` | boxed existential (retroactive + value conformers) | yes | deferred |
| `Collection[?]` | parameter (element) wildcard | n/a | deferred |

Because `?` quantifies a parameter and `Dyn` the conformer, they compose: `Dyn[Collection[?]]`.

The two **existential** forms (bare `Trait`, `Dyn[Trait]`) require the trait to be **existential-eligible** — `Self` only as the receiver, no `static` members, no per-method type params (Rust *object safety* / Swift's Self-requirement rule). `Show`/`Collection[T]`/`Convert[B]` qualify; `Ord` (`lt (o: Self)`) and `Clone` (`clone: Self`) do not, and are used only via `[A: Trait]` bounds. Normative rule in [spec §5.9](spec.md)/[§6.10](spec.md).

---

## 7. Migration map (parameter-based → Self-based)

For anyone reading older drafts or porting Scala:

| Parameter-based (old / Scala) | Self-based (Hi v0.5) |
|---|---|
| `trait Show[A] { fun show (self: A) }` | `trait Show { fun show }` (receiver implicit) |
| `given Show[Int] { … }` | `given Show for Int { … }` |
| `given f[A](using Show[A]): Show[List[A]]` | `given [A: Show] Show for List[A] { … }` |
| `fun f[A] (x: A) (using Show[A])` | `fun f[A: Show] (x: A)` |
| `class Dog <: Show` ≡ `<: Show[Dog]` (F-bound) | `class Dog <: Show` (Self = Dog, no sugar) |
| `Array[Show]` ≡ `Show[?]`; `Show[*]` boxed | `Array[Show]` (no box); `Array[Dyn[Show]]` (boxed); `Collection[?]` is now a *parameter* wildcard |
| explicit `self` / `this` parameter | implicit receiver; `self` keyword in body; `static` for associated members |

---

## 8. Trade-offs and what's deferred

- **Symmetric multi-parameter type classes** (Haskell MPTCs, where no argument is the privileged conformer) are not expressible. Workaround: choose one type as `Self` plus auxiliary params (`trait Convert[B]`), exactly as Rust does. A future associated-type generalization could lift this.
- **Parameter-wildcard checking** (`Collection[?]` capture/bounds) is deferred; the grammar reserves `?` now so the feature lands collision-free ([spec §5.9](spec.md)).
- **`Dyn[Trait]`** (the boxed existential) is deferred; until it ships, pair value + dictionary by hand in a one-field wrapper `class` (worked example in [comparison.md §4](comparison.md)).
- **No global coherence** — the same `(Trait, type)` can resolve to different conformances in different scopes (the Scala trade-off), so a dictionary threaded into a data structure is not guaranteed identical to one resolved elsewhere.

---

## 9. Why it fits the backend (no new NIR)

Self-based maps *more* directly onto Scala Native than parameter-based did:

- **Nominal `<:`** → the conformer's **itable**; `dog.show` is an `Op.Method` virtual dispatch. This is exactly what the backend already does for interface dispatch.
- **Retroactive `given Trait for Type`** → a synthesized dictionary class/module implementing the erased trait, its methods operating on the `for`-type `Self` (unboxing a primitive `Self` at the boundary).
- **`[A: Trait]` bound** → an extra dictionary parameter in the flattened signature (the same lowering parameter-based `using Show[A]` had).
- **A nominal `<:`** also synthesizes a definition-site forwarding dictionary so owned types satisfy bounds.
- **`Dyn[Trait]`** (when it lands) → a synthesized `Defn.Class` holding payload + dictionary — no new NIR.

So the pivot is a frontend-only change in how conformance is *expressed*; the lowering vocabulary ([spec §8.8](spec.md)) is unchanged.

---

*Normative rules live in [spec.md §5.3](spec.md) and [§6.10](spec.md). Cross-language translation is in [comparison.md](comparison.md). This doc is design rationale, not a specification.*
