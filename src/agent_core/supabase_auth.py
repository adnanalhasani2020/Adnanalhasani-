"""Minimal server-side Supabase Auth REST client.

Uses only the project's public anon/publishable key. Never log passwords or tokens.
The caller is responsible for storing session tokens in secure HttpOnly cookies and
for verifying the access token with get_user before authorizing requests.
"""
from __future__ import annotations

import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class SupabaseAuthError(RuntimeError):
    """Safe-to-display Supabase Auth failure without credential contents."""


class SupabaseAuthClient:
    def __init__(self, *, timeout: float = 10.0):
        self.url = os.environ.get("SUPABASE_URL", "").rstrip("/")
        self.anon_key = os.environ.get("SUPABASE_ANON_KEY", "")
        if not self.url.startswith("https://") or not self.anon_key:
            raise SupabaseAuthError("Set HTTPS SUPABASE_URL and SUPABASE_ANON_KEY.")
        if not 0 < timeout <= 30:
            raise ValueError("timeout must be in (0, 30].")
        self.timeout = timeout

    def _request(self, path: str, payload: dict | None = None, *, access_token: str | None = None):
        headers = {
            "apikey": self.anon_key,
            "Content-Type": "application/json",
            "Cache-Control": "no-store",
        }
        if access_token:
            headers["Authorization"] = "Bearer " + access_token
        request = Request(
            self.url + path,
            data=json.dumps(payload).encode("utf-8") if payload is not None else None,
            headers=headers,
            method="POST" if payload is not None else "GET",
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read(1_000_001)
                if len(raw) > 1_000_000:
                    raise SupabaseAuthError("Supabase Auth response exceeded the size limit.")
                return json.loads(raw.decode("utf-8")) if raw else {}
        except HTTPError as exc:
            # Do not forward provider response bodies: they may contain sensitive details.
            if exc.code in (400, 401, 403, 409, 422):
                raise SupabaseAuthError("Supabase Auth rejected the request.") from None
            raise SupabaseAuthError(f"Supabase Auth request failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise SupabaseAuthError("Could not verify the Supabase Auth response.") from None

    def sign_up(self, email: str, password: str) -> dict:
        if not isinstance(email, str) or not email.strip() or len(email) > 320:
            raise SupabaseAuthError("Enter a valid email address.")
        if not isinstance(password, str) or not 12 <= len(password) <= 1024:
            raise SupabaseAuthError("Password must be between 12 and 1024 characters.")
        return self._request("/auth/v1/signup", {"email": email.strip().lower(), "password": password})

    @staticmethod
    def _validate_session(result: dict, *, refreshed: bool = False) -> dict:
        """Reject incomplete provider payloads before callers persist session cookies."""
        if not isinstance(result, dict):
            raise SupabaseAuthError("Supabase did not return a valid session.")
        required_tokens = ("access_token", "refresh_token")
        if any(not isinstance(result.get(key), str) or not result[key]
               for key in required_tokens):
            raise SupabaseAuthError("Supabase did not return a valid session.")
        if not refreshed:
            user = result.get("user")
            if (
                not isinstance(user, dict)
                or not isinstance(user.get("id"), str)
                or not user["id"]
                or not isinstance(user.get("email"), str)
                or not user["email"]
            ):
                raise SupabaseAuthError("Supabase did not return a valid signed-in user.")
        token_type = result.get("token_type")
        if token_type is not None and (
            not isinstance(token_type, str) or token_type.lower() != "bearer"
        ):
            raise SupabaseAuthError("Supabase returned an unsupported session token type.")
        return result

    def sign_in(self, email: str, password: str) -> dict:
        if not isinstance(email, str) or not email.strip() or len(email) > 320:
            raise SupabaseAuthError("Enter a valid email address.")
        if not isinstance(password, str) or not 12 <= len(password) <= 1024:
            raise SupabaseAuthError("Password must be between 12 and 1024 characters.")
        result = self._request(
            "/auth/v1/token?grant_type=password",
            {"email": email.strip().lower(), "password": password},
        )
        return self._validate_session(result)

    def refresh_session(self, refresh_token: str) -> dict:
        """Exchange a Supabase refresh token for a new session token pair."""
        if not isinstance(refresh_token, str) or not refresh_token or len(refresh_token) > 16_384:
            raise SupabaseAuthError("A valid Supabase refresh token is required.")
        result = self._request(
            "/auth/v1/token?grant_type=refresh_token",
            {"refresh_token": refresh_token},
        )
        return self._validate_session(result, refreshed=True)

    def get_user(self, access_token: str) -> dict:
        if not isinstance(access_token, str) or not access_token or len(access_token) > 16_384:
            raise SupabaseAuthError("A valid Supabase session is required.")
        user = self._request("/auth/v1/user", access_token=access_token)
        if not isinstance(user, dict) or not isinstance(user.get("id"), str) or not user.get("email"):
            raise SupabaseAuthError("Supabase session is invalid.")
        return user

    def sign_out(self, access_token: str) -> None:
        if not isinstance(access_token, str) or not access_token or len(access_token) > 16_384:
            return
        # Supabase revokes the current session; token is sent only in the Authorization header.
        request = Request(
            self.url + "/auth/v1/logout",
            data=b"",
            headers={"apikey": self.anon_key, "Authorization": "Bearer " + access_token},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.timeout):
                return
        except HTTPError as exc:
            if exc.code in (401, 403):
                return
            raise SupabaseAuthError(f"Supabase sign-out failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError):
            raise SupabaseAuthError("Could not confirm Supabase sign-out.") from None
