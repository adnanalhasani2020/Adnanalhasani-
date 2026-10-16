# EXEC-0043 — Payment currency-code representation integrity

**Status:** Implemented on an isolated branch; GitHub Actions on the exact pull-request head is the verification gate.

## Gap
Payment creation accepted any non-empty currency string and retained case differences. A linked Obligation using `YER` could reject a semantically equivalent input such as `yer`, while malformed values could be persisted when no linked Obligation forced a comparison.

## Change
- Validate currency-code representation as exactly three ASCII letters.
- Normalize surrounding whitespace and uppercase the code before authorization or writes.
- Compare a linked Obligation's code after applying the same representation normalization.
- Add tests for normalization, malformed values and linked Payment/Obligation currency matching.

## Boundary
This is a representation-integrity guard only. It does not claim that a code is officially assigned, decide supported currencies/markets, define minor-unit precision, set an account's default currency, or provide exchange rates. Those remain subject to Q-0023 (Critical Decision). No schema migration, currency conversion, or financial posting behavior is introduced.

## Traceability
- Payment persistence: `src/agent_core/payment_application.py`.
- Tests: `tests/test_stage52_payment_currency_code.py`.
- Open question: `Q-0023` in `docs/01-المتطلبات/07-سجل-الأسئلة-المفتوحة.md`.

## Verification
GitHub Actions on the final PR head is authoritative; no pass is claimed until it completes.
