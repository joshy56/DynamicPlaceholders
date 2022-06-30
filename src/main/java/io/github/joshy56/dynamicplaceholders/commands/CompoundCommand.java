package io.github.joshy56.dynamicplaceholders.commands;

import co.aikar.timings.Timing;
import co.aikar.timings.TimingsManager;
import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerCommandException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import io.github.joshy56.dynamicplaceholders.util.AdvancedBooleans;
import io.github.joshy56.dynamicplaceholders.util.Storage;
import org.apache.commons.lang.BooleanUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by joshy23 (justJoshy23 - joshy56) on 16/6/2022.
 */
public abstract class CompoundCommand extends TranslatableCommand implements CommandMap {
    private final Map<String, Command> knownCommands;

    /**
     * Pattern that match on single word, double-quoted phrase, phrase single-quoted and word with apostrophe.
     * <ul>
     *     <li>Single word: word</li>
     *     <li>Phrase double-quoted: "another double-quoted phrase"</li>
     *     <li>Phrase single-quoted: 'another single-quoted phrase'</li>
     *     <li>Word with apostrophe: "yeah i'm a word with apostrophe"</li>
     * </ul>
     */
    protected static final Pattern COMMAND_ARGUMENT_PATTERN;

    static {
        COMMAND_ARGUMENT_PATTERN = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']+)*'");
    }

    protected CompoundCommand(@NotNull String name, @NotNull String description, @NotNull String usageMessage, @NotNull List<String> aliases, @NotNull final Storage storage) {
        super(name, description, usageMessage, aliases, storage);
        knownCommands = new HashMap<>();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand@execute(CommandSender=" + ((sender instanceof Player) ? "Player" : "Other") + " Alias=" + alias + " Args=" + Arrays.toString(args) + ")");
        if (Strings.isNullOrEmpty(alias))
            return false;

        if (
                AdvancedBooleans.xor(
                        getAliases().stream().noneMatch(alias::equalsIgnoreCase),
                        !alias.equalsIgnoreCase(getLabel())
                )
        )
            return false;

        String cmdLine = String.join(" ", args);
        List<String> complexArgs = getArgumentsOf(cmdLine);
        Bukkit.getConsoleSender()
                .sendMessage("Processed args=" + Arrays.toString(complexArgs.toArray(new String[0])));
        if (complexArgs.isEmpty())
            return execute(sender);

        return dispatch(sender, cmdLine);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull List<String> args) {
        return dispatch(sender, String.join(" ", args));
    }

    abstract protected boolean execute(@NotNull CommandSender sender);

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        return tabComplete(sender, alias, args, null);
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args, @Nullable Location location) throws IllegalArgumentException {
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand@tabComplete(CommandSender=" + ((sender instanceof Player) ? "Player" : "Other") + " Alias=" + alias + " Args=" + Arrays.toString(args) + ")");
        if (Strings.isNullOrEmpty(alias))
            return new ArrayList<>(0);

        if (getAliases().stream().noneMatch(alias::equalsIgnoreCase))
            return new ArrayList<>(0);

        String cmdLine = String.join(" ", args);
        List<String> complexArgs = getArgumentsOf(cmdLine);
        Bukkit.getConsoleSender()
                .sendMessage("Processed args=" + Arrays.toString(complexArgs.toArray(new String[0])));
        if (complexArgs.isEmpty()) {
            return new ImmutableList.Builder<String>()
                    .addAll(tabComplete(sender, location))
                    .addAll(tabComplete(sender, cmdLine, location))
                    .build();
        }

        return Optional
                .ofNullable(tabComplete(sender, cmdLine, location))
                .orElse(new ArrayList<>(0));
    }

    abstract protected @NotNull List<String> tabComplete(@NotNull CommandSender sender, @Nullable Location location);

/*    public @NotNull CompoundCommand setMessagesSection(@NotNull ConfigurationSection section) {
        Preconditions.checkNotNull(
                section,
                "Please choose a not-null section..."
        );

        if (getCommandMap() instanceof CompoundCommand) {
            ((CompoundCommand) getCommandMap()).setMessagesSection(section);
            return this;
        }

        this.messages = section;
        return this;
    }*/

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerAll(@NotNull String fallbackPrefix, @NotNull List<Command> commands) {
        if (commands == null || commands.isEmpty())
            return;

        for (Command command :
                commands) {
            register(fallbackPrefix, command);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean register(@NotNull String fallbackPrefix, @NotNull Command command) {
        return register(command.getName(), fallbackPrefix, command);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean register(@NotNull String label, @NotNull String fallbackPrefix, @NotNull Command command) {
        command.timings = TimingsManager.getCommandTiming(fallbackPrefix, command); // Paper
        label = label.toLowerCase().trim();
        String finalFallbackPrefix = fallbackPrefix.toLowerCase().trim();
        boolean registered = register(label, command, false, finalFallbackPrefix);

        List<String> aliases = command.getAliases();
        aliases.add(label);
        aliases.removeIf(alias -> !register(alias, command, true, finalFallbackPrefix));
        command.setAliases(aliases);

        // If we failed to register under the real name, we need to set the command label to the direct address
        if (!registered)
            command.setLabel(finalFallbackPrefix + ":" + label);

        // Register to us so further updates of the commands label and aliases are postponed until its re-registered
        command.register(this);

        return registered;
    }

    /**
     * Registers a command with the given name is possible. Also uses
     * fallbackPrefix to create a unique name.
     *
     * @param label          the name of the command, without the '/'-prefix.
     * @param command        the command to register
     * @param isAlias        whether the command is an alias
     * @param fallbackPrefix a prefix which is prepended to the command for a
     *                       unique address
     * @return true if command was registered, false otherwise.
     */
    private synchronized boolean register(@NotNull String label, @NotNull Command command, boolean isAlias, @NotNull String fallbackPrefix) {
        knownCommands.put(fallbackPrefix + ":" + label, command);

        if ((command instanceof BukkitCommand || isAlias) && knownCommands.containsKey(label))
            return false;

        Command conflict = getCommand(label);
        if (conflict != null && conflict.getLabel().equalsIgnoreCase(label))
            return false;

        if (!isAlias)
            command.setLabel(label);
        knownCommands.put(label, command);

        return true;
    }

    @Override
    public boolean dispatch(@NotNull CommandSender sender, @NotNull String cmdLine) throws CommandException {
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand#CommandMap@dispatch(cmdLine=" + cmdLine + ")");
        List<String> args = getArgumentsOf(cmdLine);
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand#CommandMap@dispatch(args=" + Arrays.toString(args.toArray()) + ")");
        if (args.isEmpty())
            return false;

        String label = args.get(0).toLowerCase();
        Command target = getCommand(label);
        if (target == null)
            return false;

        // Paper start - Plugins do weird things to workaround normal registration
        if (target.timings == null) {
            target.timings = co.aikar.timings.TimingsManager.getCommandTiming(null, target);
        }
        // Paper end

        try {
            try (Timing ignored = target.timings.startTiming()) { // Paper - use try with resources
                // Note: we don't return the result of target.execute as that's success / failure, we return handled (true) or not handled (false)
                Bukkit.getConsoleSender()
                        .sendMessage("CommandAlias=" + label + " Registered=" + target.isRegistered() + " CommandAliases=" + Arrays.toString(target.getAliases().toArray()));
                target.execute(sender, label, args.subList(1, args.size()).toArray(new String[0]));
            } // target.timings.stopTiming(); // Spigot // Paper
        } catch (CommandException ex) {
            new ServerExceptionEvent(new ServerCommandException(ex, target, sender, args.toArray(new String[0]))).callEvent(); // Paper
            // target.timings.stopTiming(); // Spigot // Paper
            throw ex;
        } catch (Throwable ex) {
            //target.timings.stopTiming(); // Spigot // Paper
            String msg = "Unhandled exception executing '" + cmdLine + "' in " + target;
            new ServerExceptionEvent(new ServerCommandException(ex, target, sender, args.toArray(new String[0]))).callEvent(); // Paper
            throw new CommandException(msg, ex);
        }

        // return true as command was handled
        return true;
    }



    @Override
    public synchronized void clearCommands() {
        getKnownCommands()
                .values()
                .forEach(
                        command -> command.unregister(this)
                );
        getKnownCommands().clear();
    }

    @Override
    public @Nullable Command getCommand(@NotNull String name) {
        return getKnownCommands().get(name.toLowerCase());
    }

    @Override
    public @Nullable List<String> tabComplete(@NotNull CommandSender sender, @NotNull String cmdLine) throws IllegalArgumentException {
        return tabComplete(sender, cmdLine, (Location) null);
    }

    @Override
    public @Nullable List<String> tabComplete(@NotNull CommandSender sender, @NotNull String cmdLine, @Nullable Location location) throws IllegalArgumentException {
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand#CommandMap@tabComplete(cmdLine=" + cmdLine + ")");
        List<String> args = getArgumentsOf(cmdLine);
        Bukkit.getConsoleSender()
                .sendMessage("DPH:Debug CompoundCommand#CommandMap@tabComplete(args=" + Arrays.toString(args.toArray()) + ")");
        if (args.isEmpty())
            return getKnownCommands()
                    .entrySet()
                    .stream()
                    .filter(
                            entry -> entry.getValue().testPermissionSilent(sender)
                    )
                    .map(Map.Entry::getKey)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());

        if (args.size() == 1)
            return getKnownCommands()
                    .entrySet()
                    .stream()
                    .filter(
                            entry -> StringUtil.startsWithIgnoreCase(entry.getKey(), args.get(0))
                    )
                    .filter(
                            entry -> entry.getValue().testPermissionSilent(sender)
                    )
                    .map(Map.Entry::getKey)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());

        return Optional.ofNullable(getKnownCommands().get(args.get(0)))
                .filter(
                        command -> command.testPermissionSilent(sender)
                )
                .map(
                        command -> command.tabComplete(sender, args.get(0), args.subList(1, args.size()).toArray(new String[0]))
                )
                .orElse(new ArrayList<>(0));
    }

    @Override
    public @NotNull Map<String, Command> getKnownCommands() {
        return knownCommands;
    }

    protected List<String> getArgumentsOf(@NotNull final String commandLine) {
        ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
        Matcher matcher = COMMAND_ARGUMENT_PATTERN.matcher(commandLine);
        while (matcher.find()) {
            Bukkit.getConsoleSender()
                    .sendMessage("Group count=" + matcher.groupCount());
            if (matcher.group(1) != null) {
                // Add double-quoted string without the quotes
                argsBuilder.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                // Add single-quoted string without the quotes
                argsBuilder.add(matcher.group(2));
            } else {
                // Add unquoted word
                argsBuilder.add(matcher.group());
            }
        }
        return argsBuilder.build();
    }
}