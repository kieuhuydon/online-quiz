package quiz.client.ui.login;

import org.junit.jupiter.api.Test;
import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.common.protocol.ErrorCode;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class LoginMessagesTest {
    @Test void messagesAreSafeAndDistinguishUserActions() {
        assertNotEquals(LoginMessages.message(new ApiException(ErrorCode.INVALID_CREDENTIALS)),
            LoginMessages.message(new ApiException(ErrorCode.ACCOUNT_DISABLED)));
        assertTrue(LoginMessages.message(new CompletionException(new TransportException(TransportException.Kind.TIMEOUT))).contains("thời gian"));
        assertFalse(LoginMessages.message(new IllegalStateException("secret password SQL token")).contains("secret"));
        assertFalse(LoginMessages.message(new ApiException(ErrorCode.DATABASE_ERROR)).contains("DATABASE_ERROR"));
    }
}
