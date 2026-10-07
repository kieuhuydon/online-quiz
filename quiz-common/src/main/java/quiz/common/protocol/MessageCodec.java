package quiz.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Bộ mã hóa / giải mã đối tượng thông điệp (MessageCodec) qua Java Serialization.
 * <p>
 * Tính năng quan trọng:
 * 1. Gọi {@code reset()} sau mỗi lần ghi để xóa cache tham chiếu của {@link ObjectOutputStream},
 *    tránh rò rỉ bộ nhớ (memory leak) trên kết nối socket duy trì lâu dài.
 * 2. Cấu hình {@link ObjectInputFilter} chỉ cho phép các package nội bộ và chuẩn của Java,
 *    ngăn chặn triệt để lỗ hổng Deserialization Attack.
 */
public final class MessageCodec {

    /**
     * Mẫu whitelist cho ObjectInputFilter:
     * Cho phép các lớp trong quiz.common.**, java.lang.*, java.util.*, java.time.*, và mảng nguyên thủy.
     * Từ chối tất cả các lớp khác (!*).
     */
    public static final String FILTER_PATTERN =
            "quiz.common.**;java.lang.*;java.util.*;java.time.*;[Lquiz.common.*;[Ljava.lang.*;[B;!*";

    private MessageCodec() {
        // Utility class
    }

    /**
     * Ghi một đối tượng thông điệp ra OutputStream, thực hiện flush và reset() ngay sau đó.
     *
     * @param out Stream ghi dữ liệu
     * @param msg Đối tượng cần gửi
     * @throws IOException nếu có lỗi I/O
     */
    public static void write(ObjectOutputStream out, Object msg) throws IOException {
        out.writeObject(msg);
        out.flush();
        out.reset(); // Ngăn rò rỉ bộ nhớ do ObjectOutputStream giữ tham chiếu các object cũ
    }

    /**
     * Đọc một đối tượng thông điệp từ ObjectInputStream.
     *
     * @param in Stream đọc dữ liệu
     * @return Đối tượng đọc được
     * @throws IOException            nếu có lỗi I/O
     * @throws ClassNotFoundException nếu lớp không tìm thấy
     */
    public static Object read(ObjectInputStream in) throws IOException, ClassNotFoundException {
        return in.readObject();
    }

    /**
     * Tạo một ObjectInputStream đã được gắn ObjectInputFilter bảo mật theo whitelist.
     *
     * @param in InputStream gốc (từ Socket hoặc luồng dữ liệu)
     * @return ObjectInputStream đã được bảo vệ
     * @throws IOException nếu có lỗi I/O
     */
    public static ObjectInputStream createFilteredInputStream(InputStream in) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(in);
        ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(FILTER_PATTERN);
        ois.setObjectInputFilter(filter);
        return ois;
    }

    /**
     * Chuyển đổi đối tượng Serializable thành mảng bytes (hữu ích cho test và logging).
     *
     * @param obj Đối tượng cần serialize
     * @return Mảng byte
     * @throws IOException nếu có lỗi serialize
     */
    public static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            write(oos, obj);
        }
        return baos.toByteArray();
    }

    /**
     * Chuyển đổi mảng bytes thành đối tượng (áp dụng bộ lọc bảo mật).
     *
     * @param bytes Mảng bytes
     * @return Đối tượng deserialize
     * @throws IOException            nếu có lỗi I/O
     * @throws ClassNotFoundException nếu không tìm thấy lớp
     */
    public static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = createFilteredInputStream(bais)) {
            return read(ois);
        }
    }
}
