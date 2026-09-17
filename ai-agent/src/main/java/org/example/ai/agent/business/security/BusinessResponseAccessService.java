package org.example.ai.agent.business.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/**
 * 保存并复核业务回答的服务端权限绑定。
 *
 * 原始主体标识只保存在持久化JSON的私有节点中，
 * 返回浏览器前必须完成当前权限复核并删除该节点。
 */
@Service
public class BusinessResponseAccessService {

    private static final String ACCESS_FIELD = "_access";
    private static final String DENIED_MESSAGE = "回答不存在或无权访问";

    private final SubjectSelectionTokenService tokenService;
    private final ProjectDirectoryService projectDirectory;
    private final AuthorizedPersonDirectoryService personDirectory;
    private final DepartmentDirectoryService departmentDirectory;
    private final ObjectMapper objectMapper;

    public BusinessResponseAccessService(
            SubjectSelectionTokenService tokenService,
            ProjectDirectoryService projectDirectory,
            AuthorizedPersonDirectoryService personDirectory,
            DepartmentDirectoryService departmentDirectory,
            ObjectMapper objectMapper) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService不能为空");
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory不能为空");
        this.personDirectory = Objects.requireNonNull(personDirectory, "personDirectory不能为空");
        this.departmentDirectory = Objects.requireNonNull(departmentDirectory, "departmentDirectory不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    /**
     * 将当前已解析主体绑定到数据库回答快照。
     *
     * accessToken不会被序列化，数据库只保存解密后的服务端主体标识。
     */
    public String bind(AiResponse response, String userId, String sessionId) {
        Objects.requireNonNull(response, "业务回答不能为空");
        String documentJson = write(response);
        ResponseContext context = response.context();

        if (context == null || !StringUtils.hasText(context.subjectType())
                || !StringUtils.hasText(context.accessToken())) {
            return documentJson;
        }

        BusinessSubjectType subjectType = subjectType(context.subjectType());
        String rawSubjectId = tokenService.resolve(
                context.accessToken(), userId, sessionId, subjectType
        ).orElseThrow(() -> new IllegalStateException("业务回答主体绑定已失效"));

        ObjectNode document = readObject(documentJson);
        ObjectNode access = document.putObject(ACCESS_FIELD);
        access.put("subjectType", subjectType.name());
        access.put("subjectId", rawSubjectId);
        return write(document);
    }

    /**
     * 使用当前认证重新查询PM主体目录，授权成功后删除私有节点。
     */
    public String authorizeAndSanitize(
            String documentJson,
            String userId,
            String sessionId,
            String runId,
            String authorization) {
        if (!StringUtils.hasText(documentJson)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(sessionId)
                || !StringUtils.hasText(runId)
                || !StringUtils.hasText(authorization)) {
            throw denied();
        }

        try {
            ObjectNode document = readObject(documentJson);
            String contextType = document.path("context").path("subjectType").asText();

            // 非业务回答不执行PM目录复权，但仍禁止意外私有字段返回浏览器。
            if (!StringUtils.hasText(contextType)) {
                if (!document.has(ACCESS_FIELD)) return documentJson;
                document.remove(ACCESS_FIELD);
                return write(document);
            }

            JsonNode access = document.path(ACCESS_FIELD);
            BusinessSubjectType subjectType = subjectType(access.path("subjectType").asText());
            String subjectId = access.path("subjectId").asText();

            if (!contextType.equals(subjectType.name())
                    || !StringUtils.hasText(subjectId)
                    || !currentlyAuthorized(
                    runId, userId, sessionId, authorization, subjectType, subjectId
            )) {
                throw denied();
            }

            document.remove(ACCESS_FIELD);
            return write(document);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw denied();
        }
    }

    /**
     * 精确查询当前用户是否仍然拥有该主体。
     */
    private boolean currentlyAuthorized(
            String runId,
            String userId,
            String sessionId,
            String authorization,
            BusinessSubjectType subjectType,
            String subjectId) {
        SubjectDirectoryQuery query = new SubjectDirectoryQuery(
                runId,
                userId,
                sessionId,
                authorization,
                Map.of(),
                subjectType,
                SubjectSearchMode.SELECTED_SUBJECT,
                subjectId,
                null,
                null,
                null,
                null,
                null,
                1,
                2
        );

        SubjectDirectoryPage page = switch (subjectType) {
            case PROJECT -> projectDirectory.search(query);
            case PERSON -> personDirectory.search(query);
            case DEPARTMENT -> departmentDirectory.search(query);
        };

        if (page == null
                || !page.accessible()
                || page.pageNumber() != 1
                || page.pageSize() != 2
                || !page.totalKnown()
                || page.totalCount() != 1
                || page.hasNext()
                || page.candidates().size() != 1) {
            return false;
        }

        AuthorizedSubjectCandidate candidate = page.candidates().get(0);
        return candidate.type() == subjectType
                && subjectId.equals(candidate.rawSubjectId());
    }

    private BusinessSubjectType subjectType(String value) {
        if (!StringUtils.hasText(value)) throw denied();

        try {
            return BusinessSubjectType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw denied();
        }
    }

    private ObjectNode readObject(String documentJson) {
        try {
            JsonNode document = objectMapper.readTree(documentJson);
            if (!(document instanceof ObjectNode object)) {
                throw new IllegalArgumentException("回答快照必须是JSON对象");
            }
            return object;
        } catch (Exception exception) {
            throw new IllegalStateException("回答快照解析失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("回答快照序列化失败", exception);
        }
    }

    private BusinessException denied() {
        return new BusinessException(404, DENIED_MESSAGE);
    }
}