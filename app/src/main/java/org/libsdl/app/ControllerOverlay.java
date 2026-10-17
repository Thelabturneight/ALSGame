package org.libsdl.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
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

import org.ikemen_engine.ikemen_go.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controles de toque do Ikemen GO (estilo "contorno": botoes transparentes por dentro, com desenhos no lugar das letras).
 *
 * Modos (informados pelo engine via SDLActivity.onUnhandledMessage):
 *  - MENU: so aparece o botao "Voltar"; o resto da tela e repassado ao jogo como toque
 *          (o engine converte em selecao/confirmacao nos menus e na selecao de personagem).
 *  - LUTA: analogico + botoes A B C X Y Z, Start e Menu.
 *  - EDITOR: aberto por "Configuracoes > Controle > Mapeamento". Os controles ficam SEMPRE visiveis.
 *          So da para arrastar cada controle para a posicao desejada (salva automaticamente).
 *          Tamanho e opacidade sao fixos (opacidade padrao 30%).
 */
public class ControllerOverlay extends RelativeLayout {
    public static final int MODE_MENU = 0;
    public static final int MODE_FIGHT = 1;

    // Teclas do gamepad virtual -> teclas do Ikemen (config padrao Joystick_P1)
    // 102 = LB (tecla "d" do Ikemen), 4004 = LT (tecla "w" do Ikemen): troca de personagem no modo Tag
    private static final int[] CODES = {96, 97, 4005, 99, 100, 103, 108, 109, 102, 4004};
    private static final String[] CODE_NAMES = {"A", "B", "C", "X", "Y", "Z", "START", "MENU", "D", "W"};

    private static final int BACK_CODE = 109; // tecla "menu" do Ikemen: cancelar/voltar nos menus
    private static final int TYPE_BUTTON = 0;
    private static final int TYPE_ANALOG = 1;

    private static final String PREFS_NAME = "ikemen_touch_layout_v3";
    private static final String KEY_DPAD = "dirtype_dpad_v2"; // padrao: D-pad
    private static final float DEFAULT_ALPHA = 0.30f; // opacidade padrao de todos os controles
    private static final long DODGE_RELEASE_MS = 50L;  // tempo "solto" entre os dois toques da esquiva

    // Desenhos (res/drawable-nodpi). Cada PNG de botao e 256x256 com o anel de 248 px de diametro;
    // a base do analogico e 512x512 com anel de 504 px; a alavanca (anel do meio + bolinha) e 256x256.
    private static final float BTN_CANVAS = 256f / 248f;
    private static final float BASE_CANVAS = 512f / 504f;
    private static final float STICK_RATIO = 0.597f;           // diametro da alavanca / diametro da base
    private static final float STICK_CANVAS = 256f / 248f;
    // Distancia da borda esquerda da tela ate a borda da base do analogico (padrao), em dp
    private static final float ANALOG_LEFT_DP = 84f;

    // No PNG da alavanca (256 px): anel interno com raio externo ~124 px (fixo) e bolinha com raio ~77 px (move).
    // A bolinha sai do anel interno e anda ate a borda dela encostar por FORA do anel: (124 + 77) / 256 do tamanho da alavanca.
    private static final float RING_OUTER_PX = 124f;
    private static final float DOT_RADIUS_PX = 77f;
    private static final float THUMB_TRAVEL_IN_STICK = (RING_OUTER_PX + DOT_RADIUS_PX) / 256f;

    // Quanto os botoes de ataque (A B C Y + X Z) ficam afastados do canto da tela, em dp.
    // Positivo = para dentro da tela (esquerda), longe do canto; negativo = para o canto.
    private static final float ATTACK_SHIFT_DP = 24f;

    // Altura (centro) do botao Menu, em dp; D e W (Tag) usam a mesma para ficarem alinhados com ele
    private static final float MENU_CY_DP = 34f;

    private static class Ctl {
        final String key;
        final String title;
        final int type;
        final float baseDp;   // diametro base em dp
        final int defCode;

        boolean visible = true;
        float scale = 1f;
        float alpha = DEFAULT_ALPHA;
        int code;
        float fx = -1f, fy = -1f; // centro, em fracao da tela; <0 = padrao
        float cx, cy;             // centro atual em px
        boolean pressed;
        boolean tagOnly;          // so aparece na luta quando o modo e Tag (sempre aparece no editor)

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
    private boolean dpadMode = true;    // true = D-pad (padrao), false = analogico
    private int wantX = 0, wantY = 0;   // direcao que o dedo esta pedindo (pode diferir do hat durante a esquiva)
    private boolean dodging = false;    // esquiva em andamento (segura o hat neutro por alguns ms)
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.graphics.Path dpadClip = new android.graphics.Path();
    private float thumbDx = 0f, thumbDy = 0f;

    private int mode = MODE_MENU;
    private boolean tagMode = false; // partida em modo Tag: mostra os botoes D e W
    private boolean backVisible = false; // starts hidden (boot/loading/title); the engine shows it inside submenus
    private boolean editMode = false;
    private Ctl selected = null;
    private Ctl dragging = null;
    private float dragOffX, dragOffY;
    private int dragPointer = -1;
    private boolean menuForwarding = false;
    private int backPointer = -1;
    private boolean panelGesture = false;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF dst = new RectF();

    // Desenhos dos botoes
    private final Map<Integer, Bitmap> codeBitmaps = new HashMap<>(); // tecla -> desenho
    private Bitmap analogBase, analogRing, analogDot, backBitmap, dpadBitmap, dpadFillBitmap;

    // Painel do editor
    private LinearLayout panel;
    private TextView panelTitle;

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
        ctls.add(new Ctl("x", "Botao X", TYPE_BUTTON, 48f, 99));
        ctls.add(new Ctl("y", "Botao Y", TYPE_BUTTON, 64f, 100));
        ctls.add(new Ctl("z", "Botao Z", TYPE_BUTTON, 48f, 103));
        ctls.add(new Ctl("start", "Botao Start", TYPE_BUTTON, 48f, 108));
        // Troca de personagem (Tag): pequenos, canto superior direito
        Ctl tagD = new Ctl("d", "Botao D (Tag)", TYPE_BUTTON, 32f, 102);
        Ctl tagW = new Ctl("w", "Botao W (Tag)", TYPE_BUTTON, 32f, 4004);
        tagD.tagOnly = true;
        tagW.tagOnly = true;
        ctls.add(tagD);
        ctls.add(tagW);
        menuCtl = new Ctl("menu", "Botao Menu", TYPE_BUTTON, 40f, 109);
        ctls.add(menuCtl);

        fillPaint.setStyle(Paint.Style.FILL);
        loadBitmaps(context);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(2f * density);
        dashPaint.setColor(Color.rgb(255, 214, 102));
        dashPaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 7f * density}, 0f));

        loadPrefs();
        buildPanel(context);
    }

    private Bitmap loadBmp(Context context, int resId) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(context.getResources(), resId, o);
    }

    private void loadBitmaps(Context context) {
        codeBitmaps.put(96, loadBmp(context, R.drawable.touch_a));
        codeBitmaps.put(97, loadBmp(context, R.drawable.touch_b));
        codeBitmaps.put(4005, loadBmp(context, R.drawable.touch_c));
        codeBitmaps.put(99, loadBmp(context, R.drawable.touch_x));
        codeBitmaps.put(100, loadBmp(context, R.drawable.touch_y));
        codeBitmaps.put(103, loadBmp(context, R.drawable.touch_z));
        codeBitmaps.put(102, loadBmp(context, R.drawable.touch_d));
        codeBitmaps.put(4004, loadBmp(context, R.drawable.touch_w));
        codeBitmaps.put(108, loadBmp(context, R.drawable.touch_start));
        codeBitmaps.put(109, loadBmp(context, R.drawable.touch_menu));
        analogBase = loadBmp(context, R.drawable.touch_analog_base);
        analogRing = loadBmp(context, R.drawable.touch_analog_ring);
        analogDot = loadBmp(context, R.drawable.touch_analog_dot);
        backBitmap = loadBmp(context, R.drawable.touch_back);
        dpadBitmap = loadBmp(context, R.drawable.touch_dpad);
        dpadFillBitmap = loadBmp(context, R.drawable.touch_dpad_fill);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    private float[] defaultCenter(Ctl c, int W, int H) {
        float d = density;
        float bs = 64f * d;
        float marginR = 30f * d, marginB = 26f * d;
        // "Roda" de 4 botoes no canto inferior direito, em losango:
        //        C
        //     B     Y
        //        A
        float r = 52f * d;
        float cx = W - marginR - r - bs / 2f - ATTACK_SHIFT_DP * d;
        float cy = H - marginB - r - bs / 2f;
        // X e Z ficam separados da roda, subindo pela lateral direita (X acima do Y, Z acima do X)
        float xX = cx + r + 6f * d;
        float xY = cy - r - 68f * d; // X/Z um pouco mais acima (distancia parecida com a do Start)
        float zX = xX + 12f * d;
        float zY = Math.max(xY - 64f * d, 6f * d + 24f * d);
        switch (c.key) {
            // Analogico na esquerda, afastado da borda para o dedo nao sair da tela ao puxar pra esquerda
            case "analog": return new float[]{ANALOG_LEFT_DP * d + 75f * d, H - marginB - 75f * d};
            case "c":      return new float[]{cx, cy - r};
            case "b":      return new float[]{cx - r, cy};
            case "y":      return new float[]{cx + r, cy};
            case "a":      return new float[]{cx, cy + r};
            case "x":      return new float[]{xX, xY};
            case "z":      return new float[]{zX, zY};
            // Troca de personagem (Tag): fileira no canto superior direito, W no canto e D ao lado
            // (mesma altura do centro do botao Menu: ficam alinhados em linha reta com ele)
            case "w":      return new float[]{W - 14f * d - 16f * d, MENU_CY_DP * d};
            case "d":      return new float[]{W - 14f * d - 16f * d - 46f * d, MENU_CY_DP * d};
            // Menu: cantinho superior esquerdo, fora do caminho
            case "menu":   return new float[]{16f * d + 20f * d, MENU_CY_DP * d};
            // Start: embaixo, ao lado esquerdo da roda
            case "start":  return new float[]{cx - r - bs / 2f - 50f * d, H - marginB - 24f * d};
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
        dpadMode = sp.getBoolean(KEY_DPAD, true);
        for (Ctl c : ctls) {
            String k = c.key + ".";
            // Tamanho, opacidade, visibilidade e tecla nao sao mais editaveis: sempre o padrao.
            c.visible = true;
            c.scale = 1f;
            c.alpha = DEFAULT_ALPHA;
            c.code = c.defCode;
            c.fx = sp.getFloat(k + "fx", -1f);
            c.fy = sp.getFloat(k + "fy", -1f);
        }
    }

    private void savePrefs() {
        SharedPreferences.Editor e = sp.edit();
        for (Ctl c : ctls) {
            String k = c.key + ".";
            e.putFloat(k + "fx", c.fx);
            e.putFloat(k + "fy", c.fy);
        }
        e.putBoolean(KEY_DPAD, dpadMode);
        e.apply();
    }

    private void resetCtl(Ctl c) {
        c.visible = true;
        c.scale = 1f;
        c.alpha = DEFAULT_ALPHA;
        c.code = c.defCode;
        c.fx = -1f;
        c.fy = -1f;
    }

    /** Tipo de direcional (vindo do engine): false = analogico, true = D-pad. Salvo automaticamente. */
    public void setDirType(boolean dpad) {
        if (dpadMode == dpad) return;
        uiHandler.post(new Runnable() {
            @Override public void run() {
                releaseAll();
            }
        });
        dpadMode = dpad;
        savePrefs();
        postInvalidate();
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
                if (c.visible && (!c.tagOnly || tagMode)) drawCtl(canvas, c, false);
            }
        } else if (backVisible) {
            drawBackButton(canvas);
        }
    }

    private void drawBmp(Canvas canvas, Bitmap bmp, float cx, float cy, float size, float alpha) {
        if (bmp == null) return;
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        bmpPaint.setAlpha((int) (255 * Math.max(0.1f, Math.min(1f, alpha))));
        canvas.drawBitmap(bmp, null, dst, bmpPaint);
    }

    private void drawCtl(Canvas canvas, Ctl c, boolean editing) {
        float r = c.radius(density);
        float a = c.visible ? c.alpha : 0.25f; // oculto: so aparece "fantasma" no editor
        if (c.type == TYPE_ANALOG && dpadMode) {
            drawDpad(canvas, c, r, a, editing);
        } else if (c.type == TYPE_ANALOG) {
            // fixos: base (anel externo + setas) e anel interno; so a bolinha segue o dedo
            float stickSize = 2f * r * STICK_RATIO * STICK_CANVAS;
            drawBmp(canvas, analogBase, c.cx, c.cy, 2f * r * BASE_CANVAS, a);
            drawBmp(canvas, analogRing, c.cx, c.cy, stickSize, a);
            float px = c.cx + (analogPointer != -1 && !editing ? thumbDx : 0f);
            float py = c.cy + (analogPointer != -1 && !editing ? thumbDy : 0f);
            drawBmp(canvas, analogDot, px, py, stickSize, a);
        } else {
            if (c.pressed && !editing) {
                fillPaint.setColor(Color.argb((int) (90 * a), 255, 255, 255));
                canvas.drawCircle(c.cx, c.cy, r, fillPaint);
            }
            drawBmp(canvas, codeBitmaps.get(c.code), c.cx, c.cy, 2f * r * BTN_CANVAS, a);
        }
    }

    // D-pad: mesma estetica do analogico (contorno + setas, cinza claro). Os desenhos tem a mesma
    // moldura 512x512 da base do analogico; a direcao pressionada "acende" (versao preenchida, recortada).
    private static final float DPAD_ARM_HALF = 54f;  // meia largura do braco (px do PNG de 512)
    private static final float DPAD_REACH = 218f;    // alcance do braco / peca diagonal

    // Ponta do D-pad apontando para (ox, oy): pentagono limitado pelas diagonais do "X" central
    private void addDpadArm(float cx, float cy, float ox, float oy, float w, float l) {
        float px = -oy, py = ox; // perpendicular
        float[][] pts = {{l, -w}, {l, w}, {w, w}, {0f, 0f}, {w, -w}}; // (ao longo, de lado)
        for (int i = 0; i < pts.length; i++) {
            float X = cx + ox * pts[i][0] + px * pts[i][1];
            float Y = cy + oy * pts[i][0] + py * pts[i][1];
            if (i == 0) dpadClip.moveTo(X, Y); else dpadClip.lineTo(X, Y);
        }
        dpadClip.close();
    }

    private void drawDpad(Canvas canvas, Ctl c, float r, float a, boolean editing) {
        float size = 2f * r * BASE_CANVAS;
        drawBmp(canvas, dpadBitmap, c.cx, c.cy, size, a);
        if (editing || analogPointer == -1 || (hatX == 0 && hatY == 0)) return;
        float k = size / 512f;
        float w = DPAD_ARM_HALF * k, l = DPAD_REACH * k;
        float x = c.cx, y = c.cy;
        dpadClip.reset();
        // cada ponta e separada das outras pelo "X": area da ponta = ate a linha diagonal do meio
        if (hatY < 0) addDpadArm(x, y, 0f, -1f, w, l);
        if (hatY > 0) addDpadArm(x, y, 0f, 1f, w, l);
        if (hatX < 0) addDpadArm(x, y, -1f, 0f, w, l);
        if (hatX > 0) addDpadArm(x, y, 1f, 0f, w, l);
        if (hatX != 0 && hatY != 0) { // diagonal: acende tambem a peca solta daquele canto
            float gap = 8f * k;
            float x0 = hatX > 0 ? x + w + gap : x - l, x1 = hatX > 0 ? x + l : x - w - gap;
            float y0 = hatY > 0 ? y + w + gap : y - l, y1 = hatY > 0 ? y + l : y - w - gap;
            dpadClip.addRect(x0, y0, x1, y1, android.graphics.Path.Direction.CW);
        }
        canvas.save();
        canvas.clipPath(dpadClip);
        drawBmp(canvas, dpadFillBitmap, x, y, size, a);
        canvas.restore();
    }

    // Botao "Voltar" mostrado nos menus: cancelar / voltar (circulo com a seta)
    private RectF backRect() {
        float d = density;
        return new RectF(14f * d, 12f * d, 14f * d + 46f * d, 12f * d + 46f * d);
    }

    private void drawBackButton(Canvas canvas) {
        RectF r = backRect();
        if (backPointer != -1) {
            fillPaint.setColor(Color.argb(70, 255, 255, 255));
            canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fillPaint);
        }
        drawBmp(canvas, backBitmap, r.centerX(), r.centerY(), r.width() * BTN_CANVAS, DEFAULT_ALPHA);
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

    // Modo Tag (vindo do engine): mostra/esconde os botoes de troca de personagem (D e W)
    public void setTagMode(boolean v) {
        if (tagMode == v) return;
        tagMode = v;
        if (!v) {
            // solta qualquer botao de Tag que esteja pressionado
            for (Integer id : new ArrayList<>(pointerCtl.keySet())) {
                Ctl c = pointerCtl.get(id);
                if (c != null && c.tagOnly) releasePointer(id);
            }
        }
        postInvalidate();
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
        panelTitle.setTextSize(15f);
        panelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        panelTitle.setGravity(Gravity.CENTER);
        panelTitle.setText("Arraste os controles para mapear a posicao");
        panel.addView(panelTitle);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button resetAll = new Button(context);
        resetAll.setText("Restaurar padrao");
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
        b.setMessage("Restaurar a posicao de todos os controles para o padrao?");
        b.setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                for (Ctl c : ctls) resetCtl(c);
                applyLayout();
                savePrefs();
            }
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void updatePanel() { }

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
                if ("y".equals(hit.key)) {
                    dodge(); // Y = esquiva (nao envia o botao Y do jogo)
                } else {
                    handleCode(hit.code, true);
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (id == analogPointer) {
                    updateAnalog(event.getX(i), event.getY(i));
                }
            }
            // Botao: se o dedo sai de cima dele, solta a tecla (so volta a apertar num novo toque)
            for (Integer id : new ArrayList<>(pointerCtl.keySet())) {
                int pi = event.findPointerIndex(id);
                Ctl c = pointerCtl.get(id);
                if (pi < 0 || c == null) continue;
                if (!isInsideCtl(c, event.getX(pi), event.getY(pi))) releasePointer(id);
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
        for (int i = ctls.size() - 1; i >= 0; i--) {
            Ctl c = ctls.get(i);
            if (!c.visible) continue;
            if (c.tagOnly && !tagMode) continue; // botoes de Tag so existem no modo Tag
            if (isInsideCtl(c, x, y)) return c;
        }
        return null;
    }

    // Area de toque de um controle (mesma regra para apertar e para continuar apertando)
    private boolean isInsideCtl(Ctl c, float x, float y) {
        float slop = 8f * density; // tolerancia
        float r = c.radius(density) + (c.type == TYPE_ANALOG ? c.radius(density) * 0.3f : slop);
        float dx = x - c.cx, dy = y - c.cy;
        return dx * dx + dy * dy <= r * r;
    }

    private void releasePointer(int pId) {
        if (pId == analogPointer) {
            analogPointer = -1;
            thumbDx = 0f;
            thumbDy = 0f;
            wantX = 0; wantY = 0;
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
        dodging = false;
        wantX = 0; wantY = 0;
        setHatForce(0, 0);
        for (Ctl c : ctls) c.pressed = false;
        invalidate();
    }

    // Analogico: o visual segue o dedo; para o jogo vira direcao digital de 8 sentidos (hat)
    private void updateAnalog(float x, float y) {
        // Area do analogico: so a metade da tela onde ele esta. Passou do meio da tela, para de responder.
        float mid = getWidth() / 2f;
        boolean outside = (analogCtl.cx < mid) ? (x > mid) : (x < mid);
        if (outside) {
            thumbDx = 0f;
            thumbDy = 0f;
            wantX = 0; wantY = 0;
            setHat(0, 0);
            return;
        }
        float r = analogCtl.radius(density);
        float dx = x - analogCtl.cx;
        float dy = y - analogCtl.cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float limit = 2f * r * STICK_RATIO * STICK_CANVAS * THUMB_TRAVEL_IN_STICK;
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
        if (dpadMode) { // no D-pad a "bolinha" nao segue o dedo
            thumbDx = 0f;
            thumbDy = 0f;
        }
        wantX = nx; wantY = ny;
        setHat(nx, ny);
    }

    private void setHat(int nx, int ny) {
        if (dodging) return; // durante a esquiva o hat e controlado por dodge()
        setHatForce(nx, ny);
    }

    private void setHatForce(int nx, int ny) {
        if (nx == hatX && ny == hatY) return;
        if (!isInitialized) return;
        hatX = nx;
        hatY = ny;
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
    }

    /**
     * Esquiva: detecta a direcao horizontal em que o jogador esta andando e simula o duplo toque rapido
     * nessa direcao (solta a direcao por alguns ms e aperta de novo), igual a apertar duas vezes seguidas.
     * Sem direcao pressionada, nao faz nada.
     */
    private void dodge() {
        if (!isInitialized || dodging || hatX == 0) return;
        final int dir = hatX;
        final int keepY = hatY;
        dodging = true;
        setHatForce(0, keepY);
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dodging = false;
                // reaperta na mesma direcao se o dedo ainda esta segurando (ou na que ele estiver pedindo agora)
                int nx = (analogPointer != -1) ? (wantX != 0 ? wantX : dir) : 0;
                int ny = (analogPointer != -1) ? wantY : 0;
                setHatForce(nx, ny);
            }
        }, DODGE_RELEASE_MS);
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
