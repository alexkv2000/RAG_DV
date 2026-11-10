package kvo.itsnn.rag.refsync;

public enum RefStatus {
    NEW('N'), IN_PROGRESS('P'), INDEXED('I'), DELETED('D'), ERROR('E'), PURGED('X');
    public final char code;
    RefStatus(char c) { code = c; }
}