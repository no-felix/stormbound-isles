# Stormbound Isles

<div align="center">
  <img src="src/main/resources/assets/stormbound-isles/icon.png" alt="Stormbound Isles" width="128" height="128">
  
  **A competitive multiplayer Minecraft mod featuring elemental islands, strategic gameplay, and dynamic disasters**
  
  [![Fabric](https://img.shields.io/badge/Fabric-1.21.1-green.svg)](https://fabricmc.net/)
  [![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
  [![License](https://img.shields.io/github/license/no-felix/stormbound-isles)](LICENSE)
  
  [🌐 Project Website](https://no-felix.github.io/stormbound-isles-nextjs/) • [📖 Documentation](https://github.com/no-felix/stormbound-isles-nextjs)
</div>

## 🎮 Overview

Stormbound Isles is a sophisticated Minecraft mod built with Fabric that transforms multiplayer gameplay into an elemental battleground. Five teams compete across unique themed islands while facing dynamic disasters and strategic challenges in multiple game phases.

### 🌟 Key Features

- **🏝️ Five Elemental Islands**: Volcano, Ice, Desert, Mushroom, and Crystal biomes with unique mechanics
- **⚡ Dynamic Disaster System**: Random or triggered catastrophes that reshape gameplay
- **🛡️ Team Passive Buffs**: Location and island-based bonuses using custom [`BuffAuraHandler`](src/main/java/de/nofelix/stormboundisles/handler/BuffAuraHandler.java)
- **📊 Multi-Phase Gameplay**: Build phase protection → PvP phase → Scoring evaluation
- **🏆 Comprehensive Scoring**: Creative building, survival, and combat performance tracking
- **⚔️ Strategic PvP**: Coordinated team battles after the protection period

## 🏗️ Technical Architecture

### Core Systems

- **[`GameManager`](src/main/java/de/nofelix/stormboundisles/game/GameManager.java)**: Phase management, timers, and game state coordination
- **[`ScoreboardManager`](src/main/java/de/nofelix/stormboundisles/game/ScoreboardManager.java)**: Real-time team scoring and Minecraft scoreboard integration
- **[`InitializationRegistry`](src/main/java/de/nofelix/stormboundisles/init/InitializationRegistry.java)**: Priority-based component initialization system
- **[`CommandManager`](src/main/java/de/nofelix/stormboundisles/command/CommandManager.java)**: Modular command architecture with permission levels

### Design Patterns

```java
@Initialize(priority = 2000)
public static void initialize() {
    // Priority-based initialization system
    // 2000-3000: Core systems
    // 1500-1999: Managers and services  
    // 1000-1499: Game elements
    // 500-999: Feature implementations
}
```

- **Modular Command System**: Interface-based [`CommandCategory`](src/main/java/de/nofelix/stormboundisles/command/CommandCategory.java) for organized command management
- **Event-Driven Architecture**: Fabric event handlers for seamless Minecraft integration
- **Data Abstraction**: Centralized [`DataManager`](src/main/java/de/nofelix/stormboundisles/data/DataManager.java) for team and island management
- **Configuration Management**: Flexible settings through [`ConfigManager`](src/main/java/de/nofelix/stormboundisles/config/ConfigManager.java)

## 📋 Command System

The mod features a hierarchical permission system with three levels:

| Permission Level | Role | Commands |
|-----------------|------|----------|
| **Level 0** | Players | Team info, player info |
| **Level 2** | Moderators | Island management, team assignments, points |
| **Level 3** | Administrators | Game control, reset functions |

### Example Commands
```
/sbi admin game start              # Start the game
/sbi team assign volcano player1   # Assign player to team
/sbi island setspawn island_01     # Set island spawn point
/sbi points add volcano 100 "Building bonus"
```

## 🎯 Game Flow

1. **Lobby Phase**: Team assignment and preparation
2. **Build Phase**: Protected construction period with team buffs
3. **PvP Phase**: Strategic combat and resource competition  
4. **Evaluation**: Scoring based on creativity, survival, and performance

## 🚀 Getting Started

### Prerequisites
- Minecraft 1.21.1
- Fabric Loader
- Java 21+

### Installation
1. Download the latest release
2. Place in your Fabric mods folder
3. Configure teams and islands via commands
4. Start your elemental competition!

## 🌐 Project Links

- **[🎮 Official Website](https://no-felix.github.io/stormbound-isles-nextjs/)** - Complete project showcase
- **[📱 Next.js Frontend](https://github.com/no-felix/stormbound-isles-nextjs)** - Modern web interface

## 🤝 Contributing

We welcome contributions! Please check our coding standards and architectural patterns before submitting PRs.

### Development Setup
```bash
git clone https://github.com/no-felix/stormbound-isles
./gradlew build
./gradlew runClient  # For testing
./gradlew runServer  # For server testing
```

## 📄 License

This project is licensed under the CC0-1.0 License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">
  <sub>Built with ❤️ using Fabric for Minecraft 1.21.1</sub>
</div>