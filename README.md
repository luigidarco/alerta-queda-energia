# Alerta de queda de energia

Serviço Java 21 em Docker que acompanha um disjuntor Zigbee2MQTT e envia **e-mail e push ntfy** na queda e no retorno da tensão. Sem Spring, interface web ou banco externo.

## Como funciona

| Situação | Aviso |
| --- | --- |
| Leitura nova ≤ 10 V | Queda detectada por tensão |
| 30 s sem mensagens novas, com MQTT e ponte online | Possível queda ou perda de comunicação |
| Duas leituras novas ≥ 100 V, separadas por ≥ 5 s | Tensão restabelecida, ou comunicação e tensão normalizadas após suspeita |
| MQTT desconectado ou Zigbee2MQTT offline | Monitoramento indisponível; diagnóstico de silêncio suspenso |

Cada transição gera um aviso por canal, com horário, motivo e duração observada. O horário real da interrupção pode ser anterior à detecção. A indisponibilidade do broker é percebida pela conexão/keepalive, não necessariamente no mesmo instante da falha física.

`OFF`, consumo zero, subtensão e os eventos `electricity_shortage`/`electricity_shortage_alarm` não confirmam falta de energia. Uma leitura baixa confirma **tensão baixa no ponto medido**, sem distinguir queda da concessionária, defeito local ou abertura de circuito.

## Preparar o Zigbee2MQTT

O modelo usado no desenvolvimento foi o **Tongou TO-Q-SYS-JZT**. Troque o endereço de exemplo `0x00124b0000000000` pelo endereço do seu dispositivo nesta configuração e em `MQTT_TOPICO`. Mescle o trecho de [ajuste-zigbee2mqtt.yaml](teste/ajuste-zigbee2mqtt.yaml) na configuração existente:

```yaml
advanced:
  last_seen: epoch
devices:
  '0x00124b0000000000':
    filtered_cache:
      - '^voltage$'
      - '^event$'
```

Preserve as demais opções e entradas de `filtered_cache`. Faça backup antes e reinicie o Zigbee2MQTT após o ajuste. **O monitor não modifica nem reinicia o Zigbee2MQTT automaticamente.** As primeiras mensagens após a mudança podem carregar cache antigo; deixe o Zigbee2MQTT receber algumas leituras antes de iniciar o monitor.

`last_seen` crescente é obrigatório. Mensagens retidas, anteriores à conexão, repetidas, atrasadas além do prazo de silêncio ou com timestamp muito futuro não alteram o estado. Campos de tensão ausentes não são reutilizados de mensagens anteriores. Na inicialização saudável não há aviso de retorno. A normalização depende de duas medições novas de tensão. Se o dispositivo demorar a informar a tensão, o aviso de retorno também será adiado. Verifique o comportamento do aparelho em um teste real antes de depender dos alertas.

## Instalar

Requisitos: Docker com Compose, broker MQTT acessível e relógios sincronizados. Servidor, broker, coordenador Zigbee, roteador e modem/ONU precisam permanecer no nobreak; a internet deve continuar disponível durante a queda.

Após copiar o arquivo de exemplo, preencha `.env` com as opções descritas a seguir antes de iniciar o monitor.

```sh
git clone https://github.com/luigidarco/alerta-queda-energia.git
cd alerta-queda-energia
cp .env.example .env
```

Após configurar `.env`, inicie o serviço.

```sh
docker compose up -d --build
docker compose logs -f monitor
```

Para atualizar: `git pull` e `docker compose up -d --build`. O Compose recompila para a arquitetura do servidor (AMD64 ou ARM64) e reinicia o monitor automaticamente. O Docker também deve estar configurado para iniciar com o sistema.

### MQTT

- `MQTT_URL`: `tcp://host.docker.internal:1883` se o broker estiver no hospedeiro, ou `tcp://IP_DO_BROKER:1883`. Para TLS, use `ssl://host:8883` com certificado confiável pela JVM.
- O broker precisa escutar numa interface acessível aos contêineres. Um broker limitado a `127.0.0.1` pode não ser acessível; configure listener e autenticação/rede adequados.
- **`localhost` no contêiner é o próprio contêiner.** Se o broker estiver em outro Compose, use o IP acessível ou uma rede Docker compartilhada e o nome do serviço.
- `MQTT_USUARIO`, `MQTT_SENHA`: se houver autenticação. `MQTT_CLIENTE_ID` deve ser exclusivo entre instâncias.
- Ajuste `MQTT_TOPICO` se renomear o dispositivo, e `MQTT_PONTE` se trocar o `base_topic`.

### E-mail

Preencha `SMTP_HOST`, `SMTP_PORTA`, `SMTP_USUARIO`, `SMTP_SENHA`, `EMAIL_ORIGEM` e `EMAIL_DESTINO`. Destinatários múltiplos podem ser separados por vírgula. A senha não é registrada em logs.

Padrão: porta 587 com `SMTP_STARTTLS=true`, obrigatório quando ativado. Para porta 465: `SMTP_SSL=true` e `SMTP_STARTTLS=false`. Gmail normalmente exige uma senha de aplicativo, quando disponível na conta. Desative TLS somente em ambiente de testes confiável. Para usar apenas push, defina `EMAIL_ATIVO=false`.

### Push no celular

Instale o [ntfy para Android ou iPhone](https://docs.ntfy.sh/subscribe/phone/). Em `.env`, configure `NTFY_URL=https://ntfy.sh` e um `NTFY_TOPICO` longo e aleatório; assine exatamente esse tópico no aplicativo e permita notificações. Caso utilize tópico protegido, informe `NTFY_TOKEN` e autorize o aplicativo nesse servidor.

Tópicos públicos funcionam como um segredo compartilhado: quem conhece o nome pode ler/publicar. Para acesso restrito, utilize tópico reservado/autenticado. No iPhone, servidor ntfy próprio exige a configuração de [push instantâneo via upstream](https://docs.ntfy.sh/config/#ios-instant-notifications). Para usar apenas e-mail, defina `PUSH_ATIVO=false`.

### Ajustes

Os padrões ficam em `.env.example`: silêncio 30 s, recuperação 5 s, queda 10 V, normalização 100 V e fuso `America/Sao_Paulo`. Calibre o silêncio acima do intervalo real de mensagens. Os limites são critérios do software; **não alteram as proteções elétricas do disjuntor**. Não há comandos `/set` enviados ao equipamento.

## Persistência e mudança de servidor

Incidente e fila de entrega ficam no volume Docker `estado`, em `/dados/estado.json`, gravados juntos por substituição atômica. SMTP e push têm filas independentes, preservam a ordem e repetem falhas com espera de 2 s até 5 min. Sem internet, ficam pendentes para envio posterior. A fila cresce enquanto os canais não entregam; acompanhe logs e espaço em disco em falhas prolongadas.

Após reinício, o incidente e as pendências continuam; a confirmação de retorno exige novas leituras. Uma confirmação perdida entre o envio e a gravação pode duplicar a notificação. O recebimento SMTP/HTTP confirma aceitação pelo provedor, sem garantir leitura ou entrega final ao celular.

Para migrar, pare o serviço (`docker compose stop`), copie `.env` por um meio privado e faça backup do volume `estado`. Restaure seu conteúdo no volume `estado` criado pelo Compose no novo computador, mantendo proprietário **10001:10001**, antes de iniciar o monitor. Não mantenha duas instâncias com o mesmo `MQTT_CLIENTE_ID`. Não use `docker compose down -v` em produção: apaga o histórico e a fila. Estado corrompido impede a inicialização; restaure um backup em vez de descartá-lo silenciosamente.

## Desenvolvimento e testes

No IntelliJ: abra `pom.xml`, selecione **JDK 21** como SDK e importe o Maven. Para executar `br.com.monitorenergia.Aplicacao`, adicione as variáveis de ambiente da configuração de execução; a JVM não lê `.env` automaticamente. Use `DIRETORIO_DADOS=dados` fora do Docker.

```sh
mvn verify
./scripts/testar-integracao.sh
```

O teste de integração sobe broker Mosquitto, caixa SMTP Mailpit, endpoint ntfy simulado e o monitor numa rede Docker isolada. Verifica queda, retorno, silêncio, ponte offline e reenvio após falha HTTP. Não envia e-mails nem pushes externos; remove somente os contêineres e o volume de testes ao terminar. O modelo de CI em `teste/validar.github.yml` também valida as imagens AMD64 e ARM64. Para habilitá-lo, copie para `.github/workflows/validar.yml` e publique com uma credencial que tenha permissão `workflow`.

Para validar o ambiente real: confira primeiro leituras novas com `last_seen` e `voltage`; confirme que toda a cadeia permanece funcionando no nobreak; provoque uma interrupção somente por um procedimento seguro e apropriado à instalação e observe os avisos de suspeita/queda e normalização. **Não há teste elétrico real automatizado.** Não publique mensagens fictícias no tópico do disjuntor de produção.

## Referências

[Dispositivo](https://www.zigbee2mqtt.io/devices/TO-Q-SYS-JZT.html) · [Disponibilidade](https://www.zigbee2mqtt.io/guide/configuration/device-availability.html) · [API ntfy](https://docs.ntfy.sh/publish/)
