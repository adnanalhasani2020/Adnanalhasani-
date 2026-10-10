"""Service domain states must match the persisted Service contract."""
import re

from agent_core.domain_inventory import Service, ServiceState
from agent_core.persistence import connect_database


def test_service_state_vocabulary_matches_schema_check_constraint():
    db = connect_database()
    sql = db.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='services'"
    ).fetchone()[0]
    match = re.search(r"state\s+IN\s*\(([^)]*)\)", sql, flags=re.IGNORECASE)
    assert match is not None
    persisted_states = set(re.findall(r"'([^']+)'", match.group(1)))
    assert persisted_states == {state.value for state in ServiceState}
    db.close()


def test_new_service_uses_a_persistable_initial_state():
    service = Service(name="Delivery")
    assert service.state is ServiceState.DRAFT
    assert service.state.value == "draft"
