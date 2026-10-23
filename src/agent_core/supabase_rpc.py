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

    def catalog(self) -> list[dict]:
        """Return currently sellable catalog rows visible to this JWT under RLS."""
        from datetime import datetime, timezone
        from urllib.error import HTTPError
        from urllib.parse import urlencode

        query = {
            "select": "id,activity_id,product_id,price_minor,currency_code,scope_key,location_ref,valid_from,valid_to,product:commercial_products(id,name,unit_name,state),balances:commercial_inventory_balances(quantity_on_hand,quantity_reserved,location_id,organization_id,location:commercial_organization_locations(name,code,state))",
            "state": "eq.active", "order": "created_at.desc", "limit": "500",
        }
        request = Request(
            f"{self.url}/rest/v1/commercial_offerings?{urlencode(query)}",
            method="GET",
            headers={"apikey": self.anon_key, "Authorization": f"Bearer {self.access_token}",
                     "Accept": "application/json", "Cache-Control": "no-store"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise SupabaseRPCError("Supabase catalog response exceeded the size limit.")
                rows = json.loads(raw.decode("utf-8")) if raw else []
        except HTTPError as exc:
            if exc.code in (400, 401, 403):
                raise SupabaseRPCError(f"Supabase rejected catalog access (HTTP {exc.code}).") from None
            raise SupabaseRPCError(f"Supabase catalog request failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise SupabaseRPCError("Supabase catalog connection or response failed.") from None

        if not isinstance(rows, list):
            raise SupabaseRPCError("Unexpected Supabase catalog response.")
        now, items = datetime.now(timezone.utc), []
        for row in rows:
            if not isinstance(row, dict):
                continue
            product, balances = row.get("product"), row.get("balances")
            if isinstance(product, list):
                product = product[0] if product else None
            if not isinstance(product, dict) or product.get("state") != "active":
                continue
            if isinstance(balances, dict):
                balances = [balances]
            if not isinstance(balances, list):
                continue
            try:
                price = int(row["price_minor"])
                starts = datetime.fromisoformat(str(row["valid_from"]).replace("Z", "+00:00"))
                ends_raw = row.get("valid_to")
                ends = datetime.fromisoformat(str(ends_raw).replace("Z", "+00:00")) if ends_raw else None
            except (KeyError, TypeError, ValueError, OverflowError):
                continue
            if starts > now or (ends is not None and ends <= now):
                continue
            for balance in balances:
                if not isinstance(balance, dict):
                    continue
                location = balance.get("location")
                if isinstance(location, list):
                    location = location[0] if location else None
                if not isinstance(location, dict) or location.get("state") != "active":
                    continue
                try:
                    available = int(float(balance.get("quantity_on_hand", 0)) - float(balance.get("quantity_reserved", 0)))
                except (TypeError, ValueError, OverflowError):
                    continue
                if available < 1:
                    continue
                items.append({
                    "offering_id": row.get("id"), "activity_id": row.get("activity_id"),
                    "product_id": row.get("product_id"), "product_name": product.get("name"),
                    "unit_name": product.get("unit_name"), "unit_price_minor": price,
                    "currency_code": row.get("currency_code"), "scope_key": row.get("scope_key"),
                    "location_ref": balance.get("location_id"), "location_name": location.get("name"),
                    "organization_id": balance.get("organization_id"), "available_quantity": available,
                })
        return items

    def create_storefront(self, *, organization_name: str, organization_slug: str,
                          activity_name: str, product_name: str, unit_name: str,
                          price_minor: int, currency_code: str, location_name: str,
                          initial_quantity: int) -> dict:
        if not isinstance(price_minor, int) or isinstance(price_minor, bool) or price_minor < 0:
            raise ValueError("price_minor must be a non-negative integer")
        if not isinstance(initial_quantity, int) or isinstance(initial_quantity, bool) or not 1 <= initial_quantity <= 100000000:
            raise ValueError("initial_quantity must be an integer between 1 and 100000000")
        result = self.rpc("commercial_create_storefront", {
            "p_organization_name": organization_name,
            "p_organization_slug": organization_slug,
            "p_activity_name": activity_name,
            "p_product_name": product_name,
            "p_unit_name": unit_name,
            "p_price_minor": price_minor,
            "p_currency_code": currency_code,
            "p_location_name": location_name,
            "p_initial_quantity": initial_quantity,
        })
        if not isinstance(result, dict) or not isinstance(result.get("organization_id"), str) or not isinstance(result.get("offering_id"), str):
            raise SupabaseRPCError("Unexpected storefront setup response; verify the setup before retrying.")
        return result

    def checkout(self, *, offering_id: str, location_id: str, quantity: int,
                 idempotency_key: str) -> dict:
        if not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= 100000:
            raise ValueError("quantity must be an integer between 1 and 100000")
        result = self.rpc("commercial_checkout_sale", {
            "p_offering_id": offering_id,
            "p_location_id": location_id,
            "p_quantity": quantity,
            "p_idempotency_key": idempotency_key,
        })
        if not isinstance(result, dict) or not isinstance(result.get("sale_id"), str):
            raise SupabaseRPCError("Unexpected checkout response; verify the sale before retrying.")
        return result

    def receipts(self, *, sale_id: str | None = None) -> list[dict]:
        from urllib.parse import urlencode
        query = {
            "select": "id,receipt_number,activity_id,organization_id,currency_code,total_minor,status,payment_status,created_at,items:commercial_sale_items(quantity,unit_price_minor,product_name_snapshot,line_total_minor,offering_id)",
            "order": "created_at.desc",
            "limit": "20",
        }
        if sale_id is not None:
            query["id"] = "eq." + sale_id
        request = Request(
            f"{self.url}/rest/v1/commercial_sales?{urlencode(query)}",
            method="GET",
            headers={"apikey": self.anon_key, "Authorization": f"Bearer {self.access_token}",
                     "Accept": "application/json", "Cache-Control": "no-store"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise SupabaseRPCError("Supabase receipts response exceeded the size limit.")
                rows = json.loads(raw.decode("utf-8")) if raw else []
        except HTTPError as exc:
            if exc.code in (400, 401, 403):
                raise SupabaseRPCError(f"Supabase rejected receipt access (HTTP {exc.code}).") from None
            raise SupabaseRPCError(f"Supabase receipts request failed (HTTP {exc.code}).") from None
        except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise SupabaseRPCError("Supabase receipts connection or response failed.") from None
        if not isinstance(rows, list):
            raise SupabaseRPCError("Unexpected Supabase receipts response.")
        results = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            items = row.get("items") or []
            item = items[0] if isinstance(items, list) and items and isinstance(items[0], dict) else {}
            results.append({
                "sale_id": row.get("id"),
                "receipt_number": row.get("receipt_number"),
                "activity_id": row.get("activity_id"),
                "organization_id": row.get("organization_id"),
                "state": row.get("status"),
                "payment_status": row.get("payment_status"),
                "quantity": item.get("quantity"),
                "product_name": item.get("product_name_snapshot"),
                "product_id": None,
                "offering_id": item.get("offering_id"),
                "unit_price_minor": item.get("unit_price_minor"),
                "total_minor": row.get("total_minor"),
                "currency_code": row.get("currency_code"),
                "occurred_at": row.get("created_at"),
            })
        return results

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
