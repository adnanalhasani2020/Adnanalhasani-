from uuid import uuid4

import pytest

from agent_core.domain_purchases import Purchase, PurchaseLine, PurchaseState
from agent_core.shared import ValidationError


def make_purchase(quantity=5):
    line = PurchaseLine(
        product_id=uuid4(), quantity_units=quantity, unit_label="box",
        unit_cost_minor=1250, currency_code="YER",
    )
    return Purchase(supplier_ref="organization:vendor-1", activity_id=uuid4(), lines=(line,)), line


def test_purchase_lifecycle_and_partial_receipt():
    purchase, line = make_purchase()
    purchase = purchase.submit().approve().mark_ordered()
    purchase = purchase.receive(line.id, 2)
    assert purchase.state is PurchaseState.PARTIALLY_RECEIVED
    assert purchase.lines[0].received_quantity_units == 2
    purchase = purchase.receive(line.id, 3)
    assert purchase.state is PurchaseState.RECEIVED
    assert purchase.lines[0].received_quantity_units == 5
    assert [event.quantity_units for event in purchase.receipts] == [2, 3]


def test_purchase_rejects_receipt_before_ordering():
    purchase, line = make_purchase()
    with pytest.raises(ValidationError):
        purchase.receive(line.id, 1)


def test_purchase_rejects_over_receipt_and_unknown_line():
    purchase, line = make_purchase(2)
    purchase = purchase.submit().approve().mark_ordered()
    with pytest.raises(ValidationError):
        purchase.receive(line.id, 3)
    with pytest.raises(ValidationError):
        purchase.receive(uuid4(), 1)


def test_purchase_cannot_cancel_after_ordering():
    purchase, _ = make_purchase()
    purchase = purchase.submit().approve().mark_ordered()
    with pytest.raises(ValidationError):
        purchase.cancel()


def test_purchase_line_validates_quantity_and_money():
    with pytest.raises(ValidationError):
        PurchaseLine(product_id=uuid4(), quantity_units=0, unit_label="piece")
    with pytest.raises(ValidationError):
        PurchaseLine(
            product_id=uuid4(), quantity_units=1, unit_label="piece",
            unit_cost_minor=10, currency_code="usd",
        )


def test_purchase_requires_supplier_and_lines():
    with pytest.raises(ValidationError):
        Purchase(supplier_ref=" ", activity_id=uuid4(), lines=(PurchaseLine(
            product_id=uuid4(), quantity_units=1, unit_label="piece"
        ),))
    with pytest.raises(ValidationError):
        Purchase(supplier_ref="supplier:1", activity_id=uuid4(), lines=())
