package com.jazzkuh.commandlib.brigadier;

import com.jazzkuh.commandlib.brigadier.utils.provider.CommandContextProvider;
import com.jazzkuh.commandlib.common.chat.FormattingProvider;
import com.mojang.brigadier.CommandDispatcher;
import lombok.Getter;
import lombok.Setter;

public class BrigadierCommandLoader<S> {
    @Getter @Setter
    private static FormattingProvider formattingProvider;

    @Getter @Setter
    private CommandContextProvider<S> commandContextProvider;

	@Getter @Setter
	private CommandDispatcher<S> commandDispatcher;

}
