package org.libsdl.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controles de toque do Ikemen GO (estilo "contorno": botoes transparentes por dentro).
 *
 * Modos (informados pelo engine via SDLActivity.onUnhandledMessage):
 *  - MENU: so aparece o botao "Voltar"; o resto da tela e repassado ao jogo como toque
 *          (o engine converte em selecao/confirmacao nos menus e na selecao de personagem).
 *  - LUTA: analogico + botoes A B C X Y Z, Start e Menu.
 *  - EDITOR: aberto por "Opcoes > Controles de Toque". Os controles ficam SEMPRE visiveis
 *          (nada de auto-ocultar). Cada controle tem sua propria configuracao
 *          (posicao, tamanho, opacidade, tecla e visibilidade).
 */
public class ControllerOverlay extends RelativeLayout {
    public static final int MODE_MENU = 0;
    public static final int MODE_FIGHT = 1;

    // Teclas do gamepad virtual -> teclas do Ikemen (config padrao Joystick_P1)
    private static final int[] CODES = {96, 97, 4005, 99, 100, 103, 108, 109};
    private static final String[] CODE_NAMES = {"A", "B", "C", "X", "Y", "Z", "START", "MENU"};

    private static final int BACK_CODE = 109; // tecla "menu" do Ikemen: cancelar/voltar nos menus
    private static final int TYPE_BUTTON = 0;
    private static final int TYPE_ANALOG = 1;

    private static final String PREFS_NAME = "ikemen_touch_layout_v2";

    private static class Ctl {
        final String key;
        final String title;
        final int type;
        final float baseDp;   // diametro base em dp
        final int defCode;

        boolean visible = true;
        float scale = 1f;
        float alpha = 0.8f;
        int code;
        float fx = -1f, fy = -1f; // centro, em fracao da tela; <0 = padrao
        float cx, cy;             // centro atual em px
        boolean pressed;

        Ctl(String key, String title, int type, float baseDp, int defCode) {
            this.key = key;
            this.title = title;
            this.type = type;
            this.baseDp = baseDp;
            this.defCode = defCode;
            this.code = defCode;
        }

        float radius(float density) {
            return baseDp * density * scale / 2f;
        }
    }

    private final float density;
    private final SharedPreferences sp;
    private final List<Ctl> ctls = new ArrayList<>();
    private final Ctl analogCtl;
    private final Ctl menuCtl;     // usado tambem como "Voltar" nos menus
    private final Map<Integer, Ctl> pointerCtl = new HashMap<>(); // dedo -> botao
    private final Map<Integer, Integer> codeCounts = new HashMap<>();

    private int virtualDeviceId;
    private boolean isInitialized = false;
    private int hatX = 0, hatY = 0;
    private int analogPointer = -1;
    private float thumbDx = 0f, thumbDy = 0f;

    private int mode = MODE_MENU;
    private boolean backVisible = true; // hidden on the root title menu (set by the engine)
    private boolean editMode = false;
    private Ctl selected = null;
    private Ctl dragging = null;
    private float dragOffX, dragOffY;
    private int dragPointer = -1;
    private boolean menuForwarding = false;
    private int backPointer = -1;
    private boolean panelGesture = false;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    // Painel do editor
    private LinearLayout panel;
    private TextView panelTitle;
    private SeekBar sizeBar, alphaBar;
    private TextView sizeLabel, alphaLabel;
    private Spinner keySpinner;
    private CheckBox visibleBox;
    private LinearLayout keyRow;
    private boolean updatingPanel = false;

    // ------------------------------------------------------------------
    // Joystick fisico (inalterado)
    // ------------------------------------------------------------------
    public int getPhysicalJoystickCount() {
        int count = 0;
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            if (isRealGamepad(dev)) {
                count++;
            }
        }
        return count;
    }

    private boolean isRealGamepad(InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        boolean isJoystick = ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ||
                ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);

        if (!isJoystick) return false;
        if (device.isVirtual()) return false;

        int[] keysToCheck = {
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
        };

        boolean[] hasKeys = device.hasKeys(keysToCheck);
        for (boolean exists : hasKeys) {
            if (exists) return true;
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Construcao
    // ------------------------------------------------------------------
    public ControllerOverlay(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setEnabled(true);
        setWillNotDraw(false);
        setMotionEventSplittingEnabled(true);

        density = context.getResources().getDisplayMetrics().density;
        sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        analogCtl = new Ctl("analog", "Analogico", TYPE_ANALOG, 150f, 0);
        ctls.add(analogCtl);
        ctls.add(new Ctl("a", "Botao A", TYPE_BUTTON, 64f, 96));
        ctls.add(new Ctl("b", "Botao B", TYPE_BUTTON, 64f, 97));
        ctls.add(new Ctl("c", "Botao C", TYPE_BUTTON, 64f, 4005));
        ctls.add(new Ctl("x", "Botao X", TYPE_BUTTON, 64f, 99));
        ctls.add(new Ctl("y", "Botao Y", TYPE_BUTTON, 64f, 100));
        ctls.add(new Ctl("z", "Botao Z", TYPE_BUTTON, 64f, 103));
        ctls.add(new Ctl("start", "Botao Start", TYPE_BUTTON, 40f, 108));
        menuCtl = new Ctl("menu", "Botao Menu", TYPE_BUTTON, 40f, 109);
        ctls.add(menuCtl);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.5f * density);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(2f * density);
        dashPaint.setColor(Color.rgb(255, 214, 102));
        dashPaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 7f * density}, 0f));

        loadPrefs();
        buildPanel(context);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    private float[] defaultCenter(Ctl c, int W, int H) {
        float d = density;
        float bs = 64f * d;
        float step = bs + 12f * d;
        float c2x = W - 34f * d - bs / 2f;
        float c1x = c2x - step;
        float c0x = c1x - step;
        float r1y = H - 30f * d - bs / 2f;
        float r0y = r1y - step;
        switch (c.key) {
            case "analog": return new float[]{40f * d + 75f * d, H - 30f * d - 75f * d};
            case "a":      return new float[]{c0x, r1y};
            case "b":      return new float[]{c1x, r1y};
            case "c":      return new float[]{c2x, r1y};
            case "x":      return new float[]{c0x, r0y};
            case "y":      return new float[]{c1x, r0y};
            case "z":      return new float[]{c2x, r0y};
            case "menu":   return new float[]{W - 34f * d - 52f * d, 12f * d + 20f * d};
            case "start":  return new float[]{W - 34f * d, 12f * d + 20f * d};
            default:       return new float[]{W / 2f, H / 2f};
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyLayout();
    }

    private void applyLayout() {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        for (Ctl c : ctls) {
            if (c.fx >= 0f && c.fy >= 0f) {
                c.cx = c.fx * W;
                c.cy = c.fy * H;
            } else {
                float[] p = defaultCenter(c, W, H);
                c.cx = p[0];
                c.cy = p[1];
            }
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Preferencias (cada controle tem o seu proprio conjunto)
    // ------------------------------------------------------------------
    private void loadPrefs() {
        for (Ctl c : ctls) {
            String k = c.key + ".";
            c.visible = sp.getBoolean(k + "visible", true);
            c.scale = sp.getFloat(k + "scale", 1f);
            c.alpha = sp.getFloat(k + "alpha", 0.8f);
            c.code = sp.getInt(k + "code", c.defCode);
            c.fx = sp.getFloat(k + "fx", -1f);
            c.fy = sp.getFloat(k + "fy", -1f);
        }
    }

    private void savePrefs() {
        SharedPreferences.Editor e = sp.edit();
        for (Ctl c : ctls) {
            String k = c.key + ".";
            e.putBoolean(k + "visible", c.visible);
            e.putFloat(k + "scale", c.scale);
            e.putFloat(k + "alpha", c.alpha);
            e.putInt(k + "code", c.code);
            e.putFloat(k + "fx", c.fx);
            e.putFloat(k + "fy", c.fy);
        }
        e.apply();
    }

    private void resetCtl(Ctl c) {
        c.visible = true;
        c.scale = 1f;
        c.alpha = 0.8f;
        c.code = c.defCode;
        c.fx = -1f;
        c.fy = -1f;
    }

    // ------------------------------------------------------------------
    // Desenho
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editMode) {
            for (Ctl c : ctls) drawCtl(canvas, c, true);
            if (selected != null) {
                float r = selected.radius(density) + 8f * density;
                canvas.drawCircle(selected.cx, selected.cy, r, dashPaint);
            }
        } else if (mode == MODE_FIGHT) {
            for (Ctl c : ctls) {
                if (c.visible) drawCtl(canvas, c, false);
            }
        } else if (backVisible) {
            drawBackButton(canvas);
        }
    }

    private int gray(float alpha) {
        return Color.argb((int) (255 * Math.max(0.1f, Math.min(1f, alpha))), 215, 215, 215);
    }

    private void drawCtl(Canvas canvas, Ctl c, boolean editing) {
        float r = c.radius(density);
        float a = c.visible ? c.alpha : 0.25f; // oculto: so aparece "fantasma" no editor
        ringPaint.setColor(gray(a));
        if (c.type == TYPE_ANALOG) {
            // anel externo (transparente por dentro)
            canvas.drawCircle(c.cx, c.cy, r, ringPaint);
            // setinhas
            fillPaint.setColor(gray(a * 0.8f));
            float t = r * 0.86f, s = r * 0.09f;
            drawArrow(canvas, c.cx, c.cy - t, 0, -1, s);
            drawArrow(canvas, c.cx, c.cy + t, 0, 1, s);
            drawArrow(canvas, c.cx - t, c.cy, -1, 0, s);
            drawArrow(canvas, c.cx + t, c.cy, 1, 0, s);
            // "pino" tambem so com contorno
            float tr = r * 0.38f;
            float px = c.cx + (analogPointer != -1 && !editing ? thumbDx : 0f);
            float py = c.cy + (analogPointer != -1 && !editing ? thumbDy : 0f);
            ringPaint.setStrokeWidth(3f * density);
            canvas.drawCircle(px, py, tr, ringPaint);
            ringPaint.setStrokeWidth(2.5f * density);
            textPaint.setColor(gray(a));
            textPaint.setTextSize(tr * 0.9f);
            canvas.drawText("L", px, py + tr * 0.32f, textPaint);
        } else {
            if (c.pressed && !editing) {
                fillPaint.setColor(Color.argb((int) (90 * a), 255, 255, 255));
                canvas.drawCircle(c.cx, c.cy, r, fillPaint);
            }
            canvas.drawCircle(c.cx, c.cy, r, ringPaint);
            textPaint.setColor(gray(a));
            String label = labelFor(c.code);
            textPaint.setTextSize(r * (label.length() > 2 ? 0.42f : 0.8f));
            canvas.drawText(label, c.cx, c.cy + textPaint.getTextSize() * 0.35f, textPaint);
        }
    }

    private void drawArrow(Canvas canvas, float x, float y, int dx, int dy, float s) {
        arrowPath.reset();
        if (dx == 0) {
            arrowPath.moveTo(x, y + dy * s);
            arrowPath.lineTo(x - s, y - dy * s);
            arrowPath.lineTo(x + s, y - dy * s);
        } else {
            arrowPath.moveTo(x + dx * s, y);
            arrowPath.lineTo(x - dx * s, y - s);
            arrowPath.lineTo(x - dx * s, y + s);
        }
        arrowPath.close();
        canvas.drawPath(arrowPath, fillPaint);
    }

    private String labelFor(int code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == code) return CODE_NAMES[i];
        }
        return "?";
    }

    // Botao "Voltar" mostrado nos menus: cancelar / voltar
    private RectF backRect() {
        float d = density;
        return new RectF(14f * d, 12f * d, 14f * d + 96f * d, 12f * d + 40f * d);
    }

    private void drawBackButton(Canvas canvas) {
        RectF r = backRect();
        if (backPointer != -1) {
            fillPaint.setColor(Color.argb(70, 255, 255, 255));
            canvas.drawRoundRect(r, 20f * density, 20f * density, fillPaint);
        }
        ringPaint.setColor(gray(0.8f));
        canvas.drawRoundRect(r, 20f * density, 20f * density, ringPaint);
        textPaint.setColor(gray(0.9f));
        textPaint.setTextSize(15f * density);
        canvas.drawText("\u25C0  VOLTAR", r.centerX(), r.centerY() + 5.5f * density, textPaint);
    }

    // ------------------------------------------------------------------
    // Modo (vindo do engine) e editor
    // ------------------------------------------------------------------
    public void setMode(int newMode) {
        if (mode == newMode) return;
        releaseAll();
        mode = newMode;
        invalidate();
    }

    public void setBackVisible(boolean v) {
        if (backVisible == v) return;
        backVisible = v;
        if (!v && backPointer != -1) {
            handleCode(BACK_CODE, false);
            backPointer = -1;
        }
        postInvalidate();
    }

    public void openEditor() {
        if (!isInitialized) {
            initializeVirtualController();
        }
        releaseAll();
        editMode = true;
        selected = null;
        panel.setVisibility(View.VISIBLE);
        updatePanel();
        bringChildToFront(panel);
        invalidate();
    }

    private void closeEditor() {
        editMode = false;
        selected = null;
        dragging = null;
        dragPointer = -1;
        savePrefs();
        panel.setVisibility(View.GONE);
        invalidate();
    }

    private Ctl findCtlAt(float x, float y) {
        // da frente para tras: os ultimos da lista ficam por cima
        for (int i = ctls.size() - 1; i >= 0; i--) {
            Ctl c = ctls.get(i);
            float r = c.radius(density);
            float dx = x - c.cx, dy = y - c.cy;
            if (dx * dx + dy * dy <= r * r) return c;
        }
        return null;
    }

    private boolean handleEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);
        if (action == MotionEvent.ACTION_DOWN) {
            Ctl c = findCtlAt(x, y);
            if (c != null) {
                selected = c;
                dragging = c;
                dragPointer = e.getPointerId(idx);
                dragOffX = c.cx - x;
                dragOffY = c.cy - y;
                updatePanel();
            } else {
                selected = null;
                updatePanel();
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE && dragging != null) {
            int pi = e.findPointerIndex(dragPointer);
            if (pi >= 0) {
                float nx = e.getX(pi) + dragOffX;
                float ny = e.getY(pi) + dragOffY;
                float r = dragging.radius(density);
                nx = Math.max(r, Math.min(getWidth() - r, nx));
                ny = Math.max(r, Math.min(getHeight() - r, ny));
                dragging.cx = nx;
                dragging.cy = ny;
                dragging.fx = nx / getWidth();
                dragging.fy = ny / getHeight();
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging != null) savePrefs();
            dragging = null;
            dragPointer = -1;
        }
        return true;
    }

    private void buildPanel(Context context) {
        int pad = (int) (10 * density);
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Color.argb(215, 18, 18, 22));
        panel.setVisibility(View.GONE);

        panelTitle = new TextView(context);
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setTextSize(16f);
        panelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        panelTitle.setGravity(Gravity.CENTER);
        panel.addView(panelTitle);

        sizeLabel = new TextView(context);
        sizeLabel.setTextColor(Color.LTGRAY);
        panel.addView(sizeLabel);
        sizeBar = new SeekBar(context);
        sizeBar.setMax(150); // 50% .. 200%
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (updatingPanel || selected == null) return;
                selected.scale = (50 + p) / 100f;
                sizeLabel.setText("Tamanho: " + (50 + p) + "%");
                invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { savePrefs(); }
        });
        panel.addView(sizeBar);

        alphaLabel = new TextView(context);
        alphaLabel.setTextColor(Color.LTGRAY);
        panel.addView(alphaLabel);
        alphaBar = new SeekBar(context);
        alphaBar.setMax(80); // 20% .. 100%
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (updatingPanel || selected == null) return;
                selected.alpha = (20 + p) / 100f;
                alphaLabel.setText("Opacidade: " + (20 + p) + "%");
                invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { savePrefs(); }
        });
        panel.addView(alphaBar);

        keyRow = new LinearLayout(context);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView keyLabel = new TextView(context);
        keyLabel.setTextColor(Color.LTGRAY);
        keyLabel.setText("Tecla: ");
        keyRow.addView(keyLabel);
        keySpinner = new Spinner(context);
        ArrayAdapter<String> ad = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, CODE_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keySpinner.setAdapter(ad);
        keySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (updatingPanel || selected == null || selected.type != TYPE_BUTTON) return;
                selected.code = CODES[pos];
                savePrefs();
                invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        keyRow.addView(keySpinner);
        panel.addView(keyRow);

        visibleBox = new CheckBox(context);
        visibleBox.setText("Mostrar no jogo");
        visibleBox.setTextColor(Color.WHITE);
        visibleBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updatingPanel || selected == null) return;
                selected.visible = checked;
                savePrefs();
                invalidate();
            }
        });
        panel.addView(visibleBox);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button resetOne = new Button(context);
        resetOne.setText("Restaurar este");
        resetOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected == null) return;
                resetCtl(selected);
                applyLayout();
                updatePanel();
                savePrefs();
            }
        });
        Button resetAll = new Button(context);
        resetAll.setText("Restaurar tudo");
        resetAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmResetAll();
            }
        });
        Button done = new Button(context);
        done.setText("Concluir");
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEditor();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(resetOne, bp);
        buttons.addView(resetAll, bp);
        buttons.addView(done, bp);
        panel.addView(buttons);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams((int) (340 * density), LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.topMargin = (int) (6 * density);
        addView(panel, lp);
    }

    private void confirmResetAll() {
        AlertDialog.Builder b = new AlertDialog.Builder(getContext());
        b.setMessage("Restaurar todos os controles para o padrao?");
        b.setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                for (Ctl c : ctls) resetCtl(c);
                applyLayout();
                updatePanel();
                savePrefs();
            }
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void updatePanel() {
        updatingPanel = true;
        if (selected == null) {
            panelTitle.setText("Toque num controle para editar");
            sizeBar.setEnabled(false);
            alphaBar.setEnabled(false);
            visibleBox.setEnabled(false);
            keySpinner.setEnabled(false);
            sizeLabel.setText("Tamanho");
            alphaLabel.setText("Opacidade");
            keyRow.setVisibility(View.VISIBLE);
        } else {
            panelTitle.setText(selected.title);
            sizeBar.setEnabled(true);
            alphaBar.setEnabled(true);
            visibleBox.setEnabled(true);
            sizeBar.setProgress(Math.round(selected.scale * 100f) - 50);
            alphaBar.setProgress(Math.round(selected.alpha * 100f) - 20);
            sizeLabel.setText("Tamanho: " + Math.round(selected.scale * 100f) + "%");
            alphaLabel.setText("Opacidade: " + Math.round(selected.alpha * 100f) + "%");
            visibleBox.setChecked(selected.visible);
            boolean isBtn = selected.type == TYPE_BUTTON;
            keyRow.setVisibility(isBtn ? View.VISIBLE : View.GONE);
            keySpinner.setEnabled(isBtn);
            if (isBtn) {
                for (int i = 0; i < CODES.length; i++) {
                    if (CODES[i] == selected.code) keySpinner.setSelection(i);
                }
            }
        }
        updatingPanel = false;
    }

    // ------------------------------------------------------------------
    // Toque
    // ------------------------------------------------------------------
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isInitialized) {
            initializeVirtualController();
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            panelGesture = false;
            if (editMode && panel.getVisibility() == View.VISIBLE) {
                float x = ev.getX(), y = ev.getY();
                panelGesture = x >= panel.getLeft() && x <= panel.getRight()
                        && y >= panel.getTop() && y <= panel.getBottom();
            }
        }
        return !panelGesture; // no painel do editor os filhos recebem o toque
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode) {
            return handleEditTouch(event);
        }
        if (mode == MODE_MENU) {
            return handleMenuTouch(event);
        }
        return handleFightTouch(event);
    }

    // Menus: so o "Voltar" e nosso; o resto vai para o jogo (SDL) como toque
    private boolean handleMenuTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            menuForwarding = false;
            if (backVisible && backRect().contains(event.getX(idx), event.getY(idx))) {
                backPointer = event.getPointerId(idx);
                handleCode(BACK_CODE, true);
                invalidate();
                return true;
            }
            menuForwarding = true;
        }
        if (backPointer != -1) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handleCode(BACK_CODE, false);
                backPointer = -1;
                invalidate();
            }
            return true;
        }
        if (menuForwarding && SDLActivity.mSurface != null) {
            SDLActivity.mSurface.onTouch(SDLActivity.mSurface, event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                menuForwarding = false;
            }
        }
        return true;
    }

    private boolean handleFightTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pId = event.getPointerId(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index), y = event.getY(index);
            Ctl hit = hitTest(x, y);
            if (hit == analogCtl) {
                analogPointer = pId;
                updateAnalog(x, y);
            } else if (hit != null) {
                pointerCtl.put(pId, hit);
                hit.pressed = true;
                handleCode(hit.code, true);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (id == analogPointer) {
                    updateAnalog(event.getX(i), event.getY(i));
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            releasePointer(pId);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
        }
        invalidate();
        return true;
    }

    private Ctl hitTest(float x, float y) {
        float slop = 8f * density; // tolerancia
        for (int i = ctls.size() - 1; i >= 0; i--) {
            Ctl c = ctls.get(i);
            if (!c.visible) continue;
            float r = c.radius(density) + (c.type == TYPE_ANALOG ? c.radius(density) * 0.3f : slop);
            float dx = x - c.cx, dy = y - c.cy;
            if (dx * dx + dy * dy <= r * r) return c;
        }
        return null;
    }

    private void releasePointer(int pId) {
        if (pId == analogPointer) {
            analogPointer = -1;
            thumbDx = 0f;
            thumbDy = 0f;
            setHat(0, 0);
        }
        Ctl c = pointerCtl.remove(pId);
        if (c != null) {
            c.pressed = false;
            handleCode(c.code, false);
        }
    }

    private void releaseAll() {
        List<Integer> ids = new ArrayList<>(pointerCtl.keySet());
        for (int id : ids) releasePointer(id);
        if (analogPointer != -1) releasePointer(analogPointer);
        if (backPointer != -1) {
            handleCode(BACK_CODE, false);
            backPointer = -1;
        }
        codeCounts.clear();
        setHat(0, 0);
        for (Ctl c : ctls) c.pressed = false;
        invalidate();
    }

    // Analogico: o visual segue o dedo; para o jogo vira direcao digital de 8 sentidos (hat)
    private void updateAnalog(float x, float y) {
        float r = analogCtl.radius(density);
        float dx = x - analogCtl.cx;
        float dy = y - analogCtl.cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float limit = r * 0.55f;
        if (dist > limit && dist > 0f) {
            thumbDx = dx / dist * limit;
            thumbDy = dy / dist * limit;
        } else {
            thumbDx = dx;
            thumbDy = dy;
        }
        int nx = 0, ny = 0;
        if (dist > r * 0.22f) {
            float ux = dx / dist, uy = dy / dist;
            if (ux > 0.38f) nx = 1; else if (ux < -0.38f) nx = -1;
            if (uy > 0.38f) ny = 1; else if (uy < -0.38f) ny = -1;
        }
        setHat(nx, ny);
    }

    private void setHat(int nx, int ny) {
        if (nx == hatX && ny == hatY) return;
        if (!isInitialized) return;
        hatX = nx;
        hatY = ny;
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
    }

    /** Pressiona/solta uma tecla; dois controles com a mesma tecla nao se atrapalham. */
    private void handleCode(int code, boolean pressed) {
        Integer cnt = codeCounts.get(code);
        int n = (cnt == null) ? 0 : cnt;
        if (pressed) {
            codeCounts.put(code, n + 1);
            if (n == 0) sendCode(code, true);
        } else if (n > 0) {
            codeCounts.put(code, n - 1);
            if (n == 1) sendCode(code, false);
        }
    }

    private void sendCode(final int code, boolean pressed) {
        if (!isInitialized) return;
        if (code >= 4000) { // gatilhos
            int axis = code - 4000;
            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
        } else {
            if (pressed) {
                SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
                int result = SDLControllerManager.onNativePadDown(virtualDeviceId, code);
                if (result < 0) {
                    android.util.Log.e("SDL", "INPUT FAILURE: Device " + virtualDeviceId + " rejected button " + code);
                }
            } else {
                SDLControllerManager.onNativePadUp(virtualDeviceId, code);
            }
        }
    }

    // ------------------------------------------------------------------
    // Inicializacao do gamepad virtual
    // ------------------------------------------------------------------
    public void initializeVirtualController() {
        android.util.Log.i("ControllerOverlay", "initializing virtual joystick...");
        virtualDeviceId = getPhysicalJoystickCount();
        pointerCtl.clear();
        codeCounts.clear();
        hatX = 0;
        hatY = 0;
        SDLControllerManager.nativeRemoveJoystick(virtualDeviceId);
        ensureJoystickAlive();
        for (int i = 0; i < 6; i++) {
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.00390625f);
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.0f);
        }
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
        isInitialized = true;
        applyLayout();
    }

    private int ensureJoystickAlive() {
        return SDLControllerManager.nativeAddJoystick(virtualDeviceId, "Xbox 360 Controller", "Gamepad", 0x045E, 0x028E, false, 0xFFF, 6, 0x3F, 1, 0);
    }
}
