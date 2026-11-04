package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class CommercialCloudSupplierControlAccount(
    val accountRole: String,
    val ledgerAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudSupplierControlAccounts(context: Context): List<CommercialCloudSupplierControlAccount> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_supplier_gl_accounts?select=account_role,ledger_account_id,currency_code&organization_id=eq.$orgId&order=account_role.asc,currency_code.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val role = row.optString("account_role").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (role.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudSupplierControlAccount(role, accountId, currency))
            }
        }
    }
}

internal data class CommercialCloudGlAccountMapping(
    val mappingKey: String,
    val ledgerAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudGlAccountMappings(context: Context): List<CommercialCloudGlAccountMapping> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_gl_account_mappings?select=mapping_key,ledger_account_id,ledger_account:commercial_ledger_accounts!commercial_gl_account_mapping_organization_id_ledger_accou_fkey(currency_code)&organization_id=eq.$orgId&order=mapping_key.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val key = row.optString("mapping_key").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = row.optJSONObject("ledger_account")
            val currency = account?.optString("currency_code").takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty()
            if (key.isNotBlank() && accountId.isNotBlank()) add(CommercialCloudGlAccountMapping(key, accountId, currency))
        }
    }
}

internal data class CommercialCloudPartyAccountLink(
    val partyId: String,
    val partyAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudCustomerPartyAccounts(context: Context): List<CommercialCloudPartyAccountLink> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_customer_party_accounts?select=customer_id,party_account_id,party_account:commercial_party_accounts!commercial_customer_party_accounts_party_account_id_fkey(currency_code)&organization_id=eq.$orgId&order=customer_id.asc,party_account_id.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val partyId = row.optString("customer_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = row.optJSONObject("party_account")
            val currency = account?.optString("currency_code").takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty()
            if (partyId.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudPartyAccountLink(partyId, accountId, currency))
            }
        }
    }
}

internal fun loadCommercialCloudSupplierPartyAccounts(context: Context): List<CommercialCloudPartyAccountLink> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_supplier_party_accounts?select=supplier_id,party_account_id,currency_code&organization_id=eq.$orgId&order=supplier_id.asc,currency_code.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val partyId = row.optString("supplier_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (partyId.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudPartyAccountLink(partyId, accountId, currency))
            }
        }
    }
}

internal data class CommercialCloudJournalLine(
    val entryId: String,
    val entryNumber: String,
    val entryDate: String,
    val entryDescription: String,
    val sourceType: String,
    val sourceId: String,
    val lineNumber: Int,
    val accountId: String,
    val accountCode: String,
    val accountName: String,
    val accountType: String,
    val normalBalance: String,
    val partyAccountId: String,
    val debitMinor: Long,
    val creditMinor: Long,
    val currencyCode: String
)

/**
 * Loads posted entries and their lines from the authoritative organization-scoped ledger.
 * Draft/unposted entries are intentionally excluded. This function is read-only and does not post
 * or repair accounting data.
 */
internal fun loadCommercialCloudPostedJournalLines(context: Context): List<CommercialCloudJournalLine> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val entries = commercialCloudPagedGet(
        "/rest/v1/commercial_journal_entries?select=id,entry_number,status,entry_date,description,source_type,source_id" +
            "&organization_id=eq.$orgId&status=eq.posted&order=entry_date.asc,id.asc",
        access
    )
    val entryById = buildMap {
        for (i in 0 until entries.length()) {
            val row = entries.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank() && row.optString("status").equals("posted", true)) put(id, row)
        }
    }
    if (entryById.isEmpty()) return emptyList()

    val lines = commercialCloudPagedGet(
        "/rest/v1/commercial_journal_lines?select=id,journal_entry_id,line_number,ledger_account_id,party_account_id,description,debit_minor,credit_minor,currency_code" +
            "&organization_id=eq.$orgId&order=journal_entry_id.asc,line_number.asc",
        access
    )
    val accounts = commercialCloudPagedGet(
        "/rest/v1/commercial_ledger_accounts?select=id,account_code,account_name,account_type,normal_balance,currency_code,is_active" +
            "&organization_id=eq.$orgId&order=account_code.asc,id.asc",
        access
    )
    val accountById = buildMap {
        for (i in 0 until accounts.length()) {
            val row = accounts.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank()) put(id, row)
        }
    }
    return buildList {
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val entryId = line.optString("journal_entry_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val entry = entryById[entryId] ?: continue
            val accountId = line.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = accountById[accountId]
            add(CommercialCloudJournalLine(
                entryId = entryId,
                entryNumber = entry.optString("entry_number").takeUnless { it == "null" }.orEmpty(),
                entryDate = entry.optString("entry_date").takeUnless { it == "null" }.orEmpty(),
                entryDescription = entry.optString("description").takeUnless { it == "null" }.orEmpty(),
                sourceType = entry.optString("source_type").takeUnless { it == "null" }.orEmpty(),
                sourceId = entry.optString("source_id").takeUnless { it == "null" }.orEmpty(),
                lineNumber = line.optInt("line_number", i + 1),
                accountId = accountId,
                accountCode = account?.optString("account_code").takeUnless { it == null || it == "null" }.orEmpty(),
                accountName = account?.optString("account_name").takeUnless { it == null || it == "null" }.orEmpty(),
                accountType = account?.optString("account_type").takeUnless { it == null || it == "null" }.orEmpty(),
                normalBalance = account?.optString("normal_balance").takeUnless { it == null || it == "null" }.orEmpty(),
                partyAccountId = line.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                debitMinor = line.optLong("debit_minor", 0L),
                creditMinor = line.optLong("credit_minor", 0L),
                currencyCode = line.optString("currency_code").takeUnless { it == "null" }.orEmpty()
                    .ifBlank { account?.optString("currency_code").takeUnless { it == null || it == "null" }.orEmpty() }
            ))
        }
    }
}
