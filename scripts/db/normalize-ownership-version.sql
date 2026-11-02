-- 일회성 PostgreSQL 복구용이다. Flyway 자동 실행 경로에 넣지 않는다.
-- 서버를 멈추고 DB를 백업한 뒤 normalize-ownership-version.psql로 실행한다.
--
-- 구버전 소유권 마이그레이션(2026102702, 2026102707)이 적용된 DB의 이력 번호를 현재 번호로 맞춘다.
--   V2026102702__create_ownership_tables.sql   -> V2026102803__create_ownership_tables.sql
--   V2026102707__add_ownership_pending_slot.sql -> V2026102804__add_ownership_pending_slot.sql
-- 두 쌍의 SQL 본문은 바이트 단위로 같고 체크섬도 같다. 그래서 실제 구조·데이터·체크섬은 건드리지 않고
-- 이력의 version, script 두 필드만 바꾼다. 번호를 맞추지 않으면 새 2803이 이미 있는 ownership_transfers를
-- 다시 만들려다 "relation already exists"로 서버 기동이 실패한다.
DO $alignment$
DECLARE
    target_schema TEXT := current_schema();
    spec RECORD;
    applied RECORD;
    old_count INTEGER;
    new_count INTEGER;
    base_count INTEGER;
    slot_count INTEGER;
    changed_count INTEGER;
    transfers_oid OID;
BEGIN
    IF current_setting('pastport.expected_database', TRUE) IS DISTINCT FROM current_database()
       OR current_setting('pastport.expected_schema', TRUE) IS DISTINCT FROM target_schema THEN
        RAISE EXCEPTION 'Expected database/schema must match the current connection';
    END IF;
    IF target_schema IS NULL OR target_schema = 'information_schema' OR target_schema LIKE 'pg\_%' ESCAPE '\'
       OR to_regclass(format('%I.flyway_schema_history', target_schema)) IS NULL THEN
        RAISE EXCEPTION 'The selected application schema has no Flyway history';
    END IF;

    PERFORM set_config('lock_timeout', '5s', TRUE);
    EXECUTE format('LOCK TABLE %I.flyway_schema_history IN ACCESS EXCLUSIVE MODE', target_schema);

    transfers_oid := to_regclass(format('%I.ownership_transfers', target_schema));

    SELECT count(*) INTO base_count FROM flyway_schema_history
    WHERE version IN ('2026102702', '2026102803');
    SELECT count(*) INTO slot_count FROM flyway_schema_history
    WHERE version IN ('2026102707', '2026102804');

    -- 소유권 이력이 전혀 없으면 정규화할 것이 없다. 테이블만 있으면 출처를 알 수 없으므로 중단한다.
    IF base_count = 0 AND slot_count = 0 THEN
        IF transfers_oid IS NOT NULL THEN
            RAISE EXCEPTION 'ownership_transfers exists without ownership history; manual review required';
        END IF;
        RAISE NOTICE 'No ownership history recorded; nothing to normalize';
        RETURN;
    END IF;
    IF slot_count > 0 AND base_count = 0 THEN
        RAISE EXCEPTION 'Pending-slot migration is recorded without the ownership table migration';
    END IF;
    IF transfers_oid IS NULL THEN
        RAISE EXCEPTION 'Ownership history is recorded but ownership_transfers is missing; no history change performed';
    END IF;

    -- 두 이전 SQL이 실제로 만든 구조가 있는지 먼저 확인한다. 기록과 실제 DDL이 다르면 이력만 맞추지 않고 중단한다.
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = target_schema AND table_name = 'ownership_transfers'
          AND column_name IN ('id', 'product_id', 'from_user_id', 'to_user_id', 'token_hash', 'status',
                              'transfer_type', 'purchase_request_id', 'expires_at', 'completed_at',
                              'cancelled_at', 'created_at')) <> 12
       OR (SELECT count(*) FROM pg_constraint
           WHERE conrelid = transfers_oid
             AND conname IN ('uk_transfers_token_hash', 'ck_transfers_status')) <> 2 THEN
        RAISE EXCEPTION 'Expected ownership_transfers columns/constraints are missing; no history change performed';
    END IF;
    IF slot_count > 0 AND (
           NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = target_schema AND table_name = 'ownership_transfers'
                         AND column_name = 'pending_slot')
           OR (SELECT count(*) FROM pg_constraint
               WHERE conrelid = transfers_oid
                 AND conname IN ('uk_transfers_pending_product', 'ck_transfers_pending_slot')) <> 2) THEN
        RAISE EXCEPTION 'Expected pending_slot column/constraints are missing; no history change performed';
    END IF;

    FOR spec IN
        SELECT * FROM (VALUES
            ('2026102702', '2026102803', 'create ownership tables',
             'V2026102702__create_ownership_tables.sql', 'V2026102803__create_ownership_tables.sql',
             -1318655273),
            ('2026102707', '2026102804', 'add ownership pending slot',
             'V2026102707__add_ownership_pending_slot.sql', 'V2026102804__add_ownership_pending_slot.sql',
             -1107274211)
        ) AS t(old_version, new_version, description, old_script, new_script, expected_checksum)
    LOOP
        SELECT count(*) INTO old_count FROM flyway_schema_history WHERE version = spec.old_version;
        SELECT count(*) INTO new_count FROM flyway_schema_history WHERE version = spec.new_version;
        IF old_count + new_count > 1 THEN
            RAISE EXCEPTION 'Both % and % are recorded; manual review required', spec.old_version, spec.new_version;
        END IF;
        IF old_count + new_count = 0 THEN
            CONTINUE;
        END IF;

        SELECT * INTO applied FROM flyway_schema_history
        WHERE version IN (spec.old_version, spec.new_version);
        IF applied.checksum IS DISTINCT FROM spec.expected_checksum
           OR applied.success IS DISTINCT FROM TRUE
           OR applied.type IS DISTINCT FROM 'SQL'
           OR applied.description IS DISTINCT FROM spec.description
           OR applied.script IS DISTINCT FROM (CASE WHEN applied.version = spec.old_version
                                                    THEN spec.old_script ELSE spec.new_script END) THEN
            RAISE EXCEPTION 'Ownership migration % identity/checksum/status does not match the verified SQL', applied.version;
        END IF;

        IF new_count = 1 THEN
            RAISE NOTICE 'Already canonical: %; no change', spec.new_version;
            CONTINUE;
        END IF;

        UPDATE flyway_schema_history
        SET version = spec.new_version, script = spec.new_script
        WHERE installed_rank = applied.installed_rank AND version = spec.old_version;
        GET DIAGNOSTICS changed_count = ROW_COUNT;
        IF changed_count <> 1 THEN
            RAISE EXCEPTION 'Expected exactly one history row to change';
        END IF;
        RAISE NOTICE 'Aligned % to %; business data and checksum unchanged', spec.old_version, spec.new_version;
    END LOOP;
END;
$alignment$;
