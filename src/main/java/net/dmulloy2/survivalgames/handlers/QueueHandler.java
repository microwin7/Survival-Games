package net.dmulloy2.survivalgames.handlers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.dmulloy2.survivalgames.SurvivalGames;
import net.dmulloy2.survivalgames.types.BlockData;
import net.dmulloy2.survivalgames.types.Game;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class QueueHandler {
    private ConcurrentHashMap<Integer, List<BlockData>> queue = new ConcurrentHashMap<Integer, List<BlockData>>();
    private File baseDir;

    private final SurvivalGames plugin;

    public QueueHandler(SurvivalGames plugin) {
        this.plugin = plugin;
        this.baseDir = new File(plugin.getDataFolder(), "ArenaData");

        try {
            if (!baseDir.exists())
                baseDir.mkdirs();

            for (Game g : plugin.getGameHandler().getGames()) {
                ensureFile(g.getID());
            }
        } catch (Throwable ex) {
        }

        new DataDumper().runTaskTimer(plugin, 100, 100);
    }

    public void rollback(final int id) {
        loadSave(id);

        if (plugin.isDisabling()) {
            rollback(id, true, 0, 1, 0);
        } else {
            new Rollback(id, false, 0, 1, 0).runTaskLater(plugin, 2L);
        }

        List<Entity> removelist = new ArrayList<Entity>();

        for (Entity e : plugin.getSettingsHandler().getGameWorld(id).getEntities()) {
            if (!(e instanceof Player) && !(e instanceof NPC)) {
                if (plugin.getGameHandler().getBlockGameId(e.getLocation()) == id) {
                    removelist.add(e);
                }
            }
        }

        if (plugin.isDisabling()) {
            removeEntites(removelist);
        } else {
            new EntityRemoveTask(removelist).runTaskLater(plugin, 2L);
        }
    }

    public void add(BlockData data) {
        List<BlockData> dat = queue.get(data.getGameId());
        if (dat == null) {
            dat = new ArrayList<BlockData>();
            ensureFile(data.getGameId());
        }

        dat.add(data);
        queue.put(data.getGameId(), dat);
    }

    public void ensureFile(int id) {
        try {
            File f2 = new File(baseDir, "Arena" + id + ".dat");
            if (!f2.exists())
                f2.createNewFile();
        } catch (Throwable ex) {
        }
    }

    public class DataDumper extends BukkitRunnable {
        @Override
        public void run() {
            for (int id : queue.keySet()) {
                try {
                    List<BlockData> data = queue.get(id);
                    ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(new File(baseDir, "Arena" + id + ".dat")));

                    out.writeObject(data);
                    out.flush();
                    out.close();
                } catch (Throwable ex) {
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void loadSave(int id) {
        ensureFile(id);

        try {
            ObjectInputStream in = new ObjectInputStream(new FileInputStream(new File(baseDir, "Arena" + id + ".dat")));

            List<BlockData> input = (ArrayList<BlockData>) in.readObject();

            List<BlockData> data = queue.get(id);
            if (data == null) {
                data = new ArrayList<BlockData>();
            }

            for (BlockData d : input) {
                if (!data.contains(d)) {
                    data.add(d);
                }
            }

            queue.put(id, data);
            in.close();
        } catch (Throwable ex) {
        }
    }

    public class Rollback extends BukkitRunnable {
        private int id, totalRollback, iteration;
        private boolean shutdown;
        private long time;

        public Rollback(int id, boolean shutdown, int trb, int it, long time) {
            this.id = id;
            this.totalRollback = trb;
            this.iteration = it;
            this.shutdown = shutdown;
            this.time = time;
        }

        @Override
        public void run() {
            rollback(id, shutdown, totalRollback, iteration, time);
        }
    }

    public final void rollback(int id, boolean shutdown, int totalRollback, int iteration, long time) {
        Game game = plugin.getGameHandler().getGame(id);
        List<BlockData> data = queue.get(id);
        if (data != null) {
            int a = data.size() - 1;
            int rb = 0;
            long t1 = new Date().getTime();
            int pt = plugin.getSettingsHandler().getConfig().getInt("rollback.per-tick", 100);
            while (a >= 0 && (rb < pt || shutdown)) {
                plugin.debug("Resetting " + a);
                BlockData result = data.get(a);
                if (result.getGameId() == game.getID()) {
                    data.remove(a);
                    Location l = new Location(plugin.getServer().getWorld(result.getWorld()), result.getX(), result.getY(), result.getZ());
                    Block b = l.getBlock();
                    if (result.getPrevmat() != null) {
                        b.setType(result.getPrevmat());
                        b.getState().setData(result.getPrevdata());
                        b.getState().update();
                        rb++;
                    }
                }
                a--;
            }

            time += new Date().getTime() - t1;

            if (a != -1) {
                if (!plugin.isDisabling()) {
                    new Rollback(id, shutdown, totalRollback + rb, iteration + 1, time).runTaskLater(plugin, 1);
                } else {
                    rollback(id, shutdown, totalRollback + rb, iteration + 1, time);
                }
            } else {
                plugin.log("Arena " + id + " reset. Rolled back " + totalRollback + " blocks in " + iteration + " iterations (" + pt + " blocks per iteration Total time spent rolling back was " + time + "ms");
                game.resetCallback();
            }
        } else {
            plugin.log("Arena " + id + " reset. Rolled back " + totalRollback + " blocks in " + iteration + " iterations. Total time spent rolling back was " + time + "ms");
            game.resetCallback();
        }
    }

    public class EntityRemoveTask extends BukkitRunnable {
        private final List<Entity> entities;

        public EntityRemoveTask(List<Entity> entities) {
            this.entities = entities;
        }

        @Override
        public void run() {
            removeEntites(entities);
        }
    }

    public final void removeEntites(List<Entity> entities) {
        for (Entity entity : entities) {
            entity.remove();
        }
    }
}