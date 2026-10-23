package com.lerobot.workbench.service;

import java.util.Locale;

/**
 * Turns a task sentence into a stable dataset folder under {@code local/}.
 * "Pick the cube" becomes {@code local/PickTheCube}.
 */
public final class TaskRepoIds {

    private TaskRepoIds() {}

    public static String repoId(String task) {
        String folder = pascal(task);
        if (folder.isBlank()) {
            throw new IllegalArgumentException("请先填写任务描述，用来生成数据文件夹。");
        }
        return "local/" + folder;
    }

    public static String pascal(String task) {
        if (task == null || task.isBlank()) {
            return "";
        }
        StringBuilder token = new StringBuilder();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < task.length(); ) {
            int cp = task.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                token.appendCodePoint(cp);
            } else if (!token.isEmpty()) {
                appendWord(out, token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            appendWord(out, token.toString());
        }
        if (out.length() > 64) {
            out.setLength(64);
        }
        return out.toString();
    }

    private static void appendWord(StringBuilder out, String word) {
        int first = word.codePointAt(0);
        if (first < 128 && Character.isLetter(first)) {
            out.appendCodePoint(Character.toUpperCase(first));
            String rest = word.substring(word.offsetByCodePoints(0, 1));
            out.append(rest.toLowerCase(Locale.ROOT));
        } else {
            out.append(word);
        }
    }
}
