import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.DecimalFormat;
import java.util.Timer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class AuctionClientGUI extends JFrame {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 1234;
    private static final Color PRIMARY_COLOR = new Color(0, 102, 204);
    private static final Color SECONDARY_COLOR = new Color(255, 215, 0);
    private static final Color BACKGROUND_COLOR = new Color(15, 15, 35);
    private static final Color CARD_COLOR = new Color(25, 25, 45);
    private static final Color SUCCESS_COLOR = new Color(46, 204, 113);
    private static final Color DANGER_COLOR = new Color(231, 76, 60);

    // UI Components
    private JTextArea auctionLog;
    private JTextField teamNameField, bidAmountField;
    private JButton connectButton, startAuctionButton, placeBidButton, 
                   finalizeButton, readyButton, displayTeamButton, exitButton;
    private JLabel statusLabel, currentPlayerLabel, currentBidLabel, 
                  teamPurseLabel, connectionStatusLabel;
    private JProgressBar connectionProgress;
    private JPanel mainPanel, controlPanel, infoPanel;
    private JScrollPane logScrollPane;
    
    // Network components
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    
    // Application state
    private boolean isConnected = false;
    private boolean isLoggedIn = false;
    private boolean isAuctionStarted = false;
    private String teamName = "";
    private double teamPurse = 12000.0;
    private String currentPlayer = "";
    private double currentBid = 0.0;
    private String currentBidder = "";
    private Timer connectionTimer;
    private DecimalFormat currencyFormat = new DecimalFormat("#,##0.00");

    public AuctionClientGUI() {
        initializeUI();
        setupEventHandlers();
        updateUIState();
    }

    private void initializeUI() {
        setTitle("IPL Auction 2025 - Professional Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setResizable(true);
        
        // Set modern look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fallback to default
        }

        // Create main panel with background
        mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBackground(BACKGROUND_COLOR);
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        createHeaderPanel();
        createControlPanel();
        createInfoPanel();
        createLogPanel();

        add(mainPanel);
    }

    private void createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(BACKGROUND_COLOR);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

        // Title
        JLabel titleLabel = new JLabel("IPL AUCTION 2025", JLabel.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 32));
        titleLabel.setForeground(SECONDARY_COLOR);
        
        // Connection status
        connectionStatusLabel = new JLabel("Disconnected", JLabel.CENTER);
        connectionStatusLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        connectionStatusLabel.setForeground(DANGER_COLOR);
        
        connectionProgress = new JProgressBar();
        connectionProgress.setIndeterminate(false);
        connectionProgress.setStringPainted(true);
        connectionProgress.setString("Not Connected");
        connectionProgress.setVisible(false);

        headerPanel.add(titleLabel, BorderLayout.NORTH);
        headerPanel.add(connectionStatusLabel, BorderLayout.CENTER);
        headerPanel.add(connectionProgress, BorderLayout.SOUTH);

        mainPanel.add(headerPanel, BorderLayout.NORTH);
    }

    private void createControlPanel() {
        controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setBackground(CARD_COLOR);
        controlPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(PRIMARY_COLOR, 2),
            BorderFactory.createEmptyBorder(20, 20, 20, 20)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Team name section
        addLabelAndField("Team Name:", teamNameField = new JTextField(20), gbc, 0);
        teamNameField.setFont(new Font("Arial", Font.PLAIN, 14));
        
        // Bid amount section
        addLabelAndField("Bid Amount (₹):", bidAmountField = new JTextField(20), gbc, 1);
        bidAmountField.setFont(new Font("Arial", Font.PLAIN, 14));
        
        // Buttons panel
        JPanel buttonPanel = createButtonPanel();
        gbc.gridy = 2;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        controlPanel.add(buttonPanel, gbc);

        mainPanel.add(controlPanel, BorderLayout.WEST);
    }

    private void addLabelAndField(String labelText, JTextField field, GridBagConstraints gbc, int row) {
        JLabel label = new JLabel(labelText);
        label.setFont(new Font("Arial", Font.BOLD, 14));
        label.setForeground(Color.WHITE);
        
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        controlPanel.add(label, gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        controlPanel.add(field, gbc);
    }

    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 10, 10));
        buttonPanel.setBackground(CARD_COLOR);

        // Create buttons with modern styling
        connectButton = createStyledButton("Connect", PRIMARY_COLOR);
        startAuctionButton = createStyledButton("Start Auction", SUCCESS_COLOR);
        placeBidButton = createStyledButton("Place Bid", SECONDARY_COLOR);
        finalizeButton = createStyledButton("Finalize", new Color(155, 89, 182));
        readyButton = createStyledButton("Ready", new Color(52, 152, 219));
        displayTeamButton = createStyledButton("My Team", new Color(26, 188, 156));
        exitButton = createStyledButton("Exit", DANGER_COLOR);

        buttonPanel.add(connectButton);
        buttonPanel.add(startAuctionButton);
        buttonPanel.add(placeBidButton);
        buttonPanel.add(finalizeButton);
        buttonPanel.add(readyButton);
        buttonPanel.add(displayTeamButton);
        buttonPanel.add(exitButton);
        buttonPanel.add(new JLabel()); // Empty space

        return buttonPanel;
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setFont(new Font("Arial", Font.BOLD, 12));
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createRaisedBevelBorder());
        button.setPreferredSize(new Dimension(120, 40));
        
        // Add hover effect
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(color.brighter());
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(color);
            }
        });
        
        return button;
    }

    private void createInfoPanel() {
        infoPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        infoPanel.setBackground(CARD_COLOR);
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(PRIMARY_COLOR, 2),
            BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));

        // Status and info labels
        statusLabel = createInfoLabel("Status: Disconnected");
        currentPlayerLabel = createInfoLabel("Current Player: None");
        currentBidLabel = createInfoLabel("Current Bid: ₹0.00");
        teamPurseLabel = createInfoLabel("Team Purse: ₹12,000.00");

        infoPanel.add(statusLabel);
        infoPanel.add(currentPlayerLabel);
        infoPanel.add(currentBidLabel);
        infoPanel.add(teamPurseLabel);

        mainPanel.add(infoPanel, BorderLayout.EAST);
    }

    private JLabel createInfoLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.PLAIN, 12));
        label.setForeground(Color.WHITE);
        label.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        return label;
    }

    private void createLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(CARD_COLOR);
        logPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(PRIMARY_COLOR, 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        JLabel logTitle = new JLabel("Auction Log");
        logTitle.setFont(new Font("Arial", Font.BOLD, 16));
        logTitle.setForeground(SECONDARY_COLOR);
        logTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        auctionLog = new JTextArea(15, 50);
        auctionLog.setEditable(false);
        auctionLog.setBackground(new Color(20, 20, 30));
        auctionLog.setForeground(Color.WHITE);
        auctionLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        auctionLog.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        logScrollPane = new JScrollPane(auctionLog);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        logPanel.add(logTitle, BorderLayout.NORTH);
        logPanel.add(logScrollPane, BorderLayout.CENTER);

        mainPanel.add(logPanel, BorderLayout.CENTER);
    }

    private void setupEventHandlers() {
        connectButton.addActionListener(e -> connectToServer());
        startAuctionButton.addActionListener(e -> startAuction());
        placeBidButton.addActionListener(e -> placeBid());
        finalizeButton.addActionListener(e -> finalizeBid());
        readyButton.addActionListener(e -> markReady());
        displayTeamButton.addActionListener(e -> displayTeam());
        exitButton.addActionListener(e -> exitApplication());

        // Enter key handlers
        teamNameField.addActionListener(e -> connectToServer());
        bidAmountField.addActionListener(e -> placeBid());

        // Window closing handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
    }

    private void connectToServer() {
        if (isConnected) {
            showMessage("Already connected to server!", "INFO");
            return;
        }

        String team = teamNameField.getText().trim();
        if (team.isEmpty()) {
            showMessage("Please enter a team name!", "ERROR");
            return;
        }

        teamName = team;
        
        SwingUtilities.invokeLater(() -> {
            connectionProgress.setVisible(true);
            connectionProgress.setIndeterminate(true);
            connectionProgress.setString("Connecting...");
            connectButton.setEnabled(false);
        });

        // Connect in background thread
        new Thread(() -> {
            try {
                socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                SwingUtilities.invokeLater(() -> {
                    isConnected = true;
                    connectionStatusLabel.setText("Connected");
                    connectionStatusLabel.setForeground(SUCCESS_COLOR);
                    connectionProgress.setVisible(false);
                    connectButton.setEnabled(true);
                    updateUIState();
                    appendLog("Connected to server successfully!");
                });

                // Send login immediately after connection
                out.println("LOGIN:" + teamName);
                isLoggedIn = true;
                
                // Start listening for server messages
                listenForServerMessages();
                
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    showMessage("Failed to connect to server: " + e.getMessage(), "ERROR");
                    connectionStatusLabel.setText("Connection Failed");
                    connectionStatusLabel.setForeground(DANGER_COLOR);
                    connectionProgress.setVisible(false);
                    connectButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void startAuction() {
        if (!isConnected || !isLoggedIn) {
            showMessage("Please connect first!", "ERROR");
            return;
        }
        
        out.println("READY");
        appendLog("Marked as ready for auction start...");
        updateUIState();
    }

    private void placeBid() {
        if (!isConnected || !isAuctionStarted) {
            showMessage("Auction not started yet!", "ERROR");
            return;
        }

        String bidText = bidAmountField.getText().trim();
        if (bidText.isEmpty()) {
            showMessage("Please enter a bid amount!", "ERROR");
            return;
        }

        try {
            double bidAmount = Double.parseDouble(bidText);
            if (bidAmount <= 0) {
                showMessage("Bid amount must be positive!", "ERROR");
                return;
            }
            
            if (bidAmount > teamPurse) {
                showMessage("Insufficient funds! Current purse: ₹" + currencyFormat.format(teamPurse), "ERROR");
                return;
            }

            out.println("BID:" + bidAmount);
            appendLog("Placed bid: ₹" + currencyFormat.format(bidAmount));
            bidAmountField.setText("");
            
        } catch (NumberFormatException e) {
            showMessage("Please enter a valid number!", "ERROR");
        }
    }

    private void finalizeBid() {
        if (!isConnected || !isAuctionStarted) {
            showMessage("Auction not started yet!", "ERROR");
            return;
        }
        
        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to finalize this player?",
            "Confirm Finalization",
            JOptionPane.YES_NO_OPTION);
            
        if (confirm == JOptionPane.YES_OPTION) {
            out.println("FINALIZE_PLAYER");
            appendLog("Voted to finalize current player...");
        }
    }

    private void markReady() {
        if (!isConnected) {
            showMessage("Please connect first!", "ERROR");
            return;
        }
        
        out.println("READY");
        appendLog("Marked as ready...");
    }

    private void displayTeam() {
        if (!isConnected || !isLoggedIn) {
            showMessage("Please connect first!", "ERROR");
            return;
        }
        
        out.println("DISPLAY_TEAMS");
        appendLog("Requesting team information...");
    }

    private void exitApplication() {
        if (isConnected) {
            try {
                out.println("EXIT");
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        
        if (connectionTimer != null) {
            connectionTimer.cancel();
        }
        
        System.exit(0);
    }

    private void listenForServerMessages() {
        new Thread(() -> {
            try {
                String message;
                while ((message = in.readLine()) != null) {
                    final String finalMessage = message;
                    SwingUtilities.invokeLater(() -> processServerMessage(finalMessage));
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    if (isConnected) {
                        appendLog("Connection lost: " + e.getMessage());
                        connectionStatusLabel.setText("Disconnected");
                        connectionStatusLabel.setForeground(DANGER_COLOR);
                        isConnected = false;
                        isLoggedIn = false;
                        isAuctionStarted = false;
                        updateUIState();
                    }
                });
            }
        }).start();
    }

    private void processServerMessage(String message) {
        appendLog("Server: " + message);
        
        if (message.startsWith("AUCTION_STARTED")) {
            isAuctionStarted = true;
            statusLabel.setText("Status: Auction Started");
            statusLabel.setForeground(SUCCESS_COLOR);
            showMessage("Auction has started!", "INFO");
            
        } else if (message.startsWith("NEW_PLAYER:")) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                currentPlayer = parts[1];
                currentBid = Double.parseDouble(parts[2]);
                currentBidder = "";
                currentPlayerLabel.setText("Current Player: " + currentPlayer);
                currentBidLabel.setText("Current Bid: ₹" + currencyFormat.format(currentBid));
                showMessage("New player up for auction: " + currentPlayer, "INFO");
            }
            
        } else if (message.startsWith("NEW_BID:")) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                currentBidder = parts[1];
                currentBid = Double.parseDouble(parts[2]);
                currentBidLabel.setText("Current Bid: ₹" + currencyFormat.format(currentBid) + " by " + currentBidder);
            }
            
        } else if (message.startsWith("PLAYER_SOLD:")) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                String buyer = parts[1];
                double price = Double.parseDouble(parts[2]);
                showMessage(currentPlayer + " sold to " + buyer + " for ₹" + currencyFormat.format(price), "SUCCESS");
                
                // Update purse if it's our team
                if (buyer.equals(teamName)) {
                    teamPurse -= price;
                    teamPurseLabel.setText("Team Purse: ₹" + currencyFormat.format(teamPurse));
                }
            }
            
        } else if (message.startsWith("PLAYER_UNSOLD")) {
            showMessage(currentPlayer + " went unsold!", "INFO");
            
        } else if (message.startsWith("BID_REJECTED:")) {
            String reason = message.substring(13);
            showMessage("Bid rejected: " + reason, "ERROR");
            
        } else if (message.startsWith("AUCTION_FINISHED")) {
            isAuctionStarted = false;
            statusLabel.setText("Status: Auction Finished");
            statusLabel.setForeground(new Color(155, 89, 182));
            showMessage("Auction has finished!", "INFO");
            
        } else if (message.contains("Remaining purse:")) {
            // Extract purse amount from message
            String[] parts = message.split("Remaining purse: ");
            if (parts.length > 1) {
                try {
                    teamPurse = Double.parseDouble(parts[1]);
                    teamPurseLabel.setText("Team Purse: ₹" + currencyFormat.format(teamPurse));
                } catch (NumberFormatException e) {
                    // Ignore parsing errors
                }
            }
        }
        
        updateUIState();
    }

    private void updateUIState() {
        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(!isConnected);
            teamNameField.setEnabled(!isConnected);
            startAuctionButton.setEnabled(isConnected && isLoggedIn && !isAuctionStarted);
            placeBidButton.setEnabled(isConnected && isAuctionStarted);
            finalizeButton.setEnabled(isConnected && isAuctionStarted);
            readyButton.setEnabled(isConnected && isLoggedIn);
            displayTeamButton.setEnabled(isConnected && isLoggedIn);
            bidAmountField.setEnabled(isConnected && isAuctionStarted);
        });
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            auctionLog.append("[" + timestamp + "] " + message + "\n");
            auctionLog.setCaretPosition(auctionLog.getDocument().getLength());
        });
    }

    private void showMessage(String message, String type) {
        SwingUtilities.invokeLater(() -> {
            int messageType = JOptionPane.INFORMATION_MESSAGE;
            switch (type) {
                case "ERROR":
                    messageType = JOptionPane.ERROR_MESSAGE;
                    break;
                case "WARNING":
                    messageType = JOptionPane.WARNING_MESSAGE;
                    break;
                case "SUCCESS":
                    messageType = JOptionPane.INFORMATION_MESSAGE;
                    break;
            }
            JOptionPane.showMessageDialog(this, message, "IPL Auction", messageType);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Use default look and feel
            }
            
            new AuctionClientGUI().setVisible(true);
        });
    }
}