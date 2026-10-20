import json
import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError
from hospital.lab_guide import (
    approve_specimen_guide_entry,
    create_specimen_guide_entry,
    get_specimen_guide_history,
    list_specimen_guide_entries,
    retire_specimen_guide_entry,
    update_specimen_guide_draft,
)


class LabSpecimenGuideTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "lab-guide.sqlite3")

    def tearDown(self):
        self.temp.cleanup()

    def create_draft(self):
        return create_specimen_guide_entry(
            self.db, test_code="cbc", test_name="CBC",
            specimen_type="EDTA blood", container="Facility-approved EDTA tube",
            preparation="Follow locally approved protocol",
            collection_instructions="Use the current approved collection procedure",
            actor="editor", at="2026-10-20T08:00:00Z",
        )

    def test_draft_hidden_from_normal_search_until_approved(self):
        guide_id = self.create_draft()
        self.assertEqual(list_specimen_guide_entries(self.db), [])
        self.assertEqual(len(list_specimen_guide_entries(self.db, approved_only=False)), 1)
        approve_specimen_guide_entry(
            self.db, guide_id=guide_id, reviewer="lab-lead",
            at="2026-10-20T09:00:00Z", source_reference="Facility SOP LAB-001 v2",
        )
        entries = list_specimen_guide_entries(self.db, search="CBC")
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["status"], "approved")
        self.assertEqual(entries[0]["source_reference"], "Facility SOP LAB-001 v2")

    def test_author_cannot_approve_own_entry(self):
        guide_id = self.create_draft()
        with self.assertRaisesRegex(HospitalError, "distinct from the author"):
            approve_specimen_guide_entry(
                self.db, guide_id=guide_id, reviewer="editor",
                at="2026-10-20T09:00:00Z", source_reference="SOP-1",
            )

    def test_draft_update_increments_version_and_records_history(self):
        guide_id = self.create_draft()
        version = update_specimen_guide_draft(
            self.db, guide_id=guide_id, actor="editor-2",
            at="2026-10-20T08:30:00Z", stability="Per approved local SOP",
        )
        self.assertEqual(version, 2)
        history = get_specimen_guide_history(self.db, guide_id=guide_id)
        self.assertEqual([item["action"] for item in history], ["created", "updated"])
        snapshot = json.loads(history[-1]["snapshot_json"])
        self.assertEqual(snapshot["version"], 2)
        with self.assertRaisesRegex(HospitalError, "distinct from the author"):
            approve_specimen_guide_entry(
                self.db, guide_id=guide_id, reviewer="editor-2",
                at="2026-10-20T09:00:00Z", source_reference="SOP-1",
            )

    def test_approved_entry_cannot_be_edited_as_draft(self):
        guide_id = self.create_draft()
        approve_specimen_guide_entry(
            self.db, guide_id=guide_id, reviewer="lab-lead",
            at="2026-10-20T09:00:00Z", source_reference="Facility SOP LAB-001 v2",
        )
        with self.assertRaisesRegex(HospitalError, "only an existing draft"):
            update_specimen_guide_draft(
                self.db, guide_id=guide_id, actor="editor",
                at="2026-10-20T09:30:00Z", test_name="Modified CBC",
            )

    def test_retirement_is_audited_and_hidden_from_search(self):
        guide_id = self.create_draft()
        approve_specimen_guide_entry(
            self.db, guide_id=guide_id, reviewer="lab-lead",
            at="2026-10-20T09:00:00Z", source_reference="Facility SOP LAB-001 v2",
        )
        retire_specimen_guide_entry(
            self.db, guide_id=guide_id, actor="lab-manager",
            at="2026-10-20T10:00:00Z", reason="SOP superseded",
        )
        self.assertEqual(list_specimen_guide_entries(self.db), [])
        history = get_specimen_guide_history(self.db, guide_id=guide_id)
        self.assertEqual(history[-1]["action"], "retired")
        with self.db.connect() as conn:
            audit = conn.execute(
                "SELECT action FROM audit_log WHERE entity_type='lab_specimen_guide' ORDER BY id"
            ).fetchall()
        self.assertEqual([row["action"] for row in audit], [
            "lab.specimen_guide.created", "lab.specimen_guide.approved", "lab.specimen_guide.retired"
        ])

    def test_validation_and_pagination(self):
        with self.assertRaisesRegex(HospitalError, "test_code"):
            create_specimen_guide_entry(
                self.db, test_code="bad code", test_name="CBC", specimen_type="blood",
                container="tube", actor="editor", at="2026-10-20T08:00:00Z",
            )
        self.create_draft()
        with self.assertRaisesRegex(HospitalError, "limit"):
            list_specimen_guide_entries(self.db, limit=101)


if __name__ == "__main__":
    unittest.main()
