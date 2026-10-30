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

/** 服务分类写操作（仅 ADMIN，拦截规则见 WebConfig）。 */
@Controller
@RequestMapping("/services/categories")
public class ServiceCategoryController {

    private final ServiceCategoryService service;
    private final com.petshop.module.audit.AuditService audit;

    public ServiceCategoryController(ServiceCategoryService service,
                                     com.petshop.module.audit.AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam String degree,
                       RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceCategory before = find(id);
        ServiceCategory after = service.save(id, name, degree);
        audit.log(id == null ? "SERVICE_CATEGORY_CREATE" : "SERVICE_CATEGORY_UPDATE",
                "SERVICE_CATEGORY", after.getId(),
                before == null ? null : SvcAuditSnapshots.category(before),
                SvcAuditSnapshots.category(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", id == null ? "服务分类已新增" : "服务分类已保存");
        return "redirect:/services";
    }

    @PostMapping("/{id}/visibility")
    public String visibility(@PathVariable Long id, @RequestParam boolean visible,
                             RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceCategory before = find(id);
        service.setVisible(id, visible);
        ServiceCategory after = find(id);
        audit.log("SERVICE_CATEGORY_VISIBILITY", "SERVICE_CATEGORY", id,
                before == null ? null : SvcAuditSnapshots.category(before),
                after == null ? null : SvcAuditSnapshots.category(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", visible ? "服务分类已显示" : "服务分类已隐藏");
        return "redirect:/services";
    }

    @PostMapping("/{id}/move")
    public String move(@PathVariable Long id, @RequestParam String dir,
                       RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceCategory before = find(id);
        service.move(id, dir);
        ServiceCategory after = find(id);
        audit.log("SERVICE_CATEGORY_MOVE", "SERVICE_CATEGORY", id,
                before == null ? null : SvcAuditSnapshots.category(before),
                after == null ? null : SvcAuditSnapshots.category(after),
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "服务分类顺序已调整");
        return "redirect:/services";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         RedirectAttributes ra, HttpSession session, HttpServletRequest request) {
        ServiceCategory before = find(id);
        service.delete(id);
        audit.log("SERVICE_CATEGORY_DELETE", "SERVICE_CATEGORY", id,
                before == null ? null : SvcAuditSnapshots.category(before), null,
                user(session), request.getRemoteAddr());
        ra.addFlashAttribute("okMsg", "服务分类已删除");
        return "redirect:/services";
    }

    private ServiceCategory find(Long id) {
        return id == null ? null : service.findAll().stream()
                .filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    private SessionUser user(HttpSession session) {
        return (SessionUser) session.getAttribute("sessionUser");
    }
}
