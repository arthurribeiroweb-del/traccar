# Push notifications no Traccar Manager (canal Traccar)

Este guia habilita o canal "Traccar" (push) para que alertas cheguem no app Traccar Manager mesmo com o app fechado.

## 1) Configuracao no servidor

Arquivo de configuracao (instalacao Linux padrao):
- /opt/traccar/conf/traccar.xml

Exemplo de snippet:

```xml
<entry key='notificator.types'>web,mail,command,traccar</entry>
<entry key='notificator.traccar.key'>SUA_TRACCAR_KEY_AQUI</entry>
```

Obs: este repositorio inclui um arquivo de exemplo em `traccar.xml`.

## 2) Onde pegar a Traccar Notifications API key

1. Acesse sua conta em traccar.org
2. Na pagina de conta, copie a "Traccar notifications API key"
3. Cole o valor em `notificator.traccar.key`

## 3) Reiniciar o servico

- systemctl restart traccar

## 4) Como criar notificacao de cerca "CASA"

1. Configuracoes -> Notificacoes -> +
2. Tipo: Entrada na cerca virtual (e/ou Saida da cerca virtual)
3. Cerca: CASA
4. Canal: Traccar (push)
5. Selecione dispositivo(s) e salve

## 5) Diferenca entre Web x Traccar (push)

- Web: aparece somente quando o usuario esta com a interface web aberta.
- Traccar (push): entrega no app Traccar Manager, inclusive com o app fechado.

## 6) Troubleshooting rapido

Checklist:
1. O canal "Traccar" aparece em Notificacoes? Se nao, revise `notificator.types` e reinicie.
2. A chave `notificator.traccar.key` foi configurada?
3. O servidor tem acesso a internet (porta 443) para enviar push?
4. No celular, notificacoes permitidas para o app Traccar Manager e sem bloqueio de bateria.
5. Logs do servidor:
   - systemd: `journalctl -u traccar -f`
   - ou arquivo de log em `logs/traccar.log`
   Procure por mensagens como "Notification push error" ou "Push user ... error".

## 7) Validacao rapida

- Crie a notificacao de Entrada/Saida da cerca "CASA" com canal Traccar.
- Simule a entrada/saida na cerca.
- Confirme o push chegando no app.
