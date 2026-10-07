# Traceability Matrix — REQ → SPEC → Data Model → Architecture → Source

## قاعدة Primary / Supporting SPEC

المصفوفة تسجل جميع SPECs المشاركة في تغطية كل REQ، ولا يعني ترتيبها النصي ملكية تلقائية. وفق قاعدة Stage 6:

- **Primary SPEC** = المواصفة التي تملك السلوك الأساسي للـREQ.
- **Supporting SPEC** = مواصفة تساعد في تحقيق الـREQ أو تضبط جانباً مشتركاً منها دون امتلاك السلوك الأساسي.
- وجود REQ في أكثر من SPEC لا يعني تعدد الملكية؛ لكل REQ Primary SPEC واحدة، مع Supporting SPECs عند الحاجة.
- لا يغيّر هذا التصنيف معنى REQ أو ملكية أي Concept/Relationship في Stage 5.

ويجب أن تكون هذه القاعدة هي أساس فصل Primary/Supporting عند الانتقال إلى كتابة المواصفات التفصيلية.


التغطية: 59/59 = 100%.
هذه مصفوفة جرد، وليست إثباتاً أن السلوك التفصيلي قد كُتب أو اعتُمد.

| REQ | Primary SPEC | Supporting SPECs | Dependency/Constraint | Decision | Data Model Concept(s) | Architecture Boundary |
|---|---|---|---|---|---|---|
| REQ-0001 | SPEC-0001 | — | — | — | Identity / Access | DOM-0001/0002; RES-0025 |
| REQ-0002 | SPEC-0001 | SPEC-0007 | — | — | Identity / Finance | COR-0001; DEC-0002 |
| REQ-0003 | SPEC-0002 | — | — | — | Activities | DOM-0001/0002/0007 |
| REQ-0004 | SPEC-0001 | — | — | — | Access | DOM-0001/0002 |
| REQ-0005 | SPEC-0004 | SPEC-0003 | — | — | Family / Authorization | COR-0002; DEC-0004 |
| REQ-0006 | SPEC-0004 | SPEC-0003 | — | — | Family / Authorization | Q-0004; DEC-0001/0004 |
| REQ-0007 | SPEC-0004 | SPEC-0021 | — | — | Family / Authorization / Audit | Q-0014 |
| REQ-0008 | SPEC-0002 | — | — | — | Activities | Q-0001 |
| REQ-0009 | SPEC-0003 | SPEC-0002 | — | — | Activities / Authorization | Q-0027 |
| REQ-0010 | SPEC-0003 | SPEC-0002 | — | — | Activities / Privacy | Q-0002/Q-0027; DEC-0001 |
| REQ-0013 | SPEC-0005 | — | — | — | Commerce | E-0006/E-0007 |
| REQ-0014 | SPEC-0005 | SPEC-0006 | — | — | Commerce / Inventory | COR-0006 |
| REQ-0015 | SPEC-0006 | — | — | — | Inventory | Q-0009/Q-0021; DEC-0012 |
| REQ-0016 | SPEC-0014 | SPEC-0006 | — | — | Discovery / Commerce | COR-0006; DEC-0009 |
| REQ-0017 | SPEC-0007 | SPEC-0009 | — | — | Finance | RES-0025 |
| REQ-0018 | SPEC-0007 | — | — | — | Financial Relations | Q-0005/Q-0034 |
| REQ-0019 | SPEC-0007 | — | — | — | Financial Relations / Finance | Q-0028/Q-0034; DEC-0003 |
| REQ-0020 | SPEC-0007 | SPEC-0008, SPEC-0021 | — | — | Financial Relations / Finance | COR-0003 |
| REQ-0021 | SPEC-0008 | — | — | — | Payments / Settlement | Q-0006 |
| REQ-0022 | SPEC-0008 | — | — | — | Payments / Finance | COR-0003; Q-0007/Q-0008 |
| REQ-0023 | SPEC-0008 | SPEC-0009 | — | — | Payments / Finance | E-0011/E-0012 |
| REQ-0024 | SPEC-0010 | SPEC-0025 | — | — | Health / Finance | COR-0002; Q-0010/Q-0032 |
| REQ-0025 | SPEC-0010 | — | — | — | Health | Q-0010 |
| REQ-0026 | SPEC-0010 | SPEC-0011 | — | — | Health / Agents | DEC-0007 |
| REQ-0027 | SPEC-0012 | — | — | — | Education | DEC-0010 |
| REQ-0028 | SPEC-0012 | SPEC-0004 | — | — | Education / Family | DEC-0001/0004 |
| REQ-0029 | SPEC-0012 | SPEC-0025 | — | — | Education / Finance | DEC-0001/0010 |
| REQ-0030 | SPEC-0013 | — | — | — | Communication | DOM-0001/0004 |
| REQ-0031 | SPEC-0013 | SPEC-0002 | — | — | Communication / Activities | REQ-FUNC-0008 |
| REQ-0032 | SPEC-0013 | SPEC-0026 | — | — | Communication / Target Domain | COR-0003 |
| REQ-0033 | SPEC-0014 | — | — | — | Discovery / Commerce | DEC-0009 |
| REQ-0034 | SPEC-0014 | SPEC-0006 | — | — | Discovery / Inventory | COR-0006; DEC-0009 |
| REQ-0035 | SPEC-0014 | SPEC-0006 | — | — | Discovery | T-0012; Q-0018 |
| REQ-0036 | SPEC-0015 | — | — | — | Channels / Commerce | DEC-0008 |
| REQ-0037 | SPEC-0005 | SPEC-0008, SPEC-0009 | — | — | Commerce / Finance / Payments | Q-0008 |
| REQ-0038 | SPEC-0005 | SPEC-0003, SPEC-0015 | — | — | Commerce / Authorization | Q-0027 |
| REQ-0039 | SPEC-0016 | — | — | — | Instrument / Commerce / Finance | DEC-0005 |
| REQ-0040 | SPEC-0016 | — | — | — | Instrument / Finance | DEC-0005 |
| REQ-0041 | SPEC-0016 | SPEC-0021 | — | — | Instrument / Audit | COR-0004; DEC-0005 |
| REQ-0042 | SPEC-0017 | SPEC-0003 | — | — | Agents / Authorization | DEC-0006/0007 |
| REQ-0043 | SPEC-0017 | SPEC-0018 | — | — | Agents / Authorization | DEC-0006/0007 |
| REQ-0044 | SPEC-0017 | SPEC-0021 | — | — | Agents / Audit | Q-0017 |
| REQ-0045 | SPEC-0018 | SPEC-0017 | — | — | Authorization / Agents | DEC-0006/0007 |
| REQ-0046 | SPEC-0019 | — | — | — | Offline / Target Domain | DEC-0011 |
| REQ-0048 | SPEC-0020 | — | — | — | Offline / Owner Domain | DEC-0012/0013 |
| REQ-0049 | SPEC-0022 | SPEC-0003 | — | — | Privacy / Authorization | DEC-0001 |
| REQ-0050 | SPEC-0021 | — | — | — | Audit / Governance | Q-0013/Q-0017 |
| REQ-0051 | SPEC-0021 | SPEC-0022 | — | — | Audit / Domain | DEC-0001 |
| REQ-0052 | SPEC-0022 | SPEC-0003 | — | — | Privacy / Authorization | DEC-0001 |
| REQ-0053 | SPEC-0024 | — | — | — | Cross-domain | T-0001/T-0004/T-0005 |
| REQ-0054 | SPEC-0021 | SPEC-0024 | — | — | Audit / Governance | Q-0017; COR-0005 |
| REQ-0055 | SPEC-0022 | SPEC-0024 | — | — | Privacy | DEC-0001 |
| REQ-0056 | SPEC-0019 | SPEC-0024 | — | — | Offline / Target Domain | Q-0020; DEC-0011 |
| REQ-0061 | SPEC-0023 | — | — | — | Cross-domain | DOM-0004/0006 |
| REQ-0062 | SPEC-0023 | — | — | — | Payments / Offline | Q-0020/Q-0021 |
| REQ-0063 | SPEC-0023 | SPEC-0005, SPEC-0008 | — | — | Commerce / Finance / Payments | Q-0008/Q-0025; DEC-0001 |
| REQ-0064 | SPEC-0020 | — | — | — | Offline / Owner Domain | DEC-0012 |
| REQ-0065 | SPEC-0020 | — | — | — | Access / Offline | DEC-0013 |
| REQ-0066 | SPEC-0020 | SPEC-0010 | — | — | Health / Offline | DEC-0007/0012 |
