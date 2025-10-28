package com.jazzkuh.commandlib.brigadier.utils.provider;

import com.jazzkuh.commandlib.brigadier.utils.context.CommandContext;
import net.kyori.adventure.text.Component;

public interface CommandContextProvider<S> {

    void sendMessage(CommandContext<S> source, Component message);

    boolean hasPermission(CommandContext<S> source, String permission);
}
