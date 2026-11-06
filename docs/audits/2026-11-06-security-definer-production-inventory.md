# Production SECURITY DEFINER inventory — 2026-11-06

## Scope and safety

Read-only inspection of the connected Supabase production project on 2026-11-06, limited to function metadata/definitions, effective role privileges, Supabase security-advisor findings, and the three tables named by the advisor.

No production schema, data, grants, RLS policies, migration history, project settings, or credentials were changed. This document records an initial inventory, not a completed security approval.

Project reference: `dukeigbrzvpxzkbmdbeh`  
Related tracking issue: #765

## Current advisor findings

The Supabase security advisor reported:

- **86** public `SECURITY DEFINER` functions executable by `authenticated`.
- **3** public tables with RLS enabled and no direct policies:
  - `commercial_account_delegations`
  - `commercial_catalog_inventory_creation_requests`
  - `commercial_personal_account_organizations`

These are review findings, not proof of exploitability. The callable RPCs may be intentional; each must be assessed by its actual caller, function body, authorization scope, and tests. Do not blanket-revoke EXECUTE.

## Read-only catalog verification

A catalog query over all public `SECURITY DEFINER` functions found:

| Check | Result |
|---|---:|
| Total public SECURITY DEFINER functions | 105 |
| Executable by authenticated | 86 |
| Executable by anon | 0 |
| Functions with an explicit function-level configuration | 105 |
| Functions with an explicitly empty search_path | 47 |
| Functions pinned to `pg_catalog, public` | 58 |

The effective privilege check confirmed that none of the 86 advisor-flagged functions is executable by `anon`. The public schema does not grant CREATE to either `anon` or `authenticated`; both have USAGE. This reduces one common object-shadowing risk, but it does not replace reviewing each function's SQL, qualified object references, ownership, and authorization.

The 86 advisor findings are consistent with the catalog query's effective `authenticated` EXECUTE result. The remaining 19 public SECURITY DEFINER functions were not executable by `authenticated` in this snapshot.

## Initial authorization-body triage

A static scan of the 86 callable definitions found:

- 79 definitions contain a direct `auth.uid()` reference.
- 7 do not contain a direct `auth.uid()` reference.

The seven are not automatically vulnerabilities. Initial call-chain inspection found:

- Five are SQL/API wrappers delegating to private-schema functions:
  - `commercial_create_inventory_item`
  - `commercial_create_purchase_order`
  - `commercial_link_purchase_order_item_offering`
  - `commercial_receive_purchase_order`
  - `commercial_transition_purchase_order`
- Two inventory-creation wrappers delegate to the above inventory helper and/or the separately reviewed `commercial_assign_offering_category` function:
  - `commercial_create_inventory_item_with_category`
  - `commercial_create_inventory_item_with_category_and_unit`

The inspected downstream helpers contain authentication checks and active organization membership/role checks. This is a useful first classification, not a substitute for testing every wrapper's complete call chain and all scope checks. In particular, the complete authorization contract must cover organization, activity, branch/location, object ownership, state transitions, and caller role as applicable.

## Three RLS/no-policy tables

For each of the three advisor-listed tables, the live catalog confirmed:

- RLS is enabled.
- Policy count is zero.
- `anon` has no direct SELECT/INSERT privilege.
- `authenticated` has no direct SELECT/INSERT/UPDATE/DELETE privilege.

This is consistent with an RPC-only access design. Keep direct table access denied unless a specific least-privilege policy is justified by a reviewed application call site. The RPCs that read or write these tables still require individual authorization review.

## What this inventory does not prove

- It does not prove that all 86 functions enforce correct tenant and role boundaries.
- Presence of `auth.uid()` is not proof that the returned identity is checked against the requested organization or resource.
- A pinned `search_path` is not proof that every relation/function reference is safe or schema-qualified.
- Zero `anon` EXECUTE does not mean an authenticated caller is authorized for every argument they can submit.
- The three no-policy tables have no direct client privileges, but their RPC call paths still need review.
- No authenticated two-tenant negative tests were run against production; do not run destructive or mutation tests against production.

## Next review sequence

1. Export a complete, versioned inventory of all 86 advisor findings with identity arguments, owner, grants, function settings, language, and definition hash.
2. Group functions by business workflow and intended Android/web caller.
3. Trace each call site and classify authorization: authentication, active membership, role, organization, activity, branch/location, object ownership, and state transition.
4. Review the seven no-direct-`auth.uid()` wrappers as complete call graphs; add explicit contract tests where the downstream guard is not obvious.
5. Review the 79 direct-`auth.uid()` functions for actual membership/scope checks rather than relying on keyword presence.
6. Add negative tests for cross-organization IDs, inactive membership, insufficient roles, anonymous callers, and direct table access in disposable PostgreSQL fixtures.
7. Change grants or function bodies only after call-site and regression evidence exists. Preserve intentional authenticated RPC entry points.
8. Re-run security advisors after reviewed changes and classify each remaining finding as fixed, intentionally accepted with evidence, or blocked.

## Current decision

**No production permission changes are justified by this inventory alone.** Keep issue #765 open. Continue with function-by-function review and isolated regression coverage before proposing any permission migration.


## Additional call-chain checks from the first review pass

Two examples show why a direct text scan must not be treated as a verdict:

- `commercial_request_sale_return` checks that the caller is authenticated, then delegates to `commercial_request_sale_return_base_units`. The downstream function checks that the caller either created the sale or is an active member of the sale's organization with an allowed role before creating a return request. The wrapper should be tested together with the downstream function, not assessed in isolation.
- `commercial_account_lookup_personal_account` is called by the Android personal-account linking screen. It returns only account ID, account number, display name, and account type (not email, phone, or identity documents). This appears to serve the account-linking preview, but it is also an authenticated account-discovery endpoint: review account-number entropy, request throttling, enumeration resistance, and whether a non-enumerating response is feasible. The existing source-contract test checks field minimization and that anonymous EXECUTE is revoked; it does not prove resistance to authenticated enumeration.

A keyword-only grouping of the 86 callable definitions found 35 definitions directly mentioning `commercial_organization_members`, 18 with other membership/helper signals, 80 with an organization-id reference, and 36 with an activity/location/branch reference. These are search signals only; they overlap and do not establish authorization coverage. The next step is call-graph and policy review, not changing grants based on these counts.


## Follow-up: personal-account lookup entropy and index shape

A second read-only production inspection traced the Android personal-account linking flow to `commercial_account_lookup_personal_account(text)` and its account-number issuer.

Confirmed behavior:

- The issuer creates personal account numbers as `RH-` plus the first 16 hexadecimal characters of a freshly generated UUID (64 random bits).
- The lookup performs an exact, case-insensitive equality comparison after trimming; it does not perform prefix or substring search. The client-side minimum of three characters is only an input gate, not a partial-match query.
- The lookup returns only the account ID, account number, display name, and account type. It does not return email, phone, or identity documents.
- The account-number column has a unique index, so collisions are rejected by the database rather than silently assigning the same number.
- The function is authenticated-only and checks `auth.uid()`; anonymous EXECUTE is false.

Assessment: the endpoint remains an authenticated account-discovery surface, but the random 64-bit exact-match account number materially reduces practical guessing compared with a sequential or low-entropy identifier. The display name is disclosed only after a full account-number match. Do not weaken the identifier format or add prefix search. A rate-limit / abuse-monitoring control is still worth considering as defense in depth; no production change is justified from this read-only review.

The live catalog also shows three equivalent partial unique indexes enforcing one active personal account per owner:

- `commercial_one_active_personal_account_per_owner_uidx`
- `commercial_party_accounts_one_active_personal_owner_uidx`
- `commercial_party_accounts_one_active_personal_per_user_key`

Their predicates are materially equivalent. This is index-maintenance overhead, not an authorization bypass. Do not drop any index until migration ownership/history and dependency checks identify the canonical one and a staged migration is tested.


## Follow-up: completed static call-chain review of the seven wrappers

A read-only production definition/grant review covered all seven functions previously flagged for lacking a direct `auth.uid()` reference:

| Public wrapper | Downstream authorization evidence |
|---|---|
| `commercial_create_inventory_item` | Delegates to `commercial_private.commercial_create_inventory_item`, which checks non-null `auth.uid()`, active organization membership, and owner/admin/manager role; it also checks activity/organization and location scope. |
| `commercial_create_purchase_order` | Delegates to `commercial_private.commercial_create_purchase_order`, which checks authentication, active organization membership, owner/admin/manager role, supplier ownership/state, receiving-location ownership/state, and offering/activity organization scope. |
| `commercial_link_purchase_order_item_offering` | Delegates to the private helper, which checks authentication, active organization membership and manager role, purchase-order organization, line ownership, lifecycle state, and offering/currency scope. |
| `commercial_receive_purchase_order` | Delegates to the private helper, which checks authentication, active organization membership and manager role, purchase/order/location scope, line quantities, and idempotency. |
| `commercial_transition_purchase_order` | Delegates to the private helper, which checks authentication, active organization membership and manager role, purchase-order ownership, and allowed state transition. |
| `commercial_create_inventory_item_with_category` | Calls the private inventory-creation helper above; category assignment is delegated to `commercial_assign_offering_category`, which independently checks authentication, active organization membership/manager role, offering/activity scope, and category ownership/state. |
| `commercial_create_inventory_item_with_category_and_unit` | Calls the category wrapper above, then updates the product returned by the authorized creation call. The category wrapper and private inventory helper enforce the organization/role checks; unit lookup requires an active unit code. |

For all seven public wrappers, the live privilege check returned `authenticated EXECUTE = true` and `anon EXECUTE = false`. The five `commercial_private` helpers returned no EXECUTE for either public API role. All inspected wrappers/helpers had explicit function-level `search_path` settings.

Assessment: the absence of a direct `auth.uid()` in these wrappers is explained by delegation to guarded helpers, not by an unguarded path in the inspected definitions. This closes the static triage item for the seven wrappers only. It does **not** replace disposable-PostgreSQL negative tests for cross-organization IDs, inactive members, insufficient roles, wrong activity/location, and invalid lifecycle transitions; keep those tests as release gates.
