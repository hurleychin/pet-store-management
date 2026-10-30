package com.petshop.module.svc;

import com.petshop.common.SessionUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * 美容单项写操作（仅 ADMIN，拦截规则见 WebConfig）。
 * 列表展示在价目页 {@code templates/svc/_beauty-items.html} 片段中。
 */
@Controller
@RequestMapping("/services/beauty-items")
public class ServiceBeautyItemController {

    private final ServiceBeautyItemService service;
    private final com.petshop.module.audit.AuditService audit;

    public ServiceBeautyItemController(ServiceBeautyItemService service,
                                       com.petshop.module.audit.AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/add")
    public String add(@RequestParam String name,
                      @RequestParam(required = false) String categoryId,
                      @RequestParam(defaultValue = "0") String retailPriceYuan,
                      @RequestParam(required = false) String discountType,
                      @RequestParam(required = false) String discountRate,
                      RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceBeautyItem item = service.save(null, name, categoryId(categoryId), retailPriceYuan,
                discountType, discountRate);
        audit.log("SERVICE_BEAUTY_ITEM_CREATE", "SERVICE_BEAUTY_ITEM", item.getId(), null, desc(item),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "美容单项已新增");
        return "redirect:/services";
    }

    @PostMapping("/batch")
    public String batch(@RequestParam(required = false) String categoryId,
                        @RequestParam(required = false) String discountType,
                        @RequestParam(required = false) String discountRate,
                        @RequestParam(required = false) String lines,
                        RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        java.util.List<ServiceBeautyItem> created = service.batchAdd(categoryId, discountType, discountRate, lines);
        for (ServiceBeautyItem item : created) {
            audit.log("SERVICE_BEAUTY_ITEM_CREATE", "SERVICE_BEAUTY_ITEM", item.getId(), null, desc(item),
                    user(session), request.getRemoteAddr());
        }
        ra.addFlashAttribute("okMsg", "已批量新增 " + created.size() + " 个美容单项");
        return "redirect:/services";
    }

    @PostMapping("/{id}/save")
    public String save(@PathVariable Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String categoryId,
                       @RequestParam(defaultValue = "0") String retailPriceYuan,
                       @RequestParam(required = false) String discountType,
                       @RequestParam(required = false) String discountRate,
                       RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceBeautyItem before = service.requireExists(id);
        String beforeJson = desc(before);
        ServiceBeautyItem after = service.save(id, name, categoryId(categoryId), retailPriceYuan,
                discountType, discountRate);
        audit.log("SERVICE_BEAUTY_ITEM_UPDATE", "SERVICE_BEAUTY_ITEM", id, beforeJson, desc(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "美容单项已保存");
        return "redirect:/services";
    }

    @PostMapping("/{id}/switches")
    public String switches(@PathVariable Long id,
                           @RequestParam(defaultValue = "false") boolean showCashier,
                           @RequestParam(defaultValue = "false") boolean showShop,
                           @RequestParam(defaultValue = "false") boolean earnPoints,
                           RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        String beforeJson = desc(service.requireExists(id));
        ServiceBeautyItem after = service.saveSwitches(id, showCashier, showShop, earnPoints);
        audit.log("SERVICE_BEAUTY_ITEM_SWITCHES", "SERVICE_BEAUTY_ITEM", id, beforeJson, desc(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "服务开关已保存");
        return "redirect:/services";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        String beforeJson = desc(service.requireExists(id));
        service.delete(id);
        audit.log("SERVICE_BEAUTY_ITEM_DELETE", "SERVICE_BEAUTY_ITEM", id, beforeJson, null,
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "美容单项已删除");
        return "redirect:/services";
    }

    private Long categoryId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String desc(ServiceBeautyItem i) {
        return "name=" + i.getName() + ", categoryId=" + i.getCategoryId()
                + ", price=" + i.getRetailPriceCent() + ", discount=" + i.getDiscountType()
                + "/" + i.getDiscountRate() + ", cashier=" + i.getShowCashier()
                + ", shop=" + i.getShowShop() + ", points=" + i.getEarnPoints();
    }

    private SessionUser user(HttpSession session) {
        return (SessionUser) session.getAttribute("sessionUser");
    }
}
