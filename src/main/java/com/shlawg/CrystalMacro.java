package your.mod.modules;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Crystal Macro.
 *
 * Every action goes through vanilla MultiPlayerGameMode (useItemOn / attack),
 * so the server receives normal ServerboundUseItemOn / ServerboundInteract /
 * ServerboundSetCarriedItem packets. Targets are re-read from the live crosshair
 * hit result every tick; nothing about a target is cached.
 */
public final class CrystalMacro {

    private static final long OBSIDIAN_COOLDOWN_MS = 500;
    private static final long FAIL_BACKOFF_MS = 100;

    /** Toggle this from your module system / keybind. */
    private static boolean enabled = true;

    // Session state: only set while we are temporarily holding obsidian.
    private static int returnSlot = -1;    // crystal slot to switch back to
    private static int obsidianSlot = -1;  // slot we switched to

    private static long nextObsidianAt = 0;
    private static boolean inMacroCall = false;

    private CrystalMacro() {}

    // ---------------------------------------------------------------- setup

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(CrystalMacro::onTick);

        // Suppress vanilla right-click handling while the macro owns the interaction,
        // otherwise vanilla would place a second obsidian / duplicate crystal.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (inMacroCall) return InteractionResult.PASS;
            return shouldSuppressVanilla(player) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) resetSession();
    }

    // ----------------------------------------------------------------- tick

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (player == null || mc.level == null || gm == null) {
            resetSession();
            return;
        }

        Inventory inv = player.getInventory();
        boolean held = enabled
                && mc.screen == null
                && mc.options.keyUse.isDown()
                && !player.isSpectator();

        if (returnSlot != -1) {
            // Player changed slot themselves: abandon the session, don't fight them.
            if (inv.getSelectedSlot() != obsidianSlot) {
                resetSession();
                return;
            }
            // Switch back to the crystal (vanilla slot change; the server is told
            // via ServerboundSetCarriedItem on the next interaction).
            inv.setSelectedSlot(returnSlot);
            resetSession();
            if (!held) return;
        } else if (!held) {
            return;
        }

        if (!player.getMainHandItem().is(Items.END_CRYSTAL)) return;

        // Always the live crosshair result for this tick.
        HitResult hit = mc.hitResult;
        if (hit == null) return;

        if (hit instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            if (target instanceof EndCrystal crystal
                    && crystal.isAlive()
                    && !crystal.isRemoved()
                    && player.isWithinEntityInteractionRange(crystal, 0.0)) {
                gm.attack(player, crystal);
                player.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;

        Level level = mc.level;
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) {
            // Looking at a valid base: crystal only. Never obsidian onto obsidian.
            tryPlaceCrystal(mc, player, gm, level, blockHit);
        } else {
            tryPlaceObsidian(mc, player, gm, level, inv, blockHit, state);
        }
    }

    // -------------------------------------------------------------- crystal

    private static void tryPlaceCrystal(Minecraft mc, LocalPlayer player, MultiPlayerGameMode gm,
                                        Level level, BlockHitResult hit) {
        BlockPos above = hit.getBlockPos().above();
        if (!player.isWithinBlockInteractionRange(hit.getBlockPos(), 0.0)) return;
        if (!level.isEmptyBlock(above)) return;
        if (!level.getEntities((Entity) null, new AABB(above)).isEmpty()) return;

        interact(player, gm, hit);
    }

    // ------------------------------------------------------------- obsidian

    private static void tryPlaceObsidian(Minecraft mc, LocalPlayer player, MultiPlayerGameMode gm,
                                         Level level, Inventory inv, BlockHitResult hit,
                                         BlockState clickedState) {
        long now = System.currentTimeMillis();
        if (now < nextObsidianAt) return;

        BlockPos clicked = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos placePos = clicked.relative(face);

        if (clickedState.canBeReplaced()) return; // keep placement predictable
        if (!player.isWithinBlockInteractionRange(clicked, 0.0)) return;

        // Target must actually be free right now.
        BlockState existing = level.getBlockState(placePos);
        if (!existing.canBeReplaced()) return;
        if (existing.is(Blocks.OBSIDIAN) || existing.is(Blocks.BEDROCK)) return;
        if (!level.isUnobstructed(Blocks.OBSIDIAN.defaultBlockState(), placePos,
                CollisionContext.of(player))) return;

        int slot = findHotbarSlot(inv, Items.OBSIDIAN);
        if (slot < 0) return;

        returnSlot = inv.getSelectedSlot();
        obsidianSlot = slot;
        inv.setSelectedSlot(slot);

        boolean ok = interact(player, gm, hit);
        nextObsidianAt = now + (ok ? OBSIDIAN_COOLDOWN_MS : FAIL_BACKOFF_MS);
        // We stay on obsidian until next tick, where we switch back and
        // only continue to the crystal if the obsidian is really in the world.
    }

    // -------------------------------------------------------------- helpers

    /** Vanilla block interaction; returns true if the interaction was accepted. */
    private static boolean interact(LocalPlayer player, MultiPlayerGameMode gm, BlockHitResult hit) {
        InteractionResult result;
        inMacroCall = true;
        try {
            result = gm.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        } finally {
            inMacroCall = false;
        }
        if (result instanceof InteractionResult.Success success) {
            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            return true;
        }
        return false;
    }

    private static int findHotbarSlot(Inventory inv, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static boolean shouldSuppressVanilla(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || player != mc.player) return false;
        if (returnSlot != -1) return true;
        return mc.screen == null
                && mc.options.keyUse.isDown()
                && player.getMainHandItem().is(Items.END_CRYSTAL);
    }

    private static void resetSession() {
        returnSlot = -1;
        obsidianSlot = -1;
    }
}
