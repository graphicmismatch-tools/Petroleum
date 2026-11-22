package Petroleum.Collab;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

final class Peer {
    static final int MAX_LINE = 32 * 1024 * 1024;
    private static final long MAX_QUEUED = 192L * 1024 * 1024;
    private static final String CLOSE = new String("close");

    private final Socket socket;
    private final InputStream in;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    String id = "";
    Room.Member member;

    Peer(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        Thread writer = new Thread(() -> drain(out), "peer-writer");
        writer.setDaemon(true);
        writer.start();
    }

    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            }
            if (line.size() >= MAX_LINE) {
                throw new IOException("line too long");
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    void send(String line) {
        if (queued.get() + line.length() > MAX_QUEUED) {
            close();
            return;
        }
        queued.addAndGet(line.length());
        outbox.add(line);
    }

    void close() {
        outbox.add(CLOSE);
    }

    private void shut() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void drain(OutputStream out) {
        try {
            while (true) {
                String line = outbox.take();
                if (line == CLOSE) {
                    out.flush();
                    shut();
                    return;
                }
                queued.addAndGet(-line.length());
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            shut();
        }
    }
}
