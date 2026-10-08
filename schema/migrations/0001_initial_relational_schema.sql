PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TEXT NOT NULL
);

CREATE TABLE persons (
    person_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('active','suspended','merged','deactivated')),
    canonical_person_id TEXT NULL REFERENCES persons(person_id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (canonical_person_id IS NULL OR canonical_person_id <> person_id)
);

CREATE TABLE identifiers (
    identifier_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    identifier_type TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    uniqueness_scope TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('issued','revoked','historical')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE access_accounts (
    access_account_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    state TEXT NOT NULL CHECK (state IN ('active','suspended','expired','recovery')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE authenticators (
    authenticator_id TEXT PRIMARY KEY,
    access_account_id TEXT NOT NULL REFERENCES access_accounts(access_account_id),
    authenticator_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('enrolled','revoked','expired')),
    external_identity TEXT NULL,
    enrolled_at TEXT NOT NULL,
    revoked_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL
);

CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,
    device_fingerprint_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','lost','revoked','retired')),
    person_id TEXT NULL REFERENCES persons(person_id),
    access_account_id TEXT NULL REFERENCES access_accounts(access_account_id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE sessions (
    session_id TEXT PRIMARY KEY,
    access_account_id TEXT NOT NULL REFERENCES access_accounts(access_account_id),
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    state TEXT NOT NULL CHECK (state IN ('started','active','expired','cancelled')),
    started_at TEXT NOT NULL,
    ended_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL
);

CREATE TABLE activities (
    activity_id TEXT PRIMARY KEY,
    owner_person_id TEXT NOT NULL REFERENCES persons(person_id),
    state TEXT NOT NULL CHECK (state IN ('active','suspended','ended')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE memberships (
    membership_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    state TEXT NOT NULL CHECK (state IN ('active','suspended','ended')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE role_assignments (
    role_assignment_id TEXT PRIMARY KEY,
    membership_id TEXT NOT NULL REFERENCES memberships(membership_id),
    role_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','suspended','ended')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE products (
    product_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('draft','active','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE services (
    service_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('draft','active','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE offerings (
    offering_id TEXT PRIMARY KEY,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    service_id TEXT NULL REFERENCES services(service_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    state TEXT NOT NULL CHECK (state IN ('draft','active','ended','withdrawn')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE inventory_positions (
    inventory_position_id TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    product_id TEXT NULL REFERENCES products(product_id),
    offering_id TEXT NULL REFERENCES offerings(offering_id),
    location_ref TEXT NULL,
    scope_key TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('observed','effective','closed')),
    quantity_minor INTEGER NULL,
    observed_at TEXT NOT NULL,
    effective_from TEXT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE sales (
    sale_id TEXT PRIMARY KEY,
    offering_id TEXT NOT NULL REFERENCES offerings(offering_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    buyer_person_id TEXT NULL REFERENCES persons(person_id),
    state TEXT NOT NULL CHECK (state IN ('initiated','confirmed','completed','cancelled','returned')),
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE invoices (
    invoice_id TEXT PRIMARY KEY,
    sale_id TEXT NOT NULL REFERENCES sales(sale_id),
    obligation_id TEXT NULL REFERENCES obligations(obligation_id),
    state TEXT NOT NULL CHECK (state IN ('issued','void','settled')),
    issuer_ref TEXT NOT NULL,
    invoice_number TEXT NOT NULL,
    issue_at TEXT NOT NULL,
    due_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (issuer_ref, invoice_number)
);

CREATE TABLE financial_accounts (
    financial_account_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    account_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','closed')),
    provider_ref TEXT NULL,
    external_account_ref TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE loans (
    loan_id TEXT PRIMARY KEY,
    lender_person_id TEXT NOT NULL REFERENCES persons(person_id),
    borrower_person_id TEXT NOT NULL REFERENCES persons(person_id),
    state TEXT NOT NULL CHECK (state IN ('proposed','active','closed','cancelled')),
    principal_minor INTEGER NOT NULL CHECK (principal_minor > 0),
    currency_code TEXT NOT NULL,
    maturity_at TEXT NULL,
    provider_ref TEXT NULL,
    external_loan_ref TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (lender_person_id <> borrower_person_id)
);

CREATE TABLE obligations (
    obligation_id TEXT PRIMARY KEY,
    creditor_person_id TEXT NOT NULL REFERENCES persons(person_id),
    debtor_person_id TEXT NOT NULL REFERENCES persons(person_id),
    source_invoice_id TEXT NULL REFERENCES invoices(invoice_id),
    loan_id TEXT NULL REFERENCES loans(loan_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    currency_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('proposed','open','due','overdue','satisfied','cancelled')),
    due_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (creditor_person_id <> debtor_person_id)
);

CREATE TABLE payments (
    payment_id TEXT PRIMARY KEY,
    obligation_id TEXT NULL REFERENCES obligations(obligation_id),
    invoice_id TEXT NULL REFERENCES invoices(invoice_id),
    payer_person_id TEXT NOT NULL REFERENCES persons(person_id),
    payee_person_id TEXT NOT NULL REFERENCES persons(person_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    currency_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('initiated','pending','completed','failed','cancelled')),
    provider_ref TEXT NULL,
    external_payment_ref TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (payer_person_id <> payee_person_id)
);

CREATE TABLE settlements (
    settlement_id TEXT PRIMARY KEY,
    payment_id TEXT NOT NULL REFERENCES payments(payment_id),
    obligation_id TEXT NOT NULL REFERENCES obligations(obligation_id),
    state TEXT NOT NULL CHECK (state IN ('pending','matched','settled','failed','reversed')),
    settled_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (obligation_id)
);

CREATE TABLE financial_transactions (
    financial_transaction_id TEXT PRIMARY KEY,
    settlement_id TEXT NULL REFERENCES settlements(settlement_id),
    financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    currency_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('recognized','void')),
    recognition_source_type TEXT NOT NULL,
    recognition_source_ref TEXT NOT NULL,
    recognized_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (
        (settlement_id IS NOT NULL AND recognition_source_type = 'settlement')
        OR
        (settlement_id IS NULL AND recognition_source_type IN ('reversal','correction'))
    )
);

CREATE TABLE ledger_entries (
    ledger_entry_id TEXT PRIMARY KEY,
    financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
    financial_transaction_id TEXT NOT NULL REFERENCES financial_transactions(financial_transaction_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    currency_code TEXT NOT NULL,
    entry_sequence INTEGER NOT NULL CHECK (entry_sequence > 0),
    state TEXT NOT NULL CHECK (state IN ('posted','void')),
    posted_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (financial_transaction_id, entry_sequence)
);

CREATE TABLE instruments (
    instrument_id TEXT PRIMARY KEY,
    instrument_type TEXT NOT NULL,
    issuer_ref TEXT NOT NULL,
    canonical_identifier TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('issued','active','redeemed','revoked','expired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (instrument_type, issuer_ref, canonical_identifier)
);

CREATE TABLE instrument_usages (
    instrument_usage_id TEXT PRIMARY KEY,
    instrument_id TEXT NOT NULL REFERENCES instruments(instrument_id),
    action_ref TEXT NOT NULL,
    context_ref TEXT NULL,
    state TEXT NOT NULL CHECK (state IN ('recorded','reversed')),
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL
);

CREATE TABLE family_relationships (
    family_relationship_id TEXT PRIMARY KEY,
    person_a_id TEXT NOT NULL REFERENCES persons(person_id),
    person_b_id TEXT NOT NULL REFERENCES persons(person_id),
    relationship_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','ended','disputed')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    CHECK (person_a_id <> person_b_id)
);

CREATE TABLE delegations (
    delegation_id TEXT PRIMARY KEY,
    delegator_person_id TEXT NOT NULL REFERENCES persons(person_id),
    subject_person_id TEXT NOT NULL REFERENCES persons(person_id),
    context_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','suspended','revoked','expired')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (delegator_person_id <> subject_person_id)
);

CREATE TABLE authorization_grants (
    authorization_grant_id TEXT PRIMARY KEY,
    subject_person_id TEXT NULL REFERENCES persons(person_id),
    agent_id TEXT NULL REFERENCES agents(agent_id),
    action_code TEXT NOT NULL,
    scope_ref TEXT NOT NULL,
    context_ref TEXT NOT NULL,
    delegation_id TEXT NULL REFERENCES delegations(delegation_id),
    state TEXT NOT NULL CHECK (state IN ('active','suspended','revoked','expired')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (subject_person_id IS NOT NULL OR agent_id IS NOT NULL)
);

CREATE TABLE agents (
    agent_id TEXT PRIMARY KEY,
    owner_person_id TEXT NULL REFERENCES persons(person_id),
    configured_identity_ref TEXT NOT NULL,
    context_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','suspended','revoked','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE authority_policies (
    authority_policy_id TEXT PRIMARY KEY,
    policy_key TEXT NOT NULL,
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    state TEXT NOT NULL CHECK (state IN ('draft','active','retired')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    policy_ref TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (policy_key, policy_version)
);

CREATE TABLE agent_actions (
    agent_action_id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL REFERENCES agents(agent_id),
    action_code TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    context_ref TEXT NOT NULL,
    authority_policy_id TEXT NULL REFERENCES authority_policies(authority_policy_id),
    authorization_grant_id TEXT NULL REFERENCES authorization_grants(authorization_grant_id),
    state TEXT NOT NULL CHECK (state IN ('proposed','prepared','delegated-execution','approved','executed','rejected')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    executed_at TEXT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (authorization_grant_id)
);

CREATE TABLE approvals (
    approval_id TEXT PRIMARY KEY,
    agent_action_id TEXT NOT NULL REFERENCES agent_actions(agent_action_id),
    approval_gate_code TEXT NOT NULL DEFAULT 'default',
    state TEXT NOT NULL CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    approver_person_id TEXT NULL REFERENCES persons(person_id),
    decision_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (agent_action_id, approval_gate_code)
);

CREATE TABLE conversations (
    conversation_id TEXT PRIMARY KEY,
    context_ref TEXT NOT NULL,
    conversation_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','closed','archived')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE participants (
    participant_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id),
    participant_person_id TEXT NULL REFERENCES persons(person_id),
    participant_ref TEXT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','left','removed')),
    joined_at TEXT NOT NULL,
    left_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    CHECK (participant_person_id IS NOT NULL OR participant_ref IS NOT NULL)
);

CREATE TABLE messages (
    message_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id),
    sender_person_id TEXT NULL REFERENCES persons(person_id),
    sender_ref TEXT NULL,
    message_type TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','edited','deleted')),
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    CHECK (sender_person_id IS NOT NULL OR sender_ref IS NOT NULL)
);

CREATE TABLE patient_contexts (
    patient_context_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    context_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','closed','restricted')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE encounters (
    encounter_id TEXT PRIMARY KEY,
    patient_context_id TEXT NOT NULL REFERENCES patient_contexts(patient_context_id),
    encounter_type TEXT NOT NULL,
    service_id TEXT NULL REFERENCES services(service_id),
    state TEXT NOT NULL CHECK (state IN ('planned','active','completed','cancelled')),
    started_at TEXT NOT NULL,
    ended_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE clinical_records (
    clinical_record_id TEXT PRIMARY KEY,
    patient_context_id TEXT NOT NULL REFERENCES patient_contexts(patient_context_id),
    encounter_id TEXT NULL REFERENCES encounters(encounter_id),
    record_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('draft','final','amended','void')),
    recorded_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE results_reports (
    result_report_id TEXT PRIMARY KEY,
    patient_context_id TEXT NOT NULL REFERENCES patient_contexts(patient_context_id),
    encounter_id TEXT NULL REFERENCES encounters(encounter_id),
    result_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('draft','final','amended','void')),
    issued_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE prescriptions (
    prescription_id TEXT PRIMARY KEY,
    patient_context_id TEXT NOT NULL REFERENCES patient_contexts(patient_context_id),
    encounter_id TEXT NULL REFERENCES encounters(encounter_id),
    prescriber_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('draft','issued','dispensed','cancelled','expired')),
    issued_at TEXT NOT NULL,
    expires_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE pending_operations (
    pending_operation_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    namespace TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    operation_kind TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('queued','submitted','finalized','rejected','conflicted','cancelled')),
    queued_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (namespace, operation_id)
);

CREATE TABLE conflicts (
    conflict_id TEXT PRIMARY KEY,
    target_ref TEXT NOT NULL,
    domain_ref TEXT NOT NULL,
    conflict_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('detected','resolved','rejected')),
    detected_at TEXT NOT NULL,
    resolved_at TEXT NULL,
    resolution_ref TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL
);

CREATE TABLE durable_operation_records (
    durable_operation_record_id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    operation_kind TEXT NOT NULL,
    actor_context_ref TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('reserved','completed','rejected','conflicted')),
    outcome_ref TEXT NULL,
    conflict_id TEXT NULL REFERENCES conflicts(conflict_id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (namespace, operation_id),
    CHECK (
        (state = 'conflicted' AND conflict_id IS NOT NULL)
        OR
        (state <> 'conflicted')
    )
);

CREATE TABLE audit_records (
    audit_record_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    actor_context_ref TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    result_status TEXT NOT NULL,
    operation_ref TEXT NULL,
    correlation_ref TEXT NULL,
    provenance_id TEXT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE provenance_records (
    provenance_id TEXT PRIMARY KEY,
    source_type TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    source_version_ref TEXT NULL,
    as_of_at TEXT NULL,
    derivation_type TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE domain_history (
    domain_history_id TEXT PRIMARY KEY,
    owner_domain TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    change_type TEXT NOT NULL,
    historical_at TEXT NOT NULL,
    actor_context_ref TEXT NOT NULL,
    prior_version_ref TEXT NULL,
    current_version_ref TEXT NULL,
    change_payload_ref TEXT NULL,
    created_at TEXT NOT NULL,
    provenance_id TEXT NULL
);

CREATE TABLE workflows (
    workflow_id TEXT PRIMARY KEY,
    workflow_namespace TEXT NOT NULL,
    workflow_kind TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('created','in_progress','recognized','failed','cancelled','reversed')),
    started_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (workflow_namespace, workflow_id)
);

CREATE TABLE workflow_events (
    workflow_event_id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
    event_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('created','in_progress','recognized','failed','cancelled','reversed')),
    event_sequence INTEGER NOT NULL CHECK (event_sequence > 0),
    occurred_at TEXT NOT NULL,
    detail_ref TEXT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (workflow_id, event_sequence)
);

CREATE INDEX idx_identifiers_person ON identifiers(person_id);
CREATE UNIQUE INDEX uq_identifiers_active_identity
    ON identifiers(identifier_type, uniqueness_scope, normalized_value)
    WHERE state = 'issued';

CREATE INDEX idx_access_accounts_person_state ON access_accounts(person_id, state);
CREATE INDEX idx_authenticators_account_state ON authenticators(access_account_id, state);
CREATE UNIQUE INDEX uq_devices_fingerprint ON devices(device_fingerprint_ref);
CREATE INDEX idx_sessions_account_state ON sessions(access_account_id, state);
CREATE INDEX idx_sessions_device_state ON sessions(device_id, state);

CREATE INDEX idx_activities_owner_state ON activities(owner_person_id, state);
CREATE INDEX idx_memberships_person_activity_state ON memberships(person_id, activity_id, state);
CREATE INDEX idx_memberships_activity_effective ON memberships(activity_id, effective_from, effective_to);
CREATE INDEX idx_role_assignments_membership_state ON role_assignments(membership_id, role_code, state, effective_from);

CREATE INDEX idx_products_state ON products(state);
CREATE INDEX idx_services_state ON services(state);
CREATE INDEX idx_offerings_product_activity_state ON offerings(product_id, activity_id, state, effective_from);
CREATE INDEX idx_offerings_service_activity ON offerings(service_id, activity_id);
CREATE INDEX idx_inventory_scope_state_freshness ON inventory_positions(scope_key, state, observed_at);
CREATE INDEX idx_inventory_offering ON inventory_positions(offering_id);
CREATE INDEX idx_inventory_product_location ON inventory_positions(product_id, location_ref);
CREATE INDEX idx_sales_activity_time_state ON sales(activity_id, occurred_at, state);
CREATE INDEX idx_sales_offering ON sales(offering_id);
CREATE INDEX idx_invoices_sale_state ON invoices(sale_id, state);
CREATE INDEX idx_invoices_obligation ON invoices(obligation_id);

CREATE UNIQUE INDEX uq_financial_external_account
    ON financial_accounts(provider_ref, external_account_ref)
    WHERE provider_ref IS NOT NULL AND external_account_ref IS NOT NULL;
CREATE INDEX idx_financial_accounts_person_state ON financial_accounts(person_id, state);
CREATE INDEX idx_loans_parties_state ON loans(lender_person_id, borrower_person_id, state);
CREATE INDEX idx_obligations_parties_state_due ON obligations(creditor_person_id, debtor_person_id, state, due_at);
CREATE INDEX idx_obligations_invoice ON obligations(source_invoice_id);
CREATE INDEX idx_obligations_loan ON obligations(loan_id);
CREATE INDEX idx_payments_obligation_state ON payments(obligation_id, state);
CREATE UNIQUE INDEX uq_payment_external
    ON payments(provider_ref, external_payment_ref)
    WHERE provider_ref IS NOT NULL AND external_payment_ref IS NOT NULL;
CREATE INDEX idx_settlements_payment ON settlements(payment_id);
CREATE INDEX idx_settlements_obligation ON settlements(obligation_id);
CREATE UNIQUE INDEX uq_financial_transactions_settlement
    ON financial_transactions(settlement_id)
    WHERE settlement_id IS NOT NULL;
CREATE UNIQUE INDEX uq_financial_transactions_source
    ON financial_transactions(recognition_source_type, recognition_source_ref);
CREATE INDEX idx_financial_transactions_account_time
    ON financial_transactions(financial_account_id, recognized_at);
CREATE INDEX idx_ledger_account_time
    ON ledger_entries(financial_account_id, posted_at, entry_sequence);
CREATE INDEX idx_ledger_transaction ON ledger_entries(financial_transaction_id);

CREATE INDEX idx_instruments_issuer_state ON instruments(issuer_ref, state);
CREATE INDEX idx_instrument_usages_instrument_time ON instrument_usages(instrument_id, occurred_at);

CREATE INDEX idx_family_relationship_a ON family_relationships(person_a_id, relationship_type, state);
CREATE INDEX idx_family_relationship_b ON family_relationships(person_b_id, relationship_type, state);
CREATE INDEX idx_delegations_subject_context ON delegations(subject_person_id, context_ref, state, effective_from);
CREATE INDEX idx_grants_subject_action_context ON authorization_grants(subject_person_id, action_code, context_ref, state, effective_from);
CREATE INDEX idx_grants_agent_action_context ON authorization_grants(agent_id, action_code, context_ref, state, effective_from);
CREATE INDEX idx_agent_actions_agent_state_time ON agent_actions(agent_id, state, created_at);
CREATE INDEX idx_agent_actions_grant ON agent_actions(authorization_grant_id);
CREATE INDEX idx_approvals_action_state ON approvals(agent_action_id, state);
CREATE INDEX idx_authority_policies_key_effective ON authority_policies(policy_key, state, effective_from);

CREATE INDEX idx_participants_conversation_state ON participants(conversation_id, state);
CREATE UNIQUE INDEX uq_active_participant
    ON participants(conversation_id, COALESCE(participant_person_id, participant_ref))
    WHERE state = 'active';
CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, occurred_at);

CREATE INDEX idx_patient_context_person_state ON patient_contexts(person_id, state);
CREATE INDEX idx_encounters_patient_time ON encounters(patient_context_id, started_at, state);
CREATE INDEX idx_clinical_records_patient_time ON clinical_records(patient_context_id, recorded_at);
CREATE INDEX idx_results_patient_time ON results_reports(patient_context_id, issued_at);
CREATE INDEX idx_prescriptions_patient_state ON prescriptions(patient_context_id, state, issued_at);

CREATE INDEX idx_pending_device_state_time ON pending_operations(device_id, state, queued_at);
CREATE INDEX idx_pending_operation_identity ON pending_operations(namespace, operation_id);
CREATE INDEX idx_conflicts_target_state ON conflicts(target_ref, domain_ref, state);
CREATE INDEX idx_durable_operation_state_time ON durable_operation_records(namespace, state, created_at);
CREATE INDEX idx_durable_operation_fingerprint ON durable_operation_records(namespace, request_fingerprint);

CREATE INDEX idx_audit_target_time ON audit_records(target_ref, occurred_at);
CREATE INDEX idx_audit_actor_time ON audit_records(actor_context_ref, occurred_at);
CREATE INDEX idx_provenance_source ON provenance_records(source_type, source_ref);
CREATE INDEX idx_history_target_time ON domain_history(target_ref, historical_at);
CREATE INDEX idx_history_domain_time ON domain_history(owner_domain, historical_at);

CREATE INDEX idx_workflows_namespace_state ON workflows(workflow_namespace, state, started_at);
CREATE INDEX idx_workflow_events_workflow_sequence ON workflow_events(workflow_id, event_sequence);

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0001_initial_relational_schema', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
