ALTER TABLE authorization_grants
ADD COLUMN purpose TEXT NOT NULL DEFAULT 'unspecified';

CREATE INDEX IF NOT EXISTS idx_grants_subject_action_scope_context_purpose
ON authorization_grants(subject_person_id, action_code, scope_ref, context_ref, purpose, state, effective_from);
