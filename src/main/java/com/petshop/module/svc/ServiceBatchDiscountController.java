package com.petshop.module.svc;

import com.petshop.common.SessionUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 批量设置折扣：从价目页进入，按服务分类分页签列出美容单项，勾选后一次性下发服务折扣。
 * 下发表单仅 ADMIN 可进（拦截规则见 WebConfig），页面本身店员只读。
 */
@Controller
@RequestMapping("/services")
public class ServiceBatchDiscountController {

    private final ServiceCategoryService categoryService;
    private final ServiceBeautyItemService beautyItemService;
    private final com.petshop.module.audit.AuditService audit;

    public ServiceBatchDiscountController(ServiceCategoryService categoryService,
                                          ServiceBeautyItemService beautyItemService,
                                          com.petshop.module.audit.AuditService audit) {
        this.categoryService = categoryService;
        this.beautyItemService = beautyItemService;
        this.audit = audit;
    }

    @GetMapping("/discount-batch")
    public String page(@RequestParam(required = false) Long categoryId, Model model) {
        List<ServiceCategory> categories = categoryService.findAll().stream()
                .filter(ServiceCategory::getIsVisible)
                .collect(Collectors.toList());
        Long current = null;
        for (ServiceCategory c : categories) {
            if (c.getId().equals(categoryId)) {
                current = c.getId();
                break;
            }
        }
        if (current == null && !categories.isEmpty()) {
            current = categories.get(0).getId();
        }
        model.addAttribute("categories", categories);
        model.addAttribute("currentCategoryId", current);
        model.addAttribute("batchItems", current == null ? java.util.Collections.emptyList()
                : beautyItemService.findByCategory(current));
        return "svc/discount-batch";
    }

    @PostMapping("/beauty-items/batch-discount")
    public String batchDiscount(@RequestParam(required = false) List<Long> ids,
                                @RequestParam(required = false) String discountType,
                                @RequestParam(required = false) String discountRate,
                                @RequestParam(required = false) Long categoryId,
                                RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        java.util.List<ServiceBeautyItem> before = new java.util.ArrayList<>();
        for (Long id : new java.util.LinkedHashSet<>(ids == null ? java.util.Collections.<Long>emptyList() : ids)) {
            before.add(beautyItemService.requireExists(id));
        }
        List<ServiceBeautyItem> changed = beautyItemService.batchSetDiscount(ids, discountType, discountRate);
        List<Long> changedIds = new java.util.ArrayList<>();
        for (ServiceBeautyItem item : changed) {
            changedIds.add(item.getId());
        }
        // 每个被改项目一条，带各自改前折扣与本次批量涉及的项目 id 列表
        for (int i = 0; i < changed.size(); i++) {
            ServiceBeautyItem item = changed.get(i);
            audit.log("SERVICE_BEAUTY_ITEM_BATCH_DISCOUNT", "SERVICE_BEAUTY_ITEM", item.getId(),
                    SvcAuditSnapshots.beautyItem(before.get(i)),
                    SvcAuditSnapshots.beautyItemDiscount(item, changedIds),
                    user(session), request.getRemoteAddr());
        }
        ra.addFlashAttribute("okMsg", "已为 " + changed.size() + " 个项目设置服务折扣");
        return "redirect:" + (categoryId == null ? "/services/discount-batch"
                : "/services/discount-batch?categoryId=" + categoryId);
    }

    private SessionUser user(HttpSession session) {
        return (SessionUser) session.getAttribute("sessionUser");
    }
}
