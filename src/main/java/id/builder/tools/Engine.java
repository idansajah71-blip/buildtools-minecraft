package id.builder.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Inti mod: sesi pemain, antrian kerja (dicicil per tick), bayar pakai item (survival), undo/redo. */
public final class Engine {
    public static final int PER_TICK = 150;       // blok yang diproses per tick
    public static final int MAX_VOLUME = 500_000; // batas ukuran area
    public static final int MAX_UNDO = 20;

    public record Edit(BlockPos pos, BlockState before, BlockState after) {}
    public record Step(BlockPos pos, BlockState target) {}

    public static final class Session {
        public BlockPos p1, p2;
        public Integer lockY;
        public String brushType; // "hill" / "flatten" / null
        public int brushR = 4;
        public final Deque<List<Edit>> undo = new ArrayDeque<>();
        public final Deque<List<Edit>> redo = new ArrayDeque<>();
    }

    private enum Result { DONE, SKIP, NO_MATERIAL }

    private static final class Job {
        ServerPlayer player; ServerLevel level; List<Step> steps; int index;
        boolean exact, clearRedo, stopped;
        Deque<List<Edit>> dest; String label;
        final List<Edit> edits = new ArrayList<>();
        int done, skipped, noMat;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final List<Job> JOBS = new ArrayList<>();

    public static Session session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), k -> new Session());
    }

    public static Session peek(UUID id) { return SESSIONS.get(id); }

    public static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text));
    }

    // ---------- submit ----------

    public static void submit(ServerPlayer p, ServerLevel l, List<Step> steps, String label) {
        enqueue(p, l, steps, label, false, session(p).undo, true);
    }

    public static void undo(ServerPlayer p, ServerLevel l) {
        Session s = session(p);
        List<Edit> last = s.undo.pollFirst();
        if (last == null) { msg(p, "Gak ada yang bisa di-undo."); return; }
        List<Step> steps = new ArrayList<>();
        for (int i = last.size() - 1; i >= 0; i--) steps.add(new Step(last.get(i).pos(), last.get(i).before()));
        enqueue(p, l, steps, "Undo", true, s.redo, false);
    }

    public static void redo(ServerPlayer p, ServerLevel l) {
        Session s = session(p);
        List<Edit> last = s.redo.pollFirst();
        if (last == null) { msg(p, "Gak ada yang bisa di-redo."); return; }
        List<Step> steps = new ArrayList<>();
        for (int i = last.size() - 1; i >= 0; i--) steps.add(new Step(last.get(i).pos(), last.get(i).after()));
        enqueue(p, l, steps, "Redo", true, s.undo, false);
    }

    private static void enqueue(ServerPlayer p, ServerLevel l, List<Step> steps, String label,
                                boolean exact, Deque<List<Edit>> dest, boolean clearRedo) {
        if (steps.isEmpty()) { msg(p, label + ": gak ada blok yang perlu diubah."); return; }
        Job j = new Job();
        j.player = p; j.level = l; j.steps = steps; j.exact = exact;
        j.dest = dest; j.clearRedo = clearRedo; j.label = label;
        JOBS.add(j);
        msg(p, label + ": mulai, " + steps.size() + " blok.");
    }

    // ---------- tick ----------

    public static void tick(MinecraftServer server) {
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            if (j.player.isRemoved()) { finish(j); it.remove(); continue; }
            int budget = PER_TICK;
            while (budget-- > 0 && j.index < j.steps.size() && !j.stopped) {
                Step s = j.steps.get(j.index++);
                switch (transition(j, s.pos(), s.target())) {
                    case DONE -> j.done++;
                    case SKIP -> j.skipped++;
                    case NO_MATERIAL -> { j.noMat++; j.stopped = true; }
                }
            }
            if (j.stopped || j.index >= j.steps.size()) { finish(j); it.remove(); }
        }
    }

    private static void finish(Job j) {
        Session s = session(j.player);
        if (!j.edits.isEmpty()) {
            j.dest.addFirst(j.edits);
            while (j.dest.size() > MAX_UNDO) j.dest.pollLast();
            if (j.clearRedo) s.redo.clear();
        }
        String m = j.label + " selesai: " + j.done + " blok";
        if (j.skipped > 0) m += ", " + j.skipped + " dilewati";
        if (j.stopped) m += ". BERHENTI: bahan habis (sisa " + (j.steps.size() - j.index + 1) + " blok). Tambah bahan lalu ulangi perintah yang sama, yang sudah jadi otomatis dilewati.";
        msg(j.player, m);
    }

    // ---------- inti: ubah satu blok + bayar ----------

    private static Result transition(Job j, BlockPos pos, BlockState target) {
        ServerLevel level = j.level;
        ServerPlayer p = j.player;
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return Result.SKIP;
        BlockState cur = level.getBlockState(pos);
        if (cur == target) return Result.SKIP;
        if (level.getBlockEntity(pos) != null) return Result.SKIP; // chest dll jangan disentuh
        if (!cur.isAir() && cur.getDestroySpeed(level, pos) < 0) return Result.SKIP; // bedrock dll
        Session s = session(p);
        if (!cur.isAir() && s.lockY != null && pos.getY() < s.lockY) return Result.SKIP; // Layer Lock

        boolean survival = !p.isCreative();
        if (survival) {
            if (!target.isAir()) {
                Item item = target.getBlock().asItem();
                if (item == Items.AIR || !takeOne(p, item)) return Result.NO_MATERIAL;
            }
            if (!cur.isAir() && !(cur.getBlock() instanceof LiquidBlock)) {
                if (j.exact) give(level, p, new ItemStack(cur.getBlock().asItem()));
                else for (ItemStack d : Block.getDrops(cur, level, pos, null, p, p.getMainHandItem())) give(level, p, d);
            }
        }
        level.setBlock(pos, target, 3);
        j.edits.add(new Edit(pos, cur, target));
        return Result.DONE;
    }

    private static void give(ServerLevel level, ServerPlayer p, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack) && !stack.isEmpty()) {
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, p.getX(), p.getY(), p.getZ(), stack));
        }
    }

    public static boolean takeOne(ServerPlayer p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.is(item)) { st.shrink(1); return true; }
        }
        return false;
    }

    public static int countItem(ServerPlayer p, Item item) {
        var inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.is(item)) n += st.getCount();
        }
        return n;
    }

    /** Hitung kebutuhan bahan dari daftar langkah, kasih tau pemain (survival saja). */
    public static void reportNeed(ServerPlayer p, List<Step> steps) {
        if (p.isCreative()) return;
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Step s : steps) {
            if (s.target().isAir()) continue;
            Item it = s.target().getBlock().asItem();
            if (it != Items.AIR) need.merge(it, 1, Integer::sum);
        }
        for (var e : need.entrySet()) {
            int have = countItem(p, e.getKey());
            String name = new ItemStack(e.getKey()).getHoverName().getString();
            msg(p, "Butuh " + e.getValue() + " " + name + ", lu punya " + have
                    + (have < e.getValue() ? " (kurang " + (e.getValue() - have) + ")" : ""));
        }
    }
}
