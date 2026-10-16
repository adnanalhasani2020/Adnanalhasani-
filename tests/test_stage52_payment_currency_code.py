"""Regression tests for Payment currency-code representation integrity."""
import pytest

from agent_core.payment_application import PaymentApplication
from agent_core.shared import ValidationError


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("USD", "USD"),
        ("usd", "USD"),
        (" Yer ", "YER"),
        ("jPy", "JPY"),
    ],
)
def test_currency_code_is_trimmed_and_canonicalized(raw, expected):
    assert PaymentApplication._currency_code(raw) == expected


@pytest.mark.parametrize(
    "invalid",
    [None, 0, False, "", "US", "USDD", "U$D", "U D", "éUR", "１２３"],
)
def test_currency_code_rejects_invalid_representation(invalid):
    with pytest.raises(ValidationError, match="three-letter ASCII code"):
        PaymentApplication._currency_code(invalid)


def test_payment_creation_compares_currency_codes_canonically():
    from test_stage26_payment_application import seed, authorize, uid, STAMP
    from agent_core.persistence import connect_database

    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    app = PaymentApplication()
    payment_id = uid()
    grant, action = authorize(
        db,
        agent=agent,
        context=context,
        payment_id=payment_id,
        action_code="payment.create",
        approver=approver,
    )
    payment = app.create_payment(
        db,
        payment_id=payment_id,
        payer_person_id=payer,
        payee_person_id=payee,
        obligation_id=obligation,
        amount_minor=1000,
        currency_code="yer",
        context_ref=context,
        authorization_grant_id=grant,
        agent_action_id=action,
        now=STAMP,
    )
    assert payment.currency_code == "YER"
    db.close()
