package quiz.server.dispatch;

import org.junit.jupiter.api.Test;
import quiz.common.dto.AccountDto;
import quiz.common.dto.LoginResultDto;
import quiz.common.enums.Role;
import quiz.common.protocol.MessageCodec;
import quiz.common.protocol.Response;
import quiz.server.service.AuthResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DtoMapperTest {
    @Test
    void accountCopiesPublicFieldsAndCodecDoesNotContainHash() throws Exception {
        var user = DispatchTestUsers.user(8, Role.STUDENT);
        AccountDto dto = DtoMapper.account(user);
        assertEquals(user.id(), dto.id());
        assertEquals(user.username(), dto.username());
        assertEquals(user.fullName(), dto.fullName());
        assertEquals(user.email(), dto.email());
        assertEquals(user.role(), dto.role());
        assertEquals(user.active(), dto.active());
        assertEquals(user.createdBy(), dto.createdBy());
        assertEquals(user.createdAt(), dto.createdAt());
        byte[] bytes = MessageCodec.serialize(Response.success(42, dto));
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(DispatchTestUsers.HASH));
        assertEquals(dto, ((Response) MessageCodec.deserialize(bytes)).payload());
    }

    @Test
    void loginMapsUsernameAlongWithTokenIdNameAndRole() throws Exception {
        var user = DispatchTestUsers.user(7, Role.ADMIN);
        LoginResultDto dto = DtoMapper.login(new AuthResult("issued-token", user));
        assertEquals(new LoginResultDto("issued-token", 7, user.username(), user.fullName(), Role.ADMIN), dto);
        byte[] bytes = MessageCodec.serialize(Response.success(42, dto));
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(DispatchTestUsers.HASH));
        assertEquals(dto, ((Response) MessageCodec.deserialize(bytes)).payload());
    }

    @Test
    void accountListIsSerializableArrayListWithOrderNullableFieldsAndInstant() throws Exception {
        var users = List.of(DispatchTestUsers.user(7, Role.ADMIN), DispatchTestUsers.user(8, Role.STUDENT));
        var dtos = DtoMapper.accounts(users);
        assertInstanceOf(ArrayList.class, dtos);
        assertNull(dtos.get(0).email());
        assertNull(dtos.get(0).createdBy());
        assertEquals(7, dtos.get(0).id());
        assertEquals(8, dtos.get(1).id());
        assertEquals(dtos, ((Response) MessageCodec.deserialize(MessageCodec.serialize(Response.success(42, dtos)))).payload());
        assertTrue(DtoMapper.accounts(List.of()).isEmpty());
    }
}
