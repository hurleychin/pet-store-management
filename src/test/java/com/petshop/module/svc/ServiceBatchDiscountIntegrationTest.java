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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * 批量设置折扣端到端：从价目页进入批次页，按分类分页签列出美容单项，勾选后一次性下发服务折扣。
 * 测试专用名称一律以「测-」开头，清理时按前缀删除，不影响其他测试与种子数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/service-batch-discount-test.db",
        "app.data-dir=./target/service-batch-discount-data",
        "app.backup-dir=./target/service-batch-discount-data/backup"
})
@AutoConfigureMockMvc
class ServiceBatchDiscountIntegrationTest {

    private static final String PREFIX = "测-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long beautyCat;
    private long showerCat;

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        beautyCat = addCategory(PREFIX + "剪毛", "BEAUTY");
        showerCat = addCategory(PREFIX + "精洗", "SHOWER");
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

    private long addCategory(String name, String degree) throws Exception {
        mockMvc.perform(post("/services/categories/save").session(admin())
                .param("name", name).param("degree", degree));
        return jdbc.queryForObject("SELECT MAX(id) FROM service_category WHERE name = ?", Long.class, name);
    }

    private long addItem(String name, long categoryId, String discountType, String discountRate) throws Exception {
        mockMvc.perform(post("/services/beauty-items/add").session(admin())
                .param("name", PREFIX + name)
                .param("categoryId", String.valueOf(categoryId))
                .param("retailPriceYuan", "100")
                .param("discountType", discountType)
                .param("discountRate", discountRate));
        return jdbc.queryForObject("SELECT MAX(id) FROM service_beauty_item WHERE name = ?", Long.class, PREFIX + name);
    }

    private String batchPage(MockHttpSession session, Long categoryId) throws Exception {
        MockHttpServletRequestBuilder req = get("/services/discount-batch");
        if (categoryId != null) {
            req.param("categoryId", String.valueOf(categoryId));
        }
        return mockMvc.perform(req.session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String discountOf(String name) {
        return jdbc.queryForObject("SELECT discount_type || '/' || discount_rate FROM service_beauty_item WHERE name = ?",
                String.class, PREFIX + name);
    }

    @Test
    void pricePageLinksToBatchDiscount() throws Exception {
        String body = mockMvc.perform(get("/services").session(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("/services/discount-batch"), "价目页应提供进入批量设置折扣的入口");
    }

    @Test
    void batchPageListsItemsByCategoryTab() throws Exception {
        addItem("剪指甲", beautyCat, "FULL", "");
        addItem("修脚底毛", beautyCat, "FULL", "");
        addItem("洗耳朵", showerCat, "FULL", "");

        String body = batchPage(admin(), beautyCat);

        int tab = body.indexOf("测-剪毛");
        int row = body.indexOf("测-剪指甲");
        int other = body.indexOf("测-洗耳朵");
        assertTrue(tab >= 0, "应展示分类页签");
        assertTrue(row > tab, "所选分类下的美容单项应列在页签之后");
        assertTrue(body.contains("测-修脚底毛"), "所选分类下的全部美容单项都应列出");
        assertTrue(other < 0 || other < tab, "其他分类的美容单项不应出现在当前页签下");

        // 切到另一个分类页签
        String shower = batchPage(admin(), showerCat);
        assertTrue(shower.contains("测-洗耳朵"));
        assertTrue(shower.indexOf("测-剪指甲") < 0 || shower.indexOf("测-剪指甲") < shower.indexOf("测-剪毛"),
                "切换页签后只列出该分类的美容单项");
    }

    @Test
    void submitWithoutSelectionPromptsToTickItems() throws Exception {
        addItem("剪指甲", beautyCat, "FULL", "");

        mockMvc.perform(post("/services/beauty-items/batch-discount").session(admin())
                        .param("discountType", "RATE").param("discountRate", "8"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errMsg", "请先勾选项目"));

        assertEquals("FULL/10", discountOf("剪指甲"), "未勾选时不应改动任何项目");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_beauty_item WHERE name = ?", Long.class, PREFIX + "剪指甲"),
                "项目应仍然存在");
    }

    @Test
    void selectedItemsAllGetNewDiscountAndOthersStayUnchanged() throws Exception {
        long a = addItem("剪指甲", beautyCat, "FULL", "");
        long b = addItem("修脚底毛", beautyCat, "GROUP", "");
        addItem("剃毛", beautyCat, "FULL", "");

        mockMvc.perform(post("/services/beauty-items/batch-discount").session(admin())
                        .param("ids", String.valueOf(a))
                        .param("ids", String.valueOf(b))
                        .param("discountType", "RATE").param("discountRate", "8"))
                .andExpect(status().is3xxRedirection());

        assertEquals("RATE/80", discountOf("剪指甲"), "勾选的项目应更新为 8 折");
        assertEquals("RATE/80", discountOf("修脚底毛"), "勾选的项目应更新为 8 折");
        assertEquals("FULL/10", discountOf("剃毛"), "未勾选的项目折扣应保持不变");

        String body = batchPage(admin(), beautyCat);
        assertTrue(body.contains("测-剪指甲"));
    }

    @Test
    void everyDiscountOptionCanBeBatched() throws Exception {
        long id = addItem("剪指甲", beautyCat, "FULL", "");
        String[][] cases = {
                {"GROUP", "", "GROUP/10"},
                {"FULL", "", "FULL/10"},
                {"RATE", "9", "RATE/90"},
                {"RATE", "8", "RATE/80"},
                {"RATE", "7", "RATE/70"},
                {"RATE", "8.5", "RATE/85"},
        };
        for (String[] c : cases) {
            mockMvc.perform(post("/services/beauty-items/batch-discount").session(admin())
                            .param("ids", String.valueOf(id))
                            .param("discountType", c[0]).param("discountRate", c[1]))
                    .andExpect(status().is3xxRedirection());
            assertEquals(c[2], discountOf("剪指甲"), "批量下发 " + c[0] + "/" + c[1] + " 应正确落库");
        }

        String body = batchPage(admin(), beautyCat);
        for (String label : new String[]{"会员组折扣", "全额", "9 折", "8 折", "7 折", "自定义折"}) {
            assertTrue(body.contains(label), "批量修改折扣应提供选项「" + label + "」");
        }
    }

    @Test
    void invalidRateIsRejectedAndNothingChanges() throws Exception {
        long a = addItem("剪指甲", beautyCat, "FULL", "");
        long b = addItem("修脚底毛", beautyCat, "FULL", "");

        mockMvc.perform(post("/services/beauty-items/batch-discount").session(admin())
                        .param("ids", String.valueOf(a)).param("ids", String.valueOf(b))
                        .param("discountType", "RATE").param("discountRate", "12"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errMsg", "折扣率需大于 0 且不超过 10，最多一位小数"));

        assertEquals("FULL/10", discountOf("剪指甲"));
        assertEquals("FULL/10", discountOf("修脚底毛"));
    }

    @Test
    void staffCannotBatchDiscount() throws Exception {
        long id = addItem("剪指甲", beautyCat, "FULL", "");

        mockMvc.perform(post("/services/beauty-items/batch-discount").session(staff())
                        .param("ids", String.valueOf(id))
                        .param("discountType", "RATE").param("discountRate", "7"))
                .andExpect(status().is3xxRedirection());

        assertEquals("FULL/10", discountOf("剪指甲"), "店员不能批量改折扣");

        String body = batchPage(staff(), beautyCat);
        assertFalse(body.contains("/services/beauty-items/batch-discount"), "店员页面不应出现批量下发表单");
        assertFalse(body.contains("批量修改折扣"), "店员不应看到批量修改折扣入口");
    }
}
