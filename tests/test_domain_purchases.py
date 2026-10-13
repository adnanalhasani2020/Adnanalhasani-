from uuid import uuid4

import pytest

from agent_core.domain_purchases import Purchase, PurchaseLine, PurchaseState, ReceiptEvent
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


def test_purchase_partial_receipt_state_requires_some_but_not_all_quantities():
    purchase, line = make_purchase(quantity=5)
    with pytest.raises(ValidationError):
        Purchase(
            supplier_ref=purchase.supplier_ref,
            activity_id=purchase.activity_id,
            lines=(line,),
            state=PurchaseState.PARTIALLY_RECEIVED,
            history=(PurchaseState.DRAFT, PurchaseState.SUBMITTED, PurchaseState.APPROVED, PurchaseState.ORDERED, PurchaseState.PARTIALLY_RECEIVED),
        )

    fully_received_line = PurchaseLine(
        product_id=line.product_id, quantity_units=5, unit_label="box",
        received_quantity_units=5,
    )
    with pytest.raises(ValidationError):
        Purchase(
            supplier_ref=purchase.supplier_ref,
            activity_id=purchase.activity_id,
            lines=(fully_received_line,),
            state=PurchaseState.PARTIALLY_RECEIVED,
            history=(PurchaseState.DRAFT, PurchaseState.SUBMITTED, PurchaseState.APPROVED, PurchaseState.ORDERED, PurchaseState.PARTIALLY_RECEIVED),
            receipts=(ReceiptEvent(line_id=fully_received_line.id, quantity_units=5),),
        )


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



def test_purchase_history_rejects_duplicate_non_receiving_states():
    purchase, line = make_purchase()
    with pytest.raises(ValidationError):
        Purchase(
            supplier_ref=purchase.supplier_ref,
            activity_id=purchase.activity_id,
            lines=(line,),
            state=PurchaseState.DRAFT,
            history=(PurchaseState.DRAFT, PurchaseState.DRAFT),
        )


def test_purchase_history_requires_one_receipt_event_per_receiving_version():
    purchase, line = make_purchase()
    received_line = PurchaseLine(
        product_id=line.product_id, quantity_units=5, unit_label="box",
        received_quantity_units=1,
    )
    with pytest.raises(ValidationError):
        Purchase(
            supplier_ref=purchase.supplier_ref,
            activity_id=purchase.activity_id,
            lines=(received_line,),
            state=PurchaseState.PARTIALLY_RECEIVED,
            history=(
                PurchaseState.DRAFT,
                PurchaseState.SUBMITTED,
                PurchaseState.APPROVED,
                PurchaseState.ORDERED,
                PurchaseState.PARTIALLY_RECEIVED,
                PurchaseState.PARTIALLY_RECEIVED,
            ),
            receipts=(ReceiptEvent(line_id=received_line.id, quantity_units=1),),
            # The persisted history claims two receipt versions, but only one receipt exists.
        )



def test_purchase_rejects_duplicate_receipt_event_identifiers():
    product_id = uuid4()
    line_id = uuid4()
    receipt_id = uuid4()
    line = PurchaseLine(
        product_id=product_id, quantity_units=2, unit_label="piece",
        id=line_id, received_quantity_units=2,
    )
    receipt = ReceiptEvent(line_id=line_id, quantity_units=1, id=receipt_id)
    duplicate = ReceiptEvent(line_id=line_id, quantity_units=1, id=receipt_id)

    with pytest.raises(ValidationError, match="receipt event identifiers must be unique"):
        Purchase(
            supplier_ref="supplier:duplicate-receipt",
            activity_id=uuid4(),
            lines=(line,),
            state=PurchaseState.RECEIVED,
            history=(
                PurchaseState.DRAFT,
                PurchaseState.SUBMITTED,
                PurchaseState.APPROVED,
                PurchaseState.ORDERED,
                PurchaseState.PARTIALLY_RECEIVED,
                PurchaseState.RECEIVED,
            ),
            receipts=(receipt, duplicate),
        )
