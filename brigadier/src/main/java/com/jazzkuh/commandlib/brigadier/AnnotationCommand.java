package com.jazzkuh.commandlib.brigadier;

import com.jazzkuh.commandlib.common.*;
import com.jazzkuh.commandlib.common.annotations.Main;
import com.jazzkuh.commandlib.common.annotations.Optional;
import com.jazzkuh.commandlib.common.annotations.Subcommand;
import com.jazzkuh.commandlib.common.exception.*;
import com.jazzkuh.commandlib.common.resolvers.ContextResolver;
import com.jazzkuh.commandlib.common.resolvers.Resolvers;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/* Made by ThebigTijn */

public class AnnotationCommand<S> implements AnnotationCommandImpl {
	private static final ComponentLogger LOGGER = ComponentLogger.logger("CommandLibrary");

	protected String commandName;
	protected final List<AnnotationSubCommand> mainCommands = new ArrayList<>();
	protected final List<AnnotationSubCommand> subCommands = new ArrayList<>();
	protected final Class<S> senderType;

	public AnnotationCommand(String commandName, Class<S> senderType) {
		this.commandName = commandName;
		this.senderType = senderType;
		this.init();
	}

	public AnnotationCommand(Class<S> senderType) {
		this.senderType = senderType;
		if (!this.getClass().isAnnotationPresent(com.jazzkuh.commandlib.common.annotations.Command.class)) {
			throw new IllegalArgumentException("AnnotationCommand needs to have a @Command annotation!");
		}

		this.commandName = this.getClass().getAnnotation(com.jazzkuh.commandlib.common.annotations.Command.class).value();
		this.init();
	}

	private void init() {
		List<Method> mainCommandMethods = Arrays.stream(this.getClass().getMethods())
				.filter(method -> method.isAnnotationPresent(Main.class))
				.toList();

		mainCommandMethods.forEach(method -> this.mainCommands.add(AnnotationCommandParser.parse(this, method)));

		List<Method> subcommandMethods = Arrays.stream(this.getClass().getMethods())
				.filter(method -> method.isAnnotationPresent(Subcommand.class))
				.toList();
		subcommandMethods.forEach(method -> this.subCommands.add(AnnotationCommandParser.parse(this, method)));
	}

	@Override
	public String getCommandName() {
		return this.commandName;
	}

	public void register(CommandDispatcher<S> dispatcher) {
		LiteralArgumentBuilder<S> command = LiteralArgumentBuilder.<S>literal(this.commandName);

		// Register subcommands
		for (AnnotationSubCommand subCommand : this.subCommands) {
			LiteralArgumentBuilder<S> subCommandBuilder = LiteralArgumentBuilder.<S>literal(subCommand.getName());

			// Add aliases for subcommands
			for (String alias : subCommand.getAliases()) {
				LiteralArgumentBuilder<S> aliasBuilder = LiteralArgumentBuilder.<S>literal(alias);
				buildSubCommandArguments(aliasBuilder, subCommand);
				command.then(aliasBuilder);
			}

			buildSubCommandArguments(subCommandBuilder, subCommand);
			command.then(subCommandBuilder);
		}

		// Register main commands with dynamic arguments
		if (!this.mainCommands.isEmpty()) {
			RequiredArgumentBuilder<S, String> argsBuilder = RequiredArgumentBuilder
					.<S, String>argument("args", StringArgumentType.greedyString())
					.suggests(this::suggestMainCommands)
					.executes(this::executeMainCommand);

			command.then(argsBuilder);
			command.executes(this::executeMainCommand);
		}

		dispatcher.register(command);

		try {
			dispatcher.register(command);
			LOGGER.info("Registered command: {}", this.getCommandName());
		} catch (Exception exception) {
			LOGGER.info("Unable to register command: {}", this.getCommandName());
		}

	}

	private void buildSubCommandArguments(LiteralArgumentBuilder<S> builder, AnnotationSubCommand subCommand) {
		Method method = subCommand.getMethod();
		Parameter[] parameters = method.getParameters();

		LiteralArgumentBuilder<S> current = builder;

		for (int i = 1; i < parameters.length; i++) {
			Parameter param = parameters[i];
			String paramName = param.getName();
			boolean isOptional = param.isAnnotationPresent(Optional.class);

			RequiredArgumentBuilder<S, String> argBuilder = RequiredArgumentBuilder
					.<S, String>argument(paramName, StringArgumentType.word())
					.suggests(createSuggestionProvider(subCommand, i - 1));

			if (i == parameters.length - 1) {
				argBuilder.executes(context -> this.executeSubCommand(context, subCommand));
			}

			current.then(argBuilder);

			if (isOptional) {
				current.executes(context -> this.executeSubCommand(context, subCommand));
			}
		}

		if (parameters.length == 1) {
			builder.executes(context -> this.executeSubCommand(context, subCommand));
		}
	}

	private SuggestionProvider<S> createSuggestionProvider(AnnotationSubCommand subCommand, int argIndex) {
		return (context, builder) -> {
			AnnotationCommandSender<S> commandSender = new AnnotationCommandSender<>(context.getSource());
			AnnotationCommandExecutor<S> executor = new AnnotationCommandExecutor<>(subCommand, this);

			String[] args = extractArgs(context, argIndex + 1);
			List<String> suggestions = executor.complete(commandSender, args);

			for (String suggestion : suggestions) {
				builder.suggest(suggestion);
			}

			return builder.buildFuture();
		};
	}

	private CompletableFuture<Suggestions> suggestMainCommands(CommandContext<S> context, SuggestionsBuilder builder) {
		AnnotationCommandSender<S> commandSender = new AnnotationCommandSender<>(context.getSource());
		String input = builder.getRemaining();
		String[] args = input.isEmpty() ? new String[0] : input.split(" ");

		List<String> suggestions = new ArrayList<>();

		for (AnnotationSubCommand mainCommand : this.mainCommands) {
			if (hasPermission(context.getSource(), mainCommand.getPermission())) {
				AnnotationCommandExecutor<S> executor = new AnnotationCommandExecutor<>(mainCommand, this);
				suggestions.addAll(executor.complete(commandSender, args));
			}
		}

		for (String suggestion : suggestions) {
			if (suggestion.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
				builder.suggest(suggestion);
			}
		}

		return builder.buildFuture();
	}

	private int executeMainCommand(CommandContext<S> context) {
		String argsString = "";
		try {
			argsString = StringArgumentType.getString(context, "args");
		} catch (IllegalArgumentException ignored) {
			// No args provided
		}

		String[] args = argsString.isEmpty() ? new String[0] : argsString.split(" ");

		if (this.mainCommands.isEmpty()) {
			sendUsage(context.getSource());
			return 1;
		}

		if (this.mainCommands.size() == 1) {
			this.executeCommand(this.mainCommands.get(0), context.getSource(), args);
		} else {
			AnnotationSubCommand matchingCommand = findMatchingMainCommand(args);
			if (matchingCommand != null) {
				this.executeCommand(matchingCommand, context.getSource(), args);
			} else {
				sendUsage(context.getSource());
			}
		}

		return 1;
	}

	private int executeSubCommand(CommandContext<S> context, AnnotationSubCommand subCommand) {
		String[] args = extractArgsFromContext(context, subCommand);
		this.executeCommand(subCommand, context.getSource(), args);
		return 1;
	}

	private String[] extractArgsFromContext(CommandContext<S> context, AnnotationSubCommand subCommand) {
		Method method = subCommand.getMethod();
		Parameter[] parameters = method.getParameters();
		List<String> args = new ArrayList<>();

		for (int i = 1; i < parameters.length; i++) {
			Parameter param = parameters[i];
			String paramName = param.getName();

			try {
				String value = StringArgumentType.getString(context, paramName);
				args.add(value);
			} catch (IllegalArgumentException e) {
				if (!param.isAnnotationPresent(Optional.class)) {
					break;
				}
			}
		}

		return args.toArray(new String[0]);
	}

	private String[] extractArgs(CommandContext<S> context, int maxArgs) {
		List<String> args = new ArrayList<>();
		for (int i = 0; i < maxArgs; i++) {
			try {
				String arg = StringArgumentType.getString(context, "arg" + i);
				args.add(arg);
			} catch (IllegalArgumentException e) {
				break;
			}
		}
		return args.toArray(new String[0]);
	}

	private AnnotationSubCommand findMatchingMainCommand(String[] args) {
		if (mainCommands.size() == 1) {
			return mainCommands.get(0);
		}

		for (AnnotationSubCommand mainCommand : mainCommands) {
			if (canCommandHandleArgs(mainCommand, args)) {
				return mainCommand;
			}
		}

		return null;
	}

	private boolean canCommandHandleArgs(AnnotationSubCommand command, String[] args) {
		Method method = command.getMethod();
		Parameter[] parameters = method.getParameters();

		int requiredParamCount = 0;
		int totalParamCount = parameters.length - 1;

		for (int i = 1; i < parameters.length; i++) {
			if (!parameters[i].isAnnotationPresent(Optional.class)) {
				requiredParamCount++;
			}
		}

		if (args.length < requiredParamCount || args.length > totalParamCount) {
			return false;
		}

		for (int i = 0; i < args.length && i + 1 < parameters.length; i++) {
			Parameter param = parameters[i + 1];
			Class<?> paramType = param.getType();
			String arg = args[i];

			if (!isArgCompatibleWithType(arg, paramType)) {
				return false;
			}
		}

		return true;
	}

	private boolean isArgCompatibleWithType(String arg, Class<?> type) {
		if (type == int.class || type == Integer.class) {
			try {
				Integer.parseInt(arg);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		if (type == double.class || type == Double.class) {
			try {
				Double.parseDouble(arg);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		if (type == float.class || type == Float.class) {
			try {
				Float.parseFloat(arg);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		if (type == long.class || type == Long.class) {
			try {
				Long.parseLong(arg);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		if (type == boolean.class || type == Boolean.class) {
			return "true".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg);
		}

		if (type.isEnum()) {
			try {
				Enum.valueOf((Class<? extends Enum>) type, arg.toUpperCase());
				return true;
			} catch (IllegalArgumentException e) {
				return false;
			}
		}

		ContextResolver<?> resolver = Resolvers.context(type);
		if (resolver != null) {
			try {
				return resolver.resolve(arg) != null;
			} catch (Exception e) {
				return false;
			}
		}

		return type == String.class;
	}

	private void executeCommand(AnnotationSubCommand subCommand, S sender, String[] args) {
		if (subCommand.getPermission() != null && !hasPermission(sender, subCommand.getPermission())) {
			PermissionException permissionException = new PermissionException("You do not have permission to use this command.");
			sendErrorMessage(sender, permissionException.getMessage());
			return;
		}

		AnnotationCommandExecutor<S> commandExecutor = new AnnotationCommandExecutor<>(subCommand, this);
		AnnotationCommandSender<S> commandSender = new AnnotationCommandSender<>(sender);

		try {
			commandExecutor.execute(commandSender, args);
		} catch (CommandException commandException) {
			switch (commandException) {
				case ArgumentException argumentException -> sendUsage(sender);
				case PermissionException permissionException ->
						sendErrorMessage(sender, permissionException.getMessage());
				case ContextResolverException contextResolverException ->
						sendErrorMessage(sender, "A context resolver was not found for: " + contextResolverException.getMessage());
				case ParameterException parameterException -> sendErrorMessage(sender, parameterException.getMessage());
				case ErrorException errorException ->
						sendErrorMessage(sender, "An error occurred while executing this subcommand: " + errorException.getMessage());
				default -> {
				}
			}
		}
	}

	private boolean hasPermission(S sender, String permission) {
		// Override this method in implementations to check permissions
		return permission == null;
	}

	private void sendErrorMessage(S sender, String message) {
		// Override this method in implementations to send error messages
		System.err.println("Error: " + message);
	}

	private void sendUsage(S sender) {
		List<String> usageMessages = new ArrayList<>();

		for (AnnotationSubCommand mainCommand : this.mainCommands) {
			if (hasPermission(sender, mainCommand.getPermission())) {
				String usage = "/" + this.getCommandName() + mainCommand.getUsage() + " - " + mainCommand.getDescription();
				usageMessages.add(usage);
			}
		}

		for (AnnotationSubCommand subCommand : this.subCommands) {
			if (hasPermission(sender, subCommand.getPermission())) {
				String usage = "/" + this.getCommandName() + " " + subCommand.getName() + subCommand.getUsage() + " - " + subCommand.getDescription();
				usageMessages.add(usage);
			}
		}

		if (usageMessages.isEmpty()) {
			sendErrorMessage(sender, "No available command syntaxes.");
			return;
		}

		if (usageMessages.size() == 1) {
			sendMessage(sender, "Invalid command syntax. Correct command syntax is: " + usageMessages.get(0));
		} else {
			sendMessage(sender, "Invalid command syntax. Correct command syntax's are:");
			for (String usage : usageMessages) {
				sendMessage(sender, usage);
			}
		}
	}

	private void sendMessage(S sender, String message) {
		// Override this method in implementations to send messages
		System.out.println(message);
	}
}