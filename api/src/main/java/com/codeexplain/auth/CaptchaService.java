package com.codeexplain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.wf.captcha.SpecCaptcha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 图形验证码（4 位字符）。
 *
 * <p>职责边界要说清：图形码<b>不是</b>防刷主力，它只挡「脚本无脑刷接口」。
 * 真正的防线是发邮件时的频控（同邮箱 60s + 同 IP 每日上限），见 {@link EmailCodeService}。
 *
 * <p><b>为什么不用算术题 {@code ArithmeticCaptcha}</b>：它内部用 Nashorn（{@code javax.script}
 * 的 JS 引擎）把算式求值，而 JDK 15 起官方移除了 Nashorn，JDK 21 上 {@code text()} 直接
 * NullPointerException——接口上线就是 500。这个坑是测试跑出来的，不是猜的。
 *
 * <p><b>答案为什么落库而不是存内存</b>：Vercel 容器闲置 5 分钟就缩容、多实例之间也不共享
 * Caffeine。用户打开登录页拿到图，输完点提交时请求可能落到另一个实例，答案是 null，
 * 于是「刚生成的验证码」提示已过期。这类问题本地永远复现不了，只能靠共享存储解决。
 *
 * <p><b>答案为什么用 SHA-256 而不是 BCrypt</b>：4 位字符空间只有 36^4 ≈ 170 万，
 * 慢哈希挡不住离线爆破，却要给每次校验加 60ms 延迟；真正的防线是 5 分钟 TTL 与
 * 「校验一次即销毁」。哈希的意义仅在于库被导出时不直接暴露明文答案。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 只出数字+大写字母：避开大小写歧义，用户不用来回切 Shift。
     *
     * <p>用全限定名是因为本类自带一个同名的 {@code Captcha} record，
     * 直接把 {@code com.wf.captcha.base.Captcha} 导进来会撞名。
     */
    private static final int CHAR_TYPE = com.wf.captcha.base.Captcha.TYPE_NUM_AND_UPPER;

    private final CaptchaRepository repo;
    private final SecureRandom random = new SecureRandom();

    /**
     * 把答案打到日志，<b>仅供本地自动化测试</b>。
     * 默认关闭；生产环境绝不能开，等于把验证码白送。
     */
    private final boolean debugAnswer;

    public CaptchaService(CaptchaRepository repo,
                          @Value("${app.auth.debug-captcha:false}") boolean debugAnswer) {
        this.repo = repo;
        this.debugAnswer = debugAnswer;
    }

    /** 生成一张新图；id 回给前端，答案（的哈希）落库。 */
    public Captcha create() {
        return create(null);
    }

    /** @param ip 仅用于排查刷码，不参与鉴权 */
    public Captcha create(String ip) {
        SpecCaptcha captcha = new SpecCaptcha(130, 48, 4);
        captcha.setCharType(CHAR_TYPE);
        String id = UUID.randomUUID().toString();
        String answer = captcha.text().trim();
        repo.insert(id, hash(answer), Instant.now().plus(TTL), ip);
        if (debugAnswer) {
            log.warn("【captcha 调试】id={} answer={}", id, answer);
        }
        lazyPurge();
        return new Captcha(id, captcha.toBase64());
    }

    /**
     * 校验并<b>立即销毁</b>。
     *
     * <p>销毁发生在比对之前（见 {@link CaptchaRepository#consume}）：
     * 答案错了这张图也作废，用户必须换一张，杜绝同一个 id 被反复试。
     *
     * @return true 表示通过；false 表示过期、不存在或答案错误（三者对调用方无差别）
     */
    public boolean verifyAndConsume(String id, String input) {
        if (id == null || input == null || input.isBlank()) {
            return false;
        }
        var consumed = repo.consume(id);
        if (consumed == null) {
            return false;
        }
        if (consumed.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        return consumed.answerHash().equals(hash(input));
    }

    /** 预置一条答案，仅供测试绕过图形渲染。 */
    void putForTest(String id, String answer) {
        repo.insert(id, hash(answer), Instant.now().plus(TTL), null);
    }

    /**
     * 懒清理：每签发一张图顺手删一批早就过期的行。
     *
     * <p>不用 {@code @Scheduled}：容器里没有常驻线程，定时任务在 serverless 上根本不会触发。
     * 清理失败不能影响签发，否则一个清理异常会让整个登录页挂掉。
     */
    private void lazyPurge() {
        try {
            repo.purgeExpired(Duration.ofHours(1));
        } catch (Exception e) {
            log.debug("清理过期图形码失败，忽略: {}", e.toString());
        }
    }

    /** 大小写不敏感（图上全是大写，用户常按小写输入），哈希前统一归一。 */
    private static String hash(String answer) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(answer.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** @param imageBase64 可直接塞进 {@code <img src>} 的 data URL */
    public record Captcha(String id, String imageBase64) {
    }
}
