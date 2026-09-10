package org.example.ai.agent.business.dataset.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.dto.ReportDatasetStatusDTO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetDetailVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetListVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetValidationVO;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告数据集管理接口，只负责请求参数和统一返回结构转换。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/report-datasets")
public class ReportDatasetAdminController {

    private final ReportDatasetService reportDatasetService;
    private final CurrentUserProvider currentUserProvider;

    /** 分页查询当前配置。 */
    @GetMapping("/pageList")
    public Result<Page<ReportDatasetListVO>> pageList(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(reportDatasetService.pageCurrent(
                current,
                size,
                keyword,
                domainCode,
                enabled
        ));
    }

    /** 查询当前配置详情。 */
    @GetMapping("/detail/{id}")
    public Result<ReportDatasetDetailVO> detail(@PathVariable Long id) {
        return Result.success(reportDatasetService.detailCurrent(id));
    }

    /** 只校验配置，不写数据库。 */
    @PostMapping("/validate")
    public Result<ReportDatasetValidationVO> validate(
            @RequestBody ReportDatasetSaveDTO dto) {
        return Result.success(reportDatasetService.validateCurrent(dto));
    }

    /** 保存结构化配置，操作人取当前登录用户。 */
    @PostMapping("/save")
    public Result<ReportDatasetDetailVO> save(
            @RequestBody ReportDatasetSaveDTO dto) {
        return Result.success(reportDatasetService.saveCurrent(
                dto,
                currentUserProvider.getRequiredUserId()
        ));
    }

    /** 启用或停用当前配置。 */
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @RequestBody ReportDatasetStatusDTO dto) {
        reportDatasetService.updateStatus(
                id,
                dto.getEnabled(),
                dto.getVersion(),
                currentUserProvider.getRequiredUserId()
        );
        return Result.success();
    }
}
