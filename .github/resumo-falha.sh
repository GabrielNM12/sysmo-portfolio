#!/usr/bin/env bash
# Publica o essencial de uma falha de build como anotação do GitHub Actions
# (legível pela API de check-runs, sem precisar baixar o log completo).
log="$1"; relatorios="$2"
{
  grep -E "\[ERROR\]|FAILURE|Tests run:.*Fail|expected|Caused by|Exception:|\.java:\[" "$log" | grep -v "^\[ERROR\] *$" | head -120
  if [ -d "$relatorios" ]; then
    for f in "$relatorios"/*.txt; do grep -q -E "FAILURE|ERROR" "$f" && sed -n '1,60p' "$f"; done
  fi
} > resumo.txt 2>/dev/null
msg=$(sed 's/%/%25/g' resumo.txt | head -c 60000 | awk '{printf "%s%%0A", $0}')
echo "::error title=Resumo da falha::${msg}"
