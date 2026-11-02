package br.com.monitorenergia;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;

public record Configuracao(Map<String, String> valores) {
    public Configuracao {
        valores = Map.copyOf(valores);
    }

    public String texto(String chave, String padrao) {
        return valores.getOrDefault(chave, padrao).trim();
    }

    public String segredo(String chave) {
        return valores.getOrDefault(chave, "");
    }

    public int numero(String chave, int padrao) {
        return Integer.parseInt(texto(chave, Integer.toString(padrao)));
    }

    public boolean ativo(String chave, boolean padrao) {
        String valor = texto(chave, Boolean.toString(padrao));
        if (!valor.equals("true") && !valor.equals("false")) throw new IllegalArgumentException(chave + " deve ser true ou false");
        return Boolean.parseBoolean(valor);
    }

    public long silencioMs() { return numero("SILENCIO_SEGUNDOS", 30) * 1000L; }
    public long recuperacaoMs() { return numero("RECUPERACAO_SEGUNDOS", 5) * 1000L; }
    public double quedaVolts() { return numero("QUEDA_VOLTS", 10); }
    public double normalVolts() { return numero("NORMAL_VOLTS", 100); }
    public ZoneId fuso() { return ZoneId.of(texto("FUSO_HORARIO", "America/Sao_Paulo")); }
    public Path diretorio() { return Path.of(texto("DIRETORIO_DADOS", "dados")); }
    public String topico() { return texto("MQTT_TOPICO", ""); }
    public String ponte() { return texto("MQTT_PONTE", "zigbee2mqtt/bridge/state"); }
    public String broker() { return texto("MQTT_URL", "tcp://host.docker.internal:1883"); }

    public void validar() {
        fuso();
        if (silencioMs() < 1000 || recuperacaoMs() < 1000 || quedaVolts() < 0 || normalVolts() <= quedaVolts()) {
            throw new IllegalArgumentException("Limites de tensão e tempos inválidos");
        }
        URI mqtt = URI.create(broker());
        if (!java.util.Set.of("tcp", "ssl").contains(mqtt.getScheme()) || mqtt.getHost() == null || mqtt.getUserInfo() != null) {
            throw new IllegalArgumentException("MQTT_URL deve usar tcp:// ou ssl://; credenciais nas variáveis separadas");
        }
        for (String topico : new String[]{topico(), ponte()}) {
            if (topico.isBlank() || topico.contains("#") || topico.contains("+") || topico.contains("\u0000")) {
                throw new IllegalArgumentException("Tópico MQTT inválido");
            }
        }
        if (topico().equals(ponte())) throw new IllegalArgumentException("Tópicos devem ser diferentes");
        if (ativo("EMAIL_ATIVO", true)) {
            exigir("SMTP_HOST"); exigir("EMAIL_ORIGEM"); exigir("EMAIL_DESTINO");
            int porta = numero("SMTP_PORTA", 587);
            if (porta < 1 || porta > 65535) throw new IllegalArgumentException("SMTP_PORTA inválida");
            boolean ssl = ativo("SMTP_SSL", false), tls = ativo("SMTP_STARTTLS", true);
            if (ssl && tls) throw new IllegalArgumentException("Use SMTP_SSL ou SMTP_STARTTLS, não ambos");
        }
        if (ativo("PUSH_ATIVO", true)) {
            exigir("NTFY_TOPICO");
            String topico = texto("NTFY_TOPICO", "");
            if (!topico.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("NTFY_TOPICO inválido");
            URI uri = URI.create(texto("NTFY_URL", "https://ntfy.sh"));
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("NTFY_URL inválida");
            }
        }
        if (!ativo("EMAIL_ATIVO", true) && !ativo("PUSH_ATIVO", true)) {
            throw new IllegalArgumentException("Habilite pelo menos um canal de notificação");
        }
    }

    private void exigir(String chave) {
        if (texto(chave, "").isBlank()) throw new IllegalArgumentException("Configure " + chave);
    }

    @Override public String toString() { return "Configuracao[credenciais ocultas]"; }
}
