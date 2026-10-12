package quiz.common.protocol;

import java.io.Serializable;

/**
 * Envelope thông điệp phản hồi từ Server gửi về Client hoặc thông báo PUSH.
 *
 * @param requestId Trùng với requestId của Request tương ứng; giá trị 0 nếu là thông điệp PUSH
 * @param success   Trạng thái thành công hay thất bại
 * @param errorCode Mã lỗi nếu thất bại (null nếu thành công)
 * @param message   Thông báo hiển thị cho người dùng
 * @param pushType  Loại sự kiện PUSH (null nếu là phản hồi thông thường; khác null nếu là PUSH)
 * @param payload   Dữ liệu kết quả đính kèm (DTO Serializable) hoặc null
 */
public record Response(
    long requestId,
    boolean success,
    ErrorCode errorCode,
    String message,
    MessageType pushType,
    Serializable payload
) implements Serializable {

    /**
     * Tạo Response thành công cho một request.
     */
    public static Response success(long requestId, Serializable payload) {
        return new Response(requestId, true, null, null, null, payload);
    }

    /**
     * Tạo Response thành công kèm thông báo.
     */
    public static Response success(long requestId, String message, Serializable payload) {
        return new Response(requestId, true, null, message, null, payload);
    }

    /**
     * Tạo Response báo lỗi cho một request.
     */
    public static Response error(long requestId, ErrorCode errorCode, String message) {
        return new Response(requestId, false, errorCode, message, null, null);
    }

    /**
     * Tạo thông điệp PUSH chủ động từ server xuống client.
     */
    public static Response push(MessageType pushType, Serializable payload) {
        return new Response(0L, true, null, null, pushType, payload);
    }

    /**
     * Tạo thông điệp PUSH chủ động từ server kèm thông báo.
     */
    public static Response push(MessageType pushType, String message, Serializable payload) {
        return new Response(0L, true, null, message, pushType, payload);
    }

    /**
     * Kiểm tra xem phản hồi có phải là thông điệp PUSH không.
     */
    public boolean isPush() {
        return pushType != null;
    }
}
