package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State for invoice-to-purchase review and catalog item matching. */
internal class CatalogInvoiceTransferState {
    var invoiceSaleId by mutableStateOf("")
    var invoiceDestinationOrg by mutableStateOf("")
    var invoiceReceiveLocationMenuExpanded by mutableStateOf(false)
    var invoiceReceiveLocationId by mutableStateOf("")
    var invoiceRequests by mutableStateOf<List<InvoicePurchaseRequestRow>>(emptyList())
    var invoiceMappingRequestId by mutableStateOf("")
    var invoiceMappingLines by mutableStateOf<List<InvoicePurchaseUnmappedLine>>(emptyList())
    var invoiceMappingLineId by mutableStateOf("")
    var invoiceMappingLineMenuExpanded by mutableStateOf(false)
    var invoiceMappingOfferings by mutableStateOf<List<InvoicePurchaseOffering>>(emptyList())
    var invoiceMappingOfferingMenuExpanded by mutableStateOf(false)
    var invoiceMappingOfferingId by mutableStateOf("")
    var invoiceMappingWarehouses by mutableStateOf<List<CatalogWarehouseOption>>(emptyList())
    var invoiceMappingWarehouseMenuExpanded by mutableStateOf(false)
    var invoiceMappingWarehouseId by mutableStateOf("")
}
