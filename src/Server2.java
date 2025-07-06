import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Server2 {
    private static final int PORT = 1234;
    private static final int MAX_CLIENTS = 10;
    private static final int BIDDING_TIMEOUT = 30000; // 30 seconds
    private static final int FINALIZATION_TIMEOUT = 15000; // 15 seconds
    private static final double INITIAL_PURSE = 12000.0;
    private static final int MAX_PLAYERS_PER_TEAM = 25;
    private static final int MAX_NON_INDIAN_PLAYERS = 8;
    private static final double BID_INCREMENT = 10.0; // Minimum bid increment
    
    
    private static Connection connection;
    
    
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final Set<String> readyClients = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> finalizationVotes = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Double> teamPurses = new ConcurrentHashMap<>();
    private static final Map<String, Integer> teamPlayerCounts = new ConcurrentHashMap<>();
    private static final Map<String, Integer> teamNonIndianCounts = new ConcurrentHashMap<>();
    
    
    private static final List<Integer> playerIds = new ArrayList<>();
    private static volatile int currentPlayerIndex = -1;
    private static volatile String currentHighestBidder = null;
    private static volatile double currentHighestBid = 0;
    private static volatile int currentPlayerId = -1;
    private static volatile String currentPlayerName = "";
    private static volatile boolean auctionStarted = false;
    private static volatile boolean auctionFinished = false;
    
    
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static ScheduledFuture<?> biddingTimer;
    private static ScheduledFuture<?> finalizationTimer;
    
   
    private static final Logger logger = Logger.getLogger(Server2.class.getName());
    private static final DecimalFormat currencyFormat = new DecimalFormat("#,##0.00");
    
    static {
        setupLogger();
    }
    
    public static void main(String[] args) {
        logger.info("Starting IPL Auction Server...");
        
        try {
           
            connectToDatabase();
            loadPlayerIds();
            
           
            ServerSocket serverSocket = new ServerSocket(PORT);
            logger.info("Auction Server started on port " + PORT);
            logger.info("Waiting for clients to join... (Max: " + MAX_CLIENTS + ")");
            
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down server...");
                shutdown();
            }));
            
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    
                    if (clients.size() >= MAX_CLIENTS) {
                        logger.warning("Maximum clients reached. Rejecting connection from: " + 
                                     clientSocket.getInetAddress());
                        clientSocket.close();
                        continue;
                    }
                    
                    ClientHandler client = new ClientHandler(clientSocket);
                    executor.submit(client);
                    
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        logger.severe("Error accepting client connection: " + e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            logger.severe("Server startup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void setupLogger() {
        logger.setLevel(Level.INFO);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("[%s] %s: %s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    record.getLevel(),
                    record.getMessage()
                );
            }
        });
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
    }
    
    private static void connectToDatabase() throws SQLException {
        connection = IPLAuctionDB1.setupDatabase(connection);
        logger.info("Connected to database successfully");
    }
    
    private static void loadPlayerIds() throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
            "SELECT id FROM players WHERE status = 'Available' ORDER BY base_price_inr DESC");
        ResultSet rs = ps.executeQuery();
        
        while (rs.next()) {
            playerIds.add(rs.getInt("id"));
        }
        
       
        Collections.shuffle(playerIds);
        logger.info("Loaded " + playerIds.size() + " players for auction");
    }
    
    private static synchronized void attemptAuctionStart() {
        if (clients.size() >= 2 && readyClients.size() == clients.size() && !auctionStarted) {
            auctionStarted = true;
            logger.info("Starting auction with " + clients.size() + " teams");
            broadcast("AUCTION_STARTED");
            
           
            scheduler.schedule(() -> startNextPlayer(), 2, TimeUnit.SECONDS);
        }
    }
    
    private static synchronized void startNextPlayer() {
        if (auctionFinished) return;
        
        
        if (biddingTimer != null) biddingTimer.cancel(false);
        if (finalizationTimer != null) finalizationTimer.cancel(false);
        
        if (++currentPlayerIndex < playerIds.size()) {
            currentPlayerId = playerIds.get(currentPlayerIndex);
            finalizationVotes.clear();
            
            try {
                PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, base_price_inr, type, nationality FROM players WHERE id = ?");
                ps.setInt(1, currentPlayerId);
                ResultSet rs = ps.executeQuery();
                
                if (rs.next()) {
                    currentPlayerName = rs.getString("name");
                    currentHighestBid = rs.getDouble("base_price_inr");
                    currentHighestBidder = null;
                    
                    String playerType = rs.getString("type");
                    String nationality = rs.getString("nationality");
                    
                    String playerInfo = String.format("NEW_PLAYER:%s:%.2f:Type:%s:Nationality:%s", 
                        currentPlayerName, currentHighestBid, playerType, nationality);
                    
                    broadcast(playerInfo);
                    logger.info("Started bidding for: " + currentPlayerName + 
                              " (Base: ₹" + currencyFormat.format(currentHighestBid) + ")");
                    
                    
                    startBiddingTimer();
                }
            } catch (SQLException e) {
                logger.severe("Error fetching player data: " + e.getMessage());
                broadcast("ERROR:Unable to fetch player data");
            }
        } else {
           
            finishAuction();
        }
    }
    
    private static void startBiddingTimer() {
        biddingTimer = scheduler.schedule(() -> {
            logger.info("Bidding timeout reached for: " + currentPlayerName);
            broadcast("BIDDING_TIMEOUT:Moving to finalization");
            startFinalizationTimer();
        }, BIDDING_TIMEOUT, TimeUnit.MILLISECONDS);
    }
    
    private static void startFinalizationTimer() {
        finalizationTimer = scheduler.schedule(() -> {
            logger.info("Finalization timeout reached for: " + currentPlayerName);
            broadcast("FINALIZATION_TIMEOUT:Auto-finalizing");
            autoFinalize();
        }, FINALIZATION_TIMEOUT, TimeUnit.MILLISECONDS);
    }
    
    private static void autoFinalize() {
        if (currentHighestBidder != null) {
            handlePlayerSold(currentHighestBidder, currentHighestBid);
        } else {
            handlePlayerUnsold();
        }
        
        
        scheduler.schedule(() -> startNextPlayer(), 3, TimeUnit.SECONDS);
    }
    
    private static synchronized void handleBid(String teamName, double bid) {
        if (auctionFinished || currentPlayerId == -1) {
            sendToTeam(teamName, "BID_REJECTED:Auction not active");
            return;
        }
        
       
        if (bid < currentHighestBid + BID_INCREMENT) {
            sendToTeam(teamName, "BID_REJECTED:Bid must be at least ₹" + 
                      currencyFormat.format(currentHighestBid + BID_INCREMENT));
            return;
        }
        
        
        Double teamPurse = teamPurses.get(teamName);
        if (teamPurse == null || teamPurse < bid) {
            sendToTeam(teamName, "BID_REJECTED:Insufficient funds (Available: ₹" + 
                      currencyFormat.format(teamPurse != null ? teamPurse : 0) + ")");
            return;
        }
        
        
        if (!validateTeamConstraints(teamName, currentPlayerId)) {
            return;
        }
        
       
        currentHighestBid = bid;
        currentHighestBidder = teamName;
        
        String bidMessage = String.format("NEW_BID:%s:%.2f", teamName, bid);
        broadcast(bidMessage);
        
        logger.info("New bid: ₹" + currencyFormat.format(bid) + " by " + teamName + 
                   " for " + currentPlayerName);
        
        
        if (biddingTimer != null) biddingTimer.cancel(false);
        startBiddingTimer();
    }
    
    private static boolean validateTeamConstraints(String teamName, int playerId) {
        try {
            
            Integer playerCount = teamPlayerCounts.get(teamName);
            if (playerCount != null && playerCount >= MAX_PLAYERS_PER_TEAM) {
                sendToTeam(teamName, "BID_REJECTED:Maximum player limit reached (" + 
                          MAX_PLAYERS_PER_TEAM + ")");
                return false;
            }
            
            
            PreparedStatement ps = connection.prepareStatement(
                "SELECT nationality FROM players WHERE id = ?");
            ps.setInt(1, playerId);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String nationality = rs.getString("nationality");
                if (!nationality.equalsIgnoreCase("India")) {
                    Integer nonIndianCount = teamNonIndianCounts.get(teamName);
                    if (nonIndianCount != null && nonIndianCount >= MAX_NON_INDIAN_PLAYERS) {
                        sendToTeam(teamName, "BID_REJECTED:Maximum non-Indian player limit reached (" + 
                                  MAX_NON_INDIAN_PLAYERS + ")");
                        return false;
                    }
                }
            }
            
            return true;
            
        } catch (SQLException e) {
            logger.severe("Error validating team constraints: " + e.getMessage());
            sendToTeam(teamName, "BID_REJECTED:Database error");
            return false;
        }
    }
    
    private static synchronized void handleFinalize(String teamName) {
        if (auctionFinished || currentPlayerId == -1) {
            sendToTeam(teamName, "FINALIZE_REJECTED:No active auction");
            return;
        }
        
        finalizationVotes.add(teamName);
        logger.info("Finalization vote from: " + teamName + " (" + finalizationVotes.size() + 
                   "/" + clients.size() + ")");
        
        if (finalizationVotes.size() >= Math.ceil(clients.size() * 0.6)) { 
            if (finalizationTimer != null) finalizationTimer.cancel(false);
            
            if (currentHighestBidder != null) {
                handlePlayerSold(currentHighestBidder, currentHighestBid);
            } else {
                handlePlayerUnsold();
            }
            
            
            scheduler.schedule(() -> startNextPlayer(), 3, TimeUnit.SECONDS);
        } else {
            broadcast("FINALIZATION_PROGRESS:" + finalizationVotes.size() + "/" + clients.size());
        }
    }
    
    private static void handlePlayerSold(String buyerTeam, double price) {
        try {
            
            PreparedStatement updatePlayer = connection.prepareStatement(
                "UPDATE players SET status = 'Sold' WHERE id = ?");
            updatePlayer.setInt(1, currentPlayerId);
            updatePlayer.executeUpdate();
            
            
            double newPurse = teamPurses.get(buyerTeam) - price;
            teamPurses.put(buyerTeam, newPurse);
            
            
            teamPlayerCounts.put(buyerTeam, teamPlayerCounts.getOrDefault(buyerTeam, 0) + 1);
            
           
            PreparedStatement playerInfo = connection.prepareStatement(
                "SELECT nationality FROM players WHERE id = ?");
            playerInfo.setInt(1, currentPlayerId);
            ResultSet rs = playerInfo.executeQuery();
            
            if (rs.next() && !rs.getString("nationality").equalsIgnoreCase("India")) {
                teamNonIndianCounts.put(buyerTeam, teamNonIndianCounts.getOrDefault(buyerTeam, 0) + 1);
            }
            
            
            addPlayerToTeam(buyerTeam, currentPlayerId, currentPlayerName, price);
            
            String soldMessage = String.format("PLAYER_SOLD:%s:%.2f:Remaining purse: %.2f", 
                buyerTeam, price, newPurse);
            broadcast(soldMessage);
            
            logger.info(currentPlayerName + " sold to " + buyerTeam + 
                       " for ₹" + currencyFormat.format(price));
            
        } catch (SQLException e) {
            logger.severe("Error handling player sale: " + e.getMessage());
            broadcast("ERROR:Database error during player sale");
        }
    }
    
    private static void handlePlayerUnsold() {
        broadcast("PLAYER_UNSOLD:" + currentPlayerName);
        logger.info(currentPlayerName + " went unsold");
    }
    
    private static void addPlayerToTeam(String teamName, int playerId, String playerName, double price) {
        try {
            String tableName = "team_" + teamName.replaceAll("\\s+", "_").toLowerCase();
            
            
            String createTableSQL = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "player_id INT PRIMARY KEY, " +
                "player_name VARCHAR(100), " +
                "player_type VARCHAR(50), " +
                "nationality VARCHAR(100), " +
                "base_price DECIMAL(10,2), " +
                "bid_amount DECIMAL(10,2), " +
                "purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                tableName);
            connection.createStatement().execute(createTableSQL);
            
            
            PreparedStatement getPlayerInfo = connection.prepareStatement(
                "SELECT type, nationality, base_price_inr FROM players WHERE id = ?");
            getPlayerInfo.setInt(1, playerId);
            ResultSet rs = getPlayerInfo.executeQuery();
            
            if (rs.next()) {
                String playerType = rs.getString("type");
                String nationality = rs.getString("nationality");
                double basePrice = rs.getDouble("base_price_inr");
                
                
                PreparedStatement insertPlayer = connection.prepareStatement(
                    String.format("INSERT INTO %s (player_id, player_name, player_type, nationality, base_price, bid_amount) VALUES (?, ?, ?, ?, ?, ?)", tableName));
                insertPlayer.setInt(1, playerId);
                insertPlayer.setString(2, playerName);
                insertPlayer.setString(3, playerType);
                insertPlayer.setString(4, nationality);
                insertPlayer.setDouble(5, basePrice);
                insertPlayer.setDouble(6, price);
                insertPlayer.executeUpdate();
            }
            
        } catch (SQLException e) {
            logger.severe("Error adding player to team: " + e.getMessage());
        }
    }
    
    private static void finishAuction() {
        auctionFinished = true;
        broadcast("AUCTION_FINISHED");
        logger.info("Auction completed successfully");
        
       
        generateAuctionReport();
        
       
        scheduler.schedule(() -> {
            shutdown();
            System.exit(0);
        }, 30, TimeUnit.SECONDS);
    }
    
    private static void generateAuctionReport() {
        try {
            logger.info("=== AUCTION SUMMARY ===");
            
            for (String teamName : clients.keySet()) {
                String tableName = "team_" + teamName.replaceAll("\\s+", "_").toLowerCase();
                
                PreparedStatement ps = connection.prepareStatement(
                    String.format("SELECT COUNT(*) as player_count, SUM(bid_amount) as total_spent FROM %s", tableName));
                ResultSet rs = ps.executeQuery();
                
                if (rs.next()) {
                    int playerCount = rs.getInt("player_count");
                    double totalSpent = rs.getDouble("total_spent");
                    double remainingPurse = teamPurses.getOrDefault(teamName, 0.0);
                    
                    logger.info(String.format("%s: %d players, ₹%s spent, ₹%s remaining",
                        teamName, playerCount, currencyFormat.format(totalSpent), 
                        currencyFormat.format(remainingPurse)));
                }
            }
            
            // Count unsold players
            PreparedStatement unsoldPs = connection.prepareStatement(
                "SELECT COUNT(*) FROM players WHERE status = 'Available'");
            ResultSet unsoldRs = unsoldPs.executeQuery();
            if (unsoldRs.next()) {
                logger.info("Unsold players: " + unsoldRs.getInt(1));
            }
            
        } catch (SQLException e) {
            logger.severe("Error generating auction report: " + e.getMessage());
        }
    }
    
    private static synchronized void broadcast(String message) {
        for (ClientHandler client : clients.values()) {
            client.sendMessage(message);
        }
    }
    
    private static synchronized void sendToTeam(String teamName, String message) {
        ClientHandler client = clients.get(teamName);
        if (client != null) {
            client.sendMessage(message);
        }
    }
    
    private static void displayTeamPlayers(String teamName) {
        try {
            String tableName = "team_" + teamName.replaceAll("\\s+", "_").toLowerCase();
            String query = String.format(
                "SELECT player_name, player_type, nationality, base_price, bid_amount FROM %s ORDER BY bid_amount DESC", 
                tableName);
            
            PreparedStatement ps = connection.prepareStatement(query);
            ResultSet rs = ps.executeQuery();
            
            StringBuilder teamInfo = new StringBuilder();
            teamInfo.append("=== TEAM ").append(teamName.toUpperCase()).append(" ===\n");
            
            double totalSpent = 0;
            int playerCount = 0;
            
            while (rs.next()) {
                String playerName = rs.getString("player_name");
                String playerType = rs.getString("player_type");
                String nationality = rs.getString("nationality");
                double basePrice = rs.getDouble("base_price");
                double bidAmount = rs.getDouble("bid_amount");
                
                teamInfo.append(String.format("%s (%s, %s) - Base: ₹%s, Bought: ₹%s\n",
                    playerName, playerType, nationality, 
                    currencyFormat.format(basePrice), currencyFormat.format(bidAmount)));
                
                totalSpent += bidAmount;
                playerCount++;
            }
            
            teamInfo.append(String.format("\nTotal Players: %d\n", playerCount));
            teamInfo.append(String.format("Total Spent: ₹%s\n", currencyFormat.format(totalSpent)));
            teamInfo.append(String.format("Remaining Purse: ₹%s\n", 
                currencyFormat.format(teamPurses.getOrDefault(teamName, 0.0))));
            
            sendToTeam(teamName, teamInfo.toString());
            
        } catch (SQLException e) {
            logger.severe("Error displaying team players: " + e.getMessage());
            sendToTeam(teamName, "ERROR:Unable to display team players");
        }
    }
    
    private static void shutdown() {
        logger.info("Shutting down server...");
        
        
        if (biddingTimer != null) biddingTimer.cancel(false);
        if (finalizationTimer != null) finalizationTimer.cancel(false);
        
        
        for (ClientHandler client : clients.values()) {
            client.close();
        }
        
        
        executor.shutdown();
        scheduler.shutdown();
        
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
        
        
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Database connection closed");
            }
        } catch (SQLException e) {
            logger.severe("Error closing database connection: " + e.getMessage());
        }
        
        logger.info("Server shutdown complete");
    }
    
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String teamName;
        private boolean connected = true;
        
        public ClientHandler(Socket socket) {
            this.socket = socket;
        }
        
        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                
                logger.info("Client connected from: " + socket.getInetAddress());
                
                String message;
                while (connected && (message = in.readLine()) != null) {
                    processMessage(message);
                }
                
            } catch (IOException e) {
                if (connected) {
                    logger.warning("Client connection error: " + e.getMessage());
                }
            } finally {
                cleanup();
            }
        }
        
        private void processMessage(String message) {
            try {
                if (message.startsWith("LOGIN:")) {
                    handleLogin(message.substring(6));
                    
                } else if (message.startsWith("BID:")) {
                    if (teamName != null) {
                        double bid = Double.parseDouble(message.substring(4));
                        handleBid(teamName, bid);
                    }
                    
                } else if (message.equals("READY")) {
                    if (teamName != null) {
                        readyClients.add(teamName);
                        logger.info("Team ready: " + teamName);
                        broadcast("TEAM_READY:" + teamName);
                        attemptAuctionStart();
                    }
                    
                } else if (message.equals("FINALIZE_PLAYER")) {
                    if (teamName != null) {
                        handleFinalize(teamName);
                    }
                    
                } else if (message.equals("DISPLAY_TEAMS")) {
                    if (teamName != null) {
                        displayTeamPlayers(teamName);
                    }
                    
                } else if (message.equals("EXIT")) {
                    connected = false;
                    
                } else {
                    logger.warning("Unknown message from " + teamName + ": " + message);
                }
                
            } catch (Exception e) {
                logger.severe("Error processing message: " + e.getMessage());
                sendMessage("ERROR:Message processing failed");
            }
        }
        
        private void handleLogin(String name) {
            if (clients.containsKey(name)) {
                sendMessage("LOGIN_REJECTED:Team name already exists");
                return;
            }
            
            teamName = name;
            clients.put(teamName, this);
            teamPurses.put(teamName, INITIAL_PURSE);
            teamPlayerCounts.put(teamName, 0);
            teamNonIndianCounts.put(teamName, 0);
            
            
            try {
                String tableName = "team_" + teamName.replaceAll("\\s+", "_").toLowerCase();
                String createTableSQL = String.format(
                    "CREATE TABLE IF NOT EXISTS %s (" +
                    "player_id INT PRIMARY KEY, " +
                    "player_name VARCHAR(100), " +
                    "player_type VARCHAR(50), " +
                    "nationality VARCHAR(100), " +
                    "base_price DECIMAL(10,2), " +
                    "bid_amount DECIMAL(10,2), " +
                    "purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                    tableName);
                connection.createStatement().execute(createTableSQL);
                
                
                connection.createStatement().execute("DELETE FROM " + tableName);
                
            } catch (SQLException e) {
                logger.severe("Error creating team table: " + e.getMessage());
            }
            
            sendMessage("LOGIN_SUCCESS:Welcome " + teamName + "!");
            broadcast("TEAM_JOINED:" + teamName);
            logger.info("Team joined: " + teamName + " (Total teams: " + clients.size() + ")");
        }
        
        public void sendMessage(String message) {
            if (out != null && connected) {
                out.println(message);
            }
        }
        
        public void close() {
            connected = false;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.warning("Error closing client socket: " + e.getMessage());
            }
        }
        
        private void cleanup() {
            if (teamName != null) {
                clients.remove(teamName);
                readyClients.remove(teamName);
                finalizationVotes.remove(teamName);
                teamPurses.remove(teamName);
                teamPlayerCounts.remove(teamName);
                teamNonIndianCounts.remove(teamName);
                
                broadcast("TEAM_LEFT:" + teamName);
                logger.info("Team disconnected: " + teamName + " (Remaining: " + clients.size() + ")");
                
                
                if (clients.isEmpty() && auctionStarted) {
                    logger.info("All clients disconnected. Shutting down...");
                    scheduler.schedule(() -> {
                        shutdown();
                        System.exit(0);
                    }, 5, TimeUnit.SECONDS);
                }
            }
            
            close();
        }
        
        public String getTeamName() {
            return teamName;
        }
    }
}

class IPLAuctionDB1 {
    private static final String URL = "jdbc:mysql://localhost:3306/";
    private static final String USER = "root";
    private static final String PASSWORD = "your_password";
    private static final String DB_NAME = "ipl_auction_2025";
    
    public static Connection setupDatabase(Connection connection) {
        try {
            
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
            Statement statement = connection.createStatement();
            
            
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
            statement.executeUpdate("USE " + DB_NAME);
            
            
            String createPlayersTable = """
                CREATE TABLE IF NOT EXISTS players (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    type ENUM('BATTER', 'BOWLER', 'ALL-ROUNDER', 'WICKETKEEPER') NOT NULL,
                    nationality VARCHAR(50) NOT NULL,
                    base_price_inr DECIMAL(10, 2) NOT NULL,
                    status ENUM('Available', 'Sold', 'Unsold') DEFAULT 'Available',
                    age INT,
                    matches_played INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
            """;
            statement.executeUpdate(createPlayersTable);
            
            
            String createAuctionLogTable = """
                CREATE TABLE IF NOT EXISTS auction_log (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    player_id INT,
                    team_name VARCHAR(100),
                    bid_amount DECIMAL(10, 2),
                    action ENUM('BID', 'SOLD', 'UNSOLD') NOT NULL,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (player_id) REFERENCES players(id)
                )
            """;
            statement.executeUpdate(createAuctionLogTable);
            
            
            statement.executeUpdate("DELETE FROM players");
            statement.executeUpdate("DELETE FROM auction_log");
            
            
            String insertData = """
           INSERT INTO players (name, type, nationality, base_price_inr, status) VALUES
                ('Ruturaj Gaikwad', 'Right-hand Batsman', 'India', 200, 'Available'),
                ('Mohsin Khan', 'Left-arm Pace Bowler', 'India', 200, 'Available'),
                ('Shivam Dube', 'Left-hand All-rounder', 'India', 200, 'Available'),
                ('Rashid Khan', 'Right-hand Leg Spin Bowler', 'Afghanistan', 200, 'Available'),
                ('Sandeep Sharma', 'Right-arm Medium Pace Bowler', 'India', 200, 'Available'),
                ('Hardik Pandya', 'Right-hand All-rounder', 'India', 200, 'Available'),
                ('Pat Cummins', 'Right-arm Fast Bowler', 'Australia', 200, 'Available'),
                ('Rinku Singh', 'Left-hand Batsman', 'India', 200, 'Available'),
                ('Mayank Yadav', 'Right-arm Pace Bowler', 'India', 200, 'Available'),
                ('Jasprit Bumrah', 'Right-arm Fast Bowler', 'India', 200, 'Available'),
                ('Kuldeep Yadav', 'Left-arm Chinaman Bowler', 'India', 200, 'Available'),
                ('Abhishek Porel', 'Left-hand Wicket-keeper', 'India', 200, 'Available'),
                ('Sunil Narine', 'Left-hand Mystery Spinner', 'West Indies', 200, 'Available'),
                ('Ravi Bishnoi', 'Right-hand Leg Spin Bowler', 'India', 200, 'Available'),
                ('Travis Head', 'Left-hand Batsman', 'Australia', 200, 'Available'),
                ('Shahrukh Khan', 'Right-hand Batsman', 'India', 200, 'Available'),
                ('Yashaswi Jaiswal', 'Left-hand Batsman', 'India', 200, 'Available'),
                ('Ravindra Jadeja', 'Left-hand All-rounder', 'India', 200, 'Available'),
                ('Andre Russell', 'Right-hand All-rounder', 'West Indies', 200, 'Available'),
                ('Suryakumar Yadav', 'Right-hand Batsman', 'India', 200, 'Available'),
                ('Riyan Parag', 'Right-hand All-rounder', 'India', 200, 'Available'),
                ('Harshit Rana', 'Right-arm Pace Bowler', 'India', 200, 'Available'),
                ('Virat Kohli', 'Right-hand Batsman', 'India', 200, 'Available'),
                ('Ramandeep Singh', 'Right-hand All-rounder', 'India', 200, 'Available'),
                ('Tristan Stubbs', 'Right-hand Batsman', 'South Africa', 200, 'Available'),
                ('Yash Dayal', 'Left-arm Pace Bowler', 'India', 200, 'Available'),
                ('Abhishek Sharma', 'Left-hand All-rounder', 'India', 200, 'Available'),
                ('Nicholas Pooran', 'Left-hand Wicket-keeper', 'West Indies', 200, 'Available'),
                ('Matheesha Pathirana', 'Right-arm Pace Bowler', 'Sri Lanka', 200, 'Available'),
                ('Rohit Sharma', 'Right-hand Batsman', 'India', 200, 'Available'),
                ('Prabhsimran Singh', 'Right-hand Wicket-keeper', 'India', 200, 'Available'),
                ('Jos Buttler', 'WICKETKEEPER', 'England', 200, 'Available'),
                ('Shreyas Iyer', 'BATTER', 'India', 200, 'Available'),
                ('Rishabh Pant', 'BATTER', 'India', 200, 'Available'),
                ('Kagiso Rabada', 'BOWLER', 'South Africa', 200, 'Available'),
                ('Arshdeep Singh', 'BOWLER', 'India', 200, 'Available'),
                ('Mitchell Starc', 'BOWLER', 'Australia', 200, 'Available'),
                ('Yuzvendra Chahal', 'BOWLER', 'India', 200, 'Available'),
                ('Liam Livingstone', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('David Miller', 'BATTER', 'South Africa', 150, 'Available'),
                ('KL Rahul', 'WICKETKEEPER', 'India', 200, 'Available'),
                ('Mohammad Shami', 'BOWLER', 'India', 200, 'Available'),
                ('Mohammad Siraj', 'BOWLER', 'India', 200, 'Available'),
                ('Harry Brook', 'BATTER', 'England', 200, 'Available'),
                ('Devon Conway', 'BATTER', 'New Zealand', 200, 'Available'),
                ('Jake Fraser-Mcgurk', 'BATTER', 'Australia', 200, 'Available'),
                ('Aiden Markram', 'BATTER', 'South Africa', 200, 'Available'),
                ('Devdutt Padikkal', 'BATTER', 'India', 200, 'Available'),
                ('Rahul Tripathi', 'BATTER', 'India', 75, 'Available'),
                ('David Warner', 'BATTER', 'Australia', 200, 'Available'),
                ('Ravichandaran Ashwin', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('Venkatesh Iyer', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('Mitchell Marsh', 'ALL-ROUNDER', 'Australia', 200, 'Available'),
                ('Glenn Maxwell', 'ALL-ROUNDER', 'Australia', 200, 'Available'),
                ('Harshal Patel', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('Rachin Ravindra', 'ALL-ROUNDER', 'New Zealand', 150, 'Available'),
                ('Marcus Stoinis', 'ALL-ROUNDER', 'Australia', 200, 'Available'),
                ('Jonny Bairstow', 'WICKETKEEPER', 'England', 200, 'Available'),
                ('Quinton De Kock', 'WICKETKEEPER', 'South Africa', 200, 'Available'),
                ('Rahmanullah Gurbaz', 'WICKETKEEPER', 'Afghanistan', 200, 'Available'),
                ('Ishan Kishan', 'WICKETKEEPER', 'India', 200, 'Available'),
                ('Phil Salt', 'WICKETKEEPER', 'England', 200, 'Available'),
                ('Jitesh Sharma', 'WICKETKEEPER', 'India', 100, 'Available'),
                ('Syed Khaleel Ahmed', 'BOWLER', 'India', 200, 'Available'),
                ('Trent Boult', 'BOWLER', 'New Zealand', 200, 'Available'),
                ('Josh Hazlewood', 'BOWLER', 'Australia', 200, 'Available'),
                ('Avesh Khan', 'BOWLER', 'India', 200, 'Available'),
                ('Prasidh Krishna', 'BOWLER', 'India', 200, 'Available'),
                ('T. Natarajan', 'BOWLER', 'India', 200, 'Available'),
                ('Anrich Nortje', 'BOWLER', 'South Africa', 200, 'Available'),
                ('Noor Ahmad', 'BOWLER', 'Afghanistan', 200, 'Available'),
                ('Rahul Chahar', 'BOWLER', 'India', 100, 'Available'),
                ('Wanindu Hasaranga', 'BOWLER', 'Sri Lanka', 200, 'Available'),
                ('Waqar Salamkheil', 'BOWLER', 'Afghanistan', 75, 'Available'),
                ('Maheesh Theekshana', 'BOWLER', 'Sri Lanka', 200, 'Available'),
                ('Adam Zampa', 'BOWLER', 'Australia', 200, 'Available'),
                ('Yash Dhull', 'BATTER', 'India', 30, 'Available'),
                ('Abhinav Manohar', 'BATTER', 'India', 30, 'Available'),
                ('Karun Nair', 'BATTER', 'India', 30, 'Available'),
                ('Angkrish Raghuvanshi', 'BATTER', 'India', 30, 'Available'),
                ('Anmolpreet Singh', 'BATTER', 'India', 30, 'Available'),
                ('Atharva Taide', 'BATTER', 'India', 30, 'Available'),
                ('Nehal Wadhera', 'BATTER', 'India', 30, 'Available'),
                ('Harpreet Brar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Naman Dhir', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mahipal Lomror', 'ALL-ROUNDER', 'India', 50, 'Available'),
                ('Sameer Rizvi', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Abdul Samad', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vijay Shankar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ashutosh Sharma', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Nishant Sindhu', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Utkarsh Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Aryan Juyal', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Kumar Kushagra', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Robin Minz', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Anuj Rawat', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Luvnith Sisodia', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Vishnu Vinod', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Upendra Singh Yadav', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Vaibhav Arora', 'BOWLER', 'India', 30, 'Available'),
                ('Rasikh Dar', 'BOWLER', 'India', 30, 'Available'),
                ('Akash Madhwal', 'BOWLER', 'India', 30, 'Available'),
                ('Mohit Sharma', 'BOWLER', 'India', 50, 'Available'),
                ('Simarjeet Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Yash Thakur', 'BOWLER', 'India', 30, 'Available'),
                ('Kartik Tyagi', 'BOWLER', 'India', 40, 'Available'),
                ('Vyshak Vijaykumar', 'BOWLER', 'India', 30, 'Available'),
                ('Piyush Chawla', 'BOWLER', 'India', 50, 'Available'),
                ('Shreyas Gopal', 'BOWLER', 'India', 30, 'Available'),
                ('Mayank Markande', 'BOWLER', 'India', 30, 'Available'),
                ('Suyash Sharma', 'BOWLER', 'India', 30, 'Available'),
                ('Karn Sharma', 'BOWLER', 'India', 50, 'Available'),
                ('Kumar Kartikeya Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Manav Suthar', 'BOWLER', 'India', 30, 'Available'),
                ('Mayank Agarawal', 'BATTER', 'India', 100, 'Available'),
                ('Faf Du Plessis', 'BATTER', 'South Africa', 200, 'Available'),
                ('Glenn Phillips', 'BATTER', 'New Zealand', 200, 'Available'),
                ('Rovman Powell', 'BATTER', 'West Indies', 150, 'Available'),
                ('Ajinkya Rahane', 'BATTER', 'India', 150, 'Available'),
                ('Prithvi Shaw', 'BATTER', 'India', 75, 'Available'),
                ('Kane Williamson', 'BATTER', 'New Zealand', 200, 'Available'),
                ('Sam Curran', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('Marco Jansen', 'ALL-ROUNDER', 'South Africa', 125, 'Available'),
                ('Daryl Mitchell', 'ALL-ROUNDER', 'New Zealand', 200, 'Available'),
                ('Krunal Pandya', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('Nitish Rana', 'ALL-ROUNDER', 'India', 150, 'Available'),
                ('Washington Sundar', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('Shardul Thakur', 'ALL-ROUNDER', 'India', 200, 'Available'),
                ('K.S Bharat', 'WICKETKEEPER', 'India', 75, 'Available'),
                ('Alex Carey', 'WICKETKEEPER', 'Australia', 100, 'Available'),
                ('Donovan Ferreira', 'WICKETKEEPER', 'South Africa', 75, 'Available'),
                ('Shai Hope', 'WICKETKEEPER', 'West Indies', 125, 'Available'),
                ('Josh Inglis', 'WICKETKEEPER', 'Australia', 200, 'Available'),
                ('Ryan Rickelton', 'WICKETKEEPER', 'South Africa', 100, 'Available'),
                ('Deepak Chahar', 'BOWLER', 'India', 200, 'Available'),
                ('Gerald Coetzee', 'BOWLER', 'South Africa', 125, 'Available'),
                ('Akash Deep', 'BOWLER', 'India', 100, 'Available'),
                ('Tushar Deshpande', 'BOWLER', 'India', 100, 'Available'),
                ('Lockie Ferguson', 'BOWLER', 'New Zealand', 200, 'Available'),
                ('Bhuvneshwar Kumar', 'BOWLER', 'India', 200, 'Available'),
                ('Mukesh Kumar', 'BOWLER', 'India', 200, 'Available'),
                ('Allah Ghazanfar', 'BOWLER', 'Afghanistan', 75, 'Available'),
                ('Akeal Hosein', 'BOWLER', 'West Indies', 150, 'Available'),
                ('Keshav Maharaj', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Mujeeb Ur Rahman', 'BOWLER', 'Afghanistan', 200, 'Available'),
                ('Adil Rashid', 'BOWLER', 'England', 200, 'Available'),
                ('Vijayakanth Viyaskanth', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Ricky Bhui', 'BATTER', 'India', 30, 'Available'),
                ('Swastik Chhikara', 'BATTER', 'India', 30, 'Available'),
                ('Aarya Desai', 'BATTER', 'India', 30, 'Available'),
                ('Shubham Dubey', 'BATTER', 'India', 30, 'Available'),
                ('Madhav Kaushik', 'BATTER', 'India', 30, 'Available'),
                ('Pukhraj Mann', 'BATTER', 'India', 30, 'Available'),
                ('Shaik Rasheed', 'BATTER', 'India', 30, 'Available'),
                ('Himmat Singh', 'BATTER', 'India', 30, 'Available'),
                ('Mayank Dagar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Anshul Kamboj', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mohd. Arshad Khan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Darshan Nalkande', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Suyash Prabhudessai', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Anukul Roy', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Swapnil Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sanvir Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Avanish Aravelly', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Vansh Bedi', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Saurav Chauhan', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Harvik Desai', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Tom Kohler-Cadmore', 'WICKETKEEPER', 'England', 50, 'Available'),
                ('Kunal Rathore', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('B.R Sharath', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Gurnoor Singh Brar', 'BOWLER', 'India', 30, 'Available'),
                ('Mukesh Choudhary', 'BOWLER', 'India', 30, 'Available'),
                ('Sakib Hussain', 'BOWLER', 'India', 30, 'Available'),
                ('Vidwath Kaverappa', 'BOWLER', 'India', 30, 'Available'),
                ('Rajan Kumar', 'BOWLER', 'India', 30, 'Available'),
                ('Sushant Mishra', 'BOWLER', 'India', 30, 'Available'),
                ('Arjun Tendulkar', 'BOWLER', 'India', 30, 'Available'),
                ('Zeeshan Ansari', 'BOWLER', 'India', 30, 'Available'),
                ('Prince Choudhary', 'BOWLER', 'India', 30, 'Available'),
                ('Himanshu Sharma', 'BOWLER', 'India', 30, 'Available'),
                ('M. Siddharth', 'BOWLER', 'India', 30, 'Available'),
                ('Digvesh Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Prashant Solanki', 'BOWLER', 'India', 30, 'Available'),
                ('Jhathavedh Subramanyan', 'BOWLER', 'India', 30, 'Available'),
                ('Finn Allen', 'BATTER', 'New Zealand', 200, 'Available'),
                ('Dewald Brevis', 'BATTER', 'South Africa', 75, 'Available'),
                ('Ben Duckett', 'BATTER', 'England', 200, 'Available'),
                ('Manish Pandey', 'BATTER', 'India', 75, 'Available'),
                ('Rilee Rossouw', 'BATTER', 'South Africa', 200, 'Available'),
                ('Sherfane Rutherford', 'BATTER', 'West Indies', 150, 'Available'),
                ('Ashton Turner', 'BATTER', 'Australia', 100, 'Available'),
                ('James Vince', 'BATTER', 'England', 200, 'Available'),
                ('Shahbaz Ahamad', 'ALL-ROUNDER', 'India', 100, 'Available'),
                ('Moeen Ali', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('Tim David', 'ALL-ROUNDER', 'Australia', 200, 'Available'),
                ('Deepak Hooda', 'ALL-ROUNDER', 'India', 75, 'Available'),
                ('Will Jacks', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('Azmatullah Omarzai', 'ALL-ROUNDER', 'Afghanistan', 150, 'Available'),
                ('R. Sai Kishore', 'ALL-ROUNDER', 'India', 75, 'Available'),
                ('Romario Shepherd', 'ALL-ROUNDER', 'West Indies', 150, 'Available'),
                ('Tom Banton', 'WICKETKEEPER', 'England', 200, 'Available'),
                ('Sam Billings', 'WICKETKEEPER', 'England', 150, 'Available'),
                ('Jordan Cox', 'WICKETKEEPER', 'England', 125, 'Available'),
                ('Ben McDermott', 'WICKETKEEPER', 'Australia', 75, 'Available'),
                ('Kusal Mendis', 'WICKETKEEPER', 'Sri Lanka', 75, 'Available'),
                ('Kusal Perera', 'WICKETKEEPER', 'Sri Lanka', 75, 'Available'),
                ('Josh Philippe', 'WICKETKEEPER', 'Australia', 75, 'Available'),
                ('Tim Seifert', 'WICKETKEEPER', 'New Zealand', 125, 'Available'),
                ('Nandre Burger', 'BOWLER', 'South Africa', 125, 'Available'),
                ('Spencer Johnson', 'BOWLER', 'Australia', 200, 'Available'),
                ('Umran Malik', 'BOWLER', 'India', 75, 'Available'),
                ('Mustafizur Rahman', 'BOWLER', 'Bangladesh', 200, 'Available'),
                ('Ishant Sharma', 'BOWLER', 'India', 75, 'Available'),
                ('Nuwan Thushara', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Naveen Ul Haq', 'BOWLER', 'Afghanistan', 200, 'Available'),
                ('Jaydev Unadkat', 'BOWLER', 'India', 100, 'Available'),
                ('Umesh Yadav', 'BOWLER', 'India', 200, 'Available'),
                ('Rishad Hossain', 'BOWLER', 'Bangladesh', 75, 'Available'),
                ('Zahir Khan Pakten', 'BOWLER', 'Afghanistan', 75, 'Available'),
                ('Nqabayomzi Peter', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Tanveer Sangha', 'BOWLER', 'Australia', 75, 'Available'),
                ('Tabraiz Shamsi', 'BOWLER', 'South Africa', 200, 'Available'),
                ('Jeffery Vandersay', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Sachin Baby', 'BATTER', 'India', 30, 'Available'),
                ('Priyam Garg', 'BATTER', 'India', 30, 'Available'),
                ('Harnoor Pannu', 'BATTER', 'India', 30, 'Available'),
                ('Smaran Ravichandran', 'BATTER', 'India', 30, 'Available'),
                ('Shashwat Rawat', 'BATTER', 'India', 30, 'Available'),
                ('Andre Siddarth', 'BATTER', 'India', 30, 'Available'),
                ('Avneesh Sudha', 'BATTER', 'India', 30, 'Available'),
                ('Apoorv Wankhade', 'BATTER', 'India', 30, 'Available'),
                ('Yudhvir Charak', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Rishi Dhawan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Rajvardhan Hangargekar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Tanush Kotian', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Arshin Kulkarni', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shams Mulani', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shivam Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Lalit Yadav', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mohammed Azharuddeen', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('L.R Chethan', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Aryaman Singh Dhaliwal', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Urvil Patel', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Sanskar Rawat', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Bipin Saurabh', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Tanay Thyagarajann', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Money Grewal', 'BOWLER', 'India', 30, 'Available'),
                ('Ashwani Kumar', 'BOWLER', 'India', 30, 'Available'),
                ('Ishan Porel', 'BOWLER', 'India', 30, 'Available'),
                ('Abhilash Shetty', 'BOWLER', 'India', 30, 'Available'),
                ('Akash Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Gurjapneet Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Basil Thampi', 'BOWLER', 'India', 30, 'Available'),
                ('Murugan Ashwin', 'BOWLER', 'India', 30, 'Available'),
                ('Shreyas Chavan', 'BOWLER', 'India', 30, 'Available'),
                ('Chintal Gandhi', 'BOWLER', 'India', 30, 'Available'),
                ('Raghav Goyal', 'BOWLER', 'India', 30, 'Available'),
                ('Jagadeesha Suchith', 'BOWLER', 'India', 30, 'Available'),
                ('Roshan Waghsare', 'BOWLER', 'India', 30, 'Available'),
                ('Bailapudi Yeswanth', 'BOWLER', 'India', 30, 'Available'),
                ('Sediqullah Atal', 'BATTER', 'Afghanistan', 75, 'Available'),
                ('Matthew Breetzke', 'BATTER', 'South Africa', 75, 'Available'),
                ('Mark Chapman', 'BATTER', 'New Zealand', 150, 'Available'),
                ('Brandon King', 'BATTER', 'West Indies', 75, 'Available'),
                ('Evin Lewis', 'BATTER', 'West Indies', 200, 'Available'),
                ('Pathum Nissanka', 'BATTER', 'Sri Lanka', 75, 'Available'),
                ('Bhanuka Rajapaksa', 'BATTER', 'Sri Lanka', 75, 'Available'),
                ('Steve Smith', 'BATTER', 'Australia', 200, 'Available'),
                ('Gus Atkinson', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('Tom Curran', 'ALL-ROUNDER', 'England', 200, 'Available'),
                ('Krishnappa Gowtham', 'ALL-ROUNDER', 'India', 100, 'Available'),
                ('Mohammad Nabi', 'ALL-ROUNDER', 'Afghanistan', 150, 'Available'),
                ('Gulbadin Naib', 'ALL-ROUNDER', 'Afghanistan', 100, 'Available'),
                ('Sikandar Raza', 'ALL-ROUNDER', 'Zimbabwe', 125, 'Available'),
                ('Mitchell Santner', 'ALL-ROUNDER', 'New Zealand', 200, 'Available'),
                ('Jayant Yadav', 'ALL-ROUNDER', 'India', 75, 'Available'),
                ('Johnson Charles', 'WICKETKEEPER', 'West Indies', 75, 'Available'),
                ('Litton Das', 'WICKETKEEPER', 'Bangladesh', 75, 'Available'),
                ('Andre Fletcher', 'WICKETKEEPER', 'West Indies', 75, 'Available'),
                ('Tom Latham', 'WICKETKEEPER', 'New Zealand', 150, 'Available'),
                ('Ollie Pope', 'WICKETKEEPER', 'England', 75, 'Available'),
                ('Kyle Verreynne', 'WICKETKEEPER', 'South Africa', 75, 'Available'),
                ('Fazalhaq Farooqi', 'BOWLER', 'Afghanistan', 200, 'Available'),
                ('Richard Gleeson', 'BOWLER', 'England', 75, 'Available'),
                ('Matt Henry', 'BOWLER', 'New Zealand', 200, 'Available'),
                ('Alzarri Joseph', 'BOWLER', 'West Indies', 200, 'Available'),
                ('Kwena Maphaka', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Kuldeep Sen', 'BOWLER', 'India', 75, 'Available'),
                ('Reece Topley', 'BOWLER', 'England', 75, 'Available'),
                ('Lizaad Williams', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Luke Wood', 'BOWLER', 'England', 75, 'Available'),
                ('Sachin Dhas', 'BATTER', 'India', 30, 'Available'),
                ('Leus Du Plooy', 'BATTER', 'England', 50, 'Available'),
                ('Ashwin Hebbar', 'BATTER', 'India', 30, 'Available'),
                ('Rohan Kunnummal', 'BATTER', 'India', 30, 'Available'),
                ('Ayush Pandey', 'BATTER', 'India', 30, 'Available'),
                ('Akshat Raghuwanshi', 'BATTER', 'India', 30, 'Available'),
                ('Shoun Roger', 'BATTER', 'India', 40, 'Available'),
                ('Virat Singh', 'BATTER', 'India', 30, 'Available'),
                ('Priyansh Arya', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Manoj Bhandage', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Pravin Dubey', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ajay Mandal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Prerak Mankad', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vipraj Nigam', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vicky Ostwal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shivalik Sharma', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Salil Arora', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Dinesh Bana', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Ajitesh Guruswamy', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Narayan Jagadeesan', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Shrijith Krishnan', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Michael Pepper', 'WICKETKEEPER', 'England', 50, 'Available'),
                ('Vishnu Solanki', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('K.M Asif', 'BOWLER', 'India', 30, 'Available'),
                ('Akhil Chaudhary', 'BOWLER', 'India', 30, 'Available'),
                ('Himanshu Chauhan', 'BOWLER', 'India', 30, 'Available'),
                ('Arpit Guleria', 'BOWLER', 'India', 30, 'Available'),
                ('Nishanth Saranu', 'BOWLER', 'India', 30, 'Available'),
                ('Kuldip Yadav', 'BOWLER', 'India', 30, 'Available'),
                ('Prithviraj Yarra', 'BOWLER', 'India', 30, 'Available'),
                ('Shubham Agrawal', 'BOWLER', 'India', 30, 'Available'),
                ('Jass Inder Baidwan', 'BOWLER', 'India', 30, 'Available'),
                ('Jasmer Dhankhar', 'BOWLER', 'India', 30, 'Available'),
                ('Pulkit Narang', 'BOWLER', 'India', 30, 'Available'),
                ('Saumy Pandey', 'BOWLER', 'India', 30, 'Available'),
                ('Mohit Rathee', 'BOWLER', 'India', 30, 'Available'),
                ('Himanshu Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Towhid Hridoy', 'BATTER', 'Bangladesh', 75, 'Available'),
                ('Mikyle Louis', 'BATTER', 'West Indies', 75, 'Available'),
                ('Harry Tector', 'BATTER', 'Ireland', 75, 'Available'),
                ('Rassie Van Der Dussen', 'BATTER', 'South Africa', 200, 'Available'),
                ('Will Young', 'BATTER', 'New Zealand', 125, 'Available'),
                ('Najibullah Zadran', 'BATTER', 'Afghanistan', 75, 'Available'),
                ('Ibrahim Zadran', 'BATTER', 'Afghanistan', 75, 'Available'),
                ('Sean Abbott', 'ALL-ROUNDER', 'Australia', 200, 'Available'),
                ('Jacob Bethell', 'ALL-ROUNDER', 'England', 125, 'Available'),
                ('Brydon Carse', 'ALL-ROUNDER', 'England', 100, 'Available'),
                ('Aaron Hardie', 'ALL-ROUNDER', 'Australia', 125, 'Available'),
                ('Sarfaraz Khan', 'ALL-ROUNDER', 'India', 75, 'Available'),
                ('Kyle Mayers', 'ALL-ROUNDER', 'West Indies', 150, 'Available'),
                ('Kamindu Mendis', 'ALL-ROUNDER', 'Sri Lanka', 75, 'Available'),
                ('Matthew Short', 'ALL-ROUNDER', 'Australia', 75, 'Available'),
                ('Jason Behrendorff', 'BOWLER', 'Australia', 150, 'Available'),
                ('Dushmantha Chameera', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Nathan Ellis', 'BOWLER', 'Australia', 125, 'Available'),
                ('Shamar Joseph', 'BOWLER', 'West Indies', 75, 'Available'),
                ('Josh Little', 'BOWLER', 'Ireland', 75, 'Available'),
                ('Shivam Mavi', 'BOWLER', 'India', 75, 'Available'),
                ('Jhye Richardson', 'BOWLER', 'Australia', 150, 'Available'),
                ('Navdeep Saini', 'BOWLER', 'India', 75, 'Available'),
                ('Tanmay Agarwal', 'BATTER', 'India', 30, 'Available'),
                ('Amandeep Khare', 'BATTER', 'India', 30, 'Available'),
                ('Ayush Mhatre', 'BATTER', 'India', 30, 'Available'),
                ('Salman Nizar', 'BATTER', 'India', 30, 'Available'),
                ('Aniket Verma', 'BATTER', 'India', 30, 'Available'),
                ('Sumeet Verma', 'BATTER', 'India', 30, 'Available'),
                ('Manan Vohra', 'BATTER', 'India', 30, 'Available'),
                ('Samarth Vyas', 'BATTER', 'India', 30, 'Available'),
                ('Raj Angad Bawa', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Emanjot Chahal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Musheer Khan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Manvanth Kumar L', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mayank Rawat', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Suryansh Shedge', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Hritik Shokeen', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sonu Yadav', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('S. Rithik Easwaran', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Anmol Malhotra', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Pradosh Paul', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Karteek Sharma', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Akash Singh', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Tejasvi Singh', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Siddharth Yadav', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Saurabh Dubey', 'BOWLER', 'India', 30, 'Available'),
                ('Aaqib Khan', 'BOWLER', 'India', 30, 'Available'),
                ('Kulwant Khejroliya', 'BOWLER', 'India', 30, 'Available'),
                ('Ankit Singh Rajpoot', 'BOWLER', 'India', 30, 'Available'),
                ('Divesh Sharma', 'BOWLER', 'India', 30, 'Available'),
                ('Naman Tiwari', 'BOWLER', 'India', 30, 'Available'),
                ('Prince Yadav', 'BOWLER', 'India', 30, 'Available'),
                ('Kunal Singh Chibb', 'BOWLER', 'India', 30, 'Available'),
                ('Yuvraj Chudasama', 'BOWLER', 'India', 30, 'Available'),
                ('Deepak Devadiga', 'BOWLER', 'India', 30, 'Available'),
                ('Ramesh Prasad', 'BOWLER', 'India', 30, 'Available'),
                ('Shivam Shukla', 'BOWLER', 'India', 30, 'Available'),
                ('Himanshu Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Tejpreet Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Qais Ahmad', 'ALL-ROUNDER', 'Afghanistan', 75, 'Available'),
                ('Charith Asalanka', 'ALL-ROUNDER', 'Sri Lanka', 75, 'Available'),
                ('Michael Bracewell', 'ALL-ROUNDER', 'New Zealand', 150, 'Available'),
                ('Gudakesh Motie', 'ALL-ROUNDER', 'West Indies', 75, 'Available'),
                ('Daniel Mousley', 'ALL-ROUNDER', 'England', 75, 'Available'),
                ('Jamie Overton', 'ALL-ROUNDER', 'England', 150, 'Available'),
                ('Dunith Wellalage', 'ALL-ROUNDER', 'Sri Lanka', 75, 'Available'),
                ('Ottneil Baartman', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Xavier Bartlett', 'BOWLER', 'Australia', 75, 'Available'),
                ('Dilshan Madushanka', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Adam Milne', 'BOWLER', 'New Zealand', 200, 'Available'),
                ('Lungisani Ngidi', 'BOWLER', 'South Africa', 100, 'Available'),
                ('William Rourke', 'BOWLER', 'New Zealand', 150, 'Available'),
                ('Chetan Sakariya', 'BOWLER', 'India', 75, 'Available'),
                ('Sandeep Warrier', 'BOWLER', 'India', 75, 'Available'),
                ('Musaif Ajaz', 'BATTER', 'India', 30, 'Available'),
                ('Agni Chopra', 'BATTER', 'India', 30, 'Available'),
                ('Abhimanyu Easwaran', 'BATTER', 'India', 30, 'Available'),
                ('Sudip Gharami', 'BATTER', 'India', 30, 'Available'),
                ('Shubham Khajuria', 'BATTER', 'India', 30, 'Available'),
                ('Akhil Rawat', 'BATTER', 'India', 30, 'Available'),
                ('Prateek Yadav', 'BATTER', 'India', 30, 'Available'),
                ('Abdul Bazith', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('K.C Cariappa', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Yuvraj Chaudhary', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Aman Khan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sumit Kumar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Kamlesh Nagarkoti', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Hardik Raj', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Harsh Tyagi', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('M. Ajnas', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Unmukt Chand', 'WICKETKEEPER', 'USA', 30, 'Available'),
                ('Tejasvi Dahiya', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Sumit Ghadigaonkar', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Baba Indrajith', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Muhammed Khan', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Bhagmender Lather', 'WICKETKEEPER', 'India', 30, 'Available'),
                ('Baltej Dhanda', 'BOWLER', 'India', 30, 'Available'),
                ('Ali Khan', 'BOWLER', 'USA', 30, 'Available'),
                ('Ravi Kumar', 'BOWLER', 'India', 30, 'Available'),
                ('Vineet Panwar', 'BOWLER', 'India', 30, 'Available'),
                ('Vidyadhar Patil', 'BOWLER', 'India', 30, 'Available'),
                ('Aradhya Shukla', 'BOWLER', 'India', 30, 'Available'),
                ('Abhinandan Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Cooper Connolly', 'ALL-ROUNDER', 'Australia', 75, 'Available'),
                ('Dushan Hemantha', 'ALL-ROUNDER', 'Sri Lanka', 75, 'Available'),
                ('Jason Holder', 'ALL-ROUNDER', 'West Indies', 200, 'Available'),
                ('Karim Janat', 'ALL-ROUNDER', 'Afghanistan', 75, 'Available'),
                ('Jimmy Neesham', 'ALL-ROUNDER', 'New Zealand', 150, 'Available'),
                ('Daniel Sams', 'ALL-ROUNDER', 'Australia', 150, 'Available'),
                ('William Sutherland', 'ALL-ROUNDER', 'Australia', 75, 'Available'),
                ('Taskin Ahmed', 'BOWLER', 'Bangladesh', 100, 'Available'),
                ('Ben Dwarshuis', 'BOWLER', 'Australia', 75, 'Available'),
                ('Obed McCoy', 'BOWLER', 'West Indies', 125, 'Available'),
                ('Riley Meredith', 'BOWLER', 'Australia', 150, 'Available'),
                ('Lance Morris', 'BOWLER', 'Australia', 125, 'Available'),
                ('Olly Stone', 'BOWLER', 'England', 75, 'Available'),
                ('Daniel Worrall', 'BOWLER', 'England', 150, 'Available'),
                ('Pyla Avinash', 'BATTER', 'India', 30, 'Available'),
                ('Kiran Chormale', 'BATTER', 'India', 30, 'Available'),
                ('Ashish Dahariya', 'BATTER', 'India', 30, 'Available'),
                ('Tushar Raheja', 'BATTER', 'India', 30, 'Available'),
                ('Sarthak Ranjan', 'BATTER', 'India', 30, 'Available'),
                ('Abhijeet Tomar', 'BATTER', 'India', 30, 'Available'),
                ('Krish Bhagat', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sohraab Dhaliwal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Harsh Dubey', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ramakrishna Ghosh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Raj Limbani', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ninad Rathva', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vivrant Sharma', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shiva Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sayed Irfan Aftab', 'BOWLER', 'India', 30, 'Available'),
                ('Anirudh Chowdhary', 'BOWLER', 'India', 30, 'Available'),
                ('Anshuman Hooda', 'BOWLER', 'India', 30, 'Available'),
                ('Siddharth Kaul', 'BOWLER', 'India', 40, 'Available'),
                ('Prashant Sai Painkra', 'BOWLER', 'India', 30, 'Available'),
                ('Venkata Satyanarayana Penmetsa', 'BOWLER', 'India', 30, 'Available'),
                ('Yeddala Reddy', 'BOWLER', 'India', 30, 'Available'),
                ('Zak Foulkes', 'ALL-ROUNDER', 'New Zealand', 75, 'Available'),
                ('Chris Green', 'ALL-ROUNDER', 'Australia', 100, 'Available'),
                ('Shakib Al Hasan', 'ALL-ROUNDER', 'Bangladesh', 100, 'Available'),
                ('Mehidy Hasan Miraz', 'ALL-ROUNDER', 'Bangladesh', 100, 'Available'),
                ('Wiaan Mulder', 'ALL-ROUNDER', 'South Africa', 75, 'Available'),
                ('Dwaine Pretorius', 'ALL-ROUNDER', 'South Africa', 75, 'Available'),
                ('Dasun Shanaka', 'ALL-ROUNDER', 'Sri Lanka', 75, 'Available'),
                ('Shoriful Islam', 'BOWLER', 'Bangladesh', 75, 'Available'),
                ('Blessing Muzarabani', 'BOWLER', 'Zimbabwe', 75, 'Available'),
                ('Matthew Potts', 'BOWLER', 'England', 150, 'Available'),
                ('Tanzim Hasan Sakib', 'BOWLER', 'Bangladesh', 75, 'Available'),
                ('Benjamin Sears', 'BOWLER', 'New Zealand', 100, 'Available'),
                ('Tim Southee', 'BOWLER', 'New Zealand', 150, 'Available'),
                ('John Turner', 'BOWLER', 'England', 150, 'Available'),
                ('Joshua Brown', 'BATTER', 'Australia', 30, 'Available'),
                ('Oliver Davies', 'BATTER', 'Australia', 30, 'Available'),
                ('Bevan John Jacobs', 'BATTER', 'New Zealand', 30, 'Available'),
                ('Atharva Kale', 'BATTER', 'India', 30, 'Available'),
                ('Abhishek Nair', 'BATTER', 'India', 30, 'Available'),
                ('Vishwanath Pratap Singh', 'BATTER', 'India', 30, 'Available'),
                ('Nasir Lone', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Brandon McMullen', 'ALL-ROUNDER', 'Scotland', 30, 'Available'),
                ('S. Midhun', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Abid Mushtaq', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mahesh Pithiya', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Maramreddy Reddy', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Atit Sheth', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Jonty Sidhu', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mohit Avasthi', 'BOWLER', 'India', 30, 'Available'),
                ('Faridoon Dawoodzai', 'BOWLER', 'Afghanistan', 30, 'Available'),
                ('Praful Hinge', 'BOWLER', 'India', 30, 'Available'),
                ('Pankaj Jaswal', 'BOWLER', 'India', 30, 'Available'),
                ('Vijay Kumar', 'BOWLER', 'India', 30, 'Available'),
                ('Ashok Sharma', 'BOWLER', 'India', 30, 'Available'),
                ('Mujtaba Yousuf', 'BOWLER', 'India', 30, 'Available'),
                ('Ashton Agar', 'ALL-ROUNDER', 'Australia', 125, 'Available'),
                ('Roston Chase', 'ALL-ROUNDER', 'West Indies', 75, 'Available'),
                ('Junior Dala', 'ALL-ROUNDER', 'South Africa', 75, 'Available'),
                ('Mahedi Hasan', 'ALL-ROUNDER', 'Bangladesh', 75, 'Available'),
                ('Nangeyalia Kharote', 'ALL-ROUNDER', 'Afghanistan', 75, 'Available'),
                ('Dan Lawrence', 'ALL-ROUNDER', 'England', 100, 'Available'),
                ('Nathan Smith', 'ALL-ROUNDER', 'New Zealand', 100, 'Available'),
                ('James Anderson', 'BOWLER', 'England', 125, 'Available'),
                ('Kyle Jamieson', 'BOWLER', 'New Zealand', 150, 'Available'),
                ('Chris Jordan', 'BOWLER', 'England', 200, 'Available'),
                ('Hasan Mahmud', 'BOWLER', 'Bangladesh', 75, 'Available'),
                ('Tymal Mills', 'BOWLER', 'England', 200, 'Available'),
                ('David Payne', 'BOWLER', 'England', 100, 'Available'),
                ('Nahid Rana', 'BOWLER', 'Bangladesh', 75, 'Available'),
                ('Prayas Ray Barman', 'BATTER', 'India', 30, 'Available'),
                ('Jafar Jamal', 'BATTER', 'India', 30, 'Available'),
                ('Ayaz Khan', 'BATTER', 'India', 30, 'Available'),
                ('Kaushik Maity', 'BATTER', 'India', 30, 'Available'),
                ('Rituraj Sharma', 'BATTER', 'India', 30, 'Available'),
                ('Vaibhav Suryavanshi', 'BATTER', 'India', 30, 'Available'),
                ('Kartik Chadha', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Writtick Chatterjee', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Prerit Dutta', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Rajneesh Gurbani', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shubhang Hegde', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Saransh Jain', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ripal Patel', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Akash Vashisht', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Anirudh Kanwar', 'BOWLER', 'India', 30, 'Available'),
                ('Shubham Kapse', 'BOWLER', 'India', 30, 'Available'),
                ('Atif Mushtaq', 'BOWLER', 'India', 30, 'Available'),
                ('Dipesh Parwani', 'BOWLER', 'India', 30, 'Available'),
                ('Manish Reddy', 'BOWLER', 'India', 30, 'Available'),
                ('Chetan Sharma', 'BOWLER', 'India', 30, 'Available'),
                ('Avinash Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Alick Athanaze', 'ALL-ROUNDER', 'West Indies', 75, 'Available'),
                ('Hilton Cartwright', 'ALL-ROUNDER', 'Australia', 75, 'Available'),
                ('Dominic Drakes', 'ALL-ROUNDER', 'West Indies', 125, 'Available'),
                ('Daryn Dupavillon', 'BOWLER', 'South Africa', 75, 'Available'),
                ('Matthew Forde', 'ALL-ROUNDER', 'West Indies', 125, 'Available'),
                ('Patrick Kruger', 'ALL-ROUNDER', 'South Africa', 75, 'Available'),
                ('Lahiru Kumara', 'BOWLER', 'Sri Lanka', 75, 'Available'),
                ('Michael Neser', 'ALL-ROUNDER', 'Australia', 75, 'Available'),
                ('Richard Ngarava', 'BOWLER', 'Zimbabwe', 75, 'Available'),
                ('Wayne Parnell', 'BOWLER', 'South Africa', 100, 'Available'),
                ('Keemo Paul', 'ALL-ROUNDER', 'West Indies', 125, 'Available'),
                ('Odean Smith', 'ALL-ROUNDER', 'West Indies', 75, 'Available'),
                ('Andrew Tye', 'BOWLER', 'Australia', 75, 'Available'),
                ('Ajay Ahlawat', 'ALL-ROUNDER', 'India', 40, 'Available'),
                ('Corbin Bosch', 'ALL-ROUNDER', 'South Africa', 30, 'Available'),
                ('Mayank Gusain', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mukhtar Hussain', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Girinath Reddy', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Jalaj Saxena', 'ALL-ROUNDER', 'India', 40, 'Available'),
                ('Yajas Sharma', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sanjay Yadav', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vishal Godara', 'BOWLER', 'India', 30, 'Available'),
                ('Eshan Malinga', 'BOWLER', 'Sri Lanka', 30, 'Available'),
                ('Samarth Nagraj', 'BOWLER', 'India', 30, 'Available'),
                ('Abhishek Saini', 'BOWLER', 'India', 30, 'Available'),
                ('Dumindu Sewmina', 'BOWLER', 'Sri Lanka', 30, 'Available'),
                ('Pradyuman Kumar Singh', 'BOWLER', 'India', 30, 'Available'),
                ('Vasu Vats', 'BOWLER', 'India', 30, 'Available'),
                ('Umang Kumar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Mohamed Ali', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Atharva Ankolekar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vaisakh Chandran', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Auqib Dar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Rohit Rayudu', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Uday Saharan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ayush Vartak', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Baba Aparajith', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sumit Kumar Beniwal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Nishunk Birla', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Digvijay Deshmukh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Lakshay Jain', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Duan Jansen', 'ALL-ROUNDER', 'South Africa', 30, 'Available'),
                ('Kritagya Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('P. Vignesh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sabhay Chadha', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ben Howell', 'ALL-ROUNDER', 'England', 50, 'Available'),
                ('Hemanth Kumar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Rohan Rana', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Bharat Sharma', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Pratham Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Tripurana Vijay', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Ravi Yadav', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Arjun Azad', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Abhay Choudhary', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Gaurav Gambhir', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Shubham Garhwal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Tejasvi Jaiswal', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Sairaj Patil', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Madhav Tiwari', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Kamal Tripathi', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Prashant Chauhan', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Yash Dabas', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Dhruv Kaushik', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Khrievitso Kense', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Akash Parkar', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vignesh Puthur', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Tripuresh Singh', 'ALL-ROUNDER', 'India', 30, 'Available'),
                ('Vijay Yadav', 'ALL-ROUNDER', 'India', 30, 'Available')
            """;
            statement.executeUpdate(insertData);
            
            System.out.println("Database setup completed successfully!");
            return connection;
            
        } catch (SQLException e) {
            System.err.println("Database setup failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
