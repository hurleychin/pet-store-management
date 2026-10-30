package com.petshop.module.svc;

import com.petshop.common.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 价目修改写入审计日志：改价 / 改折扣 / 增删改价目行、分类、美容单项 / 批量折扣，
 * 都在 audit_log 留一条记录，含 target_type、target_id、操作人，改前改后为 JSON。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/service-price-audit-test.db",
        "app.data-dir=./target/service-price-audit-data",
        "app.backup-dir=./target/service-price-audit-data/backup"
})
@AutoConfigureMockMvc
class ServicePriceAuditIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpSession adminSession;
    private long cat;
    private long rowId;
    private long marker;

    @BeforeEach
    void setUp() throws Exception {
        adminSession = new MockHttpSession();
        adminSession.setAttribute("sessionUser", new SessionUser(1L, "admin", "店主", "ADMIN"));
        jdbc.update("DELETE FROM service_price_cell WHERE category_id IN "
                + "(SELECT id FROM service_category WHERE name LIKE 'T审计-%')");
        jdbc.update("DELETE FROM service_category WHERE name LIKE 'T审计-%'");
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE 'T审计行-%')");
        jdbc.update("DELETE FROM service_price_row WHERE description LIKE 'T审计行-%'");
        jdbc.update("DELETE FROM service_beauty_item WHERE name LIKE 'T审计单项-%'");
        // 审计日志是只增不改的旁路记录，用例之间靠 id 水位区分（用例自己的建数据动作也算在内）
        marker = jdbc.queryForObject("SELECT IFNULL(MAX(id), 0) FROM audit_log", Long.class);
        cat = createCategory("T审计-精洗");
        rowId = createRow("T审计行-1", "DOG", 1);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM service_price_cell WHERE category_id IN "
                + "(SELECT id FROM service_category WHERE name LIKE 'T审计-%')");
        jdbc.update("DELETE FROM service_category WHERE name LIKE 'T审计-%'");
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE 'T审计行-%')");
        jdbc.update("DELETE FROM service_price_row WHERE description LIKE 'T审计行-%'");
        jdbc.update("DELETE FROM service_beauty_item WHERE name LIKE 'T审计单项-%'");
        jdbc.update("DELETE FROM audit_log WHERE id > ?", marker);
        // 审计是原始 JDBC 写入，不参与测试事务；每例自清，避免用弹跳的 id 作水位
        jdbc.execute("DELETE FROM sqlite_sequence WHERE name = 'audit_log'");
    }

    // ---------------------------------------------------------------- 价格格子改价 / 改折扣

    @Test
    void priceChangeRecordsBeforeAndAfterPriceAndDiscount() throws Exception {
        mockMvc.perform(post("/services/price-rows/" + rowId + "/cells/save").session(adminSession)
                        .param("categoryId", String.valueOf(cat)).param("priceYuan", "128")
                        .param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/services/price-rows/" + rowId + "/cells/save").session(adminSession)
                        .param("categoryId", String.valueOf(cat)).param("priceYuan", "99.5")
                        .param("discountType", "RATE").param("discountRate", "8.5"))
                .andExpect(status().is3xxRedirection());

        List<Map<String, Object>> logs = audit("SERVICE_PRICE_CELL_UPDATE", "SERVICE_PRICE_CELL", rowId);
        assertEquals(2, logs.size(), "每次保存格子都应留一条审计");
        Map<String, Object> log = logs.get(0); // id DESC：最后一次 99.5 / 8.5 折
        assertTrue(logs.get(1).get("before_json") == null, "首次定价没有改前值");
        assertEquals("店主", log.get("operator"));
        assertEquals(1L, ((Number) log.get("operator_id")).longValue());

        JsonNode before = JSON.readTree((String) log.get("before_json"));
        assertEquals(12800, before.get("priceCent").asInt(), "改前价格按分记录");
        assertEquals("FULL", before.get("discountType").asText());
        JsonNode after = JSON.readTree((String) log.get("after_json"));
        assertEquals(cat, after.get("categoryId").asLong());
        assertEquals(9950, after.get("priceCent").asInt(), "改后价格按分记录");
        assertEquals("RATE", after.get("discountType").asText());
        assertEquals(85, after.get("discountRate").asInt(), "折扣率按十分位整数记录，85=8.5折");

        // 可在审计查询页按操作筛选看到
        String page = mockMvc.perform(get("/system/audit").session(adminSession)
                        .param("action", "SERVICE_PRICE_CELL_UPDATE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("价格格子改价"), "审计页应把动作显示为中文");
    }

    // ---------------------------------------------------------------- 价目行

    @Test
    void rowCreateEditDeleteAreAudited() throws Exception {
        long created = jdbc.queryForObject(
                "SELECT id FROM service_price_row WHERE description = 'T审计行-1' ORDER BY id DESC LIMIT 1", Long.class);
        List<Map<String, Object>> creates = audit("SERVICE_PRICE_ROW_CREATE", "SERVICE_PRICE_ROW", created);
        assertEquals(1, creates.size());
        assertTrue(creates.get(0).get("before_json") == null);
        JsonNode after = JSON.readTree((String) creates.get(0).get("after_json"));
        assertEquals("DOG", after.get("animal").asText());
        assertEquals("T审计行-1", after.get("description").asText());

        mockMvc.perform(post("/services/price-rows/save").session(adminSession)
                        .param("id", String.valueOf(created)).param("animal", "CAT")
                        .param("description", "T审计行-改").param("sortNo", "3"))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> updates = audit("SERVICE_PRICE_ROW_UPDATE", "SERVICE_PRICE_ROW", created);
        assertEquals(1, updates.size());
        assertEquals("DOG", JSON.readTree((String) updates.get(0).get("before_json")).get("animal").asText());
        assertEquals("CAT", JSON.readTree((String) updates.get(0).get("after_json")).get("animal").asText());

        mockMvc.perform(post("/services/price-rows/" + created + "/delete").session(adminSession))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> deletes = audit("SERVICE_PRICE_ROW_DELETE", "SERVICE_PRICE_ROW", created);
        assertEquals(1, deletes.size());
        assertEquals("T审计行-改", JSON.readTree((String) deletes.get(0).get("before_json"))
                .get("description").asText());
        assertTrue(deletes.get(0).get("after_json") == null);
    }

    // ---------------------------------------------------------------- 服务分类

    @Test
    void categoryCreateEditVisibilityMoveDeleteAreAudited() throws Exception {
        List<Map<String, Object>> creates = audit("SERVICE_CATEGORY_CREATE", "SERVICE_CATEGORY", cat);
        assertEquals(1, creates.size());
        assertEquals("T审计-精洗", JSON.readTree((String) creates.get(0).get("after_json")).get("name").asText());

        mockMvc.perform(post("/services/categories/save").session(adminSession)
                        .param("id", String.valueOf(cat)).param("name", "T审计-改名").param("degree", "BEAUTY"))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> updates = audit("SERVICE_CATEGORY_UPDATE", "SERVICE_CATEGORY", cat);
        assertEquals(1, updates.size());
        JsonNode before = JSON.readTree((String) updates.get(0).get("before_json"));
        JsonNode after = JSON.readTree((String) updates.get(0).get("after_json"));
        assertEquals("T审计-精洗", before.get("name").asText());
        assertEquals("BEAUTY", after.get("degree").asText());

        mockMvc.perform(post("/services/categories/" + cat + "/visibility").session(adminSession)
                        .param("visible", "false"))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> vis = audit("SERVICE_CATEGORY_VISIBILITY", "SERVICE_CATEGORY", cat);
        assertEquals(1, vis.size());
        assertTrue(JSON.readTree((String) vis.get(0).get("before_json")).get("isVisible").asBoolean());
        assertFalse(JSON.readTree((String) vis.get(0).get("after_json")).get("isVisible").asBoolean());

        mockMvc.perform(post("/services/categories/" + cat + "/move").session(adminSession)
                        .param("dir", "up"))
                .andExpect(status().is3xxRedirection());
        assertEquals(1, audit("SERVICE_CATEGORY_MOVE", "SERVICE_CATEGORY", cat).size());

        mockMvc.perform(post("/services/categories/" + cat + "/delete").session(adminSession))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> deletes = audit("SERVICE_CATEGORY_DELETE", "SERVICE_CATEGORY", cat);
        assertEquals(1, deletes.size());
        assertEquals("T审计-改名", JSON.readTree((String) deletes.get(0).get("before_json")).get("name").asText());
    }

    // ---------------------------------------------------------------- 美容单项

    @Test
    void beautyItemCreateEditSwitchesDeleteAreAudited() throws Exception {
        mockMvc.perform(post("/services/beauty-items/add").session(adminSession)
                        .param("name", "T审计单项-剪指甲").param("categoryId", String.valueOf(cat))
                        .param("retailPriceYuan", "20").param("discountType", "FULL"))
                .andExpect(status().is3xxRedirection());
        long id = jdbc.queryForObject("SELECT id FROM service_beauty_item WHERE name = 'T审计单项-剪指甲'", Long.class);
        List<Map<String, Object>> creates = audit("SERVICE_BEAUTY_ITEM_CREATE", "SERVICE_BEAUTY_ITEM", id);
        assertEquals(1, creates.size());
        assertEquals(2000, JSON.readTree((String) creates.get(0).get("after_json")).get("retailPriceCent").asInt());

        mockMvc.perform(post("/services/beauty-items/" + id + "/save").session(adminSession)
                        .param("name", "T审计单项-剪指甲").param("categoryId", String.valueOf(cat))
                        .param("retailPriceYuan", "25").param("discountType", "RATE").param("discountRate", "9"))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> updates = audit("SERVICE_BEAUTY_ITEM_UPDATE", "SERVICE_BEAUTY_ITEM", id);
        assertEquals(1, updates.size());
        assertEquals(2000, JSON.readTree((String) updates.get(0).get("before_json")).get("retailPriceCent").asInt());
        JsonNode after = JSON.readTree((String) updates.get(0).get("after_json"));
        assertEquals(2500, after.get("retailPriceCent").asInt());
        assertEquals(90, after.get("discountRate").asInt(), "折扣率按十分位整数记录，90=9折");

        mockMvc.perform(post("/services/beauty-items/" + id + "/switches").session(adminSession)
                        .param("showCashier", "true").param("showShop", "false").param("earnPoints", "true"))
                .andExpect(status().is3xxRedirection());
        List<Map<String, Object>> switches = audit("SERVICE_BEAUTY_ITEM_SWITCHES", "SERVICE_BEAUTY_ITEM", id);
        assertEquals(1, switches.size());
        assertFalse(JSON.readTree((String) switches.get(0).get("before_json")).get("earnPoints").asBoolean());
        assertTrue(JSON.readTree((String) switches.get(0).get("after_json")).get("earnPoints").asBoolean());

        mockMvc.perform(post("/services/beauty-items/" + id + "/delete").session(adminSession))
                .andExpect(status().is3xxRedirection());
        assertEquals(1, audit("SERVICE_BEAUTY_ITEM_DELETE", "SERVICE_BEAUTY_ITEM", id).size());
    }

    @Test
    void batchAddBeautyItemsAuditsEachItem() throws Exception {
        mockMvc.perform(post("/services/beauty-items/batch").session(adminSession)
                        .param("categoryId", String.valueOf(cat)).param("discountType", "FULL")
                        .param("lines", "T审计单项-甲,10\nT审计单项-乙,15"))
                .andExpect(status().is3xxRedirection());
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM service_beauty_item WHERE name LIKE 'T审计单项-%' ORDER BY id", Long.class);
        assertEquals(2, ids.size());
        for (Long id : ids) {
            assertEquals(1, audit("SERVICE_BEAUTY_ITEM_CREATE", "SERVICE_BEAUTY_ITEM", id).size());
        }
    }

    // ---------------------------------------------------------------- 批量设置折扣

    @Test
    void batchDiscountAuditsEachChangedItemWithBeforeAndAfter() throws Exception {
        mockMvc.perform(post("/services/beauty-items/batch").session(adminSession)
                        .param("categoryId", String.valueOf(cat)).param("discountType", "FULL")
                        .param("lines", "T审计单项-甲,10\nT审计单项-乙,15"))
                .andExpect(status().is3xxRedirection());
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM service_beauty_item WHERE name LIKE 'T审计单项-%' ORDER BY id", Long.class);

        mockMvc.perform(post("/services/beauty-items/batch-discount").session(adminSession)
                        .param("ids", String.valueOf(ids.get(0))).param("ids", String.valueOf(ids.get(1)))
                        .param("discountType", "RATE").param("discountRate", "7")
                        .param("categoryId", String.valueOf(cat)))
                .andExpect(status().is3xxRedirection());

        // 选「每个被改项目一条」，便于按 target_id 追溯
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT * FROM audit_log WHERE id > ? AND action = 'SERVICE_BEAUTY_ITEM_BATCH_DISCOUNT' "
                        + "ORDER BY id", marker);
        assertEquals(2, logs.size(), "每个被改项目一条审计");
        for (Map<String, Object> log : logs) {
            assertEquals("SERVICE_BEAUTY_ITEM", log.get("target_type"));
            assertEquals("店主", log.get("operator"));
            long targetId = ((Number) log.get("target_id")).longValue();
            assertTrue(ids.contains(targetId));
            JsonNode before = JSON.readTree((String) log.get("before_json"));
            JsonNode after = JSON.readTree((String) log.get("after_json"));
            assertEquals("FULL", before.get("discountType").asText());
            assertEquals("RATE", after.get("discountType").asText());
            assertEquals(70, after.get("discountRate").asInt());
            assertNotNull(after.get("ids"), "应带本次批量涉及的项目 id 列表");
            assertEquals(2, after.get("ids").size());
        }
    }

    // ---------------------------------------------------------------- 审计失败不拖累业务

    @Test
    void auditFailureDoesNotFailPriceChange() throws Exception {
        // 现有 AuditService 吞掉写审计异常；用触发器模拟审计表写失败
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS t_audit_fail BEFORE INSERT ON audit_log "
                + "WHEN NEW.target_type = 'SERVICE_PRICE_CELL' "
                + "BEGIN SELECT RAISE(ABORT, 'T审计模拟失败'); END");
        try {
            mockMvc.perform(post("/services/price-rows/" + rowId + "/cells/save").session(adminSession)
                            .param("categoryId", String.valueOf(cat)).param("priceYuan", "66"))
                    .andExpect(status().is3xxRedirection());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t_audit_fail");
        }
        assertEquals(6600, jdbc.queryForObject(
                "SELECT price_cent FROM service_price_cell WHERE row_id = ? AND category_id = ?",
                Integer.class, rowId, cat), "审计写失败不应影响改价结果");
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> audit(String action, String targetType, long targetId) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE id > ? AND action = ? AND target_type = ? "
                + "AND target_id = ? ORDER BY id DESC", marker, action, targetType, targetId);
    }

    private long createCategory(String name) throws Exception {
        mockMvc.perform(post("/services/categories/save").session(adminSession)
                        .param("name", name).param("degree", "SHOWER"))
                .andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("SELECT id FROM service_category WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
    }

    private long createRow(String description, String animal, int sortNo) throws Exception {
        mockMvc.perform(post("/services/price-rows/save").session(adminSession)
                        .param("animal", animal).param("description", description)
                        .param("sortNo", String.valueOf(sortNo)))
                .andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("SELECT id FROM service_price_row WHERE description = ? ORDER BY id DESC LIMIT 1",
                Long.class, description);
    }
}
