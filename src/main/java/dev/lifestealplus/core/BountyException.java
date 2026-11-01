package dev.lifestealplus.core;

/** A bounty the rules don't allow. The message is written for the player who tried to place it. */
public class BountyException extends RuleException {

    public BountyException(String message) {
        super(message);
    }
}
