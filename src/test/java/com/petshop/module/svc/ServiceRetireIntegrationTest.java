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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #7 旧服务项目退场端到端：示例数据 / 预约 → 到店 → 转开单预填美容单项 / 次卡类型适用美容单项与卡适用性校验 /
 * 旧路由下线 / 提成规则按美容单项命中 / 日结服务收入含两种来源。
 * 测试数据一律以「测退场-」开头，清理时按前缀删除。
 */
@SpringBootTest(properties = {
        "app.sample-data=true",
        "app.data-dir=./target/service-retire-data",
        "app.backup-dir=./target/service-retire-data/backup",
        "spring.datasource.url=jdbc:sqlite:file:./target/service-retire-data/pet.db"
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on"
})
@AutoConfigureMockMvc
class ServiceRetireIntegrationTest {

    private static final String P = "测退场-";

    @DynamicPropertySource
    static void freshDbBeforeStart(DynamicPropertyRegistry registry) throws IOException {
        Path data = Paths.get("./target/service-retire-data");
        if (Files.exists(data)) {
            try (Stream<Path> files = Files.walk(data)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignore) {
                    }
                });
            }
        }
        Files.createDirectories(data);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private MockHttpSession admin;
    private long cat;
    private long row;
    private long beauty;
    private long customer;
    private long pet;

    @BeforeEach
    void setUp() {
        admin = new MockHttpSession();
        admin.setAttribute("sessionUser", new SessionUser(1L, "admin", "店主", "ADMIN"));
        cleanup();

        cat = jdbc.queryForObject("INSERT INTO service_category(name, degree, sort_no, is_visible) "
                + "VALUES(?, 'SHOWER', 0, 1) RETURNING id", Long.class, P + "精洗");
        row = jdbc.queryForObject("INSERT INTO service_price_row(animal, description, sort_no) "
                + "VALUES('DOG', ?, 0) RETURNING id", Long.class, P + "0-3kg");
        jdbc.update("INSERT INTO service_price_cell(row_id, category_id, price_cent, discount_type, discount_rate) "
                + "VALUES(?, ?, 10000, 'FULL', 10)", row, cat);
        beauty = jdbc.queryForObject("INSERT INTO service_beauty_item(name, category_id, retail_price_cent, "
                + "discount_type, discount_rate, show_cashier, show_shop, earn_points, sort_no, is_active) "
                + "VALUES(?, ?, 2000, 'FULL', 10, 1, 1, 0, 0, 1) RETURNING id", Long.class, P + "剪指甲", cat);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        customer = jdbc.queryForObject("INSERT INTO customer(name, phone, is_active, created_at) "
                + "VALUES(?, '13922220001', 1, ?) RETURNING id", Long.class, P + "客户", ts);
        pet = jdbc.queryForObject("INSERT INTO pet(owner_id, name, species, is_active, created_at) "
                + "VALUES(?, ?, 'DOG', 1, ?) RETURNING id", Long.class, customer, P + "豆豆", ts);
    }

    @AfterEach
    void cleanup() {
        List<Long> custs = jdbc.queryForList("SELECT id FROM customer WHERE name LIKE ?", Long.class, P + "%");
        for (Long c : custs) {
            List<Long> bills = jdbc.queryForList("SELECT id FROM bill WHERE customer_id = ?", Long.class, c);
            for (Long b : bills) {
                jdbc.update("DELETE FROM commission_record WHERE bill_id = ?", b);
                jdbc.update("DELETE FROM card_consume_log WHERE bill_id = ?", b);
                jdbc.update("DELETE FROM bill_item WHERE bill_id = ?", b);
                jdbc.update("DELETE FROM bill WHERE id = ?", b);
            }
            jdbc.update("DELETE FROM appointment WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer_card WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM pet WHERE owner_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM card_type WHERE name LIKE ?", P + "%");
        jdbc.update("DELETE FROM commission_rule WHERE remark LIKE ?", P + "%");
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE ?)", P + "%");
        jdbc.update("DELETE FROM service_price_row WHERE description LIKE ?", P + "%");
        jdbc.update("DELETE FROM service_beauty_item WHERE name LIKE ?", P + "%");
        jdbc.update("DELETE FROM service_category WHERE name LIKE ?", P + "%");
    }

    // ------------------------------------------------------------ 示例数据与页面

    @Test
    void sampleDataFillsPricePageAndLegacyServiceRoutesAreGone() throws Exception {
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='service_item'", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM service_category", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM service_price_row", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM service_price_cell", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM service_beauty_item", Integer.class) > 0);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_group WHERE name = '散客组'", Integer.class));

        String page = mockMvc.perform(get("/services").session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("洗护美容价目"));
        assertTrue(page.contains("精洗"), "价目页应列出示例分类");
        assertTrue(page.contains("剪指甲"), "价目页应列出示例美容单项");
        assertFalse(page.contains("新增服务项目"), "旧「新增服务项目」区块应下线");
        assertFalse(page.contains("已配置项目"), "旧「已配置项目」区块应下线");
        assertTrue(page.contains("修改美容洗护分类"), "分类维护区块保留");
        assertTrue(page.contains("/services/discount-batch"), "批量设置折扣入口保留");

        // 旧服务 CRUD 路由已不存在
        int save = mockMvc.perform(post("/services/save").session(admin).param("name", "旧服务"))
                .andReturn().getResponse().getStatus();
        assertTrue(save == 404 || save == 405, "POST /services/save 应已下线，实际 " + save);
        int toggle = mockMvc.perform(post("/services/1/toggle").session(admin).param("active", "false"))
                .andReturn().getResponse().getStatus();
        assertTrue(toggle == 404 || toggle == 405, "POST /services/{id}/toggle 应已下线，实际 " + toggle);

        // 侧栏入口改名
        String billPage = mockMvc.perform(get("/bills/new").param("customerId", String.valueOf(customer))
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(billPage.contains("洗护美容价目"), "侧栏应显示「洗护美容价目」");
        assertFalse(billPage.contains(">服务项目<"), "侧栏不再有「服务项目」入口");
    }

    // ------------------------------------------------------------ 预约 → 到店 → 转开单

    @Test
    void appointmentWithBeautyItemPrefillsBillAfterArrival() throws Exception {
        String form = mockMvc.perform(get("/appointments/new").param("customerId", String.valueOf(customer))
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(form.contains(P + "剪指甲"), "预约服务下拉应列出美容单项");
        assertTrue(form.contains(P + "精洗"), "预约服务下拉应列出价目表服务分类项目");
        assertFalse(form.contains(P + "0-3kg"), "价目行（带体型档）本身仍不作为预约选项，只列服务分类");

        mockMvc.perform(post("/appointments/save").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("petId", String.valueOf(pet))
                        .param("serviceId", String.valueOf(beauty))
                        .param("appointDate", java.time.LocalDate.now().toString())
                        .param("slot", "10:00"))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> appt = jdbc.queryForMap(
                "SELECT * FROM appointment WHERE customer_id = ? ORDER BY id DESC LIMIT 1", customer);
        // 新库无 service_name_snapshot 列时，插入回退为不带快照的语句；有则写入名称快照
        Object savedService = appt.get("service_id");
        assertTrue(savedService instanceof String && String.valueOf(beauty).equals(savedService),
                "预约应保存美容单项 id（指向 id 的文本，旧 id 不沿用），实际：" + savedService);
        long apptId = ((Number) appt.get("id")).longValue();
        assertFalse(appt.containsKey("size_tier"), "新库 appointment 不再有 size_tier 列");

        mockMvc.perform(post("/appointments/" + apptId + "/arrive").session(admin))
                .andExpect(status().is3xxRedirection());

        String billPage = mockMvc.perform(get("/bills/new").param("customerId", String.valueOf(customer))
                        .param("appointmentId", String.valueOf(apptId)).session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(billPage.contains("apptServiceId\" value=\"" + beauty + "\""),
                "转开单页应预填预约对应的美容单项");
        assertTrue(billPage.contains("apptPetId\" value=\"" + pet + "\""), "转开单页应预填预约宠物");
    }

    @Test
    void appointmentWithBeautyItemInHiddenCategoryIsRejected() throws Exception {
        jdbc.update("UPDATE service_category SET is_visible = 0 WHERE id = ?", cat);

        org.springframework.test.web.servlet.MvcResult r = mockMvc.perform(post("/appointments/save").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("petId", String.valueOf(pet))
                        .param("serviceId", String.valueOf(beauty))
                        .param("appointDate", java.time.LocalDate.now().toString())
                        .param("slot", "10:00"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment WHERE customer_id = ?", Long.class, customer),
                "隐藏分类下的美容单项不得预约");
        Object err = r.getFlashMap().get("errMsg");
        assertTrue(err != null && err.toString().contains("隐藏"), "应提示分类已隐藏，实际：" + err);
    }

    @Test
    void appointmentWithCategorySavesAndPrefillsHintAfterArrival() throws Exception {
        mockMvc.perform(post("/appointments/save").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("petId", String.valueOf(pet))
                        .param("serviceId", String.valueOf(cat))
                        .param("serviceType", "CATEGORY")
                        .param("appointDate", java.time.LocalDate.now().toString())
                        .param("slot", "11:00"))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> appt = jdbc.queryForMap(
                "SELECT * FROM appointment WHERE customer_id = ? ORDER BY id DESC LIMIT 1", customer);
        assertEquals("CATEGORY", String.valueOf(appt.get("service_type")), "预约应记录来源为价目表分类");
        assertTrue(String.valueOf(cat).equals(String.valueOf(appt.get("service_id"))), "预约应保存服务分类 id");
        long apptId = ((Number) appt.get("id")).longValue();

        mockMvc.perform(post("/appointments/" + apptId + "/arrive").session(admin))
                .andExpect(status().is3xxRedirection());

        String billPage = mockMvc.perform(get("/bills/new").param("customerId", String.valueOf(customer))
                        .param("appointmentId", String.valueOf(apptId)).session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(billPage.contains("apptServiceType\" value=\"CATEGORY\""), "转开单页应带出预约来源类型");
        assertTrue(billPage.contains("apptPetId\" value=\"" + pet + "\""), "转开单页应预填预约宠物");
    }

    @Test
    void appointmentWithHiddenCategoryIsRejected() throws Exception {
        jdbc.update("UPDATE service_category SET is_visible = 0 WHERE id = ?", cat);
        org.springframework.test.web.servlet.MvcResult r = mockMvc.perform(post("/appointments/save").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("petId", String.valueOf(pet))
                        .param("serviceId", String.valueOf(cat))
                        .param("serviceType", "CATEGORY")
                        .param("appointDate", java.time.LocalDate.now().toString())
                        .param("slot", "11:30"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment WHERE customer_id = ?", Long.class, customer),
                "隐藏分类不得预约");
        Object err = r.getFlashMap().get("errMsg");
        assertTrue(err != null && err.toString().contains("隐藏"), "应提示分类已隐藏，实际：" + err);
    }

    // ------------------------------------------------------------ 次卡类型适用美容单项 + 卡适用性校验
    @Test
    void cardTypeScopedToBeautyItemAllowsOnlyThatItem() throws Exception {
        String typesPage = mockMvc.perform(get("/card-types").session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(typesPage.contains(P + "剪指甲"), "卡类型页适用服务应列出美容单项");

        mockMvc.perform(post("/card-types/save").session(admin)
                        .param("name", P + "剪指甲5次卡")
                        .param("serviceIds", String.valueOf(beauty))
                        .param("paidQty", "5")
                        .param("priceYuan", "100")
                        .param("validDays", "365"))
                .andExpect(status().is3xxRedirection());
        long cardType = jdbc.queryForObject(
                "SELECT id FROM card_type WHERE name = ?", Long.class, P + "剪指甲5次卡");
        assertEquals(String.valueOf(beauty), jdbc.queryForObject(
                "SELECT service_ids FROM card_type WHERE id = ?", String.class, cardType));
        String listed = mockMvc.perform(get("/card-types").session(admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(listed.contains(P + "剪指甲"), "适用服务应显示美容单项名称（而不是旧服务项目）");

        long card = sellCard(cardType);

        // 正向：美容单项行，卡配置了该单项 → 可抵扣
        long okBill = settle(items(item("SERVICE", beauty, pet, card, "BEAUTY", null)), "");
        Map<String, Object> okItem = jdbc.queryForMap(
                "SELECT * FROM bill_item WHERE bill_id = ?", okBill);
        assertEquals(1, ((Number) okItem.get("paid_by_card")).intValue());

        // 反向 1：同卡开价目格子行（配置了适用服务，与格子不匹配）→ 拒单
        String err = settleExpectError(items(item("SERVICE", row, pet, card, "PRICE", cat)), "");
        assertTrue(err.contains("该卡未配置适用服务") || err.contains("该卡不适用于此服务项目"),
                "配置了适用服务的卡不能抵扣价目格子行，实际：" + err);

        // 反向 2：未配置适用服务的卡（不限）可以抵扣价目格子行
        mockMvc.perform(post("/card-types/save").session(admin)
                        .param("name", P + "通用卡")
                        .param("paidQty", "5")
                        .param("priceYuan", "100")
                        .param("validDays", "365"))
                .andExpect(status().is3xxRedirection());
        long anyCard = sellCard(jdbc.queryForObject(
                "SELECT id FROM card_type WHERE name = ?", Long.class, P + "通用卡"));
        long anyBill = settle(items(item("SERVICE", row, pet, anyCard, "PRICE", cat)), "");
        assertEquals(1, ((Number) jdbc.queryForObject(
                "SELECT paid_by_card FROM bill_item WHERE bill_id = ?", Integer.class, anyBill)).intValue());
    }

    // ------------------------------------------------------------ 提成：SERVICE 规则按美容单项 id 命中

    @Test
    void commissionServiceRuleByBeautyItemIsMatchedOnBeautyLines() throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long staff = jdbc.queryForObject("INSERT INTO staff(name, role, is_active, created_at) "
                + "VALUES(?, 'GROOMER', 1, ?) RETURNING id", Long.class, P + "美容师", ts);
        jdbc.update("INSERT INTO commission_rule(scope, ref_id, ratio_bp, effective_from, remark, created_at) "
                + "VALUES('SERVICE', ?, 1000, '2000-01-01', ?, ?)", beauty, P + "规则", ts);

        long beautyBill = settle(items(item("SERVICE", beauty, pet, null, "BEAUTY", null)), "", staff);
        Map<String, Object> rec = jdbc.queryForMap(
                "SELECT * FROM commission_record WHERE bill_id = ?", beautyBill);
        assertEquals(2000, ((Number) rec.get("base_cent")).intValue());
        assertEquals(200, ((Number) rec.get("amount_cent")).intValue(), "美容单项行应按单项规则提成 10%");

        // 价目格子行（refId 是价目行 id）不命中「不限服务」以外的指定单项规则
        long priceBill = settle(items(item("SERVICE", row, pet, null, "PRICE", cat)), "", staff);
        java.util.List<Long> bases = jdbc.queryForList(
                "SELECT base_cent FROM commission_record WHERE bill_id = ?", Long.class, priceBill);
        assertFalse(bases.contains(2000L), "指定了美容单项的提成规则不应命中价目格子行（基数 100 元）");
        for (Long base : bases) {
            assertEquals(10000L, base.longValue(), "价目格子行只能命中「不限服务」规则，基数应为 100 元");
        }
    }

    // ------------------------------------------------------------ 报表：服务收入含两种来源

    @Test
    void dailyReportServiceIncomeCoversPriceCellAndBeautyItem() throws Exception {
        settle(items(item("SERVICE", row, pet, null, "PRICE", cat),
                item("SERVICE", beauty, pet, null, "BEAUTY", null)), "");

        String page = mockMvc.perform(get("/reports/daily").session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 价目格子 100 元 + 美容单项 20 元 = 120 元都应计入服务收入
        Integer todayService = jdbc.queryForObject(
                "SELECT IFNULL(SUM(i.amount_cent - i.discount_cent),0) FROM bill_item i JOIN bill b ON b.id=i.bill_id "
                        + "WHERE b.customer_id = ? AND b.status='SETTLED' AND i.item_type='SERVICE'",
                Integer.class, customer);
        assertEquals(12000, todayService.intValue());
        assertTrue(page.contains("120.00"), "日结页应显示 120.00 元服务收入");
    }

    // ------------------------------------------------------------ helpers

    private String item(String type, long refId, long petId, Long cardId, String source, Long categoryId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("itemType", type);
        m.put("refId", refId);
        m.put("petId", petId);
        m.put("cardId", cardId);
        m.put("qty", 1);
        if (source != null) {
            m.put("itemSource", source);
        }
        if (categoryId != null) {
            m.put("categoryId", categoryId);
        }
        return toJson(m);
    }

    private String items(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long settle(String itemsJson, String discountYuan) throws Exception {
        return settle(itemsJson, discountYuan, null);
    }

    private long settle(String itemsJson, String discountYuan, Long staffId) throws Exception {
        String json = staffId == null ? itemsJson
                : itemsJson.replace("\"qty\":1", "\"qty\":1,\"staffId\":" + staffId);
        mockMvc.perform(post("/bills/settle").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("itemsJson", json)
                        .param("discountYuan", discountYuan)
                        .param("payMethod", "CASH")
                        .param("requestId", java.util.UUID.randomUUID().toString()))
                .andExpect(status().is3xxRedirection());
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM bill WHERE customer_id = ? AND status = 'SETTLED'", Long.class, customer);
        assertNotNull(id);
        return id;
    }

    private String settleExpectError(String itemsJson, String discountYuan) throws Exception {
        org.springframework.test.web.servlet.MvcResult r = mockMvc.perform(post("/bills/settle").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("itemsJson", itemsJson)
                        .param("discountYuan", discountYuan)
                        .param("payMethod", "CASH")
                        .param("requestId", java.util.UUID.randomUUID().toString()))
                .andReturn();
        Object err = r.getFlashMap() == null ? null : r.getFlashMap().get("errMsg");
        return err == null ? "" : String.valueOf(err);
    }

    private long sellCard(long cardTypeId) throws Exception {
        mockMvc.perform(post("/cards/sell").session(admin)
                        .param("customerId", String.valueOf(customer))
                        .param("cardTypeId", String.valueOf(cardTypeId))
                        .param("receivedYuan", "100")
                        .param("payMethod", "CASH"))
                .andExpect(status().is3xxRedirection());
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM customer_card WHERE customer_id = ?", Long.class, customer);
    }
}
