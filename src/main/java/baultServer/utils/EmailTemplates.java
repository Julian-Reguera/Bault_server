package baultServer.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailTemplates {

    private final String appName;

    public EmailTemplates(@Value("${bault.email.app-name:Bault}") String appName) {
        this.appName = appName;
    }

    public Rendered verificationCode(String code, int ttlMinutes) {
        String subject = "Verifica tu cuenta de " + appName;
        String heading = "Verifica tu correo";
        String intro = "Introduce este código en la aplicación para confirmar tu cuenta:";
        String footer = "El código caduca en " + ttlMinutes + " minutos. " +
                "Si no has creado una cuenta en " + appName + ", ignora este correo.";
        String html = renderHtml(heading, intro, code, footer);
        String text = renderText(heading, intro, code, footer);
        return new Rendered(subject, html, text);
    }

    public Rendered passwordResetCode(String code, int ttlMinutes) {
        String subject = "Restablecer contraseña de " + appName;
        String heading = "Restablece tu contraseña";
        String intro = "Introduce este código en la aplicación para elegir una nueva contraseña:";
        String footer = "El código caduca en " + ttlMinutes + " minutos. " +
                "Si no has solicitado el cambio, ignora este correo — tu contraseña no ha cambiado.";
        String html = renderHtml(heading, intro, code, footer);
        String text = renderText(heading, intro, code, footer);
        return new Rendered(subject, html, text);
    }

    private String renderHtml(String heading, String intro, String code, String footer) {
        return """
                <!DOCTYPE html>
                <html lang="es">
                  <head><meta charset="utf-8"/></head>
                  <body style="margin:0;padding:0;background:#f4f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1f2937;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f5f7;padding:32px 0;">
                      <tr>
                        <td align="center">
                          <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:12px;padding:32px;box-shadow:0 1px 3px rgba(0,0,0,0.08);">
                            <tr><td style="font-size:14px;letter-spacing:2px;color:#6b7280;text-transform:uppercase;">%s</td></tr>
                            <tr><td style="padding-top:8px;font-size:22px;font-weight:600;color:#111827;">%s</td></tr>
                            <tr><td style="padding-top:16px;font-size:15px;line-height:22px;color:#374151;">%s</td></tr>
                            <tr>
                              <td align="center" style="padding:24px 0;">
                                <div style="display:inline-block;background:#111827;color:#ffffff;font-size:28px;font-weight:700;letter-spacing:8px;padding:16px 24px;border-radius:8px;font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace;">%s</div>
                              </td>
                            </tr>
                            <tr><td style="font-size:13px;line-height:20px;color:#6b7280;">%s</td></tr>
                          </table>
                          <div style="margin-top:16px;font-size:12px;color:#9ca3af;">© %s</div>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(escape(appName), escape(heading), escape(intro), escape(code), escape(footer), escape(appName));
    }

    private String renderText(String heading, String intro, String code, String footer) {
        return heading + "\n\n" + intro + "\n\n    " + code + "\n\n" + footer + "\n\n— " + appName;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public record Rendered(String subject, String html, String text) {}
}
