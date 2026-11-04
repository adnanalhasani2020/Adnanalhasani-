package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf

/** State for invoice-to-purchase review and catalog item matching. */
internal class CatalogInvoiceTransferState {
    var invoiceRequestKey by mutableStateOf(java.util.UUID.randomUUID().toString())
    var invoiceRequests by mutableStateOf<List<InvoicePurchaseRequestRow>>(emptyList())
    var mappingLines by mutableStateOf<List<InvoicePurchaseUnmappedLine>>(emptyList())
    var mappingOfferings by mutableStateOf<List<InvoicePurchaseOffering>>(emptyList())
    var mappingWarehouses by mutableStateOf<List<CatalogWarehouseOption>>(emptyList())
    var mappingPurchaseOrderId by mutableStateOf("")
    var mappingLocationId by mutableStateOf("")
    var showItemCreation by mutableStateOf(false)
    var newNameAr by mutableStateOf("")
    var newNameEn by mutableStateOf("")
    var newCategoryCode by mutableStateOf("")
    var newBaseUnitCode by mutableStateOf("")
    var newSaleUnitCode by mutableStateOf("")
    var newCategoryMenuExpanded by mutableStateOf(false)
    var newBaseUnitMenuExpanded by mutableStateOf(false)
    var newSaleUnitMenuExpanded by mutableStateOf(false)
    var newIdempotencyKey by mutableStateOf("")
    var invoiceSaleId by mutableStateOf("")
    var invoiceDestinationOrg by mutableStateOf("")
    var invoiceReceiveLocationMenuExpanded by mutableStateOf(false)
    var invoiceReceiveLocationId by mutableStateOf("")
    var invoiceMappingRequestId by mutableStateOf("")
    var invoiceMappingLineId by mutableStateOf("")
    var invoiceMappingLineMenuExpanded by mutableStateOf(false)
    var invoiceMappingOfferingMenuExpanded by mutableStateOf(false)
    var invoiceMappingOfferingId by mutableStateOf("")
    var invoiceMappingWarehouseMenuExpanded by mutableStateOf(false)
    var invoiceMappingWarehouseId by mutableStateOf("")
}
