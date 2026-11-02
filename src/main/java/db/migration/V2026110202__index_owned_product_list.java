package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 내 제품 cursor 조회용 인덱스를 PostgreSQL에서는 쓰기를 막지 않고 생성한다. */
public class V2026110202__index_owned_product_list extends BaseJavaMigration {

  private static final String INDEX = "ix_products_owner_archive_created";

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("2026110202");
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public void migrate(Context context) throws Exception {
    String database = context.getConnection().getMetaData().getDatabaseProductName();
    try (Statement statement = context.getConnection().createStatement()) {
      if ("PostgreSQL".equals(database)) {
        if (hasInvalidPostgresIndex(statement)) {
          statement.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX);
        }
        statement.execute(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS "
                + INDEX
                + " ON products (owner_id, archived_at, created_at DESC, id DESC)");
      } else {
        statement.execute(
            "CREATE INDEX IF NOT EXISTS "
                + INDEX
                + " ON products (owner_id, archived_at, created_at DESC, id DESC)");
      }
    }
  }

  private boolean hasInvalidPostgresIndex(Statement statement) throws Exception {
    try (ResultSet result =
        statement.executeQuery(
            """
            SELECT NOT i.indisvalid
              FROM pg_class c
              JOIN pg_index i ON i.indexrelid = c.oid
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relname = 'ix_products_owner_archive_created'
               AND n.nspname = current_schema()
            """)) {
      return result.next() && result.getBoolean(1);
    }
  }
}
