package quiz.server.security;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiện ích băm và kiểm tra mật khẩu bằng thuật toán BCrypt.
 */
public final class PasswordHasher {
    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);
    private static final int BCRYPT_LOG_ROUNDS = 10;

    private PasswordHasher() {
    }

    /**
     * Băm mật khẩu dạng văn bản rõ (plain text) bằng BCrypt salt 10 vòng.
     *
     * @param plainPassword Mật khẩu thô cần băm
     * @return Chuỗi băm BCrypt ($2a$10$...)
     */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or blank");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    /**
     * Kiểm tra so khớp giữa mật khẩu người dùng nhập và chuỗi băm BCrypt trong cơ sở dữ liệu.
     *
     * @param plainPassword  Mật khẩu người dùng nhập
     * @param hashedPassword Chuỗi băm BCrypt lưu trong CSDL
     * @return true nếu khớp, ngược lại false
     */
    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        if (plainPassword == null || hashedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, hashedPassword);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid BCrypt hash format encountered: {}", e.getMessage());
            return false;
        }
    }
}
