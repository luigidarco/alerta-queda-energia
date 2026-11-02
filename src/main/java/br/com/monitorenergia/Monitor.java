package br.com.monitorenergia;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class Monitor {
    private static final System.Logger LOG = System.getLogger(Monitor.class.getName());
    private final Configuracao config;
    private final Repositorio repositorio;
    private final Clock relogio;
    private boolean conectado;
    private boolean ponteOnline;
    private long inicioSessao;
    private long ultimaMensagem;
    private long inicioNormal = -1;
    private long maiorTimestamp;

    public Monitor(Configuracao config, Repositorio repositorio, Clock relogio) {
        this.config = config;
        this.repositorio = repositorio;
        this.relogio = relogio;
        inicioSessao = relogio.millis();
        maiorTimestamp = repositorio.ler().ultimaLeitura;
    }

    public synchronized void conectar() {
        conectado = true;
        ponteOnline = false;
        inicioSessao = relogio.millis();
        ultimaMensagem = inicioSessao;
        inicioNormal = -1;
    }

    public synchronized void desconectar() throws IOException {
        conectado = false;
        ponteOnline = false;
        inicioNormal = -1;
        indisponivel("Conexão MQTT interrompida; não é possível confirmar falta de energia.");
    }

    public synchronized void ponte(String payload) throws IOException {
        if (!conectado) return;
        String estado;
        try {
            JsonNode json = Repositorio.JSON.readTree(payload);
            if (json == null) return;
            estado = json.isTextual() ? json.asText() : json.path("state").asText();
        } catch (IOException erro) {
            estado = payload.trim();
        }
        if (estado.equals("offline")) {
            ponteOnline = false;
            inicioNormal = -1;
            indisponivel("Zigbee2MQTT offline; não é possível confirmar falta de energia.");
        } else if (estado.equals("online")) {
            if (!ponteOnline) ultimaMensagem = relogio.millis();
            ponteOnline = true;
            Repositorio.Dados dados = repositorio.ler();
            if (dados.indisponivelDesde != null) {
                registrar("Monitoramento reconectado", "MQTT e Zigbee2MQTT disponíveis; aguardando novas leituras.",
                        dados.indisponivelDesde, novo -> novo.indisponivelDesde = null);
            }
        }
    }

    public synchronized void receber(String payload, boolean retida) throws IOException {
        if (retida || !conectado || !ponteOnline) return;
        JsonNode json;
        try {
            json = Repositorio.JSON.readTree(payload);
        } catch (IOException erro) {
            LOG.log(System.Logger.Level.WARNING, "Mensagem JSON inválida ignorada");
            return;
        }
        if (json == null || !json.isObject()) return;
        long timestamp;
        try {
            JsonNode visto = json.path("last_seen");
            timestamp = visto.isIntegralNumber() ? visto.longValue() : Instant.parse(visto.asText()).toEpochMilli();
        } catch (RuntimeException erro) { return; }
        long agora = relogio.millis();
        if (timestamp <= maiorTimestamp || timestamp < inicioSessao || timestamp > agora + 5000 || agora - timestamp > config.silencioMs()) return;
        if (agora - ultimaMensagem >= config.silencioMs()) inicioNormal = -1;
        maiorTimestamp = timestamp;
        ultimaMensagem = agora;
        repositorio.alterar(dados -> dados.ultimaLeitura = timestamp);
        JsonNode tensao = json.path("voltage");
        if (!tensao.isNumber() || !Double.isFinite(tensao.asDouble()) || tensao.asDouble() < 0) return;
        double volts = tensao.asDouble();
        Repositorio.Dados dados = repositorio.ler();
        if (volts <= config.quedaVolts()) {
            inicioNormal = -1;
            if (!dados.incidente.equals("QUEDA")) {
                registrar("Queda detectada por tensão", "Nova leitura: " + volts + " V.", dados.inicio == 0 ? agora : dados.inicio, novo -> {
                    if (novo.incidente.equals("NENHUM")) novo.inicio = agora;
                    novo.incidente = "QUEDA";
                });
            }
        } else if (volts >= config.normalVolts()) {
            if (dados.incidente.equals("NENHUM")) return;
            if (inicioNormal < 0) inicioNormal = agora;
            else if (agora - inicioNormal >= config.recuperacaoMs()) {
                String titulo = dados.incidente.equals("QUEDA") ? "Tensão restabelecida" : "Comunicação e leitura de tensão normalizadas";
                registrar(titulo, "Duas leituras novas em faixa normal; última: " + volts + " V.", dados.inicio, novo -> {
                    novo.incidente = "NENHUM";
                    novo.inicio = 0;
                });
                inicioNormal = -1;
            }
        } else inicioNormal = -1;
    }

    public synchronized void verificarSilencio() throws IOException {
        long agora = relogio.millis();
        if (!conectado || !ponteOnline) {
            if (agora - inicioSessao >= config.silencioMs()) {
                indisponivel("MQTT ou Zigbee2MQTT indisponível; detecção por silêncio suspensa.");
            }
            return;
        }
        if (agora - ultimaMensagem >= config.silencioMs()) {
            inicioNormal = -1;
            if (repositorio.ler().incidente.equals("NENHUM")) {
                registrar("Possível queda de energia ou perda de comunicação com o disjuntor",
                        "Sem mensagens novas há " + (agora - ultimaMensagem) / 1000 + " s; falta de energia não confirmada.",
                        agora, novo -> { novo.incidente = "SUSPEITA"; novo.inicio = agora; });
            }
        }
    }

    private void indisponivel(String motivo) throws IOException {
        if (repositorio.ler().indisponivelDesde != null) return;
        long agora = relogio.millis();
        registrar("Monitoramento indisponível", motivo, agora, novo -> novo.indisponivelDesde = agora);
    }

    private void registrar(String titulo, String motivo, long inicio, Consumer<Repositorio.Dados> mudanca) throws IOException {
        long agora = relogio.millis();
        String horario = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z").withZone(config.fuso()).format(Instant.ofEpochMilli(agora));
        String mensagem = titulo + "\nHorário: " + horario + "\n" + motivo
                + "\nDuração observada: " + Math.max(0, (agora - inicio) / 1000) + " s."
                + "\nO instante exato da interrupção pode ser anterior à detecção.";
        repositorio.alterar(dados -> { mudanca.accept(dados); Repositorio.enfileirar(dados, config, titulo, mensagem); });
        LOG.log(System.Logger.Level.INFO, titulo);
    }
}
