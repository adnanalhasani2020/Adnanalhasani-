"""Read-only inventory of legacy Product identity gaps and preserved references."""
from dataclasses import dataclass


@dataclass(frozen=True)
class LegacyProductNameGap:
    product_id: str
    product_state: str
    offering_ids: tuple[str, ...]
    sale_ids: tuple[str, ...]

    @property
    def offering_count(self):
        return len(self.offering_ids)

    @property
    def sale_count(self):
        return len(self.sale_ids)


@dataclass(frozen=True)
class ProductLegacyNameAudit:
    gaps: tuple[LegacyProductNameGap, ...]
    total_affected_product_count: int | None = None
    total_referenced_product_count: int | None = None

    @property
    def affected_product_count(self):
        return self.total_affected_product_count if self.total_affected_product_count is not None else len(self.gaps)

    @property
    def referenced_product_count(self):
        if self.total_referenced_product_count is not None:
            return self.total_referenced_product_count
        return sum(1 for gap in self.gaps if gap.offering_ids or gap.sale_ids)


class ProductLegacyNameAuditor:
    """Find unnamed legacy Products without guessing or changing records."""

    def inspect(self, connection, *, limit: int | None = None, offset: int = 0):
        """Audit missing names, optionally returning one stable page.

        Aggregate counts describe the full matching population, even when
        `gaps` contains only the requested page. Names are never inferred
        from Offering or Sale text and no records are modified.
        """
        if limit is not None and (type(limit) is not int or not 1 <= limit <= 500):
            raise ValueError("limit must be an integer between 1 and 500, or None")
        if type(offset) is not int or offset < 0:
            raise ValueError("offset must be a non-negative integer")

        where = "WHERE (p.name IS NULL OR trim(p.name)='')"
        total = connection.execute(
            f"SELECT COUNT(*) FROM products p {where}"
        ).fetchone()[0]
        referenced = connection.execute(
            f"""
            SELECT COUNT(*) FROM products p
            {where}
              AND (
                EXISTS (SELECT 1 FROM offerings o WHERE o.product_id=p.product_id)
                OR EXISTS (
                    SELECT 1 FROM sales s
                    JOIN offerings o ON o.offering_id=s.offering_id
                    WHERE o.product_id=p.product_id
                )
              )
            """
        ).fetchone()[0]
        page_limit = -1 if limit is None else limit
        rows = connection.execute(
            f"""
            SELECT p.product_id,p.state FROM products p
            {where}
            ORDER BY p.product_id
            LIMIT ? OFFSET ?
            """,
            (page_limit, offset),
        ).fetchall()
        gaps = []
        for product_id, state in rows:
            offering_ids = tuple(row[0] for row in connection.execute(
                "SELECT offering_id FROM offerings WHERE product_id=? ORDER BY offering_id",
                (product_id,),
            ).fetchall())
            sale_ids = tuple(row[0] for row in connection.execute(
                "SELECT s.sale_id FROM sales s JOIN offerings o ON o.offering_id=s.offering_id "
                "WHERE o.product_id=? ORDER BY s.sale_id", (product_id,)
            ).fetchall())
            gaps.append(LegacyProductNameGap(product_id, state, offering_ids, sale_ids))
        return ProductLegacyNameAudit(tuple(gaps), total, referenced)
