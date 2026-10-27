package com.heishandashu.ironfarm.engine.stats;

/**
 * 一条「某村民这次为什么没刷」的记录。
 *
 * <p>统计页要回答的问题是「统计到哪些村民铁傀儡刷新失败，原因是什么」，
 * 所以这里按**村民 + 原因**聚合，而不是流水账。聚合键是
 * {@code (villagerEntityId, reasonCode)}。</p>
 */
public final class FailureRecord {

    public final int villagerEntityId;
    public final String reasonCode;
    /** 该组合出现的次数。 */
    public long count;
    /** 最近一次出现的游戏时间。 */
    public long lastGameTime;
    /** 最近一次出现时该条件的实测值与门槛，便于显示「差多少」。 */
    public double lastValue;
    public double lastThreshold;

    public FailureRecord(int villagerEntityId, String reasonCode) {
        this.villagerEntityId = villagerEntityId;
        this.reasonCode = reasonCode;
        this.count = 0;
        this.lastGameTime = 0;
        this.lastValue = Double.NaN;
        this.lastThreshold = Double.NaN;
    }

    public void hit(long gameTime, double value, double threshold) {
        count++;
        lastGameTime = gameTime;
        lastValue = value;
        lastThreshold = threshold;
    }

    /** 多语言原因键。 */
    public String reasonLangKey() {
        return "ironfarm.reason." + reasonCode;
    }

    @Override
    public String toString() {
        return "Failure{#" + villagerEntityId + " " + reasonCode + " x" + count + "}";
    }
}
