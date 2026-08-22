package cn.blockeco.exchange.application;

/** Counts from one database-only startup pass. */
public record IpoSubscriptionRecoverySummary(int completedFromEscrow, int markedAmbiguous, int alreadyAmbiguous) { }
