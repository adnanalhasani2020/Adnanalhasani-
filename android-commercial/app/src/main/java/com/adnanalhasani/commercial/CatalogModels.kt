package com.adnanalhasani.commercial

internal data class CatalogUnit(val unitCode: String, val role: String, val quantityInBase: String, val barcode: String, val packageName: String, val imageUrl: String, val isDefault: Boolean, val unitName: String = "", val sku: String = "", val netQuantity: String = "", val netUnitCode: String = "")
internal data class CatalogRuling(val id: String, val domain: String, val authority: String, val religion: String, val jurisdiction: String, val outcome: String, val rationale: String, val sourceTitle: String, val sourceUrl: String, val confidence: String, val effectiveFrom: String, val effectiveTo: String, val reviewedAt: String)
internal data class CatalogProduct(
    val id: String,
    val catalogCode: String,
    val name: String,
    val description: String,
    val categoryCode: String,
    val category: String,
    val brand: String,
    val manufacturer: String,
    val countryOfManufacture: String,
    val imageUrl: String,
    val prescriptionOnly: Boolean,
    val recommendationScore: Int,
    val barcode: String = "",
    val unitCode: String = "EA",
    val availableUnits: List<CatalogUnit> = emptyList(),
    val createdBy: String = "",
    val createdAt: String = "",
    val importedBy: String = "",
    val attributesSchema: String = "",
    val modelNumber: String = "",
    val partNumber: String = "",
    val imageAlt: String = "",
    val materialComposition: String = "",
    val ingredients: String = "",
    val recipe: String = "",
    val specifications: String = "",
    val allergens: String = "",
    val storageInstructions: String = "",
    val usageInstructions: String = "",
    val warnings: String = "",
    val shelfLifeDays: Int? = null,
    val requiresExpiryDate: Boolean = false,
    val isBatchTracked: Boolean = false,
    val isSerialTracked: Boolean = false,
    val rulings: List<CatalogRuling> = emptyList(),
    val ownerOrganizationId: String = "",
    val nameAr: String = "",
    val nameEn: String = "",
    val descriptionAr: String = "",
    val descriptionEn: String = "",
    val itemType: String = "product",
    val barcodeGtin: String = "",
    val rawMaterialComposition: String = "[]",
    val rawIngredients: String = "[]",
    val rawRecipe: String = "{}",
    val rawSpecifications: String = "{}",
    val rawAllergens: String = "[]",
    val rawAttributesSchema: String = "{}",
    val rawBrand: String = "",
    val rawManufacturer: String = "",
    val rawCountryOfManufacture: String = "",
    val rawModelNumber: String = "",
    val rawPartNumber: String = "",
    val rawImageAlt: String = "",
    val rawStorageInstructions: String = "",
    val rawUsageInstructions: String = "",
    val rawWarnings: String = ""
)
internal data class CatalogCategory(val code: String, val parentCode: String, val nameAr: String, val nameEn: String, val localizedName: String = "", val descriptionAr: String = "", val descriptionEn: String = "", val localizedDescription: String = "")
internal data class CatalogBranchOption(val id: String, val name: String, val latitude: String, val longitude: String)
internal data class CatalogWarehouseOption(val id: String, val name: String, val branchId: String)
internal data class NearbyCatalogItem(val id: String, val name: String, val popularityBand: String, val merchantCountBand: String)
internal data class InvoicePurchaseRequestRow(
 val id: String,val sourceOrganizationId: String,val destinationOrganizationId: String,
 val invoiceNumber: String,val currencyCode: String,val totalMinor: Long,val status: String,
 val purchaseOrderId: String,val createdAt: String
)

internal data class InvoicePurchaseUnmappedLine(val id: String,val lineNumber: Int,val description: String,val quantity: String,val unitCode: String)
internal data class InvoicePurchaseOffering(val id: String,val globalItemId: String,val name: String,val unitCode: String)
internal data class DirectoryUnit(val code: String, val nameAr: String, val nameEn: String, val dimension: String, val baseCode: String, val factor: String, val owner: String, val localizedName: String = "")
internal data class PendingTransfer(val id: String, val source: String, val destination: String, val itemCount: Int, val createdAt: String)
