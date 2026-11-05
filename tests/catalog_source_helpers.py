from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def catalog_android_source(*extra_files: str) -> str:
    """Read catalog implementation across its extracted owners for source contracts."""
    paths = sorted(CATALOG.glob("Catalog*.kt"))
    sources = [
        path.read_text(encoding="utf-8")
        for path in paths
        if path.name != "CatalogModels.kt"
    ]
    sources.extend((CATALOG / name).read_text(encoding="utf-8") for name in extra_files)
    return "\n".join(sources)
