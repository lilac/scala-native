# Hi — Deferred-Feature Proposals

Design notes for features intentionally **kept out of the MVP** but judged worth doing, captured here so the reasoning from the design discussions is not lost and the work can be picked up cold. Each is non-normative; [spec.md](../spec.md) is authoritative. The MVP additions decided in the same pass (module-level `var`, loop `break`/`continue`, numeric conversions, the first-order `std.collections` facade) went straight into the spec — see the v0.7 changelog.

A recurring theme: **Hi's frontend-over-Scala-Native architecture means none of these cost anything at the backend** — generics, variance, kinds, and nullability all erase before NIR. The cost is in the type-checker (and, for HKT, in one design-coherence problem). That is the architecture paying off: "how hard" is a frontend-complexity question, not a runtime/codegen one.

| Proposal | What | Backend cost | Frontend cost | Why deferred |
|---|---|---|---|---|
| [higher-kinded-types.md](higher-kinded-types.md) | `Functor`/`Monad`/`Traverse`; `[F[_]: Trait]` | none (erases) | moderate + **one design problem** | The v0.5 implicit-receiver Self-based model assumes receiver = `Self`; HKT needs `Self : * -> *`, receiver `Self[A]`. Solvable (Scala-3 shape) but perturbs the receiver convention everything depends on — wants its own design pass, not an MVP rush. The eventual prerequisite for the FP-expressiveness claim and the polymorphic collections milestone. |
| [variance.md](variance.md) | declaration-site `[+A]`/`[-A]` | none (erases) | low–moderate | Mostly valuable *paired with* collections subtyping; `Array` must stay invariant. No design crisis — a clean fast-follow, before or alongside HKT. |
| [exceptions-finally.md](exceptions-finally.md) | `finally` / resource cleanup | low (reuses unwind) | moderate | Interacts with `return`/`break`/`continue` unwinding (the MVP currently relies on "no `finally` ⇒ no cleanup interaction"). Next exception-handling item. |
| [non-null-types.md](non-null-types.md) | non-null-by-default + flow narrowing | none (erases) | **large** | The biggest item — flow-sensitive "smart casts" over the nullable substrate (the Kotlin model). Highest *value* safety win, highest *cost*; a major-version project. |

**Suggested sequencing:** `finally` (cheap, plugs a real gap) → variance (low-risk, sets up collections) → HKT (design pass first, then the polymorphic collections it unlocks) → non-null types (major version, on its own). Scope-based `given` resolution is **not** revisited — it is a deliberate, reaffirmed choice (asc/desc `Ord for Int`), see [spec §6.10](../spec.md).
