package quiz.client.ui.admin.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.common.protocol.ErrorCode;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AccountMessages Unit Tests")
class AccountMessagesTest {

    @Test
    @DisplayName("Ánh xạ đầy đủ các mã lỗi sang thông báo tiếng Việt an toàn, không lộ dữ liệu nhạy cảm")
    void mapsAllErrorCodesAndTransportFailuresSafely() {
        assertNotEquals(
            AccountMessages.message(new ApiException(ErrorCode.USERNAME_ALREADY_EXISTS)),
            AccountMessages.message(new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS))
        );
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.CONFLICT)).contains("xung đột"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.VALIDATION_ERROR)).contains("không hợp lệ"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.FORBIDDEN)).contains("Admin"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.UNAUTHENTICATED)).contains("đăng nhập lại"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.NOT_FOUND)).contains("không còn tồn tại"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.SERVER_BUSY)).contains("bận"));
        assertFalse(AccountMessages.message(new ApiException(ErrorCode.DATABASE_ERROR)).contains("DATABASE_ERROR"));
        assertTrue(AccountMessages.message(new ApiException(ErrorCode.INTERNAL_ERROR)).contains("tải lại"));

        assertTrue(AccountMessages.message(
            new CompletionException(new TransportException(TransportException.Kind.OVERLOADED))
        ).contains("chưa được gửi"));

        String unknownOutcome = AccountMessages.message(new TransportException(TransportException.Kind.TIMEOUT));
        assertTrue(unknownOutcome.contains("Chưa xác nhận"));
        assertFalse(AccountMessages.message(new RuntimeException("secret SQL password")).contains("secret"));
    }
}
