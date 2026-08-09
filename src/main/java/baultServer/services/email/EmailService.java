package baultServer.services.email;

/**
 * Abstracción sobre el proveedor de envío de correos.
 * Cambiar de Resend a otro proveedor (Sendgrid, SES...) sólo debería requerir
 * añadir una nueva implementación de esta interfaz y cambiar el bean activo.
 */
public interface EmailService {

    /**
     * Envía un correo. Implementaciones deben ser síncronas desde el punto de vista del caller
     * (lanzan EmailDeliveryException si falla).
     *
     * @param to          destinatario
     * @param subject     asunto
     * @param htmlBody    cuerpo HTML (versión rica)
     * @param textBody    fallback en texto plano (para clientes que no renderizan HTML)
     */
    void send(String to, String subject, String htmlBody, String textBody);
}
