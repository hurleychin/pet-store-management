package com.petshop.module.svc;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;
import com.petshop.common.Money;
import com.petshop.common.SessionUser;
import com.petshop.module.audit.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** 价目行与价格格子写操作（仅 ADMIN，拦截规则见 WebConfig）。 */
@Controller
@RequestMapping("/services/price-rows")
public class ServicePriceRowController {

    private final ServicePriceService service;
    private final AuditService audit;

    public ServicePriceRowController(ServicePriceService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam String animal,
                       @RequestParam String description,
                       @RequestParam(required = false) String dogSize,
                       @RequestParam(required = false) String furLength,
                       @RequestParam(required = false) String breedTags,
                       @RequestParam(defaultValue = "0") int sortNo,
                       RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServicePriceRow before = service.findRow(id);
        ServicePriceRow after = service.saveRow(id, animal, description, dogSize, furLength, breedTags, sortNo);
        audit.log(id == null ? "SERVICE_PRICE_ROW_CREATE" : "SERVICE_PRICE_ROW_UPDATE",
                "SERVICE_PRICE_ROW", after.getId(),
                before == null ? null : SvcAuditSnapshots.priceRow(before),
                SvcAuditSnapshots.priceRow(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", id == null ? "价目行已新增" : "价目行已保存");
        return "redirect:/services";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServicePriceRow before = service.findRow(id);
        service.deleteRow(id);
        audit.log("SERVICE_PRICE_ROW_DELETE", "SERVICE_PRICE_ROW", id,
                before == null ? null : SvcAuditSnapshots.priceRow(before), null,
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "价目行已删除");
        return "redirect:/services";
    }

    /** 改价 + 设置该格子的服务折扣。价格为空表示未定价。 */
    @PostMapping("/{id}/cells/save")
    public String saveCell(@PathVariable Long id,
                           @RequestParam Long categoryId,
                           @RequestParam(required = false) String priceYuan,
                           @RequestParam(required = false) String discountType,
                           @RequestParam(required = false) String discountRate,
                           RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        Integer cent = priceCent(priceYuan);
        ServicePriceCell before = service.findCell(id, categoryId);
        service.saveCell(id, categoryId, cent, discountType, discountRate);
        // 改前 / 改后都取落库后的真实值：未定价、默认全额等归一化后的口径也能对上
        ServicePriceCell after = service.findCell(id, categoryId);
        audit.log("SERVICE_PRICE_CELL_UPDATE", "SERVICE_PRICE_CELL", id,
                before == null ? null : SvcAuditSnapshots.priceCell(before.getCategoryId(), before.getPriceCent(),
                        before.getDiscountType(), before.getDiscountRate()),
                after == null ? null : SvcAuditSnapshots.priceCell(after.getCategoryId(), after.getPriceCent(),
                        after.getDiscountType(), after.getDiscountRate()),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "价格格子已保存");
        return "redirect:/services";
    }

    /** 空 = 未定价；否则用 Money 精确到分，负数拒绝。 */
    private Integer priceCent(String yuan) {
        if (yuan == null || yuan.trim().isEmpty()) {
            return null;
        }
        int cent = Money.toCent(yuan.trim());
        if (cent < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "格子价格不能为负数");
        }
        return cent;
    }

    private SessionUser user(HttpSession session) {
        return (SessionUser) session.getAttribute("sessionUser");
    }
}
