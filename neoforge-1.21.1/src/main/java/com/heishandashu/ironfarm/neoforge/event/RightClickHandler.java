package com.heishandashu.ironfarm.neoforge.event;

import com.heishandashu.ironfarm.neoforge.IronFarmClientState;
import com.heishandashu.ironfarm.neoforge.gui.VillagerStatusScreen;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 右键村民打开状态页。
 *
 * <p><b>按需求的两条分支</b>：</p>
 * <ol>
 *   <li><b>有交易界面</b>（有职业且非婴儿）：不拦截原版，
 *       让交易界面正常打开，再由 {@code MerchantScreenMixin}
 *       在底部追加「村民状态」按钮。</li>
 *   <li><b>无交易界面</b>（失业/婴儿/没有可交易项）：直接打开状态页。</li>
 * </ol>
 *
 * <p>怎么判断"有没有交易界面"：婴儿一定没有；无职业村民也没有。
 * 用 {@code getProfession() == VillagerProfession.NONE} 判定。</p>
 *
 * <p>⚠ 返回 {@link InteractionResult#PASS} 让原版继续处理，
 * 绝不能返回 FAIL —— 那会取消原版的交易界面打开，
 * 玩家会觉得"右键村民没反应"。这条在 泡世界 项目里踩过（见经验文档）。</p>
 */
public final class RightClickHandler {

    private RightClickHandler() {
    }

    /**
     * NeoForge 的事件入口。
     *
     * <p>Fabric 用 {@code UseEntityCallback}（可返回 InteractionResult 干预结果）；
     * NeoForge 对应 {@link net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract}。
     * 它不影响原版行为 —— 正好符合需求：只在旁边打开界面，不改变右键的原始结果。</p>
     */
    public static void onEntityInteract(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        onUseEntity(event.getEntity(), event.getLevel(),
                event.getHand(), event.getTarget(), null);
    }

    private static InteractionResult onUseEntity(
            Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hit) {
        // 只在客户端处理：这是纯显示功能，不需要服务端参与，
        // 也不应该因为"服务端没装这个模组"而失效。
        if (!world.isClientSide()) {
            return InteractionResult.PASS;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(entity instanceof Villager)) {
            return InteractionResult.PASS;
        }
        Villager v = (Villager) entity;

        IronFarmClientState state = IronFarmClientState.get();
        // 右键开关：与主开关分开控制。
        // 关掉后右键村民不弹任何窗口，交还原版（交易仍然可用）。
        if (!state.settings().canRightClickVillager()) {
            return InteractionResult.PASS;
        }

        // 有交易界面 → 放行，交给 MerchantScreenMixin 加按钮
        if (hasTradeUi(v)) {
            return InteractionResult.PASS;
        }

        // 无交易界面 → 直接打开状态页
        // 只在引擎扫到过这只村民时才打开，否则弹出来是空的
        if (state.reportOf(v.getId()) == null) {
            return InteractionResult.PASS;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new VillagerStatusScreen(null, v.getId()));
        // SUCCESS 会取消后续原版逻辑（无交易界面本来也没有后续），
        // 但仍然返回 SUCCESS 而不是 FAIL，语义正确。
        return InteractionResult.SUCCESS;
    }

    /**
     * 该村民是否有原版交易界面。
     *
     * <p>婴儿一定没有；无职业（{@code VillagerProfession.NONE}）的村民也没有。
     * 有职业但还没补货的村民**仍然有**交易界面（只是列表为空），
     * 所以不能用 {@code getOffers().isEmpty()} 判断 ——
     * 那会把"刚变成图书管理员、还没交易过"的村民误判成"没有界面"，
     * 右键他就直接弹出状态页，玩家再也打不开交易。</p>
     */
    public static boolean hasTradeUi(Villager v) {
        if (v == null || v.isBaby()) {
            return false;
        }
        return v.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE;
    }
}
