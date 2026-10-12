package quiz.common.protocol;

import java.io.Serializable;

/**
 * Envelope thông điệp yêu cầu từ Client gửi lên Server.
 *
 * @param requestId Mã định danh yêu cầu do client tự tăng (AtomicLong) để ghép với Response
 * @param type      Loại thông điệp yêu cầu
 * @param token     Mã phiên làm việc (null nếu là LOGIN)
 * @param payload   Dữ liệu đính kèm (DTO Serializable) hoặc null
 */
public record Request(
    long requestId,
    MessageType type,
    String token,
    Serializable payload
) implements Serializable {
}
