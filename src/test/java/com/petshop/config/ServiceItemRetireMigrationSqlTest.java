package com.petshop.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.petshop.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #7 旧服务项目退场（contract 段）SQL 回归：在一份「仍带 service_item 与 appointment 外键」的旧库上
 * 跑真实的 v1.4 迁移，断言引用被改写、旧表被删除，且重复执行幂等。
 */
public class ServiceItemRetireMigrationSqlTest {

    private Path dbFile;
    private Connection conn;
    private JdbcTemplate jdbc;

    @BeforeEach
    public void setUp() throws Exception {
        dbFile = Files.createTempFile("psm-retire-", ".db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
        buildLegacyDatabase();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    /** 模拟 v1.3 时期的旧库：service_item 存在，appointment 带 REFERENCES service_item(id)。 */
    private void buildLegacyDatabase() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE schema_version(version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
            st.execute("INSERT INTO schema_version VALUES('v1.1','2026-01-01 00:00:00'),"
                    + "('v1.2','2026-01-01 00:00:00'),('v1.3','2026-01-01 00:00:00')");
            st.execute("CREATE TABLE service_item(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "duration_min INTEGER NOT NULL DEFAULT 60, price_s_cent INTEGER NOT NULL DEFAULT 0, "
                    + "price_m_cent INTEGER NOT NULL DEFAULT 0, price_l_cent INTEGER NOT NULL DEFAULT 0, "
                    + "price_xl_cent INTEGER NOT NULL DEFAULT 0, sort_no INTEGER NOT NULL DEFAULT 0, "
                    + "is_active INTEGER NOT NULL DEFAULT 1)");
            st.execute("INSERT INTO service_item(id, name) VALUES(1,'精洗'),(2,'剪毛造型')");
            st.execute("CREATE TABLE customer(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)");
            st.execute("INSERT INTO customer VALUES(1,'张三')");
            st.execute("CREATE TABLE pet(id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER, name TEXT)");
            st.execute("INSERT INTO pet VALUES(1, 1, '豆豆')");
            st.execute("CREATE TABLE appointment ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "customer_id INTEGER NOT NULL REFERENCES customer(id), "
                    + "pet_id INTEGER NOT NULL REFERENCES pet(id), "
                    + "service_id INTEGER NOT NULL REFERENCES service_item(id), "
                    + "appoint_date TEXT NOT NULL, slot TEXT NOT NULL, size_tier TEXT, "
                    + "status TEXT NOT NULL DEFAULT 'BOOKED', bill_id INTEGER, remark TEXT, "
                    + "created_at TEXT NOT NULL, created_by TEXT)");
            st.execute("CREATE INDEX idx_appointment_date ON appointment(appoint_date, slot)");
            st.execute("INSERT INTO appointment(id, customer_id, pet_id, service_id, appoint_date, slot, "
                    + "size_tier, status, remark, created_at, created_by) VALUES"
                    + "(1, 1, 1, 1, '2026-10-01', '10:00', 'M', 'DONE', '历史预约', '2026-10-01 09:00:00', '店主'),"
                    + "(2, 1, 1, 2, '2026-10-02', '11:00', 'L', 'BOOKED', NULL, '2026-10-02 09:00:00', '店主')");
            st.execute("CREATE TABLE card_type(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "service_ids TEXT NOT NULL, paid_qty INTEGER NOT NULL, gift_qty INTEGER NOT NULL DEFAULT 0, "
                    + "price_cent INTEGER NOT NULL, valid_days INTEGER NOT NULL DEFAULT 365, "
                    + "is_active INTEGER NOT NULL DEFAULT 1)");
            st.execute("INSERT INTO card_type(id, name, service_ids, paid_qty, price_cent) "
                    + "VALUES(1, '洗护10次卡', '1,2', 10, 120000)");
            st.execute("CREATE TABLE commission_rule(id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, "
                    + "ref_id INTEGER, staff_role TEXT, ratio_bp INTEGER NOT NULL DEFAULT 0, "
                    + "fixed_cent INTEGER NOT NULL DEFAULT 0, effective_from TEXT NOT NULL, effective_to TEXT, "
                    + "is_active INTEGER NOT NULL DEFAULT 1, remark TEXT, created_at TEXT NOT NULL)");
            st.execute("INSERT INTO commission_rule(scope, ref_id, ratio_bp, effective_from, created_at) VALUES"
                    + "('SERVICE', 1, 1000, '2026-01-01', '2026-01-01 00:00:00'),"
                    + "('SERVICE', NULL, 500, '2026-01-01', '2026-01-01 00:00:00')");
        }
    }

    private boolean tableExists(String name) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Integer.class, name);
        return n != null && n > 0;
    }

    /** 与老库语法兼容的简化版 service_category，供外键解析。 */
    private void createCategoryTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS service_category(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, degree TEXT NOT NULL DEFAULT 'SHOWER', sort_no INTEGER NOT NULL DEFAULT 0, "
                + "is_visible INTEGER NOT NULL DEFAULT 1)");
    }

    /** 在应用连接上跑真实的 v1.4 迁移（与 DataInitializer 相同的 helper 与事务配置）。 */
    private void runMigration() {
        AppProps props = new AppProps();
        String parent = dbFile.toAbsolutePath().getParent().toString();
        props.setDataDir(parent);
        DataInitializer initializer = new DataInitializer(null, props, null, jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(
                        new SingleConnectionDataSource(conn, true))));
        initializer.migrateV14();
    }

    @Test
    public void migrationRetiresLegacyServiceItemAndRewritesReferences() throws Exception {
        createCategoryTable();
        jdbc.update("INSERT INTO service_category(name) VALUES('精洗')");

        runMigration();

        // 1. sqlite_master 里不再有 service_item，且不残留 service_item_legacy
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'service_item%'", Integer.class),
                "旧表及其备份表都不应留在库里");

        // 2. appointment 重建：没有指向 service_item 的外键，数据保留，size_tier 列移除
        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='appointment'", String.class);
        assertFalse(ddl.toUpperCase().contains("SERVICE_ITEM"), "appointment 不应再引用 service_item：" + ddl);
        assertTrue(ddl.toUpperCase().contains("REFERENCES SERVICE_BEAUTY_ITEM"), "service_id 应指向美容单项：" + ddl);
        assertTrue(ddl.contains("service_name_snapshot"), "重建后保留项目名快照列：" + ddl);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class));
        assertNull(jdbc.queryForMap("SELECT * FROM appointment WHERE id=1").get("service_id"),
                "旧预约的 service_id 指针清空（旧 id 不沿用）");
        assertEquals("精洗", jdbc.queryForObject(
                "SELECT service_name_snapshot FROM appointment WHERE id=1", String.class),
                "历史预约保留当时的项目名快照");
        assertEquals("剪毛造型", jdbc.queryForObject(
                "SELECT service_name_snapshot FROM appointment WHERE id=2", String.class));
        assertNull(jdbc.queryForMap("SELECT * FROM appointment WHERE id=1").get("size_tier"));
        assertEquals("BOOKED", jdbc.queryForObject(
                "SELECT status FROM appointment WHERE id=2", String.class));
        List<Map<String, Object>> fk = jdbc.queryForList("PRAGMA foreign_key_list(appointment)");
        boolean pointsToBeauty = false;
        for (Map<String, Object> f : fk) {
            assertFalse("service_item".equals(f.get("table")), "外键列表里不应再有 service_item");
            if ("service_beauty_item".equals(f.get("table"))) {
                pointsToBeauty = true;
            }
        }
        assertTrue(pointsToBeauty, "appointment.service_id 外键应指向 service_beauty_item");
        // 重建后索引仍在
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master "
                + "WHERE type='index' AND name='idx_appointment_date'", Integer.class));

        // 3. 次卡类型适用服务清空（旧 service_item id 与新 id 空间不同），卡类型本身保留
        assertEquals("", jdbc.queryForObject("SELECT service_ids FROM card_type WHERE id=1", String.class));

        // 4. 提成规则：指定了旧项目的规则改为不限（旧 id 不指向新表），原本不限服务的规则不动
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_rule WHERE scope='SERVICE' AND ref_id IS NULL", Integer.class),
                "两条 SERVICE 规则都应变为不限服务");
        assertFalse(jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_rule WHERE ref_id IS NOT NULL", Integer.class) > 0);

        // 5. 版本登记
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version='v1.4'", Integer.class));
    }

    @Test
    public void migrationIsIdempotent() throws Exception {
        runMigration();
        int appointmentBefore = jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class);
        runMigration();

        assertFalse(tableExists("service_item"));
        assertEquals(appointmentBefore, jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class));
        assertEquals("", jdbc.queryForObject("SELECT service_ids FROM card_type WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version='v1.4'", Integer.class));
    }

    @Test
    public void freshSchemaHasNoServiceItemTable() throws Exception {
        // schema.sql 本身不再建 service_item：新库从空库启动时迁移无需重建 appointment
        try (Connection fresh = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = fresh.createStatement()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(fresh,
                        new org.springframework.core.io.ClassPathResource("schema/schema.sql"));
                java.sql.ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'service_item%'");
                assertFalse(rs.next(), "schema.sql 不应再建 service_item");
                rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE name='appointment'");
                assertTrue(rs.next());
                assertFalse(rs.getString(1).toUpperCase().contains("SERVICE_ITEM"));
            }
            runMigration();
            assertNotNull(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schema_version WHERE version='v1.4'", Integer.class));
        }
    }
}
