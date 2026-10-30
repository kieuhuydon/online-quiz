package quiz.client.ui.teacher.question;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.common.protocol.ErrorCode;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QuestionMessages Unit Tests")
class QuestionMessagesTest {

    @Test
    @DisplayName("Ánh xạ mã lỗi câu hỏi sang thông báo tiếng Việt an toàn")
    void mapsQuestionErrorsSafely() {
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.CONFLICT)).contains("không thể sửa"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.VALIDATION_ERROR)).contains("không hợp lệ"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.FORBIDDEN)).contains("không có quyền"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.UNAUTHENTICATED)).contains("đăng nhập lại"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.NOT_FOUND)).contains("không còn tồn tại"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.SERVER_BUSY)).contains("bận"));
        assertFalse(QuestionMessages.message(new ApiException(ErrorCode.DATABASE_ERROR)).contains("DATABASE_ERROR"));
        assertTrue(QuestionMessages.message(new ApiException(ErrorCode.INTERNAL_ERROR)).contains("tải lại"));

        assertTrue(QuestionMessages.message(
            new CompletionException(new TransportException(TransportException.Kind.OVERLOADED))
        ).contains("chưa được gửi"));

        assertTrue(QuestionMessages.message(
            new TransportException(TransportException.Kind.CONNECTION_LOST)
        ).contains("Chưa xác nhận"));
    }
}
