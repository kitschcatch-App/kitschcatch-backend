-- 지정한 개발 애플리케이션 테이블의 스키마와 실행 권한을 읽는다.
BEGIN READ ONLY;
SET LOCAL search_path = pg_catalog, public;
WITH wanted AS (
    SELECT unnest(string_to_array(:'tables', ',')) AS name
), tables AS (
    SELECT c.oid, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN wanted w ON w.name = c.relname
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
), objects AS (
    SELECT jsonb_build_array('table', t.relname, NULL) AS data FROM tables t
    UNION ALL
    SELECT jsonb_build_array('column', t.relname, a.attname, format_type(a.atttypid, a.atttypmod),
                             a.attnotnull, a.attidentity, pg_get_expr(d.adbin, d.adrelid))
    FROM tables t JOIN pg_attribute a ON a.attrelid = t.oid
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attnum > 0 AND NOT a.attisdropped
    UNION ALL
    SELECT jsonb_build_array('constraint', t.relname, c.conname, pg_get_constraintdef(c.oid), c.convalidated)
    FROM tables t JOIN pg_constraint c ON c.conrelid = t.oid
    UNION ALL
    SELECT jsonb_build_array('index', t.relname, c.relname, pg_get_indexdef(i.indexrelid))
    FROM tables t JOIN pg_index i ON i.indrelid = t.oid
    JOIN pg_class c ON c.oid = i.indexrelid
    WHERE i.indisvalid AND i.indisready
    UNION ALL
    SELECT jsonb_build_array('privilege', t.relname, p.name)
    FROM tables t CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) p(name)
    WHERE has_table_privilege(t.oid, p.name)
    UNION ALL
    SELECT jsonb_build_array('sequence', t.relname, s.relname, has_sequence_privilege(s.oid, 'USAGE'))
    FROM tables t JOIN pg_depend d ON d.refobjid = t.oid
    JOIN pg_class s ON s.oid = d.objid
    WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
      AND d.deptype IN ('a', 'i') AND s.relkind = 'S'
)
SELECT data FROM objects ORDER BY data::text;
COMMIT;
