package com.shlawg;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class ShlawgClient implements ClientModInitializer {
    private static final int DETONATE_SLOT = 8; // hotbar slot 9 (0-indexed)

    private static KeyMapping triggerKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("shlawg", "main"));
        triggerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.shlawg.safe_anchor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (triggerKey.consumeClick()) {
                if (mc.player != null && mc.level != null && mc.gameMode != null) {
                    run(mc);
                }
            }
        });
    }

    private static void run(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (!(mc.hitResult instanceof BlockHitResult target) || mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos targetPos = target.getBlockPos();
        boolean replaceable = level.getBlockState(targetPos).canBeReplaced();
        if (!replaceable && target.getDirection() != Direction.UP) return;
        BlockPos anchorPos = replaceable ? targetPos : targetPos.above();

        int anchorSlot = findHotbar(player, Items.RESPAWN_ANCHOR);
        int glowSlot = findHotbar(player, Items.GLOWSTONE);
        ItemStack detonator = player.getInventory().getItem(DETONATE_SLOT);
        if (anchorSlot < 0 || glowSlot < 0) return;
        if (detonator.isEmpty() || detonator.is(Items.GLOWSTONE)) return;

        // 1. Place anchor
        if (!use(mc, player, anchorSlot, target)) return;

        // 2. Charge it
        BlockHitResult onAnchor = new BlockHitResult(Vec3.atCenterOf(anchorPos), Direction.UP, anchorPos, false);
        if (!use(mc, player, glowSlot, onAnchor)) return;

        // 3. Optional: Glowstone behind the anchor. Fully isolated: if the space is blocked, missing,
        //    or the placement fails for any reason, it is skipped and step 4 still runs.
        try {
            BlockPos glowPos = anchorPos.relative(player.getDirection().getOpposite());
            BlockPos glowGround = glowPos.below();
            boolean valid = level.getBlockState(glowGround).isFaceSturdy(level, glowGround, Direction.UP)
                    && level.getBlockState(glowPos).canBeReplaced()
                    && player.getInventory().getItem(glowSlot).getCount() > 0;
            if (valid) {
                BlockHitResult onGround = new BlockHitResult(
                        new Vec3(glowGround.getX() + 0.5, glowGround.getY() + 1.0, glowGround.getZ() + 0.5),
                        Direction.UP, glowGround, false);
                use(mc, player, glowSlot, onGround);
            }
        } catch (Exception ignored) {
        }

        // 4. Detonate with the slot 9 item (always runs once the anchor is placed and charged)
        use(mc, player, DETONATE_SLOT, onAnchor);
    }

    /** Selects the slot and right-clicks the block through the vanilla gameMode, which sends the real packets. */
    private static boolean use(Minecraft mc, LocalPlayer player, int slot, BlockHitResult hit) {
        player.getInventory().setSelectedSlot(slot);
        InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
        return r.consumesAction();
    }

    private static int findHotbar(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
}
