package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 镜像服进程控制器。
 * 通过 ProcessBuilder 启动独立 JVM 进程，stdin 发命令，stdout 读日志。
 */
public class MirrorProcess {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private final Path mirrorDir;
    private final MirrorConfig config;

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private Process process;
    private BufferedWriter stdin;
    private Thread outputThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 日志回调（用于捕获 "Done" 等启动完成信号） */
    private volatile Consumer<String> logListener;

    public MirrorProcess(Path mirrorDir, MirrorConfig config) {
        this.mirrorDir = mirrorDir;
        this.config = config;
    }

    public State getState() { return state.get(); }
    public boolean isRunning() { return running.get(); }
    public void setLogListener(Consumer<String> listener) { this.logListener = listener; }

    /**
     * 启动镜像服进程。
     * 需要先确定 server jar 文件名。
     */
    public boolean start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            LOGGER.warn("[Process] Cannot start, state={}", state.get());
            return false;
        }

        try {
            Path serverJar = findServerJar();
            if (serverJar == null) {
                LOGGER.error("[Process] No server jar in {}", mirrorDir);
                state.set(State.ERROR);
                return false;
            }

            // 用主服同一份 JVM（java.home），避免 PATH 里 java 版本不符
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-Dmirror.instance=true",       // 镜像模式自识别
                    "-Xms2G", "-Xmx4G",              // 镜像服内存
                    "-jar", serverJar.getFileName().toString(),
                    "nogui"
            );
            pb.directory(mirrorDir.toFile());
            pb.redirectErrorStream(true); // stderr 合并到 stdout
            // 显式隔离 stdin：镜像服 stdin 是独立 PIPE，不继承主服 System.in
            // （主服控制台输入只进主服，镜像服命令只能通过 sendCommand 显式发送）
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            LOGGER.info("[Process] Starting mirror server: {}", String.join(" ", pb.command()));
            process = pb.start();

            // 独立线程读 stdout
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            outputThread = new Thread(() -> readOutput(reader), "Mirror-Process-Output");
            outputThread.setDaemon(true);
            outputThread.start();

            // stdin 写入器
            stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            running.set(true);
            state.set(State.RUNNING);
            LOGGER.info("[Process] Mirror process started (pid={})", process.pid());
            return true;
        } catch (IOException e) {
            LOGGER.error("[Process] Failed to start mirror process", e);
            state.set(State.ERROR);
            return false;
        }
    }

    /**
     * 发送命令到镜像服 stdin（等价于控制台输入）。
     */
    public boolean sendCommand(String command) {
        if (!running.get() || stdin == null) return false;
        try {
            stdin.write(command);
            stdin.newLine();
            stdin.flush();
            LOGGER.info("[Process] → {}", command);
            return true;
        } catch (IOException e) {
            LOGGER.error("[Process] Failed to send command", e);
            return false;
        }
    }

    /**
     * 停止镜像服（异步：先发 stop，后台等待退出，超时强杀）。
     * 不阻塞调用线程，避免卡住主服 tick 循环。
     */
    public void stop() {
        if (state.get() == State.STOPPED) return;
        state.set(State.STOPPING);

        // 先发 stop 命令（此时 running 仍为 true，sendCommand 才能生效）
        sendCommand("stop");

        // 标记停止中，阻止后续命令
        running.set(false);

        // 后台线程等待进程退出，超时强杀
        Thread waiter = new Thread(() -> {
            try {
                boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    LOGGER.warn("[Process] Graceful stop timed out, force killing");
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                process = null;
                stdin = null;
                state.set(State.STOPPED);
                LOGGER.info("[Process] Mirror process stopped");
            }
        }, "Mirror-Stop-Thread");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * 同步停止镜像服（发 stop，当前线程等待退出，超时强杀）。
     * 供 sync 等在后台线程执行的场景使用，确保进程真正退出后再操作文件。
     */
    public void stopAndWait() {
        if (state.get() == State.STOPPED) return;
        state.set(State.STOPPING);

        sendCommand("stop");
        running.set(false);

        try {
            boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                LOGGER.warn("[Process] Graceful stop timed out, force killing");
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            process = null;
            stdin = null;
            state.set(State.STOPPED);
            LOGGER.info("[Process] Mirror process stopped");
        }
    }

    /**
     * 强制杀死镜像服进程。
     */
    public void forceKill() {
        running.set(false);
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        state.set(State.STOPPED);
    }

    private void readOutput(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                // 只转发关键事件，避免镜像服日志刷屏污染主服控制台。
                // 镜像服完整日志由自身写入 mirror/logs/latest.log。
                if (isKeyEvent(line)) {
                    LOGGER.info("[MirrorSrv] {}", line);
                }
                Consumer<String> listener = logListener;
                if (listener != null) listener.accept(line);
            }
        } catch (IOException ignored) {
        } finally {
            running.set(false);
            if (state.get() != State.STOPPING && state.get() != State.STOPPED) {
                state.set(State.ERROR);
                LOGGER.warn("[Process] Mirror process output stream closed");
            }
        }
    }

    /**
     * 判断镜像服日志行是否为需要转发到主服的关键事件。
     * 关键事件：启动完成、严重错误、异常、停止。
     */
    private static boolean isKeyEvent(String line) {
        if (line == null) return false;
        String l = line;
        return l.contains("Done (")           // 启动完成
                || l.contains("ERROR")        // 错误
                || l.contains("Exception")    // 异常
                || l.contains("Caused by")    // 异常堆栈
                || l.contains("Stopping")     // 停止
                || l.contains("crash");       // 崩溃
    }

    private Path findServerJar() {
        try (java.nio.file.DirectoryStream<Path> stream =
                     java.nio.file.Files.newDirectoryStream(mirrorDir, "*.jar")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.contains("fabric-server-launch") || name.equals("server.jar")) {
                    return p;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }
}
