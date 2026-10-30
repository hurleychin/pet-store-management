package com.petshop.module.svc;

import com.petshop.common.SessionUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 美容单项端到端：通过 HTTP 新增 / 批量新增 / 编辑 / 删除 / 三个开关，页面呈现与落库结果核对。
 * 测试专用名称一律以「测-」开头，清理时按前缀删除，不影响其他测试与种子数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/service-beauty-item-test.db",
        "app.data-dir=./target/service-beauty-item-data",
        "app.backup-dir=./target/service-beauty-item-data/backup"
})
@AutoConfigureMockMvc
class ServiceBeautyItemIntegrationTest {

    private static final String PREFIX = "测-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.petshop.config.DataInitializer dataInitializer;

    private long showerCat;
    private long beautyCat;
    private long hiddenCat;

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        showerCat = addCategory(PREFIX + "精洗", "SHOWER", true);
        beautyCat = addCategory(PREFIX + "剪毛", "BEAUTY", true);
        hiddenCat = addCategory(PREFIX + "已下架", "SHOWER", false);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM service_beauty_item WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM service_price_row WHERE description LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM service_category WHERE name LIKE ?", PREFIX + "%");
    }

    private MockHttpSession admin() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("sessionUser", new SessionUser(1L, "admin", "店主", "ADMIN"));
        return session;
    }

    private MockHttpSession staff() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("sessionUser", new SessionUser(99L, "clerk", "测试店员", "STAFF"));
        return session;
    }

    private long addCategory(String name, String degree, boolean visible) throws Exception {
        mockMvc.perform(post("/services/categories/save").session(admin())
                .param("name", name).param("degree", degree));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM service_category WHERE name = ?", Long.class, name);
        if (!visible) {
            mockMvc.perform(post("/services/categories/" + id + "/visibility").session(admin())
                    .param("visible", "false"));
        }
        return id;
    }

    private String page(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/services").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(String name) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM service_beauty_item WHERE name = ?", Long.class, name);
        return n == null ? 0 : n;
    }

    private long idOf(String name) {
        return jdbc.queryForObject("SELECT MAX(id) FROM service_beauty_item WHERE name = ?", Long.class, name);
    }

    @Test
    void adminAddsBeautyItemAndItAppearsImmediately() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                        .param("name", PREFIX + "剪指甲")
                        .param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "28.50")
                        .param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        assertEquals(1, count(PREFIX + "剪指甲"), "美容单项应落库");
        assertEquals(2850, jdbc.queryForObject(
                "SELECT retail_price_cent FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "剪指甲"));

        String body = page(admin);
        assertTrue(body.contains(PREFIX + "剪指甲"), "清单应展示新增的美容单项");
        assertTrue(body.contains("28.50"), "清单应展示零售价");
    }
    @Test
    void batchAddCreatesEveryLine() throws Exception {
        MockHttpSession admin = admin();
        String lines = PREFIX + "修脚底毛,30\n\n" + PREFIX + "剃毛,120.5\n" + PREFIX + "洗耳朵,15";
        mockMvc.perform(post("/services/beauty-items/batch").session(admin)
                        .param("categoryId", String.valueOf(showerCat))
                        .param("discountType", "GROUP")
                        .param("lines", lines))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"))
                .andExpect(flash().attribute("okMsg", "已批量新增 3 个美容单项"));

        assertEquals(1, count(PREFIX + "修脚底毛"));
        assertEquals(1, count(PREFIX + "剃毛"));
        assertEquals(1, count(PREFIX + "洗耳朵"));
        assertEquals(12050, jdbc.queryForObject(
                "SELECT retail_price_cent FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "剃毛"));
        assertEquals(showerCat, jdbc.queryForObject(
                "SELECT category_id FROM service_beauty_item WHERE name = ?", Long.class, PREFIX + "洗耳朵"));

        String body = page(admin);
        for (String n : new String[]{"修脚底毛", "剃毛", "洗耳朵"}) {
            assertTrue(body.contains(PREFIX + n), "清单应展示批量新增的 " + n);
        }
    }
    @Test
    void editNameCategoryAndPriceShowsNewValues() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                .param("name", PREFIX + "剪指甲")
                .param("categoryId", String.valueOf(showerCat))
                .param("retailPriceYuan", "28")
                .param("discountType", "FULL"));
        long id = idOf(PREFIX + "剪指甲");

        mockMvc.perform(post("/services/beauty-items/" + id + "/save").session(admin)
                        .param("name", PREFIX + "剪指甲精修")
                        .param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "35.80")
                        .param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT name, category_id, retail_price_cent FROM service_beauty_item WHERE id = ?", id)) {
            assertEquals(PREFIX + "剪指甲精修", row.get("name"));
            assertEquals(beautyCat, ((Number) row.get("category_id")).longValue());
            assertEquals(3580, ((Number) row.get("retail_price_cent")).intValue());
        }
        String body = page(admin);
        assertTrue(body.contains(PREFIX + "剪指甲精修"));
        assertTrue(body.contains("35.80"), "清单应展示修改后的零售价");
        assertFalse(body.contains(PREFIX + "剪指甲<"), "旧名称不应再出现");
    }
    @Test
    void eachDiscountOptionIsSavedAndShown() throws Exception {
        MockHttpSession admin = admin();
        String[][] cases = {
                {"会员组", "GROUP", ""},
                {"全额", "FULL", ""},
                {"九折", "RATE", "9"},
                {"八折", "RATE", "8"},
                {"七折", "RATE", "7"},
                {"自定义", "RATE", "8.5"},
        };
        for (String[] c : cases) {
            mockMvc.perform(post("/services/beauty-items/add").session(admin)
                            .param("name", PREFIX + c[0])
                            .param("categoryId", String.valueOf(beautyCat))
                            .param("retailPriceYuan", "100")
                            .param("discountType", c[1])
                            .param("discountRate", c[2]))
                    .andExpect(redirectedUrl("/services"));
        }
        assertEquals("GROUP", jdbc.queryForObject(
                "SELECT discount_type FROM service_beauty_item WHERE name = ?", String.class, PREFIX + "会员组"));
        assertEquals("FULL", jdbc.queryForObject(
                "SELECT discount_type FROM service_beauty_item WHERE name = ?", String.class, PREFIX + "全额"));
        assertEquals(90, jdbc.queryForObject(
                "SELECT discount_rate FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "九折"));
        assertEquals(80, jdbc.queryForObject(
                "SELECT discount_rate FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "八折"));
        assertEquals(70, jdbc.queryForObject(
                "SELECT discount_rate FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "七折"));
        assertEquals(85, jdbc.queryForObject(
                "SELECT discount_rate FROM service_beauty_item WHERE name = ?", Integer.class, PREFIX + "自定义"));

        // 编辑时改折扣
        long id = idOf(PREFIX + "会员组");
        mockMvc.perform(post("/services/beauty-items/" + id + "/save").session(admin)
                        .param("name", PREFIX + "会员组")
                        .param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "100")
                        .param("discountType", "RATE").param("discountRate", "7"))
                .andExpect(redirectedUrl("/services"));
        assertEquals(70, jdbc.queryForObject(
                "SELECT discount_rate FROM service_beauty_item WHERE id = ?", Integer.class, id));

        String body = page(admin);
        for (String label : new String[]{"会员组折扣", "全额", "按折扣率"}) {
            assertTrue(body.contains(label), "清单或表单应展示折扣选项「" + label + "」");
        }
    }

    @Test
    void invalidDiscountIsRejectedAndNothingIsSaved() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                        .param("name", PREFIX + "超折扣")
                        .param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "100")
                        .param("discountType", "RATE").param("discountRate", "12"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errMsg", "折扣率需大于 0 且不超过 10，最多一位小数"));
        assertEquals(0, count(PREFIX + "超折扣"), "非法折扣率不应落库");
    }
    @Test
    void switchesAreSavedAndShownOnList() throws Exception {
        MockHttpSession admin = admin();
        String name = PREFIX + "开三关";
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                .param("name", name)
                .param("categoryId", String.valueOf(beautyCat))
                .param("retailPriceYuan", "20")
                .param("discountType", "FULL"));
        long id = idOf(name);
        // 默认：银开、微开、积关
        assertTrue("开,开,关".equals(switchStates(page(admin), name)), "默认开关应为 银开/微开/积关");

        mockMvc.perform(post("/services/beauty-items/" + id + "/switches").session(admin)
                        .param("showCashier", "false").param("showShop", "true").param("earnPoints", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        String body = page(admin);
        assertEquals("关,开,开", switchStates(body, name), "三个开关应保存并在清单上显示");
        assertEquals(0, jdbc.queryForObject(
                "SELECT show_cashier FROM service_beauty_item WHERE id = ?", Integer.class, id));

        mockMvc.perform(post("/services/beauty-items/" + id + "/switches").session(admin)
                        .param("earnPoints", "true"))
                .andExpect(redirectedUrl("/services"));
        assertEquals("关,关,开", switchStates(page(admin), name), "未勾选的开关应保存为关");
    }

    private String switchStates(String body, String name) {
        int start = body.indexOf(name);
        assertTrue(start >= 0, "清单中应有 " + name);
        String row = body.substring(start, body.indexOf("</tr>", start));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("data-on=\"(true|false)\"").matcher(row);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append(',');
            sb.append("true".equals(m.group(1)) ? "开" : "关");
        }
        return sb.toString();
    }

    @Test
    void migrationCreatesBeautyItemTableIdempotently() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS service_beauty_item");
        dataInitializer.run(null);
        dataInitializer.run(null);
        assertEquals(0, count(PREFIX + "不存在"), "v1.3 迁移后表应可用");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.3'", Long.class));
    }

    @Test
    void deletedItemNoLongerListed() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                .param("name", PREFIX + "待删除")
                .param("categoryId", String.valueOf(beautyCat))
                .param("retailPriceYuan", "20")
                .param("discountType", "FULL"));
        long id = idOf(PREFIX + "待删除");
        assertTrue(page(admin).contains(PREFIX + "待删除"));

        mockMvc.perform(post("/services/beauty-items/" + id + "/delete").session(admin))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        assertEquals(0, count(PREFIX + "待删除"), "美容单项应被删除");
        assertFalse(page(admin).contains(PREFIX + "待删除"), "删除后清单不应再显示该项目");
    }
    @Test
    void staffCannotWrite() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                .param("name", PREFIX + "只读")
                .param("categoryId", String.valueOf(beautyCat))
                .param("retailPriceYuan", "20")
                .param("discountType", "FULL"));
        long id = idOf(PREFIX + "只读");
        MockHttpSession staff = staff();

        mockMvc.perform(post("/services/beauty-items/add").session(staff)
                        .param("name", PREFIX + "店员建的").param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "1").param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/beauty-items/batch").session(staff)
                        .param("categoryId", String.valueOf(beautyCat))
                        .param("lines", PREFIX + "店员批量,1"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/beauty-items/" + id + "/save").session(staff)
                        .param("name", PREFIX + "被改名").param("categoryId", String.valueOf(beautyCat))
                        .param("retailPriceYuan", "1").param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/beauty-items/" + id + "/switches").session(staff)
                        .param("showCashier", "false"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/beauty-items/" + id + "/delete").session(staff))
                .andExpect(status().is3xxRedirection());

        assertEquals(0, count(PREFIX + "店员建的"), "店员不能新增");
        assertEquals(0, count(PREFIX + "店员批量"), "店员不能批量新增");
        assertEquals(1, count(PREFIX + "只读"), "店员不能改名或删除");
        assertEquals(1, jdbc.queryForObject(
                "SELECT show_cashier FROM service_beauty_item WHERE id = ?", Integer.class, id), "店员不能改开关");

        // 店员仍可只读查看清单，但页面上没有任何美容单项写操作入口
        String body = page(staff);
        assertTrue(body.contains(PREFIX + "只读"), "店员应能看到美容单项清单");
        assertFalse(body.contains("/services/beauty-items/"), "店员页面不应出现美容单项写操作表单");
        assertFalse(body.contains("新增美容单项"), "店员不应看到新增入口");
    }

    @Test
    void onlyVisibleCategoriesOfferedInForms() throws Exception {
        MockHttpSession admin = admin();
        String body = page(admin);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<option value=\"(\\d+)\"[^>]*>([^<]*)</option>").matcher(body);
        java.util.Map<String, String> options = new java.util.HashMap<>();
        while (m.find()) {
            options.put(m.group(1), m.group(2));
        }
        assertTrue(options.containsValue(PREFIX + "精洗"));
        assertTrue(options.containsValue(PREFIX + "剪毛"));
        assertFalse(options.containsValue(PREFIX + "已下架"), "隐藏分类不应出现在分类下拉中");

        // 直接提交隐藏分类也应被拒绝
        mockMvc.perform(post("/services/beauty-items/add").session(admin)
                        .param("name", PREFIX + "挂隐藏分类")
                        .param("categoryId", String.valueOf(hiddenCat))
                        .param("retailPriceYuan", "10")
                        .param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errMsg", "所选服务分类不存在或已隐藏，请重新选择"));
        assertEquals(0, count(PREFIX + "挂隐藏分类"));
    }

    // ---------------------------------------------------------------- v1.3 会员组 / 客户表单 / 迁移（#6）

    @Test
    void customerFormLetsAdminPickExistingMemberGroup() throws Exception {
        MockHttpSession admin = admin();
        long groupId = jdbc.queryForObject("INSERT INTO member_group(name, discount_rate) "
                + "VALUES(?, 8) RETURNING id", Long.class, PREFIX + "VIP组");
        String form;
        try {
            form = mockMvc.perform(get("/customers/new").session(admin))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(form.contains("name=\"memberGroupId\""), "客户表单应有会员组下拉");
            assertTrue(form.contains(PREFIX + "VIP组"), "下拉应列出已有会员组");
            assertTrue(form.contains("散客组"), "下拉应有散客组（不设会员组）");

            mockMvc.perform(post("/customers/save").session(admin)
                            .param("name", PREFIX + "会员客户").param("phone", "13700001234")
                            .param("memberGroupId", String.valueOf(groupId)))
                    .andExpect(status().is3xxRedirection());
            assertEquals(groupId, jdbc.queryForObject(
                    "SELECT member_group_id FROM customer WHERE phone = '13700001234'", Long.class));

            // 会员组不存在时拒绝保存，不落半截数据
            mockMvc.perform(post("/customers/save").session(admin)
                            .param("name", PREFIX + "错组客户").param("phone", "13700001235")
                            .param("memberGroupId", "99999999"))
                    .andExpect(flash().attribute("errMsg", "会员组不存在，请刷新后重新选择"));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM customer WHERE phone = '13700001235'", Long.class));
        } finally {
            jdbc.update("DELETE FROM customer WHERE phone IN ('13700001234','13700001235')");
            jdbc.update("DELETE FROM member_group WHERE id = ?", groupId);
        }
    }

    /** 模拟老库时用 fresh=true，避免 MyBatis 的本地缓存返回替换前的表结构结果。 */
    private boolean tableExists(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table) > 0;
    }

    private long insertRow(String description) throws Exception {
        mockMvc.perform(post("/services/price-rows/save").session(admin())
                        .param("animal", "DOG").param("description", description).param("sortNo", "0"))
                .andExpect(redirectedUrl("/services"));
        return jdbc.queryForObject("SELECT id FROM service_price_row WHERE description = ?", Long.class, description);
    }

    private boolean columnMissing(String table, String column) {
        for (java.util.Map<String, Object> col : jdbc.queryForList("PRAGMA table_info(" + table + ")")) {
            if (column.equals(col.get("name"))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void migrationAddsMemberGroupAndLineDiscountColumnsIdempotently() throws Exception {
        // 模拟 v1.3 之前的老库：缺会员组表、customer.member_group_id、bill_item.line_discount_cent。
        // 先把本用例依赖的价目数据挂到一个测试分类下，替换主表后找回。
        long cat = jdbc.queryForObject("INSERT INTO service_category(name, degree) VALUES(?, 'SHOWER') RETURNING id",
                Long.class, PREFIX + "迁移分类");
        long row = insertRow(PREFIX + "迁移行");
        int cellsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM service_price_cell", Integer.class) + 1;
        jdbc.update("INSERT INTO service_price_cell(row_id, category_id, price_cent) VALUES(?, ?, 1000)", row, cat);
        try {
        jdbc.execute("DROP TABLE IF EXISTS member_group");
        jdbc.execute("DROP TABLE IF EXISTS customer__old");
        jdbc.execute("CREATE TABLE customer__old AS SELECT id, name, phone, is_active, created_at FROM customer");
        jdbc.execute("DROP TABLE customer");
        jdbc.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "phone TEXT NOT NULL, source TEXT, remark TEXT, is_active INTEGER NOT NULL DEFAULT 1, "
                + "created_at TEXT NOT NULL)");
        jdbc.execute("INSERT INTO customer(id, name, phone, is_active, created_at) "
                + "SELECT id, name, phone, is_active, created_at FROM customer__old");
        jdbc.execute("DROP TABLE customer__old");
        jdbc.execute("ALTER TABLE service_price_cell RENAME TO service_price_cell__old");
        jdbc.execute("DROP TABLE IF EXISTS service_price_cell");
        jdbc.execute("DROP TABLE IF EXISTS bill_item__old");
        jdbc.execute("CREATE TABLE bill_item__old AS SELECT * FROM bill_item");
        jdbc.execute("DROP TABLE bill_item");
        jdbc.execute("CREATE TABLE bill_item (id INTEGER PRIMARY KEY AUTOINCREMENT, bill_id INTEGER NOT NULL, "
                + "item_type TEXT NOT NULL, ref_id INTEGER NOT NULL, name_snapshot TEXT NOT NULL, size_tier TEXT, "
                + "pet_id INTEGER, qty INTEGER NOT NULL DEFAULT 1, unit_price_cent INTEGER NOT NULL DEFAULT 0, "
                + "amount_cent INTEGER NOT NULL DEFAULT 0, discount_cent INTEGER NOT NULL DEFAULT 0, "
                + "paid_by_card INTEGER NOT NULL DEFAULT 0, card_id INTEGER, sort_no INTEGER NOT NULL DEFAULT 0)");
        // 只保留 v1.3 之前的公共列，模拟老库
        jdbc.execute("INSERT INTO bill_item(id, bill_id, item_type, ref_id, name_snapshot, size_tier, pet_id, qty, "
                + "unit_price_cent, amount_cent, discount_cent, paid_by_card, card_id, sort_no) "
                + "SELECT id, bill_id, item_type, ref_id, name_snapshot, size_tier, pet_id, qty, "
                + "unit_price_cent, amount_cent, discount_cent, paid_by_card, card_id, sort_no FROM bill_item__old");
        jdbc.execute("DROP TABLE bill_item__old");
        } finally {
            if (columnMissing("customer", "member_group_id")) {
                jdbc.execute("ALTER TABLE customer ADD COLUMN member_group_id INTEGER");
            }
            if (columnMissing("bill_item", "line_discount_cent")) {
                jdbc.execute("ALTER TABLE bill_item ADD COLUMN line_discount_cent INTEGER NOT NULL DEFAULT 0");
            }
            jdbc.execute("CREATE TABLE IF NOT EXISTS member_group (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, discount_rate INTEGER NOT NULL DEFAULT 10)");
            jdbc.update("INSERT OR IGNORE INTO member_group(id, name, discount_rate) VALUES (1, '散客组', 10)");
            if (tableExists("service_price_cell__old")) {
                jdbc.execute("DROP TABLE IF EXISTS service_price_cell");
                jdbc.execute("ALTER TABLE service_price_cell__old RENAME TO service_price_cell");
                assertEquals(cellsBefore, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM service_price_cell", Integer.class), "迁移不应改动既有格子");
            }
            jdbc.update("DELETE FROM service_price_cell WHERE category_id = ?", cat);
            jdbc.update("DELETE FROM service_price_row WHERE id = ?", row);
            jdbc.update("DELETE FROM service_category WHERE id = ?", cat);
        }

        dataInitializer.run(null);
        dataInitializer.run(null);

        assertEquals(10, jdbc.queryForObject(
                "SELECT discount_rate FROM member_group WHERE name = '散客组'", Integer.class), "预置散客组 10 折");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_group WHERE name = '散客组'", Long.class), "重复迁移不重复插入散客组");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer WHERE member_group_id IS NOT NULL", Long.class), "老客户默认散客组");
        assertEquals("0", jdbc.queryForObject(
                "SELECT IFNULL(MAX(line_discount_cent), '0') FROM bill_item", String.class), "老明细行折扣为 0");
    }
}
