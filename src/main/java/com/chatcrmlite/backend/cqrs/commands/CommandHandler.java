package com.chatcrmlite.backend.cqrs.commands;

/**
 * Interface for Command Handlers.
 */
public interface CommandHandler<C extends Command> {
    void handle(C command);
}
