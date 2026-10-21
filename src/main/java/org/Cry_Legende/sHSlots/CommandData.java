package org.Cry_Legende.sHSlots;

import org.bukkit.scheduler.BukkitRunnable;

public class CommandData {
    private final int slot;
    private final String targetPlayerName;
    private final String duration;
    private BukkitRunnable countdownTask;

    public CommandData(int slot, String targetPlayerName, String duration) {
        this.slot = slot;
        this.targetPlayerName = targetPlayerName;
        this.duration = duration;
    }

    public int getSlot() {
        return slot;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
    }

    public void setCountdownTask(BukkitRunnable task) {
        this.countdownTask = task;
    }

    public void cancelCountdown() {
        if (this.countdownTask != null && !this.countdownTask.isCancelled()) {
            this.countdownTask.cancel();
        }
    }
}