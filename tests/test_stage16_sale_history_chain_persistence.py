"""Stage 16 — persistence continuity for a multi-entry Sale history chain.

Traceability:
SPEC-0005 §11 and invariant 38; SPEC-0021 §§7, 10 and invariant 4;
SPEC-0023 §§7, 12 and invariant 5; REQ-FUNC-0037 / REQ-SEC-0051.
History references are treated as opaque persisted references; this test does not
define a new lifecycle vocabulary or equate "completed" with "fulfilled".
"""
import uuid

from agent_core.persistence import connect_database

NOW = "2026-10-09T00:00:00Z"


def _id():
    return str(uuid.uuid4())


def test_sale_state_and_entire_history_chain_survive_database_reopen(tmp_path):
    path = tmp_path / "sale-history-chain.sqlite"
    db = connect_database(path)
    person_id, activity_id = _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", NOW, NOW),
    )
    product_id, offering_id, sale_id = _id(), _id(), _id()
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", NOW, None, NOW, NOW),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "initiated", NOW, NOW, NOW),
    )

    opaque_refs = (
        (None, "history-ref-1"),
        ("history-ref-1", "history-ref-2"),
        ("history-ref-2", "history-ref-3"),
        ("history-ref-3", "history-ref-4"),
    )
    states = ("initiated", "confirmed", "completed", "returned")
    history_ids = []
    for state, (prior_ref, current_ref) in zip(states, opaque_refs):
        db.execute("UPDATE sales SET state=?,updated_at=? WHERE sale_id=?", (state, NOW, sale_id))
        history_id = _id()
        db.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (history_id, "commerce", sale_id, "sale_state_transition", NOW, activity_id,
             prior_ref, current_ref, NOW),
        )
        history_ids.append(history_id)

    db.commit()
    db.close()

    db = connect_database(path)
    sale = db.execute(
        "SELECT sale_id,offering_id,activity_id,state FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()
    history = db.execute(
        "SELECT domain_history_id,target_ref,owner_domain,change_type,actor_context_ref,"
        "prior_version_ref,current_version_ref FROM domain_history WHERE target_ref=? ORDER BY rowid",
        (sale_id,),
    ).fetchall()

    assert sale == (sale_id, offering_id, activity_id, "returned")
    assert [row[0] for row in history] == history_ids
    assert len(history) == 4
    assert all(row[1:5] == (sale_id, "commerce", "sale_state_transition", activity_id) for row in history)
    assert [(row[5], row[6]) for row in history] == list(opaque_refs)
    assert all(history[index][6] == history[index + 1][5] for index in range(len(history) - 1))
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()
