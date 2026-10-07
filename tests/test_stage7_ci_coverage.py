from importlib import import_module
from pathlib import Path


def test_all_stage7_python_modules_are_importable():
    package_dir = Path(__file__).resolve().parents[1] / "src" / "agent_core"
    module_files = sorted(
        path for path in package_dir.glob("*.py") if path.name != "__init__.py"
    )

    assert module_files, "No Stage 7 Python modules were discovered."

    for path in module_files:
        module_name = f"agent_core.{path.stem}"
        module = import_module(module_name)
        assert module is not None, f"Failed to import {module_name}"
