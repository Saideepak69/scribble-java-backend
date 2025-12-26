package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.*;
import java.util.concurrent.*;

// --- DATA STRUCTURES ---

class Message {
    public String type; // join, chat, guess, stroke, clear, etc.
    public String roomId;
    public String username;
    public Object payload; // flexible data payload

    public Message() {}
    public Message(String type, String roomId, String username, Object payload) {
        this.type = type;
        this.roomId = roomId;
        this.username = username;
        this.payload = payload;
    }
}

class DrawStroke {
    public Map<String, Double> from;
    public Map<String, Double> to;
    public String color;
    public int size;
    public String tool;
}

class GameState {
    public boolean gameActive;
    public String currentDrawer;
    public boolean hasWord;
    public long timeRemaining;       // seconds
    public long sessionTimeRemaining;// seconds
}

// --- GAME ROOM LOGIC ---

class GameRoom {
    public String roomId;
    public Map<String, String> users = new ConcurrentHashMap<>(); // socketId -> username
    public Map<String, Integer> scores = new ConcurrentHashMap<>();
    
    public String currentDrawerId = null;
    public String currentWord = null;
    public boolean gameActive = false;
    
    // Timers
    public long roundEndTime = 0;
    public long sessionStartTime = 0;
    
    // Scheduler for managing game loop
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> currentTask;
    private ScheduledFuture<?> countdownTask;

    private final List<String> WORD_LIST = Arrays.asList(
        "apple", "banana", "cat", "dog", "car", "house", "tree", "sun", "moon", 
        "computer", "phone", "book", "chair", "guitar", "pizza", "rocket"
    );

    // Constants (in milliseconds)
    private final long ROUND_DURATION = 120000; 
    private final long SESSION_DURATION = 600000;
    private final long COUNTDOWN_DURATION = 20000;

    public GameRoom(String roomId) {
        this.roomId = roomId;
    }

    public void broadcast(String type, Object payload) {
        Main.broadcastToRoom(roomId, new Message(type, roomId, "System", payload));
    }

    public void broadcastToUser(String socketId, String type, Object payload) {
        Main.sendToSocket(socketId, new Message(type, roomId, "System", payload));
    }

    public void startGameLoop() {
        if (gameActive) return;
        
        broadcast("chatMessage", Map.of("from", "System", "text", "Minimum players reached! Starting in 20 seconds..."));
        
        // Start Countdown
        final int[] countdown = { (int)(COUNTDOWN_DURATION / 1000) };
        
        countdownTask = scheduler.scheduleAtFixedRate(() -> {
            broadcast("countdown", Map.of("seconds", countdown[0]));
            countdown[0]--;

            if (countdown[0] < 0) {
                countdownTask.cancel(false);
                startSession();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void startSession() {
        gameActive = true;
        sessionStartTime = System.currentTimeMillis();
        broadcast("chatMessage", Map.of("from", "System", "text", "Session Started! 10 minutes to play."));
        startNewRound();
        
        // Session End Timer
        scheduler.schedule(this::endSession, SESSION_DURATION, TimeUnit.MILLISECONDS);
    }

    private void startNewRound() {
        if (!gameActive) return;

        // Select next drawer
        List<String> playerIds = new ArrayList<>(users.keySet());
        if (playerIds.isEmpty()) { endSession(); return; }

        int currentIndex = playerIds.indexOf(currentDrawerId);
        int nextIndex = (currentIndex + 1) % playerIds.size();
        currentDrawerId = playerIds.get(nextIndex);

        // Select Word
        currentWord = WORD_LIST.get(new Random().nextInt(WORD_LIST.size()));
        roundEndTime = System.currentTimeMillis() + ROUND_DURATION;

        String drawerName = users.get(currentDrawerId);

        broadcast("clearBoard", null);
        broadcast("chatMessage", Map.of("from", "System", "text", drawerName + " is drawing! Guess the word!"));
        broadcastToUser(currentDrawerId, "yourWord", Map.of("word", currentWord));

        sendGameState();

        // Round End Timer
        if (currentTask != null && !currentTask.isDone()) currentTask.cancel(false);
        currentTask = scheduler.schedule(() -> endRound(false), ROUND_DURATION, TimeUnit.MILLISECONDS);
    }

    public void endRound(boolean guessed) {
        if (currentTask != null) currentTask.cancel(false);
        
        String txt = guessed ? "Round ended! The word was " + currentWord : "Time's up! The word was " + currentWord;
        broadcast("chatMessage", Map.of("from", "System", "text", txt));
        
        currentWord = null;
        roundEndTime = 0;
        sendGameState();

        // Check Session Time
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed >= SESSION_DURATION) {
            endSession();
            return;
        }

        // Delay before next round
        scheduler.schedule(this::startNewRound, 5, TimeUnit.SECONDS);
    }

    private void endSession() {
        gameActive = false;
        if (countdownTask != null) countdownTask.cancel(false);
        if (currentTask != null) currentTask.cancel(false);

        // Calculate Winner
        List<Map.Entry<String, Integer>> leaderboard = new ArrayList<>(scores.entrySet());
        leaderboard.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        String winner = leaderboard.isEmpty() ? "No one" : leaderboard.get(0).getKey();

        broadcast("sessionEnded", Map.of(
            "winner", winner,
            "leaderboard", leaderboard
        ));
        
        broadcast("chatMessage", Map.of("from", "System", "text", "Session Ended. Winner: " + winner));

        // Reset Logic
        scheduler.schedule(() -> {
            scores.replaceAll((k, v) -> 0);
            broadcast("scoreUpdate", Map.of("scores", scores));
        }, 10, TimeUnit.SECONDS);
    }

    public void handleGuess(String socketId, String text) {
        String username = users.get(socketId);
        
        if (socketId.equals(currentDrawerId)) {
            broadcast("chatMessage", Map.of("from", username, "text", text));
            return;
        }

        if (gameActive && currentWord != null && text.trim().equalsIgnoreCase(currentWord)) {
            // Correct Guess
            broadcast("chatMessage", Map.of("from", "System", "text", username + " guessed the word!"));
            
            // Update Scores
            scores.put(username, scores.getOrDefault(username, 0) + 10);
            String drawerName = users.get(currentDrawerId);
            if (drawerName != null) {
                scores.put(drawerName, scores.getOrDefault(drawerName, 0) + 5);
            }
            
            broadcast("scoreUpdate", Map.of("scores", scores));
            endRound(true);
        } else {
            broadcast("chatMessage", Map.of("from", username, "text", text));
        }
    }

    public void sendGameState() {
        long rTime = roundEndTime > 0 ? (roundEndTime - System.currentTimeMillis()) / 1000 : 0;
        long sTime = sessionStartTime > 0 ? (SESSION_DURATION - (System.currentTimeMillis() - sessionStartTime)) / 1000 : 0;
        
        String dName = currentDrawerId != null ? users.get(currentDrawerId) : "";
        
        broadcast("gameState", Map.of(
            "gameActive", gameActive,
            "currentDrawer", dName == null ? "" : dName,
            "timeRemaining", Math.max(0, rTime),
            "sessionTimeRemaining", Math.max(0, sTime)
        ));
    }
    
    public void removeUser(String socketId) {
        String name = users.remove(socketId);
        if (name != null) {
            broadcast("userList", users.values());
            broadcast("chatMessage", Map.of("from", "System", "text", name + " left."));
            
            if (users.isEmpty()) {
                scheduler.shutdownNow(); // Kill room if empty
                Main.removeRoom(roomId);
            } else if (socketId.equals(currentDrawerId)) {
                endRound(false); // Drawer left, skip round
            }
        }
    }
}

// --- MAIN SERVER ---

public class Main {
    private static final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private static final Map<WsContext, String> userRoomMap = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> config.router.mount(router -> {})).start(7070);

        System.out.println("🚀 Scribble Java Server started on port 7070");

        app.ws("/game", ws -> {
            ws.onConnect(ctx -> System.out.println("Connected: " + ctx.sessionId()));

            ws.onMessage(ctx -> {
                Message msg = ctx.messageAsClass(Message.class);
                String rId = msg.roomId;

                if ("joinRoom".equals(msg.type)) {
                    userRoomMap.put(ctx, rId);
                    rooms.computeIfAbsent(rId, GameRoom::new);
                    GameRoom room = rooms.get(rId);
                    
                    room.users.put(ctx.sessionId(), msg.username);
                    room.scores.putIfAbsent(msg.username, 0);

                    // Send initial state
                    room.broadcast("userList", room.users.values());
                    room.broadcast("scoreUpdate", Map.of("scores", room.scores));
                    room.broadcast("chatMessage", Map.of("from", "System", "text", msg.username + " joined."));
                    room.sendGameState();

                    if (room.users.size() >= 2 && !room.gameActive) {
                        room.startGameLoop();
                    }
                }
                
                GameRoom room = rooms.get(rId);
                if (room == null) return;

                if ("stroke".equals(msg.type)) {
                    // Only current drawer can draw
                    if (ctx.sessionId().equals(room.currentDrawerId)) {
                        ctx.send(new Message("remoteStroke", rId, "System", msg.payload));
                        // Forward to others (exclude self)
                        for (WsContext client : userRoomMap.keySet()) {
                            if (userRoomMap.get(client).equals(rId) && !client.sessionId().equals(ctx.sessionId())) {
                                client.send(new Message("remoteStroke", rId, "System", msg.payload));
                            }
                        }
                    }
                } 
                else if ("clear".equals(msg.type)) {
                    if (ctx.sessionId().equals(room.currentDrawerId)) {
                        room.broadcast("clearBoard", null);
                    }
                }
                else if ("guess".equals(msg.type)) {
                    Map map = (Map) msg.payload;
                    String text = (String) map.get("text");
                    room.handleGuess(ctx.sessionId(), text);
                }
            });

            ws.onClose(ctx -> {
                String rId = userRoomMap.remove(ctx);
                if (rId != null && rooms.containsKey(rId)) {
                    rooms.get(rId).removeUser(ctx.sessionId());
                }
            });
        });
    }

    public static void broadcastToRoom(String roomId, Message msg) {
        userRoomMap.forEach((ctx, rId) -> {
            if (rId.equals(roomId) && ctx.session.isOpen()) {
                ctx.send(msg);
            }
        });
    }

    public static void sendToSocket(String socketId, Message msg) {
        userRoomMap.forEach((ctx, rId) -> {
            if (ctx.sessionId().equals(socketId) && ctx.session.isOpen()) {
                ctx.send(msg);
            }
        });
    }
    
    public static void removeRoom(String roomId) {
        rooms.remove(roomId);
        System.out.println("Room " + roomId + " deleted (empty).");
    }
}