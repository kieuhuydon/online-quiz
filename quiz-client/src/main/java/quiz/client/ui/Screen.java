package quiz.client.ui;

import quiz.common.enums.Role;

/** Phase 5.2 routes; teacher features for administrators are added with the corresponding modules. */
public enum Screen {
    LOGIN("/quiz/client/ui/login/login.fxml", "Đăng nhập", null),
    ADMIN_ACCOUNTS("/quiz/client/ui/admin/account/account.fxml", "Quản lý tài khoản", Role.ADMIN),
    TEACHER_DASHBOARD("/quiz/client/ui/teacher/dashboard.fxml", "Không gian giảng dạy", Role.TEACHER),
    STUDENT_DASHBOARD("/quiz/client/ui/student/dashboard.fxml", "Không gian học tập", Role.STUDENT),
    STUDENT_EXAM("/quiz/client/ui/student/exam.fxml", "Đang làm bài", Role.STUDENT),
    STUDENT_RESULT("/quiz/client/ui/student/result.fxml", "Kết quả bài thi", Role.STUDENT),
    STUDENT_HISTORY("/quiz/client/ui/student/history.fxml", "Lịch sử làm bài", Role.STUDENT);

    private final String resource;
    private final String title;
    private final Role role;

    Screen(String resource, String title, Role role) {
        this.resource = resource;
        this.title = title;
        this.role = role;
    }

    public String resource() { return resource; }
    public String title() { return title; }
    public Role role() { return role; }

    public static Screen home(Role role) {
        if (role == null) return LOGIN;
        return switch (role) {
            case ADMIN -> ADMIN_ACCOUNTS;
            case TEACHER -> TEACHER_DASHBOARD;
            case STUDENT -> STUDENT_DASHBOARD;
        };
    }

    public static String roleLabel(Role role) {
        return switch (role) {
            case ADMIN -> "Quản trị viên";
            case TEACHER -> "Giáo viên";
            case STUDENT -> "Học viên";
        };
    }
}
