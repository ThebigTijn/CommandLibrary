package com.jazzkuh.commandlib.brigadier;

import com.jazzkuh.commandlib.brigadier.utils.provider.CommandContextProvider;
import com.jazzkuh.commandlib.brigadier.utils.context.CommandContext;
import com.jazzkuh.commandlib.common.*;
import com.jazzkuh.commandlib.common.exception.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class AnnotationCommand<S> implements AnnotationCommandImpl {
    private static final ComponentLogger LOGGER = ComponentLogger.logger("CommandLibrary");

    protected BrigadierCommandLoader<S> commandLoader;
	protected String commandName;
    protected AnnotationSubCommand mainCommand = null;
    protected final List<AnnotationSubCommand> subCommands = new ArrayList<>();

    public AnnotationCommand(BrigadierCommandLoader<S> commandLoader, String commandName) {
        this.commandLoader = commandLoader;
        this.commandName = commandName;
		this.register();
    }

    public AnnotationCommand(BrigadierCommandLoader<S> commandLoader) {
        if (!this.getClass().isAnnotationPresent(com.jazzkuh.commandlib.common.annotations.Command.class)) {
            throw new IllegalArgumentException("AnnotationCommand needs to have a @Command annotation!");
        }

        this.commandLoader = commandLoader;
        this.commandName = this.getClass().getAnnotation(com.jazzkuh.commandlib.common.annotations.Command.class).value();
		this.register();
    }

    @Override
    public String getCommandName() {
        return this.commandName;
    }

    private void executeCommand(AnnotationSubCommand subCommand, CommandContext<S> context, String[] args) {
        CommandContextProvider<S> contextProvider = commandLoader.getCommandContextProvider();
        if (subCommand.getPermission() != null && !contextProvider.hasPermission(context, subCommand.getPermission())) {
            PermissionException permissionException = new PermissionException("You do not have permission to use this command.");
            contextProvider.sendMessage(context, BrigadierCommandLoader.getFormattingProvider().formatError(permissionException, permissionException.getMessage()));
            return;
        }

        AnnotationCommandExecutor<CommandContext<S>> commandExecutor = new AnnotationCommandExecutor<>(subCommand, this);
        AnnotationCommandSender<CommandContext<S>> commandSender = new AnnotationCommandSender<>(context);

        try {
            commandExecutor.execute(commandSender, args);
        } catch (CommandException commandException) {
            if (commandException instanceof ArgumentException) {
                this.formatUsage(context);
            } else if (commandException instanceof PermissionException permissionException) {
                contextProvider.sendMessage(context, BrigadierCommandLoader.getFormattingProvider().formatError(commandException, permissionException.getMessage()));
            } else if (commandException instanceof ContextResolverException contextResolverException) {
                contextProvider.sendMessage(context, BrigadierCommandLoader.getFormattingProvider().formatError(commandException, "A context resolver was not found for: " + contextResolverException.getMessage()));
            } else if (commandException instanceof ParameterException parameterException) {
                contextProvider.sendMessage(context, BrigadierCommandLoader.getFormattingProvider().formatError(commandException, parameterException.getMessage()));
            } else if (commandException instanceof ErrorException errorException) {
                contextProvider.sendMessage(context, BrigadierCommandLoader.getFormattingProvider().formatError(commandException, "An error occurred while executing this subcommand: " + errorException.getMessage()));
            }
        }
    }

    public LiteralArgumentBuilder<S> register() {
        LiteralArgumentBuilder<S> builder = LiteralArgumentBuilder.literal(this.commandName);
        builder.then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())).executes(context -> {
            String[] args = Arrays.stream(StringArgumentType.getString(context, "args").split(" ")).skip(1).toArray(String[]::new);
            CommandContext<S> commandContext = new CommandContext<>(context.getSource());

            if (args.length < 1) {
                if (this.mainCommand == null) {
                    this.formatUsage(commandContext);
                    return 1;
                }

                this.executeCommand(this.mainCommand, commandContext, args);
                return 1;
            }

            for (AnnotationSubCommand subCommand : subCommands) {
                if (!args[0].equalsIgnoreCase(subCommand.getName()) && !subCommand.getAliases().contains(args[0].toLowerCase()))
                    continue;
                this.executeCommand(subCommand, commandContext, args);
                return 1;
            }

            this.executeCommand(this.mainCommand, commandContext, args);
            return 1;
        });
		try {
			commandLoader.getCommandDispatcher().register(builder);
			LOGGER.info("Registered command: {}", this.getCommandName());
		} catch (Exception e) {
			LOGGER.error(Component.text("Failed to register command: " + this.getCommandName()), e);
		}
        return builder;
    }

    public void formatUsage(CommandContext<S> context) {
        CommandContextProvider<S> contextProvider = commandLoader.getCommandContextProvider();
        if (mainCommand.getUsage() != null && !this.mainCommand.getUsage().isEmpty() && this.subCommands.isEmpty()) {
            contextProvider.sendMessage(context, Component.text("Invalid command syntax. Correct command syntax is: ", TextColor.fromHexString("#FBFB00")));
            contextProvider.sendMessage(context, Component.text("/" + this.getCommandName() + this.mainCommand.getUsage() + " - " + this.mainCommand.getDescription(), TextColor.fromHexString("#FBFB00")));
            return;
        }

        contextProvider.sendMessage(context, Component.text("Invalid command syntax. Correct command syntax's are:", TextColor.fromHexString("#FBFB00")));
        if (mainCommand.getUsage() != null && !mainCommand.getUsage().isEmpty()) {
            contextProvider.sendMessage(context, Component.text("/" + this.getCommandName() + this.mainCommand.getUsage() + " - " + this.mainCommand.getDescription(), TextColor.fromHexString("#FBFB00")));
        }

        for (AnnotationSubCommand subCommand : this.subCommands) {
            if (subCommand.getPermission() == null || contextProvider.hasPermission(context, subCommand.getPermission())) {
                contextProvider.sendMessage(context, Component.text("/" + this.getCommandName() + " " + subCommand.getName() + subCommand.getUsage() + " - " + subCommand.getDescription(), TextColor.fromHexString("#FBFB00")));
            }
        }
    }}