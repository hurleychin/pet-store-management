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
}
