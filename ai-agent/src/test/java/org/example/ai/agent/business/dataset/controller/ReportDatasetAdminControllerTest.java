package org.example.ai.agent.business.dataset.controller;

import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.dto.ReportDatasetStatusDTO;
import org.example.ai.agent.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDatasetAdminControllerTest {

    @Test
    void controllerShouldExposeExactlyFiveAdminOperations() throws Exception {
        RequestMapping root = ReportDatasetAdminController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/agent/admin/report-datasets");
        assertThat(ReportDatasetAdminController.class.getMethod(
                "pageList", long.class, long.class, String.class, String.class, Boolean.class
        ).getAnnotation(GetMapping.class).value()).containsExactly("/pageList");
        assertThat(ReportDatasetAdminController.class.getMethod("detail", Long.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/detail/{id}");
        assertThat(ReportDatasetAdminController.class.getMethod(
                "validate", ReportDatasetSaveDTO.class
        ).getAnnotation(PostMapping.class).value()).containsExactly("/validate");
        assertThat(ReportDatasetAdminController.class.getMethod(
                "save", ReportDatasetSaveDTO.class
        ).getAnnotation(PostMapping.class).value()).containsExactly("/save");
        assertThat(ReportDatasetAdminController.class.getMethod(
                "updateStatus", Long.class, ReportDatasetStatusDTO.class
        ).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/status");
    }

    @Test
    void writeOperationsShouldUseCurrentLoginUser() {
        ReportDatasetService service = mock(ReportDatasetService.class);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.getRequiredUserId()).thenReturn("admin-1");
        ReportDatasetAdminController controller = new ReportDatasetAdminController(
                service,
                currentUser
        );
        ReportDatasetSaveDTO save = new ReportDatasetSaveDTO();
        ReportDatasetStatusDTO status = new ReportDatasetStatusDTO();
        status.setEnabled(false);
        status.setVersion(3);

        controller.save(save);
        controller.updateStatus(10L, status);

        verify(service).saveCurrent(save, "admin-1");
        verify(service).updateStatus(10L, false, 3, "admin-1");
    }
}
