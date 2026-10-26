# Shoyo Macro (mod Forge 1.8.9)
Autor: shoyoo.mc. Mod do lado do cliente, funciona em qualquer launcher.

Comandos no chat: /shoyo attack|place|break [on|off], /shoyo cps attack|place <min> <max>, /shoyo status
Tambem ha teclas (sem tecla padrao) em Controles > Shoyo Macro.

Logica: segurando o botao, clica em intervalo aleatorio de 1000/CPSmax ate 1000/CPSmin ms
(padrao 6-8 CPS). Se a mira estiver num bloco, nao clica (deixa quebrar normal) a menos que
"break" esteja ligado. Colocar bloco so age com bloco na mao. Use onde macros forem permitidos.

Launcher: o mod envia ao SHOYO LAUNCHER (UDP 127.0.0.1:47653) se a mira esta num bloco; o macro do launcher pausa nessa hora.
Para usar so o macro do launcher, deixe attack/place do mod desligados (padrao).
