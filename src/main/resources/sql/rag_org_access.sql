/* ================================================================
   Фильтрация документов RAG по юридическим лицам (org_le)
   Модель: пользователь → группы → ЮЛ группы → доступные документы
   ================================================================ */

/* 1. Колонка org_le в таблице ссылок на документы.
      Заполняется из DocsVision (атрибут карточки «ЮЛ Группы»).
      NULL / '' — документ общий, доступен всем. */
IF COL_LENGTH('dbo.rag_document_ref', 'org_le') IS NULL
ALTER TABLE dbo.rag_document_ref ADD org_le NVARCHAR(255) NULL;

/* 2. Индекс для быстрой фильтрации при SQL-запросах (Compare, Summary) */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_rag_document_ref_org_le')
CREATE INDEX IX_rag_document_ref_org_le
    ON dbo.rag_document_ref (org_le) INCLUDE (document_guid, status, classifier, row_id)
    WHERE status = 'I';

/* 3. Связь группа → ЮЛ: члены группы видят документы этих ЮЛ.
      Одна группа может быть привязана к нескольким ЮЛ (и наоборот). */
IF OBJECT_ID('dbo.rag_group_org', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.rag_group_org (
                                           group_id BIGINT        NOT NULL
                                               CONSTRAINT FK_rag_group_org_group FOREIGN KEY REFERENCES dbo.rag_group(id),
                                           org_le   NVARCHAR(255) NOT NULL,
                                           CONSTRAINT PK_rag_group_org PRIMARY KEY (group_id, org_le)
        );

        /* Справочная связь наименование ЮЛ → справочник DocsVision не обязательна:
           org_le хранится строкой (Name из dvtable_{7473f07f-…}), FK не нужен. */
    END
GO

/* 4. Пример заполнения org_le из DocsVision.
      Схема связи document_guid → карточка DocsVision зависит от вашей вьюхи.
      Ниже — шаблон: замените rag_source_view на актуальную вьюху/JOIN. */
/*
UPDATE r
   SET r.org_le = le.Name
FROM dbo.rag_document_ref r
JOIN dbo.rag_source_view sv ON sv.document_guid = r.document_guid
JOIN dbo.[dvtable_{7473f07f-11ed-4762-9f1e-7ff10808ddd1}] le (nolock)
     ON le.RowID = sv.org_le_row_id          -- ID ЮЛ из карточки документа
WHERE r.org_le IS NULL
  AND le.Type = 0
  AND (le.NotAvailable IS NULL OR le.NotAvailable = 0);
*/

/* 5. Пример: привязать группу «RAG — пользователи» к ЮЛ «Газпром» */
/*
INSERT INTO dbo.rag_group_org (group_id, org_le)
SELECT g.id, N'Газпром'
FROM dbo.rag_group g
WHERE g.name = N'RAG — пользователи'
  AND NOT EXISTS (SELECT 1 FROM dbo.rag_group_org go
                  WHERE go.group_id = g.id AND go.org_le = N'Газпром');
*/