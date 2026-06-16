# Proposal: Non-Null-by-Default Reference Types + Flow Narrowing

> **Status:** deferred (post-MVP). **The largest of the deferred items** — a genuine type-system feature, not a localized addition. **Backend cost:** none (nullability erases; it is a compile-time discipline over the existing nullable substrate). **Frontend cost:** large — flow-sensitive narrowing ("smart casts") and a nullable-type layer.
>
> Non-normative. Cross-refs: [spec §6.2](../spec.md) (`Null`/nullability), [spec §6.3](../spec.md) (subtyping), [spec §7.5](../spec.md) (`match` and `null`), [spec §6.9](../spec.md) (union/LUB).

---

## 1. Why we want it

Hi reference types are currently **nullable** ([spec §6.2](../spec.md)): `Null <: R` for every reference type `R`, `null` arises from interop/runtime, and dereferencing it throws `NullPointerException`. This is inherited from the substrate (`Type.Ref.nullable`).

For a language designed fresh in 2026 and pitched as modern, safe, and elegant ([spec §1.1](../spec.md)), nullable-by-default is a step behind Kotlin, Swift, and Rust — all of which make the *absence* of null the default and force null to be explicit in the type. Hi has `Option[A]` (an ADT, now in `std.collections`, [spec §10.2](../spec.md)), but the *type system* still admits `null` silently into every reference type, so the NPE class of bug is not actually closed. Closing it is the highest-*value* safety improvement available — it is deferred only because it is the highest-*cost*.

## 2. The Kotlin model is the right reference

Kotlin solved exactly this problem over exactly this kind of substrate (a nullable host runtime, the JVM). The shape:

- **Non-null by default.** `String` denotes a value that is never `null`; the nullable form is the explicit `String?` (sugar for the union `String | Null`, which Hi can already represent — [spec §6.9](../spec.md)).
- **Flow narrowing ("smart casts").** After a check, the type narrows in the controlled scope:
  ```
  fun describe (s: String?): String =
    if s == null then "none" else s.length .toString   // `s : String` in the else branch
  ```
  `s.length` is legal in the `else` because the `== null` test narrowed `s` to `String`. A `.field`/`.m` on a bare `String?` is a **compile error** — you must narrow first.
- **Platform types from interop.** Javalib/Scala Native symbols are not annotated for nullability, so values crossing the FFI/runtime boundary are **platform types** (`String!`): usable as either nullable or non-null, with the check deferred to runtime (Kotlin's pragmatic escape valve that keeps interop usable without a fully-annotated world).
- **Operators:** `?.` (safe call), `?:` (elvis/default), `!!` (assert-non-null) are the ergonomic surface — optional, can be staged.

## 3. Why the backend cost is zero (and the frontend cost isn't)

Nullability is a **compile-time refinement of the existing reference types**. `String` and `String?` erase to the *same* nullable `Type.Ref` ([spec §6.2, §6.8](../spec.md)); the difference is entirely in what the type-checker permits before erasure. So: **no new NIR, no runtime tag, no GC change.** A `!!` assertion lowers to an existing null-check + throw (the machinery already inserted on deref); `?.`/`?:` lower to ordinary branches.

The expense is the **flow-sensitive narrowing pass** in the type-checker: tracking, per program point, which bindings have been proven non-null (by `== null`/`!= null` tests, `if`/`match`/`&&`/`||` short-circuit structure, and early `return`/`throw`/`break`). This is real dataflow over the typed AST, interacting with `var` reassignment (a narrowed `var` re-widens on reassignment), captures (a captured `var` cannot stay narrowed across a closure), and the LUB join ([spec §6.9](../spec.md)). It is the same machinery Kotlin/TypeScript invest in, and it is the bulk of the work.

## 4. Migration / interaction

- **`Null` and `match`** ([spec §7.5](../spec.md)) already specify that `null` matches only `_`/a binder, and that an exhaustive variant `match` over a nullable scrutinee without a catch-all throws `MatchError`. Under this proposal, a *non-null* scrutinee would not need a null arm at all (the type guarantees it), tightening exhaustiveness.
- **`Option[A]` vs `A?`.** Both would exist; `A?` is the zero-cost (erased) "maybe a reference" for interop and local control flow, `Option[A]` the first-class data structure (boxable, storable in generic positions, mappable). Document when to use which — broadly Kotlin's `T?` vs. an explicit `Optional`/`Option` guidance.
- **Existing code.** Turning on non-null-by-default is a breaking change to the meaning of every `R` annotation; it must land at a deliberate version boundary with the platform-type escape hatch (§2) so interop-heavy code is not instantly unbuildable.

## 5. Effort estimate

- Type layer (`T?` as `T | Null`, non-null default): **moderate** (mostly reuses union machinery).
- Flow-narrowing dataflow pass: **large** — the dominant cost; interacts with `var`, closures, joins.
- Operators (`?.`/`?:`/`!!`): **low** each, stageable after the core.
- Platform-type interop handling: **moderate** and essential for usability.
- Backend: **none**.
- Risk: **moderate–high**, entirely in type-checker complexity (no design-coherence crisis like HKT, no backend risk).

**Net:** the highest-value safety feature, but a substantial type-system project on its own — correctly deferred. Worth doing at a major version boundary, with the Kotlin playbook (non-null default + smart casts + platform types) as the template.

## 6. Open questions

1. Version/compat strategy — gate behind a flag during transition, or hard cutover at a major version?
2. How aggressively to narrow `var` (Kotlin refuses to smart-cast mutable `var`s that could change between check and use, esp. across calls/closures) — match Kotlin's conservatism.
3. Surface for platform types — explicit `String!` spelling, or purely internal?
4. Do `Option[A]` and `A?` interconvert implicitly, or via explicit `.toOption`/`.orNull`? (Recommend explicit, consistent with [spec §2.2](../spec.md) "no implicit conversions".)
