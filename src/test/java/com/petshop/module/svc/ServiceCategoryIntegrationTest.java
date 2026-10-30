package com.petshop.module.svc;

import com.petshop.common.SessionUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务分类（洗护美容价目）端到端：全部通过 HTTP 驱动，落库结果直接查表核对。
 * 覆盖：新增 / 改名 / 切换大类 / 隐藏 / 调序 / 删除（含引用拒绝）/ 非管理员写操作拒绝 / v1.3 迁移幂等。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/service-category-test.db",
        "app.data-dir=./target/service-category-data",
        "app.backup-dir=./target/service-category-data/backup"
})
@AutoConfigureMockMvc
class ServiceCategoryIntegrationTest {

    private static final String PREFIX = "T分类-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.petshop.config.DataInitializer dataInitializer;

    @AfterEach
    void cleanup() {
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

    private String page(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/services").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(String name) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM service_category WHERE name = ?", Long.class, name);
        return n == null ? 0 : n;
    }

    private long idOf(String name) {
        return jdbc.queryForObject("SELECT id FROM service_category WHERE name = ?", Long.class, name);
    }

    @Test
    void adminCreatesCategoryAndItAppearsOnPricePageBar() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                        .param("name", PREFIX + "精洗").param("degree", "SHOWER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        assertEquals(1, count(PREFIX + "精洗"), "分类应落库");
        String body = page(admin);
        assertTrue(body.contains(PREFIX + "精洗"), "分类条应展示新分类");
        assertTrue(body.contains("洗护类"), "分类条应展示服务大类");
    }

    @Test
    void renameAndSwitchDegreeShowNewValues() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "剪毛").param("degree", "SHOWER"));
        long id = idOf(PREFIX + "剪毛");

        mockMvc.perform(post("/services/categories/save").session(admin)
                        .param("id", String.valueOf(id))
                        .param("name", PREFIX + "剪毛造型").param("degree", "BEAUTY"))
                .andExpect(redirectedUrl("/services"));

        assertEquals(0, count(PREFIX + "剪毛"), "旧名称不应残留");
        assertEquals("BEAUTY", jdbc.queryForObject(
                "SELECT degree FROM service_category WHERE id = ?", String.class, id));
        String body = page(admin);
        assertTrue(body.contains(PREFIX + "剪毛造型"));
        assertTrue(body.contains("美容类"));
    }

    @Test
    void hiddenCategoryStillListedAndMarkedHidden() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "药浴").param("degree", "SHOWER"));
        long id = idOf(PREFIX + "药浴");

        mockMvc.perform(post("/services/categories/" + id + "/visibility").session(admin)
                        .param("visible", "false"))
                .andExpect(redirectedUrl("/services"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT is_visible FROM service_category WHERE id = ?", Integer.class, id));
        String body = page(admin);
        assertTrue(body.contains(PREFIX + "药浴"), "隐藏分类仍应出现在分类条");
        assertTrue(body.contains("隐藏"), "隐藏分类应标记为隐藏");

        // 再点一次恢复显示
        mockMvc.perform(post("/services/categories/" + id + "/visibility").session(admin)
                        .param("visible", "true"))
                .andExpect(redirectedUrl("/services"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT is_visible FROM service_category WHERE id = ?", Integer.class, id));
    }

    @Test
    void reorderChangesBarOrder() throws Exception {
        MockHttpSession admin = admin();
        for (String n : new String[]{"甲", "乙", "丙"}) {
            mockMvc.perform(post("/services/categories/save").session(admin)
                    .param("name", PREFIX + n).param("degree", "SHOWER"));
        }
        long b = idOf(PREFIX + "乙");
        long c = idOf(PREFIX + "丙");

        // 上移「丙」一位：与「乙」交换顺序
        mockMvc.perform(post("/services/categories/" + c + "/move").session(admin)
                        .param("dir", "up"))
                .andExpect(redirectedUrl("/services"));

        Integer sortB = jdbc.queryForObject("SELECT sort_no FROM service_category WHERE id = ?", Integer.class, b);
        Integer sortC = jdbc.queryForObject("SELECT sort_no FROM service_category WHERE id = ?", Integer.class, c);
        assertTrue(sortC < sortB, "上移后「丙」应排在「乙」之前");

        String body = page(admin);
        int ib = body.indexOf(">" + PREFIX + "乙<");
        int ic = body.indexOf(">" + PREFIX + "丙<");
        assertTrue(ic >= 0 && ib >= 0 && ic < ib, "页面分类条顺序应与排序号一致");
    }

    @Test
    void unreferencedCategoryCanBeDeleted() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "临时").param("degree", "BEAUTY"));
        long id = idOf(PREFIX + "临时");

        mockMvc.perform(post("/services/categories/" + id + "/delete").session(admin))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services"));

        assertEquals(0, count(PREFIX + "临时"), "无引用分类应被删除");
        assertFalse(page(admin).contains(PREFIX + "临时"));
    }

    @Test
    void staffCannotWrite() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "只读").param("degree", "SHOWER"));
        long id = idOf(PREFIX + "只读");
        MockHttpSession staff = staff();

        mockMvc.perform(post("/services/categories/save").session(staff)
                        .param("name", PREFIX + "店员建的").param("degree", "SHOWER"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/categories/save").session(staff)
                        .param("id", String.valueOf(id)).param("name", PREFIX + "被改名").param("degree", "BEAUTY"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/categories/" + id + "/visibility").session(staff)
                        .param("visible", "false"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/categories/" + id + "/move").session(staff).param("dir", "down"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/categories/" + id + "/delete").session(staff))
                .andExpect(status().is3xxRedirection());

        assertEquals(0, count(PREFIX + "店员建的"), "店员不能新增分类");
        assertEquals(1, count(PREFIX + "只读"), "店员不能改名");
        assertEquals("SHOWER", jdbc.queryForObject(
                "SELECT degree FROM service_category WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT is_visible FROM service_category WHERE id = ?", Integer.class, id));

        // 店员仍可只读查看页面，但看不到「修改」入口与任何写操作表单
        String body = page(staff);
        assertFalse(body.contains("href=\"#categoryAdmin\""), "店员不应看到分类修改入口");
        assertFalse(body.contains("/services/categories/"), "店员页面不应出现分类写操作表单");
    }

    @Test
    void unreferencedCategoryCanBeDeletedWhenPriceTablesExist() throws Exception {
        // 改价后价格格子正式建出（价目行／格子工单），验证「有引用时拒绝删除」
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "有格子").param("degree", "SHOWER"));
        long id = idOf(PREFIX + "有格子");
        jdbc.update("DELETE FROM service_price_row WHERE description = '分类占用测试'");
        jdbc.update("INSERT INTO service_price_row(animal, description, sort_no) VALUES('DOG', '分类占用测试', 999)");
        Long rowId = jdbc.queryForObject(
                "SELECT id FROM service_price_row WHERE description = '分类占用测试'", Long.class);
        try {
            jdbc.update("INSERT INTO service_price_cell(row_id, category_id, price_cent) VALUES(?, ?, 5000)", rowId, id);
            mockMvc.perform(post("/services/categories/" + id + "/delete").session(admin))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("errMsg",
                            "该服务分类已被价格格子或美容单项引用，不能删除，可改为隐藏"));
            assertEquals(1, count(PREFIX + "有格子"), "有价格格子的分类不能删除");

            jdbc.update("DELETE FROM service_price_cell WHERE category_id = ?", id);
            mockMvc.perform(post("/services/categories/" + id + "/delete").session(admin))
                    .andExpect(redirectedUrl("/services"));
            assertEquals(0, count(PREFIX + "有格子"), "引用清空后可删除");
        } finally {
            jdbc.update("DELETE FROM service_price_cell WHERE row_id = ?", rowId);
            jdbc.update("DELETE FROM service_price_row WHERE id = ?", rowId);
        }
    }

    @Test
    void categoryReferencedByBeautyItemCannotBeDeleted() throws Exception {
        MockHttpSession admin = admin();
        mockMvc.perform(post("/services/categories/save").session(admin)
                .param("name", PREFIX + "有单项").param("degree", "BEAUTY"));
        long id = idOf(PREFIX + "有单项");
        Long itemId = jdbc.queryForObject("INSERT INTO service_beauty_item(name, category_id, retail_price_cent) "
                + "VALUES(?, ?, 2000) RETURNING id", Long.class, PREFIX + "剪指甲", id);
        try {
            mockMvc.perform(post("/services/categories/" + id + "/delete").session(admin))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("errMsg",
                            "该服务分类已被价格格子或美容单项引用，不能删除，可改为隐藏"));
            assertEquals(1, count(PREFIX + "有单项"), "有美容单项的分类不能删除");

            jdbc.update("DELETE FROM service_beauty_item WHERE id = ?", itemId);
            itemId = null;
            mockMvc.perform(post("/services/categories/" + id + "/delete").session(admin))
                    .andExpect(redirectedUrl("/services"));
            assertEquals(0, count(PREFIX + "有单项"), "引用清空后可删除");
        } finally {
            if (itemId != null) {
                jdbc.update("DELETE FROM service_beauty_item WHERE id = ?", itemId);
            }
        }
    }

    @Test
    void v13MigrationIsIdempotent() throws Exception {
        Long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.3'", Long.class);
        dataInitializer.run(null);
        dataInitializer.run(null);
        Long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 'v1.3'", Long.class);
        assertEquals(before, after, "重复执行 v1.3 不应重复登记");
        assertEquals(1L, after, "v1.3 只登记一次");
    }
}
