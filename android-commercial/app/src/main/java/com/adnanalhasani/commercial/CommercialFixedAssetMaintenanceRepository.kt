package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialFixedAssetMaintenance(
    val id:String,val assetId:String,val assetCode:String,val assetName:String,
    val type:String,val title:String,val description:String,val scheduledDate:String,
    val dueDate:String,val status:String,val assignedTo:String,val vendorName:String,
    val completedAt:String,val costMinor:Long,val currencyCode:String,val notes:String,val version:Int
)

private fun assetMaintenanceContext(context:Context):Triple<String,String,String>{
 val (organizationId,access)=commercialMasterDataAccess(context)
 val activityId=context.getSharedPreferences("commercial_cloud_context_v1",Context.MODE_PRIVATE)
  .getString("activity_id",null).orEmpty()
 require(activityId.isNotBlank()){ "ملف النشاط غير محفوظ." }
 return Triple(organizationId,activityId,access)
}

internal fun loadCommercialFixedAssetMaintenance(context:Context,status:String=""):List<CommercialFixedAssetMaintenance>{
 val (organizationId,activityId,access)=assetMaintenanceContext(context)
 val rows=JSONArray(SupabaseClient.request(
  "/rest/v1/rpc/commercial_fixed_asset_maintenance_list","POST",
  JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
   .put("p_status",status.takeIf{it.isNotBlank()}?:JSONObject.NULL).put("p_limit",200),
  access
 ))
 return buildList{
  for(i in 0 until rows.length()){
   val row=rows.optJSONObject(i)?:continue
   val id=row.optString("id").takeUnless{it.isBlank()||it=="null"}.orEmpty()
   val title=row.optString("title").trim()
   if(id.isBlank()||title.isBlank())continue
   add(CommercialFixedAssetMaintenance(
    id=id,assetId=row.optString("asset_id"),assetCode=row.optString("asset_code"),
    assetName=row.optString("asset_name"),type=row.optString("maintenance_type"),title=title,
    description=row.optString("description"),scheduledDate=row.optString("scheduled_date"),
    dueDate=row.optString("due_date").takeUnless{it=="null"}.orEmpty(),status=row.optString("status"),
    assignedTo=row.optString("assigned_to").takeUnless{it=="null"}.orEmpty(),
    vendorName=row.optString("vendor_name"),completedAt=row.optString("completed_at").takeUnless{it=="null"}.orEmpty(),
    costMinor=row.optLong("cost_minor"),currencyCode=row.optString("currency_code","YER"),
    notes=row.optString("notes"),version=row.optInt("version",1)
   ))
  }
 }
}

internal fun createCommercialFixedAssetMaintenance(
 context:Context,assetId:String,type:String,title:String,description:String,
 scheduledDate:String,dueDate:String?,vendorName:String,costMinor:Long,notes:String,idempotencyKey:String
):JSONObject{
 require(assetId.isNotBlank()&&title.isNotBlank()&&costMinor>=0)
 val (organizationId,activityId,access)=assetMaintenanceContext(context)
 return JSONObject(SupabaseClient.request(
  "/rest/v1/rpc/commercial_fixed_asset_maintenance_create","POST",
  JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
   .put("p_asset_id",assetId).put("p_maintenance_type",type).put("p_title",title.trim())
   .put("p_description",description.trim()).put("p_scheduled_date",scheduledDate)
   .put("p_due_date",dueDate?.takeIf{it.isNotBlank()}?:JSONObject.NULL)
   .put("p_assigned_to",JSONObject.NULL).put("p_vendor_name",vendorName.trim())
   .put("p_cost_minor",costMinor).put("p_currency_code",JSONObject.NULL)
   .put("p_notes",notes.trim()).put("p_idempotency_key",idempotencyKey),
  access
 ))
}

internal fun updateCommercialFixedAssetMaintenance(
 context:Context,item:CommercialFixedAssetMaintenance,status:String,
 completedAt:String?,vendorName:String,costMinor:Long,notes:String
):JSONObject{
 val (_,_,access)=assetMaintenanceContext(context)
 return JSONObject(SupabaseClient.request(
  "/rest/v1/rpc/commercial_fixed_asset_maintenance_update","POST",
  JSONObject().put("p_maintenance_id",item.id).put("p_expected_version",item.version)
   .put("p_status",status).put("p_completed_at",completedAt?.takeIf{it.isNotBlank()}?:JSONObject.NULL)
   .put("p_vendor_name",vendorName.trim()).put("p_cost_minor",costMinor)
   .put("p_notes",notes.trim()),
  access
 ))
}

internal fun newCommercialAssetMaintenanceIdempotencyKey():String =
 "asset-maintenance-"+UUID.randomUUID().toString()
