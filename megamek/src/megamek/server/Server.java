/*
 * MegaMek - Copyright (C) 2000-2002 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */

package megamek.server;

import java.net.*;
import java.io.*;
import java.util.*;

import megamek.*;
import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.options.*;
import megamek.server.commands.*;

/**
 * @author Ben Mazur
 */
public class Server
    implements Runnable
{
//    public final static String  LEGAL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-";
    public final static String  DEFAULT_BOARD = MapSettings.BOARD_SURPRISE;
    
    // server setup
    private String              password;
    private ServerSocket        serverSocket;
    private ServerLog           log = new ServerLog();
    private String              motd;

    // game info
    private Vector              connections = new Vector(4);
    private Vector              connectionsPending = new Vector(4);
    private Hashtable           connectionIds = new Hashtable();

    private int                 connectionCounter = 0;
    private int                 entityCounter = 0;

    private Game                game = new Game();

    private MapSettings         mapSettings = new MapSettings();
    
    // list of turns and whose turn it is
    private int                 roundCounter = 0;
    private Vector              turns = new Vector();
    private int                 turnIndex = 0;

    // stuff for the current turn
    private Vector              attacks = new Vector();
    private Vector              pendingCharges = new Vector();
    private Vector              pilotRolls = new Vector();

    private StringBuffer        roundReport = new StringBuffer();
    private StringBuffer        phaseReport = new StringBuffer();
    
    private boolean             forceVictory = false;
    
    // commands
    private Hashtable           commandsHash = new Hashtable();

    // listens for and connects players
    private Thread              connector;

    /**
     * Construct a new GameHost and begin listening for
     * incoming clients.
     */
    public Server(String password, int port) {
        this.password = password.length() > 0 ? password : null;
        // initialize server socket
        try {
            serverSocket = new ServerSocket(port);
        } catch(IOException ex) {
            System.err.println("could not create server socket on port " + port);
        }
        
        motd = createMotd();
        
        game.getOptions().initialize();

        changePhase(Game.PHASE_LOUNGE);

        // display server start text
        System.out.println("s: starting a new server...");
        System.out.println("s: address = " + serverSocket.getInetAddress().getHostAddress() + " port = " + serverSocket.getLocalPort());
        try {
            System.out.println("s: address = " + InetAddress.getByName("127.0.0.1").getHostAddress());
        } catch(UnknownHostException ex) {}
        System.out.println("s: password = " + this.password);

        connector = new Thread(this);
        connector.start();
        
        // register commands
        registerCommand(new HelpCommand(this));
        registerCommand(new KickCommand(this));
        registerCommand(new ResetCommand(this));
        registerCommand(new RollCommand(this));
        registerCommand(new VictoryCommand(this));
        registerCommand(new WhoCommand(this));
    }
    
    public void setGame(Game g)
    {
        game = g;
    }
    
    /**
     * Make a default message o' the day containing the version string, and
     * if it was found, the build timestamp
     */
    private String createMotd() {
        StringBuffer buf = new StringBuffer();
        buf.append("Welcome to MegaMek.  Server is running version ");
        buf.append(MegaMek.VERSION);
        buf.append(", build date ");
        if (MegaMek.TIMESTAMP > 0L) {
            buf.append(new Date(MegaMek.TIMESTAMP).toString());
        } else {
            buf.append("unknown");
        }
        buf.append(".");
        
        return buf.toString();
    }
    
    /**
     * @return true if the server has a password
     */
public boolean isPassworded() {
        return password != null;
    }
    
    /**
     * @return true if the password matches
     */
    public boolean isPassword(Object guess) {
        return password.equals(guess);
    }
    
    /**
     * Registers a new command in the server command table
     */
    private void registerCommand(ServerCommand command) {
        commandsHash.put(command.getName(), command);
    }
    
    /**
     * Returns the command associated with the specified name
     */
    public ServerCommand getCommand(String name) {
        return (ServerCommand)commandsHash.get(name);
    }
    
    /**
     * Shuts down the server.
     */
    public void die() {
        // kill thread accepting new connections
        connector = null;
        
        // close socket
        try {
            serverSocket.close();
        } catch(IOException ex) { ; }
        
        // kill pending connnections
        for (Enumeration i = connectionsPending.elements(); i.hasMoreElements();) {
            final Connection conn = (Connection)i.nextElement();
            conn.die();
        }
        connectionsPending = null;
        
        // kill active connnections
        for (Enumeration i = connections.elements(); i.hasMoreElements();) {
            final Connection conn = (Connection)i.nextElement();
            conn.die();
        }
        connections = null;
        connectionIds = null;
    }
    
    /**
     * Returns an enumeration of all the command names
     */
    public Enumeration getAllCommandNames() {
        return commandsHash.keys();
    }

    /**
     * Sent when a clients attempts to connect.
     */
    private void greeting(int cn) {
        // send server greeting -- client should reply with client info.
        sendToPending(cn, new Packet(Packet.COMMAND_SERVER_GREETING));
    }

    /**
     * Allow the player to set whatever parameters he is able to
     */
    private void receivePlayerInfo(Packet packet, int connId) {
        Player player = (Player)packet.getObject(0);
        game.getPlayer(connId).setColorIndex(player.getColorIndex());
        game.getPlayer(connId).setStartingPos(player.getStartingPos());
        game.getPlayer(connId).setTeam(player.getTeam());
    }
        
    /**
     * Recieves a player name, sent from a pending connection, and connects
     * that connection.
     */
    private void receivePlayerName(Packet packet, int connId) {
        final Connection conn = getPendingConnection(connId);
        String name = (String)packet.getObject(0);
        boolean returning = false;

        // this had better be from a pending connection
        if (conn == null) {
            System.out.println("server: got a client name from a non-pending connection");
            return;
        }
        
        // check if they're connecting with the same name as a ghost player
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            Player player = (Player)i.nextElement();
            if (player.isGhost() && player.getName().equals(name)) {
                returning = true;
                player.setGhost(false);
                // switch id
                connId = player.getId();
                conn.setId(connId);
            }
        }
        

        // right, switch the connection into the "active" bin
        connectionsPending.removeElement(conn);
        connections.addElement(conn);
        connectionIds.put(new Integer(conn.getId()), conn);

        // add and validate the player info
        if (!returning) {
            game.addPlayer(connId, new Player(connId, name));
            validatePlayerInfo(connId);
        }
        
        // send the player the motd
        sendServerChat(connId, motd);

        // send info that the player has connected
        send(createPlayerConnectPacket(connId));

        // tell them their local playerId
        send(connId, new Packet(Packet.COMMAND_LOCAL_PN, new Integer(connId)));

        // send current game info
        sendCurrentInfo(connId);

        System.out.println("s: player " + connId
                           + " (" + getPlayer(connId).getName() + ") connected from "
                           + getClient(connId).getSocket().getInetAddress());
        sendServerChat(getPlayer(connId).getName() + " connected from "
                           + getClient(connId).getSocket().getInetAddress());
        
        // there is more than one player, uncheck the friendly fire option
        if (game.getNoOfPlayers() > 1 && game.getOptions().booleanOption("friendly_fire")) {
            game.getOptions().getOption("friendly_fire").setValue(false);
            send(createGameSettingsPacket());
        }
    }
    
    /**
     * Sends a player the info they need to look at the current phase
     */
    private void sendCurrentInfo(int connId) {
        transmitAllPlayerConnects(connId);
        send(createGameSettingsPacket());
        if (doBlind()) {
            send(connId, createFilteredEntitiesPacket(getPlayer(connId)));
        }
        else {
        send(connId, createEntitiesPacket());
        }
        switch (game.phase) {
        case Game.PHASE_LOUNGE :
            send(connId, createMapSettingsPacket());
            break;
        default :
            getPlayer(connId).setReady(game.getEntitiesOwnedBy(getPlayer(connId)) <= 0);
            send(connId, createBoardPacket());
            break;
        }
        send(connId, new Packet(Packet.COMMAND_PHASE_CHANGE, new Integer(game.phase)));
    }
    
    

    /**
     * Validates the player info.
     */
    public void validatePlayerInfo(int playerId) {
        final Player player = getPlayer(playerId);
    
//        maybe this isn't actually useful
//        // replace characters we don't like with "X"
//        StringBuffer nameBuff = new StringBuffer(player.getName());
//        for (int i = 0; i < nameBuff.length(); i++) {
//            int chr = nameBuff.charAt(i);
//            if (LEGAL_CHARS.indexOf(chr) == -1) {
//                nameBuff.setCharAt(i, 'X');
//            }
//        }
//        player.setName(nameBuff.toString());
        
        //TODO: check for duplicate or reserved names

        // make sure colorIndex is unique
        boolean[] colorUsed = new boolean[Player.colorNames.length];
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player otherPlayer = (Player)i.nextElement();
            if (otherPlayer.getId() != playerId) {
                colorUsed[otherPlayer.getColorIndex()] = true;
            }
        }
        if (colorUsed[player.getColorIndex()]) {
            // find a replacement color;
            for (int i = 0; i < colorUsed.length; i++) {
                if (!colorUsed[i]) {
                    player.setColorIndex(i);
                    break;
                }
            }
        }

    }

    /**
     * Called when it is sensed that a connection has terminated.
     */
    public void disconnected(int connId) {
        final Connection conn = getClient(connId);
        final Player player = getPlayer(connId);

        // if the connection's even still there, remove it
        if (conn != null) {
            conn.die();
            connections.removeElement(conn);
            connectionIds.remove(new Integer(connId));
        }

        // in the lounge, just remove all entities for that player
        if (game.phase == Game.PHASE_LOUNGE) {
            removeAllEntitesOwnedBy(player);
            //send(createEntitiesPacket());
            entityAllUpdate();
        }

        // if a player has active entities, he becomes a ghost
        if (game.getEntitiesOwnedBy(player) > 0) {
            player.setGhost(true);
            player.setReady(true);
            send(createPlayerUpdatePacket(player.getId()));
            checkReady();
        } else {
            game.removePlayer(player.getId());
            send(new Packet(Packet.COMMAND_PLAYER_REMOVE, new Integer(player.getId())));
        }

        System.out.println("s: player " + connId + " disconnected");
        sendServerChat(player.getName() + " disconnected.");
    }
    
    /**
     * Reset the game back to the lounge.
     *
     * TODO: couldn't this be a hazard if there are other things executing at 
     *  the same time?
     */
    public void resetGame() {
        // remove all entities
        Vector tempEntities = (Vector)game.getEntitiesVector().clone();
        for (Enumeration e = tempEntities.elements(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();
            game.removeEntity(entity.getId());
        }
        send(createEntitiesPacket());
        
        //TODO: remove ghosts
        
        // reset all players
        resetPlayerReady();
        transmitAllPlayerReadys();
        
        changePhase(Game.PHASE_LOUNGE);
    }
    
    /**
     * Shortcut to game.getPlayer(id)
     */
    public Player getPlayer(int id) {
        return game.getPlayer(id);
    }

    /**
     * Counts up how many non-ghost, non-observer players are connected.
     */
        private int countActivePlayers() {
        int count = 0;

        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();

            if (!player.isGhost() && !player.isObserver()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Removes all entities owned by a player.  Should only be called when it
     * won't cause trouble (the lounge, for instance, or between phases.)
     */
    private void removeAllEntitesOwnedBy(Player player) {
        Vector toRemove = new Vector();

        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();

            if (entity.getOwner().equals(player)) {
                toRemove.addElement(entity);
            }
        }

        for (Enumeration e = toRemove.elements(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();

            game.removeEntity(entity.getId());
        }
    }

    /**
     * a shorter name for getConnection()
     */
    private Connection getClient(int connId) {
        return getConnection(connId);
    }

    /**
     * Returns a connection, indexed by id
     */
    public Enumeration getConnections() {
        return connections.elements();
    }

    /**
     * Returns a connection, indexed by id
     */
    public Connection getConnection(int connId) {
        return (Connection)connectionIds.get(new Integer(connId));
    }

    /**
     * Returns a pending connection
     */
    private Connection getPendingConnection(int connId) {
        for (Enumeration i = connectionsPending.elements(); i.hasMoreElements();) {
            final Connection conn = (Connection)i.nextElement();

            if (conn.getId() == connId) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Are we out of turns (done with the phase?)
     */
    private boolean areMoreTurns() {
        return turnIndex < turns.size();
    }

    /**
     * Returns the next turn object or null if we're done with this phase
     */
    private GameTurn nextTurn() {
        return (GameTurn)turns.elementAt(turnIndex++);
    }

    /**
     * Called at the beginning of each game round to reset values on this entity
     * that are reset every round
     */
    private void resetEntityRound() {
        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            Entity entity = (Entity)e.nextElement();

            entity.newRound();
        }
    }

    /**
     * Called at the beginning of each phase.  Sets and resets
     * any entity parameters that need to be reset.
     */
    private void resetEntityPhase() {
    // first, mark doomed entities as destroyed and move them to the graveyard
        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();

            if (entity.isDoomed()) {
                entity.setDestroyed(true);
            }
            if (entity.isDestroyed() || entity.getCrew().isDead()) {
                game.moveToGraveyard(entity.getId());
            }
        }

        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();
            
            // mark all damaged equipment destroyed and empty
            for (Enumeration i = entity.getEquipment(); i.hasMoreElements();) {
                Mounted mounted = (Mounted)i.nextElement();
                if (mounted.isHit() || mounted.isMissing()) {
                    mounted.setShotsLeft(0);
                    mounted.setDestroyed(true);
                }
            }

            // destroy criticals that were hit last phase
            for (int i = 0; i < entity.locations(); i++) {
                for (int j = 0; j < entity.getNumberOfCriticals(i); j++) {
                    final CriticalSlot cs = entity.getCritical(i, j);
                    if (cs != null) {
                        cs.setDestroyed(cs.isDamaged());
                    }
                }
            }
            
            // destroy armor/internals if the section was removed
            for (int i = 0; i < entity.locations(); i++) {
                if (entity.getInternal(i) == Entity.ARMOR_DOOMED) {
                    entity.setArmor(Entity.ARMOR_DESTROYED, i);
                    entity.setArmor(Entity.ARMOR_DESTROYED, i, true);
                    entity.setInternal(Entity.ARMOR_DESTROYED, i);
                }
            }
            
            // try to reload weapons
            for (Enumeration i = entity.getWeapons(); i.hasMoreElements();) {
               Mounted mounted = (Mounted)i.nextElement();
               WeaponType wtype = (WeaponType)mounted.getType();

               if (wtype.getAmmoType() != AmmoType.T_NA) {
                   if (mounted.getLinked() == null || mounted.getLinked().getShotsLeft() <= 0) {
                       entity.loadWeapon(mounted);
                   }
               }              
            }

            // reset damage this phase
            entity.damageThisPhase = 0;

            // reset ready to true
            entity.ready = entity.isActive();
        }
    }

    /**
     * Called at the beginning of certain phases to make
     * every player not ready.
     */
    private void resetPlayerReady() {
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            player.setReady(false);
        }
    }

    /**
     * Called at the beginning of certain phases to make
     * every active player not ready.
     */
    private void resetActivePlayersReady() {
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();

            player.setReady(game.getEntitiesOwnedBy(player) <= 0);

        }
        transmitAllPlayerReadys();
    }
    
    /**
     * Writes the victory report
     */
    private void prepareVictoryReport() {
        roundReport = new StringBuffer();
        
        roundReport.append("\nVictory!\n-------------------\n\n");
        
        roundReport.append("Survivors are:\n");
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            Entity entity = (Entity)i.nextElement();
            roundReport.append(entity.victoryReport());
            roundReport.append('\n');
        }
        
        roundReport.append("\nGraveyard contains:\n");
        for (Enumeration i = game.getGraveyardEntities(); i.hasMoreElements();) {
            Entity entity = (Entity)i.nextElement();
            roundReport.append(entity.victoryReport());
            roundReport.append('\n');
        }
        roundReport.append("\nDetailed unit status saved to entitystatus.txt\n");
    }
    
    /**
     * Generates a detailed report for campaign use
     */
    private String getDetailedVictoryReport() {
        StringBuffer sb = new StringBuffer();
        
        Vector vAllUnits = new Vector();
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            vAllUnits.addElement(i.nextElement());
        }
        
        for (Enumeration i = game.getGraveyardEntities(); i.hasMoreElements();) {
            vAllUnits.addElement(i.nextElement());
        }
        
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            Player p = (Player)i.nextElement();
            sb.append("++++++++++ " + p.getName() + " ++++++++++\n");
            for (int x = 0; x < vAllUnits.size(); x++) {
                Entity e = (Entity)vAllUnits.elementAt(x);
                if (e.getOwner() == p) {
                    sb.append(UnitStatusFormatter.format(e));
                }
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Forces victory at then end of the turn.
     */
    public void forceVictory() {
        forceVictory = true;
    }

    /**
     * Called when a player is "ready".  Handles any moving
     * to the next turn or phase or that stuff.
     */
    private void checkReady() {
        // are there any active players?
        boolean allAboard = countActivePlayers() > 0;
        // check if all active players are ready
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            if (!player.isGhost() && !player.isReady()) {
                allAboard = false;
            }
        }
        // now, do something about it.
        switch(game.phase) {
        case Game.PHASE_LOUNGE :
        case Game.PHASE_EXCHANGE :
        case Game.PHASE_INITIATIVE :
        case Game.PHASE_MOVEMENT_REPORT :
        case Game.PHASE_FIRING_REPORT :
        case Game.PHASE_END :
        case Game.PHASE_VICTORY :
            if (allAboard) {
                endCurrentPhase();
            }
            break;
        case Game.PHASE_MOVEMENT :
        case Game.PHASE_FIRING :
        case Game.PHASE_PHYSICAL :
            if(getPlayer(game.getTurn().getPlayerNum()).isReady()) {
                changeToNextTurn();
            }
            break;
        }
    }

    /**
     * Changes the current phase, does some bookkeeping and
     * then tells the players.
     */
    private void changePhase(int phase) {
        game.phase = phase;

        // prepare for the phase
        prepareForPhase(phase);

        if (isPhasePlayable(phase)) {
            // tell the players about the new phase
            send(new Packet(Packet.COMMAND_PHASE_CHANGE, new Integer(phase)));

            // post phase change stuff
            executePhase(phase);
        } else {
            endCurrentPhase();
        }
    }

    /**
     * Prepares for, presumably, the next phase.  This typically involves
     * resetting the states of entities in the game and making sure the client
     * has the information it needs for the new phase.
     */
    private void prepareForPhase(int phase) {
        switch (phase) {
        case Game.PHASE_LOUNGE :
            mapSettings.setBoardsAvailableVector(scanForBoards(mapSettings.getBoardWidth(), mapSettings.getBoardHeight()));
            mapSettings.setNullBoards(DEFAULT_BOARD);
            break;
        case Game.PHASE_EXCHANGE :
            resetPlayerReady();
            // apply board layout settings to produce a mega-board
            mapSettings.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
            mapSettings.replaceBoardWithRandom(MapSettings.BOARD_SURPRISE);
            Board[] sheetBoards = new Board[mapSettings.getMapWidth() * mapSettings.getMapHeight()];
            for (int i = 0; i < mapSettings.getMapWidth() * mapSettings.getMapHeight(); i++) {
                sheetBoards[i] = new Board();
                sheetBoards[i].load((String)mapSettings.getBoardsSelectedVector().elementAt(i) + ".board");
            }
            game.board.combine(mapSettings.getBoardWidth(), mapSettings.getBoardHeight(),
                    mapSettings.getMapWidth(), mapSettings.getMapHeight(), sheetBoards);
            // deploy all entities
            Coords center = new Coords(game.board.width / 2, game.board.height / 2);
            for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
                Entity entity = (Entity)i.nextElement();
                deploy(entity, getStartingCoords(entity.getOwner().getStartingPos()), center, 10);
            }
            break;
        case Game.PHASE_INITIATIVE :
            // remove the last traces of last round
            attacks.removeAllElements();
            roundReport = new StringBuffer();
            resetEntityPhase();
            resetEntityRound();
            // roll 'em
            resetActivePlayersReady();
            rollInitiative();
            setIneligible(phase);
            determineTurnOrder();
            writeInitiativeReport();
            send(createReportPacket());
            break;
        case Game.PHASE_MOVEMENT :
        case Game.PHASE_FIRING :
        case Game.PHASE_PHYSICAL :
            resetEntityPhase();
            setIneligible(phase);
            determineTurnOrder();
            resetActivePlayersReady();
            //send(createEntitiesPacket());
            entityAllUpdate();
            phaseReport = new StringBuffer();
            break;
        case Game.PHASE_END :
            phaseReport = new StringBuffer();
            resetEntityPhase();
            resolveHeat();
            checkForSuffocation();
            resolveCrewDamage();
            resolveCrewWakeUp();
            if (phaseReport.length() > 0) {
                roundReport.append(phaseReport.toString());
            }
            log.append("\n" + roundReport.toString());
        case Game.PHASE_MOVEMENT_REPORT :
        case Game.PHASE_FIRING_REPORT :
            resetActivePlayersReady();
            send(createReportPacket());
            break;
        case Game.PHASE_VICTORY :
            prepareVictoryReport();
            send(createReportPacket());
            send(createEndOfGamePacket());
            break;
        }
    }

    /**
     * Should we play this phase or skip it?  The only phases we'll skip
     * are the firing or the physical phase if no entities are eligible.
     */
    private boolean isPhasePlayable(int phase) {
        switch (phase) {
        case Game.PHASE_MOVEMENT :
        case Game.PHASE_FIRING :
        case Game.PHASE_PHYSICAL :
            return areMoreTurns();
        default :
            return true;
        }
    }

    /**
     * Do anything we seed to start the new phase, such as give a turn to
     * the first player to play.
     */
    private void executePhase(int phase) {
        switch (phase) {
        case Game.PHASE_EXCHANGE :
            // transmit the board to everybody
            send(createBoardPacket());
            break;
        case Game.PHASE_MOVEMENT :
        case Game.PHASE_FIRING :
        case Game.PHASE_PHYSICAL :
            // set turn
            turnIndex = 0;
            changeToNextTurn();
            break;
        }
    }

    /**
     * Ends this phase and moves on to the next.
     */
    private void endCurrentPhase() {
        switch (game.phase) {
        case Game.PHASE_LOUNGE :
            changePhase(Game.PHASE_EXCHANGE);
            break;
        case Game.PHASE_EXCHANGE :
            changePhase(Game.PHASE_INITIATIVE);
            break;
        case Game.PHASE_INITIATIVE :
            changePhase(Game.PHASE_MOVEMENT);
            break;
        case Game.PHASE_MOVEMENT :
            roundReport.append("\nMovement Phase\n-------------------\n");
            resolveCrewDamage();
            // check phase report
            if (phaseReport.length() > 0) {
                roundReport.append(phaseReport.toString());
                changePhase(Game.PHASE_MOVEMENT_REPORT);
            } else {
                roundReport.append("<nothing>\n");
                changePhase(Game.PHASE_FIRING);
            }
            break;
        case Game.PHASE_MOVEMENT_REPORT :
            changePhase(Game.PHASE_FIRING);
            break;
        case Game.PHASE_FIRING :
            resolveWeaponAttacks();
            checkFor20Damage();
            resolveCrewDamage();
            resolvePilotingRolls();
            resolveCrewDamage(); // again, I guess
            // check phase report
            if (phaseReport.length() > 0) {
                roundReport.append(phaseReport.toString());
                changePhase(Game.PHASE_FIRING_REPORT);
            } else {
                roundReport.append("<nothing>\n");
                changePhase(Game.PHASE_PHYSICAL);
            }
            break;
        case Game.PHASE_FIRING_REPORT :
            changePhase(Game.PHASE_PHYSICAL);
            break;
        case Game.PHASE_PHYSICAL :
            resolvePhysicalAttacks();
            checkFor20Damage();
            resolveCrewDamage();
            resolvePilotingRolls();
            resolveCrewDamage(); // again, I guess
            // check phase report
            if (phaseReport.length() > 0) {
                roundReport.append(phaseReport.toString());
            } else {
                roundReport.append("<nothing>\n");
            }
            changePhase(Game.PHASE_END);
            break;
        case Game.PHASE_END :
            if (victory()) {
                changePhase(Game.PHASE_VICTORY);
            } else {
                changePhase(Game.PHASE_INITIATIVE);
            }
            break;
        case Game.PHASE_VICTORY :
            forceVictory = false;
            resetGame();
            break;
        }
    }
    
    /**
     * Changes to the next turn, if that player is connected
     */
    private void changeToNextTurn() {
        // if there aren't any more turns, end the phase
        if (!areMoreTurns()) {
            endCurrentPhase();
            return;
        }
        GameTurn nextTurn = nextTurn();
        if (getPlayer(nextTurn.getPlayerNum()).isGhost()) {
            changeToNextTurn();
            return;
        }
        changeTurn(nextTurn);
    }

    /**
     * Changes it to make it the specified player's turn.
     */
    private void changeTurn(GameTurn turn) {
        final Player player = getPlayer(turn.getPlayerNum());
        game.setTurn(turn);
        if (player != null) {
            player.setReady(false);
        }
        send(new Packet(Packet.COMMAND_TURN, turn));
    }
    
    /**
     * Returns true if victory conditions have been met.  Victory conditions
     * are when there is only one player left with mechs or only one team.
     */
    public boolean victory() {
        if (forceVictory) {
            return true;
        }
        
        if (!game.getOptions().booleanOption("check_victory")) {
            return false;
        }
        
        // is there only one player left with mechs?
        int playersAlive = 0;
        int teamsAlive = 0;
        boolean teamKnownAlive[] = new boolean[Player.MAX_TEAMS];
        boolean unteamedAlive = false;
        for (Enumeration e = game.getPlayers(); e.hasMoreElements();) {
            Player player = (Player)e.nextElement();
            int team = player.getTeam();
            if (game.getLiveEntitiesOwnedBy(player) > 0) {
                playersAlive++;
                if (team == Player.TEAM_NONE) {
                    unteamedAlive = true;
                } else if (!teamKnownAlive[team]) {
                    teamsAlive++;
                    teamKnownAlive[team] = true;
                }
            }
        }
        
        return playersAlive <= 1 || (teamsAlive == 1 && !unteamedAlive);
    }

    /**
     * Deploys an entity near a selected point on the board.
     *
     * @param entity the entity to deploy
     * @param pos the point to deploy near
     * @param towards another point that the deployed mechs will face towards
     */
    private boolean deploy(Entity entity, Coords pos, Coords towards, int recurse) {
        if (game.board.contains(pos) && game.getEntity(pos) == null 
                && !entity.isHexProhibited(game.board.getHex(pos))) {
            placeEntity(entity, pos, pos.direction(towards));
            return true;
        }
        
        // if pos is filled, try some different positions
        for (int j = 0; j < recurse; j++) {
            for (int i = 0; i < 6; i++) {
                Coords deployPos = pos.translated(i);
                if (deploy(entity, deployPos, towards, j)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Places a mech on the board
     */
    private void placeEntity(Entity entity, Coords pos, int facing) {
        entity.setPosition(pos);
        entity.setFacing(facing);
        entity.setSecondaryFacing(facing);
    }

    /**
     * Returns the starting point for the specified player
     */
    private Coords getStartingCoords(int startingPos) {
        switch (startingPos) {
        default :
        case 0 :
            return new Coords(1, 1);
        case 1 :
            return new Coords(game.board.width / 2, 1);
        case 2 :
            return new Coords(game.board.width - 2, 1);
        case 3 :
            return new Coords(game.board.width - 2, game.board.height / 2);
        case 4 :
            return new Coords(game.board.width - 2, game.board.height - 2);
        case 5 :
            return new Coords(game.board.width / 2, game.board.height - 2);
        case 6 :
            return new Coords(1, game.board.height - 2);
        case 7 :
            return new Coords(1, game.board.height / 2);
        }
    }

    /**
     * Rolls initiative for all the players.
     */
    private void rollInitiative() {
        roundCounter++;

        // roll the dice for each player
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            player.getInitiative().clear();
        }
        
        // roll off all ties
        resolveInitTies(game.getPlayersVector());
        
        transmitAllPlayerUpdates();
    }
    
    /**
     * This goes thru and adds a roll on to the end of the intiative "stack"
     * for all players involved.  Then it checks the list again for ties, and
     * recursively resolves all further ties.
     */
    private void resolveInitTies(Vector players) {
        // add a roll for all players
        for (Enumeration i = players.elements(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            player.getInitiative().addRoll();
        }
        // check for further ties
        Vector ties = new Vector();
        for (Enumeration i = players.elements(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            ties.removeAllElements();
            ties.addElement(player);
            for (Enumeration j = game.getPlayers(); j.hasMoreElements();) {
                final Player other = (Player)j.nextElement();
                if (player != other && player.getInitiative().equals(other.getInitiative())) {
                    ties.addElement(other);
                }
            }
            if (ties.size() > 1) {
                resolveInitTies(ties);
            }
        }
        
    }

    
    /**
     * Determine turn order by number of entities that are selectable this phase
     *
     * TODO: this is a real mess
     */
    private void determineTurnOrder() {
        // sort players
        com.sun.java.util.collections.ArrayList plist = new com.sun.java.util.collections.ArrayList(game.getNoOfPlayers());
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            plist.add(player);
        }
        com.sun.java.util.collections.Collections.sort(plist, new com.sun.java.util.collections.Comparator() {
            public int compare(Object o1, Object o2) {
                return ((Player)o1).getInitiative().compareTo(((Player)o2).getInitiative());
            }
        });
        
        // determine turn order
        int[] order = new int[game.getNoOfPlayers()];
        int oi = 0;
        for (com.sun.java.util.collections.Iterator i = plist.iterator(); i.hasNext();) {
            final Player player = (Player)i.next();
            order[oi++] = player.getId();
        }
        
        // count how many entities each player controls, and how many turns we have to assign
        int MAX_PLAYERS = 255; //XXX HACK HACK HACK!
        int[] noe = new int[MAX_PLAYERS];
        int noOfTurns = 0;
        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            final Entity entity = (Entity)e.nextElement();
            if (entity.isSelectable()) {
                noe[entity.getOwner().getId()]++;
                noOfTurns++;
            }
        }

        // generate turn list
        turns.setSize(noOfTurns);
        turnIndex = 0;
        while (turnIndex < turns.size()){
            // get lowest number of entities, minimum 1.
            int hnoe = 1;
            int lnoe = Integer.MAX_VALUE;
            for (int i = 0; i < MAX_PLAYERS; i++) {
                if (noe[i] > 0 && noe[i] < lnoe) {
                    lnoe = noe[i];
                }
                if (noe[i] > hnoe) {
                    hnoe = noe[i];
                }
            }
            // cycle through order list
            for (int i = 0; i < order.length; i++) {
                if (noe[order[i]] <= 0) {
                    continue;
                }
                /* if you have less than twice the next lowest,
                 * move 1, otherwise, move more.
                 * if you have less than half the maximum,
                 * move none
                 */
                int ntm = Math.max(1, (int)Math.floor(noe[order[i]] / lnoe));
                for (int j = 0; j < ntm; j++) {
                    turns.setElementAt(new GameTurn(order[i]), turnIndex);
                    turnIndex++;
                    noe[order[i]]--;
                }
            }
        }
        // reset turn counter
        turnIndex = 0;
    }

    /**
     * Write the initiative results to the report
     */
    private void writeInitiativeReport() {
        // write to report
        roundReport.append("\nInitiative Phase for Round #" + roundCounter
                         + "\n------------------------------\n");
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();
            roundReport.append(player.getName() + " rolls a ");
            for (int j = 0; j < player.getInitiative().size(); j++) {
                if (j != 0) {
                    roundReport.append(" / ");
                }
                roundReport.append(player.getInitiative().getRoll(j));
            }
            roundReport.append(".\n");
        }
        roundReport.append("\nThe turn order is:\n  ");
        boolean firstTurn = true;
        for (Enumeration i = turns.elements(); i.hasMoreElements();) {
            GameTurn turn = (GameTurn)i.nextElement();
            roundReport.append((firstTurn ? "" : ", ") + getPlayer(turn.getPlayerNum()).getName());
            firstTurn = false;
        }
        roundReport.append("\n");

        // reset turn index
        turnIndex = 0;
    }

    /**
     * Marks ineligible entities as not ready for this phase
     */
    private void setIneligible(int phase) {
        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            Entity entity = (Entity)e.nextElement();
            if (!isEligibleFor(entity, phase)) {
                entity.ready = false;
            }
        }
    }

    /**
     * Determines if an entity is eligible for a phase.
     */
    private boolean isEligibleFor(Entity entity, int phase) {
        switch (phase) {
        case Game.PHASE_FIRING :
            return isEligibleForFiring(entity, phase);
        case Game.PHASE_PHYSICAL :
            if (entity instanceof Mech) {
                return isEligibleForPhysical(entity, phase);
            }
            else {
                return false;
            }
        default:
            return true;
        }
    }

    /**
     * An entity is eligible if its to-hit number is anything but impossible.
     * This is only really an issue if friendly fire is turned off.
     */
    private boolean isEligibleForFiring(Entity entity, int phase) {
        // if you're charging, no shooting
        if (entity.isCharging() || entity.isMakingDfa()) {
            return false;
        }
        
//        // check game options
//        if (!game.getOptions().booleanOption("skip_ineligable_firing")) {
//            return true;
//        }
        
        // TODO: check for any weapon attacks
        
        return true;
    }

    /**
     * Check if the entity has any valid targets for physical attacks.
     */
    private boolean isEligibleForPhysical(Entity entity, int phase) {
        boolean canHit = false;
        boolean friendlyFire = game.getOptions().booleanOption("friendly_fire");
        
        // if you're charging or finding a club, it's already declared
        if (entity.isCharging() || entity.isMakingDfa() || entity.isFindingClub()) {
            return false;
        }
        
        // check game options
        if (!game.getOptions().booleanOption("skip_ineligable_physical")) {
            return true;
        }

        for (Enumeration e = game.getEntities(); e.hasMoreElements();) {
            Entity target = (Entity)e.nextElement();
                   
            // don't shoot at friendlies unless you are into that sort of thing
            // and do not shoot yourself even then
            if (!(entity.isEnemyOf(target) || (friendlyFire && entity.getId() != target.getId() ))) {
                continue;
            }

            canHit |= Compute.toHitPunch(game, entity.getId(), target.getId(),
                                         PunchAttackAction.LEFT).getValue()
                      != ToHitData.IMPOSSIBLE;

            canHit |= Compute.toHitPunch(game, entity.getId(), target.getId(),
                                         PunchAttackAction.RIGHT).getValue()
                      != ToHitData.IMPOSSIBLE;

            canHit |= Compute.toHitKick(game, entity.getId(), target.getId(),
                                        KickAttackAction.LEFT).getValue()
                      != ToHitData.IMPOSSIBLE;

            canHit |= Compute.toHitKick(game, entity.getId(), target.getId(),
                                        KickAttackAction.RIGHT).getValue()
                      != ToHitData.IMPOSSIBLE;
        }

        return canHit;
    }
    
    /**
     * Steps thru an entity movement packet, executing it.
     */
    private void doEntityMovement(Packet c, int cn) {
        final MovementData md = (MovementData)c.getObject(1);
        // walk thru data, stopping when the mech is out of movement
        final Entity entity = game.getEntity(c.getIntValue(0));
        
        if (entity == null) {
            // what causes this?  --Ben
            return;
        }
        
        if (!entity.ready) {
            // the entity has already moved, apparently
            return;
        }
        
        // check for fleeing
        if (md.contains(MovementData.STEP_FLEE)) {
            phaseReport.append("\n" + entity.getDisplayName()
                       + " flees the battlefield.\n");
            game.moveToGraveyard(entity.getId());
            send(createRemoveEntityPacket(entity.getId()));
            return;
        }
        
        // okay, proceed with movement calculations
        Coords lastPos = entity.getPosition();
        Coords curPos = entity.getPosition();
        int curFacing = entity.getFacing();
        int distance = 0;
        int mpUsed = 0;
        int moveType = Entity.MOVE_NONE;
        int overallMoveType = Entity.MOVE_NONE;
        boolean firstStep;
        boolean wasProne;
        boolean fellDuringMovement;

        Compute.compile(game, entity.getId(), md);

        // get last step's movement type
        for (final Enumeration i = md.getSteps(); i.hasMoreElements();) {
            final MovementData.Step step = (MovementData.Step)i.nextElement();
            if (step.getMovementType() == Entity.MOVE_ILLEGAL) {
                break;
            } else {
                overallMoveType = step.getMovementType();
            }
        }

        // iterate through steps
        firstStep = true;
        fellDuringMovement = false;
        for (final Enumeration i = md.getSteps(); i.hasMoreElements();) {
            final MovementData.Step step = (MovementData.Step)i.nextElement();
            wasProne = entity.isProne();

            // stop for illegal movement
            if (step.getMovementType() == Entity.MOVE_ILLEGAL) {
                break;
            }

            // check piloting skill for getting up
            if (step.getType() == MovementData.STEP_GET_UP) {
                entity.heatBuildup += 1;
                entity.setProne(false);
                wasProne = false;
                doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), 0, "getting up"), true);
            } else if (firstStep) {
                // running with destroyed hip or gyro needs a check
                if (overallMoveType == Entity.MOVE_RUN
                        && (entity.getDestroyedCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO,Mech.LOC_CT) > 0
                            || entity.hasHipCrit())) {
                    doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), 0, "running with damaged hip actuator or gyro"), false);
                }
                firstStep = false;
            }

            // did the entity just fall?
            if (!wasProne && entity.isProne()) {
                moveType = step.getMovementType();
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                break;
            }
            
            // check for charge
            if (step.getType() == MovementData.STEP_CHARGE) {
                Entity target = game.getEntity(step.getPosition());

                distance = step.getDistance();

                ChargeAttackAction caa = new ChargeAttackAction(entity.getId(), target.getId(), target.getPosition());
                entity.setDisplacementAttack(caa);
                pendingCharges.addElement(caa);
                break;
            }

            // check for dfa
            if (step.getType() == MovementData.STEP_DFA) {
                Entity target = game.getEntity(step.getPosition());

                distance = step.getDistance();
 
                DfaAttackAction daa = new DfaAttackAction(entity.getId(), target.getId(), target.getPosition());
                entity.setDisplacementAttack(daa);
                pendingCharges.addElement(daa);
                break;
            }

            // step...
            moveType = step.getMovementType();
            curPos = step.getPosition();
            curFacing = step.getFacing();
            distance = step.getDistance();
            mpUsed = step.getMpUsed();

            final Hex curHex = game.board.getHex(curPos);

            // check if we've moved into rubble
            if (!lastPos.equals(curPos)
                    && step.getMovementType() != Entity.MOVE_JUMP
                    && curHex.levelOf(Terrain.RUBBLE) > 0) {
                doSkillCheckWhileMoving(entity, lastPos, curPos, new PilotingRollData(entity.getId(), 0, "entering Rubble"));
            }

            // check if we've moved into water
            if (!lastPos.equals(curPos)
                    && step.getMovementType() != Entity.MOVE_JUMP
                    && curHex.levelOf(Terrain.WATER) > 0
                    && entity.getMovementType() != Entity.MovementType.HOVER) {
                if (curHex.levelOf(Terrain.WATER) == 1) {
                    doSkillCheckWhileMoving(entity, lastPos, curPos, new PilotingRollData(entity.getId(), -1, "entering Depth 1 Water"));
                } else if (curHex.levelOf(Terrain.WATER) == 2) {
                    doSkillCheckWhileMoving(entity, lastPos, curPos, new PilotingRollData(entity.getId(), 0, "entering Depth 2 Water"));
                } else {
                    doSkillCheckWhileMoving(entity, lastPos, curPos, new PilotingRollData(entity.getId(), 1, "entering Depth 3+ Water"));
                }
            }

            // did the entity just fall?
            if (!wasProne && entity.isProne()) {
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                break;
            }

            // update lastPos
            lastPos = new Coords(curPos);
        }

        // set entity parameters
        entity.setPosition(curPos);
        entity.setFacing(curFacing);
        entity.setSecondaryFacing(curFacing);
        entity.delta_distance = distance;
        entity.moved = moveType;
        entity.mpUsed = mpUsed;

        // but the danger isn't over yet!  landing from a jump can be risky!
        if (overallMoveType == Entity.MOVE_JUMP && !entity.isMakingDfa()) {
            // check for damaged criticals
            if (entity.getDestroyedCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO,Mech.LOC_CT) > 0 || entity.hasLegActuatorCrit()) {
                doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), 0, "landing with damaged leg actuator or gyro"), false);
            }
            // jumped into water?
            int waterLevel = game.board.getHex(curPos).levelOf(Terrain.WATER);
            if (waterLevel == 1) {
                doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), -1, "entering Depth 1 Water"), false);
            } else if (waterLevel == 2) {
                doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), 0, "entering Depth 2 Water"), false);
            } else if (waterLevel >= 3) {
                doSkillCheckInPlace(entity, new PilotingRollData(entity.getId(), 1, "entering Depth 3+ Water"), false);
            }            
        }

        // build up heat from movement
        if (moveType == Entity.MOVE_WALK) {
            entity.heatBuildup += 1;
        } else if (moveType == Entity.MOVE_RUN) {
            entity.heatBuildup += 2;
        } else if (moveType == Entity.MOVE_JUMP) {
            entity.heatBuildup += Math.max(3, distance);
        }

        // should we give another turn to the entity to keep moving?
        if (fellDuringMovement && entity.mpUsed < entity.getRunMP() && entity.isSelectable()) {
            entity.ready = true;
            turns.insertElementAt(new GameTurn(entity.getOwner().getId(), entity.getId()), turnIndex);
        } else {
            entity.ready = false;
        }

        // duhh.. send an outgoing packet to everybody
        //send(createEntityPacket(entity.getId()));
        entityUpdate(entity.getId());
    }

    /**
     * Do a piloting skill check while standing still (during the movement phase).
     * We have a special case for getting up because quads need not roll to stand
     * if they have no damaged legs.  If a quad is short a gyro, however....
     */
    private void doSkillCheckInPlace(Entity entity, PilotingRollData reason, boolean gettingUp) {        
        // non-mechs should never get here
        if (! (entity instanceof Mech)) {
            return;
        }
        
        if (gettingUp && !entity.needsRollToStand() && (entity.getDestroyedCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO,Mech.LOC_CT) < 2)) {
            phaseReport.append("\n" + entity.getDisplayName() + " does not need to make "
            + "a piloting skill check to stand up because it has all four of its legs.");
            return;
        }
        final PilotingRollData roll = Compute.getBasePilotingRoll(game, entity.getId());
        
        // append the reason modifier
        roll.append(reason);
        
        // okay, print the info
        phaseReport.append("\n" + entity.getDisplayName()
        + " must make a piloting skill check (" + reason.getPlainDesc() + ")"
        + ".\n");
        // roll
        final int diceRoll = Compute.d6(2);
        phaseReport.append("Needs " + roll.getValueAsString()
        + " [" + roll.getDesc() + "]"
        + ", rolls " + diceRoll + " : ");
        if (diceRoll < roll.getValue()) {
            phaseReport.append("falls.\n");
            doEntityFall(entity, roll);
        } else {
            phaseReport.append("succeeds.\n");
        }

    }

    /**
     * Do a piloting skill check while moving
     */
    private void doSkillCheckWhileMoving(Entity entity, Coords src, Coords dest,
                                         PilotingRollData reason) {
        // non mechs should never get here
        if (! (entity instanceof Mech)) {
            return;
        }
        
        final PilotingRollData roll = Compute.getBasePilotingRoll(game, entity.getId());
        final Hex srcHex = game.board.getHex(src);
        final Hex destHex = game.board.getHex(dest);
        boolean fallsInPlace;
        int fallElevation;

        // append the reason modifier
        roll.append(reason);

        // will the entity fall in the source or destination hex?
        if (src.equals(dest) || srcHex.floor() < destHex.floor()) {
            fallsInPlace = true;
        } else {
            fallsInPlace = false;
        }

        // how far down did it fall?
        fallElevation = Math.abs(destHex.floor() - srcHex.floor());
        
        // okay, print the info
        phaseReport.append("\n" + entity.getDisplayName()
                + " must make a piloting skill check"
                + " while moving from hex " + src.getBoardNum()
                + " to hex " + dest.getBoardNum()
                + " (" + reason.getPlainDesc() + ")" + ".\n");
        // roll
        final int diceRoll = Compute.d6(2);
        phaseReport.append("Needs " + roll.getValueAsString()
                + " [" + roll.getDesc() + "]"
                + ", rolls " + diceRoll + " : ");
        if (diceRoll < roll.getValue()) {
            phaseReport.append("falls.\n");
            doEntityFallsInto(entity, (fallsInPlace ? dest : src), (fallsInPlace ? src : dest), roll);
        } else {
            phaseReport.append("succeeds.\n");
        }
    }

    /**
     * The entity falls into the hex specified.  Check for any conflicts and
     * resolve them.  Deal damage to faller.
     */
    private void doEntityFallsInto(Entity entity, Coords src, Coords dest, PilotingRollData roll) {
        final Hex srcHex = game.board.getHex(src);
        final Hex destHex = game.board.getHex(dest);
        final int fallElevation = Math.abs(destHex.floor() - srcHex.floor());
        int direction = src.direction(dest);
        // check entity in target hex
        Entity target = game.getEntity(dest);
        // check if we can fall in that hex
        if (target != null && !target.equals(entity)
            && !Compute.isValidDisplacement(game, target.getId(), src, dest)) {
            // if target can't be displaced, fall in source hex.
            // NOTE: source hex should never contain a non-displacable entity
            Coords temp = dest;
            dest = src;
            src = temp;
            target = game.getEntity(dest);
        }

        // falling mech falls
        phaseReport.append(entity.getDisplayName() + " falls "
                + fallElevation + " level(s) into hex "
                + dest.getBoardNum() + ".\n");

        // if hex was empty, deal damage and we're done
        if (target == null || target.equals(entity)) {
            doEntityFall(entity, dest, fallElevation, roll);
            return;
        }

        // hmmm... somebody there... problems.
        if (fallElevation >= 2) {
            // accidental death from above
        } else {
            // damage as normal
            doEntityFall(entity, dest, fallElevation, roll);
            // target gets displaced
            doEntityDisplacement(target, dest, dest.translated(direction), new PilotingRollData(target.getId(), 0, "domino effect"));
        }
    }

    /**
     * Displace a unit in the direction specified.  The unit moves in that
     * direction, and the piloting skill roll is used to determine if it
     * falls.  The roll may be unnecessary as certain situations indicate an
     * automatic fall.  Rolls are added to the piloting roll list.
     */
    private void doEntityDisplacement(Entity entity, Coords src, Coords dest,
                                      PilotingRollData roll) {
        final Hex srcHex = game.board.getHex(src);
        final Hex destHex = game.board.getHex(dest);
        final int direction = src.direction(dest);
        int fallElevation = entity.elevationOccupied(srcHex) - entity.elevationOccupied(destHex);
        Entity target = game.getEntity(dest);

        // can't fall upwards
        if (fallElevation < 0) {
            fallElevation = 0;
        }

        // if destination is empty, this could be easy...
        if (target == null || target.equals(entity)) {
            if (fallElevation < 2) {
                // no cliff: move and roll normally
                phaseReport.append(entity.getDisplayName()
                           + " is displaced into hex "
                           + dest.getBoardNum() + ".\n");
                entity.setPosition(dest);
                if (roll != null) {
                    pilotRolls.addElement(roll);
                }
                return;
            } else {
                // cliff: fall off it, deal damage, prone immediately
                phaseReport.append(entity.getDisplayName() + " falls "
                           + fallElevation + " levels into hex "
                           + dest.getBoardNum() + ".\n");
                // only given a modifier, so flesh out into a full piloting roll
                PilotingRollData pilotRoll = Compute.getBasePilotingRoll(game, entity.getId());
                if (roll != null) {
                    pilotRoll.append(roll);
                }
                doEntityFall(entity, dest, fallElevation, pilotRoll);
                return;
            }
        }

        // okay, destination occupied.  hmmm...
        System.err.println("server.doEntityDisplacement: destination occupied");
        if (fallElevation < 2) {
            // domino effect: move & displace target
            phaseReport.append(entity.getDisplayName()
                           + " is displaced into hex "
                           + dest.getBoardNum() + ", occupied by "
                           + target.getDisplayName() + ".\n");
            entity.setPosition(dest);
            if (roll != null) {
                pilotRolls.addElement(roll);
            }
            doEntityDisplacement(target, dest, dest.translated(direction), new PilotingRollData(target.getId(), 0, "domino effect"));
            return;
        } else {
            // accidental fall from above: havoc!
            phaseReport.append(entity.getDisplayName() + " falls "
                           + fallElevation + " levels into hex "
                           + dest.getBoardNum() + ", occupied by "
                           + target.getDisplayName() + ".\n");

            // determine to-hit number
            ToHitData toHit = new ToHitData(7, "base");
            toHit.append(Compute.getTargetMovementModifier(game, target.getId()));
            toHit.append(Compute.getTargetTerrainModifier(game, target.getId()));

            // roll dice
            final int diceRoll = Compute.d6(2);
            phaseReport.append("Collision occurs on a " + toHit.getValue()
                           + " or greater.  Rolls " + diceRoll);
            if (diceRoll >= toHit.getValue()) {
                phaseReport.append(", hits!\n");
                // deal damage to target
                int damage = (int)Math.ceil(entity.getWeight() / 10);
                phaseReport.append(target.getDisplayName() + " takes "
                                   + damage + " from the collision.");
                while (damage > 0) {
                    int cluster = Math.min(5, damage);
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_PUNCH, ToHitData.SIDE_FRONT);
                    phaseReport.append(damageEntity(target, hit, cluster));
                    damage -= cluster;
                }
                phaseReport.append("\n");

                // attacker falls as normal, on his back
                // only given a modifier, so flesh out into a full piloting roll
                PilotingRollData pilotRoll = Compute.getBasePilotingRoll(game, entity.getId());
                pilotRoll.append(roll);
                doEntityFall(entity, dest, fallElevation, 3, pilotRoll);

                // defender pushed away, or destroyed
                Coords targetDest = Compute.getValidDisplacement(game, target.getId(), dest, direction);
                if (targetDest != null) {
                    doEntityDisplacement(target, dest, targetDest, new PilotingRollData(target.getId(), 2, "fallen on"));
                } else {
                    // ack!  automatic death!
                    phaseReport.append("*** " + target.getDisplayName()
                                       + " DESTROYED due to impossible displacement! ***");
                    target.setDoomed(true);
                }
            } else {
                phaseReport.append(", misses.\n");
                //TODO: this is not quite how the rules go
                Coords targetDest = Compute.getValidDisplacement(game, entity.getId(), dest, direction);
                if (targetDest != null) {
                    doEntityDisplacement(entity, src, targetDest, new PilotingRollData(entity.getId(), PilotingRollData.IMPOSSIBLE, "pushed off a cliff"));
                } else {
                    // ack!  automatic death!
                    phaseReport.append("*** " + entity.getDisplayName()
                                       + " DESTROYED due to impossible displacement! ***");
                    entity.setDoomed(true);
                }
            }
            return;
        }
    }

    /**
     * Gets a bunch of entity actions from the packet
     */
    private void receiveAttack(Packet pkt) {
        Vector vector = (Vector)pkt.getObject(1);
        Entity entity = game.getEntity(pkt.getIntValue(0));
        for (Enumeration i = vector.elements(); i.hasMoreElements();) {
            EntityAction ea = (EntityAction)i.nextElement();

            // move push attacks the end of the displacement attacks
            if (ea instanceof PushAttackAction) {
                PushAttackAction paa = (PushAttackAction)ea;
                entity.setDisplacementAttack(paa);
                pendingCharges.addElement(paa);
            } else {
                // add to the normal attack list.
                attacks.addElement(ea);
            }
            
            // if torso twist, twist so that everybody can see it later
            if (ea instanceof TorsoTwistAction) {
                TorsoTwistAction tta = (TorsoTwistAction)ea;
                game.getEntity(tta.getEntityId()).setSecondaryFacing(tta.getFacing());
            } else if (ea instanceof FlipArmsAction) {
                FlipArmsAction faa = (FlipArmsAction)ea;
                game.getEntity(faa.getEntityId()).setArmsFlipped(faa.getIsFlipped());
            }
            

            // send an outgoing packet to everybody
            send(createAttackPacket(ea));
        }
        //send(createEntityPacket(entity.getId()));
        entityUpdate(entity.getId());
    }

    /**
     * Resolve all fire for the round
     */
    private void resolveWeaponAttacks() {
        roundReport.append("\nWeapon Attack Phase\n-------------------\n");

        int cen = Entity.NONE;

        // loop thru received attack actions
        for (Enumeration i = attacks.elements(); i.hasMoreElements();) {
            Object o = i.nextElement();
            Entity entity = game.getEntity(((EntityAction)o).getEntityId());
            if (o instanceof WeaponAttackAction) {
                WeaponAttackAction waa = (WeaponAttackAction)o;
                resolveWeaponAttack(waa, cen);
                cen = waa.getEntityId();
            } else if (o instanceof TorsoTwistAction) {
                TorsoTwistAction tta = (TorsoTwistAction)o;
                game.getEntity(tta.getEntityId()).setSecondaryFacing(tta.getFacing());
            } else if (o instanceof FlipArmsAction) {
                FlipArmsAction faa = (FlipArmsAction)o;
                game.getEntity(faa.getEntityId()).setArmsFlipped(faa.getIsFlipped());
	    } else if (o instanceof FiringModeChangeAction) {
		// Fire Mode - Handles pulling the FireModeChangeActions out of the attack Vector
		FiringModeChangeAction fmc = (FiringModeChangeAction)o;
		final Entity fe = game.getEntity(fmc.getEntityId());
	        final Mounted fmcweapon = fe.getEquipment(fmc.getWeaponId());
		fmcweapon.setFiringMode(fmc.getFiringMode());
            } else if (o instanceof FindClubAction) {
                FindClubAction fca = (FindClubAction)o;
                entity.setFindingClub(true);
                try {
                    entity.addEquipment(EquipmentType.getByInternalName("Tree Club"), Mech.LOC_NONE);
                } catch (LocationFullException ex) {
                    // unlikely...
                }
                phaseReport.append("\n" + entity.getDisplayName() + " uproots a tree for use as a club.\n");
            } else {
                // hmm, error
            }
        }
        
        // and clear the attacks Vector
        attacks.removeAllElements();
    }
    
    /**
     * Resolve a single Weapon Attack object
     */
    private void resolveWeaponAttack(WeaponAttackAction waa, int lastEntityId) {
        final Entity ae = game.getEntity(waa.getEntityId());
        final Entity te = game.getEntity(waa.getTargetId());
        final Mounted weapon = ae.getEquipment(waa.getWeaponId());
        final WeaponType wtype = (WeaponType)weapon.getType();
        final boolean usesAmmo = wtype.getAmmoType() != AmmoType.T_NA;
        Mounted ammo = null;
        if (usesAmmo) {
            if (waa.getAmmoId() > -1) {
                ammo = ae.getEquipment(waa.getAmmoId());
            }
            else {
                ammo = weapon.getLinked();
            }
        }
        final AmmoType atype = ammo == null ? null : (AmmoType)ammo.getType();
                

        if (lastEntityId != waa.getEntityId()) {
            phaseReport.append("\nWeapons fire for " + ae.getDisplayName() + "\n");
        }

        phaseReport.append("    " + wtype.getName() + " at " + te.getDisplayName());
        
        // has this weapon fired already?
        if (weapon.isUsedThisRound()) {
            phaseReport.append(" but the weapon has already fired this round!\n");
            return;
        }

        // is the weapon functional?
        if (weapon.isDestroyed()) {
            phaseReport.append(" but the weapon has been destroyed in a previous round!\n");
            return;
        }

        // try reloading
        if (usesAmmo && ammo != null && ammo.getShotsLeft() == 0) {
            ae.loadWeapon(weapon);
            ammo = weapon.getLinked();
        }

        // should we even bother?
        if (te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append(" but the target is already destroyed!\n");
            return;
        }
        
        // compute to-hit
        ToHitData toHit = Compute.toHitWeapon(game, waa, attacks);
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            phaseReport.append(", but the shot is impossible (" + toHit.getDesc() + ")\n");
            return;
        } else if (toHit.getValue() == ToHitData.AUTOMATIC_FAIL) {
            phaseReport.append(", the shot is an automatic miss (" + toHit.getDesc() + "), ");
        } else {
            phaseReport.append("; needs " + toHit.getValue() + ", ");
        }
        

        // use up ammo (if weapon is out of ammo, we shouldn't be here)
        if (usesAmmo) {
            ammo.setShotsLeft(ammo.getShotsLeft() - 1);
                /*
                Fire Mode - Takes 2 shots of ammo away if we are firing uAC Double Rate.
                If you don't have enough ammo for a Double Rate shot, you can't do it!
                Rasia
                */
                if (wtype.getAmmoType() == AmmoType.T_AC_ULTRA && ammo != null && weapon.getFiringMode() == 2) {
                        if (ammo.getShotsLeft() <= 0) {
      				ae.loadWeapon(weapon);
      				ammo = weapon.getLinked();                              
				if (ammo.getShotsLeft() <= 0) {
					weapon.setFiringMode(1);
			                phaseReport.append("(Out of Ammo, Single Rate Only):");
			         } else {
			         	ammo.setShotsLeft(ammo.getShotsLeft() - 1);
			         	}
			         
                        } else {
                            ammo.setShotsLeft(ammo.getShotsLeft() - 1);
                        }       
                }
        }

        // build up some heat
        ae.heatBuildup += wtype.getHeat();
	if (wtype.getAmmoType() == AmmoType.T_AC_ULTRA && weapon.getFiringMode() == 2) // Fire Mode - If we are shooting an Ultra AC twice
		ae.heatBuildup += wtype.getHeat();
		
        // set the weapon as having fired
        weapon.setUsedThisRound(true);


        // roll
        int roll = Compute.d6(2);
        phaseReport.append("rolls " + roll + " : ");

        if (roll == 2 && ammo != null && wtype.getAmmoType() == AmmoType.T_AC_ULTRA && weapon.getFiringMode() == 2) {
        	phaseReport.append("misses AND THE AUTOCANNON JAMS.\n");
    	        weapon.setHit(true);
    	        return;
    	}

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            phaseReport.append("misses.\n");
            if (wtype.getAmmoType() == AmmoType.T_SRM_STREAK) {
                ae.heatBuildup -= wtype.getHeat();
                ammo.setShotsLeft(ammo.getShotsLeft() + 1);
                phaseReport.append("    Streak fails to achieve lock on target.\n");
            }
            return;
        }
        
                // Fire Mode - If we roll 2 and shooting uAC twice, it Jams
        if (roll < 3 && ammo != null && wtype.getAmmoType() == AmmoType.T_AC_ULTRA && weapon.getFiringMode() == 2) {
        	phaseReport.append("misses AND THE AUTOCANNON JAMS.\n");
    	        weapon.setHit(true);
    	        return;
    	}
        
        if (wtype.getDamage() == WeaponType.DAMAGE_MISSILE || (ammo != null && wtype.getAmmoType() == AmmoType.T_AC_ULTRA && weapon.getFiringMode() == 2) || (ammo != null && atype.hasFlag(AmmoType.F_CLUSTER))) {
            if (ammo != null) {
                // missiles; determine number of missiles hitting
                int hits;
                if (wtype.getAmmoType() == AmmoType.T_SRM_STREAK) {
                    hits = wtype.getRackSize();
                } else if (wtype.getRackSize() == 30 || wtype.getRackSize() == 40) {
                    // I'm going to assume these are MRMs
                    hits = Compute.missilesHit(wtype.getRackSize() / 2) + Compute.missilesHit(wtype.getRackSize() / 2);
                } else if (wtype.getAmmoType() == AmmoType.T_AC_ULTRA) {
                    hits = Compute.missilesHit(2);
                } else {
                    hits = Compute.missilesHit(wtype.getRackSize());
                }
                phaseReport.append(hits + " missiles hit" + toHit.getTableDesc() + ".");
                if (wtype.getAmmoType() == AmmoType.T_SRM || wtype.getAmmoType() == AmmoType.T_SRM_STREAK) {
                    // for SRMs, do each missile seperately
                    for (int j = 0; j < hits; j++) {
                        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                        phaseReport.append(damageEntity(te, hit, 2));
                    }
                } else if (atype.hasFlag(AmmoType.F_CLUSTER)) {
                    // for LBX cluster ammo
                    for (int j = 0; j < hits; j++) {
                        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                        phaseReport.append(damageEntity(te, hit, 1));
                    }
		} else if (wtype.getAmmoType() == AmmoType.T_AC_ULTRA) {
			// Fire Mode - uAC ammo
                    for (int j = 0; j < hits; j++) {
                        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                        phaseReport.append(damageEntity(te, hit, wtype.getDamage()));

                    }
                } else if (wtype.getAmmoType() == AmmoType.T_LRM || wtype.getAmmoType() == AmmoType.T_MRM) {
                    // LRMs, MRMs do salvos of 5
                    while (hits > 0) {
                        int salvo = Math.min(5, hits);
                        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                        phaseReport.append(damageEntity(te, hit, salvo));
                        hits -= salvo;
                    }
                }
            }
        } else if (wtype.hasFlag(WeaponType.F_FLAMER) && game.getOptions().booleanOption("flamer_heat")) {
            phaseReport.append("hits; target gains 2 more heat during heat phase.");
            te.heatBuildup += 2;
        } else {
            // normal weapon; deal damage
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            phaseReport.append("hits" + toHit.getTableDesc() + " " + te.getLocationAbbr(hit));
            phaseReport.append(damageEntity(te, hit, wtype.getDamage()));
        }

        phaseReport.append("\n");
    }
    
    /**
     * Handle all physical attacks for the round
     */
    private void resolvePhysicalAttacks() {
        roundReport.append("\nPhysical Attack Phase\n-------------------\n");

        int cen = Entity.NONE;
        
        // add any pending charges
        for (Enumeration i = pendingCharges.elements(); i.hasMoreElements();) {
            attacks.addElement(i.nextElement());
        }
        pendingCharges.removeAllElements();
        
        // remove any duplicate attack declarations
        cleanupPhysicalAttacks();

        // loop thru received attack actions
        for (Enumeration i = attacks.elements(); i.hasMoreElements();) {
            Object o = i.nextElement();
            if (o instanceof PunchAttackAction) {
                PunchAttackAction paa = (PunchAttackAction)o;
                if (paa.getArm() == PunchAttackAction.BOTH) {
                    paa.setArm(PunchAttackAction.LEFT);
                    resolvePunchAttack(paa, cen);
                    cen = paa.getEntityId();
                    paa.setArm(PunchAttackAction.RIGHT);
                    resolvePunchAttack(paa, cen);
                } else {
                    resolvePunchAttack(paa, cen);
                    cen = paa.getEntityId();
                }
            } else if (o instanceof KickAttackAction) {
                KickAttackAction kaa = (KickAttackAction)o;
                resolveKickAttack(kaa, cen);
                cen = kaa.getEntityId();
            } else if (o instanceof ClubAttackAction) {
                ClubAttackAction caa = (ClubAttackAction)o;
                resolveClubAttack(caa, cen);
                cen = caa.getEntityId();
            } else if (o instanceof PushAttackAction) {
                PushAttackAction paa = (PushAttackAction)o;
                resolvePushAttack(paa, cen);
                cen = paa.getEntityId();
            }  else if (o instanceof ChargeAttackAction) {
                ChargeAttackAction caa = (ChargeAttackAction)o;
                resolveChargeAttack(caa, cen);
                cen = caa.getEntityId();
            }  else if (o instanceof DfaAttackAction) {
                DfaAttackAction daa = (DfaAttackAction)o;
                resolveDfaAttack(daa, cen);
                cen = daa.getEntityId();
            } else {
                // hmm, error.
            }
        }
    }
    
    /**
     * Cleans up the attack declarations for the physical phase by removing
     * all attacks past the first for any one mech.  Also clears out attacks
     * by dead or disabled mechs.
     */
    private void cleanupPhysicalAttacks() {
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            Entity entity = (Entity)i.nextElement();
            removeDuplicateAttacks(entity.getId());
        }
        removeDeadAttacks();
    }
    
    /**
     * Removes any actions in the attack queue beyond the first by the 
     * specified entity.
     */
    private void removeDuplicateAttacks(int entityId) {
        boolean attacked = false;
        Vector toKeep = new Vector(attacks.size());
        
        for (Enumeration i = attacks.elements(); i.hasMoreElements();) {
            EntityAction action = (EntityAction)i.nextElement();
            if (action.getEntityId() != entityId) {
                toKeep.addElement(action);
            } else if (!attacked) {
                toKeep.addElement(action);
                attacked = true;
            } else {
                System.err.println("server: removing duplicate phys attack for id#" + entityId);
            }
        }
        
        attacks = toKeep;
    }
    
    /**
     * Removes all attacks by any dead entities.  It does this by going through
     * all the attacks and only keeping ones from active entities.
     */
    private void removeDeadAttacks() {
        Vector toKeep = new Vector(attacks.size());
        
        for (Enumeration i = attacks.elements(); i.hasMoreElements();) {
            EntityAction action = (EntityAction)i.nextElement();
            if (game.getEntity(action.getEntityId()).isActive()) {
                toKeep.addElement(action);
            }
        }
        
        attacks = toKeep;
    }

    /**
     * Handle a punch attack
     */
    private void resolvePunchAttack(PunchAttackAction paa, int lastEntityId) {
        final Entity ae = game.getEntity(paa.getEntityId());
        final Entity te = game.getEntity(paa.getTargetId());
        final String armName = paa.getArm() == PunchAttackAction.LEFT
                               ? "Left Arm" : "Right Arm";

        if (lastEntityId != paa.getEntityId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        phaseReport.append("    Punch (" +armName + ") at " + te.getDisplayName());

        // should we even bother?
        if (te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append(" but the target is already destroyed!\n");
            return;
        }
        // compute to-hit
        ToHitData toHit = Compute.toHitPunch(game, paa);
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            phaseReport.append(", but the punch is impossible (" + toHit.getDesc() + ")\n");
            return;
        }
        phaseReport.append("; needs " + toHit.getValue() + ", ");

        // roll
        int roll = Compute.d6(2);
        phaseReport.append("rolls " + roll + " : ");

        // do we hit?
        if (roll < toHit.getValue()) {
            phaseReport.append("misses.\n");
            return;
        }
        int damage = Compute.getPunchDamageFor(ae, paa.getArm());

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        phaseReport.append("hits" + toHit.getTableDesc() + " " + te.getLocationAbbr(hit));
        phaseReport.append(damageEntity(te, hit, damage));

        phaseReport.append("\n");
    }

    /**
     * Handle a kick attack
     */
    private void resolveKickAttack(KickAttackAction kaa, int lastEntityId) {
        final Entity ae = game.getEntity(kaa.getEntityId());
        final Entity te = game.getEntity(kaa.getTargetId());
        final String legName = kaa.getLeg() == KickAttackAction.LEFT 
                                ? "Left Leg" 
                                : "Right Leg";

        if (lastEntityId != ae.getId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        phaseReport.append("    Kick (" + legName + ") at " + te.getDisplayName());

        // should we even bother?
        if (te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append(" but the target is already destroyed!\n");
            return;
        }
        // compute to-hit
        ToHitData toHit = Compute.toHitKick(game, kaa);
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            phaseReport.append(", but the kick is impossible (" + toHit.getDesc() + ")\n");
            pilotRolls.addElement(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            return;
        }
        phaseReport.append("; needs " + toHit.getValue() + ", ");

        // roll
        int roll = Compute.d6(2);
        phaseReport.append("rolls " + roll + " : ");

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            phaseReport.append("misses.\n");
            pilotRolls.addElement(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            return;
        }
        
        int damage = Compute.getKickDamageFor(ae, kaa.getLeg());

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        phaseReport.append("hits" + toHit.getTableDesc() + " " + te.getLocationAbbr(hit));
        phaseReport.append(damageEntity(te, hit, damage));

        if (te.getMovementType() == Entity.MovementType.BIPED || te.getMovementType() == Entity.MovementType.QUAD) {
            pilotRolls.addElement(new PilotingRollData(te.getId(), 0, "was kicked"));
        }

        phaseReport.append("\n");
    }

    /**
     * Handle a punch attack
     */
    private void resolveClubAttack(ClubAttackAction caa, int lastEntityId) {
        final Entity ae = game.getEntity(caa.getEntityId());
        final Entity te = game.getEntity(caa.getTargetId());
        
        // restore club attack
        caa.getClub().restore();

        if (lastEntityId != caa.getEntityId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        phaseReport.append("    " + caa.getClub().getName() + " attack on " + te.getDisplayName());

        // should we even bother?
        if (te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append(" but the target is already destroyed!\n");
            return;
        }
        // compute to-hit
        ToHitData toHit = Compute.toHitClub(game, caa);
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            phaseReport.append(", but the attack is impossible (" + toHit.getDesc() + ")\n");
            return;
        }
        phaseReport.append("; needs " + toHit.getValue() + ", ");

        // roll
        int roll = Compute.d6(2);
        phaseReport.append("rolls " + roll + " : ");

        // do we hit?
        if (roll < toHit.getValue()) {
            phaseReport.append("misses.\n");
            return;
        }
        int damage = Compute.getClubDamageFor(ae, caa.getClub());

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        phaseReport.append("hits" + toHit.getTableDesc() + " " + te.getLocationAbbr(hit));
        phaseReport.append(damageEntity(te, hit, damage));

        phaseReport.append("\n");
        
        if (caa.getClub().getType().hasFlag(MiscType.F_TREE_CLUB)) {
            phaseReport.append("The " + caa.getClub().getName() + " breaks.\n");
            ae.removeMisc(caa.getClub().getName());
        }
    }
    
    /**
     * Handle a push attack
     */
    private void resolvePushAttack(PushAttackAction paa, int lastEntityId) {
        final Entity ae = game.getEntity(paa.getEntityId());
        final Entity te = game.getEntity(paa.getTargetId());

        if (lastEntityId != paa.getEntityId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        phaseReport.append("    Pushing " + te.getDisplayName());

        // should we even bother?
        if (te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append(" but the target is already destroyed!\n");
            return;
        }

        // compute to-hit
        ToHitData toHit = Compute.toHitPush(game, paa);
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            phaseReport.append(", but the push is impossible (" + toHit.getDesc() + ")\n");
            return;
        }
        phaseReport.append("; needs " + toHit.getValue() + ", ");

        // roll
        int roll = Compute.d6(2);
        phaseReport.append("rolls " + roll + " : ");

        // do we hit?
        if (roll < toHit.getValue()) {
            phaseReport.append("misses.\n");
            return;
        }

        // we hit...
        int direction = ae.getFacing();

        Coords src = te.getPosition();
        Coords dest = src.translated(direction);    
        
        if (Compute.isValidDisplacement(game, te.getId(), te.getPosition(), direction)) {
            phaseReport.append("succeeds: target is pushed into hex "
                               + dest.getBoardNum()
                               + "\n");
            doEntityDisplacement(te, src, dest, new PilotingRollData(te.getId(), 0, "was pushed"));

            // if push actually moved the target, attacker follows thru
            if (game.getEntity(src) == null) {
                ae.setPosition(src);
            }
        } else {
          if (game.getOptions().booleanOption("push_off_board") && !game.board.contains(dest)) {
            game.moveToGraveyard(te.getId());
            send(createRemoveEntityPacket(te.getId()));
            phaseReport.append("\n*** " + te.getDisplayName() + " has been forced from the field. ***\n");
            // if push actually moved the target, attacker follows thru
            if (game.getEntity(src) == null) {
                ae.setPosition(src);
            }
          } else {
            phaseReport.append("succeeds, but target can't be moved.\n");
            pilotRolls.addElement(new PilotingRollData(te.getId(), 0, "was pushed"));
          }
        }


        phaseReport.append("\n");
    }

    /**
     * Handle a charge attack
     */
    private void resolveChargeAttack(ChargeAttackAction caa, int lastEntityId) {
        final Entity ae = game.getEntity(caa.getEntityId());
        final Entity te = game.getEntity(caa.getTargetId());
        
        // is the attacker dead?  because that sure messes up the calculations
        if (ae == null) {
            return;
        }
        
        final int direction = ae.getFacing();

        // entity isn't charging any more
        ae.setDisplacementAttack(null);

        if (lastEntityId != caa.getEntityId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        // should we even bother?
        if (te == null || te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append("    Charge cancelled as the target has been destroyed.\n");
            return;
        }

        // attacker fell down?
        if (ae.isProne()) {
            phaseReport.append("    Charge cancelled as the attacker has fallen.\n");
            return;
        }

        phaseReport.append("    Charging " + te.getDisplayName());
        
        // target still in the same position?
        if (!te.getPosition().equals(caa.getTargetPos())) {
            phaseReport.append(" but the target has moved.\n");
            return;
        }
        
        // compute to-hit
        ToHitData toHit = Compute.toHitCharge(game, caa);

        // if the attacker's prone, fudge the roll
        int roll;
        if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            roll = -12;
            phaseReport.append(", but the charge is impossible (" + toHit.getDesc() + ") : ");
        } else {
            // roll
            roll = Compute.d6(2);
            phaseReport.append("; needs " + toHit.getValue() + ", ");
            phaseReport.append("rolls " + roll + " : ");
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            Coords src = ae.getPosition();
            Coords dest = Compute.getMissedChargeDisplacement(game, ae.getId(), src, direction);
            phaseReport.append("misses.\n");
            // move attacker to side hex
            doEntityDisplacement(ae, src, dest, null);
            return;
        }

        // we hit...
        int damage = Compute.getChargeDamageFor(ae);
        int damageTaken = Compute.getChargeDamageTakenBy(ae, te);

        phaseReport.append("hits.");
        phaseReport.append("\n  Defender takes " + damage + " damage" + toHit.getTableDesc() + ".");
        while (damage > 0) {
            int cluster = Math.min(5, damage);
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            phaseReport.append(damageEntity(te, hit, cluster));
            damage -= cluster;
        }
        phaseReport.append("\n  Attacker takes " + damageTaken + " damage.");
        while (damageTaken > 0) {
            int cluster = Math.min(5, damageTaken);
            HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, toHit.SIDE_FRONT);
            phaseReport.append(damageEntity(ae, hit, cluster));
            damageTaken -= cluster;
        }
        // move attacker and target, if possible
        Coords src = te.getPosition();
        Coords dest = src.translated(direction);
            
        if (Compute.isValidDisplacement(game, te.getId(), te.getPosition(), direction)) {    
            phaseReport.append("\n");
            doEntityDisplacement(te, src, dest, new PilotingRollData(te.getId(), 2, "was charged"));
            doEntityDisplacement(ae, ae.getPosition(), src, new PilotingRollData(ae.getId(), 2, "charging"));
        } else {
          pilotRolls.addElement(new PilotingRollData(ae.getId(), 2, "charging"));
          if (game.getOptions().booleanOption("push_off_board") && !game.board.contains(dest)) {
            game.moveToGraveyard(te.getId());
            send(createRemoveEntityPacket(te.getId()));
            phaseReport.append("\n*** " + te.getDisplayName() + " target has been forced from the field. ***\n");
            doEntityDisplacement(ae, ae.getPosition(), src, new PilotingRollData(ae.getId(), 2, "charging"));
          } else {
                // they stil have to roll
            pilotRolls.addElement(new PilotingRollData(te.getId(), 2, "was charged"));
          }
        }

        phaseReport.append("\n");
    }

    /**
     * Handle a death from above attack
     */
    private void resolveDfaAttack(DfaAttackAction daa, int lastEntityId) {
        final Entity ae = game.getEntity(daa.getEntityId());
        final Entity te = game.getEntity(daa.getTargetId());
        
        // is the attacker dead?  because that sure messes up the calculations
        if (ae == null) {
            return;
        }
        
        final int direction = ae.getFacing();

        if (lastEntityId != daa.getEntityId()) {
            phaseReport.append("\nPhysical attacks for " + ae.getDisplayName() + "\n");
        }

        // entity isn't charging any more
        ae.setDisplacementAttack(null);

        // should we even bother?
        if (te == null || te.isDestroyed() || te.isDoomed() || te.crew.isDead()) {
            phaseReport.append("    Death from above cancelled as the target has been destroyed.\n");
            doEntityDisplacement(ae, ae.getPosition(), daa.getTargetPos(), new PilotingRollData(ae.getId(), 4, "executed death from above"));
            return;
        }

        phaseReport.append("    Attempting death from above on " + te.getDisplayName());

        // target still in the same position?
        if (!te.getPosition().equals(daa.getTargetPos())) {
            phaseReport.append(" but the target has moved.\n");
            return;
        }
        
        // compute to-hit
        ToHitData toHit = Compute.toHitDfa(game, daa);

        // hack: if the attacker's prone, fudge the roll
        int roll;
        if (ae.isProne()) {
            roll = -12;
            phaseReport.append(" but the attacker is prone : ");
        } else if (toHit.getValue() == ToHitData.IMPOSSIBLE) {
            roll = -12;
            phaseReport.append(" but the attack is impossible (" + toHit.getDesc() + ") : ");
        } else {
            // roll
            roll = Compute.d6(2);
            phaseReport.append("; needs " + toHit.getValue() + ", ");
            phaseReport.append("rolls " + roll + " : ");
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            Coords src = ae.getPosition();
            Coords dest = te.getPosition();
            Coords targetDest = Compute.getPreferredDisplacement(game, te.getId(), dest, direction);
            phaseReport.append("misses.\n");
            if (targetDest != null) {
                // move target to preferred hex
                doEntityDisplacement(te, dest, targetDest, null);
                // attacker falls into destination hex
                phaseReport.append(ae.getDisplayName() + " falls into hex " + dest.getBoardNum() + ".\n");
                doEntityFall(ae, dest, 2, 3, Compute.getBasePilotingRoll(game, ae.getId()));
            } else {
                // attacker destroyed
                phaseReport.append("*** " + ae.getDisplayName()
                                   + " DESTROYED due to impossible displacement! ***");
                ae.setDoomed(true);
            }
            return;
        }

        // we hit...
        int damage = Compute.getDfaDamageFor(ae);
        int damageTaken = Compute.getDfaDamageTakenBy(ae);

        phaseReport.append("hits.");

        phaseReport.append("\n  Defender takes " + damage + " damage" + toHit.getTableDesc() + ".");
        while (damage > 0) {
            int cluster = Math.min(5, damage);
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            phaseReport.append(damageEntity(te, hit, cluster));
            damage -= cluster;
        }
        phaseReport.append("\n  Attacker takes " + damageTaken + " damage.");
        while (damageTaken > 0) {
            int cluster = Math.min(5, damageTaken);
            HitData hit = te.rollHitLocation(ToHitData.HIT_KICK, toHit.SIDE_FRONT);
            phaseReport.append(damageEntity(ae, hit, cluster));
            damageTaken -= cluster;
        }
        phaseReport.append("\n");
        
        // defender pushed away or destroyed
        Coords src = ae.getPosition();
        Coords dest = te.getPosition();
        Coords targetDest = Compute.getValidDisplacement(game, te.getId(), dest, direction);
        if (game.getOptions().booleanOption("push_off_board") && !game.board.contains(dest.translated(direction))) {
          game.moveToGraveyard(te.getId());
          send(createRemoveEntityPacket(te.getId()));
          phaseReport.append("\n*** " + te.getDisplayName() + " target has been forced from the field. ***\n");
        } else {
          if (targetDest != null) {
              doEntityDisplacement(te, dest, targetDest, new PilotingRollData(te.getId(), 2, "hit by death from above"));
          } else {
              // ack!  automatic death!
             phaseReport.append("*** " + te.getDisplayName() + " DESTROYED due to impossible displacement! ***");
             te.setDoomed(true);
          }
        }
        doEntityDisplacement(ae, src, dest, new PilotingRollData(ae.getId(), 4, "executed death from above"));
    }

    /**
     * Each mech sinks the amount of heat appropriate to its current heat
     * capacity.
     */
    private void resolveHeat() {
        roundReport.append("\nHeat Phase\n----------\n");
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            Entity entity = (Entity)i.nextElement();

            // should we even bother?
            if (entity.isDestroyed() || entity.isDoomed() || entity.crew.isDead()) {
                continue;
            }
            // engine hits add a lot of heat, provided the engine is on
            if (!entity.isShutDown()) {
                entity.heatBuildup += 5 * entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_CT);
				entity.heatBuildup += 5 * entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_LT);
				entity.heatBuildup += 5 * entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_RT);
            }

            // add the heat we've built up so far.
            roundReport.append(entity.getDisplayName() + " gains " + entity.heatBuildup + " heat,");
            entity.heat += entity.heatBuildup;
            entity.heatBuildup = 0;

            // how much heat can we sink?
            int tosink = Math.min(entity.getHeatCapacityWithWater(), entity.heat);

            entity.heat -= tosink;
            roundReport.append(" sinks " + tosink + " heat and is now at " + entity.heat + " heat.\n");

            // heat effects: start up
            if (entity.heat < 30 && entity.isShutDown()) {
                if (entity.heat < 14) {
                    entity.setShutDown(false);
                    roundReport.append(entity.getDisplayName() + " automatically starts up.\n");
                } else {
                    int startup = 4 + (((entity.heat - 14) / 4) * 2);
                    int suroll = Compute.d6(2);
                    roundReport.append(entity.getDisplayName() + " needs a " + startup + "+ to start up, rolls " + suroll + " : ");
                    if (suroll >= startup) {
                        entity.setShutDown(false);
                        roundReport.append("successful!\n");
                    } else {
                        roundReport.append("fails.\n");
                    }
                }
            }

            // heat effects: shutdown!
            if (entity.heat >= 14 && !entity.isShutDown()) {
                if (entity.heat >= 30) {
                    roundReport.append(entity.getDisplayName() + " automatically shuts down.\n");
                    // add a piloting roll and resolve immediately
                    pilotRolls.addElement(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                    resolvePilotingRolls();
                    // okay, now mark shut down
                    entity.setShutDown(true);
                } else if (entity.heat >= 14) {
                    int shutdown = 4 + (((entity.heat - 14) / 4) * 2);
                    int sdroll = Compute.d6(2);
                    roundReport.append(entity.getDisplayName() + " needs a " + shutdown + "+ to avoid shutdown, rolls " + sdroll + " : ");
                    if (sdroll >= shutdown) {
                        roundReport.append("avoids successfully!\n");
                    } else {
                        roundReport.append("shuts down.\n");
                        // add a piloting roll and resolve immediately
                        pilotRolls.addElement(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                        resolvePilotingRolls();
                        // okay, now mark shut down
                        entity.setShutDown(true);
                    }
                }
            }

            // heat effects: ammo explosion!
            if (entity.heat >= 19) {
                int boom = 4 + (entity.heat >= 23 ? 2 : 0) + (entity.heat >= 28 ? 2 : 0);
                int boomroll = Compute.d6(2);
                roundReport.append(entity.getDisplayName() + " needs a " + boom + "+ to avoid ammo explosion, rolls " + boomroll + " : ");
                if (boomroll >= boom) {
                    roundReport.append("avoids successfully!\n");
                } else {
                    roundReport.append("fails to avoid explosion.\n");
                    roundReport.append(explodeAmmoFromHeat(entity));
                }
            }

            // heat effects: mechwarrior damage
            if (entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD) > 0
                && entity.heat >= 15) {
                if (entity.heat >= 25) {
                    // mechwarrior takes 2 damage
                    roundReport.append(entity.getDisplayName() + " has 25 or higher heat and damaged life support.  Mechwarrior takes 2 damage.\n");
                    damageCrew(entity, 2);
                } else {
                    // mechwarrior takes 1 damage
                    roundReport.append(entity.getDisplayName() + " has 15 or higher heat and damaged life support.  Mechwarrior takes 1 damage.\n");
                    damageCrew(entity, 1);
                }
            }
        }
    }

    /**
     * Checks to see if any entity has takes 20 damage.  If so, they need a piloting
     * skill roll.
     */
    private void checkFor20Damage() {
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            final Entity entity = (Entity)i.nextElement();
            if (entity.getMovementType() == Entity.MovementType.BIPED ||
                    entity.getMovementType() == Entity.MovementType.QUAD) {
                // if this mech has 20+ damage, add another roll to the list.
                if (entity.damageThisPhase >= 20) {
                    pilotRolls.addElement(new PilotingRollData(entity.getId(), 1, "20+ damage"));
                }
            }
        }
    }

    /**
     * Checks to see if any entities are underwater with damaged life support.
     * Called during the end phase.
     */
    private void checkForSuffocation() {
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            final Entity entity = (Entity)i.nextElement();
            final Hex curHex = game.board.getHex(entity.getPosition());
            if ((curHex.levelOf(Terrain.WATER) > 1
                        || (curHex.levelOf(Terrain.WATER) == 1 && entity.isProne()))
                    && entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD) > 0) {
                roundReport.append("\n" + entity.getDisplayName() + " is underwater with damaged life support.  Mechwarrior takes 1 damage.\n");
                damageCrew(entity, 1);
            }
        }
    }

    /**
     * Resolves all built up piloting skill rolls.
     * (used at end of weapons, physical phases)
     */
    private void resolvePilotingRolls() {
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            final Entity entity = (Entity)i.nextElement();
            if (entity.isProne() || entity.isDoomed() || entity.isDestroyed()) {
                continue;
            }
            int rolls = 0;
            StringBuffer reasons = new StringBuffer();
            PilotingRollData roll = Compute.getBasePilotingRoll(game, entity.getId());
            for (Enumeration j = pilotRolls.elements(); j.hasMoreElements();) {
                final PilotingRollData modifier = (PilotingRollData)j.nextElement();
                if (modifier.getEntityId() == entity.getId()) {
                    rolls++;
                    if (reasons.length() > 0) {
                        reasons.append(", ");
                    }
                    reasons.append(modifier.getPlainDesc());
                    roll.append(modifier);
                }
            }
            // any rolls needed?
            if (rolls == 0) {
                continue;
            }
            if (roll.getValue() == PilotingRollData.AUTOMATIC_FAIL || roll.getValue() == PilotingRollData.IMPOSSIBLE) {
                phaseReport.append("\n" + entity.getDisplayName() + " must make " + rolls + " piloting skill roll(s) and automatically fails (" + roll.getDesc() + ").\n");
                doEntityFall(entity, roll);
            } else {
                phaseReport.append("\n" + entity.getDisplayName() + " must make " + rolls + " piloting skill roll(s) (" + reasons + ").\n");
                phaseReport.append("The target is " + roll.getValueAsString() + " [" + roll.getDesc() + "].\n");
                for (int j = 0; j < rolls; j++) {
                    final int diceRoll = Compute.d6(2);
                    phaseReport.append("    " + entity.getDisplayName() + " needs " + roll.getValueAsString() + ", rolls " + diceRoll + " : ");
                    phaseReport.append((diceRoll >= roll.getValue() ? "remains standing" : "falls") + ".\n");
                    if (diceRoll < roll.getValue()) {
                        doEntityFall(entity, roll);
                        // break rolling loop
                        break;
                    }
                }
            }
        }
        pilotRolls.removeAllElements();
    }

    /**
     * Inflict damage on a pilot
     */
    private String damageCrew(Entity en, int damage) {
        String s = new String();

        if (!en.crew.isDead()) {
            en.crew.setHits(en.crew.getHits() + damage);
            s += "        Pilot of " + en.getDisplayName() + " \"" + en.crew.getName() + "\" takes " + damage + " damage.";
            if (en.crew.getHits() < 6) {
                en.crew.setRollsNeeded(en.crew.getRollsNeeded() + damage);
            } else {
                en.crew.setDead(true);
                en.crew.setRollsNeeded(0);
                s += "\n*** " + en.getDisplayName() + " PILOT KILLED! ***";
            }
        }

        return s;
    }

    /**
     * This checks if the mech pilot goes unconcious from the damage he has
     * taken this phase.
     */
    private void resolveCrewDamage() {
        boolean anyRolls = false;
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            final Entity e = (Entity)i.nextElement();
            final int rollsNeeded = e.getCrew().getRollsNeeded();
            e.crew.setRollsNeeded(0);

            if (!e.isTargetable() || !e.getCrew().isActive() || rollsNeeded == 0) {
                continue;
            }
            anyRolls = true;
            for (int j = 0; j < rollsNeeded; j++) {
                int roll = Compute.d6(2);
                phaseReport.append("\nPilot of " + e.getDisplayName()
                   + " \"" + e.getCrew().getName() + "\""
                   + " needs a " + e.getCrew().getConciousnessNumber()
                   + " to stay concious.  Rolls " + roll + " : ");
                if (roll >= e.crew.getConciousnessNumber()) {
                    phaseReport.append("successful!");
                } else {
                    e.crew.setUnconcious(true);
                    e.crew.setKoThisRound(true);
                    phaseReport.append("blacks out.");
                    break;
                }
            }
        }
        if (anyRolls) {
            phaseReport.append("\n");
        }
    }

    /**
     * Make the rolls indicating whether any unconcious crews wake up
     */
    private void resolveCrewWakeUp() {
        boolean anyRolls = false;
        for (Enumeration i = game.getEntities(); i.hasMoreElements();) {
            final Entity e = (Entity)i.nextElement();

            if (!e.isTargetable() || !e.crew.isUnconcious() || e.crew.isKoThisRound()) {
                continue;
            }
            anyRolls = true;
            int roll = Compute.d6(2);
            roundReport.append("\nPilot of " + e.getDisplayName() + " \"" + e.crew.getName() + "\" needs a " + e.crew.getConciousnessNumber() + " to regain conciousness.  Rolls " + roll + " : ");
            if (roll >= e.crew.getConciousnessNumber()) {
                roundReport.append("successful!");
                e.crew.setUnconcious(false);
            } else {
                roundReport.append("fails.");
            }
        }
        if (anyRolls) {
            roundReport.append("\n");
        }
    }

    private String damageEntity(Entity te, HitData hit, int damage) {
        return damageEntity(te, hit, damage, false);
    }
    
    /**
     * Deals the listed damage to a mech.  Returns a description
     * string for the log.
     *
     * Currently mech only.
     *
     * @param te the target entity
     * @param hit the hit data for the location hit
     * @param damage the damage to apply
     * @param ammoExplosion ammo explosion type damage is handled slightly differently
     */
    private String damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion) {
        String desc = new String();

        int crits = hit.getEffect() == HitData.EFFECT_CRITICAL ? 1 : 0;

        //int loc = hit.getLocation();
        HitData nextHit = null;
        while (damage > 0 && !te.isDestroyed() && !te.isDoomed()) {
            // let's resolve some damage!
            desc += "\n        " + te.getDisplayName() + " takes " + damage + " damage to " + te.getLocationAbbr(hit) + ".";

            // was the section destroyed earlier this phase?
            if (te.getInternal(hit) == Entity.ARMOR_DOOMED) {
                // cannot transfer a through armor crit if so
                crits = 0;
            }

            // is there armor in the location hit?
            if (!ammoExplosion && te.getArmor(hit) > 0) {
                if (te.getArmor(hit) > damage) {
                    // armor absorbs all damage
                    te.setArmor(te.getArmor(hit) - damage, hit);
                    te.damageThisPhase += damage;
                    damage = 0;
                    desc += " " + te.getArmor(hit) + " Armor remaining";
                } else {
                    // damage goes on to internal
                    int absorbed = Math.max(te.getArmor(hit), 0);
                    te.setArmor(Entity.ARMOR_DESTROYED, hit);
                    te.damageThisPhase += absorbed;
                    damage -= absorbed;
                    desc += " Armor destroyed,";
                }
            }

            // is there damage remaining?
            if (damage > 0) {
                // is there internal structure in the location hit?
                if (te.getInternal(hit) > 0) {
                    // triggers a critical hit
                    crits++;
                    if (te.getInternal(hit) > damage) {
                        // internal structure absorbs all damage
                        te.setInternal(te.getInternal(hit) - damage, hit);
                        te.damageThisPhase += damage;
                        damage = 0;
                        desc += " " + te.getInternal(hit) + " Internal Structure remaining";
                    } else {
                        // damage transfers, maybe
                        int absorbed = Math.max(te.getInternal(hit), 0);
                        destroyLocation(te, hit.getLocation());
                        te.damageThisPhase += absorbed;
                        damage -= absorbed;
                        desc += " <<<SECTION DESTROYED>>>,";
			if (hit.getLocation() == Mech.LOC_RT || hit.getLocation() == Mech.LOC_LT) {
				int numEngineHits = 0;
				numEngineHits += te.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_CT);
				numEngineHits += te.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_RT);
				numEngineHits += te.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_LT);
				if (numEngineHits > 2) {
				// third engine hit
				te.setDoomed(true);
				desc += "\n*** " + te.getDisplayName() + " ENGINE DESTROYED! ***";
				}
			}
                    }
                } 
                
                // is the internal structure gone?  what are the transfer potentials?
                if (te.getInternal(hit) <= 0) {
                    nextHit = te.getTransferLocation(hit);
                    if (nextHit.getLocation() == Entity.LOC_DESTROYED) {
                        // entity destroyed.
                        desc += " Entity destroyed!\n";
                        desc += "*** " + te.getDisplayName() + " DESTROYED! ***";
                        te.setDoomed(true);
                        // no need for further damage
                        damage = 0;
                        crits = 0;
                    } else if (ammoExplosion && te.locationHasCase(hit.getLocation())) {
                        // remaining damage prevented
                        desc += " remaining " + damage + " damage prevented by CASE.";
                        damage = 0;
                    } else if (damage > 0) {
                        // remaining damage transfers
                        desc += " " + damage + " damage transfers to "
                            + te.getLocationAbbr(nextHit) + ".";
                    }
                }
            }
            
            // resolve special results
            if (hit.getEffect() == HitData.EFFECT_VEHICLE_MOVE_DAMAGED) {
                desc += "\n            Movement system damaged!";
                te.setOriginalWalkMP(Math.max(0, te.getOriginalWalkMP() - 1));
            }
            else if (hit.getEffect() == HitData.EFFECT_VEHICLE_MOVE_DESTROYED) {
                desc += "\n            Movement system destroyed!";
                te.setOriginalWalkMP(0);
            }
            else if (hit.getEffect() == HitData.EFFECT_VEHICLE_TURRETLOCK) {
                desc += "\n            Turret locked!";
                ((Tank)te).lockTurret();
            }            
            
            // roll all critical hits against this location
            for (int i = 0; i < crits; i++) {
                desc += "\n" + criticalEntity(te, hit.getLocation());
            }
            crits = 0;

            if (te instanceof Mech && hit.getLocation() == Mech.LOC_HEAD) {
                desc += "\n" + damageCrew(te, 1);
            }

            // loop to next location
            hit = nextHit;
        }

        return desc;
    }

    /**
     * Rolls and resolves critical hits
     *
     * Currently mech only
     */
    private String criticalEntity(Entity en, int loc) {
        String desc = "        Critical hit on " + en.getLocationAbbr(loc) + ". ";
        int hits = 0;
        int roll = Compute.d6(2);
        desc += "Roll = " + roll + ";";
        if (roll <= 7) {
            desc += " no effect.";
            return desc;
        } else if (roll >= 8 && roll <= 9) {
            hits = 1;
            desc += " 1 location.";
        } else if (roll >= 10 && roll <= 11) {
            hits = 2;
            desc += " 2 locations.";
        } else if (roll == 12) {
            if (en instanceof Tank) {
                hits = 3;
                desc += " 3 locations.";
            } else if (en.locationIsLeg(loc)) {
                desc += "<<<LIMB BLOWN OFF>>> " + en.getLocationName(loc) + " blown off.";
                if (en.getInternal(loc) > 0) {
                    destroyLocation(en, loc);
                }
                return desc;
            } else if (loc == Mech.LOC_RARM || loc == Mech.LOC_LARM) {
                desc += "<<<LIMB BLOWN OFF>>> " + en.getLocationName(loc) + " blown off.";
                destroyLocation(en, loc);
                return desc;
            } else if (loc == Mech.LOC_HEAD) {
                desc += "<<<HEAD BLOWN OFF>>> " + en.getLocationName(loc) + " blown off.";
                destroyLocation(en, loc);
                en.crew.setDead(true);
                desc += "\n*** " + en.getDisplayName() + " PILOT KILLED! ***";
                return desc;
            } else {
                // torso hit
                hits = 3;
                desc += " 3 locations.";
            }
        }
        
        // vehicle handle crits in their own 'special' way
        if (en instanceof Tank) {
            Tank tank = (Tank)en;
            for (int x = 0; x < hits && !tank.isDoomed(); x++) {
                switch (Compute.d6(1)) {
                    case 1 :
                        desc += "\n            <<<CRITICAL HIT>>> Crew stunned for 3 turns";
                        tank.stunCrew();
                        break;
                    case 2 :
                        // this one's ridiculous.  the 'main weapon' jams.
                        Mounted mWeap = tank.getMainWeapon();
                        if (mWeap == null) {
                            desc += "\n            No main weapon crit, because no main weapon!";
                        }
                        else {
                            desc += "\n            <<<CRITICAL HIT>>> " + mWeap.getName() +
                                    " jams.";
                            tank.setJammedWeapon(mWeap);
                        }
                        break;
                    case 3 :
                        desc += "\n            <<<CRITICAL HIT>>> Engine destroyed.  Immobile.";
                        tank.setOriginalWalkMP(0);
                        break;
                    case 4 :
                        desc += "\n            <<<CRITICAL HIT>>> Crew killed";
                    case 5 :
                        desc += "\n            <<<CRITICAL HIT>>> Fuel tank hit.  BOOM!";
                    case 6 :
                        desc += "\n            <<<CRITICAL HIT>>> Power plant hit.  BOOM!";
                        desc += "\n*** " + en.getDisplayName() + " DESTROYED! ***";
                        tank.setDoomed(true);
                }
            }
        }
        else {
            // transfer criticals, if needed
            if (hits > 0 && !en.hasHitableCriticals(loc)
                    && en.getTransferLocation(new HitData(loc)).getLocation() != Entity.LOC_DESTROYED) {
                loc = en.getTransferLocation(new HitData(loc)).getLocation();
                desc += "\n            Location is empty, so criticals transfer to " + en.getLocationAbbr(loc) +".";
    
                // may need to transfer crits twice--if you are shooting a CDA-3C Cicada and get lucky on the left arm two turns in a row
                if (hits > 0 && !en.hasHitableCriticals(loc)
                        && en.getTransferLocation(new HitData(loc)).getLocation() != Entity.LOC_DESTROYED) {
                    loc = en.getTransferLocation(new HitData(loc)).getLocation();
                    desc += "\n            Location is empty, so criticals transfer to " + en.getLocationAbbr(loc) +".";
                }
            }
            // roll criticals
            while (hits > 0) {
                if (en.getHitableCriticals(loc) <= 0) {
                    desc += "\n            Location empty.";
                    break;
                }
                int slot = Compute.random.nextInt(en.getNumberOfCriticals(loc));
                CriticalSlot cs = en.getCritical(loc, slot);
                if (cs == null || !cs.isHitable()) {
                    continue;
                }
                cs.setHit(true);
                switch(cs.getType()) {
                case CriticalSlot.TYPE_SYSTEM :
                    desc += "\n            <<<CRITICAL HIT>>> on " + Mech.systemNames[cs.getIndex()] + ".";
                    switch(cs.getIndex()) {
                    case Mech.SYSTEM_COCKPIT :
                        // boink!
                        en.crew.setDead(true);
                        desc += "\n*** " + en.getDisplayName() + " PILOT KILLED! ***";
                        break;
                    case Mech.SYSTEM_ENGINE :
    					int numEngineHits = 0;
    					numEngineHits += en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_CT);
    					numEngineHits += en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_RT);
    					numEngineHits += en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_LT);
    					if (numEngineHits > 2) {
                            // third engine hit
                            en.setDoomed(true);
                            desc += "\n*** " + en.getDisplayName() + " ENGINE DESTROYED! ***";
                        }
                        break;
                    case Mech.SYSTEM_GYRO :
                        if (en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO, loc) > 1) {
                            // gyro destroyed
                            pilotRolls.addElement(new PilotingRollData(en.getId(), PilotingRollData.AUTOMATIC_FAIL, 3, "gyro destroyed"));
                        } else {
                            // first gyro hit
                            pilotRolls.addElement(new PilotingRollData(en.getId(), 3, "gyro hit"));
                        }
                        break;
                    case Mech.ACTUATOR_UPPER_LEG :
                    case Mech.ACTUATOR_LOWER_LEG :
                    case Mech.ACTUATOR_FOOT :
                        // leg/foot actuator piloting roll
                        pilotRolls.addElement(new PilotingRollData(en.getId(), 1, "leg/foot actuator hit"));
                        break;
                    case Mech.ACTUATOR_HIP :
                        // hip piloting roll
                        pilotRolls.addElement(new PilotingRollData(en.getId(), 2, "hip actuator hit"));
                        break;
                    }
                    break;
                case CriticalSlot.TYPE_EQUIPMENT :
                    Mounted mounted = en.getEquipment(cs.getIndex());
                    EquipmentType eqType = mounted.getType();
                    boolean hitBefore = mounted.isHit();
                    desc += "\n            <<<CRITICAL HIT>>> on " + mounted.getDesc() + ".";
                    mounted.setHit(true);
                    if (eqType.isExplosive() && !hitBefore) {
                        desc += explodeEquipment(en, loc, slot);
                    }
                    break;
                }
                hits--;
    //                System.err.println("s: critical loop, " + hits + " remaining");
            }
        }

        return desc;
    }
    
    /**
     * Marks all equipment in a location on an entity as destroyed.
     */
    private void destroyLocation(Entity en, int loc) {
        // if it's already marked as destroyed, don't bother
        if (en.getInternal(loc) < 0) {
            return;
        }
        // mark armor, internal as doomed
        en.setArmor(Entity.ARMOR_DOOMED, loc, false);
        en.setInternal(Entity.ARMOR_DOOMED, loc);
        if (en.hasRearArmor(loc)) {
            en.setArmor(Entity.ARMOR_DOOMED, loc, true);
        }
        // equipment marked missing
        for (Enumeration i = en.getEquipment(); i.hasMoreElements();) {
            Mounted mounted = (Mounted)i.nextElement();
            if (mounted.getLocation() == loc) {
                mounted.setMissing(true);
            }
        }
        // all critical slots set as missing
        for (int i = 0; i < en.getNumberOfCriticals(loc); i++) {
            final CriticalSlot cs = en.getCritical(loc, i);
            if (cs != null) {
                cs.setMissing(true);
            }
        }
        // if it's a leg, the entity falls
        if (loc == Mech.LOC_RLEG || loc == Mech.LOC_LLEG) {
            pilotRolls.addElement(new PilotingRollData(en.getId(), PilotingRollData.AUTOMATIC_FAIL, 5, "leg destroyed"));
        }
        // dependent locations destroyed
        if (en.getDependentLocation(loc) != Mech.LOC_NONE) {
            destroyLocation(en, en.getDependentLocation(loc));
        }
    }

    /**
     * Makes a piece of equipment on a mech explode!  POW!  This expects either
     * ammo, or an explosive weapon.
     */
    private String explodeEquipment(Entity en, int loc, int slot) {
        Mounted mounted = en.getEquipment(en.getCritical(loc, slot).getIndex());
        StringBuffer desc = new StringBuffer();
        // is this already destroyed?
        if (mounted.isDestroyed()) {
            System.err.println("server: explodeEquipment called on destroyed"
                               + " equipment (" + mounted.getName() + ")");
            return "";
        }
        
        int damage = mounted.getExplosionDamage();
        
        if (damage <= 0) {
            return "";
        }
        
        desc.append("\n*** ");
        desc.append(mounted.getName());
        desc.append(" EXPLODES!  ");
        desc.append(damage);
        desc.append(" DAMAGE! ***");
        
        desc.append(damageEntity(en, new HitData(loc), damage, true));
        desc.append("\n");
        if (!en.isDoomed() && !en.isDestroyed()) {
            desc.append(damageCrew(en, 2));
            desc.append("\n");
        }
        
        return desc.toString();
    }

    /**
     * Makes one slot of ammo, determined by certain rules, explode on a mech.
     */
    private String explodeAmmoFromHeat(Entity entity) {
        int damage = 0;
        int rack = 0;
        int boomloc = -1;
        int boomslot = -1;
        for (int j = 0; j < entity.locations(); j++) {
            for (int k = 0; k < entity.getNumberOfCriticals(j); k++) {
                CriticalSlot cs = entity.getCritical(j, k);
                if (cs == null || cs.isDestroyed() || cs.isHit() || cs.getType() != CriticalSlot.TYPE_EQUIPMENT) {
                    continue;
                }
                Mounted mounted = entity.getEquipment(entity.getCritical(j, k).getIndex());
                if (!(mounted.getType() instanceof AmmoType)) {
                    continue;
                }
                AmmoType atype = (AmmoType)mounted.getType();
                if (!atype.isExplosive()) {
                    continue;
                }
                if (!mounted.isHit() && (rack < atype.getDamagePerShot() * atype.getRackSize()
                || damage < atype.getDamagePerShot() * atype.getRackSize() * mounted.getShotsLeft())) {
                    rack = atype.getDamagePerShot() * atype.getRackSize();
                    damage = atype.getDamagePerShot() * atype.getRackSize() * mounted.getShotsLeft();
                    boomloc = j;
                    boomslot = k;
                }
            }
        }
        if (boomloc != -1 && boomslot != -1) {
            return explodeEquipment(entity, boomloc, boomslot);
        } else {
            return "  Luckily, there is no ammo to explode.\n";
        }
    }

    /**
     * Makes a mech fall.
     */
    private void doEntityFall(Entity entity, Coords fallPos, int height, int facing, PilotingRollData roll) {
        // we don't need to deal damage yet, if the entity is doing DFA
        if (entity.isMakingDfa()) {
            phaseReport.append("But, since the 'mech is making a death from above attack, damage will be dealt during the physical phase.\n");
            entity.setProne(true);
            return;
        }            
        // facing after fall
        String side;
        int table;
        switch(facing) {
        case 1:
        case 2:
            side = "right side";
            table = ToHitData.SIDE_RIGHT;
            break;
        case 3:
            side = "rear";
            table = ToHitData.SIDE_REAR;
            break;
        case 4:
        case 5:
            side = "left side";
            table = ToHitData.SIDE_LEFT;
            break;
        case 0:
        default:
            side = "front";
            table = ToHitData.SIDE_FRONT;
        }

        // calculate damage
        int damage = (int)Math.round(entity.getWeight() / 10) * (height + 1);
        
        // TODO: only fall to surface of water
        if (game.board.getHex(fallPos).levelOf(Terrain.WATER) > 0) {
            damage = (int)Math.ceil(damage / 2.0);
        }

        // report falling
        phaseReport.append("    " + entity.getDisplayName() + " falls on its " + side + ", suffering " + damage + " damage.");

        // standard damage loop
        while (damage > 0) {
            int cluster = Math.min(5, damage);
            HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, table);
            phaseReport.append(damageEntity(entity, hit, cluster));
            damage -= cluster;
        }

        // pilot damage?
        roll.removeAutos();
        
        if (height > 0) {
            roll.addModifier(height, "height of fall");
        }
        
        if (roll.getValue() == PilotingRollData.IMPOSSIBLE) {
            phaseReport.append("\nPilot of " + entity.getDisplayName() 
            + " \"" + entity.crew.getName() + "\" cannot avoid damage.\n");
            phaseReport.append(damageCrew(entity, 1) + "\n");
        } else {
            int diceRoll = Compute.d6(2);
            phaseReport.append("\nPilot of " + entity.getDisplayName() 
            + " \"" + entity.crew.getName() + "\" must roll " + roll.getValueAsString() 
//            + " [" + roll.getDesc() + "]";
            + " to avoid damage; rolls " + diceRoll + " : "); 
            if (diceRoll >= roll.getValue()) {
                phaseReport.append("succeeds.\n");
            } else {
                phaseReport.append("fails.\n");
                phaseReport.append(damageCrew(entity, 1) + "\n");
            }
        }

        entity.setProne(true);
        entity.setPosition(fallPos);
        entity.setFacing((entity.getFacing() + (facing - 1)) % 6);
        entity.setSecondaryFacing(entity.getFacing());
    }

    /**
     * The mech falls into an unoccupied hex from the given height above
     */
    private void doEntityFall(Entity entity, Coords fallPos, int height, PilotingRollData roll) {
        doEntityFall(entity, fallPos, height, Compute.d6(1), roll);
    }

    /**
     * The mech falls down in place
     */
    private void doEntityFall(Entity entity, PilotingRollData roll) {
        doEntityFall(entity, entity.getPosition(), 0, roll);
    }
    
    /**
     * Scans the boards directory for map boards of the appropriate size
     * and returns them.
     */
    private Vector scanForBoards(int boardWidth, int boardHeight) {
        Vector boards = new Vector();
        
        File boardDir = new File("data/boards");
        
        // just a check...
        if (!boardDir.isDirectory()) {
            return boards;
        }
        
        // scan files
        String[] fileList = boardDir.list();
        for (int i = 0; i < fileList.length; i++) {
            if (Board.boardIsSize(fileList[i], boardWidth, boardHeight)) {
                boards.addElement(fileList[i].substring(0, fileList[i].lastIndexOf(".board")));
            }
        }
        
        // if there are any boards, add these:
        if (boards.size() > 0) {
            boards.insertElementAt(MapSettings.BOARD_RANDOM, 0);
            boards.insertElementAt(MapSettings.BOARD_SURPRISE, 1);
        }
        
        //TODO: alphabetize files?

        return boards;
    }
    
    private boolean doBlind()
    {
        return (game.getOptions().booleanOption("double_blind") &&
                game.phase >= Game.PHASE_INITIATIVE);
    }
    
    /**
     * In a double-blind game, update only visible entities.  Otherwise,
     * update everyone
     */
    private void entityUpdate(int nEntityID)
    {
        if (doBlind()) {
            Entity eTarget = game.getEntity(nEntityID);
            Vector vPlayers = game.getPlayersVector();
            Vector vCanSee = new Vector();
            vCanSee.addElement(eTarget.getOwner());
            Vector vEntities = game.getEntitiesVector();
            for (int x = 0; x < vEntities.size(); x++) {
                Entity e = (Entity)vEntities.elementAt(x);
                if (vCanSee.contains(e.getOwner()) || !e.isActive()) {
                    continue;
                }
                if (Compute.canSee(game, e, eTarget)) {
                    vCanSee.addElement(e.getOwner());
                }
            }
            // send an entity update to everyone who can see
            Packet pack = createEntityPacket(nEntityID);
            for (int x = 0; x < vCanSee.size(); x++) {
                Player p = (Player)vCanSee.elementAt(x);
                send(p.getId(), pack);
            }
            // send an entity delete to everyone else
            pack = createRemoveEntityPacket(nEntityID);
            for (int x = 0; x < vPlayers.size(); x++) {
                if (!vCanSee.contains(vPlayers.elementAt(x))) {
                    Player p = (Player)vPlayers.elementAt(x);
                    send(p.getId(), pack);
                }
            }
        }
        else {
            // everyone can see
            send(createEntityPacket(nEntityID));
        }
    }
    
    /**
     * Send the complete list of entities to the players.
     * If double_blind is in effect, enforce it by filtering the entities
     */
    private void entityAllUpdate()
    {
        if (doBlind()) {
            Vector vPlayers = game.getPlayersVector();
            for (int x = 0; x < vPlayers.size(); x++) {
                Player p = (Player)vPlayers.elementAt(x);
                send(p.getId(), createFilteredEntitiesPacket(p));
            }
        }
        else {
            send(createEntitiesPacket());
        }
    }
            
            
    /**
     * Filters an entity vector according to LOS
     */
    private Vector filterEntities(Player pViewer, Vector vEntities)
    {
        Vector vCanSee = new Vector();
        Vector vAllEntities = game.getEntitiesVector();
        Vector vMyEntities = new Vector();
        for (int x = 0; x < vAllEntities.size(); x++) {
            Entity e = (Entity)vAllEntities.elementAt(x);
            if (e.getOwner() == pViewer) {
                vMyEntities.addElement(e);
            }
        }
        
        for (int x = 0; x < vEntities.size(); x++) {
            Entity e = (Entity)vEntities.elementAt(x);
            if (vMyEntities.contains(e)) {
                vCanSee.addElement(e);
                continue;
            }
            for (int y = 0; y < vMyEntities.size(); y++) {
                Entity e2 = (Entity)vMyEntities.elementAt(y);
                if (Compute.canSee(game, e2, e)) {
                    vCanSee.addElement(e);
                    break;
                }
            }
        }
        return vCanSee;
    }
            
    /**
     * Sets an entity ready status to false
     */
    private void receiveEntityReady(Packet pkt, int connIndex) {
        Entity entity = game.getEntity(pkt.getIntValue(0));
        if (entity != null && entity.getOwner() == getPlayer(connIndex)
            && game.getTurn().getPlayerNum() == connIndex) {
            entity.ready = false;
        } else {
            System.out.println("server.receiveEntityReady: got an invalid ready message");
        }
    }

    /**
      * Checks if an entity added by the client is valid and if so, adds it to the list
      */
     private void receiveEntityAdd(Packet c, int connIndex) {
        Entity entity = (Entity)c.getObject(0);

        entity.restore();
        entity.setOwner(getPlayer(connIndex));
        entity.setId(entityCounter++);
        game.addEntity(entity.getId(), entity);

        send(createAddEntityPacket(entity.getId()));
     }

    /**
      * Updates an entity with the info from the client.  Only valid to do this
      * durring the lounge phase.
      */
    private void receiveEntityUpdate(Packet c, int connIndex) {
        Entity entity = (Entity)c.getObject(0);
        Entity oldEntity = game.getEntity(entity.getId());
        if (oldEntity != null && oldEntity.getOwner() == getPlayer(connIndex)) {
            entity.restore();
            entity.setOwner(getPlayer(connIndex));
            game.setEntity(entity.getId(), entity);

            send(createEntitiesPacket());
        } else {
            // hey!
        }
     }

    /**
     * Deletes an entity owned by a certain player from the list
     */
    private void receiveEntityDelete(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        Entity entity = game.getEntity(entityId);
        if (entity != null && entity.getOwner() == getPlayer(connIndex)) {
            game.removeEntity(entityId);
            send(createRemoveEntityPacket(entityId));
        } else {
            // hey! that's not your entity
        }
    }

    /**
     * Sets a player's ready status
     */
    private void receivePlayerReady(Packet pkt, int connIndex) {
        boolean ready = pkt.getBooleanValue(0);
        // don't let them enter the game with no entities
        if (ready && game.phase == Game.PHASE_LOUNGE
            && game.getEntitiesOwnedBy(getPlayer(connIndex)) < 1) {
            ready = false;
        }
        getPlayer(connIndex).setReady(ready);
    }
    
    /**
     * Sets game options, providing that the player has specified the password
     * correctly.
     *
     * @returns true if any options have been successfully changed.
     */
    private boolean receiveGameOptions(Packet packet, int connId) {
        // check password
        if (password != null && password.length() > 0 && !password.equals(packet.getObject(0))) {
            sendServerChat(connId, "The password you specified to change game options is incorrect.");
            return false;
        }
        
        int changed = 0;
        
        for (Enumeration i = ((Vector)packet.getObject(1)).elements(); i.hasMoreElements();) {
            GameOption option = (GameOption)i.nextElement();
            GameOption originalOption = game.getOptions().getOption(option.getShortName());
            
            if (originalOption == null) {
                continue;
            }
            
            sendServerChat("Player " + getPlayer(connId).getName() + " changed option \"" + originalOption.getFullName() + "\" to " + option.stringValue() + ".");
            
            originalOption.setValue(option.getValue());
            changed++;
        }
        
        return changed > 0;
    }

    /**
     * Sends out all player info to the specified connection
     */
    private void transmitAllPlayerConnects(int connId) {
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();

            send(connId, createPlayerConnectPacket(player.getId()));
        }
    }



    /**
     * Creates a packet informing that the player has connected
     */
    private Packet createPlayerConnectPacket(int playerId) {
        final Object[] data = new Object[2];
        data[0] = new Integer(playerId);
        data[1] = getPlayer(playerId);
        return new Packet(Packet.COMMAND_PLAYER_ADD, data);
    }

    /**
     * Creates a packet containing the player info, for update
     */
    private Packet createPlayerUpdatePacket(int playerId) {
        final Object[] data = new Object[2];
        data[0] = new Integer(playerId);
        data[1] = getPlayer(playerId);
        return new Packet(Packet.COMMAND_PLAYER_UPDATE, data);
    }

    /**
     * Sends out the player info updates for all players to all connections
     */
    private void transmitAllPlayerUpdates() {
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();

            send(createPlayerUpdatePacket(player.getId()));
        }
    }

    /**
     * Sends out the player ready stats for all players to all connections
     */
    private void transmitAllPlayerReadys() {
        for (Enumeration i = game.getPlayers(); i.hasMoreElements();) {
            final Player player = (Player)i.nextElement();

            send(createPlayerReadyPacket(player.getId()));
        }
    }

    /**
     * Creates a packet containing the player ready status
     */
    private Packet createPlayerReadyPacket(int playerId) {
        Object[] data = new Object[2];
        data[0] = new Integer(playerId);
        data[1] = new Boolean(getPlayer(playerId).isReady());
        return new Packet(Packet.COMMAND_PLAYER_READY, data);
    }
    
    /**
     * Creates a packet containing the map settings
     */
    private Packet createMapSettingsPacket() {
        return new Packet(Packet.COMMAND_SENDING_MAP_SETTINGS, mapSettings);
    }
    
    /**
     * Creates a packet containing temporary map settings as a response to a
     * client query
     */
    private Packet createMapQueryPacket(MapSettings temp) {
        return new Packet(Packet.COMMAND_QUERY_MAP_SETTINGS, temp);
    }
    
    /**
     * Creates a packet containing the game settingss
     */
    private Packet createGameSettingsPacket() {
        return new Packet(Packet.COMMAND_SENDING_GAME_SETTINGS, game.getOptions());
    }

    /**
     * Creates a packet containing the game board
     */
    private Packet createBoardPacket() {
        return new Packet(Packet.COMMAND_SENDING_BOARD, game.board);
    }

    /**
     * Creates a packet containing a single entity, for update
     */
    private Packet createEntityPacket(int entityId) {
        final Entity entity = game.getEntity(entityId);
        final Object[] data = new Object[2];
        data[0] = new Integer(entityId);
        data[1] = entity;
        return new Packet(Packet.COMMAND_ENTITY_UPDATE, data);
    }


    /**
     * Creates a packet containing the round report
     */
    private Packet createReportPacket() {
        return new Packet(Packet.COMMAND_SENDING_REPORT, roundReport.toString());
    }

    /**
     * Creates a packet containing all current entities
     */
    private Packet createEntitiesPacket() {
        return new Packet(Packet.COMMAND_SENDING_ENTITIES, game.getEntitiesVector());
    }

    /**
     * Creates a packet containing all entities visible to the player in a blind game
     */
    private Packet createFilteredEntitiesPacket(Player p) {
        return new Packet(Packet.COMMAND_SENDING_ENTITIES, filterEntities(p, game.getEntitiesVector()));
    }

    /**
     * Creates a packet detailing the addition of an entity
     */
    private Packet createAddEntityPacket(int entityId) {
        final Entity entity = game.getEntity(entityId);
        final Object[] data = new Object[2];
        data[0] = new Integer(entityId);
        data[1] = entity;
        return new Packet(Packet.COMMAND_ENTITY_ADD, data);
    }

    /**
     * Creates a packet detailing the removal of an entity
     */
    private Packet createRemoveEntityPacket(int entityId) {
        return new Packet(Packet.COMMAND_ENTITY_REMOVE, new Integer(entityId));
    }
    
    /**
     * Creates a packet indicating end of game, including detailed unit status
     */
    private Packet createEndOfGamePacket() {
        return new Packet(Packet.COMMAND_END_OF_GAME, getDetailedVictoryReport());
    }

    /**
     * Transmits a chat message to all players
     */
    private void sendChat(int connId, String origin, String message) {
        send(connId, new Packet(Packet.COMMAND_CHAT, origin + ": " + message));
    }

    /**
     * Transmits a chat message to all players
     */
    private void sendChat(String origin, String message) {
        String chat = origin + ": " + message;
        send(new Packet(Packet.COMMAND_CHAT, chat));
        log.append(chat);
    }
    
    public void sendServerChat(int connId, String message) {
        sendChat(connId, "***Server", message);
    }

    public void sendServerChat(String message) {
        sendChat("***Server", message);
    }

    /**
     * Creates a packet for an attack
     */
    private Packet createAttackPacket(EntityAction ea) {
        return new Packet(Packet.COMMAND_ENTITY_ATTACK, ea);
    }

    /**
     * Send a packet to all connected clients.
     */
    private void send(Packet packet) {
        packet.zipData();
        for (Enumeration i = connections.elements(); i.hasMoreElements();) {
            final Connection conn = (Connection)i.nextElement();
            conn.send(packet);
        }
    }

    /**
     * Send a packet to a specific connection.
     */
    private void send(int connId, Packet packet) {
        packet.zipData();
        getClient(connId).send(packet);
    }

    /**
     * Send a packet to a pending connection
     */
    private void sendToPending(int connId, Packet packet) {
        getPendingConnection(connId).send(packet);
    }
    
    /**
     * Process an in-game command
     */
    private void processCommand(int connId, String commandString) {
        String[] args;
        String commandName;
        // all tokens are read as strings; if they're numbers, string-ize 'em.
        StringTokenizer st = new StringTokenizer(commandString);
        args = new String[st.countTokens()];
        for (int i = 0; i < args.length; i++) {
            args[i] = st.nextToken();
        }
        
        // figure out which command this is
        commandName = args[0].substring(1);
        
        // process it
        ServerCommand command = getCommand(commandName);
        if (command != null) {
            command.run(connId, args);
        } else {
            sendServerChat(connId, "Command not recognized.  Type /help for a list of commands.");
        }
    }
    
    /**
     * Process a packet
     */
    void handle(int connId, Packet packet) {
        //System.out.println("s(" + cn + "): received command");
        if (packet == null) {
            System.out.println("server.connection.handle: got null packet");
            return;
        }
        // act on it
        switch(packet.getCommand()) {
        case Packet.COMMAND_CLIENT_NAME :
            receivePlayerName(packet, connId);
            break;
        case Packet.COMMAND_PLAYER_UPDATE :
            receivePlayerInfo(packet, connId);
            validatePlayerInfo(connId);
            send(createPlayerUpdatePacket(connId));
            break;
        case Packet.COMMAND_ENTITY_READY :
            receiveEntityReady(packet, connId);
            //send(createEntityPacket(packet.getIntValue(0)));
            entityUpdate(packet.getIntValue(0));
            break;
        case Packet.COMMAND_PLAYER_READY :
            receivePlayerReady(packet, connId);
            send(createPlayerReadyPacket(connId));
            checkReady();
            break;
        case Packet.COMMAND_CHAT :
            String chat = (String)packet.getObject(0);
            if (chat.startsWith("/")) {
                processCommand(connId, chat);
            } else {
                sendChat(getPlayer(connId).getName(), chat);
            }
            break;
        case Packet.COMMAND_ENTITY_MOVE :
            doEntityMovement(packet, connId);
            break;
        case Packet.COMMAND_ENTITY_ATTACK :
            receiveAttack(packet);
            break;
        case Packet.COMMAND_ENTITY_ADD :
            receiveEntityAdd(packet, connId);
            resetPlayerReady();
            transmitAllPlayerReadys();
            break;
        case Packet.COMMAND_ENTITY_UPDATE :
            receiveEntityUpdate(packet, connId);
            resetPlayerReady();
            transmitAllPlayerReadys();
            break;
        case Packet.COMMAND_ENTITY_REMOVE :
            receiveEntityDelete(packet, connId);
            resetPlayerReady();
            transmitAllPlayerReadys();
            break;
        case Packet.COMMAND_SENDING_GAME_SETTINGS :
            if (receiveGameOptions(packet, connId)) {
                resetPlayerReady();
                transmitAllPlayerReadys();
                send(createGameSettingsPacket());
            }
            break;
        case Packet.COMMAND_SENDING_MAP_SETTINGS :
            mapSettings = (MapSettings)packet.getObject(0);
            mapSettings.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
            resetPlayerReady();
            transmitAllPlayerReadys();
            send(createMapSettingsPacket());
            break;
        case Packet.COMMAND_QUERY_MAP_SETTINGS :
            MapSettings temp = (MapSettings)packet.getObject(0);
            temp.setBoardsAvailableVector(scanForBoards(temp.getBoardWidth(), temp.getBoardHeight()));
            temp.removeUnavailable();
            temp.setNullBoards(DEFAULT_BOARD);
            temp.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
            temp.removeUnavailable();
            send(connId, createMapQueryPacket(temp));
            break;
        }
    }


    /**
     * Listen for incoming clients.
     */
    public void run() {
        Thread currentThread = Thread.currentThread();
        System.out.println("s: listening for clients...");
        while (connector == currentThread) {
            try {
                Socket s = serverSocket.accept();

                int id = connectionCounter++;
                System.out.println("s: accepting player connection #" + id + " ...");

                connectionsPending.addElement(new Connection(this, s, id));

                greeting(id);
            } catch(IOException ex) {
                ;
            }
        }
    }
}
