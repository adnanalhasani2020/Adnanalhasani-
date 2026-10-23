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
