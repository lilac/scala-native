# Proposal: Declaration-Site Variance

> **Status:** deferred (post-MVP); a strong fast-follow once the container-polymorphic collections milestone is in view. **Backend cost:** none (erases like any generic). **Frontend cost:** low–moderate — a variance-position-checking pass plus an extension of subtyping (`<:`) and the LUB join (§6.9).
>
> Non-normative design note. Cross-refs: [spec §6.8](../spec.md) (generics/erasure), [spec §6.9](../spec.md) (union/intersection, LUB), [spec §6.3](../spec.md) (subtyping), [spec §5.9](../spec.md) (the reserved `?` wildcard).

---

## 1. Why we want it

Hi generics are currently **invariant** with no variance and no lower bounds ([spec §2.2, §6.8](../spec.md)). The practical pain shows up the moment you have collections:

- `List[Cat]` is **not** usable where `List[Animal]` is expected, even though `List` (in the MVP facade) is immutable and the substitution is sound.
- Function types want variance to subtype naturally: `A -> B` should be contravariant in `A`, covariant in `B`, so a `Cat -> Object` works where an `Animal -> String` is expected. Without it, SAM/closure subsumption is stiff.
- The deferred container-polymorphic collections ([spec §2.2](../spec.md)) reuse scalalib's API, which is **declaration-site-variance-heavy** (`+A` everywhere); reusing it ergonomically essentially requires variance in Hi's type system.

The MVP first-order collection facade ([spec §10.2](../spec.md)) is deliberately invariant and survives without this — you simply can't upcast `List[Cat]` to `List[Animal]`. Variance removes that restriction and is the natural partner to the [HKT](higher-kinded-types.md) collections work.

## 2. Why it is cheap at the backend

Variance, like all generic information, **erases before NIR** ([spec §6.8](../spec.md)). It is a *compile-time subtyping* feature only: no new NIR, no runtime representation, no codegen, no GC interaction. The "no new NIR" contract is untouched. The entire cost is in the type-checker.

## 3. Declaration-site vs. use-site (and the reserved `?`)

Two mechanisms exist in the wild:

- **Declaration-site** (Scala/Kotlin/C#): the *definition* annotates each parameter `[+A]` (covariant) / `[-A]` (contravariant) / `[A]` (invariant). The variance is checked once and applies everywhere the type is used.
- **Use-site** (Java wildcards): each *use* writes `List[? extends Animal]`. Hi already **reserves `?`** for this ([spec §5.9](../spec.md), parameter wildcards), with checking deferred.

**Recommendation: declaration-site as the primary mechanism, `?` as a complementary use-site escape hatch** — exactly Scala's arrangement (declaration-site variance *and* `_`/`?` wildcards coexist). Declaration-site is more ergonomic, matches scalalib (whose collections Hi wants to reuse), and is checked once at the definition rather than re-stated at every use. The reserved `?` then layers on top for the occasional use-site need without conflict (it quantifies an auxiliary parameter — a different slot from `Dyn`'s `Self`, [spec §6.10](../spec.md)).

## 4. The mechanics

### 4.1 Syntax

Extend `type_param` ([spec §5.3](../spec.md)) with an optional leading variance marker:

```
type_param ::= variance? UPPER_ID ( "<:" type )? ( ":" type )?
variance   ::= "+" | "-"
```

`[+A]` covariant, `[-A]` contravariant, bare `[A]` invariant (the current and default behavior). Lexically unambiguous: `+`/`-` directly before an `UPPER_ID` inside a `[ … ]` type-parameter binder.

### 4.2 Position checking

A standard well-formedness pass: classify every occurrence of a type parameter as in a **covariant**, **contravariant**, or **invariant** position, and require it to match its declared variance.

- Method/function **return** types are covariant positions; **argument** types are contravariant; a `var` field (read+write) is invariant; a `val` field is covariant.
- The arrow flips: in `A -> B`, the domain `A` is contravariant, the codomain `B` covariant.
- Nested parameters compose variance (contravariant-of-covariant = contravariant, etc.).

A `+A` used in a contravariant position (or `-A` in a covariant one) is a compile error naming the offending member — the classic Scala "covariant type A occurs in contravariant position" diagnostic.

### 4.3 Subtyping and LUB

- Extend `<:` ([spec §6.3](../spec.md)): `C[X] <: C[Y]` when `C`'s parameter is `+` and `X <: Y` (covariant), or `-` and `Y <: X` (contravariant), or invariant and `X = Y`.
- Extend the LUB join ([spec §6.9](../spec.md)) so `if`/`match`/`try` branches over `C[Sub]`/`C[Super]` join to `C[lub(...)]` when `C` is covariant. This is the main place the join logic grows.

### 4.4 `Array` stays invariant — deliberately

`Array[T]` must remain **invariant** even though it is a container. Covariant arrays are Java's known unsoundness hole (`Object[] a = new String[1]; a[0] = 1;` → `ArrayStoreException`), because arrays are mutable and have a write (contravariant) position. Scala already makes `Array` invariant; Hi follows. Only immutable structures (`List[+A]`) and read-only positions get covariance. This must be stated as a normative carve-out when variance lands.

## 5. What gets covariant/contravariant

- `List[+A]`, `Option[+A]` — immutable, covariant (the headline win).
- Function arrows: `A -> B` becomes contravariant in `A`, covariant in `B` (sugar over a built-in `Function[-A, +B]`).
- `Array[A]` — invariant (§4.4).
- Mutable `class` containers with `var` fields — invariant in those parameters (position checking enforces it).

## 6. Interaction with existing features

- **Trait bounds / existential-eligibility** ([spec §5.9, §6.10](../spec.md)) are orthogonal; variance changes only the subtyping relation the existing rules consume.
- **Union/intersection LUB** ([spec §6.9](../spec.md)) extends as in §4.3; no conflict with the "LUB is a single nominal supertype" rule — `C[lub(X,Y)]` is still nominal.
- **HKT** ([higher-kinded-types.md](higher-kinded-types.md)) is independent and synergistic; either can land first. Together they give the full scalalib-style collections.
- **MVP facade** ([spec §10.2](../spec.md)) gets retrofitted: `List[A]` → `List[+A]` is a source-compatible widening (no caller breaks; more upcasts become legal).

## 7. Effort estimate

- Grammar: **tiny** (`variance?` prefix).
- Position-checking pass: **low–moderate**, textbook algorithm.
- Subtyping + LUB extension: **moderate** (the join logic is the fiddly part).
- Erasure/backend: **none**.
- Risk: **low** — no design crisis (unlike HKT's receiver problem); the only normative subtlety is the `Array`-stays-invariant carve-out.

**Net:** smaller and lower-risk than HKT, high ROI **when paired with collections subtyping**, modest on its own. Reasonable to land in a minor version before or alongside HKT.

## 8. Open questions

1. Bare `Function[-A,+B]` as the desugaring target for `A -> B` — make it a real built-in trait, or special-case arrow subtyping directly?
2. Variance + `Dyn[Trait]` existentials — does a covariant parameter interact with the boxed existential? (Likely fine; confirm.)
3. Whether to also lift the **lower-bound** ban (`[A >: Lower]`, the `>:` token is already reserved, [spec changelog v0.3](../spec.md)) at the same time — lower bounds and contravariance often want each other.
