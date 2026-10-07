# Traceability Matrix — REQ → SPEC → Data Model → Architecture → Source

التغطية: 59/59 = 100%.
هذه مصفوفة جرد، وليست إثباتاً أن السلوك التفصيلي قد كُتب أو اعتُمد.

| REQ | SPEC | Data Model Concept(s) | Architecture Boundary | Source / Constraint / Decision |
|---|---|---|---|---|
| REQ-0001 | SPEC-0001 | Person; Identifier; Access Account; Authenticator; Session | Identity / Access | DOM-0001/0002; RES-0025 |
| REQ-0002 | SPEC-0001 / SPEC-0007 | Person; Access Account; Financial Account | Identity / Finance | COR-0001; DEC-0002 |
| REQ-0003 | SPEC-0002 | Person; Activity; Membership | Activities | DOM-0001/0002/0007 |
| REQ-0004 | SPEC-0001 | Access Account; Session | Access | DOM-0001/0002 |
| REQ-0005 | SPEC-0003 / SPEC-0004 | Family Relationship; Delegation; Authorization Grant | Family / Authorization | COR-0002; DEC-0004 |
| REQ-0006 | SPEC-0003 / SPEC-0004 | Family Relationship; Delegation; Authorization Grant | Family / Authorization | Q-0004; DEC-0001/0004 |
| REQ-0007 | SPEC-0004 / SPEC-0021 | Delegation; Authorization Grant; Domain History; Audit Record | Family / Authorization / Audit | Q-0014 |
| REQ-0008 | SPEC-0002 | Person; Membership; Role Assignment | Activities | Q-0001 |
| REQ-0009 | SPEC-0002 / SPEC-0003 | Role Assignment; Authorization Grant | Activities / Authorization | Q-0027 |
| REQ-0010 | SPEC-0003 | Activity; Person; Role Assignment; Authorization Grant | Activities / Privacy | Q-0002/Q-0027; DEC-0001 |
| REQ-0013 | SPEC-0005 | Product; Offering | Commerce | E-0006/E-0007 |
| REQ-0014 | SPEC-0005 / SPEC-0006 | Offering; Inventory Position | Commerce / Inventory | COR-0006 |
| REQ-0015 | SPEC-0006 | Inventory Position | Inventory | Q-0009/Q-0021; DEC-0012 |
| REQ-0016 | SPEC-0006 / SPEC-0014 | Availability; Product; Offering | Discovery / Commerce | COR-0006; DEC-0009 |
| REQ-0017 | SPEC-0007 / SPEC-0009 | Financial Account; Ledger Entry; Balance | Finance | RES-0025 |
| REQ-0018 | SPEC-0007 | Obligation; Debt; Loan | Financial Relations | Q-0005/Q-0034 |
| REQ-0019 | SPEC-0007 | Person; Loan; Obligation; Debt | Financial Relations / Finance | Q-0028/Q-0034; DEC-0003 |
| REQ-0020 | SPEC-0007 / SPEC-0008 / SPEC-0021 | Obligation; Loan; Payment; Settlement; Domain History | Financial Relations / Finance | COR-0003 |
| REQ-0021 | SPEC-0008 | Payment; Settlement | Payments / Settlement | Q-0006 |
| REQ-0022 | SPEC-0008 | Payment; Financial Transaction | Payments / Finance | COR-0003; Q-0007/Q-0008 |
| REQ-0023 | SPEC-0008 / SPEC-0009 | Settlement; Obligation; Payment; Financial Transaction | Payments / Finance | E-0011/E-0012 |
| REQ-0024 | SPEC-0010 / SPEC-0025 | Patient Context; Encounter; Clinical Record; Result/Report; Prescription; Financial Reference | Health / Finance | COR-0002; Q-0010/Q-0032 |
| REQ-0025 | SPEC-0010 | Patient Context; Encounter; Clinical Record; Result/Report; Prescription | Health | Q-0010 |
| REQ-0026 | SPEC-0010 / SPEC-0011 | Agent; Authority Policy; Agent Action; Approval; Clinical Record; Prescription | Health / Agents | DEC-0007 |
| REQ-0027 | SPEC-0012 | Person; Activity; Membership; Role Assignment | Education | DEC-0010 |
| REQ-0028 | SPEC-0012 / SPEC-0004 | Family Relationship; Delegation; Authorization Grant; Membership | Education / Family | DEC-0001/0004 |
| REQ-0029 | SPEC-0012 / SPEC-0025 | Education context; Financial Account; Payment; Ledger Entry | Education / Finance | DEC-0001/0010 |
| REQ-0030 | SPEC-0013 | Conversation; Message; Channel Context | Communication | DOM-0001/0004 |
| REQ-0031 | SPEC-0013 / SPEC-0002 | Message; Person; Membership; Role Assignment | Communication / Activities | REQ-FUNC-0008 |
| REQ-0032 | SPEC-0013 / SPEC-0026 | Message + referenced Domain Truth | Communication / Target Domain | COR-0003 |
| REQ-0033 | SPEC-0014 | Availability; Offering; Activity; Service | Discovery / Commerce | DEC-0009 |
| REQ-0034 | SPEC-0014 / SPEC-0006 | Availability; Offering; Inventory Position | Discovery / Inventory | COR-0006; DEC-0009 |
| REQ-0035 | SPEC-0014 / SPEC-0006 | Availability | Discovery | T-0012; Q-0018 |
| REQ-0036 | SPEC-0015 | Channel Context; Sale | Channels / Commerce | DEC-0008 |
| REQ-0037 | SPEC-0005 / SPEC-0008 / SPEC-0009 | Sale; Invoice; Obligation; Payment; Settlement | Commerce / Finance / Payments | Q-0008 |
| REQ-0038 | SPEC-0005 / SPEC-0003 / SPEC-0015 | Sale; Activity; Role Assignment; Authorization Grant | Commerce / Authorization | Q-0027 |
| REQ-0039 | SPEC-0016 | Instrument; Sale; Invoice; Payment | Instrument / Commerce / Finance | DEC-0005 |
| REQ-0040 | SPEC-0016 | Instrument; Invoice; Payment; Settlement | Instrument / Finance | DEC-0005 |
| REQ-0041 | SPEC-0016 / SPEC-0021 | Instrument; Sale; Invoice; Payment; Domain History | Instrument / Audit | COR-0004; DEC-0005 |
| REQ-0042 | SPEC-0017 | Agent; Authority Policy; Authorization Grant; Agent Action | Agents / Authorization | DEC-0006/0007 |
| REQ-0043 | SPEC-0017 / SPEC-0018 | Agent Action; Agent; Authority Policy; Approval | Agents / Authorization | DEC-0006/0007 |
| REQ-0044 | SPEC-0017 / SPEC-0021 | Agent; Agent Action; Provenance; Audit Record | Agents / Audit | Q-0017 |
| REQ-0045 | SPEC-0018 | Approval; Agent Action; Authority Policy | Authorization / Agents | DEC-0006/0007 |
| REQ-0046 | SPEC-0019 | Device; Pending Operation; State Record | Offline / Target Domain | DEC-0011 |
| REQ-0048 | SPEC-0020 | Device; Pending Operation; Conflict | Offline / Owner Domain | DEC-0012/0013 |
| REQ-0049 | SPEC-0003 / SPEC-0022 | Authorization Grant; Purpose/Access Context; Sensitivity | Privacy / Authorization | DEC-0001 |
| REQ-0050 | SPEC-0021 | Audit Record; Agent Action; Domain Action | Audit / Governance | Q-0013/Q-0017 |
| REQ-0051 | SPEC-0021 / SPEC-0022 | Domain History; Provenance; Audit Record | Audit / Domain | DEC-0001 |
| REQ-0052 | SPEC-0003 / SPEC-0022 | Authorization Grant; Purpose/Access Context; Sensitivity | Privacy / Authorization | DEC-0001 |
| REQ-0053 | SPEC-0024 | State/Behavior concepts | Cross-domain | T-0001/T-0004/T-0005 |
| REQ-0054 | SPEC-0021 / SPEC-0024 | Audit Record; Provenance; Domain History | Audit / Governance | Q-0017; COR-0005 |
| REQ-0055 | SPEC-0022 / SPEC-0024 | Authorization Grant; Purpose/Access Context; Sensitivity; Retention | Privacy | DEC-0001 |
| REQ-0056 | SPEC-0019 / SPEC-0024 | Device; Pending Operation; Domain Truth | Offline / Target Domain | Q-0020; DEC-0011 |
| REQ-0061 | SPEC-0023 | Agent Action / Payment / Sale / Domain State | Cross-domain | DOM-0004/0006 |
| REQ-0062 | SPEC-0023 | Payment; Financial Transaction; Pending Operation | Payments / Offline | Q-0020/Q-0021 |
| REQ-0063 | SPEC-0023 / SPEC-0005 / SPEC-0008 | Sale; Invoice; Payment; Settlement; Obligation | Commerce / Finance / Payments | Q-0008/Q-0025; DEC-0001 |
| REQ-0064 | SPEC-0020 | Conflict; Pending Operation; Device; Owner Domain | Offline / Owner Domain | DEC-0012 |
| REQ-0065 | SPEC-0020 | Device; Session; Pending Operation; Domain History | Access / Offline | DEC-0013 |
| REQ-0066 | SPEC-0010 / SPEC-0020 | Clinical Record; Result/Report; Prescription; Conflict | Health / Offline | DEC-0007/0012 |
