package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Diagnostic helper: on launch, shows the most relevant lines of this app's own log
 * (what the previous run wrote, including Go panics and engine errors), with a Copy button.
 * Set ENABLED to false once the problem is fixed.
 */
final class LogViewer {
    static final boolean ENABLED = true;
    private static final int MAX_LINES = 150;

    private LogViewer() {}

    static void showLastLog(final Activity act) {
        if (!ENABLED) return;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String body = readLog();
                        if (body.length() == 0) return;
                        final String text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                                + " | ABI " + Build.SUPPORTED_ABIS[0] + "\n\n" + body;
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (act.isFinishing()) return;
                                show(act, text);
                            }
                        });
                    } catch (Throwable ignored) {
                        // Never let the diagnostic tool break the game.
                    }
                }
            }, "LogViewer");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    private static void show(final Activity act, final String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        int pad = (int) (12 * act.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);

        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Log da última execução")
                .setView(sv)
                .setPositiveButton("Copiar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("ikemen-log", text));
                            Toast.makeText(act, "Log copiado", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private static boolean interesting(String line) {
        String x = line.toLowerCase(Locale.ROOT);
        if (x.contains("ikemen") || x.contains("golang") || x.contains("assetextractor")
                || x.contains("sdl") || x.contains("panic") || x.contains("fatal")
                || x.contains("signal") || x.contains("androidruntime") || x.contains("exception")
                || x.contains("error") || x.contains("goroutine")) {
            return true;
        }
        return line.matches("^\\d\\d-\\d\\d \\S+ [EF]/.*");
    }

    private static String readLog() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "time"});
        ArrayDeque<String> last = new ArrayDeque<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (!interesting(line)) continue;
                if (line.length() > 400) line = line.substring(0, 400);
                if (last.size() >= MAX_LINES) last.removeFirst();
                last.addLast(line);
            }
        } finally {
            r.close();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : last) sb.append(l).append('\n');
        return sb.toString();
    }
}
