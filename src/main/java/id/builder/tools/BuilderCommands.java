package id.builder.tools;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import id.builder.tools.Engine.Session;
import id.builder.tools.Engine.Step;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class BuilderCommands {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext access) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("b");

        root.then(Commands.literal("pos1").executes(c -> setPos(c, 1)));
        root.then(Commands.literal("pos2").executes(c -> setPos(c, 2)));
        root.then(Commands.literal("size").executes(BuilderCommands::size));
        root.then(Commands.literal("count").executes(BuilderCommands::count));
        root.then(Commands.literal("undo").executes(c -> { var p = c.getSource().getPlayerOrException(); Engine.undo(p, c.getSource().getLevel()); return 1; }));
        root.then(Commands.literal("redo").executes(c -> { var p = c.getSource().getPlayerOrException(); Engine.redo(p, c.getSource().getLevel()); return 1; }));

        // clear [filter]
        root.then(Commands.literal("clear")
                .executes(c -> clear(c, null))
                .then(Commands.argument("filter", BlockStateArgument.block(access)).executes(c -> clear(c, BlockStateArgument.getBlock(c, "filter").getState()))));

        // fill <block> [solid|hollow|walls|floor|roof]
        var fill = Commands.argument("block", BlockStateArgument.block(access)).executes(c -> fill(c, "solid"));
        for (String m : new String[]{"solid", "hollow", "walls", "floor", "roof"})
            fill.then(Commands.literal(m).executes(c -> fill(c, m)));
        root.then(Commands.literal("fill").then(fill));

        // replace <from> <to>
        root.then(Commands.literal("replace").then(Commands.argument("from", BlockStateArgument.block(access))
                .then(Commands.argument("to", BlockStateArgument.block(access)).executes(BuilderCommands::replace))));

        // flatten <y> [block]
        root.then(Commands.literal("flatten").then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
                .executes(c -> flatten(c, null))
                .then(Commands.argument("block", BlockStateArgument.block(access)).executes(c -> flatten(c, BlockStateArgument.getBlock(c, "block").getState())))));

        // sphere <block> <r> [hollow] , cylinder <block> <r> <h>   (pusat = pos1)
        root.then(Commands.literal("sphere").then(Commands.argument("block", BlockStateArgument.block(access))
                .then(Commands.argument("r", IntegerArgumentType.integer(1, 60)).executes(c -> sphere(c, false))
                        .then(Commands.literal("hollow").executes(c -> sphere(c, true))))));
        root.then(Commands.literal("cylinder").then(Commands.argument("block", BlockStateArgument.block(access))
                .then(Commands.argument("r", IntegerArgumentType.integer(1, 60))
                        .then(Commands.argument("h", IntegerArgumentType.integer(1, 200)).executes(BuilderCommands::cylinder)))));

        // lock <y> | lock off
        root.then(Commands.literal("lock")
                .then(Commands.literal("off").executes(c -> { var p = c.getSource().getPlayerOrException(); Engine.session(p).lockY = null; Engine.msg(p, "Layer Lock mati."); return 1; }))
                .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048)).executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    int y = IntegerArgumentType.getInteger(c, "y");
                    Engine.session(p).lockY = y;
                    Engine.msg(p, "Layer Lock aktif: blok di bawah Y=" + y + " gak bisa dihancurin.");
                    return 1; })));

        // brush hill|flatten <r> | brush off   (pakai stick, klik kanan blok)
        root.then(Commands.literal("brush")
                .then(Commands.literal("off").executes(c -> { var p = c.getSource().getPlayerOrException(); Engine.session(p).brushType = null; Engine.msg(p, "Brush mati."); return 1; }))
                .then(Commands.literal("hill").then(Commands.argument("r", IntegerArgumentType.integer(1, 16)).executes(c -> brush(c, "hill"))))
                .then(Commands.literal("flatten").then(Commands.argument("r", IntegerArgumentType.integer(1, 16)).executes(c -> brush(c, "flatten")))));

        d.register(root);
    }

    // ---------- util ----------

    private static int[] box(ServerPlayer p) {
        Session s = Engine.session(p);
        if (s.p1 == null || s.p2 == null) { Engine.msg(p, "Pilih 2 titik dulu: kapak kayu (kiri = pos1, kanan = pos2) atau /b pos1 & /b pos2."); return null; }
        int[] b = {Math.min(s.p1.getX(), s.p2.getX()), Math.min(s.p1.getY(), s.p2.getY()), Math.min(s.p1.getZ(), s.p2.getZ()),
                   Math.max(s.p1.getX(), s.p2.getX()), Math.max(s.p1.getY(), s.p2.getY()), Math.max(s.p1.getZ(), s.p2.getZ())};
        long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        if (vol > Engine.MAX_VOLUME) { Engine.msg(p, "Area kegedean (" + vol + "), maksimal " + Engine.MAX_VOLUME + " blok."); return null; }
        return b;
    }

    private static boolean free(BlockState s) { return s.isAir() || s.canBeReplaced(); }

    private static int setPos(CommandContext<CommandSourceStack> c, int n) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        BlockPos pos = p.blockPosition();
        if (n == 1) Engine.session(p).p1 = pos; else Engine.session(p).p2 = pos;
        Engine.msg(p, "pos" + n + " = " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        return 1;
    }

    private static int size(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int[] b = box(p); if (b == null) return 0;
        int x = b[3] - b[0] + 1, y = b[4] - b[1] + 1, z = b[5] - b[2] + 1;
        Engine.msg(p, "Ukuran: " + x + " x " + y + " x " + z + " (panjang x tinggi x lebar) = " + (long) x * y * z + " blok");
        return 1;
    }

    private static String stacks(int n) { return n >= 64 ? (n / 64) + " stack + " + (n % 64) : String.valueOf(n); }

    private static int count(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel l = c.getSource().getLevel();
        int[] b = box(p); if (b == null) return 0;
        Map<Item, Integer> m = new HashMap<>();
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            BlockState st = l.getBlockState(new BlockPos(x, y, z));
            if (st.isAir()) continue;
            Item it = st.getBlock().asItem();
            if (it != Items.AIR) m.merge(it, 1, Integer::sum);
        }
        if (m.isEmpty()) { Engine.msg(p, "Area kosong."); return 1; }
        m.entrySet().stream().sorted((a, bb) -> bb.getValue() - a.getValue()).limit(25).forEach(e -> {
            int have = Engine.countItem(p, e.getKey());
            String name = new ItemStack(e.getKey()).getHoverName().getString();
            Engine.msg(p, name + ": " + e.getValue() + " (" + stacks(e.getValue()) + ")" + " | di tas: " + have
                    + (have < e.getValue() ? " | kurang " + (e.getValue() - have) : ""));
        });
        return 1;
    }

    // ---------- operasi ----------

    private static int clear(CommandContext<CommandSourceStack> c, BlockState filter) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        int[] b = box(p); if (b == null) return 0;
        List<Step> steps = new ArrayList<>();
        for (int y = b[4]; y >= b[1]; y--) for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState cur = l.getBlockState(pos);
            if (cur.isAir()) continue;
            if (filter != null && cur.getBlock() != filter.getBlock()) continue;
            steps.add(new Step(pos, AIR));
        }
        Engine.submit(p, l, steps, "Clear");
        return 1;
    }

    private static boolean inMode(String m, int x, int y, int z, int[] b) {
        return switch (m) {
            case "hollow" -> x == b[0] || x == b[3] || y == b[1] || y == b[4] || z == b[2] || z == b[5];
            case "walls" -> x == b[0] || x == b[3] || z == b[2] || z == b[5];
            case "floor" -> y == b[1];
            case "roof" -> y == b[4];
            default -> true;
        };
    }

    private static int fill(CommandContext<CommandSourceStack> c, String mode) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        BlockState target = BlockStateArgument.getBlock(c, "block").getState();
        int[] b = box(p); if (b == null) return 0;
        List<Step> steps = new ArrayList<>();
        for (int y = b[1]; y <= b[4]; y++) for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
            if (!inMode(mode, x, y, z, b)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState cur = l.getBlockState(pos);
            if (cur == target || !free(cur)) continue; // fill cuma isi yang kosong; mau timpa? pakai /b clear atau /b replace
            steps.add(new Step(pos, target));
        }
        Engine.reportNeed(p, steps);
        Engine.submit(p, l, steps, "Fill " + mode);
        return 1;
    }

    private static int replace(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        BlockState from = BlockStateArgument.getBlock(c, "from").getState();
        BlockState to = BlockStateArgument.getBlock(c, "to").getState();
        int[] b = box(p); if (b == null) return 0;
        List<Step> steps = new ArrayList<>();
        for (int y = b[1]; y <= b[4]; y++) for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (l.getBlockState(pos).getBlock() == from.getBlock()) steps.add(new Step(pos, to));
        }
        Engine.reportNeed(p, steps);
        Engine.submit(p, l, steps, "Replace");
        return 1;
    }

    private static int flatten(CommandContext<CommandSourceStack> c, BlockState fillWith) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        int ty = IntegerArgumentType.getInteger(c, "y");
        int[] b = box(p); if (b == null) return 0;
        List<Step> clears = new ArrayList<>(), fills = new ArrayList<>();
        for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
            for (int y = b[4]; y > ty; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!l.getBlockState(pos).isAir()) clears.add(new Step(pos, AIR));
            }
            if (fillWith != null) for (int y = Math.min(ty, b[4]); y >= b[1]; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState cur = l.getBlockState(pos);
                if (free(cur)) { if (cur != fillWith) fills.add(new Step(pos, fillWith)); } else break;
            }
        }
        clears.sort((a, bb) -> bb.pos().getY() - a.pos().getY());
        List<Step> all = new ArrayList<>(clears); all.addAll(fills);
        Engine.reportNeed(p, fills);
        Engine.submit(p, l, all, "Flatten Y=" + ty);
        return 1;
    }

    private static int sphere(CommandContext<CommandSourceStack> c, boolean hollow) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        BlockState t = BlockStateArgument.getBlock(c, "block").getState();
        int r = IntegerArgumentType.getInteger(c, "r");
        BlockPos ctr = Engine.session(p).p1;
        if (ctr == null) { Engine.msg(p, "Set pos1 dulu, itu jadi pusat bola."); return 0; }
        List<Step> steps = new ArrayList<>();
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r * r) continue;
            if (hollow && d2 <= (r - 1) * (r - 1)) continue;
            BlockPos pos = ctr.offset(dx, dy, dz);
            BlockState cur = l.getBlockState(pos);
            if (cur != t && free(cur)) steps.add(new Step(pos, t));
        }
        Engine.reportNeed(p, steps);
        Engine.submit(p, l, steps, "Sphere");
        return 1;
    }

    private static int cylinder(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException(); ServerLevel l = c.getSource().getLevel();
        BlockState t = BlockStateArgument.getBlock(c, "block").getState();
        int r = IntegerArgumentType.getInteger(c, "r"), h = IntegerArgumentType.getInteger(c, "h");
        BlockPos ctr = Engine.session(p).p1;
        if (ctr == null) { Engine.msg(p, "Set pos1 dulu, itu jadi pusat alas silinder."); return 0; }
        List<Step> steps = new ArrayList<>();
        for (int dy = 0; dy < h; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) continue;
            BlockPos pos = ctr.offset(dx, dy, dz);
            BlockState cur = l.getBlockState(pos);
            if (cur != t && free(cur)) steps.add(new Step(pos, t));
        }
        Engine.reportNeed(p, steps);
        Engine.submit(p, l, steps, "Cylinder");
        return 1;
    }

    private static int brush(CommandContext<CommandSourceStack> c, String type) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Session s = Engine.session(p);
        s.brushType = type; s.brushR = IntegerArgumentType.getInteger(c, "r");
        Engine.msg(p, "Brush " + type + " radius " + s.brushR + " aktif. Pegang STICK, klik kanan blok tanah.");
        return 1;
    }
}
