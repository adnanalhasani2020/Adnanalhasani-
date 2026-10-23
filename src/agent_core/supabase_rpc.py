from __future__ import annotations

"""Authenticated, opt-in Supabase RPC transport. Does not replace SQLite."""
import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class SupabaseRPCError(RuntimeError):
    pass


class SupabaseRPCClient:
    def __init__(self, access_token: str, *, timeout: float = 10.0):
        self.url = os.environ.get("SUPABASE_URL", "").rstrip("/")
        self.anon_key = os.environ.get("SUPABASE_ANON_KEY", "")
        if not self.url.startswith("https://") or not self.anon_key:
            raise SupabaseRPCError("Set HTTPS SUPABASE_URL and SUPABASE_ANON_KEY.")
        if not access_token:
            raise SupabaseRPCError("A signed-in user's access token is required.")
        if not 0 < timeout <= 60:
            raise ValueError("timeout must be in (0, 60].")
        self.access_token = access_token
        self.timeout = timeout

    def rpc(self, function: str, payload: dict):
        if not function.replace("_", "").isalnum() or not isinstance(payload, dict):
            raise ValueError("Invalid RPC request.")
        request = Request(
            f"{self.url}/rest/v1/rpc/{function}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="POST",
            headers={"apikey": self.anon_key, "Authorization": f"Bearer {self.access_token}",
                     "Content-Type": "application/json", "Cache-Control": "no-store"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise SupabaseRPCError("Supabase response exceeded the size limit.")
                return json.loads(raw.decode("utf-8")) if raw else None
        except HTTPError as exc:
            if exc.code in (400, 401, 403, 409):
                raise SupabaseRPCError(f"Supabase rejected the request (HTTP {exc.code}).") from None
            raise SupabaseRPCError(f"Supabase RPC failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise SupabaseRPCError("Supabase connection or response failed; verify operation before retrying.") from None

    def create_organization(self, *, name: str, slug: str, organization_type: str,
                            currency_code: str, timezone: str, locale: str,
                            country_code: str | None = None,
                            module_keys: list[str] | None = None) -> str:
        result = self.rpc("commercial_create_organization", {
            "p_name": name, "p_slug": slug, "p_organization_type": organization_type,
            "p_currency_code": currency_code, "p_timezone": timezone, "p_locale": locale,
            "p_country_code": country_code, "p_module_keys": module_keys,
        })
        if not isinstance(result, str) or not result:
            raise SupabaseRPCError("Unexpected organization-creation response; verify before retrying.")
        return result

class SupabaseAuthError(SupabaseRPCError):
    """Supabase Auth rejected user-provided credentials or signup details."""


class SupabaseAuthClient:
    """Email/password authentication against Supabase Auth; no service-role secret is used."""

    def __init__(self, *, timeout: float = 10.0):
        self.url = os.environ.get("SUPABASE_URL", "").rstrip("/")
        self.anon_key = os.environ.get("SUPABASE_ANON_KEY", "")
        if not self.url.startswith("https://") or not self.anon_key:
            raise SupabaseRPCError("Set HTTPS SUPABASE_URL and SUPABASE_ANON_KEY.")
        if not 0 < timeout <= 60:
            raise ValueError("timeout must be in (0, 60].")
        self.timeout = timeout

    def _auth_request(self, path: str, payload: dict) -> dict:
        request = Request(
            f"{self.url}/auth/v1/{path}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={"apikey": self.anon_key, "Content-Type": "application/json",
                     "Cache-Control": "no-store"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read(1_000_001)
                if len(raw) > 1_000_000:
                    raise SupabaseRPCError("Supabase Auth response exceeded the size limit.")
                result = json.loads(raw.decode("utf-8")) if raw else {}
                if not isinstance(result, dict):
                    raise SupabaseRPCError("Unexpected Supabase Auth response.")
                return result
        except HTTPError as exc:
            if exc.code in (400, 401, 403, 409, 422):
                raise SupabaseAuthError(
                    "تعذر التحقق من الحساب عبر Supabase؛ راجع البريد وكلمة المرور أو حالة الحساب."
                ) from None
            raise SupabaseRPCError(f"Supabase Auth failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise SupabaseRPCError("تعذر الاتصال بخدمة تسجيل الدخول الخارجية. حاول مرة أخرى.") from None

    @staticmethod
    def _validate_credentials(email: str, password: str) -> tuple[str, str]:
        if not isinstance(email, str) or not email.strip() or len(email) > 320 or "@" not in email:
            raise SupabaseAuthError("أدخل بريدًا إلكترونيًا صالحًا.")
        if not isinstance(password, str) or not 12 <= len(password) <= 1024:
            raise SupabaseAuthError("يجب أن تتكون كلمة المرور من 12 إلى 1024 حرفًا.")
        return email.strip().lower(), password

    def sign_up(self, *, email: str, password: str) -> dict:
        email, password = self._validate_credentials(email, password)
        return self._auth_request("signup", {"email": email, "password": password})

    def sign_in_with_password(self, *, email: str, password: str) -> dict:
        email, password = self._validate_credentials(email, password)
        return self._auth_request("token?grant_type=password",
                                  {"email": email, "password": password})
