package kvo.itsnn.rag.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * POI: порог zip-bomb (MIN_INFLATE_RATIO=0.01 по умолчанию) отвергает легитимные
 * xlsx/docx с хорошо сжимаемой графикой (EMF сжимается лучше 100:1). Понижаем до
 * 0.0005 (2000:1): настоящие бомбы (ratio ~1e-6) по-прежнему ловятся, а риск
 * раздувания памяти ограничен сверху rag.content.max-file-mb (32 МБ) и внутренним
 * происхождением файлов (хранилище DocsVision).
 */
@Configuration
public class PoiLimitsConfig {

    private static final Logger log = LoggerFactory.getLogger(PoiLimitsConfig.class);

    @PostConstruct
    void apply() {
        ZipSecureFile.setMinInflateRatio(0.0005d);
        log.info("POI: min inflate ratio = 0.0005 (порог zip-bomb понижен для EMF-графики)");
    }
}