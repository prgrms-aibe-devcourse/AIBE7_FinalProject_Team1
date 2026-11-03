SELECT jsonb_build_object(
    'section', 'catalog',
    'transaction_read_only', current_setting('transaction_read_only'),
    'postgres_version', current_setting('server_version'),
    'database', current_database(), 'schema', current_schema(),
    'columns', (SELECT jsonb_agg(to_jsonb(c)) FROM (
        SELECT table_name, column_name, data_type, is_nullable, column_default
        FROM information_schema.columns WHERE table_schema = current_schema()
        ORDER BY table_name, ordinal_position) c),
    'constraints', (SELECT jsonb_agg(to_jsonb(c)) FROM (
        SELECT r.relname AS table_name, c.conname, c.contype, c.convalidated,
               pg_get_constraintdef(c.oid) AS definition
        FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = r.relnamespace
        WHERE n.nspname = current_schema() ORDER BY r.relname, c.conname) c),
    'indexes', (SELECT jsonb_agg(to_jsonb(i)) FROM (
        SELECT r.relname AS table_name, x.relname AS index_name, i.indisvalid, i.indisready,
               pg_get_indexdef(i.indexrelid) AS definition
        FROM pg_index i JOIN pg_class r ON r.oid = i.indrelid
        JOIN pg_class x ON x.oid = i.indexrelid JOIN pg_namespace n ON n.oid = r.relnamespace
        WHERE n.nspname = current_schema() ORDER BY r.relname, x.relname) i),
    'grants', (SELECT jsonb_agg(to_jsonb(g)) FROM (
        SELECT table_name, grantee, privilege_type FROM information_schema.role_table_grants
        WHERE table_schema = current_schema() ORDER BY table_name, grantee, privilege_type) g),
    'rls', (SELECT jsonb_agg(to_jsonb(r)) FROM (
        SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND c.relkind = 'r' ORDER BY c.relname) r),
    'function_grants', (SELECT jsonb_agg(to_jsonb(f)) FROM (
        SELECT routine_name, grantee, privilege_type FROM information_schema.routine_privileges
        WHERE routine_schema = current_schema() ORDER BY routine_name, grantee, privilege_type) f)
)::text;
