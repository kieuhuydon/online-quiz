package quiz.common.enums;

/**
 * Loại hành vi vi phạm mà client phát hiện và báo cáo lên server.
 */
public enum AntiCheatEventType {
    FOCUS_LOST,              // Rời khỏi cửa sổ thi / chuyển tab / minimize
    COPY_ATTEMPT,            // Cố gắng copy nội dung (Ctrl+C / Ctrl+Insert)
    PASTE_ATTEMPT,           // Cố gắng paste nội dung (Ctrl+V / Shift+Insert)
    CUT_ATTEMPT,             // Cố gắng cut nội dung (Ctrl+X)
    CONTEXT_MENU_ATTEMPT     // Bấm chuột phải mở context menu
}
