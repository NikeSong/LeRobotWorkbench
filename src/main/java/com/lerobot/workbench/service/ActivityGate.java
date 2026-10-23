package com.lerobot.workbench.service;

import org.springframework.stereotype.Service;

/**
 * One process may own the serial ports and cameras at a time.
 * Recording, teleop, calibration, joint preview and replay otherwise
 * interleave Feetech packets and corrupt the episode being written.
 */
@Service
public class ActivityGate {

    public enum Kind {
        RECORD,
        TELEOP,
        CALIBRATE,
        JOINTS,
        REPLAY
    }

    private Kind holder;
    private int ticket;

    /**
     * @return null when the caller may proceed; otherwise a message naming the occupant.
     *         Joint preview and teleop may be stopped by recording or replay before {@link #acquire}.
     */
    public synchronized String conflict(Kind requester) {
        if (holder == null) {
            return null;
        }
        if (holder == requester) {
            return label(holder) + "已在运行。";
        }
        if (requester == Kind.RECORD && (holder == Kind.JOINTS || holder == Kind.TELEOP || holder == Kind.REPLAY)) {
            return null;
        }
        if (requester == Kind.TELEOP && holder == Kind.JOINTS) {
            return null;
        }
        if (requester == Kind.REPLAY && (holder == Kind.JOINTS || holder == Kind.TELEOP)) {
            return null;
        }
        return "串口正被「" + label(holder) + "」占用。请先停止它，再开始" + label(requester) + "。";
    }

    /** @return a ticket that {@link #release(Kind, int)} must present. A newer acquire invalidates older tickets. */
    public synchronized int acquire(Kind kind) {
        String blocked = conflict(kind);
        if (blocked != null) {
            throw new IllegalStateException(blocked);
        }
        holder = kind;
        return ++ticket;
    }

    public synchronized void release(Kind kind, int heldTicket) {
        if (holder == kind && ticket == heldTicket) {
            holder = null;
        }
    }

    private static String label(Kind kind) {
        return switch (kind) {
            case RECORD -> "录制";
            case TELEOP -> "遥操作";
            case CALIBRATE -> "标定";
            case JOINTS -> "关节预览";
            case REPLAY -> "重放";
        };
    }
}
