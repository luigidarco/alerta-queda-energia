#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if docker compose version >/dev/null 2>&1; then
    compose() { docker compose -p monitor-energia-teste -f compose.teste.yaml "$@"; }
else
    compose() { docker-compose -p monitor-energia-teste -f compose.teste.yaml "$@"; }
fi
finalizar() {
    resultado=$?
    if [ "$resultado" -ne 0 ]; then compose logs --no-color; fi
    compose down --volumes --remove-orphans
}
trap finalizar EXIT
compose down --volumes --remove-orphans
compose up --build -d --wait
compose exec -T push python /testes/verificar.py
compose exec -T push python /testes/verificar.py preparar-reinicio
compose restart monitor
compose exec -T push python /testes/verificar.py finalizar-reinicio
