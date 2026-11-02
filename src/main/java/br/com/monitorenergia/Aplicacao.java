package br.com.monitorenergia;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class Aplicacao {
    private static final System.Logger LOG = System.getLogger(Aplicacao.class.getName());

    private Aplicacao() {}

    public static void main(String[] args) {
        try {
            executar(new Configuracao(System.getenv()));
        } catch (Exception erro) {
            LOG.log(System.Logger.Level.ERROR, "Não foi possível iniciar o monitor (" + erro.getClass().getSimpleName()
                    + "). Confira configuração, diretório de dados e README.");
            System.exit(1);
        }
    }

    static void executar(Configuracao config) throws Exception {
        config.validar();
        Repositorio repositorio = new Repositorio(config.diretorio());
        Monitor monitor = new Monitor(config, repositorio, Clock.systemUTC());
        Entregador entregador = new Entregador(repositorio, Clock.systemUTC());
        java.util.concurrent.atomic.AtomicBoolean encerrando = new java.util.concurrent.atomic.AtomicBoolean();
        MqttClient mqtt = new MqttClient(config.broker(), config.texto("MQTT_CLIENTE_ID", "monitor-energia"), new MemoryPersistence());
        MqttConnectOptions opcoes = new MqttConnectOptions();
        opcoes.setCleanSession(true);
        opcoes.setConnectionTimeout(10);
        opcoes.setKeepAliveInterval(10);
        if (!config.texto("MQTT_USUARIO", "").isBlank()) {
            opcoes.setUserName(config.texto("MQTT_USUARIO", ""));
            opcoes.setPassword(config.segredo("MQTT_SENHA").toCharArray());
        }
        mqtt.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable causa) { if (!encerrando.get()) seguro(monitor::desconectar); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            @Override public void messageArrived(String topico, MqttMessage mensagem) {
                if (encerrando.get()) return;
                String payload = new String(mensagem.getPayload(), StandardCharsets.UTF_8);
                seguro(() -> {
                    if (topico.equals(config.ponte())) monitor.ponte(payload);
                    else monitor.receber(payload, mensagem.isRetained());
                });
            }
        });
        ScheduledExecutorService tarefas = Executors.newScheduledThreadPool(4);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            encerrando.set(true);
            tarefas.shutdownNow();
            try {
                mqtt.disconnectForcibly(1000, 1000, false);
                mqtt.close();
                if (tarefas.awaitTermination(25, TimeUnit.SECONDS)) repositorio.close();
            } catch (Exception erro) {
                LOG.log(System.Logger.Level.WARNING, "Encerramento interrompido; fila preservada em disco");
            }
        }, "encerramento"));
        tarefas.scheduleWithFixedDelay(() -> {
            if (mqtt.isConnected()) return;
            try {
                mqtt.connect(opcoes);
                monitor.conectar();
                mqtt.subscribe(new String[]{config.ponte(), config.topico()}, new int[]{1, 1});
                LOG.log(System.Logger.Level.INFO, "MQTT conectado; aguardando leituras novas");
            } catch (Exception erro) {
                seguro(monitor::desconectar);
                try {
                    if (mqtt.isConnected()) mqtt.disconnectForcibly(500, 500, false);
                } catch (Exception falha) {
                    LOG.log(System.Logger.Level.WARNING, "Falha ao encerrar conexão MQTT; nova tentativa em 5 s");
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
        tarefas.scheduleWithFixedDelay(() -> seguro(monitor::verificarSilencio), 1, 1, TimeUnit.SECONDS);
        if (config.ativo("EMAIL_ATIVO", true)) {
            Notificacoes.Canal email = Notificacoes.email(config);
            tarefas.scheduleWithFixedDelay(() -> seguro(() -> entregador.enviarProxima("EMAIL", email)), 0, 1, TimeUnit.SECONDS);
        }
        if (config.ativo("PUSH_ATIVO", true)) {
            Notificacoes.Canal push = Notificacoes.push(config);
            tarefas.scheduleWithFixedDelay(() -> seguro(() -> entregador.enviarProxima("PUSH", push)), 0, 1, TimeUnit.SECONDS);
        }
        LOG.log(System.Logger.Level.INFO, "Monitor de energia iniciado");
        new CountDownLatch(1).await();
    }

    @FunctionalInterface private interface Operacao { void executar() throws Exception; }

    private static void seguro(Operacao operacao) {
        try { operacao.executar(); }
        catch (Exception erro) {
            LOG.log(System.Logger.Level.ERROR, "Falha interna (" + erro.getClass().getSimpleName() + "); encerrando para preservar o estado");
            System.exit(1);
        }
    }
}
