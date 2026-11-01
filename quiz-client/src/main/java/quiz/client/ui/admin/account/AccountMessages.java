package quiz.client.ui.admin.account;

import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.client.util.FxAsync;

/** Display local messages so server errors cannot expose account data in the UI. */
final class AccountMessages {
    private AccountMessages() { }

    static String message(Throwable failure) {
        Throwable cause = FxAsync.unwrap(failure);
        if (cause instanceof ApiException business) {
            return switch (business.errorCode()) {
                case USERNAME_ALREADY_EXISTS -> "Tên đăng nhập đã tồn tại. Vui lòng chọn tên khác.";
                case EMAIL_ALREADY_EXISTS -> "Email đã được sử dụng. Không thể tạo tài khoản với email này.";
                case CONFLICT -> "Thông tin bị xung đột. Vui lòng kiểm tra lại và thử lại.";
                case VALIDATION_ERROR -> "Thông tin tài khoản không hợp lệ. Kiểm tra lại các trường.";
                case FORBIDDEN -> "Bạn không có quyền thực hiện thao tác này. Tài khoản Admin được bảo vệ, không thể bị khóa hoặc xóa.";
                case UNAUTHENTICATED -> "Vui lòng đăng nhập lại để tiếp tục.";
                case NOT_FOUND -> "Tài khoản không còn tồn tại. Đang tải lại danh sách.";
                case SERVER_BUSY -> "Máy chủ đang bận. Hãy thử lại sau.";
                case DATABASE_ERROR -> "Không thể xử lý dữ liệu. Hãy thử lại sau.";
                default -> "Không thể hoàn tất yêu cầu. Hãy tải lại danh sách để kiểm tra.";
            };
        }
        if (cause instanceof TransportException transport
            && transport.kind() == TransportException.Kind.OVERLOADED) {
            return "Có quá nhiều yêu cầu đang chờ. Yêu cầu này chưa được gửi.";
        }
        return "Chưa xác nhận được kết quả. Đăng nhập lại và tải danh sách để kiểm tra trước khi thực hiện lại.";
    }
}
