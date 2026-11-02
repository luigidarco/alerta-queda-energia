package br.com.monitorenergia;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguracaoRepositorioTest {
    @TempDir Path pasta;

    Map<String, String> valores() {
        return new HashMap<>(Map.of("SMTP_HOST", "smtp.exemplo.com", "EMAIL_ORIGEM", "origem@example.com", "EMAIL_DESTINO", "destino@example.com", "NTFY_TOPICO", "teste-aleatorio", "MQTT_TOPICO", "zigbee2mqtt/disjuntor"));
    }

    @Test void validaValoresECredenciaisOcultas() {
        Map<String, String> mapa = valores();
        mapa.put("SMTP_SENHA", "segredo-nao-exibir");
        Configuracao config = new Configuracao(mapa);
        assertDoesNotThrow(config::validar);
        assertFalse(config.toString().contains("segredo-nao-exibir"));
        assertEquals(30000, config.silencioMs());
        assertEquals("zigbee2mqtt/disjuntor", config.topico());
    }

    @Test void rejeitaConfiguracoesInsegurasOuInvalidas() {
        for (Map<String, String> alteracoes : java.util.List.of(
                Map.of("SILENCIO_SEGUNDOS", "0"), Map.of("RECUPERACAO_SEGUNDOS", "0"),
                Map.of("NORMAL_VOLTS", "5"), Map.of("FUSO_HORARIO", "invalido"),
                Map.of("SMTP_HOST", ""), Map.of("SMTP_SSL", "true"), Map.of("SMTP_PORTA", "99999"),
                Map.of("EMAIL_ATIVO", "sim"), Map.of("NTFY_TOPICO", ""), Map.of("NTFY_TOPICO", "a/b"),
                Map.of("NTFY_URL", "ftp://exemplo.com"), Map.of("MQTT_URL", "http://exemplo.com"),
                Map.of("MQTT_TOPICO", ""), Map.of("MQTT_TOPICO", "#"), Map.of("MQTT_TOPICO", "zigbee2mqtt/bridge/state"),
                Map.of("EMAIL_ATIVO", "false", "PUSH_ATIVO", "false"))) {
            Map<String, String> mapa = valores(); mapa.putAll(alteracoes);
            assertThrows(RuntimeException.class, () -> new Configuracao(mapa).validar(), alteracoes.keySet().toString());
        }
    }

    @Test void estadoCorrompidoNaoEhSobrescrito() throws Exception {
        Path arquivo = pasta.resolve("estado.json");
        Files.writeString(arquivo, "{corrompido");
        assertThrows(java.io.IOException.class, () -> new Repositorio(pasta));
        assertEquals("{corrompido", Files.readString(arquivo));
    }

    @Test void impedeDuasInstanciasNoMesmoDiretorio() throws Exception {
        try (Repositorio repo = new Repositorio(pasta)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> new Repositorio(pasta));
            repo.alterar(d -> d.incidente = "QUEDA");
            assertEquals("QUEDA", repo.ler().incidente);
        }
    }

    @Test void alteracaoQueFalhaPreservaEstadoAnterior() throws Exception {
        try (Repositorio repo = new Repositorio(pasta)) {
            assertThrows(IllegalStateException.class, () -> repo.alterar(d -> { d.incidente = "QUEDA"; throw new IllegalStateException(); }));
            assertEquals("NENHUM", repo.ler().incidente);
        }
    }
}
