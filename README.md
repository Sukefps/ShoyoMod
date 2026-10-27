# MP-Mod (mod Forge 1.8.9)
Autor: shoyoo.mc. Mod so do lado do cliente; nao depende de launcher nenhum.

Comandos no chat: /shoyo attack|place|break|hud [on|off], /shoyo cps attack|place <min> <max>, /shoyo status
Teclas (sem tecla padrao): Controles > MP-Mod.

Como funciona (so clica enquanto voce SEGURA o botao):
- Atacar: clique esquerdo em CPS aleatorio entre o minimo e o maximo (padrao 6-8 = 125-167 ms, de 0 a 20).
- Mira num bloco: nao clica, o jogo quebra o bloco normalmente (ligue "break" para clicar mesmo assim).
- Colocar bloco: CPS proprio, so age com BLOCO na mao; comendo, bebendo ou usando item nunca clica.
- Tudo desligado por padrao. Use onde macros forem permitidos.

Ligado ao SHOYO LAUNCHER: o launcher envia as configuracoes (interruptores e CPS) ao mod por UDP local (127.0.0.1:47654) e o MP-Mod faz os cliques, com as pausas de bloco e comida. Sem o mod, o launcher clica sozinho.

Atacar: clica sem parar enquanto segura o botao, com ou sem entidade perto. Quebrando bloco, mirando num bloco ou comendo: nao clica. Opcional: /shoyo entity on faz clicar so com entidade a ate 'radius' blocos (/shoyo radius N).
