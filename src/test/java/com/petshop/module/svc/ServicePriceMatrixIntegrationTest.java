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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 价目表（洗护美容价目）端到端：通过 HTTP 驱动价目行与价格格子，落库结果与页面展示核对。
 * 覆盖：新增行按排序号出现 / 编辑描述标签排序 / 删除行连带删格子 / 价格按分存、按元显示 /
 * 格子服务折扣（会员组、全额、9-8-7 折、自定义）/ 隐藏分类仍为列且标不可开单 / 非管理员写操作拒绝。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/service-price-matrix-test.db",
        "app.data-dir=./target/service-price-matrix-data",
        "app.backup-dir=./target/service-price-matrix-data/backup"
})
@AutoConfigureMockMvc
class ServicePriceMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** 同一个会话贯穿用例：MockMvc 新会话在首个请求时会重建 session，不能跨请求复用。 */
    private MockHttpSession adminSession;
    private MockHttpSession staffSession;

    private long catA;
    private long catB;
    private long catHidden;

    @BeforeEach
    void setUp() throws Exception {
        adminSession = admin();
        staffSession = staff();
        // 本地跑失败的旧库可能残留价目行：清掉上次未清理的数据
        for (String n : new String[]{"T价目-精洗", "T价目-剪毛", "T价目-药浴"}) {
            jdbc.update("DELETE FROM service_price_cell WHERE category_id IN "
                    + "(SELECT id FROM service_category WHERE name = ?)", n);
            jdbc.update("DELETE FROM service_category WHERE name = ?", n);
        }
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description IN "
                + "('0-3kg','5kg 短毛','1-5kg','T行-价','T行-空格','T行-折','T行-非法折','只读行'))");
        jdbc.update("DELETE FROM service_price_row WHERE description = '0-3kg'");
        catA = createCategory("T价目-精洗");
        catB = createCategory("T价目-剪毛");
        catHidden = createCategory("T价目-药浴");
        mockMvc.perform(post("/services/categories/" + catHidden + "/visibility").session(adminSession)
                .param("visible", "false"));
    }

    @AfterEach
    void cleanup() {
        for (String n : new String[]{"T价目-精洗", "T价目-剪毛", "T价目-药浴"}) {
            jdbc.update("DELETE FROM service_price_cell WHERE category_id IN "
                    + "(SELECT id FROM service_category WHERE name = ?)", n);
            jdbc.update("DELETE FROM service_category WHERE name = ?", n);
        }
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE 'T行-%')");
        jdbc.update("DELETE FROM service_price_row WHERE description IN "
                + "('0-3kg','5kg 短毛','1-5kg','T行-价','T行-空格','T行-折','T行-非法折','只读行')");
    }

    // ---------------------------------------------------------------- 切片 1：新增价目行

    @Test
    void newRowAppearsInTableOrderedBySortNo() throws Exception {
        row("5kg 短毛", "CAT", 2);
        row("0-3kg", "DOG", 1);

        String body = rowSummary(page(adminSession));
        int first = body.indexOf("狗：0-3kg");
        int second = body.indexOf("猫：5kg 短毛");
        assertTrue(first >= 0, "页面应出现「狗：0-3kg」");
        assertTrue(second >= 0, "页面应出现「猫：5kg 短毛」");
        assertTrue(first < second, "排序号小的价目行在前");
    }

    // ---------------------------------------------------------------- 切片 2：编辑

    @Test
    void editRowDescriptionAndTagsShowsChange() throws Exception {
        long id = row("0-3kg", "DOG", 1);

        mockMvc.perform(post("/services/price-rows/save").session(adminSession)
                        .param("id", String.valueOf(id))
                        .param("animal", "CAT").param("description", "0-4kg")
                        .param("dogSize", "S,M").param("furLength", "短毛").param("breedTags", "英短,布偶")
                        .param("sortNo", "5"))
                .andExpect(redirectedUrl("/services"));

        // 只看该行编辑弹窗，避免命中表格上方「新增价目行」表单的默认值
        String editor = page(adminSession);
        java.util.regex.Matcher row = java.util.regex.Pattern
                .compile("id=\"row-edit-" + id + "\".*?</dialog>", java.util.regex.Pattern.DOTALL).matcher(editor);
        assertTrue(row.find(), "编辑弹窗应渲染该价目行");
        String dialog = row.group();
        assertTrue(dialog.contains("<option value=\"CAT\" selected=\"selected\">猫</option>"), "宠物类型应反映新值");
        assertTrue(dialog.contains("<input name=\"description\" required value=\"0-4kg\">"), "描述应反映新值");
        assertTrue(dialog.contains("<input name=\"dogSize\" value=\"S,M\""), "肩高分段标签应展示");
        assertTrue(dialog.contains("<input name=\"furLength\" value=\"短毛\""), "毛长标签应展示");
        assertTrue(dialog.contains("<input name=\"breedTags\" value=\"英短,布偶\""), "品种标签应展示");
        assertEquals("S,M", jdbc.queryForObject(
                "SELECT dog_size FROM service_price_row WHERE id = ?", String.class, id), "肩高分段标签应保存");
        assertEquals("英短,布偶", jdbc.queryForObject(
                "SELECT breed_tags FROM service_price_row WHERE id = ?", String.class, id), "品种标签应保存");
        assertEquals(5, jdbc.queryForObject(
                "SELECT sort_no FROM service_price_row WHERE id = ?", Integer.class, id));
    }

    // ---------------------------------------------------------------- 切片 3：删除连带格子

    @Test
    void deleteRowAlsoDeletesItsCells() throws Exception {
        long id = row("1-5kg", "DOG", 1);
        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(adminSession)
                .param("categoryId", String.valueOf(catA)).param("priceYuan", "88"));
        assertEquals(1L, cells(id), "改价后应有一个格子");

        mockMvc.perform(post("/services/price-rows/" + id + "/delete").session(adminSession))
                .andExpect(redirectedUrl("/services"));

        assertEquals(0L, count("SELECT COUNT(*) FROM service_price_row WHERE id = ?", id));
        assertEquals(0L, cells(id), "删除价目行时价格格子应一并删除");
        assertFalse(rowSummary(page(adminSession)).contains("狗：1-5kg"));
    }

    // ---------------------------------------------------------------- 切片 4：价格分存储、元显示

    @Test
    void cellPriceStoredInCentAndShownInYuan() throws Exception {
        long id = row("T行-价", "DOG", 1);

        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(adminSession)
                        .param("categoryId", String.valueOf(catA)).param("priceYuan", "128.50"))
                .andExpect(redirectedUrl("/services"));

        assertEquals(12850, jdbc.queryForObject(
                "SELECT price_cent FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                Integer.class, id, catA));
        String body = page(adminSession);
        String priceCell = fragmentContaining(body, "data-cell-price", "128.50");

        assertTrue(priceCell != null, "页面按元显示格子价格 128.50");
        assertFalse(priceCell.contains("12850"), "格子展示的是元，不是分");

        // 再次改价是修改同一格子，不新增
        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(adminSession)
                .param("categoryId", String.valueOf(catA)).param("priceYuan", "99"));
        assertEquals(1L, cells(id));
        assertEquals(9900, jdbc.queryForObject(
                "SELECT price_cent FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                Integer.class, id, catA));

        // 空价格 = 未定价，显示「—」
        long emptyRow = row("T行-空格", "CAT", 2);
        String emptyHtml = page(adminSession);
        int emptyRowAt = emptyHtml.indexOf("T行-空格</b>");
        String emptyCells = emptyHtml.substring(emptyRowAt, emptyHtml.indexOf("</tr>", emptyRowAt));
        assertTrue(emptyCells.contains("data-cell-price"), "空行也应渲染价格格子");
        assertEquals(0L, cells(emptyRow));
    }

    // ---------------------------------------------------------------- 切片 5：服务折扣

    @Test
    void cellKeepsServiceDiscount() throws Exception {
        long id = row("T行-折", "DOG", 1);

        assertDiscount(id, catA, "GROUP", "10", null, null);
        assertDiscount(id, catA, "FULL", "10", "全额", null);
        assertDiscount(id, catA, "RATE", "9", "9折", null);
        assertDiscount(id, catA, "RATE", "8", "8折", null);
        assertDiscount(id, catA, "RATE", "7", "7折", null);
        assertDiscount(id, catA, "RATE", "8.5", "8.5折", "8.5折");
        assertEquals(1L, cells(id), "同一格子反复设置折扣只更新不新增");
    }

    private void assertDiscount(long rowId, long categoryId, String type, String rate,
                                String shown, String customShown) throws Exception {
        mockMvc.perform(post("/services/price-rows/" + rowId + "/cells/save").session(adminSession)
                        .param("categoryId", String.valueOf(categoryId)).param("priceYuan", "100")
                        .param("discountType", type).param("discountRate", rate))
                .andExpect(redirectedUrl("/services"));
        assertEquals(type, jdbc.queryForObject(
                "SELECT discount_type FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                String.class, rowId, categoryId));
        // 十分位整数：8.5 折存 85，与美容单项口径一致；GROUP / FULL 统一存 10（全额）
        int expectedRate = ("GROUP".equals(type) || "FULL".equals(type))
                ? 10 : new java.math.BigDecimal(rate).movePointRight(1).intValueExact();
        int stored = jdbc.queryForObject(
                "SELECT discount_rate FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                Integer.class, rowId, categoryId);
        assertEquals(expectedRate, stored, "折扣率应按十分位整数精确保存");
        if (shown != null) {
            assertTrue(page(adminSession).contains(shown), "页面应显示服务折扣「" + shown + "」");
        }
        if (customShown != null) {
            assertTrue(page(adminSession).contains(customShown), "自定义折扣应显示为「" + customShown + "」");
        }
    }

    @Test
    void invalidDiscountIsRejected() throws Exception {
        long id = row("T行-非法折", "DOG", 1);
        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(adminSession)
                        .param("categoryId", String.valueOf(catA)).param("priceYuan", "100")
                        .param("discountType", "RATE").param("discountRate", "0"));
        assertEquals(0L, cells(id), "折扣率超出 (0,10] 不应保存");
    }

    // ---------------------------------------------------------------- 切片 6：隐藏分类列

    @Test
    void hiddenCategoryStillColumnButMarkedNotSellable() throws Exception {
        String body = page(adminSession);
        assertTrue(body.contains("T价目-药浴"), "隐藏分类仍应作为价目表列出现");
        assertTrue(body.contains("不可开单"), "隐藏分类列应标记不可开单");
        assertTrue(body.contains("T价目-精洗") && body.contains("T价目-剪毛"));
    }

    // ---------------------------------------------------------------- 切片 7：权限

    @Test
    void staffCannotWriteRowsOrCells() throws Exception {
        long id = row("只读行", "DOG", 1);
        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(adminSession)
                .param("categoryId", String.valueOf(catA)).param("priceYuan", "50"));
        MockHttpSession staff = staffSession;

        mockMvc.perform(post("/services/price-rows/save").session(staff)
                        .param("animal", "DOG").param("description", "1-5kg").param("sortNo", "9"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/price-rows/save").session(staff)
                        .param("id", String.valueOf(id)).param("animal", "CAT")
                        .param("description", "被改名").param("sortNo", "9"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/price-rows/" + id + "/cells/save").session(staff)
                        .param("categoryId", String.valueOf(catA)).param("priceYuan", "1"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/price-rows/" + id + "/delete").session(staff))
                .andExpect(status().is3xxRedirection());

        assertEquals(0L, count("SELECT COUNT(*) FROM service_price_row WHERE description LIKE 'T行-%' "
                + "AND description <> '只读行'"));
        String kept = null;
        for (String line : rowSummary(page(adminSession)).split(" @")) {
            if (line.startsWith("狗：只读行")) {
                kept = line;
            }
        }
        assertEquals("狗：只读行", kept, "价目行描述不应被店员修改");
        assertEquals(5000, jdbc.queryForObject(
                "SELECT price_cent FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                Integer.class, id, catA));

        String body = page(staff);
        assertFalse(body.contains("/services/price-rows/"), "店员页面不应出现价目写操作表单");
    }

    // ---------------------------------------------------------------- helpers

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

    /** 价目表逐行摘要：每行「显示名 + '|' + 标签文本」，便于按行断言展示内容。 */
    private static String rowSummary(String html) {
        StringBuilder sb = new StringBuilder();
        int m = html.indexOf("id=\"priceMatrix\"");
        int end = m < 0 ? -1 : html.indexOf("id=\"rowCreate\"", m);
        String body = end < 0 ? "" : html.substring(m, end);
        java.util.regex.Matcher r = java.util.regex.Pattern.compile("<td>\\s*<b>([^<]*)</b>(.*?)</td>", java.util.regex.Pattern.DOTALL)
                .matcher(body);
        while (r.find()) {
            String tags = r.group(2).replaceAll("(?s)<(dialog|form|button).*?</\\1>", " ")
                    .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            String label = r.group(1).replace("'", "");
            sb.append(label)
                    .append(tags.isEmpty() ? "" : "|" + tags).append(" @");
        }
        return sb.toString();
    }

    private long createCategory(String name) throws Exception {
        mockMvc.perform(post("/services/categories/save").session(adminSession)
                        .param("name", name).param("degree", "SHOWER"))
                .andExpect(redirectedUrl("/services"));
        return jdbc.queryForObject("SELECT id FROM service_category WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, name);
    }

    /** 新增价目行：自定义描述原样入库，行的显示名为「宠物类型：描述」。 */
    private long row(String description, String animal, int sortNo) throws Exception {
        mockMvc.perform(post("/services/price-rows/save").session(adminSession)
                        .param("animal", animal).param("description", description)
                        .param("sortNo", String.valueOf(sortNo)))
                .andExpect(redirectedUrl("/services"));
        return jdbc.queryForObject(
                "SELECT id FROM service_price_row WHERE description = ? ORDER BY id DESC LIMIT 1", Long.class, description);
    }

    /** 返回包含 marker 且闭合在本次标签内的元素文本（含内容），用于断言某格的展示。 */
    private static String fragmentContaining(String html, String marker, String text) {
        int at = html.indexOf(marker);
        while (at >= 0) {
            int start = html.lastIndexOf('<', at);
            int end = html.indexOf('>', start);
            String tag = html.substring(start + 1, html.indexOf(' ', Math.min(end, start + 64)) > 0
                    ? Math.min(html.indexOf(' ', start + 1), html.indexOf('>', start + 1)) : end);
            int close = html.indexOf("</" + tag + ">", end);
            String element = html.substring(start, close + tag.length() + 3);
            if (element.contains(text)) {
                return element;
            }
            at = html.indexOf(marker, at + 1);
        }
        return null;
    }

    private long cells(long rowId) {
        return count("SELECT COUNT(*) FROM service_price_cell WHERE row_id = ?", rowId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
