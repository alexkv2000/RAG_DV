package kvo.itsnn.rag.refsync;

import java.util.UUID;

/**
 * Заявка на индексацию из dbo.rag_document_ref.
 * orgLe — юридическое лицо документа (rag_document_ref.org_le):
 * NULL/"" — общий документ, конкретное имя — привязан к ЮЛ.
 * Прокидывается в метаданные ChromaDB для фильтрации при поиске.
 */
public record DocumentRef(UUID guid, String classifier, String orgLe, int retryCount) {}