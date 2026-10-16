# Financial Opening Balance — Implementation Contract

## Purpose
Record a starting amount for an existing Financial Account without introducing a separately stored Account Balance. The opening amount is represented as a Finance-owned recognized Financial Transaction plus a posted Ledger Entry; the Balance Reader continues to derive the balance from ledger facts.

## Rules
- Only an active Financial Account may receive an opening entry.
- One opening entry is allowed per Financial Account and currency. A database partial unique index enforces this even if multiple callers race.
- Amounts are signed integer minor units. Positive and negative opening balances are supported; zero is rejected because it creates no financial movement and should remain an implicit zero projection.
- Currency is a three-letter uppercase code; source reference is mandatory and bounded.
- Recognition requires connectivity, a persisted active agent-bound AuthorizationGrant, an approved matching AgentAction, and a recorded approval from an active Person.
- The transaction, posted entry, and domain-history record are written atomically. Failure leaves no partial financial truth.
- A duplicate is rejected; corrections must use the correction/reversal flow and must not overwrite the opening entry.
- Balance is not stored or edited by this operation.

## Migration
Migration 0008 rebuilds the transaction and ledger tables inside the existing migration transaction to extend the source-type CHECK constraint while retaining foreign keys and existing rows. It recreates the existing financial indexes and adds a unique per-account/per-currency opening-entry index.
