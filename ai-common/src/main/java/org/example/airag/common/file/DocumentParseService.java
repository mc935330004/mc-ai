package org.example.airag.common.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 通用文档解析服务
 * 使用 Apache Tika 解析多种文档格式，提取文本内容
 * 供知识库和简历模块共同使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB

    private final TextCleaningService textCleaningService;

    // Docling 服务地址，直接复用你 application.yml 里的 document.parse.url
    @Value("${document.parse.url:}")
    private String doclingUrl;

    @Value("${document.parse.path:}")
    private String doclingPath;
    // Docling 连接超时时间
    @Value("${document.parse.connect-timeout-ms:10000}")
    private int doclingConnectTimeoutMs;

    // Docling 读取超时时间，也就是等待解析完成的时间
    @Value("${document.parse.read-timeout-ms:120000}")
    private int doclingReadTimeoutMs;

    // 是否启用 Docling，方便本地调试或 Docling 服务不可用时关闭
    @Value("${document.parse.docling-enabled:true}")
    private boolean doclingEnabled;
    /**
     * 解析上传的文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseContent(MultipartFile file) {
//        String fileName = file.getOriginalFilename();
//        log.info("开始解析文件: {}", fileName);
//
//        // 处理空文件
//        if (file.isEmpty() || file.getSize() == 0) {
//            log.warn("文件为空: {}", fileName);
//            return "";
//        }
//
//        try (InputStream inputStream = file.getInputStream()) {
//            String content = parseContent(inputStream);
//            String cleanedContent = textCleaningService.cleanText(content);
//            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
//            return cleanedContent;
//        } catch (IOException | TikaException | SAXException e) {
//            log.error("文件解析失败: {}", e.getMessage(), e);
//            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
//        }
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("上传文件为空: {}", fileName);
            return "";
        }

        try {
            return parseContent(file.getBytes(), fileName);
        } catch (IOException e) {
            log.error("读取上传文件失败: fileName={}, error={}", fileName, e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "读取上传文件失败: " + e.getMessage()
            );
        }
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }
        long startTime = System.currentTimeMillis();
        String content;
        String parser = "TIKA";
        if (!doclingEnabled) {
            // Docling 关闭时，直接使用 Tika，方便本地开发和排查问题
            content = parseByTika(fileBytes, fileName);
        }else{
            try {
                parser = "DOCLING";
                content = parseByDocling(fileBytes, fileName);
                if (StringUtils.hasText(content)) {
                    return textCleaningService.cleanText(content);
                }
            } catch (Exception e) {
                // ponytail: 先用最小 fallback，后面真需要再拆 Parser 接口
                log.warn("Docling 解析失败，回退 Tika 解析: fileName={}, error={}", fileName, e.getMessage());
                parser = "TIKA";
                content = parseByTika(fileBytes, fileName);
            }
        }
        String parsedText = requireParsedText(content, fileName);
        log.info(
                "文件解析完成: fileName={}, parser={}, textLength={}, durationMs={}",
                fileName,
                parser,
                parsedText.length(),
                System.currentTimeMillis() - startTime
        );
        return parsedText;
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档
     * <p>
     * 优化点：
     * 1. 使用 BodyContentHandler 只提取正文内容
     * 2. 禁用 EmbeddedDocumentExtractor，不解析嵌入资源（图片、附件）
     * 3. 配置 PDFParserConfig，关闭图片和注释提取
     * 4. 显式指定 Parser 到 Context，增强健壮性
     *
     * @param inputStream 文件输入流
     * @return 提取的文本内容
     * @throws IOException     IO 异常
     * @throws TikaException   Tika 解析异常
     * @throws SAXException    SAX 解析异常
     */
    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        // 1. 创建自动检测解析器
        AutoDetectParser parser = new AutoDetectParser();

        // 2. 创建内容处理器，只接收正文，限制最大长度为 5MB
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        // 3. 创建元数据对象
        Metadata metadata = new Metadata();

        // 4. 创建解析上下文
        ParseContext context = new ParseContext();

        // 5. 显式指定 Parser 到 Context（增强健壮性）
        context.set(Parser.class, parser);

        // 6. 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        // 7. PDF 专用配置：关闭图片提取，按位置排序文本
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true); // 按 x/y 坐标排序文本，改善多栏布局解析顺序
        // 注意：Tika 2.9.2 中 setExtractAnnotations 方法可能不存在，关闭图片提取已足够
        context.set(PDFParserConfig.class, pdfConfig);

        // 8. 执行解析
        parser.parse(inputStream, handler, metadata, context);

        // 9. 返回提取的文本内容
        return handler.toString();
    }
    /**
     * 调用 Docling 解析文件。
     * 注意：Docling 的真实接口路径和返回字段要按你的 Docling 服务确认。
     */
    private String parseByDocling(byte[] fileBytes, String fileName) {
        if (!StringUtils.hasText(doclingUrl)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未配置 Docling 地址");
        }

        // 设置连接超时和读取超时，避免 Docling 卡死拖住任务线程
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(doclingConnectTimeoutMs);
        factory.setReadTimeout(doclingReadTimeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // 示例：假设 Docling 接口是 POST /v1/convert/file
        ResponseEntity<Map> response = restTemplate.exchange(
                buildDoclingApiUrl(),
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        Map<?, ?> result = response.getBody();
        // 只打印返回字段名，不打印正文内容，避免日志过大
        log.info("Docling 解析返回字段: {}", result.keySet());
        return extractDoclingContent(result);
    }

    /**
     * Docling 失败后的兜底解析。
     * 这里就是你原来的 Apache Tika 解析逻辑。
     */
    private String parseByTika(byte[] fileBytes, String fileName) {
        log.info("开始使用 Tika 兜底解析文件: {}", fileName);

        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            String content = parseByTika(inputStream);
            return textCleaningService.cleanText(content);
        } catch (IOException | TikaException | SAXException e) {
            log.error("Tika 兜底解析失败: fileName={}, error={}", fileName, e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "文件解析失败: " + e.getMessage()
            );
        }
    }

    /**
     * Tika 核心解析逻辑。
     */
    private String parseByTika(InputStream inputStream)
            throws IOException, TikaException, SAXException {

        // 自动识别 PDF、Word、TXT、MD 等常见文档类型
        AutoDetectParser parser = new AutoDetectParser();

        // 只提取正文，并限制最大文本长度
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        // 文档元数据，当前先不用，保留给 Tika 内部解析
        Metadata metadata = new Metadata();

        // 解析上下文
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);

        // 禁用内嵌资源解析，避免图片、附件等拖慢解析
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        // PDF 解析配置：不提取内联图片，按位置排序文本
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        // 执行解析
        parser.parse(inputStream, handler, metadata, context);

        return handler.toString();
    }
    /**
     * 拼接 Docling 完整接口地址。
     * 兼容 document.parse.url 是否以 / 结尾、document.parse.path 是否以 / 开头。
     */
    private String buildDoclingApiUrl() {
        String baseUrl = doclingUrl.trim();
        String apiPath = doclingPath.trim();

        if (baseUrl.endsWith("/") && apiPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + apiPath;
        }

        if (!baseUrl.endsWith("/") && !apiPath.startsWith("/")) {
            return baseUrl + "/" + apiPath;
        }

        return baseUrl + apiPath;
    }
    /**
     * 从 Docling 返回结果中提取可用于 RAG 的文本。
     * 优先使用 markdown，因为它能保留标题、列表、表格结构。
     */
    private String extractDoclingContent(Map<?, ?> result) {
        // 常见情况 1：直接返回 markdown
        String markdown = getString(result, "markdown");
        if (StringUtils.hasText(markdown)) {
            return markdown;
        }
        // 常见情况 2：返回 md_content
        String mdContent = getString(result, "md_content");
        if (StringUtils.hasText(mdContent)) {
            return mdContent;
        }
        // 常见情况 3：直接返回 text
        String text = getString(result, "text");
        if (StringUtils.hasText(text)) {
            return text;
        }
        // 常见情况 4：返回 content
        String content = getString(result, "content");
        if (StringUtils.hasText(content)) {
            return content;
        }
        // 常见情况 5：内容包在 document 对象里
        Object document = result.get("document");
        if (document instanceof Map<?, ?> documentMap) {
            String documentMarkdown = getString(documentMap, "markdown");
            if (StringUtils.hasText(documentMarkdown)) {
                return documentMarkdown;
            }

            String documentText = getString(documentMap, "text");
            if (StringUtils.hasText(documentText)) {
                return documentText;
            }
        }

        throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "Docling 未返回有效文本"
        );
    }

    /**
     * 安全读取字符串字段，避免到处写 null 判断。
     */
    private String getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }
    /**
     * 校验解析结果，避免空文本继续进入切片、向量化流程。
     */
    private String requireParsedText(String content, String fileName) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "文件未解析出有效文本: " + fileName
            );
        }
        return content.trim();
    }

}
