package com.petshop.module.svc;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 洗护美容价目页（#7 起取代旧「服务项目」页）：价目表 + 服务分类 + 美容单项。
 * 分类 / 价目行 / 格子 / 美容单项的写操作分别见同模块的独立控制器。
 */
@Controller
@RequestMapping("/services")
public class ServicePricePageController {

    private final ServiceCategoryService categoryService;
    private final ServiceBeautyItemService beautyItemService;
    private final ServicePriceService priceService;

    public ServicePricePageController(ServiceCategoryService categoryService,
                                      ServiceBeautyItemService beautyItemService, ServicePriceService priceService) {
        this.categoryService = categoryService;
        this.beautyItemService = beautyItemService;
        this.priceService = priceService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("categories", categoryService.findAll());
        // 美容单项：分类下拉只列可见分类
        model.addAttribute("visibleCategories", categoryService.findAll().stream()
                .filter(ServiceCategory::getIsVisible).collect(java.util.stream.Collectors.toList()));
        model.addAttribute("beautyItems", beautyItemService.findAll());
        // 价目表：行 + 格子索引
        model.addAttribute("priceRows", priceService.findRows());
        model.addAttribute("priceCells", priceService.cellIndex());
        return "svc/list";
    }
}
