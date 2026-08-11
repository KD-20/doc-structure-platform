package com.docstructure.platform.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Real implementation, only registered when platform.mail.enabled=true — see application.yml
 * for the SMTP_* environment variables this needs (host/port/username/password), same pattern
 * as LlmExtractionStrategy: absent by default, no-op-free deployments never instantiate this
 * or its JavaMailSender dependency, so nothing breaks when SMTP isn't configured.
 */
@Service
@ConditionalOnProperty(name = "platform.mail.enabled", havingValue = "true")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailService(JavaMailSender mailSender, @Value("${platform.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
