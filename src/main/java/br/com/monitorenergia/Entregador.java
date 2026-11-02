package br.com.monitorenergia;

import java.io.IOException;
import java.time.Clock;

public final class Entregador {
    private static final System.Logger LOG = System.getLogger(Entregador.class.getName());
    private final Repositorio repositorio;
    private final Clock relogio;

    public Entregador(Repositorio repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    public void enviarProxima(String nome, Notificacoes.Canal canal) throws IOException {
        Repositorio.Pendente proxima = repositorio.ler().fila.stream().filter(p -> p.canal.equals(nome)).findFirst().orElse(null);
        if (proxima == null || proxima.proximaTentativa > relogio.millis()) return;
        try {
            canal.enviar(proxima);
        } catch (Exception erro) {
            if (erro instanceof InterruptedException) Thread.currentThread().interrupt();
            repositorio.alterar(dados -> dados.fila.stream().filter(p -> p.id.equals(proxima.id)).findFirst().ifPresent(p -> {
                p.tentativas = Math.min(p.tentativas + 1, 30);
                p.proximaTentativa = relogio.millis() + Math.min(300, 1L << Math.min(p.tentativas, 9)) * 1000;
            }));
            LOG.log(System.Logger.Level.WARNING, "Falha no canal " + nome + "; mensagem mantida para nova tentativa (" + erro.getClass().getSimpleName() + ")");
            return;
        }
        repositorio.alterar(dados -> dados.fila.removeIf(p -> p.id.equals(proxima.id)));
        LOG.log(System.Logger.Level.INFO, "Notificação entregue: " + nome);
    }
}
