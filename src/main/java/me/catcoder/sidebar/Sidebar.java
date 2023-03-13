package me.catcoder.sidebar;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.apache.commons.lang.RandomStringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import lombok.NonNull;
import lombok.SneakyThrows;
import me.catcoder.sidebar.text.TextIterator;
import me.catcoder.sidebar.util.lang.ThrowingConsumer;
import me.catcoder.sidebar.util.lang.ThrowingFunction;

public class Sidebar {

    private static final String OBJECTIVE_PREFIX = "PS-";

    private final Set<UUID> viewers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<SidebarLine> lines = new ArrayList<>();
    private final ScoreboardObjective objective;

    private TextIterator titleText;
    private BukkitTask titleUpdater;

    final Set<Integer> taskIds = new HashSet<>();

    /**
     * Construct a new sidebar instance.
     *
     * @param title a title of sidebar
     */
    public Sidebar(@NonNull String title) {
        this.objective = new ScoreboardObjective(OBJECTIVE_PREFIX + RandomStringUtils.randomAlphabetic(3), title);
    }

    public Sidebar(@NonNull TextIterator titleIterator, @NonNull Plugin plugin) {
        this.objective = new ScoreboardObjective(OBJECTIVE_PREFIX + RandomStringUtils.randomAlphabetic(3), titleIterator.next());

        setTitleIter(titleIterator, plugin);
    }

    /**
     * Update the title of the sidebar.
     *
     * @param title title to be updated
     */
    public void setTitle(@NonNull String title) {
        cancelTitleUpdater();

        objective.setDisplayName(title);
        broadcast(objective::updateValue);
    }

    public void setTitle(@NonNull TextIterator iterator, @NonNull Plugin plugin) {
        setTitleIter(iterator, plugin);
    }

    private void cancelTitleUpdater() {
        if (titleUpdater != null) {
            titleUpdater.cancel();
            titleUpdater = null;
        }

        this.titleText = null;
    }

    private void setTitleIter(@NonNull TextIterator iterator, @NonNull Plugin plugin) {
        if (titleUpdater != null) {
            titleUpdater.cancel();
            titleUpdater = null;
        }

        this.titleText = iterator;
        this.titleUpdater = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            String next = titleText.next();

            if (!next.equals(objective.getDisplayName())) {
                objective.setDisplayName(next);
                broadcast(objective::updateValue);
            }
        }, 0, 1);
    }

    /**
     * Updates the index of the line shifting it by an offset.
     *
     * @param line   the line
     * @param offset the offset
     */
    public void shiftLine(SidebarLine line, int offset) {
        lines.remove(line);
        lines.add(offset, line);

        updateAllLines(); // recalculate indices
    }

    /**
     * Schedules the task to update all dynamic lines at fixed rate.
     *
     * @param delay  delay in ticks
     * @param period period in ticks
     * @param plugin target plugin
     * @return the scheduled task
     */
    public BukkitTask updateLinesPeriodically(long delay, long period, @NonNull Plugin plugin) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::updateAllLines, delay, period);

        this.taskIds.add(task.getTaskId());

        return task;
    }

    public SidebarLine addLine(@NonNull String text) {
        return addLine(TextComponent.fromLegacyText(text));
    }

    @Deprecated
    public SidebarLine addUpdatableLineLegacy(@NonNull ThrowingFunction<Player, String, Throwable> updater) {
        return addLine((player) -> TextComponent.fromLegacyText(updater.apply(player)), false);
    }

    public SidebarLine addUpdatableLine(@NonNull ThrowingFunction<Player, BaseComponent[], Throwable> updater) {
        return addLine(updater, false);
    }

    public SidebarLine addLine(@NonNull BaseComponent[] text) {
        return addLine(x -> text, true);
    }

    public SidebarLine addBlankLine() {
        return addLine("");
    }

    private SidebarLine addLine(@NonNull ThrowingFunction<Player, BaseComponent[], Throwable> updater, boolean staticText) {
        SidebarLine line = new SidebarLine(updater, objective.getName() + lines.size(), staticText, lines.size());
        lines.add(line);
        return line;
    }

    /**
     * Removes line from sidebar.
     *
     * @param line the line
     */
    public void removeLine(@NonNull SidebarLine line) {
        if (lines.remove(line) && line.getScore() != -1) {
            broadcast(p -> line.removeTeam(p, objective.getName()));
            updateAllLines();
        }
    }

    public Optional<SidebarLine> maxLine() {
        return lines.stream()
                .filter(line -> line.getScore() != -1)
                .max(Comparator.comparingInt(SidebarLine::getScore));
    }

    public Optional<SidebarLine> minLine() {
        return lines.stream()
                .filter(line -> line.getScore() != -1)
                .min(Comparator.comparingInt(SidebarLine::getScore));
    }

    /**
     * Update the single line.
     *
     * @param line target line.
     */
    public void updateLine(@NonNull SidebarLine line) {
        if (lines.contains(line)) {
            broadcast(p -> line.updateTeam(p, line.getScore(), objective.getName()));
        }
    }

    /**
     * Update all dynamic lines of the sidebar.
     */
    public void updateAllLines() {
        int index = lines.size();

        for (SidebarLine line : lines) {
            // if line is not created yet
            if (line.getScore() == -1) {
                line.setScore(index--);
                broadcast(p -> line.createTeam(p, objective.getName()));
                continue;
            }

            int prevIndex = line.getScore();
            line.setScore(index--);

            broadcast(p -> line.updateTeam(p, prevIndex, objective.getName()));
        }
    }

    /**
     * Remove all viewers currently receiving this sidebar.
     */
    public void removeViewers() {
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                removeViewer(player);
            }
        }
    }

    /**
     * Remove all viewers and cancel all tasks.
     * <p>
     * This method should be called when the sidebar is no longer needed.
     * Otherwise, the sidebar will be kept in memory and will be updated
     * for all players.
     * <p>
     */
    public void destroy() {
        removeViewers();
        cancelTitleUpdater();

        for (int taskId : taskIds) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        taskIds.clear();
    }

    /**
     * Sends this sidebar with all lines to the player.
     *
     * @param player target player
     */

    @SneakyThrows
    public void addViewer(@NonNull Player player) {
        if (!viewers.contains(player.getUniqueId())) {
            updateAllLines();

            objective.create(player);

            for (SidebarLine line : lines) {
                line.createTeam(player, objective.getName());
            }

            objective.display(player);

            viewers.add(player.getUniqueId());

        }
    }

    /**
     * Removes sidebar for the target player.
     *
     * @param player target player
     */
    public void removeViewer(@NonNull Player player) {
        if (viewers.remove(player.getUniqueId())) {
            updateAllLines();

            lines.forEach(line -> line.removeTeam(player, objective.getName()));
            objective.remove(player);
        }
    }

    public Set<UUID> getViewers() {
        return Collections.unmodifiableSet(viewers);
    }

    public List<SidebarLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public ScoreboardObjective getObjective() {
        return objective;
    }

    private void broadcast(@NonNull ThrowingConsumer<Player, Throwable> consumer) {
        viewers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);

        for (UUID id : viewers) {
            // double check
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }

            try {
                consumer.accept(player);
            } catch (Throwable e) {
                throw new RuntimeException("An error occurred while updating sidebar for player: " + player.getName(),
                        e);
            }
        }
    }
}
