package com.codeexplain.auth;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
 * <p>答案存在内存里而不是 cookie/签名里，理由是：存客户端就等于把答案交给攻击者。
 * 代价是多实例部署时需要粘性会话——本项目 Vercel 每实例单 JVM，暂不构成问题；
 * 若将来横向扩容，把这里换成 Redis 即可，接口不变。
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

    private final Cache<String, String> answers = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(TTL)
            .build();

    /**
     * 把答案打到日志，<b>仅供本地自动化测试</b>。
     * 默认关闭；生产环境绝不能开，等于把验证码白送。
     */
    private final boolean debugAnswer;

    public CaptchaService(@Value("${app.auth.debug-captcha:false}") boolean debugAnswer) {
        this.debugAnswer = debugAnswer;
    }

    /** 生成一张新图；id 回给前端，答案留在服务端。 */
    public Captcha create() {
        SpecCaptcha captcha = new SpecCaptcha(130, 48, 4);
        captcha.setCharType(CHAR_TYPE);
        String id = UUID.randomUUID().toString();
        String answer = captcha.text().trim();
        answers.put(id, answer);
        if (debugAnswer) {
            log.warn("【captcha 调试】id={} answer={}", id, answer);
        }
        return new Captcha(id, captcha.toBase64());
    }

    /**
     * 校验并<b>立即销毁</b>。
     *
     * <p>用 {@code asMap().remove()} 而不是先 get 再 delete：remove 是原子操作，
     * 并发重放同一个 id 时只有一个请求拿得到答案，第二个直接空。
     *
     * @return true 表示通过；false 表示过期、不存在或答案错误（三者对调用方无差别）
     */
    public boolean verifyAndConsume(String id, String input) {
        if (id == null || input == null) {
            return false;
        }
        String expected = answers.asMap().remove(id);
        if (expected == null) {
            return false;
        }
        return expected.equalsIgnoreCase(input.trim());
    }

    /** 预置一条答案，仅供测试绕过图形渲染。 */
    void putForTest(String id, String answer) {
        answers.put(id, answer);
    }

    /** @param imageBase64 可直接塞进 {@code <img src>} 的 data URL */
    public record Captcha(String id, String imageBase64) {
    }
}
