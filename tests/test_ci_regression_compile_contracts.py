from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def read(name):
    return (ANDROID / name).read_text(encoding="utf-8")


def test_catalog_directory_uses_initial_activity_provider_values():
    source = read("CatalogDirectoryWorkflow.kt")
    assert "catalogSectorForNode(initialActivityCode().uppercase(), directory.activityNodes)" in source
    assert "it.code.equals(initialActivityCode(), true)" in source


def test_catalog_screen_defines_organization_accessor_before_workflows():
    source = read("CatalogScreen.kt")
    accessor = source.index("fun org(): String?")
    workflow = source.index("val invoiceWorkflow = CatalogInvoiceWorkflow(")
    assert accessor < workflow
    assert "organizationId = { org() }" in source


def test_invoice_item_dialog_uses_the_supplied_organization_context():
    source = read("CatalogInvoiceItemCreationDialog.kt")
    assert "it.owner == organizationId" in source
    assert "stateOrganizationPlaceholder" not in source


def test_local_settlement_filter_compares_the_settlement_timestamp():
    source = read("ReportsCenterData.kt")
    assert "it.timestamp >= startMillis && it.timestamp < endExclusive" in source
    assert "it.timestamp >= startMillis && it < endExclusive" not in source
