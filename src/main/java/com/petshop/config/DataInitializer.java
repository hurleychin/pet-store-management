package com.petshop.config;

import com.petshop.module.system.BackupService;
import com.petshop.module.system.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 首次启动：建目录、建 admin、v1.1 增列迁移、可选示例数据。 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserService userService;
    private final AppProps props;
    private final BackupService backupService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DataInitializer(UserService userService, AppProps props,
                           BackupService backupService, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.userService = userService;
        this.props = props;
        this.backupService = backupService;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(Paths.get(props.getDataDir()));
        Files.createDirectories(Paths.get(props.getBackupDir()));
        Files.createDirectories(Paths.get("logs"));

        userService.initAdmin("admin", props.getDefaultAdminPassword());
        migrateV11();
        migrateV12();
        migrateV13();
        migrateV14();
        if (props.isSampleData()) {
            seedSampleData();
        }

        log.info("============================================================");
        log.info("  宠物店管理系统启动完成");
        log.info("  访问地址:  http://localhost:8080");
        log.info("  数据文件:  {}", backupService.dbPath());
        log.info("  默认账号:  admin（初始密码见 app.default-admin-password / 环境变量 APP_DEFAULT_ADMIN_PASSWORD，请登录后立即修改）");
        log.info("============================================================");
    }

    /** v1.0 库升级：product_ledger 补 unit_cost_cent 列（新库由 schema.sql 直接建出）。 */
    private void migrateV11() {
        boolean exists = false;
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(product_ledger)")) {
            if ("unit_cost_cent".equals(col.get("name"))) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            jdbc.execute("ALTER TABLE product_ledger ADD COLUMN unit_cost_cent INTEGER NOT NULL DEFAULT 0");
            log.info("v1.1 迁移：product_ledger 已增列 unit_cost_cent");
        }
        jdbc.execute("INSERT OR IGNORE INTO store_config(k, v) VALUES ('slot_capacity', '3')");
    }

    /**
     * v1.2 迁移：存量表增列（幂等）+ 迁移版本登记。
     * 新表由 schema.sql 的 IF NOT EXISTS 建出，这里只处理 ALTER。
     */
    private void migrateV12() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version(version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");

        addColumnIfMissing("product", "spec", "TEXT");
        addColumnIfMissing("product", "category", "TEXT DEFAULT 'OTHER'");
        addColumnIfMissing("product", "shelf_life_days", "INTEGER");
        addColumnIfMissing("product", "default_supplier_id", "INTEGER");
        addColumnIfMissing("bill_item", "staff_id", "INTEGER");
        addColumnIfMissing("bill_item", "batch_id", "INTEGER");
        addColumnIfMissing("product_ledger", "batch_id", "INTEGER");
        addColumnIfMissing("sys_user", "staff_id", "INTEGER");
        addColumnIfMissing("customer", "birthday", "TEXT");

        // 索引在列存在后创建，避免老库 ALTER 前建索引失败
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ledger_batch ON product_ledger(batch_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_item_staff ON bill_item(staff_id)");

        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.2'", Integer.class);
        if (done == null || done == 0) {
            jdbc.update("INSERT INTO schema_version(version, applied_at) VALUES('v1.2', ?)",
                    java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            log.info("v1.2 迁移完成：批次/盘点/提成/客户经营/审计表就绪");
        }
    }

    /**
     * v1.3 迁移：洗护美容价目。本步只登记版本，新表 service_category 等由 schema.sql 的 IF NOT EXISTS 建出；
     * 旧服务项目表的删除与数据迁移见 v1.4。可重复执行。
     */
    private void migrateV13() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version(version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
        createBeautyItemIfMissing();
        createPriceTablesIfMissing();
        createMemberGroupIfMissing();
        addColumnIfMissing("customer", "member_group_id", "INTEGER");
        addColumnIfMissing("bill_item", "line_discount_cent", "INTEGER NOT NULL DEFAULT 0");
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.3'", Integer.class);
        if (done == null || done == 0) {
            jdbc.update("INSERT INTO schema_version(version, applied_at) VALUES('v1.3', ?)",
                    java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            log.info("v1.3 迁移完成：洗护美容价目表结构就绪");
        }
    }

    /** 服务分类表：schema.sql 已建则不动；v1.4 重建预约前要保证外键目标存在（幂等）。 */
    private void createCategoryIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS service_category ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "degree TEXT NOT NULL DEFAULT 'SHOWER',"
                + "sort_no INTEGER NOT NULL DEFAULT 0,"
                + "is_visible INTEGER NOT NULL DEFAULT 1)");
    }

    /** v1.3 美容单项表：schema.sql 已建则不动；旧库若缺列则补齐（幂等）。 */
    private void createBeautyItemIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS service_beauty_item ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "category_id INTEGER NOT NULL,"
                + "retail_price_cent INTEGER NOT NULL DEFAULT 0,"
                + "discount_type TEXT NOT NULL DEFAULT 'GROUP',"
                + "discount_rate INTEGER NOT NULL DEFAULT 10,"
                + "show_cashier INTEGER NOT NULL DEFAULT 1,"
                + "show_shop INTEGER NOT NULL DEFAULT 1,"
                + "earn_points INTEGER NOT NULL DEFAULT 0,"
                + "sort_no INTEGER NOT NULL DEFAULT 0,"
                + "is_active INTEGER NOT NULL DEFAULT 1)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_service_beauty_item_cat "
                + "ON service_beauty_item(category_id, sort_no, id)");
    }

    /** v1.3 价目行 / 价格格子：与 schema.sql 第 35/36 节一致，老库在此补建（幂等）。 */
    private void createPriceTablesIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS service_price_row ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, animal TEXT NOT NULL DEFAULT 'DOG', "
                + "description TEXT NOT NULL, dog_size TEXT, fur_length TEXT, breed_tags TEXT, "
                + "sort_no INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_service_price_row_sort ON service_price_row(sort_no, id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS service_price_cell ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "row_id INTEGER NOT NULL REFERENCES service_price_row(id) ON DELETE CASCADE, "
                + "category_id INTEGER NOT NULL REFERENCES service_category(id), "
                + "price_cent INTEGER NOT NULL DEFAULT 0, "
                + "discount_type TEXT NOT NULL DEFAULT 'FULL', discount_rate REAL NOT NULL DEFAULT 10)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_service_price_cell ON service_price_cell(row_id, category_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_service_price_cell_category ON service_price_cell(category_id)");
    }

    /** v1.3 会员组：与 schema.sql 第 38 节一致；「散客组」固定 id=1、10 折，已存在则不覆盖店长改过的折扣率。 */
    private void createMemberGroupIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS member_group ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "discount_rate INTEGER NOT NULL DEFAULT 10)");
        jdbc.update("INSERT OR IGNORE INTO member_group(id, name, discount_rate) VALUES (1, '散客组', 10)");
    }

    /**
     * v1.4 迁移（旧服务项目退场，expand–contract 的 contract 段）：清掉对旧 service_item 的全部引用并删表。
     *
     * <p>顺序（可重复执行）：旧库先整库备份（备份目录）；事务内把旧预约的项目名写进快照、清空 service_id 指针
     * 并重建 appointment（去掉指向旧表的外键、不再按体型档，SQLite 不能直接删外键列），
     * 清空 card_type.service_ids 与指定了旧项目的 SERVICE 提成规则 ref_id，最后删除旧表。
     * 并发下另一进程已删表时事务整体回滚后重跑一次，读到的就是「无旧表」状态。</p>
     */
    void migrateV14() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version(version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
        if (!tableExists("appointment")) {
            registerVersion("v1.4", "旧服务项目退场：新库无 appointment 表，跳过");
            return;
        }
        // 预约区分项目来源（BEAUTY 美容单项 / CATEGORY 价目表服务分类）：幂等增列，先于可能提前 return 的判断。
        addColumnIfMissing("appointment", "service_type", "TEXT NOT NULL DEFAULT 'BEAUTY'");
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.4'", Integer.class);
        boolean legacy = tableExists("service_item");
        if ((done != null && done > 0) && !legacy) {
            return;
        }
        if (legacy) {
            backupLegacyServiceItems();
        }
        for (int attempt = 0; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    // 外键指向的表必须先存在；老库可能还没建过美容单项 / 分类
                    createCategoryIfMissing();
                    createBeautyItemIfMissing();
                    addColumnIfMissing("appointment", "service_name_snapshot", "TEXT");
                    ensureLegacyServiceIdsWide();
                    convertAppointmentServiceIds(true);
                    String apptDdl = jdbc.queryForObject(
                            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'appointment'",
                            String.class);
                    if (joinReferencesServiceItem(apptDdl) || columnExists("appointment", "size_tier")) {
                        rebuildAppointment();
                    }
                    if (tableExists("service_item")) {
                        migrateCardTypeServiceIds();
                        migrateServiceCommissionRules();
                        jdbc.execute("DROP TABLE service_item");
                    }
                    registerVersion("v1.4", "旧服务项目退场：预约改指美容单项，次卡适用服务与提成规则已清理，"
                            + "service_item 已删除");
                });
                return;
            } catch (RuntimeException e) {
                if (attempt >= 1 || tableExists("service_item")) {
                    throw e;
                }
                log.warn("v1.4 迁移重试（service_item 已被其他进程删除）：{}", e.getMessage());
            }
        }
    }

    private void registerVersion(String version, String message) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version(version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = ?", Integer.class, version);
        if (done == null || done == 0) {
            jdbc.update("INSERT INTO schema_version(version, applied_at) VALUES(?, ?)", version,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            log.info("{} 迁移完成：{}", version, message);
        }
    }

    private boolean tableExists(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(" + table + ")")) {
            if (column.equals(col.get("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 旧库删除旧服务项目表前先做一份整库备份（删除全部行会触发自动 VACUUM，旧页可能被复用，
     * 单靠备份目录里「迁移前那一份」不够稳）；备份失败只告警，不阻塞迁移。
     */
    private void backupLegacyServiceItems() {
        if (backupService == null) {
            log.warn("v1.4 迁移：未配置备份服务，删表前未预备份");
            return;
        }
        try {
            String name = backupService.manualBackup();
            log.info("v1.4 迁移：删除旧服务项目表前已备份到 {}", name);
        } catch (Exception e) {
            log.warn("v1.4 迁移：预备份失败（继续迁移）：{}", e.getMessage());
        }
    }

    /** 很老的库 service_id 可能是 INTEGER REFERENCES：先放宽成无类型列，才能把旧 id 改写成文本。 */
    private void ensureLegacyServiceIdsWide() {
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(appointment)")) {
            if ("service_id".equals(col.get("name"))
                    && "INTEGER".equalsIgnoreCase(String.valueOf(col.get("type")))) {
                readdColumnAsText("appointment", "service_id", "service_id TEXT NOT NULL DEFAULT ''");
            }
        }
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(card_type)")) {
            if ("service_ids".equals(col.get("name"))
                    && "INTEGER".equalsIgnoreCase(String.valueOf(col.get("type")))) {
                readdColumnAsText("card_type", "service_ids", "service_ids TEXT NOT NULL DEFAULT ''");
            }
        }
    }

    /** SQLite 不改列类型：重建表把该列换成 TEXT（其余列与数据保留）。 */
    private void readdColumnAsText(String table, String column, String definition) {
        String tmp = table + "_v14_tmp";
        jdbc.execute("DROP TABLE IF EXISTS " + tmp);
        String oldDdl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", String.class, table);
        String newDdl = replaceColumnDefinition(oldDdl, column, definition);
        String copyCols = null;
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(" + table + ")")) {
            copyCols = copyCols == null ? String.valueOf(col.get("name")) : copyCols + ", " + col.get("name");
        }
        jdbc.execute(renameCreateTable(newDdl, tmp));
        jdbc.execute("INSERT INTO " + tmp + "(" + copyCols + ") SELECT " + copyCols + " FROM " + table);
        jdbc.execute("DROP TABLE " + table);
        jdbc.execute("ALTER TABLE " + tmp + " RENAME TO " + table);
    }

    /**
     * 预约服务的 service_id 改为美容单项 id 的文本（兼容空）：旧 id 无法可靠映射到新表，
     * 按名称匹配同名的美容单项，匹配不到保留旧 id 文本（仅作快照参考，不对应任何新记录）。
     */
    private void convertAppointmentServiceIds(boolean legacy) {
        if (!columnExists("appointment", "service_id")) {
            return;
        }
        if (!legacy || !tableExists("service_item")) {
            // 无旧表可对照：指针已不可解释，留空（重建时 NULL 会被搬运为未指定）
            jdbc.execute("UPDATE appointment SET service_id = '' WHERE service_id IS NOT NULL AND service_id <> ''");
            return;
        }
        // 迁移后预约的 service_id 指针清空（空串 = 未指定，重建时搬运为 NULL；NOT NULL 列不能直接写 NULL）；
        // 先把旧项目名写进 service_name_snapshot，历史预约在预约看板上仍显示当时的项目名
        jdbc.execute("UPDATE appointment SET service_name_snapshot = COALESCE(service_name_snapshot, "
                + "(SELECT s.name FROM service_item s WHERE s.id = CAST(appointment.service_id AS INTEGER)))");
        jdbc.execute("UPDATE appointment SET service_id = ''");
    }

    /** appointment 是否还声明着 service_id → service_item 的外键（sqlite_master 里关键字前常无空格）。 */
    private static boolean joinReferencesServiceItem(String ddl) {
        if (ddl == null) {
            return false;
        }
        String upper = ddl.toUpperCase();
        int i = 0;
        while ((i = upper.indexOf("REFERENCES", i)) >= 0) {
            int j = i + "REFERENCES".length();
            while (j < upper.length() && Character.isWhitespace(upper.charAt(j))) {
                j++;
            }
            if (j < upper.length() && upper.charAt(j) == '"') {
                j++;
            }
            if (upper.startsWith("SERVICE_ITEM", j)) {
                return true;
            }
            i += "REFERENCES".length();
        }
        return false;
    }

    /** 新建 appointment_new → 拷数据（size_tier 不再使用，留空）→ 删旧 → 改名 → 补索引。 */
    private void rebuildAppointment() {
        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'appointment'", String.class);
        ddl = replaceColumnDefinition(ddl, "service_id",
                "service_id TEXT REFERENCES service_beauty_item(id)");
        ddl = replaceColumnDefinition(ddl, "size_tier", "size_tier TEXT /* #7 废弃：预约不再按体型档估价 */");
        ddl = ddl.replace("REFERENCES service_item", "REFERENCES service_beauty_item");
        if (!ddl.contains("service_type")) {
            ddl = ddl.replace("service_id TEXT", "service_id TEXT, service_type TEXT NOT NULL DEFAULT 'BEAUTY'");
        }
        jdbc.execute("DROP TABLE IF EXISTS appointment_new");
        jdbc.execute(renameCreateTable(ddl, "appointment_new"));
        jdbc.execute("INSERT INTO appointment_new(id, customer_id, pet_id, service_id, service_type, appoint_date, slot, "
                + "size_tier, status, bill_id, remark, created_at, created_by, service_name_snapshot) "
                // 旧 service_id 已清空（空意味着未指定），原样搬运；历史预约一律视为美容单项来源
                + "SELECT id, customer_id, pet_id, NULLIF(service_id, ''), 'BEAUTY', appoint_date, slot, "
                + "NULL, status, bill_id, remark, created_at, created_by, service_name_snapshot FROM appointment");
        jdbc.execute("DROP TABLE appointment");
        jdbc.execute("ALTER TABLE appointment_new RENAME TO appointment");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_appointment_date ON appointment(appoint_date, slot)");
    }

    /** 把建表语句的表名换成新名字（保留 IF NOT EXISTS 前的原貌）。 */
    private static String renameCreateTable(String ddl, String newName) {
        return ddl.replaceFirst("(?i)^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(\\S+)",
                "CREATE TABLE $1" + newName);
    }

    /** 在 CREATE TABLE 语句里替换某一列的完整定义（保留其余列）。 */
    private static String replaceColumnDefinition(String ddl, String column, String definition) {
        // 语句尾部可能带 COMMENT '...'：列定义括号取第一对真正的匹配括号
        int open = -1;
        int depth0 = 0;
        int matching = -1;
        for (int k = 0; k < ddl.length(); k++) {
            char ch = ddl.charAt(k);
            if (ch == '(') {
                if (depth0 == 0 && open < 0) {
                    open = k;
                }
                depth0++;
            } else if (ch == ')' && open >= 0) {
                depth0--;
                if (depth0 == 0) {
                    matching = k;
                    break;
                }
            }
        }
        if (open < 0 || matching < 0) {
            return ddl;
        }
        int close = matching;
        String head = ddl.substring(0, open + 1);
        String body = ddl.substring(open + 1, close);
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char ch : body.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        for (int i = 0; i < parts.size(); i++) {
            // 去掉整行注释后再认列名（旧 schema 的列定义后面常跟 -- 注释）
            String line = parts.get(i).replaceAll("(?m)--.*$", "");
            String trimmed = line.trim();
            int end = 0;
            while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end)) && trimmed.charAt(end) != '(') {
                end++;
            }
            String first = trimmed.substring(0, end);
            if (!first.isEmpty() && first.equalsIgnoreCase(column)) {
                parts.set(i, " " + definition + " /* " + column + " */ ");
                break;
            }
        }
        return head + String.join(",", parts) + ddl.substring(close);
    }

    /**
     * 次卡类型适用服务：service_ids 改存美容单项 id。旧 service_item id 与新 id 空间不同，
     * 逐个 id 无法可靠映射，迁移时统一清空（= 不限服务），由店长在「卡类型」页重新勾选。
     */
    private void migrateCardTypeServiceIds() {
        if (!tableExists("card_type") || !columnExists("card_type", "service_ids")) {
            return;
        }
        int n = jdbc.update("UPDATE card_type SET service_ids = '' WHERE service_ids IS NOT NULL AND service_ids <> ''");
        if (n > 0) {
            log.info("v1.4 迁移：{} 个次卡类型的适用服务已清空（旧服务项目 id 不沿用），请到卡类型页重新勾选", n);
        }
    }

    /**
     * 提成规则：SERVICE 的 ref_id 改指美容单项。指定了旧项目的规则无从映射，置为不限服务（ref_id 为空），
     * 避免误把同号的美容单项当成提成对象；不限服务的规则不变。
     */
    private void migrateServiceCommissionRules() {
        if (!tableExists("commission_rule")) {
            return;
        }
        int n = jdbc.update("UPDATE commission_rule SET ref_id = NULL WHERE scope = 'SERVICE' AND ref_id IS NOT NULL");
        if (n > 0) {
            log.info("v1.4 迁移：{} 条指定服务的提成规则已改为不限服务，如需针对美容单项请在提成规则页重设", n);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = false;
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(" + table + ")")) {
            if (column.equals(col.get("name"))) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("v1.2 迁移：{}.{} 已增列", table, column);
        }
    }

    /** 示例数据（app.sample-data=true 且库为空时灌入），演示/验收用。 */
    private void seedSampleData() {
        Integer customers = jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class);
        Integer categories = jdbc.queryForObject("SELECT COUNT(*) FROM service_category", Integer.class);
        if (customers != null && customers > 0 && categories != null && categories > 0) {
            return; // 已有数据不重复灌
        }
        log.info("sample-data=true：灌入示例数据…");
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // 客户 1 名 + 宠物 1 只
        jdbc.update("INSERT INTO customer(name, phone, source, remark, is_active, created_at) " +
                "VALUES('演示客户', '13800009999', '示例数据', 'app.sample-data=true 自动创建', 1, ?)", now);
        jdbc.update("INSERT INTO pet(owner_id, name, species, breed, gender, birthday, weight_g, " +
                "is_neutered, color, habit, is_active, created_at) " +
                "SELECT id, '豆豆', 'DOG', '金毛', 'MALE', '2023-03-15', 12500, 1, '白色', '怕吹风机', 1, ? " +
                "FROM customer WHERE phone = '13800009999'", now);
        seedPriceSheet();
        // 笼位 4 个
        for (String[] r : new String[][]{{"R01", "M"}, {"R02", "L"}, {"R03", "XL"}, {"C01", "CAT"}}) {
            jdbc.update("INSERT OR IGNORE INTO boarding_room(code, room_type, daily_price_cent, " +
                    "is_active, created_at) VALUES(?, ?, ?, 1, ?)",
                    r[0], r[1], "M".equals(r[1]) ? 6000 : "L".equals(r[1]) ? 8000
                            : "XL".equals(r[1]) ? 10000 : 5000, now);
        }
        log.info("示例数据灌入完成");
    }

    /** 示例价目：服务分类 + 价目行 + 价格格子 + 美容单项（已存在同名分类则跳过整块，避免重复灌）。 */
    private void seedPriceSheet() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_category WHERE name = '精洗'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        Long shower = category("精洗", "SHOWER", 1);
        Long beauty = category("美容", "BEAUTY", 2);
        Long dogSmall = priceRow("DOG", "0-3kg（小型犬）", 1);
        Long dogMedium = priceRow("DOG", "3-10kg（中型犬）", 2);
        Long cat = priceRow("CAT", "0-5kg", 3);
        cell(dogSmall, shower, 5800);
        cell(dogMedium, shower, 8800);
        cell(cat, shower, 6800);
        cell(dogSmall, beauty, 12800);
        cell(dogMedium, beauty, 16800);
        cell(cat, beauty, 13800);
        beautyItem("剪指甲", beauty, 1500, 1);
        beautyItem("修脚底毛", beauty, 2000, 2);
        beautyItem("挤肛门腺", shower, 1000, 3);
    }

    private Long category(String name, String degree, int sortNo) {
        jdbc.update("INSERT INTO service_category(name, degree, sort_no, is_visible) VALUES(?, ?, ?, 1)",
                name, degree, sortNo);
        return jdbc.queryForObject("SELECT id FROM service_category WHERE name = ?", Long.class, name);
    }

    private Long priceRow(String animal, String description, int sortNo) {
        jdbc.update("INSERT INTO service_price_row(animal, description, sort_no) VALUES(?, ?, ?)",
                animal, description, sortNo);
        return jdbc.queryForObject("SELECT id FROM service_price_row WHERE description = ?",
                Long.class, description);
    }

    private void cell(Long rowId, Long categoryId, int priceCent) {
        jdbc.update("INSERT INTO service_price_cell(row_id, category_id, price_cent, discount_type, discount_rate) "
                + "VALUES(?, ?, ?, 'GROUP', 10)", rowId, categoryId, priceCent);
    }

    private void beautyItem(String name, Long categoryId, int retailPriceCent, int sortNo) {
        jdbc.update("INSERT INTO service_beauty_item(name, category_id, retail_price_cent, discount_type, "
                + "discount_rate, show_cashier, show_shop, earn_points, sort_no, is_active) "
                + "VALUES(?, ?, ?, 'GROUP', 10, 1, 1, 0, ?, 1)", name, categoryId, retailPriceCent, sortNo);
    }
}
