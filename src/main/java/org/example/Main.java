package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.*;

// --- DATA MODELS ---
class Message {
    public String type;
    public String roomId;
    public String username;
    public Object payload;

    public Message() {
    }

    public Message(String type, String roomId, String username, Object payload) {
        this.type = type;
        this.roomId = roomId;
        this.username = username;
        this.payload = payload;
    }
}

class GameRoom {
    public String roomId;
    public Map<String, String> users = new ConcurrentHashMap<>();
    public Map<String, Integer> scores = new ConcurrentHashMap<>();

    public String currentDrawerId = null;
    public String currentWord = null;
    public boolean gameActive = false;

    public long roundEndTime = 0;
    public long sessionStartTime = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> currentTask;
    private ScheduledFuture<?> sessionTask;
    private ScheduledFuture<?> countdownTask;

    private final List<String> WORD_LIST = Arrays.asList(
            "apple", "banana", "cat", "dog", "car", "house", "tree", "sun", "moon", "star",
            "computer", "phone", "book", "chair", "table", "guitar", "pizza", "camera", "clock",
            "flower", "mountain", "ocean", "pencil", "bottle", "lamp", "door", "window", "rocket");

    // CONFIGURATION
    private final long ROUND_DURATION = 120000; // 2 mins
    private final long SESSION_DURATION = 600000; // 10 mins
    private final long COUNTDOWN_DURATION = 20000; // 20 secs

    public GameRoom(String roomId) {
        this.roomId = roomId;
    }

    // --- GAME LOOP ---

    public void checkAutoStart() {
        if (users.size() >= 2 && !gameActive && countdownTask == null) {
            broadcast("chatMessage", Map.of("from", "System", "text", "2 players joined! Starting in 20s..."));

            final int[] seconds = { (int) (COUNTDOWN_DURATION / 1000) };
            countdownTask = scheduler.scheduleAtFixedRate(() -> {
                broadcast("countdown", Map.of("seconds", seconds[0]));
                if (seconds[0] <= 0) {
                    countdownTask.cancel(false);
                    startSession();
                }
                seconds[0]--;
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    private void startSession() {
        gameActive = true;
        sessionStartTime = System.currentTimeMillis();
        broadcast("chatMessage", Map.of("from", "System", "text", "Session Started! 10 minutes to play."));

        startNewRound();
        sessionTask = scheduler.schedule(this::endSession, SESSION_DURATION, TimeUnit.MILLISECONDS);
    }

    private void startNewRound() {
        if (!gameActive)
            return;

        // Pick next drawer
        List<String> playerIds = new ArrayList<>(users.keySet());
        if (playerIds.isEmpty()) {
            endSession();
            return;
        }

        int currentIndex = playerIds.indexOf(currentDrawerId);
        int nextIndex = (currentIndex + 1) % playerIds.size();
        currentDrawerId = playerIds.get(nextIndex);

        // Pick word
        currentWord = WORD_LIST.get(new Random().nextInt(WORD_LIST.size()));
        roundEndTime = System.currentTimeMillis() + ROUND_DURATION;
        String drawerName = users.get(currentDrawerId);

        // Notifications
        broadcast("clearBoard", null);
        broadcast("chatMessage", Map.of("from", "System", "text", drawerName + " is drawing!"));
        Main.sendToSocket(currentDrawerId, new Message("yourWord", roomId, "System", Map.of("word", currentWord)));

        sendGameState();

        // Round Timer
        if (currentTask != null)
            currentTask.cancel(false);
        currentTask = scheduler.schedule(() -> endRound(false), ROUND_DURATION, TimeUnit.MILLISECONDS);
    }

    public void endRound(boolean guessed) {
        if (currentTask != null)
            currentTask.cancel(false);

        String msg = guessed ? "Round ended! Word was " + currentWord : "Time's up! Word was " + currentWord;
        broadcast("chatMessage", Map.of("from", "System", "text", msg));

        currentWord = null;
        roundEndTime = 0;
        sendGameState();

        // Check if session time is up
        if (System.currentTimeMillis() - sessionStartTime >= SESSION_DURATION) {
            endSession();
            return;
        }

        // 5s delay before next round
        scheduler.schedule(() -> {
            if (gameActive && !users.isEmpty())
                startNewRound();
        }, 5, TimeUnit.SECONDS);
    }

    private void endSession() {
        gameActive = false;
        if (currentTask != null)
            currentTask.cancel(false);
        if (sessionTask != null)
            sessionTask.cancel(false);
        if (countdownTask != null)
            countdownTask.cancel(false);

        // Calculate Leaderboard
        List<Map.Entry<String, Integer>> leaderboard = new ArrayList<>(scores.entrySet());
        leaderboard.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        String winner = leaderboard.isEmpty() ? "No one" : leaderboard.get(0).getKey();

        broadcast("sessionEnded", Map.of("winner", winner, "leaderboard", leaderboard));
        broadcast("chatMessage", Map.of("from", "System", "text", "GAME OVER! Winner: " + winner));

        // Reset after 10s
        scheduler.schedule(() -> {
            scores.replaceAll((k, v) -> 0);
            broadcast("scoreUpdate", Map.of("scores", scores));
        }, 10, TimeUnit.SECONDS);
    }

    // --- INTERACTION ---

    public void handleGuess(String socketId, String text) {
        String username = users.get(socketId);
        if (socketId.equals(currentDrawerId)) {
            broadcast("chatMessage", Map.of("from", username, "text", text)); // Drawer chat
            return;
        }

        if (gameActive && currentWord != null && text.trim().equalsIgnoreCase(currentWord)) {
            broadcast("chatMessage", Map.of("from", "System", "text", username + " guessed correctly! (+10 pts)"));

            scores.put(username, scores.getOrDefault(username, 0) + 10);
            String drawerName = users.get(currentDrawerId);
            if (drawerName != null)
                scores.put(drawerName, scores.getOrDefault(drawerName, 0) + 5);

            broadcast("scoreUpdate", Map.of("scores", scores));
            endRound(true);
        } else {
            broadcast("chatMessage", Map.of("from", username, "text", text));
        }
    }

    public void broadcast(String type, Object payload) {
        Main.broadcastToRoom(roomId, new Message(type, roomId, "System", payload));
    }

    public void sendGameState() {
        long rTime = roundEndTime > 0 ? (roundEndTime - System.currentTimeMillis()) / 1000 : 0;
        long sTime = sessionStartTime > 0 ? (SESSION_DURATION - (System.currentTimeMillis() - sessionStartTime)) / 1000
                : 0;
        String dName = currentDrawerId != null ? users.get(currentDrawerId) : "";

        broadcast("gameState", Map.of(
                "gameActive", gameActive, "currentDrawer", dName == null ? "" : dName,
                "hasWord", (currentWord != null), "timeRemaining", Math.max(0, rTime),
                "sessionTimeRemaining", Math.max(0, sTime)));
    }

    public void removeUser(String socketId) {
        String name = users.remove(socketId);
        if (name != null) {
            broadcast("userList", users.values());
            broadcast("chatMessage", Map.of("from", "System", "text", name + " left."));
            if (users.isEmpty()) {
                scheduler.shutdownNow();
                Main.removeRoom(roomId);
            } else if (socketId.equals(currentDrawerId) && gameActive) {
                endRound(false);
            }
        }
    }
}

// --- MAIN SERVER ---
public class Main {
    private static final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private static final Map<WsContext, String> userRoomMap = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        Javalin app = Javalin.create(config -> config.router.mount(router -> {
        })).start(port);

        // 1. SERVE FRONTEND
        app.get("/", ctx -> ctx.contentType("text/html").result(new FileInputStream("index.html")));

        System.out.println("Scribble Server running at http://localhost:7070");

        // 2. WEBSOCKET LOGIC
        app.ws("/game", ws -> {
            ws.onConnect(ctx -> System.out.println("New Connection: " + ctx.sessionId()));
            ws.onClose(ctx -> {
                String rId = userRoomMap.remove(ctx);
                if (rId != null && rooms.containsKey(rId))
                    rooms.get(rId).removeUser(ctx.sessionId());
            });

            ws.onMessage(ctx -> {
                Message msg = ctx.messageAsClass(Message.class);
                String rId = msg.roomId;

                if ("joinRoom".equals(msg.type)) {
                    userRoomMap.put(ctx, rId);
                    rooms.computeIfAbsent(rId, GameRoom::new);
                    GameRoom room = rooms.get(rId);

                    // Handle duplicate usernames
                    String originalName = msg.username;
                    String finalName = originalName;
                    int counter = 1;
                    while (room.users.containsValue(finalName)) {
                        finalName = originalName + counter;
                        counter++;
                    }

                    room.users.put(ctx.sessionId(), finalName);
                    room.scores.putIfAbsent(finalName, 0);

                    // Notify the specific user of their final name (in case of rename)
                    ctx.send(mapper.writeValueAsString(
                            new Message("joinSuccess", rId, "System", Map.of("username", finalName))));

                    room.broadcast("userList", room.users.values());
                    room.broadcast("scoreUpdate", Map.of("scores", room.scores));
                    room.broadcast("chatMessage", Map.of("from", "System", "text", finalName + " joined."));
                    room.sendGameState();
                    room.checkAutoStart();
                }

                GameRoom room = rooms.get(rId);
                if (room == null)
                    return;

                if ("ping".equals(msg.type))
                    return; // Heartbeat

                if ("stroke".equals(msg.type)) {
                    if (ctx.sessionId().equals(room.currentDrawerId)) {
                        String json = mapper
                                .writeValueAsString(new Message("remoteStroke", rId, "System", msg.payload));
                        userRoomMap.forEach((c, rid) -> {
                            if (rid.equals(rId) && !c.sessionId().equals(ctx.sessionId()) && c.session.isOpen()) {
                                c.send(json);
                            }
                        });
                    }
                } else if ("clear".equals(msg.type)) {
                    if (ctx.sessionId().equals(room.currentDrawerId))
                        room.broadcast("clearBoard", null);
                } else if ("guess".equals(msg.type)) {
                    Map p = (Map) msg.payload;
                    room.handleGuess(ctx.sessionId(), (String) p.get("text"));
                }
            });
        });
    }

    public static void broadcastToRoom(String roomId, Message msg) {
        userRoomMap.forEach((ctx, rId) -> {
            if (rId.equals(roomId) && ctx.session.isOpen())
                ctx.send(msg);
        });
    }

    public static void sendToSocket(String socketId, Message msg) {
        userRoomMap.forEach((ctx, rId) -> {
            if (ctx.sessionId().equals(socketId) && ctx.session.isOpen())
                ctx.send(msg);
        });
    }

    public static void removeRoom(String roomId) {
        rooms.remove(roomId);
    }
}