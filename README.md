# 🎻 Orquestrador
O sentinela de produtividade para desenvolvedores Linux.

O Orquestrador é uma barreira técnica contra o comportamento impulsivo. Ele foi projetado para quem reconhece que o autocontrole muitas vezes falha e que o ambiente de trabalho precisa ser blindado por código, não apenas por força de vontade.

💡 A Inspiração
O projeto nasceu de uma necessidade brutalmente sincera: eu estava perdendo tempo demais em redes sociais.

Como desenvolvedor, a facilidade de abrir uma nova aba para o X ou Reddit é um vício que destrói a produtividade. Percebi que bloqueadores comuns de navegador são fáceis demais de desativar. Eu precisava de algo que rodasse no nível do sistema (Kernel/Daemon), que fosse difícil de burlar e que agisse como um sentinela silencioso e implacável.

O Orquestrador orquestra o sistema operacional para que ele trabalhe a favor do meu foco, eliminando distrações antes mesmo que elas cheguem à tela.

🛡️ Funcionalidades Core
1. NetworkGuard (Bloqueio de Baixo Nível)
Diferente de extensões de browser, o Orquestrador manipula o /etc/hosts e gerencia regras de rede.

Bloqueio em Massa: Injeta mais de 75.000 domínios de distração e conteúdo adulto.

Redirecionamento Local: Todo tráfego para redes sociais é enviado para 127.0.0.1.

Resiliência: Mesmo em guia anônima ou diferentes navegadores, o bloqueio persiste.

2. FileSentinel (Destruição de APKs)
Uma camada extra de prevenção para evitar a "preparação" de distrações para o ambiente móvel.

Vigilância Ativa: Monitora recursivamente a pasta de Downloads usando java.nio.file.WatchService.

Zero Tolerância: Identifica e deleta arquivos .apk instantaneamente no momento em que o download é concluído.

3. Persistência Nativa (Systemd)
O software é integrado como um serviço do Linux.

Auto-Boot: Inicia antes do login do usuário.

Mútua Sobrevivência: Configurado para auto-reiniciar em 3 segundos caso o processo seja interrompido.

🛠️ Stack Tecnológica
Linguagem: Kotlin & Java (JVM)

Banco de Dados: SQLite (para logs de tentativas de acesso)

Gerenciamento de Serviço: Systemd (Linux)

Build Tool: Gradle (ShadowJar)

🚀 Instalação Rápida
Build:

Bash
./gradlew shadowJar
Deploy:
Mova o JAR para /opt/orquestrador/ e configure o arquivo .service em /etc/systemd/system/.

Ativação:

Bash
sudo systemctl enable --now orquestrador-bloqueador
Aviso: Este projeto é uma ferramenta de autodomínio. Ele assume que você tem privilégios de sudo, mas que escolheu usá-los para construir sua própria disciplina.
