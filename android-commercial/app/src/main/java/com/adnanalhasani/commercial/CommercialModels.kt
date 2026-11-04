package com.adnanalhasani.commercial

import java.math.BigDecimal

internal data class Product(val name: String, val category: String, val stock: Int, val price: String, val costPrice: Int = 0, val catalogCode: String = "", val barcode: String = "", val unitCode: String = "EA", val imageUrl: String = "", val description: String = "", val brand: String = "", val manufacturer: String = "", val countryOfManufacture: String = "", val createdAt: Long = System.currentTimeMillis(), val createdBy: String = "", val unitVariantsJson: String = "[]", val cloudOfferingId: String = "", val cloudPriceMinor: Long = 0L, val reorderPoint: Int = -1, val stockPrecise: BigDecimal = BigDecimal.valueOf(stock.toLong()))

internal data class SaleRecord(val number: Int, val product: String, val quantity: Int, val total: Int, val credit: Boolean, val customer: String = "", val paid: Int = 0, val createdAt: Long = System.currentTimeMillis(), val unitCost: Int = 0, val unitCode: String = "EA", val baseQuantitySold: Int = quantity, val currencyCode: String = "YER", val status: String = "completed")
