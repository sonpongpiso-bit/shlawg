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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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

        if (!(mc.hitResult instanceof BlockHitResult target) || mc.hitResult.getType() != HitResult.Type.BLOCK
                || target.getDirection() != Direction.UP) {
            msg(player, "Look at the top face of a ground block.");
            return;
        }

        int anchorSlot = findHotbar(player, Items.RESPAWN_ANCHOR);
        int glowSlot = findHotbar(player, Items.GLOWSTONE);
        ItemStack detonator = player.getInventory().getItem(DETONATE_SLOT);
        if (anchorSlot < 0 || glowSlot < 0) { msg(player, "Need a Respawn Anchor and Glowstone in your hotbar."); return; }
        if (detonator.isEmpty() || detonator.is(Items.GLOWSTONE)) { msg(player, "Slot 9 must hold a non-glowstone item."); return; }
        if (glowStack(player, glowSlot) < 2) { msg(player, "Need at least 2 Glowstone."); return; }

        BlockPos anchorPos = target.getBlockPos().above();

        // "Behind" = the block between you and the anchor, which shields you from the blast.
        // Set to anchorPos.relative(player.getDirection()) to put it on the far side instead.
        BlockPos glowPos = anchorPos.relative(player.getDirection().getOpposite());
        BlockPos glowGround = glowPos.below();
        BlockState glowGroundState = level.getBlockState(glowGround);
        if (!glowGroundState.isFaceSturdy(level, glowGround, Direction.UP) || !level.getBlockState(glowPos).canBeReplaced()) {
            msg(player, "No valid ground spot behind the anchor.");
            return;
        }

        // 1. Place anchor (normal use-item-on packet, with client prediction)
        if (!use(mc, player, anchorSlot, target)) return;

        // 2. Charge it
        BlockHitResult onAnchor = new BlockHitResult(Vec3.atCenterOf(anchorPos), Direction.UP, anchorPos, false);
        if (!use(mc, player, glowSlot, onAnchor)) return;

        // 3. Glowstone on the ground behind it: click the TOP face of the ground block, no sneaking involved
        BlockHitResult onGround = new BlockHitResult(
                new Vec3(glowGround.getX() + 0.5, glowGround.getY() + 1.0, glowGround.getZ() + 0.5),
                Direction.UP, glowGround, false);
        if (!use(mc, player, glowSlot, onGround)) return;

        // 4. Detonate with the slot 9 item
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

    private static int glowStack(LocalPlayer p, int slot) {
        return p.getInventory().getItem(slot).getCount();
    }

    private static void msg(LocalPlayer p, String s) {
        p.displayClientMessage(Component.literal("[Shlawg] " + s), true);
    }
}
