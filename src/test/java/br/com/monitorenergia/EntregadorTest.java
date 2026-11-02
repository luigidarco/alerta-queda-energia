package br.com.monitorenergia;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntregadorTest {
    @TempDir Path pasta;

    @Test void canaisIndependentesComOrdemPersistenciaEEsperaProgressiva() throws Exception {
        MonitorTest.Relogio relogio = new MonitorTest.Relogio();
        try (Repositorio repo = new Repositorio(pasta)) {
            repo.alterar(d -> {
                Repositorio.enfileirar(d, new Configuracao(Map.of()), "Queda", "Queda às 10h");
                Repositorio.enfileirar(d, new Configuracao(Map.of()), "Retorno", "Retorno às 11h");
            });
            Entregador entregador = new Entregador(repo, relogio);
            List<String> recebidas = new ArrayList<>();
            entregador.enviarProxima("EMAIL", p -> { throw new IOException("SMTP indisponível"); });
            entregador.enviarProxima("PUSH", p -> recebidas.add(p.titulo));
            entregador.enviarProxima("PUSH", p -> recebidas.add(p.titulo));
            assertEquals(List.of("Queda", "Retorno"), recebidas);
            assertEquals(2, repo.ler().fila.size());
            assertEquals(1, repo.ler().fila.getFirst().tentativas);
            entregador.enviarProxima("EMAIL", p -> fail("Deve aguardar antes de tentar novamente"));
            relogio.avancar(2);
            entregador.enviarProxima("EMAIL", p -> { throw new IOException(); });
            assertEquals(2, repo.ler().fila.getFirst().tentativas);
            assertEquals(relogio.millis() + 4000, repo.ler().fila.getFirst().proximaTentativa);
        }
        try (Repositorio repo = new Repositorio(pasta)) {
            Entregador entregador = new Entregador(repo, relogio);
            relogio.avancar(4);
            List<String> recebidas = new ArrayList<>();
            entregador.enviarProxima("EMAIL", p -> recebidas.add(p.titulo));
            entregador.enviarProxima("EMAIL", p -> recebidas.add(p.titulo));
            assertEquals(List.of("Queda", "Retorno"), recebidas);
            assertTrue(repo.ler().fila.isEmpty());
        }
    }

    @Test void pushComFalhaNaoBloqueiaEmail() throws Exception {
        try (Repositorio repo = new Repositorio(pasta)) {
            repo.alterar(d -> Repositorio.enfileirar(d, new Configuracao(Map.of()), "Queda", "Teste"));
            Entregador entregador = new Entregador(repo, new MonitorTest.Relogio());
            entregador.enviarProxima("PUSH", p -> { throw new IOException(); });
            entregador.enviarProxima("EMAIL", p -> {});
            assertEquals(1, repo.ler().fila.size());
            assertEquals("PUSH", repo.ler().fila.getFirst().canal);
        }
    }
}
