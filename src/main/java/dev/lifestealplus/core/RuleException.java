package dev.lifestealplus.core;

/** Something the rules don't allow. The message is written for the player who tried it. */
public class RuleException extends RuntimeException {

    public RuleException(String message) {
        super(message);
    }
}
