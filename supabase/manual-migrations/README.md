# ترحيلات يدوية محفوظة خارج مسار Supabase الآلي

يحتوي هذا المجلد على SQL نُفذ يدويًا في مشروع Supabase الحي، ولم يُسجل كإصدارات مستقلة في `supabase_migrations.schema_migrations`.

- `20261023_harden_postgres_default_table_privileges.sql`
- `20261023_harden_public_table_write_grants.sql`

أُخرجت من `supabase/migrations/` لتجنب تكرار رقم الإصدار، وليس لحذف أي صلاحية أو تغيير قاعدة البيانات. فحص البيئة الحية في 2026-11-02 أكد أن صلاحيات الكتابة المقصودة غير ممنوحة.

**لا تنقل هذه الملفات إلى مجلد الترحيلات النشطة ولا تعِد تشغيلها** إلا بعد مراجعة سجل البيئة الحية ومطابقة الأثر. يجب أن تتم تسوية تاريخها عبر baseline/إجراء migration repair موثق عند إعداد بيئة قابلة لإعادة الإنتاج.

## Baseline and reproducible local testing

The tracked supabase/migrations/ directory does **not** currently contain the original commercial base schema. The first tracked migration (20261023190000_*) assumes tables such as public.commercial_organizations already exist. The initial schema was provisioned outside the tracked migration history; the two SQL files above are security-hardening changes only and are not that base schema.

A fresh-database replay was attempted in an isolated PostgreSQL/Supabase environment. Service startup succeeded after assigning a valid local project id, then replay stopped at 20261023200000_commercial_activity_profile_and_hierarchy.sql because public.commercial_organizations had never been created. This is a real migration-history gap, not a transient runner failure.

Until an authoritative historical baseline is committed:

- Do not claim that all 216+ migrations can rebuild production from an empty database.
- Do not generate a baseline from today's production schema and then replay old migrations on top; that would duplicate later objects and distort history.
- Do not apply manual SQL or migration-repair commands to production as a shortcut.
- Test new migration slices against data-free PostgreSQL fixtures that define the exact referenced base tables, and keep a separate, explicit task to recover the pre-2026-10-23 baseline or a reviewed migration-repair plan.

The current storage-location CI job is such a focused fixture test. It validates the two storage migrations and their RPC behavior; it is **not** a substitute for full historical replay.