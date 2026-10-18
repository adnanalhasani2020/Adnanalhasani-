# Operational Sales Report — Authorization Contract (Pending Owner Decision)

Status: **blocked by default; not approved for general access**.

## Current security invariant

A commercial session establishes the identity of a commercial account. It does not establish permission to read aggregate or cross-account sales. Until an owner-approved audience and data-scope policy is implemented server-side, `GET /api/reports/sales` must return `403 Forbidden` for authenticated callers and must not disclose report rows, summaries, CSV content, or report metadata. Unauthenticated callers remain unauthenticated. Query parameters, direct requests, and choosing CSV must not bypass this rule.

## Decision required from the system owner

Choose and document:

1. **Audience** — which existing, approved principal or permission is allowed to read the report? Do not create a new role or reinterpret an existing role without explicit approval.
2. **Data scope** — all sales, one activity, one organization, or another explicitly defined scope. The server must derive and enforce scope from authoritative persisted grants, not browser settings or caller-supplied identifiers.
3. **Export** — whether CSV uses the same permission and scope as JSON, or requires a separately approved capability.
4. **Audit** — which denied/allowed report accesses must be recorded, and which metadata may be logged without exposing sale details.

## Implementation gate after approval

- Reuse an existing grant only if its action, subject, active state, and scope semantics match the approved policy. An authenticated session or a role assignment by itself is insufficient.
- Enforce authorization before report queries and on every representation/route.
- Derive allowed scope on the server; ignore caller attempts to widen it.
- Keep errors generic and do not include sale identifiers, totals, customer information, query results, or CSV bytes in denial responses or logs.
- Test unauthenticated access, authenticated-but-ungranted access, JSON, CSV, direct path access, query-parameter variations, cross-scope access, changed identity, and unchanged database state.
- Preserve the accounting boundary: operational sale-line totals are not automatically recognized accounting revenue.

## Current decision

No owner-approved audience/scope contract was found during this review. Therefore the endpoint remains blocked. This document records the missing decision; it does not grant authority or change permissions.
