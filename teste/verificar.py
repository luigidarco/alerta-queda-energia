"""Integração real MQTT/SMTP/HTTP, executada na rede Docker de testes."""
import json
import socket
import struct
import sys
import time
import urllib.request


def texto(valor):
    valor = valor.encode()
    return struct.pack("!H", len(valor)) + valor


def pacote(tipo, corpo):
    tamanho = len(corpo)
    prefixo = bytearray([tipo])
    while True:
        parte = tamanho % 128
        tamanho //= 128
        prefixo.append(parte | (128 if tamanho else 0))
        if not tamanho:
            return bytes(prefixo) + corpo


def publicar(topico, dados, retida=False):
    with socket.create_connection(("broker", 1883), timeout=5) as sock:
        conectar = texto("MQTT") + bytes([4, 2, 0, 10]) + texto("integracao-" + str(time.time_ns()))
        sock.sendall(pacote(0x10, conectar))
        resposta = sock.recv(4)
        assert resposta == b'\x20\x02\x00\x00', resposta
        corpo = dados if isinstance(dados, str) else json.dumps(dados)
        sock.sendall(pacote(0x31 if retida else 0x30, texto(topico) + corpo.encode()))
        sock.sendall(b'\xc0\x00')
        assert sock.recv(2) == b'\xd0\x00'


def ler(url):
    with urllib.request.urlopen(url, timeout=5) as resposta:
        return json.load(resposta)


def push():
    return ler("http://push:8080/mensagens")


def emails():
    return ler("http://smtp:8025/api/v1/messages")["messages"]


def esperar(condicao, descricao, segundos=20):
    limite = time.monotonic() + segundos
    while time.monotonic() < limite:
        if condicao():
            return
        time.sleep(.2)
    raise AssertionError("Tempo esgotado: " + descricao)


def leitura(volts, **extras):
    publicar("teste/disjuntor", {"last_seen": int(time.time() * 1000), "voltage": volts, **extras})


def modo_falha(ativo):
    req = urllib.request.Request("http://push:8080/falhar", json.dumps({"ativo": ativo}).encode(),
                                 {"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=5).close()


if len(sys.argv) > 1:
    if sys.argv[1] == "preparar-reinicio":
        modo_falha(True)
        leitura(0)
        esperar(lambda: len(emails()) == 7, "queda antes do reinício")
        assert len(push()) == 6
        print("Incidente e push pendente preparados para reinício.")
    elif sys.argv[1] == "finalizar-reinicio":
        modo_falha(False)
        esperar(lambda: len(push()) == 7, "push persistido após reinício")
        assert push()[-1]["title"] == "Queda detectada por tensão"
        leitura(130)
        time.sleep(1.3)
        leitura(130)
        esperar(lambda: len(push()) == 8 and len(emails()) == 8, "retorno após reinício")
        assert push()[-1]["title"] == "Tensão restabelecida"
        print("Reinício OK: incidente e fila preservados; 8 e-mails e 8 pushes capturados no total.")
    else:
        raise AssertionError("Etapa de teste desconhecida")
    sys.exit(0)


publicar("teste/disjuntor", {"last_seen": 1, "voltage": 0}, retida=True)
publicar("teste/bridge/state", {"state": "online"}, retida=True)
leitura(130, state="OFF", power=0, event="electricity_shortage")
time.sleep(1)
assert not push() and not emails(), "Inicialização saudável deve ser silenciosa"

modo_falha(True)
leitura(0)
esperar(lambda: len(emails()) == 1, "e-mail de queda")
assert not push()
leitura(130)
time.sleep(1.3)
leitura(130)
esperar(lambda: len(emails()) == 2, "e-mail de retorno")
modo_falha(False)
esperar(lambda: len(push()) == 2, "reenvio push preservando a ordem")
assert [p["title"] for p in push()] == ["Queda detectada por tensão", "Tensão restabelecida"]
assert {e["Subject"] for e in emails()} == {"Queda detectada por tensão", "Tensão restabelecida"}

esperar(lambda: len(push()) == 3, "suspeita por silêncio", 12)
assert push()[-1]["title"] == "Possível queda de energia ou perda de comunicação com o disjuntor"
leitura(130)
time.sleep(1.3)
leitura(130)
esperar(lambda: len(push()) == 4, "normalização após silêncio")
assert push()[-1]["title"] == "Comunicação e leitura de tensão normalizadas"

publicar("teste/bridge/state", {"state": "offline"}, retida=True)
esperar(lambda: len(push()) == 5, "indisponibilidade da ponte")
time.sleep(7)
assert len(push()) == 5, "Ponte offline não deve gerar suspeita de queda"
publicar("teste/bridge/state", {"state": "online"}, retida=True)
leitura(130)
esperar(lambda: len(push()) == 6 and len(emails()) == 6, "reconexão da ponte")
assert push()[-1]["title"] == "Monitoramento reconectado"
print("Integração OK: MQTT real, 6 e-mails capturados e 6 pushes simulados; nenhuma notificação externa.")
