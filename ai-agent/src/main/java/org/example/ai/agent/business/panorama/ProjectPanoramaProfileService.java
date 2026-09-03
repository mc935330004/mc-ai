package org.example.ai.agent.business.panorama;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaModule;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaProfile;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaModuleMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaProfileMapper;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按项目类型解析当前生效的全景方案。
 */
@Service
public class ProjectPanoramaProfileService {

    /**
     * DEFAULT 是全局回退配置标识，不会与真实项目类型或展示文案混用。
     */
    public static final String DEFAULT_PROJECT_TYPE = "DEFAULT";

    private static final Pattern DATASET_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int MAX_MODULES = 32;

    private final ProjectPanoramaProfileMapper profileMapper;
    private final ProjectPanoramaModuleMapper moduleMapper;

    public ProjectPanoramaProfileService(
            ProjectPanoramaProfileMapper profileMapper,
            ProjectPanoramaModuleMapper moduleMapper) {
        this.profileMapper = Objects.requireNonNull(profileMapper, "profileMapper不能为空");
        this.moduleMapper = Objects.requireNonNull(moduleMapper, "moduleMapper不能为空");
    }

    /**
     * 专属项目类型优先；未配置时仅回退到 DEFAULT，不猜测相似类型。
     */
    @Transactional(readOnly = true)
    public ProjectPanoramaPlan resolve(String projectType) {
        String normalizedType = requireText(projectType, 64, "projectType");
        ProjectPanoramaProfile profile = findEnabledProfile(normalizedType);
        if (profile == null && !DEFAULT_PROJECT_TYPE.equals(normalizedType)) {
            profile = findEnabledProfile(DEFAULT_PROJECT_TYPE);
        }
        validateProfile(profile);

        List<ProjectPanoramaModule> modules = moduleMapper.selectList(
                Wrappers.<ProjectPanoramaModule>lambdaQuery()
                        .eq(ProjectPanoramaModule::getProfileId, profile.getId())
                        .orderByAsc(
                                ProjectPanoramaModule::getDisplayOrder,
                                ProjectPanoramaModule::getId
                        )
        );
        List<ProjectPanoramaPlan.Module> planModules = validateAndMapModules(
                profile.getId(),
                modules
        );
        return new ProjectPanoramaPlan(
                profile.getId(),
                profile.getProjectType(),
                profile.getProfileName(),
                profile.getConfigChecksum(),
                planModules
        );
    }

    private ProjectPanoramaProfile findEnabledProfile(String projectType) {
        return profileMapper.selectOne(
                Wrappers.<ProjectPanoramaProfile>lambdaQuery()
                        .eq(ProjectPanoramaProfile::getProjectType, projectType)
                        .eq(ProjectPanoramaProfile::getEnabled, true)
        );
    }

    private void validateProfile(ProjectPanoramaProfile profile) {
        if (profile == null
                || profile.getId() == null
                || !Boolean.TRUE.equals(profile.getEnabled())
                || !StringUtils.hasText(profile.getProfileName())
                || !StringUtils.hasText(profile.getProjectType())
                || !SHA256.matcher(String.valueOf(profile.getConfigChecksum())).matches()) {
            throw new IllegalStateException("未配置可用的项目全景方案");
        }
    }

    private List<ProjectPanoramaPlan.Module> validateAndMapModules(
            Long profileId,
            List<ProjectPanoramaModule> modules) {
        if (modules == null || modules.isEmpty() || modules.size() > MAX_MODULES) {
            throw new IllegalStateException("项目全景模块数量不合法");
        }
        Set<String> datasetCodes = new HashSet<>();
        return modules.stream()
                .map(module -> mapModule(profileId, module, datasetCodes))
                .sorted(Comparator.comparingInt(ProjectPanoramaPlan.Module::displayOrder))
                .toList();
    }

    private ProjectPanoramaPlan.Module mapModule(
            Long profileId,
            ProjectPanoramaModule module,
            Set<String> datasetCodes) {
        if (module == null
                || module.getId() == null
                || !profileId.equals(module.getProfileId())
                || !StringUtils.hasText(module.getDatasetCode())
                || !DATASET_CODE.matcher(module.getDatasetCode()).matches()
                || !datasetCodes.add(module.getDatasetCode())
                || module.getRequiredFlag() == null
                || module.getDisplayOrder() == null
                || module.getDisplayOrder() < 0
                || module.getTimeoutMs() == null
                || module.getTimeoutMs() < 100
                || module.getTimeoutMs() > 300_000) {
            throw new IllegalStateException("项目全景模块配置不合法");
        }
        return new ProjectPanoramaPlan.Module(
                module.getDatasetCode(),
                module.getRequiredFlag(),
                module.getDisplayOrder(),
                module.getTimeoutMs()
        );
    }

    private String requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "不合法");
        }
        return value.trim();
    }
}
