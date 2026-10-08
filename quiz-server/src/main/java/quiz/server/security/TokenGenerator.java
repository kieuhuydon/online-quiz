package quiz.server.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Tiện ích sinh token phiên ngẫu nhiên bảo mật cao (CSPRNG) và mã PIN đề thi.
 */
public final class TokenGenerator {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int DEFAULT_TOKEN_BYTES = 24; // 192 bits, vượt mức tối thiểu 128 bit

    private TokenGenerator() {
    }

    /**
     * Sinh token phiên làm việc ngẫu nhiên (URL-safe Base64, 192 bits entropy).
     *
     * @return Chuỗi token ngẫu nhiên độ dài 32 ký tự
     */
    public static String generateToken() {
        return generateToken(DEFAULT_TOKEN_BYTES);
    }

    /**
     * Sinh token phiên với số byte entropy tùy biến.
     *
     * @param byteCount Số byte ngẫu nhiên (tối thiểu 16 bytes = 128 bits)
     * @return Chuỗi token URL-safe
     */
    public static String generateToken(int byteCount) {
        if (byteCount < 16) {
            throw new IllegalArgumentException("Token entropy must be at least 16 bytes (128 bits)");
        }
        byte[] randomBytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Sinh mã PIN số ngẫu nhiên cho bài thi (mặc định 6 chữ số: 100000 -> 999999).
     *
     * @return Chuỗi PIN 6 chữ số
     */
    public static String generatePin() {
        return generatePin(6);
    }

    /**
     * Sinh mã PIN số ngẫu nhiên theo độ dài mong muốn.
     *
     * @param digits Số lượng chữ số (từ 4 đến 10)
     * @return Chuỗi PIN dạng số
     */
    public static String generatePin(int digits) {
        if (digits < 4 || digits > 10) {
            throw new IllegalArgumentException("PIN digits must be between 4 and 10");
        }
        int min = (int) Math.pow(10, digits - 1);
        int max = (int) Math.pow(10, digits) - 1;
        int pin = min + SECURE_RANDOM.nextInt(max - min + 1);
        return String.valueOf(pin);
    }
}
