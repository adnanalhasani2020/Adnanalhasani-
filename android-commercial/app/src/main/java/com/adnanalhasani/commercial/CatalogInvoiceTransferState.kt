package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State for invoice-to-purchase review and catalog item matching. */
internal class CatalogInvoiceTransferState {
    var invoiceRequestKey by mutableStateOf(java.util.UUID.randomUUID().toString())
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
