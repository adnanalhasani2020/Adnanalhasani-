"""Read-only commerce data-quality reports for legacy Product records."""

from dataclasses import dataclass


@dataclass(frozen=True)
class MissingProductName:
    """A Product missing its authoritative name, with reference counts only."""

    product_id: str
    state: str
    created_at: str
    offering_count: int
    sale_count: int


def list_products_missing_names(connection, *, limit: int = 100, offset: int = 0) -> tuple[MissingProductName, ...]:
    """Return legacy Products with absent names without modifying any records.

    Product names are not inferred from Offering or Sale text: those records are
    references, not an approved source of truth for repairing a Product name.
    """
    if type(limit) is not int or not 1 <= limit <= 500:
        raise ValueError("limit must be an integer between 1 and 500")
    if type(offset) is not int or offset < 0:
        raise ValueError("offset must be a non-negative integer")

    rows = connection.execute(
        """
        SELECT p.product_id, p.state, p.created_at,
               (SELECT COUNT(*) FROM offerings o
                WHERE o.product_id = p.product_id) AS offering_count,
               (SELECT COUNT(*) FROM sales s
                JOIN offerings so ON so.offering_id = s.offering_id
                WHERE so.product_id = p.product_id) AS sale_count
        FROM products p
        WHERE p.name IS NULL OR trim(p.name) = ''
        ORDER BY p.created_at, p.product_id
        LIMIT ? OFFSET ?
        """,
        (limit, offset),
    ).fetchall()
    return tuple(MissingProductName(*row) for row in rows)
