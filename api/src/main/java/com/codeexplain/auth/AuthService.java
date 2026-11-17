package com.codeexplain.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 注册与登录的业务规则。 */
@Service
public class AuthService {

    /**
     * 登录失败时用来「陪跑」一次 BCrypt 比对的哑哈希。
     *
     * <p>没有它的话，邮箱不存在时立刻返回，而存在时慢 60ms，
     * 攻击者靠响应时间就能枚举出哪些邮箱注册过。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final EmailCodeService codes;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, EmailCodeService codes, PasswordEncoder encoder) {
        this.users = users;
        this.codes = codes;
        this.encoder = encoder;
    }

    public UserRepository.AuthUser register(String rawEmail, String code, String password, String displayName) {
        String email = normalizeEmail(rawEmail);
        validatePassword(password);

        // 先查重再验码：邮箱已注册时不该把用户的验证码消费掉
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        codes.verifyOrThrow(email, code);

        String hash = encoder.encode(password);
        try {
            return users.insert(email, hash, trimToNull(displayName), Instant.now());
        } catch (DuplicateKeyException e) {
            // 并发注册同一邮箱：唯一索引是最终防线
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
    }

    public UserRepository.AuthUser login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        var found = users.findByEmail(email);
        if (found.isEmpty()) {
            encoder.matches(password == null ? "" : password, DUMMY_HASH);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        var user = found.get();
        if (!encoder.matches(password == null ? "" : password, user.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * 密码策略。上限 72 <b>字节</b>不是随便定的：BCrypt 只处理前 72 字节，
     * 超长部分被静默丢弃，用户以为设了强密码其实后半段完全无效。
     */
    static void validatePassword(String password) {
        if (password == null) {
            throw new ApiException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(ErrorCode.PASSWORD_TOO_WEAK, "密码过长（上限 72 字节）");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (password.length() < 8 || !hasLetter || !hasDigit) {
            throw new ApiException(ErrorCode.PASSWORD_TOO_WEAK);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
