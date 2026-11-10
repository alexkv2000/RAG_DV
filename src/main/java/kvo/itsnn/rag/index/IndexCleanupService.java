package kvo.itsnn.rag.index;

import kvo.itsnn.rag.refsync.RefRepository;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IndexCleanupService {

    private static final Logger log = LoggerFactory.getLogger(IndexCleanupService.class);

    private final RefRepository refs;
    private final VectorIndex index;

    public IndexCleanupService(RefRepository refs, VectorIndex index) {
        this.refs = refs; this.index = index;
    }

    public void purgeDeleted(int limit) {
        List<UUID> deleted = refs.fetchDeleted(limit);
        if (deleted.isEmpty()) return;
        for (UUID guid : deleted) {
            if (!refs.markPurged(guid)) continue;      // успел вернуться — пропускаем
            List<Long> chunkIds = refs.chunkIdsOf(guid);   // id берём ДО удаления из MSSQL
            refs.deleteChunks(guid);
            index.removeChunks(chunkIds);              // векторы убираем из Chroma
        }
        log.info("Вычищено {} удалённых документов", deleted.size());
    }
}