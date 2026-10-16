"""Regression tests for explicitly supplied Payment timestamps."""
import uuid

import pytest

from agent_core.payment_application import PaymentApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def uid():
    return str(uuid.uuid4())


@pytest.mark.parametrize("invalid_timestamp", ["", 0, False])
def test_payment_creation_rejects_explicit_falsey_timestamps_without_writes(invalid_timestamp):
    db = connect_database()
    app = PaymentApplication()

    with pytest.raises(ValidationError, match="Payment creation timestamp"):
        app.create_payment(
            db,
            payment_id=uid(),
            payer_person_id=uid(),
            payee_person_id=uid(),
            amount_minor=100,
            currency_code="USD",
            context_ref=uid(),
            authorization_grant_id=uid(),
            agent_action_id=uid(),
            now=invalid_timestamp,
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments'"
    ).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("invalid_timestamp", ["", 0, False])
def test_payment_transition_rejects_explicit_falsey_timestamps_before_authorization(invalid_timestamp):
    db = connect_database()
    app = PaymentApplication()

    with pytest.raises(ValidationError, match="Payment transition timestamp"):
        app.transition_payment(
            db,
            uid(),
            "pending",
            expected_version=1,
            context_ref=uid(),
            authorization_grant_id=uid(),
            agent_action_id=uid(),
            now=invalid_timestamp,
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments'"
    ).fetchone()[0] == 0
    db.close()
