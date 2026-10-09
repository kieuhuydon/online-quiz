package quiz.server.security;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Tiện ích băm và kiểm tra mật khẩu bằng thuật toán BCrypt.
 */
public final class PasswordHasher {
    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);
    private static final int BCRYPT_LOG_ROUNDS = 10;
    private static final int MAX_PASSWORD_BYTES = 72;
    // Generated hashes use $2a$; bound verification cost as well as syntax.
    private static final Pattern HASH_PATTERN = Pattern.compile("\\$2a\\$(0[4-9]|1[0-4])\\$[./A-Za-z0-9]{53}");

    private PasswordHasher() {
    }

    /**
     * Băm mật khẩu dạng văn bản rõ (plain text) bằng BCrypt salt 10 vòng.
     *
     * @param plainPassword Mật khẩu thô cần băm
     * @return Chuỗi băm BCrypt ($2a$10$...)
     */
    public static String hashPassword(String plainPassword) {
        if (!isValidPassword(plainPassword)) {
            throw new IllegalArgumentException("Password must be nonblank and at most 72 UTF-8 bytes");
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
        if (!isValidPassword(plainPassword) || hashedPassword == null
            || !HASH_PATTERN.matcher(hashedPassword).matches()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, hashedPassword);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            log.warn("Invalid BCrypt hash format encountered");
            return false;
        }
    }

    private static boolean isValidPassword(String password) {
        return password != null && !password.isBlank() && password.length() <= MAX_PASSWORD_BYTES
            && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
