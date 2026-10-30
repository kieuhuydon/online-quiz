package quiz.common.protocol;

import org.junit.jupiter.api.Test;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;


import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {

    @Test
    void testSerializeAndDeserializeRequest() throws Exception {
        LoginRequestDto loginDto = new LoginRequestDto("testuser", "secret123");
        Request request = new Request(101L, MessageType.LOGIN, null, loginDto);

        byte[] bytes = MessageCodec.serialize(request);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Object result = MessageCodec.deserialize(bytes);
        assertInstanceOf(Request.class, result);

        Request decoded = (Request) result;
        assertEquals(101L, decoded.requestId());
        assertEquals(MessageType.LOGIN, decoded.type());
        assertNull(decoded.token());
        assertInstanceOf(LoginRequestDto.class, decoded.payload());

        LoginRequestDto decodedDto = (LoginRequestDto) decoded.payload();
        assertEquals("testuser", decodedDto.username());
        assertEquals("secret123", decodedDto.password());
    }

    @Test
    void testSerializeAndDeserializeResponse() throws Exception {
        LoginResultDto resultDto = new LoginResultDto("jwt-token-xyz", 1L, "admin", "Admin User", Role.ADMIN);
        Response response = Response.success(101L, resultDto);

        byte[] bytes = MessageCodec.serialize(response);
        assertNotNull(bytes);

        Object result = MessageCodec.deserialize(bytes);
        assertInstanceOf(Response.class, result);

        Response decoded = (Response) result;
        assertEquals(101L, decoded.requestId());
        assertTrue(decoded.success());
        assertNull(decoded.errorCode());
        assertFalse(decoded.isPush());

        LoginResultDto decodedDto = (LoginResultDto) decoded.payload();
        assertEquals("jwt-token-xyz", decodedDto.token());
        assertEquals(1L, decodedDto.userId());
        assertEquals("admin", decodedDto.username());
        assertEquals(Role.ADMIN, decodedDto.role());
    }

    @Test
    void testErrorResponseSerialization() throws Exception {
        Response errorResponse = Response.error(202L, ErrorCode.INVALID_CREDENTIALS, "Tên đăng nhập hoặc mật khẩu sai");

        byte[] bytes = MessageCodec.serialize(errorResponse);
        Response decoded = (Response) MessageCodec.deserialize(bytes);

        assertEquals(202L, decoded.requestId());
        assertFalse(decoded.success());
        assertEquals(ErrorCode.INVALID_CREDENTIALS, decoded.errorCode());
        assertEquals("Tên đăng nhập hoặc mật khẩu sai", decoded.message());
        assertNull(decoded.payload());
    }

    @Test
    void testPushResponseSerialization() throws Exception {
        Response pushResponse = Response.push(MessageType.PUSH_ANTICHEAT_WARNING, "Bạn đã rời cửa sổ thi 1 lần", null);

        byte[] bytes = MessageCodec.serialize(pushResponse);
        Response decoded = (Response) MessageCodec.deserialize(bytes);

        assertEquals(0L, decoded.requestId());
        assertTrue(decoded.isPush());
        assertEquals(MessageType.PUSH_ANTICHEAT_WARNING, decoded.pushType());
        assertEquals("Bạn đã rời cửa sổ thi 1 lần", decoded.message());
    }

    @Test
    void testCreateAccountDtoSerialization() throws Exception {
        CreateAccountDto dto = new CreateAccountDto("student_new", "pass123", "Nguyen Van New", "new@quiz.center", Role.STUDENT);
        Request request = new Request(303L, MessageType.CREATE_ACCOUNT, "admin-token", dto);

        byte[] bytes = MessageCodec.serialize(request);
        Request decoded = (Request) MessageCodec.deserialize(bytes);

        assertEquals("admin-token", decoded.token());
        CreateAccountDto decodedDto = (CreateAccountDto) decoded.payload();
        assertEquals("student_new", decodedDto.username());
        assertEquals(Role.STUDENT, decodedDto.role());
    }

    @Test
    void testResponseOverloadsAndClassQuizResultsDto() throws Exception {
        java.time.Instant now = java.time.Instant.parse("2026-10-30T00:00:00Z");
        quiz.common.dto.QuizDto quizDto = new quiz.common.dto.QuizDto(
            1L, "Java Basics", "Desc", "Java", 30, 50.0,
            quiz.common.enums.QuizStatus.OPEN, "123456",
            quiz.common.enums.AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3,
            2L, now, now, null
        );
        quiz.common.dto.ResultDto resultDto = new quiz.common.dto.ResultDto(
            10L, 1L, "Java Basics", "Student A", 8.5, 17, 20, true,
            quiz.common.enums.SubmitReason.MANUAL, now
        );

        java.util.List<quiz.common.dto.QuizDto> mutableQuizzes = new java.util.ArrayList<>();
        mutableQuizzes.add(quizDto);
        java.util.List<quiz.common.dto.ResultDto> mutableResults = new java.util.ArrayList<>();
        mutableResults.add(resultDto);

        quiz.common.dto.ClassQuizResultsDto classResults =
            new quiz.common.dto.ClassQuizResultsDto(mutableQuizzes, mutableResults);
        mutableQuizzes.clear();
        mutableResults.clear();

        assertEquals(1, classResults.quizzes().size());
        assertEquals(1, classResults.results().size());
        assertThrows(UnsupportedOperationException.class, () -> classResults.quizzes().add(quizDto));
        assertThrows(UnsupportedOperationException.class, () -> classResults.results().add(resultDto));
        assertThrows(NullPointerException.class, () -> new quiz.common.dto.ClassQuizResultsDto(null, java.util.List.of()));
        assertThrows(NullPointerException.class, () -> new quiz.common.dto.ClassQuizResultsDto(java.util.List.of(), null));

        Response successWithMessage = Response.success(404L, "Loaded class results", classResults);
        assertFalse(successWithMessage.isPush());
        assertEquals("Loaded class results", successWithMessage.message());

        byte[] bytes = MessageCodec.serialize(successWithMessage);
        Response decoded = (Response) MessageCodec.deserialize(bytes);
        assertEquals(404L, decoded.requestId());
        assertEquals("Loaded class results", decoded.message());
        quiz.common.dto.ClassQuizResultsDto decodedPayload =
            assertInstanceOf(quiz.common.dto.ClassQuizResultsDto.class, decoded.payload());
        assertEquals(1, decodedPayload.quizzes().size());
        assertEquals(1, decodedPayload.results().size());

        Response pushTwoArg = Response.push(MessageType.PUSH_FORCE_SUBMITTED, resultDto);
        assertTrue(pushTwoArg.isPush());
        assertEquals(0L, pushTwoArg.requestId());
        assertNull(pushTwoArg.message());
        assertEquals(resultDto, pushTwoArg.payload());
    }
}
