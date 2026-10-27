package com.heishandashu.ironfarm.engine.rule;

/**
 * 单条条件的判定结果。
 *
 * <p>{@link Status} 是三态而不是布尔：{@code UNKNOWN} 是必需的。
 * 例如村民区块尚未加载、或玩家关掉了「检测傀儡范围」的采样，此时条件
 * 既不能算通过也不能算失败。把它压成布尔会让界面显示一个错误的红叉。</p>
 */
public final class RuleResult {

    public enum Status {
        /** 满足。 */
        PASS,
        /** 不满足。 */
        FAIL,
        /** 无法判定（区块未加载、数据缺失等）。 */
        UNKNOWN
    }

    public final Condition condition;
    public final Status status;
    /**
     * 失败原因的机器可读代码。用于统计页归类，不直接显示给玩家。
     * 通过的条件下为 {@code null}。
     */
    public final String reasonCode;
    /**
     * 该条件对应的实测数值，供界面显示「还差多少」。
     * 例如睡眠条件会带上已经过去的 tick 数，群体条件会带上实际人数。
     * 没有数值时为 {@link Double#NaN}。
     */
    public final double value;
    /**
     * 该条件的门槛值，与 {@link #value} 配对。
     * 没有门槛时为 {@link Double#NaN}。
     */
    public final double threshold;

    private RuleResult(Condition condition, Status status, String reasonCode, double value, double threshold) {
        this.condition = condition;
        this.status = status;
        this.reasonCode = reasonCode;
        this.value = value;
        this.threshold = threshold;
    }

    public static RuleResult pass(Condition c) {
        return new RuleResult(c, Status.PASS, null, Double.NaN, Double.NaN);
    }

    public static RuleResult pass(Condition c, double value, double threshold) {
        return new RuleResult(c, Status.PASS, null, value, threshold);
    }

    public static RuleResult fail(Condition c, String reasonCode) {
        return new RuleResult(c, Status.FAIL, reasonCode, Double.NaN, Double.NaN);
    }

    public static RuleResult fail(Condition c, String reasonCode, double value, double threshold) {
        return new RuleResult(c, Status.FAIL, reasonCode, value, threshold);
    }

    public static RuleResult unknown(Condition c, String reasonCode) {
        return new RuleResult(c, Status.UNKNOWN, reasonCode, Double.NaN, Double.NaN);
    }

    public boolean isPass() {
        return status == Status.PASS;
    }

    public boolean isFail() {
        return status == Status.FAIL;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }

    /** 失败原因的多语言键；非失败状态返回 null。 */
    public String reasonLangKey() {
        if (reasonCode == null) {
            return null;
        }
        return "ironfarm.reason." + reasonCode;
    }

    @Override
    public String toString() {
        String base = condition.key() + "=" + status;
        if (reasonCode != null) {
            base += "(" + reasonCode + ")";
        }
        if (!Double.isNaN(value)) {
            base += "[" + value + "/" + threshold + "]";
        }
        return base;
    }
}
