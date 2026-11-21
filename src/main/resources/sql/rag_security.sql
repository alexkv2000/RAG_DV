/* Пользователи, группы и права RAG (модель авторизации ProtocolMenagment, перенос на MSSQL) */

IF OBJECT_ID('dbo.rag_user', 'U') IS NULL
CREATE TABLE dbo.rag_user (
                              id           BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_rag_user PRIMARY KEY,
                              full_name    NVARCHAR(255) NULL,
                              account_name NVARCHAR(128)  NOT NULL UNIQUE,          -- доменный логин (sAMAccountName)
                              email        NVARCHAR(255) NULL,
                              is_active    BIT            NOT NULL CONSTRAINT DF_rag_user_active DEFAULT (1)
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_rag_user_email')
CREATE UNIQUE INDEX UX_rag_user_email ON dbo.rag_user (email) WHERE email IS NOT NULL;

IF OBJECT_ID('dbo.rag_group', 'U') IS NULL
CREATE TABLE dbo.rag_group (
                               id          BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_rag_group PRIMARY KEY,
                               name        NVARCHAR(128) NOT NULL UNIQUE,
                               description NVARCHAR(512) NULL
);

IF OBJECT_ID('dbo.rag_permission', 'U') IS NULL
CREATE TABLE dbo.rag_permission (
                                    id              BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_rag_permission PRIMARY KEY,
                                    name            NVARCHAR(128) NOT NULL UNIQUE,        -- строка для hasAuthority(...)
                                    controller_name NVARCHAR(128) NULL,
                                    http_method     NVARCHAR(16)  NULL,
                                    url_pattern     NVARCHAR(255) NULL
);

IF OBJECT_ID('dbo.rag_user_group', 'U') IS NULL
CREATE TABLE dbo.rag_user_group (
                                    user_id  BIGINT NOT NULL CONSTRAINT FK_rag_user_group_user  FOREIGN KEY REFERENCES dbo.rag_user(id),
                                    group_id BIGINT NOT NULL CONSTRAINT FK_rag_user_group_group FOREIGN KEY REFERENCES dbo.rag_group(id),
                                    CONSTRAINT PK_rag_user_group PRIMARY KEY (user_id, group_id)
);

IF OBJECT_ID('dbo.rag_group_permission', 'U') IS NULL
CREATE TABLE dbo.rag_group_permission (
                                          group_id      BIGINT NOT NULL CONSTRAINT FK_rag_gp_group FOREIGN KEY REFERENCES dbo.rag_group(id),
                                          permission_id BIGINT NOT NULL CONSTRAINT FK_rag_gp_perm  FOREIGN KEY REFERENCES dbo.rag_permission(id),
                                          CONSTRAINT PK_rag_group_permission PRIMARY KEY (group_id, permission_id)
);

/* Права RAG */
INSERT INTO dbo.rag_permission (name, controller_name, http_method, url_pattern)
SELECT v.name, v.ctrl, v.method, v.url
FROM (VALUES
          (N'use:chat',     N'RagAsk/DirectChat/SearchController', N'*',    N'/api/rag/**'),
          (N'use:extract',  N'ExtractController',                  N'POST', N'/api/rag/extract'),
          (N'read:admin',   N'AdminController',                    N'GET',  N'/api/rag/admin/**'),
          (N'edit:admin',   N'AdminController',                    N'POST', N'/api/rag/admin/**'),
          (N'edit:prompts', N'AdminPromptController',              N'POST', N'/api/rag/admin/prompts'),
          (N'run:eval',     N'EvalController',                     N'POST', N'/api/rag/admin/eval/**'),
          (N'edit:users',   N'UserAdminController',                N'*',    N'/api/rag/admin/security/**'),
          (N'superAdmin',   N'*',                                  N'*',    N'*')
     ) AS v(name, ctrl, method, url)
WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_permission p WHERE p.name = v.name);

/* Группы */
INSERT INTO dbo.rag_group (name, description)
SELECT v.name, v.descr FROM (VALUES
                                 (N'RAG — пользователи',    N'Чат и поиск по базе знаний'),
                                 (N'RAG — интеграция',      N'Выгрузка реквизитов: файл → JSON'),
                                 (N'RAG — администраторы',  N'Админ-панель: индексация, промпты, eval, пользователи'),
                                 (N'RAG — суперадмины',     N'Полный доступ ко всему (superAdmin)')
                            ) AS v(name, descr)
WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_group g WHERE g.name = v.name);

/* Права групп */
INSERT INTO dbo.rag_group_permission (group_id, permission_id)
SELECT g.id, p.id
FROM dbo.rag_group g
         JOIN dbo.rag_permission p ON
    (g.name = N'RAG — пользователи'   AND p.name IN (N'use:chat'))
        OR (g.name = N'RAG — интеграция'     AND p.name IN (N'use:extract'))
        OR (g.name = N'RAG — администраторы' AND p.name IN (N'use:chat', N'use:extract', N'read:admin',
                                                            N'edit:admin', N'edit:prompts', N'run:eval', N'edit:users'))
        OR (g.name = N'RAG — суперадмины'    AND p.name IN (N'superAdmin'))
WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_group_permission gp
                  WHERE gp.group_id = g.id AND gp.permission_id = p.id);

/* Первый администратор: раскомментируйте и подставьте свой доменный логин */
-- INSERT INTO dbo.rag_user (full_name, account_name, email, is_active)
-- VALUES (N'Квочкин Алексей Юрьевич', N'KvochkinAY', N'KvochkinAY@itsnn.ru', 1);
-- INSERT INTO dbo.rag_user_group (user_id, group_id)
-- SELECT u.id, g.id FROM dbo.rag_user u, dbo.rag_group g
-- WHERE u.account_name = N'KvochkinAY' AND g.name = N'RAG — администраторы';