# Proposal: Higher-Kinded Types (HKT)

> **Status:** deferred (post-MVP). **Backend cost:** none (erases like any generic). **Frontend cost:** a kind-checker (moderate, well-understood) **plus one genuine design problem** with the v0.5 implicit-receiver Self-based trait model. **Prerequisite for:** `Functor`/`Applicative`/`Monad`/`Traverse` and the container-polymorphic collections milestone.
>
> Non-normative. Captures the design discussion so the work can be picked up without re-deriving it. Cross-refs into [spec.md](../spec.md) (§6.8 generics/erasure, §6.10 traits, §5.3 receiver convention) and [traits-design.md](../traits-design.md) (the v0.5 Self-based pivot).

---

## 1. Why we want it

Hi's typeclass system is modelled on **Scala 3's experimental Self-based typeclasses** (the 2024 SIP direction: *"a trait is a type class if it has type `Self` as a member"*). Hi adopted that encoding in v0.5 — implicit `Self`, auxiliary parameters, `[A: Trait]` bounds, type-name-as-evidence-path (`A.empty` ≈ Scala's `A.unit`).

But Hi currently restricts `Self` (and every type parameter) to **proper, first-order types** (HKT is out of scope, [spec §2.2](../spec.md)). The consequence is the single biggest expressiveness ceiling in the language:

- **`Functor`, `Applicative`, `Monad`, `Traverse` are inexpressible.** They all abstract over a *type constructor* (`F[_]`), not a proper type.
- You cannot write a function generic over the container (`fun sequence[F[_], A] (xs: List[F[A]]): F[List[A]]`).
- The container-polymorphic collections milestone ([spec §2.2](../spec.md)) — reusing scalalib's `IterableOps`/`CC[_]`-based API — needs HKT to even typecheck the signatures.

For a language whose pitch is "OCaml/Scala-style expressiveness, FP-leaning," shipping the Scala-3 typeclass *encoding* while dropping the feature (HKT) that makes that encoding worth having for FP is the gap most worth closing after the MVP. Scala 3 experimental's payoff examples (`Reader[Ctx] is Monad`, `Functor`) are exactly the ones Hi can't currently state.

## 2. Why it is cheaper than it looks (and where the cost actually is)

Three facts make the *mechanical* cost low:

1. **HKT erases to nothing.** NIR has no generics, no kinds — everything erases before NIR ([spec §6.8](../spec.md)). `F[A]` erases exactly like any other generic application (to `F`'s bound or `Object`). **No new NIR nodes, no runtime support, no GC interaction, no codegen.** The "NIR is the contract / no new NIR" principle ([spec §1.5](../spec.md)) holds unchanged.
2. **The grammar already parses `F[A]`.** `application_type ::= atom_type type_arg_block*` over a `type_path` that includes type variables ([spec §5.9](../spec.md)) — so `F[A]`, `F[G[X]]` already derive. The only grammar addition is a way to *declare* a parameter's kind (below).
3. **The inference model is HKT-compatible and dodges the hard part.** Hi is local/bidirectional and annotation-heavy, **not** Hindley–Milner ([spec §6.1](../spec.md)). Full HM + HKT needs higher-order unification, which is undecidable; Scala-style subtyping + local inference with explicit annotations sidesteps it. Scala 3 does HKT precisely this way. So the decidability problem **does not arise** for Hi.

So the cost is concentrated in **(a)** a kind-checker and **(b)** one design problem that touches the v0.5 receiver convention. (b) is the reason this is a proposal and not already in the spec.

## 3. The kind-checker (mechanical, moderate)

Introduce kinds: `*` (proper type), `* -> *` (unary constructor), `(*, *) -> *` (binary), … A type parameter carries a kind; a type variable applied to arguments (`F[A]`) is kind-checked like a function application at the type level.

- **Declaring a constructor parameter.** Spell the kind with the existing wildcard token: `[F[_]]` (kind `* -> *`), `[F[_, _]]` (binary). This is Scala's syntax and reuses brackets Hi already has. (Hi reserves `?` as a *value-level use-site* parameter wildcard, [spec §5.9](../spec.md); `_` inside a type-parameter binder is the *kind* placeholder — different position, no collision.)
- **Kind inference for `Self`.** A trait's `Self` kind is inferred from how `Self` is used in the body: if any member writes `Self[X]`, then `Self : * -> *`; otherwise `Self : *` (the current default, preserving all existing first-order traits unchanged).
- **Kind checking** is a standard well-formedness pass: every type application `H[args]` requires `H`'s kind to match the arguments' kinds. No inference subtlety beyond matching.

This is bounded, well-understood compiler work. It is **not** the blocker.

## 4. The real problem: HKT vs. the implicit-receiver Self-based model

This is the crux, and it is specific to a decision Hi made in v0.5 that Scala 3 experimental did *not* make.

### 4.1 What v0.5 assumes

In Hi today ([spec §5.3](../spec.md), [traits-design.md §6](../traits-design.md)):

- The conformer is always the **fully-applied proper type**. `class LinkedList[T] <: Collection[T]` binds `Self = LinkedList[T]` (applied); the receiver is `self : Self`.
- The receiver is **implicit** (Swift/Scala style): a method writes `fun show: String`, not `fun show (self: Self)`.

Both assumptions are load-bearing for the clean first-order interface story (`Collection[T]`).

### 4.2 What `Functor` needs

```
trait Functor[A] {                 // A = the "current element type"
  fun map[B] (f: A -> B): Self[B]  // Self applied to B -> Self : * -> *
}
```

- `Self` must be the **unapplied constructor** (`List`, kind `* -> *`), because `map` *changes the element type* — it returns `Self[B]`, not `Self`. You cannot make `Self` the applied `List[A]`.
- The **receiver** is therefore `self : Self[A]`, not `self : Self`. The element `A` is the trait's auxiliary parameter; the receiver is `Self` applied to it.

Both of v0.5's assumptions break: `Self` is no longer proper, and the receiver is no longer `Self`.

### 4.3 How Scala 3 experimental avoids it

Scala 3 keeps the receiver **explicit** in the extension:

```scala
trait Functor:
  type Self[_]
  extension [A](x: Self[A]) def map[B](f: A => B): Self[B]
```

The receiver type `Self[A]` is written out. Hi's *implicit* receiver cannot write it — which is exactly the friction. Hi chose the implicit receiver deliberately (OO-native, [traits-design.md §5.6](../traits-design.md)); that choice is what now needs an extension to accommodate HKT.

### 4.4 The nominal-conformance wrinkle

Even granting a receiver rule, nominal `<:` conformance is awkward:

```
class MyList[A] <: Functor[A]      // the class is MyList[A] (applied); Self must be MyList (unapplied)
```

The declaring type is `MyList[A]` but `Self` must bind to `MyList` (the constructor). So the `<:`-binds-`Self`-to-the-declaring-type rule ([spec §5.3](../spec.md)) needs a higher-kinded variant: when the trait's `Self` is `* -> *`, `<:` binds `Self` to the declaring type's *constructor*, and the class's own parameter `A` aligns with the trait's auxiliary parameter. This needs precise rules (and likely a constraint that the class parameter and trait parameter line up positionally).

## 5. Design options for the receiver

| Option | Rule | Cost |
|---|---|---|
| **(a) Implicit `Self[aux]`** | When `Self : * -> *`, the implicit receiver is `Self` applied to the trait's auxiliary parameters (`Functor[A]` ⇒ receiver `Self[A]`). | Keeps the implicit receiver; needs a clear "which params are the applied ones" rule; reads oddly for >1 aux param. |
| **(b) Optional explicit receiver, HK-only** | Keep implicit `self : Self` for first-order traits; allow an explicit receiver *only* when `Self` is higher-kinded (`extension [A] (self: Self[A]) …`-style in a trait). | Two receiver spellings; partially reintroduces the explicit form v0.5 rejected — but *scoped* to HKT, where it is genuinely needed. Closest to Scala 3. |
| **(c) Associated element type** | Give the trait an associated type for the element and keep `self : Self`, refining at conformance. | A bigger feature (associated types) than HKT itself; probably out of proportion. |

**Recommendation:** **(b)** — keep the implicit `self : Self` for all existing first-order traits (zero churn), and introduce an explicit applied receiver *only* for higher-kinded traits, mirroring Scala 3 experimental's `extension [A](x: Self[A])`. This localizes the new complexity to exactly the traits that need it, preserves every current example unchanged, and matches the model Hi already borrows from. The "implicit receiver everywhere" property weakens slightly — but only for HKT traits, which are inherently the advanced case.

## 6. Worked target (what it should look like)

```hi
trait Functor[A] {
  fun map[B] (self: Self[A]) (f: A -> B): Self[B]      // option (b): explicit applied receiver, HK trait
}

trait Monad[A] <: Functor[A] {
  static fun pure (x: A): Self[A]
  fun flatMap[B] (self: Self[A]) (f: A -> Self[B]): Self[B]
}

given [A] Functor[A] for List {                         // Self = List (constructor); A free
  fun map[B] (self: List[A]) (f: A -> B): List[B] = self .map f   // reuse the first-order List.map
}

fun mapTwice[F[_]: Functor, A] (xs: F[A]) (f: A -> A): F[A] =      // HK bound
  xs .map f .map f
```

Note `[F[_]: Functor]` — a higher-kinded trait bound. The dictionary threads exactly as a first-order `[A: Trait]` bound does ([spec §8.8](../spec.md)); erasure is identical.

## 7. Interaction with existing features

- **`[F[_]: Trait]` bounds** lower to the same implicit dictionary parameter as first-order bounds ([spec §8.8](../spec.md)). No new lowering.
- **Existential-eligibility / `Dyn`** ([spec §5.9](../spec.md)): an HK trait whose `Self` appears only in receiver position could still be eligible, but `Dyn[Functor]` over an unknown `* -> *` conformer is exotic; recommend HK traits are usable via **bounds only** initially, deferring HK existentials.
- **Variance** ([variance.md](variance.md)) is independent but synergistic: a covariant `List[+A]` plus `Functor` is the natural collections story. They can land in either order.
- **First-order facade unaffected.** The MVP `std.collections` `List`/`Option` are first-order; HKT would later let them *conform* to `Functor`/`Monad` without changing their first-order method signatures.

## 8. Effort estimate

- Kind representation + kind-checking pass: **moderate**, standard.
- Grammar: **tiny** (`[F[_]]` binder; `F[A]` already parses).
- Receiver-convention design + implementation (option b): **moderate**, and the part that needs care because it touches §5.3.
- Erasure/lowering: **none** (reuses existing generic erasure and dictionary threading).
- Risk: **low at the backend, moderate at the type-checker**, concentrated in the receiver rule and the nominal-HK `<:` binding (§4.4).

**Net:** not "huge," but not a drop-in either — it deserves a focused design pass on the receiver convention (this doc is the start) and its own spec version, *not* an MVP rush, because it perturbs the v0.5 model that everything else now depends on.

## 9. Open questions

1. Receiver spelling — confirm option (b); pin the exact syntax for an HK trait's explicit applied receiver.
2. Nominal HK `<:`: precise rule aligning a class's constructor + parameters with a trait's `Self` + auxiliary parameters (§4.4).
3. Multi-parameter kinds (`Either[_, _]` as a `Bifunctor`) — in or out of the first HKT cut?
4. HK existentials (`Dyn[Functor]`) — defer beyond the first cut?
5. Partial application of type constructors (`Either[String]` as `* -> *`) — needed for `Monad[Either[String, _]]`; Scala uses type lambdas. Likely defer; document the limitation.
