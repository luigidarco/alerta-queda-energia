package br.com.monitorenergia;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorTest {
    @TempDir Path pasta;
    Relogio relogio;
    Repositorio repositorio;
    Monitor monitor;
    Configuracao config = new Configuracao(Map.of());

    static class Relogio extends Clock {
        long agora = 1_800_000_000_000L;
        void avancar(long segundos) { agora += segundos * 1000; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(agora); }
    }

    @BeforeEach void preparar() throws Exception {
        relogio = new Relogio();
        repositorio = new Repositorio(pasta);
        monitor = new Monitor(config, repositorio, relogio);
        monitor.conectar();
        monitor.ponte("{\"state\":\"online\"}");
    }

    void leitura(double volts) throws Exception {
        monitor.receber("{\"last_seen\":" + relogio.millis() + ",\"voltage\":" + volts + "}", false);
    }

    int avisos() { return repositorio.ler().fila.size() / 2; }

    @Test void quedaImediataRetornoEstavelSemDuplicatas() throws Exception {
        leitura(130);
        assertEquals(0, avisos());
        relogio.avancar(1); leitura(0);
        assertEquals("QUEDA", repositorio.ler().incidente);
        relogio.avancar(1); leitura(0);
        assertEquals(1, avisos());
        relogio.avancar(1); leitura(127);
        relogio.avancar(4); leitura(127);
        assertEquals(1, avisos());
        relogio.avancar(1); leitura(127);
        assertEquals("NENHUM", repositorio.ler().incidente);
        assertEquals(2, avisos());
        assertEquals("Tensão restabelecida", repositorio.ler().fila.get(2).titulo);
        assertTrue(repositorio.ler().fila.get(2).mensagem.contains("Duração observada: 7 s"));
        relogio.avancar(1); leitura(127);
        assertEquals(2, avisos());
    }

    @Test void silencioGeraSomenteSuspeitaComRetornoEspecifico() throws Exception {
        leitura(130);
        relogio.avancar(29); monitor.verificarSilencio(); assertEquals(0, avisos());
        relogio.avancar(1); monitor.verificarSilencio(); monitor.verificarSilencio();
        assertEquals("SUSPEITA", repositorio.ler().incidente);
        assertEquals(1, avisos());
        relogio.avancar(1); leitura(130);
        relogio.avancar(5); leitura(130);
        assertEquals("Comunicação e leitura de tensão normalizadas", repositorio.ler().fila.get(2).titulo);
    }

    @Test void retidasAntigasDuplicadasFuturasEInvalidasNaoRenovamPrazo() throws Exception {
        long inicio = relogio.millis();
        monitor.receber("{\"last_seen\":" + inicio + ",\"voltage\":0}", true);
        monitor.receber("{\"last_seen\":" + (inicio - 1) + ",\"voltage\":0}", false);
        monitor.receber("{\"last_seen\":" + (inicio + 100_000) + ",\"voltage\":0}", false);
        monitor.receber("{", false);
        monitor.receber("null", false);
        monitor.receber("[]", false);
        monitor.receber("{\"voltage\":0}", false);
        assertEquals(0, avisos());
        leitura(130);
        relogio.avancar(20);
        monitor.receber("{\"last_seen\":" + inicio + ",\"voltage\":0}", false);
        relogio.avancar(10); monitor.verificarSilencio();
        assertEquals("SUSPEITA", repositorio.ler().incidente);
    }

    @Test void offConsumoZeroEEventosNaoConfirmamQueda() throws Exception {
        monitor.receber("{\"last_seen\":" + relogio.millis() + ",\"state\":\"OFF\",\"power\":0,\"event\":\"electricity_shortage\",\"voltage\":130}", false);
        relogio.avancar(1);
        monitor.receber("{\"last_seen\":" + relogio.millis() + ",\"event\":\"under_voltage_trip\",\"voltage\":80}", false);
        relogio.avancar(1);
        monitor.receber("{\"last_seen\":" + relogio.millis() + ",\"event\":\"electricity_shortage_alarm\"}", false);
        assertEquals(0, avisos());
    }

    @Test void oscilacaoInterrompeConfirmacaoDeRetorno() throws Exception {
        leitura(0);
        relogio.avancar(1); leitura(130);
        relogio.avancar(5); leitura(50);
        relogio.avancar(1); leitura(130);
        assertEquals(1, avisos());
        relogio.avancar(5); leitura(130);
        assertEquals(2, avisos());
    }

    @Test void silencioInterrompeConfirmacaoDeRetorno() throws Exception {
        leitura(0);
        relogio.avancar(1); leitura(130);
        relogio.avancar(30); monitor.verificarSilencio();
        leitura(130);
        assertEquals("QUEDA", repositorio.ler().incidente);
        relogio.avancar(5); leitura(130);
        assertEquals("NENHUM", repositorio.ler().incidente);
    }

    @Test void lacunaEntreLeiturasNaoConfirmaRetornoMesmoSemVerificacaoPeriodica() throws Exception {
        leitura(0);
        relogio.avancar(1); leitura(130);
        relogio.avancar(31); leitura(130);
        assertEquals("QUEDA", repositorio.ler().incidente);
        relogio.avancar(5); leitura(130);
        assertEquals("NENHUM", repositorio.ler().incidente);
    }

    @Test void suspeitaPodeSerConfirmadaPorTensao() throws Exception {
        relogio.avancar(30); monitor.verificarSilencio();
        relogio.avancar(1); leitura(0);
        assertEquals("QUEDA", repositorio.ler().incidente);
        assertEquals(2, avisos());
        relogio.avancar(1); leitura(0);
        assertEquals(2, avisos());
    }

    @Test void falhaMqttSuspendeSuspeitaERequerNovaSessao() throws Exception {
        leitura(130);
        monitor.desconectar(); monitor.desconectar();
        relogio.avancar(60); monitor.verificarSilencio(); leitura(0);
        assertEquals("NENHUM", repositorio.ler().incidente);
        assertEquals(1, avisos());
        monitor.conectar();
        leitura(0); assertEquals(1, avisos());
        monitor.ponte("online");
        assertEquals(2, avisos());
        relogio.avancar(1); leitura(130);
        assertEquals(2, avisos());
    }

    @Test void ponteOfflineSuspendeSilencioERetornoNaoEncerraIncidente() throws Exception {
        leitura(0);
        monitor.ponte("{\"state\":\"offline\"}");
        relogio.avancar(60); monitor.verificarSilencio(); leitura(130);
        assertEquals(2, avisos());
        monitor.ponte("online");
        leitura(130); relogio.avancar(5); leitura(130);
        assertEquals(4, avisos());
        assertEquals("NENHUM", repositorio.ler().incidente);
    }

    @Test void reinicioPreservaIncidenteEFilaSemReusarLeitura() throws Exception {
        leitura(0);
        repositorio.close();
        repositorio = new Repositorio(pasta);
        monitor = new Monitor(config, repositorio, relogio);
        monitor.conectar(); monitor.ponte("online");
        leitura(130);
        relogio.avancar(5); leitura(130);
        assertEquals(1, avisos());
        relogio.avancar(5); leitura(130);
        assertEquals(2, avisos());
    }

    @Test void semPonteOnlineNaoConfundeInicializacaoComQueda() throws Exception {
        monitor.conectar();
        relogio.avancar(30); monitor.verificarSilencio();
        assertEquals("NENHUM", repositorio.ler().incidente);
        assertEquals("Monitoramento indisponível", repositorio.ler().fila.getFirst().titulo);
    }

    @Test void aceitaIsoEIgnoraTensaoInvalida() throws Exception {
        monitor.receber("{\"last_seen\":\"" + relogio.instant() + "\",\"voltage\":-1}", false);
        relogio.avancar(1);
        monitor.receber("{\"last_seen\":\"" + relogio.instant() + "\",\"voltage\":\"0\"}", false);
        assertEquals(0, avisos());
        relogio.avancar(1);
        monitor.receber("{\"last_seen\":\"" + relogio.instant() + "\",\"voltage\":0}", false);
        assertEquals("QUEDA", repositorio.ler().incidente);
    }

    @org.junit.jupiter.api.AfterEach void fechar() throws Exception { repositorio.close(); }
}
