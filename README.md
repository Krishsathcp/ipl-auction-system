# 🏏 IPL Auction System (Java + Swing + MySQL)

[![Java](https://img.shields.io/badge/Java-17-blue?logo=java)](https://www.oracle.com/java/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-orange?logo=mysql)](https://www.mysql.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20LAN%20Cross%20Device-yellow)]()
[![UI](https://img.shields.io/badge/UI-Swing-lightgrey?logo=java)]()

> ⚡ Real-time cross-device IPL auction experience using Java, Swing, and MySQL. Perfect for coding events, hackathons, or cricket-lovers!

---

## 📸 Preview

> 🎨 Improved UI with light theme, high-contrast buttons, and professional UX.

<img src="https://user-images.githubusercontent.com/your-preview-image-client.png" width="600"/>
<img src="https://user-images.githubusercontent.com/your-preview-image-server.png" width="600"/>

---

## 📦 Folder Structure

ipl-auction-system/
│
├── .gitignore
├── LICENSE
├── README.md
│
├── bin/                       # Compiled .class files
│   ├── AuctionClientGUI*.class
│   ├── IPLAuctionDB1.class
│   └── Server2*.class
│
├── lib/
│   └── mysql-connector-j-9.1.0.jar
│
├── scripts/                  # Easy launchers
│   ├── start_client.bat      ✅ Double-click to start client
│   ├── start_server.bat      ✅ Double-click to start server
│
├── src/
│   ├── AuctionClientGUI.java
│   └── Server2.java


## 🧰 Requirements

- ✅ Java 8 or later (preferably JDK 17)
- ✅ MySQL Server (default: localhost:3306)
- ✅ Same WiFi or LAN for cross-device multiplayer
- ✅ `mysql-connector-j-9.1.0.jar` in `/lib/`

---

## ⚙️ Setup Instructions

### 1. 🔧 MySQL Configuration

Update your MySQL credentials inside `IPLAuctionDB1.java`:

```java
Connection conn = DriverManager.getConnection(
    "jdbc:mysql://localhost:3306",
    "your_mysql_username",
    "your_mysql_password"
);
📌 No need to manually create database — it auto-generates ipl_auction_2025.

2. 🚀 Running the Server
bash
Copy
Edit
scripts/start_server.bat
Compiles server Java file

Starts auction server on port 1234

3. 🎮 Running the Client
bash
Copy
Edit
scripts/start_client.bat
Launches the auction client GUI

Connects to server running on your local machine or LAN

🌐 LAN / Cross-Device Setup
On the host machine (server):

Find your IP using ipconfig (e.g., 192.168.1.5)

On client devices:

Open AuctionClientGUI.java

Change the socket line:

Socket socket = new Socket("192.168.1.5", 1234);
Recompile via start_client.bat

✅ Auction Rules & Constraints
Rule	Value
💸 Initial Purse	₹12,000
👤 Max Players per Team	25
🌍 Max Foreign Players	8
⬆️ Min Bid Increment	₹10
⏱️ Bidding Timer	30 seconds
🕒 Finalize Timer	15 seconds
🚫 Over-budget Bidding	Prevented

🧠 How It Works
Clients connect and enter team name

Once all teams are READY, server begins the auction

Players are announced one by one, each with 30s bid window

Highest bid wins, purse is deducted

Auction ends after all players processed

🧪 Sample Flow
Client1 → LOGIN:RCB

Client2 → LOGIN:CSK

Both send READY

Server starts player auction: Virat Kohli, base ₹200

Client1: BID:300, Client2: BID:350

Timer ends, player sold to CSK

Repeat for all players

Server shuts down with final stats

📊 Planned Enhancements
 Admin dashboard

 Match scheduling after auction

 CSV/Excel exports

 Role-based access (admin/viewer)

 WebSocket-based frontend (React)

👨‍💻 Author
Krishsath CP

📧 Email: cpkrishsath@gmail.com
🔗 LinkedIn: Krishsath CP
💻 GitHub: @Krishsathcp

📄 License
This project is licensed under the MIT License.

🏁 Let the Auction Begin!
⚡ Tag your friends, assign franchises, and relive the IPL thrill with your own mini-auction system!
```
