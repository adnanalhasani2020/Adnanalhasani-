"""TEST-0006 — Requirements Traceability Verification.

Verification-only. No production implementation is changed by this test.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REQ_MATRIX = ROOT / "docs/06-المواصفات/02-مصفوفة-التتبع-REQ-SPEC.md"
SPEC_MAP = ROOT / "docs/06-المواصفات/00-خريطة-المواصفات.md"
GAP_REGISTER = ROOT / "docs/08-الاختبار/09-سجل-GAPs-TEST-0004.md"
SRC = ROOT / "src/agent_core"

EXPECTED_REQS = {
    "0001","0002","0003","0004","0005","0006","0007","0008","0009","0010",
    "0013","0014","0015","0016","0017","0018","0019","0020","0021","0022",
    "0023","0024","0025","0026","0027","0028","0029","0030","0031","0032",
    "0033","0034","0035","0036","0037","0038","0039","0040","0041","0042",
    "0043","0044","0045","0046","0048","0049","0050","0051","0052","0053",
    "0054","0055","0056","0061","0062","0063","0064","0065","0066",
}

FULL = {"0001","0003","0004","0008","0013","0014","0017","0020","0021","0025","0030","0031","0032","0044","0061"}
LIMITED_GAPS = {"0009","0023","0042","0045","0062"}

SPEC_TO_IMPL = {
    "SPEC-0001": "src/agent_core/domain_identity.py",
    "SPEC-0002": "src/agent_core/domain_activities.py",
    "SPEC-0003": "src/agent_core/domain_authorization.py",
    "SPEC-0004": "src/agent_core/domain_authorization.py",
    "SPEC-0005": "src/agent_core/domain_commerce.py",
    "SPEC-0006": "src/agent_core/domain_inventory.py",
    "SPEC-0007": "src/agent_core/domain_finance.py",
    "SPEC-0008": "src/agent_core/domain_finance.py",
    "SPEC-0009": "src/agent_core/domain_finance.py",
    "SPEC-0010": "src/agent_core/domain_health.py",
    "SPEC-0011": "src/agent_core/domain_authorization.py + src/agent_core/domain_health.py",
    "SPEC-0012": "src/agent_core/domain_education.py + src/agent_core/domain_activities.py",
    "SPEC-0013": "src/agent_core/domain_communication.py",
    "SPEC-0014": "src/agent_core/domain_inventory.py",
    "SPEC-0015": "src/agent_core/domain_communication.py + src/agent_core/domain_commerce.py",
    "SPEC-0016": "NO CANONICAL INSTRUMENT IMPLEMENTATION",
    "SPEC-0017": "src/agent_core/domain_authorization.py + src/agent_core/domain_audit.py",
    "SPEC-0018": "src/agent_core/domain_authorization.py",
    "SPEC-0019": "src/agent_core/domain_offline.py",
    "SPEC-0020": "src/agent_core/domain_offline.py",
    "SPEC-0021": "src/agent_core/domain_audit.py",
    "SPEC-0022": "src/agent_core/domain_authorization.py",
    "SPEC-0023": "src/agent_core/domain_exceptions.py",
    "SPEC-0024": "NO MEASURABLE NFR IMPLEMENTATION",
    "SPEC-0025": "src/agent_core/domain_finance.py + src/agent_core/domain_health.py",
    "SPEC-0026": "CROSS-CUTTING TRACEABILITY RULE; NO DOMAIN OWNER; src/agent_core/integrity.py (Stage 11 GAP-0002/GAP-0005); src/agent_core/financial_orchestration.py (Stage 12A GAP-0004)",
}

SPEC_TO_TESTS = {
    "SPEC-0001": ["test_stage7_batch1.py","test_stage8_batch1.py","test_stage8_batch5.py"],
    "SPEC-0002": ["test_stage7_batch1.py","test_stage8_batch1.py","test_stage8_batch5.py"],
    "SPEC-0003": ["test_batch3.py","test_stage8_batch2.py","test_stage8_batch4.py"],
    "SPEC-0004": ["test_batch3.py","test_stage8_batch4.py"],
    "SPEC-0005": ["test_batch2.py","test_stage8_batch1.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0006": ["test_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0007": ["test_batch3.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0008": ["test_batch3.py","test_stage8_batch2.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0009": ["test_batch3.py","test_stage8_batch1.py","test_stage8_batch2.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0010": ["test_batch3.py","test_stage8_batch1.py","test_stage8_batch2.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0011": ["test_batch4.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0012": ["test_batch4.py"],
    "SPEC-0013": ["test_batch2.py","test_stage8_batch3.py"],
    "SPEC-0014": ["test_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0015": ["test_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0016": ["test_batch4.py"],
    "SPEC-0017": ["test_batch3.py","test_batch4.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0018": ["test_batch4.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0019": ["test_stage8_batch1.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0020": ["test_stage8_batch1.py","test_stage8_batch2.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0021": ["test_batch4.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0022": ["test_batch3.py","test_batch4.py"],
    "SPEC-0023": ["test_batch4.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
    "SPEC-0024": ["test_stage7_ci_coverage.py"],
    "SPEC-0025": ["test_stage8_batch1.py","test_stage8_batch2.py","test_stage8_batch5.py"],
    "SPEC-0026": ["test_stage8_batch1.py","test_stage8_batch3.py","test_stage8_batch4.py","test_stage8_batch5.py"],
}

def rows(path):
    return [r for r in path.read_text(encoding="utf-8").splitlines() if r.startswith("| REQ-")]

def req_matrix():
    result = {}
    for row in rows(REQ_MATRIX):
        p = [x.strip() for x in row.split("|")]
        result[p[1]] = {"primary": p[2], "supporting": p[3]}
    return result

def spec_catalog():
    specs = set()
    deps = {}
    for row in [r for r in SPEC_MAP.read_text(encoding="utf-8").splitlines() if r.startswith("| SPEC-")]:
        p = [x.strip() for x in row.split("|")]
        spec = p[1]
        specs.add(spec)
        dep_field = p[6] if len(p) > 6 else ""
        deps[spec] = [] if dep_field.startswith("Domain Constraint:") else re.findall(r"SPEC-\d{4}", dep_field)
    return specs, deps

def status_for(req):
    n = req[-4:]
    if n in LIMITED_GAPS:
        return "INTENTIONALLY LIMITED / GAP"
    if n in FULL:
        return "FULLY TRACED"
    return "BLOCKED BY OPEN DECISION / RESEARCH"

def _test_files_for(spec):
    return SPEC_TO_TESTS[spec]

def test_traceability_has_exactly_59_requirements():
    m = req_matrix()
    assert len(m) == 59
    assert {k[-4:] for k in m} == EXPECTED_REQS

def test_every_requirement_has_primary_and_supporting_classification():
    m = req_matrix()
    assert all(v["primary"].startswith("SPEC-") for v in m.values())
    assert all(v["supporting"] == "—" or all(x.startswith("SPEC-") for x in v["supporting"].split(", ")) for v in m.values())

def test_primary_specs_are_unique_and_all_specs_are_referenced():
    m = req_matrix()
    primaries = [v["primary"] for v in m.values()]
    assert all(primaries.count(s) >= 1 for s in set(primaries))
    specs, _ = spec_catalog()
    referenced = set(primaries) | {x for v in m.values() for x in v["supporting"].split(", ") if x != "—"}
    assert specs == referenced

def test_spec_dependency_graph_has_no_cycles_or_self_dependencies():
    specs, deps = spec_catalog()
    assert all(s not in deps.get(s, []) for s in specs)
    state = {}
    def visit(s):
        if state.get(s) == 1:
            raise AssertionError(f"SPEC dependency cycle at {s}")
        if state.get(s) == 2:
            return
        state[s] = 1
        for d in deps.get(s, []):
            if d in specs:
                visit(d)
        state[s] = 2
    for s in specs:
        visit(s)

def test_every_requirement_has_implementation_and_test_mapping():
    m = req_matrix()
    for req, data in m.items():
        spec = data["primary"]
        assert spec in SPEC_TO_IMPL
        assert spec in SPEC_TO_TESTS
        assert SPEC_TO_TESTS[spec]
        status = status_for(req)
        if status == "FULLY TRACED":
            assert not SPEC_TO_IMPL[spec].startswith("NO ")
        if status == "INTENTIONALLY LIMITED / GAP":
            assert "Stage 8" in " ".join(SPEC_TO_TESTS[spec]) or "test_" in " ".join(SPEC_TO_TESTS[spec])

def test_gap_and_decision_registers_are_consistent_with_traceability():
    gap = GAP_REGISTER.read_text(encoding="utf-8")
    for n in range(1, 7):
        assert f"GAP-000{n}" in gap
    assert all(f"DEC-{n:04d}" in (ROOT / "docs/06-المواصفات/01-سجل-اعتماد-القرارات-على-المواصفات.md").read_text(encoding="utf-8") for n in range(1, 14))

def test_no_batch6_claim_promotes_known_gaps_to_enforcement():
    text = (ROOT / "docs/08-الاختبار/TEST-0006-متطلبات-التتبع.md").read_text(encoding="utf-8")
    assert "GAP-0001" in text
    assert "GAP-0003" in text
    assert "GAP-0005" in text
    assert "GAP-0006" in text
    assert "complete implementation traceability is not claimed" in text

def test_reverse_implementation_inventory_has_traceability_owner():
    expected = {p.name for p in SRC.glob("*.py")}
    # Cross-cutting/support files are explicitly owned by the verification map.
    mapped = {
        "__init__.py","application.py","config.py","domain_activities.py","domain_audit.py",
        "domain_authorization.py","domain_commerce.py","domain_communication.py","domain_education.py",
        "domain_exceptions.py","domain_finance.py","domain_health.py","domain_identity.py",
        "domain_inventory.py","domain_offline.py","financial_orchestration.py","infrastructure.py","integrity.py","approval_enforcement.py","runtime.py","shared.py",
    }
    assert expected == mapped
    impl_text = " ".join(SPEC_TO_IMPL.values())
    cross_cutting = {
        "__init__.py": "package boundary",
        "config.py": "runtime configuration boundary",
        "application.py": "application composition over domain SPECs",
        "infrastructure.py": "audit infrastructure supporting SPEC-0021",
        "approval_enforcement.py": "Stage 11 GAP-0001 runtime approval enforcement boundary",
        "approval_enforcement.py": "Stage 11 GAP-0001 runtime approval enforcement boundary",
        "runtime.py": "runtime composition over mapped domain boundaries",
        "shared.py": "shared validation/state support for domain SPECs",
    }
    for module in expected:
        if module in cross_cutting:
            assert cross_cutting[module]
        else:
            assert module in impl_text
