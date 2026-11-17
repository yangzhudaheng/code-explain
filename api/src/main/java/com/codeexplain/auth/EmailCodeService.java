package com.codeexplain.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 邮箱验证码的签发与校验，兼发信频控。
 *
 * <p>三个设计取舍，都是被坑过才这么定的：
 *
 * <ol>
 *   <li><b>验证码用 BCrypt 存哈希，不用 SHA-256。</b>6 位数字只有 100 万种可能，
 *       库一旦泄露，SHA-256 几秒钟全爆破完；BCrypt 的慢哈希让离线爆破不划算。
 *       比对开销约 60ms，对发码频率来说可忽略。</li>
 *   <li><b>校验方法故意<b>不加</b> {@code @Transactional}。</b>验证码错误时要
 *       把 attempts+1 落库，但如果整个方法在一个事务里，抛异常会连带回滚这次自增，
 *       于是「尝试次数上限」永远不生效——这是个静默失效的坑。</li>
 *   <li><b>发信失败要删掉码。</b>否则用户没收到码，却因为 cooldown 被锁 60 秒，
 *       体验上是「点了没反应」，排查起来还以为是前端问题。</li>
 * </ol>
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    static final String PURPOSE_REGISTER = "register";
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration EMAIL_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_PER_IP_DAILY = 10;

    private final SecureRandom random = new SecureRandom();
    private final EmailCodeRepository repo;
    private final MailService mail;
    private final PasswordEncoder encoder;

    public EmailCodeService(EmailCodeRepository repo, MailService mail, PasswordEncoder encoder) {
        this.repo = repo;
        this.mail = mail;
        this.encoder = encoder;
    }

    /**
     * 生成并发送验证码。
     *
     * @return 是否进入 dev 模式（未真正发信）
     * @throws ApiException 频控命中时抛 429
     */
    public boolean issue(String email, String ip) {
        Instant last = repo.lastSentAt(email);
        if (last != null && last.plus(EMAIL_COOLDOWN).isAfter(Instant.now())) {
            long wait = Duration.between(Instant.now(), last.plus(EMAIL_COOLDOWN)).toSeconds() + 1;
            throw new ApiException(ErrorCode.RATE_LIMITED_EMAIL, "该邮箱请求过于频繁，请 " + wait + " 秒后再试");
        }
        if (ip != null && repo.countByIpLastDay(ip) >= MAX_PER_IP_DAILY) {
            throw new ApiException(ErrorCode.RATE_LIMITED_IP);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        UUID id = repo.insert(email, encoder.encode(code), PURPOSE_REGISTER,
                Instant.now().plus(CODE_TTL), ip);

        try {
            mail.sendCode(email, code, CODE_TTL);
        } catch (MailService.MailDeliveryException e) {
            repo.deleteById(id);
            throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
        }

        // 懒清理：每次发码顺手删一批陈旧的过期记录，不依赖定时任务（容器里没有常驻线程）
        try {
            repo.purgeExpired(Duration.ofDays(1));
        } catch (Exception e) {
            log.debug("清理过期验证码失败，忽略: {}", e.toString());
        }
        return mail.devMode();
    }

    /**
     * 校验并消费验证码。成功返回 true；失败抛带具体错误码的异常。
     *
     * <p>注意：不加事务，见类注释第 2 条。
     */
    public void verifyOrThrow(String email, String input) {
        if (input == null || !input.matches("\\d{6}")) {
            throw new ApiException(ErrorCode.EMAIL_CODE_INVALID);
        }
        List<EmailCodeRepository.CodeRow> rows = repo.latestActive(email, PURPOSE_REGISTER);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.EMAIL_CODE_EXPIRED);
        }
        EmailCodeRepository.CodeRow row = rows.get(0);
        if (row.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.EMAIL_CODE_EXPIRED);
        }
        if (row.attempts() >= MAX_ATTEMPTS) {
            throw new ApiException(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS);
        }
        if (!encoder.matches(input, row.codeHash())) {
            // 先自增再抛，异常抛在事务外所以一定落库
            repo.incrementAttempts(row.id());
            int left = MAX_ATTEMPTS - row.attempts() - 1;
            throw new ApiException(ErrorCode.EMAIL_CODE_INVALID,
                    left > 0 ? "邮箱验证码不正确，还可再试 " + left + " 次" : "验证码错误次数过多，请重新获取");
        }
        repo.markConsumed(row.id());
    }
}
