package com.codeexplain.auth;

import java.time.Duration;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 验证码发信。
 *
 * <p>用 QQ 邮箱 SMTP 465（隐式 SSL）。注意两件事：
 *
 * <ul>
 *   <li>25 端口在多数云平台被封锁，本地能连线上连不上，所以直接用 465。</li>
 *   <li>发信失败<b>必须抛出去</b>，不能让「验证码已发送」的假成功误导用户——
 *       调用方会据此回滚已写入的验证码记录，用户才能立刻重试而不是卡在 60 秒冷却里。</li>
 * </ul>
 *
 * <p>未配置 {@code SMTP_PASS} 时进入 dev 模式：把验证码打到日志并返回成功。
 * 这样本地开发和自动化测试不需要真实邮箱，但对外会明确告知 {@code devMode=true}。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String from;
    private final String username;
    private final boolean configured;

    public MailService(ObjectProvider<JavaMailSender> senderProvider,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${app.mail.from:}") String from) {
        this.senderProvider = senderProvider;
        this.username = username;
        this.from = (from == null || from.isBlank()) ? username : from;
        this.configured = username != null && !username.isBlank()
                && password != null && !password.isBlank();
        if (!configured) {
            log.warn("SMTP 未配置（缺 SMTP_USER / SMTP_PASS），验证码将输出到日志，不会真正发信");
        }
    }

    public boolean devMode() {
        return !configured;
    }

    /**
     * 发送验证码邮件。
     *
     * @throws MailDeliveryException 发信失败（网络、认证、被限流），调用方需回滚验证码记录
     */
    public void sendCode(String to, String code, Duration ttl) {
        if (!configured) {
            log.warn("【dev 模式】验证码 {} → {}（{} 分钟内有效）", code, to, ttl.toMinutes());
            return;
        }

        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            throw new MailDeliveryException("邮件客户端未初始化，请检查 SMTP 配置");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, "Code Explain");
            helper.setTo(to);
            helper.setSubject("【Code Explain】注册验证码 " + code);
            helper.setText(html(code, ttl), true);
            sender.send(message);
            log.info("验证码邮件已发送 → {}", mask(to));
        } catch (Exception e) {
            // 不把原始异常直接抛给前端：里面可能含账号、服务器地址
            log.error("验证码邮件发送失败 → {}", mask(to), e);
            throw new MailDeliveryException("验证码邮件发送失败", e);
        }
    }

    private static String html(String code, Duration ttl) {
        return """
                <div style="font-family:-apple-system,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
                  <h2 style="font-size:18px;color:#1f2a37;margin:0 0 16px">验证你的邮箱</h2>
                  <p style="color:#4a5768;font-size:14px;line-height:1.6;margin:0 0 20px">
                    你正在注册 Code Explain 账号，请在页面填写下面的验证码：
                  </p>
                  <div style="background:#f2f6ff;border:1px solid #d8e3fd;border-radius:10px;
                              padding:18px;text-align:center;margin:0 0 20px">
                    <span style="font-size:30px;letter-spacing:10px;font-weight:700;color:#2f6fed">%s</span>
                  </div>
                  <p style="color:#8a94a6;font-size:12px;line-height:1.6;margin:0">
                    验证码 %d 分钟内有效，仅可使用一次。<br>
                    如果不是你本人操作，忽略这封邮件即可，你的邮箱不会被注册。
                  </p>
                </div>
                """.formatted(code, ttl.toMinutes());
    }

    /** 日志里不落完整邮箱：a***@qq.com。 */
    static String mask(String email) {
        if (email == null) {
            return "null";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** 发信失败。与业务错误分开，便于调用方区分「用户填错」和「我们的通道挂了」。 */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message) {
            super(message);
        }

        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
