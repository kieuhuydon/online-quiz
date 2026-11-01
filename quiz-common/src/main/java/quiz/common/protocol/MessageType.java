package quiz.common.protocol;

/**
 * Danh mục tất cả các loại thông điệp giao tiếp giữa Client và Server.
 */
public enum MessageType {
    // 1. Authentication & Session
    LOGIN,
    LOGOUT,
    HEARTBEAT,

    // 2. Account Management (ADMIN)
    CREATE_ACCOUNT,
    LIST_ACCOUNTS,
    SET_ACCOUNT_ACTIVE,
    DELETE_ACCOUNT,

    // 3. Class Management: writes ADMIN; teacher reads only assigned classes.
    CREATE_CLASS,
    DELETE_CLASS,
    LIST_CLASSES,
    ADD_CLASS_MEMBER,
    REMOVE_CLASS_MEMBER,
    LIST_CLASS_MEMBERS,
    LIST_UNENROLLED_STUDENTS,
    LIST_MY_CLASSES,

    // 4. Question Bank (TEACHER, ADMIN)
    CREATE_QUESTION,
    UPDATE_QUESTION,
    GET_QUESTION,
    LIST_QUESTIONS,
    DEACTIVATE_QUESTION,
    BATCH_DEACTIVATE_QUESTIONS,

    // 5. Quiz Management (TEACHER, ADMIN)
    CREATE_QUIZ,
    UPDATE_QUIZ,
    GET_QUIZ,
    LIST_QUIZZES,
    SET_QUIZ_QUESTIONS,
    OPEN_QUIZ,
    CLOSE_QUIZ,
    ASSIGN_QUIZ,

    // 6. Student Quiz Flow (STUDENT)
    LIST_ASSIGNED_QUIZZES,
    GET_QUIZ_INFO,
    VERIFY_QUIZ_PIN,

    // 7. Attempt & Exam (STUDENT)
    START_ATTEMPT,
    SAVE_ANSWER,
    SUBMIT_ATTEMPT,
    GET_ATTEMPT_STATE,

    // 8. Results & Statistics
    GET_MY_RESULT,          // STUDENT
    LIST_MY_HISTORY,        // STUDENT
    LIST_QUIZ_RESULTS,      // TEACHER, ADMIN
    GET_CLASS_QUIZ_RESULTS, // TEACHER
    GET_QUIZ_STATISTICS,    // TEACHER, ADMIN
    LIST_ATTEMPT_EVENTS,    // TEACHER, ADMIN
    LIST_EXAM_SESSIONS,
    GET_EXAM_SESSION,
    APPLY_MONITORING_PENALTY,
    LIST_MONITORING_PENALTIES,

    // 9. Anti-Cheat Monitoring (STUDENT -> SERVER)
    REPORT_ANTICHEAT_EVENT,

    // 10. Server Push Notifications (SERVER -> CLIENT)
    PUSH_ANTICHEAT_WARNING,
    PUSH_FORCE_SUBMITTED
}
