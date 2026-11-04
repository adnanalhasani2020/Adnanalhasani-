package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSupplierMovementValidationTest {
    @Test
    fun rejectsOpeningMovementOverflow() {
        assertEquals(
            "تجاوزت حركات أو أرصدة كشف الموردين نطاق الأرقام الدقيق؛ تم حجب كشف الحركات.",
            validate(
                movements = listOf(
                    movement(date = "2026-01-01", amount = Long.MAX_VALUE),
                    movement(date = "2026-01-01", amount = 1L)
                )
            )
        )
    }

    @Test
    fun rejectsPeriodRunningBalanceOverflow() {
        assertEquals(
            "تجاوزت حركات أو أرصدة كشف الموردين نطاق الأرقام الدقيق؛ تم حجب كشف الحركات.",
            validate(
                movements = listOf(
                    movement(date = "2026-01-10", amount = Long.MAX_VALUE),
                    movement(date = "2026-01-11", amount = 1L)
                )
            )
        )
    }

    @Test
    fun rejectsUnnegatablePaymentMovement() {
        assertEquals(
            "توجد دفعة مورد تتجاوز نطاق الأرقام الدقيق عند عكس اتجاهها؛ تم حجب كشف الحركات.",
            validate(movements = listOf(movement(amount = Long.MIN_VALUE)))
        )
    }

    @Test
    fun preservesSourceErrorPrecedence() {
        assertEquals(
            "purchases unavailable",
            validate(
                purchasesLoadError = "purchases unavailable",
                supplierPaymentsLoadError = "payments unavailable"
            )
        )
        assertEquals(
            "payments unavailable",
            validate(supplierPaymentsLoadError = "payments unavailable")
        )
    }

    private fun validate(
        purchasesLoadError: String = "",
        supplierPaymentsLoadError: String = "",
        movements: List<ReportSupplierMovement> = listOf(movement(amount = 1L))
    ) = reportSupplierMovementValidationError(
        purchasesLoadError = purchasesLoadError,
        supplierPaymentsLoadError = supplierPaymentsLoadError,
        movements = movements,
        startMillis = java.time.LocalDate.parse("2026-01-02").atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli(),
        endExclusive = java.time.LocalDate.parse("2026-02-01").atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
    )

    private fun movement(
        date: String = "2026-01-10",
        amount: Long
    ) = ReportSupplierMovement(
        date = date,
        supplierId = "supplier-1",
        supplier = "Supplier",
        currency = "YER",
        reference = "movement",
        amount = amount,
        description = ""
    )
}
