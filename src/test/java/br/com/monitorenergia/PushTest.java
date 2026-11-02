package br.com.monitorenergia;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PushTest {
    @Test void enviaJsonUtf8ComTokenERejeitaFalhaHttp() throws Exception {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> corpo = new AtomicReference<>();
        AtomicReference<String> autorizacao = new AtomicReference<>();
        servidor.createContext("/", troca -> {
            corpo.set(new String(troca.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            autorizacao.set(troca.getRequestHeaders().getFirst("Authorization"));
            troca.sendResponseHeaders(status.get(), -1);
            troca.close();
        });
        servidor.start();
        try {
            Configuracao config = new Configuracao(Map.of("NTFY_URL", "http://127.0.0.1:" + servidor.getAddress().getPort(),
                    "NTFY_TOPICO", "teste", "NTFY_TOKEN", "token-de-teste"));
            Repositorio.Pendente p = new Repositorio.Pendente();
            p.titulo = "Tensão restabelecida";
            p.mensagem = "Comunicação normalizada às 15h";
            Notificacoes.Canal canal = Notificacoes.push(config);
            canal.enviar(p);
            assertEquals("Bearer token-de-teste", autorizacao.get());
            var json = Repositorio.JSON.readTree(corpo.get());
            assertEquals(p.titulo, json.path("title").asText());
            assertEquals(p.mensagem, json.path("message").asText());
            assertEquals("teste", json.path("topic").asText());
            assertEquals(4, json.path("priority").asInt());
            status.set(503);
            assertThrows(IOException.class, () -> canal.enviar(p));
        } finally { servidor.stop(0); }
    }
}
