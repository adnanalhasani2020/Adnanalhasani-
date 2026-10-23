import json
from urllib.error import HTTPError
from urllib.request import Request

import pytest

from agent_core.supabase_auth import SupabaseAuthClient, SupabaseAuthError


def client(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "https://project.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "public-test-key")
    return SupabaseAuthClient()


def test_client_requires_https_and_public_key(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "http://project.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "public-test-key")
    with pytest.raises(SupabaseAuthError):
        SupabaseAuthClient()


def test_sign_in_uses_password_grant_and_never_puts_credentials_in_url(monkeypatch):
    auth = client(monkeypatch)
    seen = {}

    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def read(self, size): return b'{"access_token":"test-token","user":{"id":"user-1","email":"a@example.com"}}'

    def fake_urlopen(request, timeout):
        seen["url"] = request.full_url
        seen["headers"] = request.headers
        seen["body"] = json.loads(request.data.decode())
        seen["timeout"] = timeout
        return Response()

    monkeypatch.setattr("agent_core.supabase_auth.urlopen", fake_urlopen)
    result = auth.sign_in(" A@Example.com ", "a long password")
    assert seen["url"].endswith("/auth/v1/token?grant_type=password")
    assert "a long password" not in seen["url"]
    assert "a@example.com" not in seen["url"]
    assert seen["body"] == {"email": "a@example.com", "password": "a long password"}
    assert seen["headers"]["Apikey"] == "public-test-key"
    assert result["access_token"] == "test-token"


def test_get_user_verifies_access_token_with_auth_server(monkeypatch):
    auth = client(monkeypatch)
    seen = {}

    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def read(self, size): return b'{"id":"user-1","email":"a@example.com"}'

    def fake_urlopen(request, timeout):
        seen["url"] = request.full_url
        seen["authorization"] = request.get_header("Authorization")
        return Response()

    monkeypatch.setattr("agent_core.supabase_auth.urlopen", fake_urlopen)
    user = auth.get_user("signed-user-token")
    assert seen["url"].endswith("/auth/v1/user")
    assert seen["authorization"] == "Bearer signed-user-token"
    assert user["id"] == "user-1"


def test_provider_errors_do_not_leak_response_body(monkeypatch):
    auth = client(monkeypatch)

    def fake_urlopen(request, timeout):
        raise HTTPError(request.full_url, 400, "bad request", hdrs=None, fp=None)

    monkeypatch.setattr("agent_core.supabase_auth.urlopen", fake_urlopen)
    with pytest.raises(SupabaseAuthError) as exc:
        auth.sign_in("a@example.com", "a long password")
    assert "password" not in str(exc.value).lower()
    assert "token" not in str(exc.value).lower()
