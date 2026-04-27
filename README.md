# 🎻 Orquestrador
O sentinela de produtividade para desenvolvedores Linux.

O Orquestrador é um serviço nativo (Daemon) escrito em Kotlin e Java, projetado para eliminar distrações de forma implacável diretamente no núcleo do sistema operacional.

🛡️ Funcionalidades
NetworkGuard: Bloqueio massivo de URLs (75k+ domínios) via manipulação de /etc/hosts.

FileSentinel: Monitoramento recursivo em tempo real para detecção e destruição imediata de arquivos .apk.

Systemd Integration: Início automático no boot e mútua sobrevivência (auto-restart).

SQLite Logging: Registro local de todas as tentativas de acesso bloqueadas.

🚀 Como instalar
Clone o repositório.

Gere o jar: ./gradlew shadowJar.

Execute o script de deploy: sudo install.sh.
