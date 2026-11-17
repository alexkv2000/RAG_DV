package kvo.itsnn.rag.config;

import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Лимиты РАЗМЕРА загрузки для POST /api/rag/extract (multipart/form-data).
 *
 * MultipartConfigElement (64 МБ файл/запрос): автоконфигурация multipart отступает
 * перед готовым бином в любой версии Boot — namespace yaml-свойств на размер не влияет.
 * Файлы 32–64 МБ дополнительно отклоняет ExtractService своим понятным сообщением.
 *
 * Лимит ЗАГОЛОВКОВ части multipart (Content-Disposition с длинным именем файла)
 * задаётся НЕ здесь: TomcatConnectorCustomizer-бин выставляет значение, но затем
 * ServerProperties накатываются на коннектор позже и перетирают его дефолтом
 * (server.tomcat.max-part-header-size = 512 байт). Каноничное и побеждающее место —
 * application.yaml: server.tomcat.max-part-header-size: 8KB.
 */
@Configuration
public class UploadConfig {

    private static final Logger log = LoggerFactory.getLogger(UploadConfig.class);

    private static final long MAX = 64L * 1024 * 1024;      // 64 МБ — и файл, и весь запрос
    private static final int MEMORY_THRESHOLD = 10 * 1024 * 1024;   // дальше — спул на диск

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigElement cfg = new MultipartConfigElement("", MAX, MAX, MEMORY_THRESHOLD);
        log.info("Multipart: max-file-size={} МБ, max-request-size={} МБ (бин UploadConfig)",
                MAX / 1024 / 1024, MAX / 1024 / 1024);
        return cfg;
    }
}