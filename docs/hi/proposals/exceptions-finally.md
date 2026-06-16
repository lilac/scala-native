# Proposal: `finally` (and resource cleanup)

> **Status:** deferred (post-MVP). **Backend cost:** low — reuses the existing NIR unwind/landing-pad machinery. **Frontend cost:** moderate — the interaction with `return`/`break`/`continue` is the real work.
>
> Non-normative. Cross-refs: [spec §7.11](../spec.md) (`throw`/`try`/`catch`), [spec §7.13](../spec.md) (`return`), [spec §7.12](../spec.md) (`break`/`continue`), [spec §8.1](../spec.md) (lowering).

---

## 1. Why we want it

The MVP has `try e catch { … }` but **no `finally`** ([spec §7.11](../spec.md)). That leaves no structured way to guarantee cleanup (close a file/socket, release a lock) on *all* exit paths — normal completion, a caught/uncaught exception, or an early `return`/`break`/`continue`. Today you duplicate cleanup in the success path and every handler, which is exactly the bug-prone pattern `finally` exists to remove. For a language targeting server/network daemons ([spec §1.1](../spec.md)), reliable resource cleanup is table stakes.

## 2. The substrate is already there

Scala Native's NIR has the full unwind machinery Hi already uses for `try`/`catch` — landing pads, `Next.Unwind`, `Inst.Throw` ([spec §7.11, §8.1](../spec.md)). `finally` adds no new NIR vocabulary; it is a frontend lowering that arranges for a cleanup block to run on every edge leaving the protected region.

## 3. The real problem: cleanup must run on *every* exit edge

The MVP deliberately leans on **"no `finally` ⇒ no cleanup interaction"** — `return` lowers to `Inst.Ret` at the point of occurrence with no unwinding interaction ([spec §7.13, §8.1](../spec.md)), and `break`/`continue` are plain jumps ([spec §7.12](../spec.md)). `finally` breaks that simplicity: a `return`/`break`/`continue`/`throw` that *leaves* a `try … finally` region must execute the `finally` block **first**.

So the lowering must intercept:

1. **Normal completion** of the protected expression → run `finally`, then continue with its value.
2. **Exception** (caught or propagating) → run `finally`, then resume catch dispatch / re-propagation.
3. **`return`** crossing the region → run `finally`, then `Inst.Ret`.
4. **`break`/`continue`** crossing the region → run `finally`, then the loop jump.

Two standard implementation strategies:

- **Duplicate the `finally` block on each exit edge** (what many compilers do): simple, no shared state, but code-size cost if `finally` is large.
- **A shared cleanup block reached via a "pending action" discriminator** (the JVM `jsr`/`ret`-style or a small switch): one copy, slightly more plumbing.

Recommend duplication for the first cut (simplest, matches the straight-line NIR shape Hi already emits); revisit if code size matters.

## 4. Semantics to pin down (the footguns)

- **`finally` value is discarded**; the `try`/`catch` value is the result (Scala/Java semantics). A `finally` that completes normally does not change the result.
- **A `return`/`break`/`continue`/`throw` *inside* the `finally` block overrides** any pending action from the protected region (Java's "abrupt completion of finally" rule). This is a well-known footgun; spec it explicitly and consider a **compiler warning** when a `finally` block can complete abruptly (swallows an in-flight exception/return).
- **Order with `catch`:** `try e catch { … } finally f` — `finally` runs after the handler (or after the uncaught throw), on the way out.

## 5. Grammar sketch

```
try_expr ::= "try" expr ("catch" arm_block)? ("finally" expr)?   // at least one of catch/finally required
```

`finally` is a new keyword (currently not reserved — add it). The protected expression and the `finally` expression are ordinary expressions/blocks.

## 6. Effort estimate

- Grammar + keyword: **tiny**.
- Lowering: **moderate** — the exit-edge interception (§3) and the `return`/`break`/`continue`-crossing-`finally` cases are the substance; they re-touch the §8.1 lowering of those control forms (which currently assume no cleanup interaction).
- Backend: **none** (reuses unwind/landing pads).
- Risk: **low–moderate**; semantics are well-trodden (copy Scala/Java), the work is careful lowering.

## 7. Open questions

1. Require at least one of `catch`/`finally` (so bare `try e` stays illegal)?
2. Ship a higher-level **`use`/try-with-resources** form (auto-close) on top of `finally`, or leave that to a later library/`given`-based design?
3. Abrupt-`finally` — warn, error, or silent (match Java = silent + lint)?
