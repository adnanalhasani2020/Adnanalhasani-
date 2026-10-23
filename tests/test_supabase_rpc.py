from __future__ import annotations

import json
from io import BytesIO
from unittest.mock import patch

import pytest

from agent_core.supabase_rpc import SupabaseRPCClient, SupabaseRPCError


def client(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "public-test-key")
    return SupabaseRPCClient("signed-in-user-token")


def test_client_requires_https_and_public_key(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "http://example.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "public-test-key")
    with pytest.raises(SupabaseRPCError):
        SupabaseRPCClient("signed-in-user-token")


def test_client_requires_user_access_token(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "public-test-key")
    with pytest.raises(SupabaseRPCError):
        SupabaseRPCClient("")


def test_rpc_sends_user_jwt_and_parses_json(monkeypatch):
    rpc = client(monkeypatch)
    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def read(self, _limit):
            return b'"org-uuid"'
    with patch("agent_core.supabase_rpc.urlopen", return_value=Response()) as open_url:
        result = rpc.rpc("example_rpc", {"p_value": "hello"})
    request = open_url.call_args.args[0]
    assert request.full_url == "https://example.supabase.co/rest/v1/rpc/example_rpc"
    assert request.get_header("Authorization") == "Bearer signed-in-user-token"
    assert request.get_header("Apikey") == "public-test-key"
    assert json.loads(request.data.decode()) == {"p_value": "hello"}
    assert result == "org-uuid"


def test_create_organization_calls_atomic_rpc(monkeypatch):
    rpc = client(monkeypatch)
    with patch.object(rpc, "rpc", return_value="00000000-0000-0000-0000-000000000001") as call:
        result = rpc.create_organization(
            name="Demo Org", slug="demo-org", organization_type="business",
            currency_code="YER", timezone="Asia/Aden", locale="ar",
            country_code="YE", module_keys=["core_operations"],
        )
    assert result == "00000000-0000-0000-0000-000000000001"
    assert call.call_args.args[0] == "commercial_create_organization"
    assert call.call_args.args[1]["p_module_keys"] == ["core_operations"]
    assert call.call_args.args[1]["p_locale"] == "ar"


def test_create_organization_rejects_unexpected_response(monkeypatch):
    rpc = client(monkeypatch)
    with patch.object(rpc, "rpc", return_value={"id": "org-uuid"}):
        with pytest.raises(SupabaseRPCError):
            rpc.create_organization(
                name="Demo Org", slug="demo-org", organization_type="business",
                currency_code="YER", timezone="Asia/Aden", locale="ar",
            )


def test_catalog_uses_user_jwt_and_filters_inactive_or_unavailable_rows(monkeypatch):
    rpc = client(monkeypatch)
    payload = [{
        "id": "offer-1", "activity_id": "activity-1", "product_id": "product-1",
        "price_minor": 1250, "currency_code": "YER", "scope_key": "retail",
        "location_ref": "legacy-location", "valid_from": "2020-01-01T00:00:00Z", "valid_to": None,
        "product": {"id": "product-1", "name": "Tea", "unit_name": "box", "state": "active"},
        "balances": [{"quantity_on_hand": 6, "quantity_reserved": 2, "location_id": "loc-1",
                      "organization_id": "org-1",
                      "location": {"name": "Main", "code": "MAIN", "state": "active"}}],
    }, {
        "id": "offer-2", "activity_id": "activity-1", "product_id": "product-2",
        "price_minor": 100, "currency_code": "YER", "scope_key": "retail",
        "location_ref": "legacy-location", "valid_from": "2020-01-01T00:00:00Z", "valid_to": None,
        "product": {"id": "product-2", "name": "Inactive", "unit_name": "piece", "state": "inactive"},
        "balances": [{"quantity_on_hand": 9, "quantity_reserved": 0, "location_id": "loc-2",
                      "organization_id": "org-1",
                      "location": {"name": "Main", "code": "MAIN", "state": "active"}}],
    }, {
        "id": "offer-3", "activity_id": "activity-1", "product_id": "product-3",
        "price_minor": 200, "currency_code": "YER", "scope_key": "retail",
        "location_ref": "legacy-location", "valid_from": "2020-01-01T00:00:00Z", "valid_to": None,
        "product": {"id": "product-3", "name": "Reserved", "unit_name": "piece", "state": "active"},
        "balances": [{"quantity_on_hand": 4, "quantity_reserved": 4, "location_id": "loc-3",
                      "organization_id": "org-1",
                      "location": {"name": "Main", "code": "MAIN", "state": "active"}}],
    }]
    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def read(self, _limit):
            return json.dumps(payload).encode()
    with patch("agent_core.supabase_rpc.urlopen", return_value=Response()) as open_url:
        result = rpc.catalog()
    request = open_url.call_args.args[0]
    assert request.full_url.startswith("https://example.supabase.co/rest/v1/commercial_offerings?")
    assert "commercial_inventory_balances" in request.full_url
    assert request.get_header("Authorization") == "Bearer signed-in-user-token"
    assert request.get_header("Apikey") == "public-test-key"
    assert len(result) == 1
    assert result[0]["offering_id"] == "offer-1"
    assert result[0]["available_quantity"] == 4
    assert result[0]["location_ref"] == "loc-1"
    assert result[0]["organization_id"] == "org-1"


def test_checkout_calls_atomic_rpc_and_validates_response(monkeypatch):
    rpc = client(monkeypatch)
    expected = {"sale_id": "sale-uuid", "total_minor": 2500}
    with patch.object(rpc, "rpc", return_value=expected) as call:
        result = rpc.checkout(
            offering_id="offer-uuid", location_id="location-uuid",
            quantity=2, idempotency_key="checkout-key-123",
        )
    assert result == expected
    assert call.call_args.args[0] == "commercial_checkout_sale"
    assert call.call_args.args[1] == {
        "p_offering_id": "offer-uuid", "p_location_id": "location-uuid",
        "p_quantity": 2, "p_idempotency_key": "checkout-key-123",
    }


def test_checkout_rejects_invalid_quantity(monkeypatch):
    rpc = client(monkeypatch)
    with pytest.raises(ValueError):
        rpc.checkout(
            offering_id="offer-uuid", location_id="location-uuid",
            quantity=True, idempotency_key="checkout-key-123",
        )


def test_receipts_uses_user_jwt_and_normalizes_sale_items(monkeypatch):
    rpc = client(monkeypatch)
    payload = [{
        "id": "sale-uuid", "receipt_number": 17, "activity_id": "activity-uuid",
        "organization_id": "org-uuid", "currency_code": "YER", "total_minor": 2500,
        "status": "completed", "payment_status": "paid",
        "created_at": "2026-10-23T10:00:00Z",
        "items": [{"quantity": 2, "unit_price_minor": 1250,
                   "product_name_snapshot": "Tea", "line_total_minor": 2500,
                   "offering_id": "offer-uuid"}],
    }]
    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def read(self, _limit):
            return json.dumps(payload).encode()
    with patch("agent_core.supabase_rpc.urlopen", return_value=Response()) as open_url:
        result = rpc.receipts()
    request = open_url.call_args.args[0]
    assert request.full_url.startswith("https://example.supabase.co/rest/v1/commercial_sales?")
    assert request.get_header("Authorization") == "Bearer signed-in-user-token"
    assert result[0]["sale_id"] == "sale-uuid"
    assert result[0]["product_name"] == "Tea"
    assert result[0]["total_minor"] == 2500
