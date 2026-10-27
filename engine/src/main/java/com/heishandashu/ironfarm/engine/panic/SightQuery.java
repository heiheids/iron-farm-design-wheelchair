package com.heishandashu.ironfarm.engine.panic;

import com.heishandashu.ironfarm.engine.model.BlockPos3;

/**
 * 视线查询接口。与 {@link com.heishandashu.ironfarm.engine.spawn.BlockQuery} 分开，
 * 因为「能不能看见」和「方块是什么」在游戏里是两套 API，而且实现代价差很多。
 */
public interface SightQuery {

    /**
     * 从 {@code from} 的眼睛位置到 {@code to} 的眼睛位置之间是否无遮挡。
     *
     * <p>原版村民判定恐慌用的是**眼对眼**的视线检查，
     * 所以这里必须传眼睛坐标而不是方块中心。</p>
     */
    boolean clearLineOfSight(BlockPos3 from, double fromEyeHeight, BlockPos3 to, double toEyeHeight);
}
