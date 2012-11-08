/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package net.boreeas.irc;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.boreeas.irc.plugins.PluginManager;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Represents a connection to an IRC server.
 * <p/>
 * @author Boreeas
 */
public final class IRCBot extends Thread {

    private static final Log logger = LogFactory.getLog("IRC");
    private final FileConfiguration config;
    private boolean interrupted;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String currentNick;
    private CommandHandler commandHandler = new CommandHandler();
    private PluginManager pluginManager;
    private Map<String, BotAccessLevel> accessLevels =
                                        new HashMap<String, BotAccessLevel>();
    private Set<String> muted = new HashSet<String>();
    private boolean whoxSupported;
    private Timer checkConnectionTimer;

    public IRCBot(final FileConfiguration config) {

        super(config.getString(ConfigKey.HOST.key())
              + ":" + config.getInt(ConfigKey.PORT.key()));

        this.config = config;
        config.setAutoSave(true);
        config.setReloadingStrategy(new FileChangedReloadingStrategy());

        // Ensure that the config is complete
        for (ConfigKey key: ConfigKey.values()) {
            if (key.isRequired() && config.getProperty(key.key()) == null) {
                throw new RuntimeException("Missing config key " + key + " ("
                                           + key.key() + ")");
            }
        }

        this.currentNick = config.getString(ConfigKey.NICK.key());

        loadAccessLevels();
        loadPlugins();
    }


    public void loadPlugins() {

        if (pluginManager != null) {
            // Prevent double loading
            pluginManager.disableAllPlugins();
        }

        List<Object> pluginNames = config.getList(ConfigKey.PLUGINS.key());
        Set<String> pluginNameSet = new HashSet<String>(pluginNames.size());

        for (Object o: pluginNames) {
            if (o instanceof String) {
                pluginNameSet.add((String) o);
            } else {
                logger.warn(this + "Non-String in plugin declaration: " + o);
            }
        }

        if (!pluginNameSet.contains("Core")) {
            throw new IllegalStateException("Plugin list needs to contain core module");
        }

        pluginManager = new PluginManager(pluginNameSet, this);
        pluginManager.loadAllPlugins();
    }


    @Override
    public void run() {


        while (!interrupted) {

            try {
                sleep(50);
            } catch (InterruptedException ex) {
                disconnect("Thread interrupted");
            }

            try {
                if (socket.getInputStream().available() == 0) {
                    continue;
                }

                onInputReceived(readLine());


            } catch (IOException ex) {
                logger.fatal("IOException in main loop", ex);
                disconnect("IOException: " + ex);
            } catch (RuntimeException ex) {
                disconnect("Unknown error: " + ex);
                throw ex;
            }
        }

        logger.info("Unloading plugins");
        pluginManager.disableAllPlugins();
        logger.info("Terminating");
    }




    // --- Connection handling ---




    /**
     * Opens the connection to the server and sends the USER/NICK command.
     * <p/>
     * @throws IOException
     */
    public void connect() throws IOException {

        socket = new Socket(host(), port());

        reader =
        new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer =
        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        int starCount = 0;

        socket.setSoTimeout(2000);

        // Wait for the "WELCOME" header, if nothing for 2 seconds
        // proceed with connection
        while (starCount < 4) {
            try {
                String in = readLine();
                if (in.contains("***")) {
                    starCount++;
                }
            } catch (SocketTimeoutException ex) {
                break;
            }
        }

        // Remember to reset the timeout
        socket.setSoTimeout(0);

        changeNick(nick());
        send("USER " + username() + " * * :" + description());

        checkConnectionTimer = new Timer();
        checkConnectionTimer.schedule(new TimeoutCheck(this), TimeoutCheck.TIMEOUT / 2);
    }

    public void disconnect() {
        disconnect("");
    }


    /**
     * Disconnects from the server and closes the sockets.
     * <p/>
     * @param reason The reason for quitting to give the server
     */
    public void disconnect(String reason) {

        try {
            send("QUIT :" + reason);
        } catch (IOException ex) {
            logger.warn("Unable to say goodbye to server.", ex);
        } finally {
            try {
                socket.close();
            } catch (IOException ex) {
                logger.fatal("Exception while closing socket.", ex);
            } catch (NullPointerException ex) {
                logger.fatal("Tried to access non-existant socket", ex);
            }

            checkConnectionTimer.cancel();
        }

        interrupted = true;
    }

    void reconnect() {

        pluginManager.saveAllPlugins();
        disconnect();

        try {
            connect();
        } catch (IOException ex) {
            logger.fatal("Unable to reconnect after timeout", ex);
        }
    }




    // --- IO/Action interface




    /**
     * Sends a command to the server without any additional formatting.
     * Automatically appends carriage return and line feed.
     * <p/>
     * @param rawCommand The command to send
     * <p/>
     * @throws IOException
     */
    public void sendRaw(String rawCommand) throws IOException {

        send(rawCommand);
    }

    /**
     * Change the bot's nick to
     * <code>newNick</code>.
     * <p/>
     * @param newNick The new nick of the bot
     * <p/>
     * @throws IOException
     */
    public void changeNick(String newNick) throws IOException {

        send("NICK " + newNick);
        currentNick = newNick;
    }

    /**
     * Join the target channel.
     * <p/>
     * @param channel The channel to join
     * <p/>
     * @throws IOException
     */
    public void joinChannel(String channel) throws IOException {
        send("JOIN " + channel);
    }

    /**
     * Leaves the target channel.
     * <p/>
     * @param channel The channel to leave
     * <p/>
     * @throws IOException
     */
    public void leaveChannel(String channel) throws IOException {
        leaveChannel(channel, "");
    }

    /**
     * Leaves the target channel with the specified reason.
     * <p/>
     * @param channel The channel to leave
     * @param reason  The reason to give for leaving
     * <p/>
     * @throws IOException
     */
    public void leaveChannel(String channel,
                             String reason) throws IOException {
        sendRaw("PART " + channel + " :" + reason);
    }

    /**
     * Sends a message to the specified user or channel.
     * <p/>
     * @param target  The target of the message
     * @param message The message to send
     * <p/>
     * @throws IOException
     */
    public void sendMessage(String target, String message) throws IOException {

        if (muted.contains(target.toLowerCase())) {
            return;
        }

        if (message.length() > 400) {

            sendRaw("PRIVMSG " + target + " :" + message.substring(0, 401));
            sendMessage(target, message.substring(401));
        } else {

            sendRaw("PRIVMSG " + target + " :" + message);
        }
    }

    /**
     * Sends a notice to the specified user or channel.
     * <p/>
     * @param target  The target of the message
     * @param message The message to send
     * <p/>
     * @throws IOException
     */
    public void sendNotice(String target, String message) throws IOException {

        if (muted.contains(target.toLowerCase())) {
            return;
        }

        if (message.length() > 400) {

            sendRaw("NOTICE " + target + " :" + message.substring(0, 401));
            sendNotice(target, message.substring(401));
        } else {

            sendRaw("NOTICE " + target + " :" + message);
        }
    }

    private String readLine() throws IOException {

        String line = reader.readLine().replace("" + (char) 0x01, "");
        logger.info("[→] " + line);

        if (line.startsWith(":")) {
            line = line.substring(1);
        }

        return line;
    }

    private void send(String command) throws IOException {

        logger.info("[←] " + command);

        writer.write(command + "\r\n");
        writer.flush();
    }




    // --- Auth interface ---



    private void loadAccessLevels() {
        String[] mods = config.getStringArray(ConfigKey.MOD.key());
        String[] admins = config.getStringArray(ConfigKey.ADMIN.key());
        String[] owner = config.getStringArray(ConfigKey.OWNER.key());

        addAllAccessLevels(mods, BotAccessLevel.MOD);
        addAllAccessLevels(admins, BotAccessLevel.ADMIN);
        addAllAccessLevels(owner, BotAccessLevel.OWNER);
    }

    private void addAllAccessLevels(String[] names,
                                    BotAccessLevel level) {
        if (names != null) {
            for (String name: names) {
                accessLevels.put(name.toLowerCase(), level);
            }
        }
    }

    public void updateAccessLevel(String accName,
                                  BotAccessLevel level) {

        if (level == BotAccessLevel.NOT_REGISTERED) {
            return; // Can't set this level
        }

        BotAccessLevel old = accessLevels.get(accName);

        if (level == BotAccessLevel.NORMAL) {
            accessLevels.remove(accName);
        } else {
            accessLevels.put(accName, level);
        }

        if (old != level) {
            switch (level) {
                case ADMIN:
                    addAccessToConfig(accName, ConfigKey.ADMIN);
                    break;
                case MOD:
                    addAccessToConfig(accName, ConfigKey.MOD);
                    break;
                default:
                    break;  // Do nothing
            }

            if (old != null) {
                switch (old) {
                    case ADMIN:
                        removeAccessFromConfig(accName, ConfigKey.ADMIN);
                        break;
                    case MOD:
                        removeAccessFromConfig(accName, ConfigKey.MOD);
                        break;
                    default:
                        break;  // Do nothing
                }
            }
        }
    }

    private void removeAccessFromConfig(String accName,
                                        ConfigKey key) {
        List<Object> names = config.getList(key.key());
        names.remove(accName);
        addSingleProperty(key.key(), names);
    }

    private void addAccessToConfig(String accName,
                                   ConfigKey key) {

        if (!config.getList(key.key()).contains(accName.toLowerCase())) {
            addItemToList(key.key(), accName.toLowerCase());
        }
    }

    /**
     * Returns the bot access level of the specified user. Returns
     * <code>
     * BotAccessLevel.NOT_REGISTERED</code> if the user is not logged in,
     * <code>
     * BotAccessLevel.NORMAL</code> if the access level is not specified, or the
     * access level as specified in the config otherwise.
     * <p/>
     * @param name   The name to check
     * @param isNick Tells whether an account name needs to be retrieved
     * <p/>
     * @return The access level of the user
     * <p/>
     * @throws IOException
     */
    public BotAccessLevel getAccessLevel(String name,
                                         boolean isNick)
            throws IOException {

        String accountName = isNick
                             ? getAccountName(name)
                             : name;

        if (accountName == null || accountName.equals("0")) {
            // Not logged in
            return BotAccessLevel.NOT_REGISTERED;
        }

        BotAccessLevel level = accessLevels.get(accountName.toLowerCase());
        logger.debug("Checking access level for account " + accountName + "... "
                     + level);

        if (level == null) {
            return BotAccessLevel.NORMAL;
        }

        return level;
    }

    /**
     * Returns the access level of the user in the specified channel.
     * <p/>
     * @param nick    The user to check
     * @param channel The channel to check
     * <p/>
     * @return The channel access level
     */
    public ChannelAccessLevel getChanAccess(String nick,
                                            String channel) {

        try {
            sendRaw("NAMES " + channel);
            boolean gotNames = false; // 353

            while (!gotNames) {

                String reply = readLine();
                String[] parts = reply.split(" ");

                if (parts[1].equals("353")) {
                    String replyNormalized = reply.toLowerCase();
                    String nickNormalized = nick.toLowerCase();

                    int indexOfNick = replyNormalized.indexOf(nickNormalized);

                    if (indexOfNick != -1) {
                        char modeChar = replyNormalized.charAt(indexOfNick - 1);

                        if (modeChar == '+') {
                            return ChannelAccessLevel.VOICE;
                        } else if (modeChar == '@') {
                            return ChannelAccessLevel.OP;
                        }

                        return ChannelAccessLevel.NONE;
                    }

                } else if (parts[1].equals("366")) {    // End of Names

                    return ChannelAccessLevel.NONE;
                } else {

                    onInputReceived(reply);
                }
            }

        } catch (IOException ex) {
            logger.fatal("Connection interrupted", ex);
            disconnect("IOException: " + ex);
        }

        return ChannelAccessLevel.NONE;
    }


    /**
     * Returns the nickserv account name of the specified user, or "0" if the
     * user is not logged in.
     * <p/>
     * @param nick The nick to check
     * <p/>
     * @return The account name of the user
     * <p/>
     * @throws IOException
     */
    public String getAccountName(String nick) throws IOException {

        if (whoxSupported) {
            return getAccountNameWHOX(nick);
        } else {
            return getAccountNameWHOIS(nick);
        }
    }

    private String getAccountNameWHOIS(String nick) throws IOException {

        sendRaw("WHOIS " + nick);

        while (true) {

            String reply = readLine();
            String[] parts = reply.split(" ");

            if (parts[1].equals("307")) {
                if (reply.toLowerCase().contains("is a registered nick")) {
                    return getAccountNameNickserv(nick);
                } else if (reply.toLowerCase().contains("has identified for "
                                                        + "this nick")) {
                    return parts[3].toLowerCase();
                }
            } else if (parts[1].equals("330")) {
                return parts[4].toLowerCase();
            } else if (parts[1].equals("318")) {
                break;  // END of WHOIS
            }
        }

        return "0";
    }

    private String getAccountNameNickserv(String nick) throws IOException {

        sendMessage("nickserv", "info " + nick);

        while (true) {

            String reply =
                   readLine().toLowerCase();

            if (reply.contains("invalid command")
                || reply.contains("*** end of info ***")
                || reply.contains("isn't registered")
                || reply.contains("is not registered")
                || reply.contains("for more verbose information")) {
                break;
            } else if (reply.contains("information on")) {
                String[] parts = reply.split(" ");
                String accName = parts[parts.length - 1];

                // Hack off ):
                if (accName.endsWith("):")) {
                    accName = accName.substring(0, accName.length() - 2);
                }

                return accName;
            }
        }

        return "0";
    }

    private String getAccountNameWHOX(String nick) throws IOException {

        sendRaw("WHO " + nick + " %a");

        while (true) {

            String reply = readLine();
            String[] parts = reply.split(" ");

            if (parts[1].equals("354")) {

                String user = parts[3];

                if (user.startsWith(":")) {
                    user = user.substring(1);
                }

                logger.debug("Accountname for " + nick + " is " + user);
                return user;
            } else if (parts[1].equals("315") && parts[3].equals(nick)) {

                logger.debug("Received 315 END OF WHO LIST while checking "
                             + "account name of " + nick);
                break;  // End of WHO list
            } else {

                onInputReceived(reply);
            }
        }


        return "0";
    }




    // --- Input handling ---




    private void onInputReceived(String line) throws IOException {

        if (line.startsWith("PING")) {
            sendRaw("PONG :" + line.split(":")[1]);
        } else {

            String[] parts = line.split(" ");
            String sender = parts[0];
            String type = parts[1];
            Object[] args = ArrayUtils.subarray(parts, 2, parts.length);
            handleChat(sender, type, (String[]) args);
        }
    }

    private void handleChat(String sender,
                            String type,
                            String[] args)
            throws IOException {

        if (type.equals("001")) {

            for (String chan: config.getStringArray(ConfigKey.CHANNELS.key())) {

                joinChannel(chan);
            }
        } else if (type.equals("005")) {

            if (ArrayUtils.contains(args, "WHOX")) {
                whoxSupported = true;
            }
        } else if (type.equals("PRIVMSG")
                   || type.equals("NOTICE")) {


            if (args[1].startsWith(":")) {
                args[1] = args[1].substring(1);
            }

            if (checkForCommandPrefix(args[1]) || checkForBotNamePrefix(args[1])) {

                String plugin, command;
                Object[] realArgs;

                if (checkForCommandPrefix(args[1])) {

                    if (args.length < 3) {
                        sendNotice(args[0], "Missing command.");
                        return;
                    }

                    plugin = args[1].substring(commandPrefix().length());
                    command = args[2];
                    realArgs = ArrayUtils.subarray(args, 3, args.length);
                } else {

                    if (args.length < 4) {
                        sendNotice(args[0], "Missing command.");
                        return;
                    }

                    plugin = args[2];
                    command = args[3];
                    realArgs = ArrayUtils.subarray(args, 4, args.length);
                }

                handleCommand(sender, args[0], plugin, command,
                              (String[]) realArgs);

            }
        }
    }

    private boolean checkForCommandPrefix(String s) {
        return s.startsWith(commandPrefix());
    }

    private boolean checkForBotNamePrefix(String s) {
        return s.toLowerCase().startsWith(nick().toLowerCase());
    }

    private void handleCommand(String sender,
                               String target,
                               String plugin,
                               String command,
                               String[] args)
            throws IOException {

        logger.debug("Received command " + plugin + " " + command);

        User user = new User(sender);

        if (!commandHandler.callCommand(plugin, command, user, target, args)) {
            sendNotice(user.nick(), "Unknown command '" + plugin + " " + command
                                    + "'");
        }
    }




    // --- Plugin interface ---




    /**
     * Returns the command handler used by this bot
     * <p/>
     * @return The command handler
     */
    public CommandHandler getCommandHandler() {
        return commandHandler;
    }

    /**
     * Returns the plugin manager used by this bot
     * <p/>
     * @return The plugin manager
     */
    public PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * Returns the directory in which this bots information are saved.
     * @return the directory
     */
    public String pluginDataDir() {
        return pluginDir() + "/" + host() + "." + port() + "." + nick();
    }

    public void toggleMute(String target) {

        target = target.toLowerCase();

        if (muted.contains(target.toLowerCase())) {
            muted.remove(target.toLowerCase());
        } else {
            muted.add(target.toLowerCase());
        }
    }

    public boolean isMuted(String target) {
        return muted.contains(target.toLowerCase());
    }



    // --- Configuration access methods ---



    private void addSingleProperty(String key, Object value) {
        config.setProperty(key, value);

        try {
            config.save();
        } catch (ConfigurationException ex) {
            logger.error("Unable to save config", ex);
        }
    }

    private void addItemToList(String key, Object value) {
        addSingleProperty(key, config.getList(key).add(value));
    }

    /**
     * Returns the host the bot is connected to.
     * <p/>
     * @return The host
     */
    public String host() {
        return config.getString(ConfigKey.HOST.key());
    }

    /**
     * Returns the remote port the bot is connected to.
     * <p/>
     * @return The remote port
     */
    public int port() {
        return config.getInt(ConfigKey.PORT.key());
    }

    /**
     * Returns the current nick of the bot
     * <p/>
     * @return The current nick
     */
    public String nick() {
        return currentNick;
    }

    /**
     * Returns the username of the bot as seen from the IRC server.
     * <p/>
     * @return The username
     */
    public String username() {
        return config.getString(ConfigKey.USER.key());
    }

    /**
     * Returns the description of the bot as seen from the IRC server.
     * <p/>
     * @return The description of the bot
     */
    public String description() {
        return config.getString(ConfigKey.DESC.key());
    }

    /**
     * Returns the plugin directory this bot uses.
     * <p/>
     * @return The directory name
     */
    public String pluginDir() {
        return config.getString(ConfigKey.PLUGIN_DIR.key(),
                                ConfigKey.PLUGIN_DIR.def());
    }

    /**
     * Returns a copy of the configuration file the bot uses. Changes that
     * modify the copy affect the original configuration, and vice versa.
     * <p/>
     * @return The configuration file
     */
    public FileConfiguration config() {
        return config;
    }

    private String commandPrefix() {
        return config.getString("cmd_prefix", "!");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + host() + ":" + port() + "]";
    }

    private enum ConfigKey {

        HOST("host"),
        PORT("port"),
        NICK("nick"),
        USER("user"),
        DESC("desc"),
        CHANNELS("channels"),
        MOD("access_mod"),
        ADMIN("access_admin"),
        OWNER("access_owner"),
        PLUGIN_DIR("plugin_dir", "plugins"),
        PLUGINS("plugins");
        private String key;
        private String def;
        private boolean required = true;

        private ConfigKey(String key) {
            this.key = key;
        }

        private ConfigKey(String key,
                          String def) {
            this.key = key;
            this.def = def;
            this.required = false;
        }

        public String key() {
            return key;
        }

        public String def() {
            if (required) {
                throw new RuntimeException(toString() + " (" + key + ") is "
                                           + "required and has no default value");
            }

            return def;
        }

        public boolean isRequired() {
            return required;
        }
    }
}
