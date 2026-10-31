package Petroleum.Collab;

import Petroleum.Serialization.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Room {
    static final long MAX_STATE = 128L * 1024 * 1024;
    static final int MAX_MEMBERS = 256;
    static final String HOST = "host";
    static final String EDITOR = "editor";
    static final String VIEWER = "viewer";

    static final class Member {
        final String id;
        final String tokenHash;
        String name;
        String role;

        Member(String id, String tokenHash, String name, String role) {
            this.id = id;
            this.tokenHash = tokenHash;
            this.name = name;
            this.role = role;
        }
    }

    private String code;
    private final Map<String, String> state = new LinkedHashMap<>();
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final List<Peer> peers = new ArrayList<>();
    private String joinAs;
    private long seq;
    private long size;
    private int nextMember = 1;
    private long emptySince = System.currentTimeMillis();
    private boolean dirty;
    private boolean closed;

    Room(String code, String joinAs) {
        this.code = code;
        this.joinAs = joinAs;
    }

    synchronized String code() {
        return code;
    }

    synchronized void enter(Peer peer, String token, String name) {
        if (closed) {
            throw new IllegalArgumentException("no such room");
        }
        Member member = null;
        if (token != null) {
            String hash = LanServer.hash(token);
            for (Member m : members.values()) {
                if (m.tokenHash.equals(hash)) {
                    member = m;
                }
            }
        }
        String issued = token;
        if (member == null) {
            if (members.size() >= MAX_MEMBERS) {
                throw new IllegalArgumentException("too many people in this room");
            }
            issued = LanServer.randomCode(26);
            member = new Member("m" + nextMember++, LanServer.hash(issued), name, members.isEmpty() ? HOST : joinAs);
            members.put(member.id, member);
        }
        member.name = name;
        peer.member = member;
        peer.id = "c" + LanServer.randomCode(8);
        dirty = true;
        Map<String, Object> welcome = Json.object();
        welcome.put("t", "welcome");
        welcome.put("room", code);
        welcome.put("client", peer.id);
        welcome.put("member", member.id);
        welcome.put("token", issued);
        welcome.put("role", member.role);
        welcome.put("joinAs", joinAs);
        welcome.put("seq", seq);
        peer.send(LanServer.line(welcome));
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Map<String, Object> stateMsg = Json.object();
            stateMsg.put("t", "state");
            stateMsg.put("k", entry.getKey());
            stateMsg.put("v", entry.getValue());
            peer.send(LanServer.line(stateMsg));
        }
        Map<String, Object> ready = Json.object();
        ready.put("t", "ready");
        peer.send(LanServer.line(ready));
        peers.add(peer);
        broadcastMembers();
    }

    synchronized boolean apply(Peer from, String key, String value) {
        if (closed) {
            return false;
        }
        if (VIEWER.equals(from.member.role)) {
            Map<String, Object> denied = Json.object();
            denied.put("t", "op");
            denied.put("seq", seq);
            denied.put("from", from.id);
            denied.put("k", key);
            denied.put("v", state.get(key));
            denied.put("denied", true);
            from.send(LanServer.line(denied));
            return true;
        }
        String old = state.get(key);
        long next = size - weight(key, old) + weight(key, value);
        if (next > MAX_STATE) {
            return false;
        }
        size = next;
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
        dirty = true;
        Map<String, Object> op = Json.object();
        op.put("t", "op");
        op.put("seq", ++seq);
        op.put("from", from.id);
        op.put("k", key);
        op.put("v", value);
        String line = LanServer.line(op);
        for (Peer peer : peers) {
            peer.send(line);
        }
        return true;
    }

    private boolean hosts(Peer peer) {
        return !closed && HOST.equals(peer.member.role);
    }

    synchronized void setRole(Peer from, String memberId, String role) {
        Member member = members.get(memberId);
        if (hosts(from) && member != null && !HOST.equals(member.role) && (EDITOR.equals(role) || VIEWER.equals(role))) {
            member.role = role;
            dirty = true;
            broadcastMembers();
        }
    }

    synchronized void setJoinAs(Peer from, String role) {
        if (hosts(from) && (EDITOR.equals(role) || VIEWER.equals(role))) {
            joinAs = role;
            dirty = true;
            broadcastMembers();
        }
    }

    synchronized void transfer(Peer from, String memberId) {
        Member member = members.get(memberId);
        if (hosts(from) && member != null && member != from.member) {
            member.role = HOST;
            from.member.role = EDITOR;
            dirty = true;
            broadcastMembers();
        }
    }

    synchronized void kick(Peer from, String memberId) {
        Member member = members.get(memberId);
        if (!hosts(from) || member == null || HOST.equals(member.role)) {
            return;
        }
        members.remove(memberId);
        for (Peer peer : new ArrayList<>(peers)) {
            if (peer.member == member) {
                peers.remove(peer);
                peer.send(LanServer.finalError("you were removed from the room by the host"));
                peer.close();
            }
        }
        dirty = true;
        broadcastMembers();
    }

    synchronized boolean close(Peer from) {
        if (!hosts(from)) {
            return false;
        }
        closed = true;
        for (Peer peer : peers) {
            peer.send(LanServer.finalError("the host closed the room"));
            peer.close();
        }
        peers.clear();
        return true;
    }

    synchronized boolean reroll(Peer from, String newCode) {
        if (!hosts(from)) {
            return false;
        }
        code = newCode;
        members.values().removeIf(m -> peers.stream().noneMatch(p -> p.member == m));
        dirty = true;
        Map<String, Object> msg = Json.object();
        msg.put("t", "room");
        msg.put("room", code);
        String line = LanServer.line(msg);
        for (Peer peer : peers) {
            peer.send(line);
        }
        broadcastMembers();
        return true;
    }

    synchronized void leave(Peer peer) {
        if (peers.remove(peer)) {
            if (peers.isEmpty()) {
                emptySince = System.currentTimeMillis();
            }
            broadcastMembers();
        }
    }

    synchronized boolean idleSince(long cutoff) {
        return peers.isEmpty() && emptySince < cutoff;
    }

    synchronized boolean isDirty() {
        return dirty;
    }

    synchronized String snapshot() {
        dirty = false;
        Map<String, Object> json = Json.object();
        json.put("code", code);
        json.put("joinAs", joinAs);
        json.put("seq", seq);
        json.put("nextMember", nextMember);
        List<Object> list = new ArrayList<>();
        for (Member m : members.values()) {
            Map<String, Object> entry = Json.object();
            entry.put("id", m.id);
            entry.put("tokenHash", m.tokenHash);
            entry.put("name", m.name);
            entry.put("role", m.role);
            list.add(entry);
        }
        json.put("members", list);
        Map<String, Object> values = Json.object();
        values.putAll(state);
        json.put("state", values);
        return Json.write(json);
    }

    static Room restore(String text) {
        Map<String, Object> json = Json.asObject(Json.parse(text));
        Room room = new Room(Json.string(json, "code"), Json.string(json, "joinAs"));
        room.seq = (long) Json.number(json, "seq");
        room.nextMember = (int) Json.number(json, "nextMember");
        for (Object item : Json.asArray(json.get("members"))) {
            Map<String, Object> m = Json.asObject(item);
            Member member = new Member(Json.string(m, "id"), Json.string(m, "tokenHash"),
                    Json.string(m, "name"), Json.string(m, "role"));
            room.members.put(member.id, member);
        }
        for (Map.Entry<String, Object> entry : Json.asObject(json.get("state")).entrySet()) {
            String value = (String) entry.getValue();
            room.state.put(entry.getKey(), value);
            room.size += weight(entry.getKey(), value);
        }
        return room;
    }

    private void broadcastMembers() {
        List<Object> list = new ArrayList<>();
        for (Member m : members.values()) {
            Map<String, Object> entry = Json.object();
            entry.put("id", m.id);
            entry.put("name", m.name);
            entry.put("role", m.role);
            entry.put("online", peers.stream().anyMatch(p -> p.member == m));
            list.add(entry);
        }
        Map<String, Object> msg = Json.object();
        msg.put("t", "members");
        msg.put("joinAs", joinAs);
        msg.put("list", list);
        String line = LanServer.line(msg);
        for (Peer peer : peers) {
            peer.send(line);
        }
    }

    private static long weight(String key, String value) {
        return value == null ? 0 : key.length() + value.length();
    }
}
