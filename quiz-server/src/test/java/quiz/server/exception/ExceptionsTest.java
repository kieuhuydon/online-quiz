package quiz.server.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.protocol.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Server Exceptions Unit Tests")
class ExceptionsTest {

    @Test
    @DisplayName("QuizException should store ErrorCode and message accurately")
    void testQuizException() {
        QuizException ex = new QuizException(ErrorCode.INTERNAL_ERROR, "Internal server issue");
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertEquals("Internal server issue", ex.getMessage());

        Throwable cause = new RuntimeException("Root cause");
        QuizException exWithCause = new QuizException(ErrorCode.DATABASE_ERROR, "DB failure", cause);
        assertEquals(ErrorCode.DATABASE_ERROR, exWithCause.getErrorCode());
        assertEquals("DB failure", exWithCause.getMessage());
        assertEquals(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("ValidationException should have VALIDATION_ERROR code")
    void testValidationException() {
        ValidationException ex = new ValidationException("Invalid username");
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertEquals("Invalid username", ex.getMessage());
    }

    @Test
    @DisplayName("UnauthorizedException should have UNAUTHENTICATED code")
    void testUnauthorizedException() {
        UnauthorizedException ex = new UnauthorizedException("Session expired");
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
        assertEquals("Session expired", ex.getMessage());
    }

    @Test
    @DisplayName("ForbiddenException should have FORBIDDEN code")
    void testForbiddenException() {
        ForbiddenException ex = new ForbiddenException("Access denied");
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("Access denied", ex.getMessage());
    }

    @Test
    @DisplayName("NotFoundException should have NOT_FOUND code")
    void testNotFoundException() {
        NotFoundException ex = new NotFoundException("Quiz not found");
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("Quiz not found", ex.getMessage());
    }

    @Test
    @DisplayName("ConflictException should have CONFLICT code")
    void testConflictException() {
        ConflictException ex = new ConflictException("Username already exists");
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("Username already exists", ex.getMessage());
    }
}
