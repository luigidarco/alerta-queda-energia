package br.com.monitorenergia;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class Repositorio implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private final Path arquivo;
    private final FileChannel travaArquivo;
    private final FileLock trava;
    private Dados dados;

    public static class Dados {
        public String incidente = "NENHUM";
        public long inicio;
        public Long indisponivelDesde;
        public long ultimaLeitura;
        public List<Pendente> fila = new ArrayList<>();
    }

    public static class Pendente {
        public String id;
        public String canal;
        public String titulo;
        public String mensagem;
        public int tentativas;
        public long proximaTentativa;
    }

    public Repositorio(Path diretorio) throws IOException {
        Files.createDirectories(diretorio);
        arquivo = diretorio.resolve("estado.json");
        travaArquivo = FileChannel.open(diretorio.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            trava = travaArquivo.tryLock();
        } catch (IOException | RuntimeException erro) {
            travaArquivo.close();
            throw erro;
        }
        if (trava == null) { travaArquivo.close(); throw new IOException("Diretório já utilizado por outro monitor"); }
        try {
            dados = Files.exists(arquivo) ? JSON.readValue(Files.readAllBytes(arquivo), Dados.class) : new Dados();
            if (dados == null || dados.fila == null || !List.of("NENHUM", "SUSPEITA", "QUEDA").contains(dados.incidente)) {
                throw new IOException("Estado persistido inválido; restaure o backup");
            }
            for (Pendente p : dados.fila) {
                if (p == null || p.id == null || p.titulo == null || p.mensagem == null
                        || !List.of("EMAIL", "PUSH").contains(p.canal) || p.tentativas < 0 || p.proximaTentativa < 0) {
                    throw new IOException("Fila persistida inválida; restaure o backup");
                }
            }
        } catch (IOException | RuntimeException erro) {
            close();
            throw erro;
        }
    }

    public synchronized Dados ler() {
        return JSON.convertValue(dados, Dados.class);
    }

    public synchronized void alterar(Consumer<Dados> mudanca) throws IOException {
        Dados novo = ler();
        mudanca.accept(novo);
        byte[] conteudo = JSON.writeValueAsBytes(novo);
        Path temporario = arquivo.resolveSibling("estado.tmp");
        try (FileChannel canal = FileChannel.open(temporario, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(conteudo);
            while (buffer.hasRemaining()) canal.write(buffer);
            canal.force(true);
        }
        Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        dados = novo;
    }

    static void enfileirar(Dados dados, Configuracao config, String titulo, String mensagem) {
        for (String canal : List.of("EMAIL", "PUSH")) {
            if (!config.ativo(canal.equals("EMAIL") ? "EMAIL_ATIVO" : "PUSH_ATIVO", true)) continue;
            Pendente p = new Pendente();
            p.id = UUID.randomUUID().toString();
            p.canal = canal;
            p.titulo = titulo;
            p.mensagem = mensagem;
            dados.fila.add(p);
        }
    }

    @Override public void close() throws IOException {
        trava.release();
        travaArquivo.close();
    }
}
