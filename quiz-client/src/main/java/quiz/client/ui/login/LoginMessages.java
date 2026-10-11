package quiz.client.ui.login;

import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.client.util.FxAsync;

/** Local text selected by error code, never by server messages or credential values. */
public final class LoginMessages {
    private LoginMessages() { }

    public static String message(Throwable failure) {
        Throwable cause = FxAsync.unwrap(failure);
        if (cause instanceof ApiException api) return switch (api.errorCode()) {
            case VALIDATION_ERROR -> "Tên đăng nhập cần có 1–50 ký tự; mật khẩu không được trống và tối đa 72 byte UTF-8.";
            case INVALID_CREDENTIALS -> "Tên đăng nhập hoặc mật khẩu không đúng.";
            case ACCOUNT_DISABLED -> "Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên.";
            case CONFLICT -> "Kết nối đang có phiên khác. Vui lòng đăng nhập lại trên kết nối mới.";
            case UNAUTHENTICATED -> "Phiên kết nối đã hết hiệu lực. Vui lòng đăng nhập lại.";
            case SERVER_BUSY -> "Máy chủ đang bận. Vui lòng thử lại.";
            case FORBIDDEN -> "Tài khoản không có quyền thực hiện thao tác này.";
            default -> "Máy chủ chưa thể xử lý đăng nhập. Vui lòng thử lại sau.";
        };
        if (cause instanceof TransportException transport) return switch (transport.kind()) {
            case TIMEOUT -> "Kết nối quá thời gian chờ. Vui lòng thử lại.";
            case CONNECTION_LOST -> "Không thể kết nối hoặc đã mất kết nối với máy chủ. Vui lòng thử lại.";
            case PROTOCOL -> "Phản hồi của máy chủ không hợp lệ. Vui lòng kết nối lại.";
            case OVERLOADED -> "Ứng dụng đang bận. Vui lòng thử lại.";
            case CLOSED -> "Kết nối đã đóng. Vui lòng mở lại ứng dụng.";
        };
        return "Không thể đăng nhập. Vui lòng thử lại.";
    }
}
