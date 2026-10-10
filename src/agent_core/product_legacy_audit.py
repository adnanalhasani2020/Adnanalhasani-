"""Read-only inventory of legacy Product identity gaps and preserved references."""
from dataclasses import dataclass

@dataclass(frozen=True)
class LegacyProductNameGap:
    product_id: str
    product_state: str
    offering_ids: tuple[str, ...]
    sale_ids: tuple[str, ...]
    @property
    def offering_count(self): return len(self.offering_ids)
    @property
    def sale_count(self): return len(self.sale_ids)

@dataclass(frozen=True)
class ProductLegacyNameAudit:
    gaps: tuple[LegacyProductNameGap, ...]
    @property
    def affected_product_count(self): return len(self.gaps)
    @property
    def referenced_product_count(self):
        return sum(1 for gap in self.gaps if gap.offering_ids or gap.sale_ids)

class ProductLegacyNameAuditor:
    """Find unnamed legacy Products without guessing or changing records."""
    def inspect(self, connection):
        rows = connection.execute(
            "SELECT product_id,state FROM products "
            "WHERE name IS NULL OR trim(name)='' ORDER BY product_id"
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
            gaps.append(LegacyProductNameGap(product_id,state,offering_ids,sale_ids))
        return ProductLegacyNameAudit(tuple(gaps))
