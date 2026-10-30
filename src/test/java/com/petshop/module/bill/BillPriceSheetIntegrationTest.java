package com.petshop.module.bill;

import com.petshop.common.SessionUser;
import com.petshop.module.customer.Customer;
import com.petshop.module.customer.MemberGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开单接入价目表与美容单项（#6）端到端：全部经 HTTP 驱动开单，落库与详情页核对。
 * 测试数据一律以「测开单-」开头，清理时按前缀删除。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/bill-price-sheet-test.db",
        "app.data-dir=./target/bill-price-sheet-data",
        "app.backup-dir=./target/bill-price-sheet-data/backup"
})
@AutoConfigureMockMvc
class BillPriceSheetIntegrationTest {

    private static final String P = "测开单-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private MemberGroupService memberGroupService;

    private MockHttpSession admin;

    private long catWash;     // 可见分类
    private long catHidden;   // 隐藏分类
    private long rowDog;      // 价目行 0-3kg
    private long customer;    // 无会员组（散客组）
    private long member;      // 会员组 8 折
    private long pet;         // 散客的狗
    private long memberPet;   // 会员的狗

    @BeforeEach
    void setUp() {
        admin = new MockHttpSession();
        admin.setAttribute("sessionUser", new SessionUser(1L, "admin", "店主", "ADMIN"));
        cleanup();

        catWash = jdbc.queryForObject("INSERT INTO service_category(name, degree, sort_no, is_visible) "
                + "VALUES(?, 'SHOWER', 0, 1) RETURNING id", Long.class, P + "精洗");
        catHidden = jdbc.queryForObject("INSERT INTO service_category(name, degree, sort_no, is_visible) "
                + "VALUES(?, 'SHOWER', 0, 0) RETURNING id", Long.class, P + "下架");
        rowDog = jdbc.queryForObject("INSERT INTO service_price_row(animal, description, sort_no) "
                + "VALUES('DOG', ?, 0) RETURNING id", Long.class, P + "0-3kg");
        cell(rowDog, catWash, 10000, "GROUP", 10);
        cell(rowDog, catHidden, 6000, "FULL", 10);

        String ts = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Long vipGroup = jdbc.queryForObject("INSERT INTO member_group(name, discount_rate) "
                + "VALUES(?, 8) RETURNING id", Long.class, P + "VIP");
        customer = jdbc.queryForObject("INSERT INTO customer(name, phone, is_active, created_at) "
                + "VALUES(?, '13911110001', 1, ?) RETURNING id", Long.class, P + "散客", ts);
        member = jdbc.queryForObject("INSERT INTO customer(name, phone, is_active, created_at, member_group_id) "
                + "VALUES(?, '13911110002', 1, ?, ?) RETURNING id", Long.class, P + "会员", ts, vipGroup);
        pet = pet(customer);
        memberPet = pet(member);
    }

    @AfterEach
    void cleanup() {
        List<Long> custs = jdbc.queryForList("SELECT id FROM customer WHERE name LIKE ?", Long.class, P + "%");
        for (Long c : custs) {
            jdbc.update("DELETE FROM bill_item WHERE bill_id IN (SELECT id FROM bill WHERE customer_id = ?)", c);
            jdbc.update("DELETE FROM bill WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM pet WHERE owner_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM service_price_cell WHERE row_id IN "
                + "(SELECT id FROM service_price_row WHERE description LIKE ?)", P + "%");
        jdbc.update("DELETE FROM service_price_row WHERE description LIKE ?", P + "%");
        jdbc.update("DELETE FROM service_beauty_item WHERE name LIKE ?", P + "%");
        jdbc.update("DELETE FROM service_category WHERE name LIKE ?", P + "%");
        jdbc.update("DELETE FROM member_group WHERE name LIKE ?", P + "%");
    }

    // ------------------------------------------------------------ 切片：开单页不再展示旧服务项目，改为两类来源

    @Test
    void newBillPageShowsPriceRowsAndBeautyItemsInsteadOfOldServices() throws Exception {
        beautyItem(P + "剪指甲", catWash, 2000, "FULL", 10);

        String page = billPage(customer);

        assertTrue(page.contains(P + "精洗"), "服务区应列出可见分类");
        assertTrue(page.contains(P + "0-3kg"), "服务区应列出价目行");
        assertEquals(java.util.Collections.singletonList(catWash), sellableCategories(page, rowDog),
                "开单页只带出可见分类下已定价的格子，隐藏分类不可开单");
        assertTrue(page.contains(P + "剪指甲"), "服务区应列收银台可见的美容单项");
        assertTrue(!page.contains("按体型档计价"), "旧服务项目区应已下线");
    }

    // ------------------------------------------------------------ 切片：选价目格子开单

    @Test
    void settleWithPriceCellCarriesCellPrice() throws Exception {
        long billId = settle(customer, item("PRICE", rowDog, catWash, pet, null, 1), "0", "");

        Map<String, Object> line = line(billId);
        assertEquals("SERVICE", line.get("item_type"));
        assertEquals(rowDog, ((Number) line.get("ref_id")).longValue(), "明细应记录价目行");
        assertEquals("狗：" + P + "0-3kg · " + P + "精洗", line.get("name_snapshot"));
        assertEquals(10000, line.get("unit_price_cent"), "带出格子价格");
        assertEquals(10000, line.get("amount_cent"));
        assertEquals(0, line.get("line_discount_cent"), "散客组 10 折不产生行折扣");
        assertEquals("¥100.00", text("/bills/" + billId, "data-sum-received"));
    }

    // ------------------------------------------------------------ 切片：选美容单项开单

    @Test
    void settleWithBeautyItemCarriesRetailPrice() throws Exception {
        long nail = beautyItem(P + "剪指甲", catWash, 2000, "FULL", 10);

        long billId = settle(customer, item("BEAUTY", nail, null, pet, null, 1), "0", "");

        Map<String, Object> line = line(billId);
        assertEquals(nail, ((Number) line.get("ref_id")).longValue(), "明细应记录美容单项");
        assertEquals(P + "剪指甲", line.get("name_snapshot"));
        assertEquals(2000, line.get("unit_price_cent"), "带出零售价");
        assertEquals(0, line.get("line_discount_cent"));
    }

    // ------------------------------------------------------------ 切片：手工改价

    @Test
    void manualPriceOverrideIsTheDealPrice() throws Exception {
        BillForm.Item raw = item("PRICE", rowDog, catWash, pet, null, 1);
        raw.setUnitPriceCent(8800);

        long billId = settleItems(customer, Arrays.asList(raw), "0", "");

        assertEquals(8800, line(billId).get("unit_price_cent"), "保存改后的价格");
        assertEquals(1200, line(billId).get("line_discount_cent"), "手改价与价目的差额记为行折扣");
        assertEquals(8800, bill(billId).get("receivable_cent"));
    }

    // ------------------------------------------------------------ 切片：会员组折扣 / 散客组

    @Test
    void memberGroupRateAppliesToServiceLines() throws Exception {
        long billId = settle(member, item("PRICE", rowDog, catWash, memberPet, null, 1), "0", "");

        Map<String, Object> line = line(billId);
        assertEquals(8000, line.get("unit_price_cent"), "会员组 8 折后的成交价");
        assertEquals(2000, line.get("line_discount_cent"), "行折扣 = 标价 − 成交价");
        assertEquals(8000, line.get("amount_cent"));
        assertEquals(8000, bill(billId).get("receivable_cent"));
        assertEquals("¥80.00", text("/bills/" + billId, "data-sum-received"));
    }

    @Test
    void customerWithoutMemberGroupUsesWalkInGroupRate() {
        Customer walkIn = new Customer();
        walkIn.setId(customer);
        assertEquals(10, memberGroupService.rateOf(walkIn), "未设会员组按散客组（10 折）");
        Customer vip = new Customer();
        vip.setId(member);
        vip.setMemberGroupId(jdbc.queryForObject("SELECT id FROM member_group WHERE name = ?", Long.class, P + "VIP"));
        assertEquals(8, memberGroupService.rateOf(vip), "会员客户按会员组折扣率");
        Customer dangling = new Customer();
        dangling.setMemberGroupId(99999999L);
        assertEquals(10, memberGroupService.rateOf(dangling), "会员组不存在时回落散客组，不多打折");
    }

    // ------------------------------------------------------------ 切片：行折扣 + 整单折扣叠加

    @Test
    void lineDiscountAndBillDiscountAreStoredSeparatelyAndAddUp() throws Exception {
        // 价目格子 100 元，会员组 8 折 → 成交 80；整单折扣 5 元 → 应收 75
        long billId = settle(member, item("PRICE", rowDog, catWash, memberPet, null, 1), "5", "");

        Map<String, Object> line = line(billId);
        assertEquals(7500, line.get("amount_cent"), "行金额已扣整单分摊");
        assertEquals(2000, line.get("line_discount_cent"), "行折扣单独落库");
        assertEquals(500, line.get("discount_cent"), "整单折扣分摊单独落库");
        Map<String, Object> b = bill(billId);
        assertEquals(10000, b.get("total_cent"), "单据合计 = 标价合计");
        assertEquals(500, b.get("discount_cent"), "整单折扣记在单据上");
        assertEquals(7500, b.get("receivable_cent"), "应收 = Σ(标价 − 行折扣) − 整单折扣");

        String html = detailPage(billId);
        assertEquals("¥20.00", cellText(html, "data-line-discount"), "明细行显示行折扣");
        assertEquals("-¥20.00", cellText(html, "data-line-discount-total"), "汇总显示行折扣合计");
        assertEquals("-¥5.00", cellText(html, "data-sum-discount"), "汇总显示整单优惠，与行折扣分开");
        assertEquals("¥75.00", cellText(html, "data-sum-received"));
    }

    // ------------------------------------------------------------ 切片：隐藏分类 / 停用单项拒单

    @Test
    void hiddenCategoryCellIsRejected() throws Exception {
        MvcResult r = postSettle(customer, item("PRICE", rowDog, catHidden, pet, null, 1), "0", "");
        assertEquals(0L, bills(customer), "隐藏分类的格子不得落单");
        assertTrue(r.getFlashMap().get("errMsg") != null
                && r.getFlashMap().get("errMsg").toString().contains("隐藏"));
    }

    @Test
    void inactiveBeautyItemIsRejected() throws Exception {
        long nail = beautyItem(P + "停用项", catWash, 2000, "FULL", 10);
        jdbc.update("UPDATE service_beauty_item SET is_active = 0 WHERE id = ?", nail);
        MvcResult r = postSettle(customer, item("BEAUTY", nail, null, pet, null, 1), "0", "");
        assertEquals(0L, count("SELECT COUNT(*) FROM bill_item WHERE ref_id = ? AND item_type = 'SERVICE'", nail));
        assertTrue(r.getFlashMap().get("errMsg") != null
                && r.getFlashMap().get("errMsg").toString().contains("停用"));
    }

    // ------------------------------------------------------------ helpers

    /** 开单页第 rowId 行的可开单分类 id 列表（逗号分隔，来自 data-price-cells-<rowId>）。 */
    private java.util.List<Long> sellableCategories(String html, long rowId) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String x : board(html, "data-price-cells").split(",")) {
            if (!x.trim().isEmpty()) ids.add(Long.parseLong(x.trim()));
        }
        return ids;
    }

    private BillForm.Item item(String source, Long rowId, Long categoryId, Long petId, Integer priceCent, int qty) {
        BillForm.Item it = new BillForm.Item();
        it.setItemType("SERVICE");
        it.setItemSource(source);
        it.setRefId(rowId);
        it.setCategoryId(categoryId);
        it.setPetId(petId);
        it.setUnitPriceCent(priceCent);
        it.setQty(qty);
        return it;
    }

    private long settle(long customerId, BillForm.Item item, String discountYuan, String remark) throws Exception {
        return settleItems(customerId, Arrays.asList(item), discountYuan, remark);
    }

    private long settleItems(long customerId, List<BillForm.Item> items, String discountYuan,
                             String remark) throws Exception {
        MvcResult r = postSettle(customerId, items, discountYuan, remark);
        assertNull(r.getFlashMap().get("errMsg"), "开单不应报错：" + r.getFlashMap().get("errMsg"));
        String location = r.getResponse().getRedirectedUrl();
        assertTrue(location != null && location.startsWith("/bills/"), "结账成功应跳到单据详情：" + location);
        return Long.parseLong(location.substring("/bills/".length()));
    }

    private MvcResult postSettle(long customerId, BillForm.Item item, String discountYuan,
                                 String remark) throws Exception {
        return postSettle(customerId, Arrays.asList(item), discountYuan, remark);
    }

    private MvcResult postSettle(long customerId, List<BillForm.Item> items, String discountYuan,
                                 String remark) throws Exception {
        String json = objectMapper.writeValueAsString(items);
        return mockMvc.perform(post("/bills/settle").session(admin)
                        .param("customerId", String.valueOf(customerId))
                        .param("itemsJson", json)
                        .param("discountYuan", discountYuan)
                        .param("payMethod", "CASH")
                        .param("remark", remark))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private String billPage(long customerId) throws Exception {
        MvcResult r = mockMvc.perform(get("/bills/new").session(admin)
                        .param("customerId", String.valueOf(customerId)))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String detailPage(long billId) throws Exception {
        return mockMvc.perform(get("/bills/" + billId).session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 取 data-* 属性值（HTML 已反转义）。 */
    private static String board(String html, String attr) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(attr + "=\"([^\"]*)\"").matcher(html);
        assertTrue(m.find(), "页面应有 " + attr);
        return m.group(1).replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&#44;", ",").replace("&#x2C;", ",").replace("&#x2c;", ",");
    }

    private String text(String url, String marker) throws Exception {
        return cellText(detailPage(Long.parseLong(url.substring("/bills/".length()))), marker);
    }

    /** 取带 data-* 锚点的元素文本（去掉标签空白），用于断言页面上的金额展示。 */
    private static String cellText(String html, String marker) {
        int at = html.indexOf(marker);
        assertTrue(at >= 0, "详情页应有 " + marker);
        int start = html.lastIndexOf('<', at);
        int end = html.indexOf('>', at);
        int close = html.indexOf("</", end);
        return html.substring(end + 1, close).replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> line(long billId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bill_item WHERE bill_id = ?", billId);
        assertEquals(1, rows.size(), "本用例应只有一行明细");
        return rows.get(0);
    }

    private Map<String, Object> bill(long billId) {
        return jdbc.queryForMap("SELECT * FROM bill WHERE id = ?", billId);
    }

    private long pet(long ownerId) {
        return jdbc.queryForObject("INSERT INTO pet(owner_id, name, species, is_active, created_at) "
                + "VALUES(?, ?, 'DOG', 1, ?) RETURNING id", Long.class, ownerId, P + "豆豆",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private void cell(long rowId, long categoryId, int priceCent, String type, int rate) {
        jdbc.update("INSERT INTO service_price_cell(row_id, category_id, price_cent, discount_type, discount_rate) "
                + "VALUES(?, ?, ?, ?, ?)", rowId, categoryId, priceCent, type, rate);
    }

    private long beautyItem(String name, long categoryId, int priceCent, String type, int rate) {
        return jdbc.queryForObject("INSERT INTO service_beauty_item(name, category_id, retail_price_cent, "
                + "discount_type, discount_rate, show_cashier, is_active) VALUES(?, ?, ?, ?, ?, 1, 1) RETURNING id",
                Long.class, name, categoryId, priceCent, type, rate);
    }

    private long bills(long customerId) {
        return count("SELECT COUNT(*) FROM bill WHERE customer_id = ?", customerId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
