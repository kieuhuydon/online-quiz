package quiz.client.ui.teacher.question;

import quiz.client.api.ApiException;
import quiz.client.network.TransportException;
import quiz.client.util.FxAsync;

/** Hiển thị thông báo địa phương để lỗi server không rò rãi dữ liệu câu hỏi ra UI. */
final class QuestionMessages {
    private QuestionMessages() {
    }

    static String message(Throwable failure) {
        Throwable cause = FxAsync.unwrap(failure);
        if (cause instanceof ApiException business) {
            return switch (business.errorCode()) {
                case CONFLICT -> "Dữ liệu đã tồn tại hoặc câu hỏi không thể sửa tại trạng thái hiện tại.";
                case VALIDATION_ERROR -> "Thông tin câu hỏi không hợp lệ. Vui lòng kiểm tra lại các trường theo hướng dẫn.";
                case FORBIDDEN -> "Bạn không có quyền quản lý câu hỏi.";
                case UNAUTHENTICATED -> "Phiên làm việc đã kết thúc. Vui lòng đăng nhập lại.";
                case NOT_FOUND -> "Câu hỏi không còn tồn tại. Đang tải lại danh sách.";
                case SERVER_BUSY -> "Máy chủ đang bận. Hãy thử lại sau.";
                case DATABASE_ERROR -> "Lỗi lưu trữ cơ sở dữ liệu. Vui lòng kiểm tra lại hoặc liên hệ quản trị viên.";
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
