package Petroleum.Collab;

import Petroleum.Serialization.Json;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LanServer {
    public static final int DEFAULT_PORT = 7777;
    static final int MAX_ROOMS = 1000;
    static final int MAX_KEY = 1024;
    static final int MAX_NAME = 64;
    static final int CODE_LENGTH = 8;
    static final int TIMEOUT_MS = 15000;
    static final long ROOM_TTL_MS = 10 * 60 * 1000;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PONG = "{\"t\":\"pong\"}";

    private final Map<String, Room> rooms = new HashMap<>();
    private final Set<String> gone = new HashSet<>();
    private final ServerSocket socket;

    private LanServer(ServerSocket socket) {
        this.socket = socket;
    }

    public static LanServer start(int port) throws IOException {
        LanServer server = new LanServer(new ServerSocket(port));
        Thread thread = new Thread(server::serve, "lan-server");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        out.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }

    private void serve() {
        while (!socket.isClosed()) {
            try {
                Socket peer = socket.accept();
                peer.setTcpNoDelay(true);
                peer.setSoTimeout(TIMEOUT_MS);
                Thread thread = new Thread(() -> handle(peer), "peer");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException ignored) {
            }
        }
    }

    static String line(Object json) {
        return Json.write(json).replaceAll("\n *", "");
    }

    static String finalError(String message) {
        Map<String, Object> error = Json.object();
        error.put("t", "error");
        error.put("message", message);
        error.put("final", true);
        return line(error);
    }

    static String randomCode(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String hash(String token) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(Socket socket) {
        Peer peer;
        try {
            peer = new Peer(socket);
        } catch (IOException e) {
            return;
        }
        Room room = null;
        try {
            Map<String, Object> hello = Json.asObject(Json.parse(require(peer.readLine())));
            String name = Json.stringOrDefault(hello, "name", "");
            name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
            Object token = hello.get("token");
            String joinAs = Room.VIEWER.equals(hello.get("joinAs")) ? Room.VIEWER : Room.EDITOR;
            Room found = switch (Json.string(hello, "t")) {
                case "create" -> createRoom(joinAs);
                case "join" -> findRoom(Json.string(hello, "room"));
                case "restore" -> restoreRoom(Json.string(hello, "room"), joinAs);
                default -> throw new IllegalArgumentException("expected create, join or restore");
            };
            found.enter(peer, token instanceof String s ? s : null, name);
            room = found;
            String text;
            while ((text = peer.readLine()) != null) {
                Map<String, Object> msg = Json.asObject(Json.parse(text));
                switch (Json.string(msg, "t")) {
                    case "ping" -> peer.send(PONG);
                    case "op" -> {
                        String key = Json.string(msg, "k");
                        Object value = msg.get("v");
                        if (key.isEmpty() || key.length() > MAX_KEY || !(value == null || value instanceof String)) {
                            throw new IllegalArgumentException("bad op");
                        }
                        if (!room.apply(peer, key, (String) value)) {
                            throw new IllegalArgumentException("room is full");
                        }
                    }
                    case "role" -> room.setRole(peer, Json.string(msg, "member"), Json.string(msg, "role"));
                    case "joinAs" -> room.setJoinAs(peer, Json.string(msg, "role"));
                    case "kick" -> room.kick(peer, Json.string(msg, "member"));
                    case "transfer" -> room.transfer(peer, Json.string(msg, "member"));
                    case "close" -> closeRoom(room, peer);
                    case "reroll" -> reroll(room, peer);
                    default -> throw new IllegalArgumentException("unexpected message");
                }
            }
        } catch (IOException | RuntimeException e) {
            Map<String, Object> error = Json.object();
            error.put("t", "error");
            error.put("message", String.valueOf(e.getMessage()));
            peer.send(line(error));
        } finally {
            if (room != null) {
                room.leave(peer);
            }
            peer.close();
        }
    }

    private static String require(String line) {
        if (line == null) {
            throw new IllegalArgumentException("no hello");
        }
        return line;
    }

    private static String normalize(String code) {
        String upper = code.toUpperCase();
        if (upper.length() != CODE_LENGTH || upper.chars().anyMatch(c -> ALPHABET.indexOf(c) < 0)) {
            return null;
        }
        return upper;
    }

    private void makeRoomFor() {
        long cutoff = System.currentTimeMillis() - ROOM_TTL_MS;
        rooms.values().removeIf(r -> r.idleSince(cutoff));
        if (rooms.size() >= MAX_ROOMS) {
            throw new IllegalArgumentException("server is full");
        }
    }

    private String freshCode() {
        String code;
        do {
            code = randomCode(CODE_LENGTH);
        } while (rooms.containsKey(code) || gone.contains(code));
        return code;
    }

    private synchronized Room createRoom(String joinAs) {
        makeRoomFor();
        Room room = new Room(freshCode(), joinAs);
        rooms.put(room.code(), room);
        return room;
    }

    private synchronized Room findRoom(String raw) {
        String code = normalize(raw);
        Room room = code == null ? null : rooms.get(code);
        if (room == null) {
            throw new IllegalArgumentException("no such room");
        }
        return room;
    }

    private synchronized Room restoreRoom(String raw, String joinAs) {
        String code = normalize(raw);
        if (code == null) {
            throw new IllegalArgumentException("no such room");
        }
        Room room = rooms.get(code);
        if (room != null) {
            return room;
        }
        if (gone.contains(code)) {
            throw new IllegalArgumentException("the room was closed or its code changed");
        }
        makeRoomFor();
        room = new Room(code, joinAs);
        rooms.put(code, room);
        return room;
    }

    private synchronized void closeRoom(Room room, Peer peer) {
        String code = room.code();
        if (room.close(peer)) {
            rooms.remove(code);
            gone.add(code);
        }
    }

    private synchronized void reroll(Room room, Peer peer) {
        String old = room.code();
        String code = freshCode();
        if (room.reroll(peer, code)) {
            rooms.remove(old);
            rooms.put(code, room);
            gone.add(old);
        }
    }
}
