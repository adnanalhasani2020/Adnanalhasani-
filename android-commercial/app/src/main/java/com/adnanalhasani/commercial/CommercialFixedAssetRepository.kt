package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialFixedAsset(
    val id: String,
    val code: String,
    val name: String,
    val category: String,
    val serialNumber: String,
    val description: String,
    val acquisitionDate: String,
    val availableForUseDate: String,
    val acquisitionCostMinor: Long,
    val salvageValueMinor: Long,
    val currencyCode: String,
    val usefulLifeMonths: Int,
    val depreciationMethod: String,
    val status: String,
    val locationId: String,
    val locationName: String,
    val custodianId: String,
    val depreciationStartDate: String,
    val disposedAt: String,
    val disposalProceedsMinor: Long?,
    val notes: String,
    val version: Int
)

private fun fixedAssetContext(context: Context): Triple<String,String,String> {
    val (organizationId, access) = commercialMasterDataAccess(context)
    val activityId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
        .getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    return Triple(organizationId, activityId, access)
}

internal fun loadCommercialFixedAssets(context: Context, status: String = ""): List<CommercialFixedAsset> {
    val (organizationId, activityId, access) = fixedAssetContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_fixed_asset_list","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
            .put("p_status",status.takeIf{it.isNotBlank()}?:JSONObject.NULL).put("p_limit",200),
        access
    ))
    return buildList {
        for(index in 0 until rows.length()){
            val row=rows.optJSONObject(index)?:continue
            val id=row.optString("id").takeUnless{it.isBlank()||it=="null"}.orEmpty()
            val code=row.optString("asset_code").trim()
            val name=row.optString("asset_name").trim()
            if(id.isBlank()||code.isBlank()||name.isBlank())continue
            add(CommercialFixedAsset(
                id=id,code=code,name=name,category=row.optString("category"),
                serialNumber=row.optString("serial_number"),description=row.optString("description"),
                acquisitionDate=row.optString("acquisition_date"),availableForUseDate=row.optString("available_for_use_date"),
                acquisitionCostMinor=row.optLong("acquisition_cost_minor"),salvageValueMinor=row.optLong("salvage_value_minor"),
                currencyCode=row.optString("currency_code","YER"),usefulLifeMonths=row.optInt("useful_life_months"),
                depreciationMethod=row.optString("depreciation_method"),status=row.optString("status"),
                locationId=row.optString("location_id").takeUnless{it=="null"}.orEmpty(),
                locationName=row.optString("location_name"),custodianId=row.optString("custodian_id").takeUnless{it=="null"}.orEmpty(),
                depreciationStartDate=row.optString("depreciation_start_date").takeUnless{it=="null"}.orEmpty(),
                disposedAt=row.optString("disposed_at").takeUnless{it=="null"}.orEmpty(),
                disposalProceedsMinor=if(row.isNull("disposal_proceeds_minor"))null else row.optLong("disposal_proceeds_minor"),
                notes=row.optString("notes"),version=row.optInt("version",1)
            ))
        }
    }
}

internal fun createCommercialFixedAsset(
    context:Context,code:String,name:String,category:String,serialNumber:String,description:String,
    acquisitionDate:String,availableForUseDate:String,costMinor:Long,salvageMinor:Long,
    usefulLifeMonths:Int,method:String,notes:String,idempotencyKey:String
):JSONObject{
    require(code.isNotBlank()&&name.isNotBlank()&&category.isNotBlank())
    require(costMinor>0&&salvageMinor>=0&&salvageMinor<costMinor&&usefulLifeMonths in 1..600)
    val (organizationId,activityId,access)=fixedAssetContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_fixed_asset_create","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
            .put("p_asset_code",code.trim()).put("p_asset_name",name.trim()).put("p_category",category.trim())
            .put("p_serial_number",serialNumber.trim()).put("p_description",description.trim())
            .put("p_acquisition_date",acquisitionDate).put("p_available_for_use_date",availableForUseDate)
            .put("p_acquisition_cost_minor",costMinor).put("p_salvage_value_minor",salvageMinor)
            .put("p_currency_code",JSONObject.NULL).put("p_useful_life_months",usefulLifeMonths)
            .put("p_depreciation_method",method).put("p_location_id",JSONObject.NULL)
            .put("p_custodian_id",JSONObject.NULL).put("p_notes",notes.trim())
            .put("p_idempotency_key",idempotencyKey),
        access
    ))
}

internal fun updateCommercialFixedAsset(
    context:Context,asset:CommercialFixedAsset,status:String,notes:String,
    disposedAt:String?=null,disposalProceedsMinor:Long?=null
):JSONObject{
    val (_,_,access)=fixedAssetContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_fixed_asset_update","POST",
        JSONObject().put("p_asset_id",asset.id).put("p_expected_version",asset.version)
            .put("p_status",status).put("p_location_id",asset.locationId.takeIf{it.isNotBlank()}?:JSONObject.NULL)
            .put("p_custodian_id",asset.custodianId.takeIf{it.isNotBlank()}?:JSONObject.NULL)
            .put("p_notes",notes.take(4000)).put("p_disposed_at",disposedAt?:JSONObject.NULL)
            .put("p_disposal_proceeds_minor",disposalProceedsMinor?:JSONObject.NULL),
        access
    ))
}

internal fun previewCommercialFixedAssetDepreciation(context:Context,assetId:String,limit:Int=600):JSONObject{
    val (organizationId,activityId,access)=fixedAssetContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_fixed_asset_depreciation_preview","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
            .put("p_asset_id",assetId).put("p_limit",limit.coerceIn(1,600)),
        access
    ))
}

internal fun newCommercialFixedAssetIdempotencyKey():String = "asset-"+UUID.randomUUID().toString()
