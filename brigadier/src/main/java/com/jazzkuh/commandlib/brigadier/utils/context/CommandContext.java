package com.jazzkuh.commandlib.brigadier.utils.context;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CommandContext<S> {
    private final S source;
}
