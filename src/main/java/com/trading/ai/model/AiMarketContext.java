package com.trading.ai.model;

/**
 * AiMarketContext — market-level features shared across all symbol evaluations.
 */
public class AiMarketContext {
    public final double niftyDirection;
    public final double bnfDirection;
    public final double niftyAtrPct;
    public final double vix;
    public final double breadthRatio;
    public final double sessionTimeFraction;
    public final double marketRegimeScore;

    public AiMarketContext(double niftyDirection, double bnfDirection,
                           double niftyAtrPct, double vix, double breadthRatio,
                           double sessionTimeFraction, double marketRegimeScore) {
        this.niftyDirection      = niftyDirection;
        this.bnfDirection        = bnfDirection;
        this.niftyAtrPct         = niftyAtrPct;
        this.vix                 = vix;
        this.breadthRatio        = breadthRatio;
        this.sessionTimeFraction = sessionTimeFraction;
        this.marketRegimeScore   = marketRegimeScore;
    }

    // Getters for Lombok-free access
    public double getNiftyDirection()      { return niftyDirection; }
    public double getBnfDirection()        { return bnfDirection; }
    public double getNiftyAtrPct()         { return niftyAtrPct; }
    public double getVix()                 { return vix; }
    public double getBreadthRatio()        { return breadthRatio; }
    public double getSessionTimeFraction() { return sessionTimeFraction; }
    public double getMarketRegimeScore()   { return marketRegimeScore; }
}