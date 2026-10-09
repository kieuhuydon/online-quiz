package quiz.common.protocol;

import org.junit.jupiter.api.Test;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageCodecSecurityTest {
    private byte[] rawSerialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @Test
    void rejectsDisallowedClassInsideEnvelope() throws Exception {
        byte[] data = rawSerialize(new Request(1, MessageType.LOGIN, null, new File("unused")));
        assertThrows(InvalidClassException.class, () -> MessageCodec.deserialize(data));
    }

    @Test
    void rejectsOversizedStringsWithoutWritingPartialFrame() throws Exception {
        var request = new Request(1, MessageType.LOGIN, null,
            new LoginRequestDto("user", "x".repeat(MessageCodec.MAX_MESSAGE_BYTES)));
        byte[] raw = rawSerialize(request);
        assertThrows(InvalidObjectException.class, () -> MessageCodec.deserialize(raw));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            assertThrows(IOException.class, () -> MessageCodec.write(out, request));
            MessageCodec.write(out, Response.success(2, "ok"));
        }
        try (var in = MessageCodec.createFilteredInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(2, ((Response) MessageCodec.read(in)).requestId());
        }
    }

    @Test
    void rejectsArrayAndDepthResourceAbuse() throws Exception {
        byte[] array = rawSerialize(new Request(1, MessageType.LOGIN, null, new byte[10001]));
        assertThrows(InvalidClassException.class, () -> MessageCodec.deserialize(array));
        ArrayList<Object> nested = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ArrayList<Object> parent = new ArrayList<>();
            parent.add(nested);
            nested = parent;
        }
        byte[] deep = rawSerialize(new Request(1, MessageType.LOGIN, null, nested));
        assertThrows(InvalidClassException.class, () -> MessageCodec.deserialize(deep));
    }

    @Test
    void rejectsInvalidFrameLengthsAndTruncatedFrames() throws Exception {
        for (int length : new int[]{-1, 0, MessageCodec.MAX_MESSAGE_BYTES + 1, 128}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeInt(length);
            }
            try (var in = MessageCodec.createFilteredInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertThrows(IOException.class, () -> MessageCodec.read(in));
            }
        }
    }

    @Test
    void rejectsObjectsInjectedIntoOuterStreamAndNonEnvelopeFrames() throws Exception {
        byte[] injected = rawSerialize(new ArrayList<>());
        try (var in = MessageCodec.createFilteredInputStream(new ByteArrayInputStream(injected))) {
            assertThrows(IOException.class, () -> MessageCodec.read(in));
        }
        byte[] nonEnvelope = rawSerialize("not an envelope");
        assertThrows(InvalidObjectException.class, () -> MessageCodec.deserialize(nonEnvelope));
    }

    @Test
    void budgetResetsForEachFrameOnLongLivedConnection() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            for (int i = 0; i < 100; i++) {
                MessageCodec.write(out, Response.success(i, "x".repeat(16000)));
            }
        }
        assertTrue(bytes.size() > MessageCodec.MAX_MESSAGE_BYTES);
        try (var in = MessageCodec.createFilteredInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (int i = 0; i < 100; i++) {
                assertEquals(i, ((Response) MessageCodec.read(in)).requestId());
            }
        }
    }

    @Test
    void supportsDtoCollectionsAndInstant() throws Exception {
        var immutable = new AttemptStateDto(1, 2, "Quiz", 60, List.of(), Map.of(1L, 'A'));
        var mutable = new AttemptStateDto(1, 2, "Quiz", 60, new ArrayList<>(), new HashMap<>(Map.of(1L, 'A')));
        var account = new AccountDto(1, "u", "User", null, Role.STUDENT, true, 2L, Instant.now());
        for (Serializable dto : List.of(immutable, mutable, account)) {
            Response response = Response.success(1, dto);
            assertEquals(response, MessageCodec.deserialize(MessageCodec.serialize(response)));
        }
    }
}
