"""Endpoint ntfy local: captura mensagens e permite simular HTTP 503."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

mensagens = []
falhar = False
trava = Lock()


class Servidor(BaseHTTPRequestHandler):
    def do_GET(self):
        with trava:
            corpo = json.dumps(mensagens, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(corpo)

    def do_POST(self):
        global falhar
        corpo = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with trava:
            if self.path == "/falhar":
                falhar = json.loads(corpo)["ativo"]
                status = 200
            elif falhar:
                status = 503
            else:
                mensagens.append(json.loads(corpo))
                status = 200
        self.send_response(status)
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Servidor).serve_forever()
