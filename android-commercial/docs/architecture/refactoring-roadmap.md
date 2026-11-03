# Commercial application refactoring execution ledger

Baseline branch: `fix/cloud-context-activation-fail-closed`  
Baseline commit: `b15ce71cd0066a3fd2eecc8195338767bc6f19f3`  
Working branch: `refactor/commercial-code-organization`

## Execution policy

Work in small, independently reviewable slices. Every slice must:
1. Inspect current implementation and all call sites before editing.
2. Preserve existing features and public behavior.
3. Add or update focused tests.
4. Run the narrow tests, full relevant test suite, and Android build when available.
5. Record exact commit and test results.
6. Never claim a cloud workflow is verified without an authenticated integration test.

No database migration is required for source-only organization. Do not modify Supabase production data as part of this refactor.

## Work packages

| ID | Work package | Current state | Acceptance |
|---|---|---|---|
| REF-000 | Inventory repository, Android source, tests, and ownership boundaries | TESTED (documentation inventory) | Existing files and ownership boundaries documented; this is not a runtime test |
| REF-001 | Establish baseline build/test commands and path-sensitive test inventory | IN PROGRESS | Commands and workflow triggers documented; complete path-sensitive test inventory still required |
| REF-002 | Extract shared network transport and cloud error mapping from cloud repository | OPEN | One transport implementation; request behavior unchanged |
| REF-003 | Split cloud repository by domain | OPEN | Sales, inventory, purchasing, customers, returns, expenses, accounting, portal repositories compile and tests pass |
| REF-004 | Separate MainActivity composition/navigation from local legacy persistence and screens | OPEN | MainActivity becomes composition root; local mode remains unchanged |
| REF-005 | Split report export infrastructure from report UI | EXTRACTED; TESTED pending CI | PDF/XLSX/print functions moved to ReportExport.kt; source-contract tests updated and XLSX unit tests added; run CI before marking tested |
| REF-006 | Split report catalog, data builders, filters, and feature screens | OPEN | Each report family has clear owner and calculation tests |
| REF-007 | Split catalog search, discovery, directory management, transfers, and invoice linking | OPEN | Catalog workflows remain intact |
| REF-008 | Split pure report calculations by business domain | PARTIALLY EXTRACTED; TESTS PENDING CI | Payment-request amount parsing/formatting now has its own pure Kotlin owner; formula parity tests and remaining report-domain extraction still required |
| REF-009 | Organize existing feature files and shared UI components | OPEN | No duplicated feature or model owners |
| REF-010 | Organize tests and update code reference | OPEN | Every feature points to focused tests and current source owner |
| REF-011 | Full regression, APK build, and release review | BLOCKED | CI green on final refactor commit; APK built from same SHA |

## Risk-first execution order

1. REF-001: inspect build/test setup and tests that refer to exact source paths or declarations.
2. REF-002 and REF-003: isolate transport first, then move one domain at a time. Keep compatibility wrappers until all callers migrate.
3. REF-004: extract local storage/models and navigation without changing cold-start/auth state transitions.
4. REF-005 and REF-006: separate export, calculations, and presentation without changing totals or report availability.
5. REF-007: split catalog by independent workflows; preserve global catalog vs offering vs local inventory distinctions.
6. REF-008 through REF-010: finish pure-domain and test organization.
7. REF-011: run full validation and build a fresh APK from the exact final commit.

## Work completed on this branch\n\n- Added the architecture overview, module-boundary inventory, and this execution ledger.\n- Extracted XLSX generation, native PDF generation, and Android print preview from `ReportsCenterScreen.kt` into `ReportExport.kt`. The functions remain `internal` so existing report UI callers keep their behavior.\n- Updated `tests/test_reports_center_source_contract.py` to validate the new export owner.\n- Added `ReportExportTest.kt` to validate Arabic RTL XLSX contents, empty-export rejection, XML special-character escaping, and columns beyond `Z`.\n- Extracted merchant payment-request amount parsing/formatting from `ReportCalculations.kt` into `CustomerPaymentRequestAmount.kt`; updated the Python source-contract test to enforce the new ownership. Existing Kotlin unit tests remain the behavioral contract.\n- No database schema, RPC, or production data was changed.\n\n## Current limitations

- The latest PR head has not been confirmed by CI after the last two commits in the prior work.
- No authenticated Android end-to-end commercial workflow has been proven by this source inventory.
- A source move or a passing static contract test does not prove Supabase production readiness.
- This branch starts from the latest reviewed PR head and is intentionally separate; it does not merge PR #714 or change `main`.
