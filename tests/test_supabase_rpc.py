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
