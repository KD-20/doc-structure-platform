package com.docstructure.platform.notifications;

/**
 * Seam for outbound email (currently just guest-link sharing). No bean implements this unless
 * platform.mail.enabled=true AND real SMTP settings are supplied — see SmtpEmailService. When
 * absent, callers must treat "no EmailService bean" as "not configured" and fail with a clear,
 * actionable message rather than silently dropping the send.
 */
public interface EmailService {
    void send(String to, String subject, String body);
}
