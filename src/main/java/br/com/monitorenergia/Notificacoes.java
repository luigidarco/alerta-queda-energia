package br.com.monitorenergia;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public final class Notificacoes {
    private Notificacoes() {}

    @FunctionalInterface
    public interface Canal {
        void enviar(Repositorio.Pendente mensagem) throws Exception;
    }

    public static Canal push(Configuracao config) {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return mensagem -> {
            String json = Repositorio.JSON.writeValueAsString(Map.of("topic", config.texto("NTFY_TOPICO", ""),
                    "title", mensagem.titulo, "message", mensagem.mensagem, "priority", 4));
            HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(config.texto("NTFY_URL", "https://ntfy.sh")))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (!config.segredo("NTFY_TOKEN").isBlank()) pedido.header("Authorization", "Bearer " + config.segredo("NTFY_TOKEN"));
            int status = cliente.send(pedido.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("Push retornou HTTP " + status);
        };
    }

    public static Canal email(Configuracao config) {
        Properties props = new Properties();
        boolean autenticacao = !config.texto("SMTP_USUARIO", "").isBlank();
        props.setProperty("mail.smtp.host", config.texto("SMTP_HOST", ""));
        props.setProperty("mail.smtp.port", config.texto("SMTP_PORTA", "587"));
        props.setProperty("mail.smtp.auth", Boolean.toString(autenticacao));
        props.setProperty("mail.smtp.starttls.enable", Boolean.toString(config.ativo("SMTP_STARTTLS", true)));
        props.setProperty("mail.smtp.starttls.required", Boolean.toString(config.ativo("SMTP_STARTTLS", true)));
        props.setProperty("mail.smtp.ssl.enable", Boolean.toString(config.ativo("SMTP_SSL", false)));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        for (String chave : new String[]{"connectiontimeout", "timeout", "writetimeout"}) props.setProperty("mail.smtp." + chave, "10000");
        Session sessao = Session.getInstance(props);
        return mensagem -> {
            MimeMessage email = new MimeMessage(sessao);
            email.setFrom(new InternetAddress(config.texto("EMAIL_ORIGEM", "")));
            email.setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.texto("EMAIL_DESTINO", ""), true));
            email.setSubject(mensagem.titulo, "UTF-8");
            email.setText(mensagem.mensagem, "UTF-8");
            email.setSentDate(new java.util.Date());
            email.setHeader("X-Monitor-Evento", mensagem.id);
            try (Transport transporte = sessao.getTransport("smtp")) {
                if (autenticacao) transporte.connect(config.texto("SMTP_USUARIO", ""), config.segredo("SMTP_SENHA"));
                else transporte.connect();
                transporte.sendMessage(email, email.getAllRecipients());
            }
        };
    }
}
