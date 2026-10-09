package quiz.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Length-delimited serialized messages with a fresh resource budget per frame. */
public final class MessageCodec {
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final String FILTER_PATTERN =
        "maxdepth=32;maxrefs=10000;maxarray=10000;maxbytes=" + MAX_MESSAGE_BYTES + ";"
        + "quiz.common.dto.*;quiz.common.enums.*;quiz.common.protocol.*;"
        + "java.lang.Object;java.lang.String;java.lang.Enum;java.lang.Number;"
        + "java.lang.Long;java.lang.Integer;java.lang.Double;java.lang.Float;"
        + "java.lang.Short;java.lang.Byte;java.lang.Boolean;java.lang.Character;"
        + "java.time.Ser;java.time.Instant;"
        + "java.util.ArrayList;java.util.LinkedList;java.util.HashMap;java.util.LinkedHashMap;"
        + "java.util.HashSet;java.util.LinkedHashSet;java.util.Map$Entry;"
        + "java.util.CollSer;java.util.ImmutableCollections$List12;java.util.ImmutableCollections$ListN;"
        + "java.util.ImmutableCollections$Map1;java.util.ImmutableCollections$MapN;"
        + "java.util.ImmutableCollections$Set12;java.util.ImmutableCollections$SetN;!*";

    private MessageCodec() { }

    /** Caller must serialize access to the shared output stream. */
    public static void write(ObjectOutputStream out, Object msg) throws IOException {
        byte[] bytes = serialize(msg);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.reset();
        out.flush();
    }

    /** A malformed frame is fatal for the connection; do not retry on that stream. */
    public static Object read(ObjectInputStream in) throws IOException, ClassNotFoundException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw new InvalidObjectException("Invalid message frame length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return deserialize(bytes);
    }

    /** Outer stream transports primitive frame data only, never serialized objects. */
    public static ObjectInputStream createFilteredInputStream(InputStream in) throws IOException {
        ObjectInputStream stream = new ObjectInputStream(in);
        stream.setObjectInputFilter(info -> ObjectInputFilter.Status.REJECTED);
        return stream;
    }

    /** Serialize one envelope, without the outer frame prefix. */
    public static byte[] serialize(Object obj) throws IOException {
        requireEnvelope(obj);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(new OutputStream() {
            private void checkCapacity(int length) throws IOException {
                if (length > MAX_MESSAGE_BYTES - buffer.size()) {
                    throw new IOException("Message exceeds maximum frame size");
                }
            }

            @Override
            public void write(int value) throws IOException {
                checkCapacity(1);
                buffer.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                checkCapacity(length);
                buffer.write(bytes, offset, length);
            }
        })) {
            stream.writeObject(obj);
        }
        return buffer.toByteArray();
    }

    public static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        Objects.requireNonNull(bytes, "Message bytes cannot be null");
        if (bytes.length == 0 || bytes.length > MAX_MESSAGE_BYTES) {
            throw new InvalidObjectException("Invalid message size");
        }
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(FILTER_PATTERN));
            Object message = stream.readObject();
            requireEnvelope(message);
            return message;
        }
    }

    private static void requireEnvelope(Object message) throws InvalidObjectException {
        if (!(message instanceof Request) && !(message instanceof Response)) {
            throw new InvalidObjectException("Expected Request or Response envelope");
        }
    }
}
