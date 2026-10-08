# Stage 14 — Commerce Core Persistence & Acceptance Evidence — Scope / Entry Gate

**Gate status:** READY FOR FINAL REVIEW — REQ-FUNC-0037 HAS A LOCAL SEMANTIC BLOCKER  
**Current main baseline:** `4de0de74ca20c69adde4f4b8c2c2a7b60e09606c`  
**Governance reconciliation:** COMPLETE — PR #32 is merged at the baseline above; Post-Merge CI run **37851720263 — SUCCESS** (314 passed, 0 failed). This proves governance reconciliation and CI status only; it is not SPEC or Stage 14 persistence-acceptance evidence.  
**Implementation authorization:** NOT GRANTED.

## 1. Stage identity and scope freeze

**Stage name:** Stage 14 — Commerce Core Persistence & Acceptance Evidence

**Purpose:** extend the Commerce Core evidence proven by Stage 13 from domain behavior to an explicit chain:

`Requirement → Specification → Domain behavior → Persistence behavior → Tests → Acceptance evidence`

### In-scope requirements

- REQ-DATA-0013
- REQ-DATA-0014
- REQ-FUNC-0015
- REQ-FUNC-0016
- REQ-FUNC-0033
- REQ-FUNC-0034
- REQ-FUNC-0035
- REQ-FUNC-0037
- REQ-FUNC-0038

No requirement is ACCEPTED by this Gate.

### Target persistence components

- `products`
- `offerings`
- `inventory_positions`
- `sales`
- `invoices`

The existing 47-table schema remains the persistence boundary. Schema expansion is excluded.

## 2. Mandatory domain boundaries

Stage 14 must preserve these invariants:

- Product ≠ Offering.
- Offering ≠ Inventory Position.
- Inventory Position is the authoritative inventory state.
- Availability is derived/read-only evidence, not inventory truth.
- Discovery consumes Availability; it does not mutate Inventory or Sale truth.
- proximity ≠ availability.
- Temporal semantics may be represented only from approved specification meaning; no new proximity/freshness thresholds may be invented.
- Sale lifecycle acceptance is locally blocked for REQ-FUNC-0037 until the `fulfilled` (SPEC-0005) versus `completed` (domain/schema) terminology is authoritatively clarified; do not treat these terms as synonyms.
- Existing domain/schema behavior may be characterized and persistence-tested as implemented, but cannot be declared semantically conformant to SPEC-0005's terminal lifecycle state while that clarification is unresolved.
- Activity context is retained on Offering/Inventory/Sale where required.
- Sale ≠ Payment.
- Sale ≠ Settlement.
- Sale ≠ Financial Transaction.
- Sale ≠ Ledger Entry.

## 3. Mandatory exclusions

The following are hard exclusions:

- Payment implementation.
- Settlement implementation.
- Financial Transaction implementation.
- Ledger implementation.
- Stage 12B.
- Durable Financial Workflow.
- Search engine.
- GIS.
- Ranking algorithm.
- Proximity threshold invention.
- Freshness threshold invention.
- Inventory reconciliation algorithm.
- Generic conflict-resolution engine.
- Agent autonomous Commerce execution.
- API/UI.
- Provider integrations.
- Schema expansion.
- New domain concepts.

If implementation requires any excluded capability, the Stage must stop and report the blocker rather than expand scope.

## 4. Entry Gate

| Gate | Required condition | Current state |
|---|---|---|
| 1. Governance consistency | 59 total; 58 PROPOSED; 0 NEEDS DECISION; 1 DRAFT; 0 ACCEPTED; DEC-0001..0018 RESOLVED | **SATISFIED — PR #32 merged at `4de0de74ca20c69adde4f4b8c2c2a7b60e09606c`; Post-Merge CI 37851720263 SUCCESS** |
| 2. Scope freeze | Nine requirements and mandatory exclusions frozen | **SATISFIED by this Gate** |
| 3. SPEC-0005 readiness | Implement specified, unambiguous commerce behavior; collect requirement acceptance evidence during Stage 14; isolate unresolved Sale terminal-state terminology | **BOUNDED IMPLEMENTATION; REQ-FUNC-0037 terminal-state acceptance locally BLOCKED** |
| 4. SPEC-0006 readiness | Implement only explicit Inventory/Availability boundaries; collect persistence evidence during Stage 14; keep research-dependent policies out of scope | **BOUNDED IMPLEMENTATION; RESEARCH QUESTIONS EXCLUDED** |
| 5. SPEC-0014 readiness | Implement only explicit Discovery boundaries; collect persistence/boundary evidence during Stage 14; no proximity/freshness policy invention | **BOUNDED IMPLEMENTATION; RESEARCH QUESTIONS EXCLUDED** |
| 6. Persistence compatibility | Existing schema contains the five target tables and required relationships/states/indexes; no schema expansion is required by the current model | **COMPATIBLE / IMPLEMENTATION EVIDENCE PENDING** |
| 7. Traceability evidence | Requirement → Spec → Behavior → Persistence → Test → Acceptance matrix defined below | **DEFINED; execution evidence pending** |
| 8. Finance boundary | Commerce does not own Payment/Settlement/Financial Transaction/Ledger truth | **SATISFIED** |
| 9. Stage 12B | Must remain NOT AUTHORIZED / NOT STARTED | **SATISFIED** |
| 10. Explicit implementation authorization | Human authorization required after all gates are satisfied | **NOT GRANTED** |

## 5. Requirement-level evidence gate

| Requirement | Authoritative spec | Domain behavior required | Persistence behavior required | Test evidence required | Acceptance evidence required | Research dependency |
|---|---|---|---|---|---|---|
| REQ-DATA-0013 | SPEC-0005 | Product and Offering remain distinct; Offering carries Activity context | Persist Product independently; Offering references Product + Activity without collapsing identity | Stage 13 domain acceptance + persistence round-trip | Stored records preserve distinct IDs and Activity-scoped Offering semantics | No new research identified; evidence only |
| REQ-DATA-0014 | SPEC-0005, SPEC-0006 | Offering does not imply Inventory Position | Persist Inventory Position separately with Offering/Product references and independent state | Stage 13 boundary test + persistence integrity test | Database evidence shows independent Offering/Inventory records and valid FK boundaries | No new research identified; evidence only |
| REQ-FUNC-0015 | SPEC-0006 | Inventory Position carries activity, scope/location, quantity, observation/effective state | Persist authoritative inventory state, quantity and temporal observation/effective fields | Domain lifecycle + persistence state/round-trip tests | Evidence that persisted Inventory Position is the authoritative inventory representation | Inventory state detail remains research/evidence-sensitive; no new decision inferred |
| REQ-FUNC-0016 | SPEC-0006, SPEC-0014 | Availability is derived, temporal, and separate from Product/Inventory truth | Persist only approved availability representation if the existing schema/spec requires it; do not create a new authoritative inventory field/table | Derived-availability boundary tests and persistence/non-ownership checks | Evidence that Availability cannot replace Inventory truth and preserves temporal validity | Temporal/proximity semantics require specification evidence; no threshold invention |
| REQ-FUNC-0033 | SPEC-0014 | Discovery boundary consumes existing Availability/Offering context only | Persist source Commerce/Inventory facts; no discovery index or search truth is introduced | Discovery boundary test + persistence source-of-truth test | Evidence that discovery reads persisted source facts without owning them | No search/GIS/ranking research in Stage 14 |
| REQ-FUNC-0034 | SPEC-0014, SPEC-0006 | Proximity is not Availability | Persist location references only where already defined; do not persist a proximity decision as domain truth | Boundary test proving no proximity threshold/field is invented | Evidence that proximity and availability remain distinct concerns | Proximity semantics remain specification-dependent; no threshold invention |
| REQ-FUNC-0035 | SPEC-0014, SPEC-0006 | Availability has temporal validity/freshness semantics without invented thresholds | Persist approved observed/effective/valid/freshness fields only where already represented by schema/spec; no threshold column/rule is added | Temporal state tests plus persistence round-trip evidence | Evidence that temporal semantics survive persistence without a fabricated freshness rule | Research/evidence remains required for exact temporal policy |
| REQ-FUNC-0037 | SPEC-0005, SPEC-0008, SPEC-0009 | Lifecycle terms in SPEC-0005: initiated → confirmed → fulfilled; current domain/schema: initiated → confirmed → completed. Cancellation/return history remains in scope, but terminal-state equivalence is not established | Persistence-test the current model's states/history without changing schema; do not assert `completed` is equivalent to `fulfilled` | Existing Stage 13 tests + persistence transition/history tests can prove implementation behavior; semantic acceptance of terminal state is withheld pending authoritative clarification | Record the exact mismatch and obtain clarification whether `fulfilled` is the canonical semantic state and how existing `completed` maps to it, or whether they are distinct states with separately specified meaning | Finance implementation excluded; this is a local semantic blocker, not a Stage-wide blocker |
| REQ-FUNC-0038 | SPEC-0005, SPEC-0003, SPEC-0015 | Sale preserves Activity context and respects authorization/finance boundaries | Persist Sale with Activity reference; no authorization or ledger ownership is introduced | Activity-context and cross-domain boundary tests | Evidence that persisted Sale retains Activity context and does not cross Finance/Authorization ownership | No new research; specification evidence remains required |

## 6. Evidence classification and stop rule

Do not conflate these three categories:

1. **Specified behavior that is ready to prove:** the frozen boundaries in Sections 1–3 and the requirement-level behavior statements in Section 5 provide testable assertions where their meaning is already explicit. Examples include Product ≠ Offering, Offering ≠ Inventory Position, Inventory Position as the inventory source of truth, Sale lifecycle/history boundaries, Activity context, and the separation of Sale from Finance-owned concepts. Existing Stage 13 domain tests are prior evidence only; they do not prove persistence round-trips or Stage 14 acceptance.
2. **Persistence and acceptance evidence to collect during an authorized Stage 14 implementation:** database round-trips, independent record/FK integrity, persisted lifecycle/history, Activity-context preservation, temporal fields as already specified, and tests showing Availability/Discovery do not become alternate sources of truth. The PR #30 migration verification and PR #32 governance merge do not substitute for these Stage 14 results.
3. **Research/specification details that remain outside Stage 14:** SPEC-0006 retains inventory-state and temporal/availability research questions; SPEC-0014 retains discovery/proximity and temporal/freshness questions. These are not blanket entry blockers: implement and test only explicit boundaries, record evidence gaps, and leave unresolved policy behavior unimplemented/unclaimed. No threshold, freshness window, proximity rule, new availability authority, or other policy may be inferred. SPEC-0005's requirement-level acceptance evidence is collected during Stage 14, not required before starting it.

### 6.1 Local semantic blocker — REQ-FUNC-0037 only

The current SPEC-0005 §11 normatively names the Sale lifecycle `initiated → confirmed → fulfilled`. The current domain (`SaleState.COMPLETED`, `Sale.complete()`) and relational schema (`sales.state` CHECK constraint) use `completed`. SPEC-0005 is the semantic specification; implementation names do not silently supersede it. The inspected decision register contains no decision declaring `fulfilled` and `completed` equivalent, and DEC-0008/0009/0010/0012 do not resolve this vocabulary mismatch. Therefore this is a real unresolved semantic mismatch for terminal-state acceptance, not a verified historical naming difference.

**Required clarification (do not resolve within this PR):** authoritatively state whether `fulfilled` is the semantic terminal state represented by the existing `completed` implementation, including the exact acceptance mapping and meaning; or whether they are distinct states, in which case the authoritative specification owner must define the distinction and intended transition. No domain/schema/migration/REQ/DEC change is authorized here. Until clarified, mark only the terminal-state semantic assertion of REQ-FUNC-0037 BLOCKED. Other REQ-FUNC-0037 persistence evidence (state round-trip, allowed transitions, cancellation/return history, Activity and Finance boundaries) may be collected without claiming terminal-state semantic acceptance.

**Mandatory stop rule:** if a requirement in the frozen nine-item slice cannot be demonstrated without choosing an unresolved semantic rule, record the exact ambiguity, affected requirement/specification, missing evidence or decision, and the failing proof boundary. Stop that proof/implementation path and request specification clarification. Do not expand scope, silently choose a rule, or modify requirements/decisions as part of Stage 14.

## 7. Current persistence compatibility evidence

The current relational schema already defines the target tables:

- `products`: independent product identity/state.
- `offerings`: Product + Activity scoped offering with lifecycle/effective window.
- `inventory_positions`: Activity-scoped inventory state with optional Product/Offering, scope/location, quantity, observed/effective timestamps.
- `sales`: Offering + Activity scoped sale with initiated/confirmed/completed/cancelled/returned states.
- `invoices`: Sale-linked commercial document with its own state and optional Obligation reference.

Existing indexes cover Product/Offering, Offering/Activity, Inventory scope/observation, Inventory/Offering, Sales/Activity/time, Sales/Offering, and Invoice/Sale.

Persistence migration/verification was previously exercised through PR #30, whose successful CI run was **37842724223** on commit `1a6d74c187aa9da0f160745ecdc20e77f4778945`. This establishes the existing persistence layer as a verified baseline; it does not constitute Stage 14 acceptance evidence.

## 8. Stage 13 evidence baseline

Stage 13 implemented and tested the nine requirements at domain-behavior level.

- Stage 13 source HEAD: `a1a69d3d723689d6a3bc4dfd4daf6374259e5041`
- Stage 13 CI: **37846980392 — success**
- Stage 13 merged main: `26d0d2d465e9159122eb66c3412cae58d28c2bec`

Stage 14 must extend this evidence to persistence and acceptance; it must not re-open Stage 13 domain scope.

## 9. Governance and decision boundary

Current authoritative governance target:

- 59 total requirements.
- 58 PROPOSED.
- 0 NEEDS DECISION.
- 1 DRAFT.
- 0 ACCEPTED.
- DEC-0001..DEC-0018 = RESOLVED.

Relevant strategic priority: **DEC-0010 — Commerce + Daily Services priority**.

Relevant decision closures for governance consistency include DEC-0014/0015 for Agent autonomy/Human Approval and DEC-0016/0017/0018 for the former requirement-level blockers. Their closure does not promote any requirement to ACCEPTED.

## 10. Finance boundary

Stage 14 may reference Finance boundaries only to prove separation:

`Sale ≠ Payment ≠ Settlement ≠ Financial Transaction ≠ Ledger Entry`

No financial implementation is authorized here. Any requirement to create/update financial truth belongs to the existing Finance authority and is outside this Stage.

## 11. Stage 12B boundary

Stage 12B remains **NOT AUTHORIZED / NOT STARTED**.

Durable Financial Workflow remains deferred. Nothing in this Gate authorizes it indirectly.

## 12. Entry decision

**Current decision: READY FOR FINAL REVIEW.**

Governance consistency and scope freeze are satisfied. Missing persistence/acceptance evidence is a planned Stage 14 output, not a pre-execution blocker. The remaining SPEC-0006/SPEC-0014 research questions are bounded out of scope: only explicit semantics may be implemented and tested, with no invented thresholds or policy. SPEC-0005 acceptance evidence is likewise collected during Stage 14.

**Local exception:** REQ-FUNC-0037's Sale terminal-state semantic assertion remains BLOCKED pending the clarification in §6.1. This does not block independent work on the other eight requirements, nor the persistence evidence portions of REQ-FUNC-0037 that do not assume `fulfilled` = `completed`.

This gate is ready for final review but does **not** authorize implementation. A separate explicit human authorization is required before Stage 14 starts.

### Conditions for an authorized Stage 14 execution

1. Governance reconciliation remains consistent with main at `4de0de74ca20c69adde4f4b8c2c2a7b60e09606c` and the successful Post-Merge CI evidence.
2. The frozen nine-requirement traceability matrix remains the acceptance-evidence plan; collect persistence and acceptance evidence during Stage 14.
3. Only already-specified behavior is implemented. Record bounded evidence and leave SPEC-0006/SPEC-0014 research-dependent policies outside scope.
4. REQ-FUNC-0037 terminal-state acceptance remains isolated until its authoritative clarification is supplied; do not silently equate the terms.
5. Existing persistence compatibility remains sufficient; no schema expansion.
6. Finance boundaries and Stage 12B **NOT AUTHORIZED / NOT STARTED** remain unchanged.
7. The human reviews this Gate and grants explicit implementation authorization separately.

No merge, implementation, tag, or release is authorized by this document.
