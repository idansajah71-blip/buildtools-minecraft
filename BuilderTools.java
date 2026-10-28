package id.builder.tools;

import id.builder.tools.Engine.Session;
import id.builder.tools.Engine.Step;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class BuilderTools implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> BuilderCommands.register(dispatcher, access));
        ServerTickEvents.END_SERVER_TICK.register(Engine::tick);

        // Kapak kayu: klik kiri = pos1, klik kanan = pos2 (blok gak rusak)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().is(Items.WOODEN_AXE)) {
                if (player instanceof ServerPlayer sp) {
                    Engine.session(sp).p1 = pos.immutable();
                    Engine.msg(sp, "pos1 = " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                }
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (player.getMainHandItem().is(Items.WOODEN_AXE)) {
                if (player instanceof ServerPlayer sp) {
                    BlockPos pos = hit.getBlockPos();
                    Engine.session(sp).p2 = pos.immutable();
                    Engine.msg(sp, "pos2 = " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                }
                return InteractionResult.SUCCESS;
            }
            if (player.getMainHandItem().is(Items.STICK) && player instanceof ServerPlayer sp && world instanceof ServerLevel sl) {
                Session s = Engine.session(sp);
                if (s.brushType != null) {
                    brush(sp, sl, hit.getBlockPos(), s);
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Layer Lock
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            Session s = Engine.peek(player.getUUID());
            if (s != null && s.lockY != null && pos.getY() < s.lockY) {
                if (player instanceof ServerPlayer sp) sp.sendSystemMessage(Component.literal("Layer Lock: Y < " + s.lockY + " dikunci."), true);
                return false;
            }
            return true;
        });
    }

    private static boolean free(BlockState s) { return s.isAir() || s.canBeReplaced(); }

    private static void brush(ServerPlayer p, ServerLevel l, BlockPos c, Session s) {
        BlockState mat = l.getBlockState(c);
        if (mat.isAir()) return;
        if (mat.is(Blocks.GRASS_BLOCK) || mat.is(Blocks.MYCELIUM) || mat.is(Blocks.PODZOL) || mat.is(Blocks.DIRT_PATH))
            mat = Blocks.DIRT.defaultBlockState(); // grass block gak bisa di-craft balik di survival
        BlockState air = Blocks.AIR.defaultBlockState();
        int r = s.brushR;
        List<Step> steps = new ArrayList<>();
        if ("hill".equals(s.brushType)) {
            for (int dy = 1; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dy * dy + dz * dz > r * r) continue;
                BlockPos pos = c.offset(dx, dy, dz);
                BlockState cur = l.getBlockState(pos);
                if (free(cur)) steps.add(new Step(pos, mat));
            }
        } else { // flatten: hancurin yang nonjol di atas, isi lubang di bawah sampai ketemu padat
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = r; dy >= 1; dy--) {
                    BlockPos pos = c.offset(dx, dy, dz);
                    if (!l.getBlockState(pos).isAir()) steps.add(new Step(pos, air));
                }
                for (int dy = 0; dy >= -r; dy--) {
                    BlockPos pos = c.offset(dx, dy, dz);
                    BlockState cur = l.getBlockState(pos);
                    if (free(cur)) { if (cur != mat) steps.add(new Step(pos, mat)); } else break;
                }
            }
        }
        Engine.reportNeed(p, steps);
        Engine.submit(p, l, steps, "Brush " + s.brushType);
    }
}
