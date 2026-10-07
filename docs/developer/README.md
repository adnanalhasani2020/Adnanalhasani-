# Developer Structure — Stage 7

- domain: Domain Truth, invariants, lifecycle behavior; no storage/transport/UI/provider/framework dependency.
- application: orchestration; no ownership of Domain Truth.
- infrastructure: replaceable adapter boundary; this batch only emits basic audit/log records.
- config: runtime configuration boundary.
- tests: executable semantic invariants and rejection tests.

Current implementation constraint: Python 3.11+ and pytest for tests. It is not a project architecture decision and does not resolve any DEC.
