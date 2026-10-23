# Ruh Platform: 10–15 Year Architecture Readiness

**Purpose:** Preserve strategic options and avoid expensive rewrites as Ruh grows. This is a decision framework, not a prediction that any single technology will dominate.

## Evidence base reviewed (as of 2026-10-23)

- CNCF/SlashData, *State of Cloud Native Development Q1 2026*: platform engineering, hybrid deployments, and AI workloads are converging. https://www.cncf.io/reports/state-of-cloud-native-development-q1-2026/
- CNCF, *Annual Cloud Native Survey 2025*: the bottleneck is increasingly maturity, delivery practices, and operational capability rather than merely adopting tools. https://www.linuxfoundation.org/research/cncf-2025-annual-survey
- DORA, *State of AI-assisted Software Development 2025*: AI amplifies existing organizational strengths and weaknesses; foundational delivery systems matter. https://dora.dev/research/2025/dora-report/
- NIST, *AI Risk Management Framework* and Generative AI Profile: use documented governance, risk ownership, evaluation, and monitoring for AI capabilities. https://www.nist.gov/itl/ai-risk-management-framework
- OWASP, *Top 10 for Agentic Applications 2026*: autonomous agents introduce distinct identity, permission, tool-use, and workflow risks. https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/
- W3C, *Verifiable Credentials Data Model v2.0* (Recommendation, 2025): interoperable, verifiable digital claims are now standardized. https://www.w3.org/TR/vc-data-model/
- NIST, post-quantum cryptography standards FIPS 203/204/205: plan crypto agility for long-lived sensitive data and signed records. https://csrc.nist.gov/News/2024/postquantum-cryptography-fips-approved
- European Commission, interoperability of data processing services study (2026): portability and interoperability are becoming explicit architectural and policy concerns. https://digital-strategy.ec.europa.eu/en/library/results-study-interoperability-data-processing-services
- OpenTelemetry semantic conventions: consistent telemetry naming improves cross-service observability and vendor portability. https://opentelemetry.io/docs/concepts/semantic-conventions/

The evidence above mixes standards, industry surveys, and research. Survey statistics are not universal forecasts; Ruh should validate priorities against customers, jurisdictions, and budget.

## Decisions worth making early

### 1. Own the data and make it portable
- Define canonical domain entities and stable IDs; separate business IDs from vendor-generated IDs.
- Maintain documented schemas, API contracts, event schemas, and explicit schema versions.
- Support complete tenant export in open formats, including attachments metadata, audit records, and relationships.
- Test restore and export/import on a schedule. Avoid business-critical logic hidden only in a vendor-specific workflow.
- Keep migration scripts, architecture decisions, and data dictionaries version-controlled.

### 2. Financial correctness and auditability
- Use integer minor units or explicit decimal rules for money; store currency and rounding policy explicitly.
- Model payments, refunds, returns, stock movements, and journal postings as separate, correlated state machines.
- Use idempotency keys and unique source references for every externally retried financial or inventory operation.
- Make posted accounting records immutable; use reversals and adjustments rather than silent edits.
- Preserve actor, tenant, request/correlation ID, timestamp, reason, and before/after state for sensitive transitions.
- Reconcile ledger, payments, returns, and inventory automatically; alert on discrepancies.
- Never claim a refund was executed merely because it was approved.

### 3. Secure tenancy and identity
- Treat tenant isolation as a core invariant: test cross-tenant access negatively, not only successful access.
- Use least privilege, short-lived credentials, MFA for privileged users, and explicit role/permission models.
- Review SECURITY DEFINER functions and RLS policies as release gates.
- Separate production, staging, and development data; keep secrets out of source control and logs.
- Define retention, deletion, legal hold, data residency, and customer export policies before they become contractual obligations.

### 4. Resilience, recovery, and operability
- Define service-level objectives, recovery-time objective (RTO), and recovery-point objective (RPO).
- Automate backups and perform documented restore drills; a backup that has never been restored is an assumption.
- Add structured logs, metrics, distributed traces, correlation IDs, and alerting for failed jobs and stuck workflows.
- Use transactional outbox/inbox patterns for reliable integrations; design retries, deduplication, dead-letter handling, and replay.
- Add load tests and failure tests before scaling claims; do not adopt Kubernetes or microservices solely for fashion.

### 5. AI-ready, not AI-dependent
- Start AI with read-only search, summarization, anomaly explanation, and draft recommendations.
- Put every AI action behind typed tools, narrow permissions, policy checks, rate limits, and human approval for money, identity, access, and irreversible actions.
- Log model/version, prompt/template version, tool calls, approvals, outputs, and evaluations while minimizing sensitive content retention.
- Build evaluation datasets from realistic workflows and regression tests; measure accuracy, latency, cost, and harmful-action rate.
- Keep model providers replaceable behind an internal interface; do not make proprietary model output the only copy of a business decision.
- Never let retrieved text or customer-provided documents grant an agent authority.

### 6. Crypto and trust agility
- Keep cryptographic algorithms and key management configurable through maintained libraries and provider interfaces.
- Inventory where long-lived personal, financial, and signed data is encrypted or signed.
- Track post-quantum migration guidance and vendor support; do not implement novel cryptography in-house.
- Use verifiable credentials only where a real cross-organization trust or verification use case exists.

### 7. Modular product evolution
- Prefer a well-structured modular monolith while product boundaries and team size are still evolving.
- Separate domains such as identity/tenancy, catalog, sales, inventory, payments, ledger, workflow, documents, and integrations through explicit interfaces.
- Introduce asynchronous events where independent consumers or durable workflows justify them.
- Avoid premature microservices; make module boundaries and ownership explicit first.

## 10–15 year regret register

| Regret risk | Cost if deferred | Low-regret action now |
| --- | --- | --- |
| Cannot export customer data cleanly | Vendor lock-in, lost trust, costly migration | Versioned export/import and restore tests |
| Financial changes cannot be reconstructed | Audit disputes and unreliable reporting | Immutable ledger, append-only audit trail, reconciliation |
| Tenant data leaks across organizations | Severe trust and legal impact | RLS policy tests and cross-tenant negative tests |
| Background workflows duplicate money/stock changes | Financial loss and reconciliation burden | Idempotency keys, unique event/source IDs, outbox/inbox |
| No tested recovery process | Long outage or permanent data loss | Restore drills, RTO/RPO, runbooks |
| AI agent has broad uncontrolled authority | Fraud, privacy breach, destructive actions | Scoped identities, approvals, tool policies, action audit |
| Provider-specific data model dominates | Expensive replacement or expansion | Canonical domain model, open formats, adapters |
| Poor observability | Slow incident diagnosis and high support cost | Correlated logs, metrics, traces, actionable alerts |
| No migration/retention policy | Data rot, compliance friction | Versioned migrations, lifecycle policies, data ownership |
| Prematurely complex infrastructure | Higher cost and slower delivery | Modular monolith until evidence supports distribution |

## Suggested roadmap

### Next 90 days
1. Complete return/refund and inventory idempotency integration tests in staging.
2. Add pgTAP checks and run them in CI against a disposable database.
3. Add tenant-isolation tests, especially for every SECURITY DEFINER RPC.
4. Document restore procedure and complete one restore drill.
5. Add export/import specification and data dictionary.
6. Confirm whether the current application actually uses Supabase; document SQLite and Supabase as separate until integration is proven.

### 3–12 months
1. Add outbox/inbox for payment, notification, and partner integrations.
2. Add reconciliation jobs for ledger/payment/refund/inventory.
3. Define SLOs, error budgets, observability, and incident runbooks.
4. Establish AI evaluation and approval gates before agentic write actions.
5. Formalize tenant lifecycle, retention, deletion, and data residency controls.

### 1–3 years
1. Reassess modular boundaries against actual load and team ownership.
2. Add region/provider portability where customer demand and recovery requirements justify it.
3. Establish a cryptographic agility plan for long-retention data.
4. Adopt interoperable credentials or industry data standards only for demonstrated partner workflows.

### Ongoing
- Quarterly architecture and threat-model review.
- Quarterly restore and tenant-isolation test review.
- Annual export/import and provider-exit exercise.
- Track standards and AI governance updates without chasing every trend.

## Architecture rule

Build durable invariants early—correctness, identity, portability, audit, recovery, observability, and controlled change. Delay expensive infrastructure choices until measured load, team structure, customer requirements, or regulation justify them.
