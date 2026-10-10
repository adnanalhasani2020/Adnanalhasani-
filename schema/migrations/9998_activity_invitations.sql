CREATE TABLE IF NOT EXISTS activity_invitations (
    invitation_id TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL REFERENCES activities(activity_id) ON DELETE RESTRICT,
    inviter_person_id TEXT NOT NULL REFERENCES persons(person_id) ON DELETE RESTRICT,
    invitee_person_id TEXT NOT NULL REFERENCES persons(person_id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_activity_invitations_activity_created
    ON activity_invitations(activity_id, created_at, invitation_id);
CREATE INDEX IF NOT EXISTS idx_activity_invitations_invitee_created
    ON activity_invitations(invitee_person_id, created_at, invitation_id);
