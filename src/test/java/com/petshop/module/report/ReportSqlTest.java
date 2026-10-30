package com.petshop.module.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 报表口径回归测试：内存库跑真实 schema.sql + 真实 SQL。
 * 覆盖三处曾经的错误：成本被明细行放大、寄养收入未并入服务列、单量漏统计。
 */
public class ReportSqlTest {

    private Connection conn;

    /** 与 mapper/MonthlyReportMapper.xml#monthlyCost 保持一致 */
    private static final String MONTHLY_COST =
            "SELECT SUM(pl.unit_cost_cent * (-pl.qty_change)) AS productCost "
            + "FROM product_ledger pl JOIN bill b ON b.id = pl.ref_id "
            + "WHERE pl.ref_type = 'BILL' AND pl.biz_type = 'SALE' "
            + "  AND b.bill_type = 'SALE' AND b.status = 'SETTLED' "
            + "  AND b.business_date BETWEEN ? AND ?";

    /** 与 mapper/ReportMapper.xml#dailySummary 的服务收入列保持一致（服务折扣后的行金额，再扣整单分摊） */
    private static final String DAILY_SERVICE_INCOME =
            "SELECT IFNULL(SUM(i.amount_cent - i.discount_cent),0) FROM bill_item i "
            + "JOIN bill b ON b.id=i.bill_id "
            + "WHERE b.bill_type='SALE' AND b.status='SETTLED' AND b.business_date = ? "
            + "  AND i.item_type IN ('SERVICE','BOARDING') AND i.paid_by_card=0";

    /** 与 mapper/ReportMapper.xml#dailySummary 的商品收入列保持一致 */
    private static final String DAILY_PRODUCT_INCOME =
            "SELECT IFNULL(SUM(i.amount_cent - i.discount_cent - i.line_discount_cent),0) FROM bill_item i "
            + "JOIN bill b ON b.id=i.bill_id "
            + "WHERE b.bill_type='SALE' AND b.status='SETTLED' AND b.business_date = ? "
            + "  AND i.item_type='PRODUCT' AND i.paid_by_card=0";

    /** 与 mapper/ReportMapper.xml#dashboard 的今日收入保持一致 */
    private static final String TODAY_INCOME =
            "SELECT IFNULL(SUM(i.amount_cent - i.discount_cent - i.line_discount_cent),0) FROM bill_item i "
            + "JOIN bill b ON b.id=i.bill_id "
            + "WHERE b.bill_type='SALE' AND b.status='SETTLED' AND b.business_date = ? "
            + "  AND i.paid_by_card=0";

    /** 与 mapper/MonthlyReportMapper.xml#monthly 的单量子查询保持一致 */
    private static final String MONTHLY_BILL_COUNT =
            "SELECT COUNT(*) FROM bill WHERE bill_type='SALE' AND status='SETTLED' "
            + "AND business_date BETWEEN ? AND ?";

    /** 与 mapper/BillMapper.xml#cancel 保持一致 */
    private static final String CANCEL_BILL =
            "UPDATE bill SET status='CANCELLED', cancelled_at='2026-10-27 12:00:00', cancel_reason='test' "
            + "WHERE id = ? AND status = 'SETTLED'";

    @BeforeEach
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema/schema.sql"));
        seed();
    }

    @AfterEach
    public void tearDown() throws Exception {
        conn.close();
    }

    private void seed() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO customer(id,name,phone,is_active,created_at) "
                    + "VALUES(1,'张三','13800000001',1,'2026-10-27 10:00:00')");
            st.execute("INSERT INTO product(id,name,sale_price_cent,cost_price_cent,stock_qty,"
                    + "safety_qty,is_active,created_at) VALUES(1,'狗粮',1000,300,10,0,1,'2026-10-27 10:00:00')");
            // 单 1：同商品两行明细（旧 SQL 会把成本算两遍）
            st.execute("INSERT INTO bill(id,bill_no,bill_type,customer_id,status,total_cent,"
                    + "discount_cent,receivable_cent,received_cent,business_date,request_id,created_at) "
                    + "VALUES(1,'S202610240001','SALE',1,'SETTLED',5000,0,5000,5000,'2026-10-27','r1','2026-10-27 10:00:00')");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,paid_by_card,sort_no) "
                    + "VALUES(1,'PRODUCT',1,'狗粮',2,1000,2000,0,0,0)");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,paid_by_card,sort_no) "
                    + "VALUES(1,'PRODUCT',1,'狗粮',3,1000,3000,0,0,1)");
            st.execute("INSERT INTO product_ledger(product_id,biz_type,qty_change,before_qty,after_qty,"
                    + "unit_cost_cent,ref_type,ref_id,created_at) VALUES(1,'SALE',-2,10,8,300,'BILL',1,'2026-10-27 10:00:00')");
            st.execute("INSERT INTO product_ledger(product_id,biz_type,qty_change,before_qty,after_qty,"
                    + "unit_cost_cent,ref_type,ref_id,created_at) VALUES(1,'SALE',-3,8,5,300,'BILL',1,'2026-10-27 10:00:00')");
            // 单 2：寄养结算
            st.execute("INSERT INTO bill(id,bill_no,bill_type,customer_id,status,total_cent,"
                    + "discount_cent,receivable_cent,received_cent,business_date,request_id,created_at) "
                    + "VALUES(2,'S202610240002','SALE',1,'SETTLED',8000,0,8000,8000,'2026-10-27','BO-B1','2026-10-27 11:00:00')");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,paid_by_card,sort_no) "
                    + "VALUES(2,'BOARDING',1,'寄养',1,8000,8000,0,0,0)");
            // 单 3：纯服务单，用来验证单量不能只数含商品的单
            st.execute("INSERT INTO bill(id,bill_no,bill_type,customer_id,status,total_cent,"
                    + "discount_cent,receivable_cent,received_cent,business_date,request_id,created_at) "
                    + "VALUES(3,'S202610240003','SALE',1,'SETTLED',5000,0,5000,5000,'2026-10-27','r3','2026-10-27 12:00:00')");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,paid_by_card,sort_no) "
                    + "VALUES(3,'SERVICE',1,'洗澡',1,5000,5000,0,0,0)");
            // 单 4：已反结账，成本不应再计入
            st.execute("INSERT INTO bill(id,bill_no,bill_type,customer_id,status,total_cent,"
                    + "discount_cent,receivable_cent,received_cent,business_date,request_id,created_at) "
                    + "VALUES(4,'S202610240004','SALE',1,'CANCELLED',1000,0,1000,1000,'2026-10-27','r4','2026-10-27 13:00:00')");
            st.execute("INSERT INTO product_ledger(product_id,biz_type,qty_change,before_qty,after_qty,"
                    + "unit_cost_cent,ref_type,ref_id,created_at) VALUES(1,'SALE',-1,6,5,999,'BILL',4,'2026-10-27 13:00:00')");
            // 单 5（#6 价目开单）：价目格子 + 美容单项，会员 9 折 + 整单折扣 300 分摊到服务行
            //   格子 10000 → 成交 9000（行折扣 1000）；单项 5000 → 成交 4500（行折扣 500）；整单 300 均摊每行 150
            st.execute("INSERT INTO bill(id,bill_no,bill_type,customer_id,status,total_cent,"
                    + "discount_cent,receivable_cent,received_cent,business_date,request_id,created_at) "
                    + "VALUES(5,'S202610270005','SALE',1,'SETTLED',15000,300,13200,13200,'2026-10-27','r5','2026-10-27 14:00:00')");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,line_discount_cent,paid_by_card,sort_no) "
                    + "VALUES(5,'SERVICE',1,'狗：0-3kg · 精洗',1,9000,9000,150,1000,0,0)");
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,line_discount_cent,paid_by_card,sort_no) "
                    + "VALUES(5,'SERVICE',1,'剪指甲',1,4500,4500,150,500,0,1)");
            // 单 5 的次卡抵扣行：金额记 0，不进服务收入
            st.execute("INSERT INTO bill_item(bill_id,item_type,ref_id,name_snapshot,qty,unit_price_cent,"
                    + "amount_cent,discount_cent,line_discount_cent,paid_by_card,sort_no) "
                    + "VALUES(5,'SERVICE',1,'剪指甲（次卡）',1,5000,0,0,0,1,2)");
        }
    }

    private long scalar(String sql, String... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    public void productCostIsNotMultipliedByItemRows() throws Exception {
        // 5 件 × 3.00 = 15.00 元；旧 SQL 会得到 30.00
        assertEquals(1500L, scalar(MONTHLY_COST, "2026-10-01", "2026-10-31"));
    }

    @Test
    public void cancelledBillCostIsExcluded() throws Exception {
        // 已反结账的单（成本 999）不能进月报
        assertEquals(1500L, scalar(MONTHLY_COST, "2026-10-01", "2026-10-31"));
    }

    @Test
    public void boardingIncomeIsIncludedInServiceColumn() throws Exception {
        // 洗澡 5000 + 寄养 8000 + 价目格子 8850 + 美容单项 4350（次卡抵扣行不计）
        assertEquals(26200L, scalar(DAILY_SERVICE_INCOME, "2026-10-27"));
    }

    @Test
    public void priceCellAndBeautyItemLinesCountAsServiceIncome() throws Exception {
        // 含价目格子与美容单项；次卡抵扣行不计
        assertEquals(26200L, scalar(DAILY_SERVICE_INCOME, "2026-10-27"));
        assertEquals(13200L, scalar(
                "SELECT IFNULL(SUM(i.amount_cent - i.discount_cent),0) FROM bill_item i "
                        + "WHERE i.bill_id = 5 AND i.item_type = 'SERVICE' AND i.paid_by_card = 0"));
    }

    @Test
    public void productIncomeNetsBillDiscountAndLineDiscount() throws Exception {
        // 狗粮两行 2000 + 3000，无折扣
        assertEquals(5000L, scalar(DAILY_PRODUCT_INCOME, "2026-10-27"));
    }

    @Test
    public void todayIncomeNetsBothDiscounts() throws Exception {
        // 当日全部非卡抵扣明细：洗澡 5000 + 寄养 8000 + 狗粮 5000 + 价目格子 8850 + 美容单项 4350，
        // 已扣行折扣（1500）与整单分摊（300）
        assertEquals(29700L, scalar(TODAY_INCOME, "2026-10-27"));
    }

    @Test
    public void billCountIncludesBillsWithoutProductItems() throws Exception {
        assertEquals(4L, scalar(MONTHLY_BILL_COUNT, "2026-10-01", "2026-10-31"));
    }

    @Test
    public void cancelIsIdempotent() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(CANCEL_BILL)) {
            ps.setInt(1, 1);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement(CANCEL_BILL)) {
            ps.setInt(1, 1);
            assertEquals(0, ps.executeUpdate());
        }
    }
}
