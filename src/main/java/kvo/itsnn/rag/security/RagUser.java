package kvo.itsnn.rag.security;

/**
 * Пользователь RAG (dbo.rag_user). Пароля нет: аутентификация — LDAP bind к AD,
 * как в ProtocolMenagment; эта таблица хранит только факт «логин известен системе»,
 * активность и принадлежность группам (права).
 */
public record RagUser(Long id, String fullName, String accountName, String email, boolean isActive) {}