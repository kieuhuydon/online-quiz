package quiz.common.protocol;

import org.junit.jupiter.api.Test;
import quiz.common.dto.LoginRequestDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;

import java.io.InvalidClassException;

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
}
