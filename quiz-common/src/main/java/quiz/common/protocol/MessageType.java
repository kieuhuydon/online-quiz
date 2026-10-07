package quiz.common.protocol;

import java.io.Serializable;

/**
 * Danh mục tất cả các loại thông điệp giao tiếp giữa Client và Server.
 */
public enum MessageType implements Serializable {
    // 1. Authentication & Session
    LOGIN,
    LOGOUT,

    // 2. Account Management (ADMIN)
    CREATE_ACCOUNT,
    LIST_ACCOUNTS,
    SET_ACCOUNT_ACTIVE,

    // 3. Class Management (TEACHER, ADMIN)
    CREATE_CLASS,
    LIST_CLASSES,
    ADD_CLASS_MEMBER,
    REMOVE_CLASS_MEMBER,
    LIST_CLASS_MEMBERS,

    // 4. Question Bank (TEACHER, ADMIN)
    CREATE_QUESTION,
    UPDATE_QUESTION,
    GET_QUESTION,
    LIST_QUESTIONS,
    DEACTIVATE_QUESTION,

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
    GET_QUIZ_STATISTICS,    // TEACHER, ADMIN
    LIST_ATTEMPT_EVENTS,    // TEACHER, ADMIN

    // 9. Anti-Cheat Monitoring (STUDENT -> SERVER)
    REPORT_ANTICHEAT_EVENT,

    // 10. Server Push Notifications (SERVER -> CLIENT)
    PUSH_ANTICHEAT_WARNING,
    PUSH_FORCE_SUBMITTED
}
