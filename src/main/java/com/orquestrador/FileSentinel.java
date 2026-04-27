package com.orquestrador;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Monitora a pasta de Downloads (e subpastas) com {@link java.nio.file.WatchService}
 * e apaga arquivos .apk assim que aparecem (criados ou movidos).
 */
public class FileSentinel implements Runnable {

    private static final int DELETE_RETRIES = 5;
    private static final long RETRY_MS = 50L;
    /** APK recém-criado pode aparecer com 0 bytes antes do download preencher o arquivo. */
    private static final long ZERO_BYTE_APK_DELAY_MS = 500L;

    private final Path downloadsDir;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keyToPath = new HashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    public FileSentinel() throws IOException {
        this(resolveDownloadsDir());
    }

    /**
     * Caminho da pasta Downloads: {@code -Dorquestrador.downloads=}, variável {@code ORQUESTRADOR_DOWNLOADS},
     * ou {@code user.home}/Downloads. Com {@code sudo}, se essa pasta não existir, tenta o home do
     * {@code SUDO_USER} (via {@code getent passwd}). Se ainda faltar, cria {@code user.home}/Downloads
     * (útil para root em serviço).
     */
    public static Path resolveDownloadsDir() throws IOException {
        String prop = System.getProperty("orquestrador.downloads");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop.trim()).normalize().toAbsolutePath();
            Files.createDirectories(p);
            return p;
        }
        String env = System.getenv("ORQUESTRADOR_DOWNLOADS");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim()).normalize().toAbsolutePath();
            Files.createDirectories(p);
            return p;
        }
        Path fromUserHome = Path.of(System.getProperty("user.home"), "Downloads").normalize().toAbsolutePath();
        if (Files.isDirectory(fromUserHome)) {
            return fromUserHome;
        }
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser != null && !sudoUser.isBlank()) {
            String home = homeFromGetent(sudoUser.trim());
            if (home != null && !home.isBlank()) {
                Path sudoDownloads = Path.of(home, "Downloads").normalize().toAbsolutePath();
                if (Files.isDirectory(sudoDownloads)) {
                    return sudoDownloads;
                }
            }
        }
        Files.createDirectories(fromUserHome);
        return fromUserHome;
    }

    private static String homeFromGetent(String login) {
        try {
            Process p = new ProcessBuilder("getent", "passwd", login)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String line;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = br.readLine();
            }
            if (p.waitFor() != 0 || line == null || line.isEmpty()) {
                return null;
            }
            return homeFromPasswdLine(line);
        } catch (Exception e) {
            return null;
        }
    }

    /** Campo home da linha passwd: penúltimo campo separado por {@code :}. */
    static String homeFromPasswdLine(String line) {
        int last = line.lastIndexOf(':');
        if (last <= 0) {
            return null;
        }
        int prev = line.lastIndexOf(':', last - 1);
        if (prev < 0) {
            return null;
        }
        return line.substring(prev + 1, last);
    }

    public FileSentinel(Path downloadsDir) throws IOException {
        this.downloadsDir = downloadsDir.normalize().toAbsolutePath();
        if (!Files.isDirectory(this.downloadsDir)) {
            throw new IOException("Pasta de Downloads inexistente ou inacessível: " + this.downloadsDir);
        }
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    /** Fecha o {@link WatchService}, desbloqueia {@code take()} e finaliza a vigia. */
    public void shutdown() {
        tryClose();
    }

    private void tryClose() {
        if (open.compareAndSet(true, false) && watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        try {
            log("Iniciando FileSentinel em: " + downloadsDir);
            registerAllRecursive(this.downloadsDir);
            pollLoop();
        } catch (ClosedWatchServiceException e) {
            log("FileSentinel encerrado (serviço fechado).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("FileSentinel interrompido.");
        } catch (Exception e) {
            System.err.println("[FileSentinel] encerrado com erro: " + e.getMessage());
            e.printStackTrace();
        } finally {
            tryClose();
        }
    }

    private void registerAllRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    registerOne(dir);
                } catch (IOException e) {
                    log("Falha ao registrar diretório: " + dir + " — " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerOne(Path dir) throws IOException {
        Path d = dir.normalize().toAbsolutePath();
        for (Path existing : keyToPath.values()) {
            if (existing.equals(d)) {
                return;
            }
        }
        WatchKey key = d.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, OVERFLOW);
        if (key != null) {
            keyToPath.put(key, d);
            log("Diretório vigiado: " + d);
        }
    }

    private void rescanFrom(Path from) {
        try {
            registerAllRecursive(from);
        } catch (IOException e) {
            log("Falha no rescan: " + e.getMessage());
        }
    }

    private void pollLoop() throws IOException, InterruptedException {
        for (;;) {
            WatchKey key = watchService.take();
            Path base = keyToPath.get(key);
            if (base == null) {
                key.reset();
                continue;
            }
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == OVERFLOW) {
                    log("overflow de eventos — reescaneando a partir de " + base);
                    rescanFrom(base);
                    continue;
                }
                if (ev.context() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) ev;
                Path name = pathEvent.context();
                Path child = base.resolve(name).normalize().toAbsolutePath();
                if (!child.startsWith(downloadsDir)) {
                    continue;
                }
                if (kind == ENTRY_CREATE) {
                    if (Files.isDirectory(child) && Files.isReadable(child)) {
                        rescanFrom(child);
                        log("Novo subdiretório incluído na vigia: " + child);
                    } else if (isApk(child) && Files.isRegularFile(child)) {
                        deleteApkWithRetry(child, "ENTRY_CREATE");
                    }
                } else if (kind == ENTRY_MODIFY) {
                    if (Files.isRegularFile(child) && isApk(child)) {
                        deleteApkWithRetry(child, "ENTRY_MODIFY");
                    }
                }
            }
            if (!key.reset()) {
                Path p = keyToPath.remove(key);
                log("WatchKey não mais válida (unmount?), removendo: " + p);
            }
        }
    }

    private static boolean isApk(Path p) {
        return p.getFileName() != null
                && p.getFileName().toString().toLowerCase().endsWith(".apk");
    }

    private void deleteApkWithRetry(Path file, String reason) {
        for (int i = 0; i < DELETE_RETRIES; i++) {
            try {
                if (!Files.isRegularFile(file) || !isApk(file)) {
                    if (!Files.exists(file)) {
                        return;
                    }
                    break;
                }
                if (Files.size(file) == 0L) {
                    try {
                        Thread.sleep(ZERO_BYTE_APK_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (Files.deleteIfExists(file)) {
                    log("BLOQUEADO: removido .apk (" + reason + "): " + file + " @ " + Instant.now());
                    StatisticsManager.INSTANCE.logApkDeleted(file.toString());
                    return;
                }
                if (!Files.exists(file)) {
                    return;
                }
            } catch (Exception e) {
                log("tentativa " + (i + 1) + " falhou para " + file + ": " + e.getMessage());
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void log(String msg) {
        System.out.println("[FileSentinel] " + Instant.now() + " " + msg);
    }
}
