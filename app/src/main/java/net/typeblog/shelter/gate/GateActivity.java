package net.typeblog.shelter.gate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.widget.TextViewCompat;

import net.typeblog.shelter.ui.MainActivity;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DecimalFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The outer door of the app.
 *
 * What the user sees is a plain, working calculator. Typing the PIN and pressing
 * "=" opens Shelter's own MainActivity. On the very first start there is no PIN
 * yet: the user types a new PIN (4-8 digits), presses "=", types it again and
 * presses "=" once more (there is no built-in default PIN).
 *
 * Holding the small gear in the top right corner for {@link #HOLD_MS} opens the
 * classic PIN screen, kept as a fallback.
 */
public class GateActivity extends Activity {
    private static final long HOLD_MS = 3000L;

    private static final String PREFS = "gate_prefs";
    private static final String K_SALT = "pin_salt";
    private static final String K_HASH = "pin_hash";
    private static final String K_FAILS = "pin_fails";
    private static final String K_LOCK_UNTIL = "pin_lock_until";
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60 * 1000L;
    private static final int PIN_MIN = 4;
    private static final int PIN_MAX = 12;
    private static final int PBKDF2_ITERATIONS = 20000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOpenPin = new Runnable() {
        @Override
        public void run() {
            showPinScreen();
        }
    };

    private boolean mPinMode = false;

    // First-time PIN choice done on the calculator itself
    private String mSetupFirst = null;
    private TextView mHint = null;

    // Calculator state
    private TextView mDisplay;
    private final StringBuilder mInput = new StringBuilder();
    private double mAcc = 0;
    private String mOp = null;
    private String mShown = "0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No screenshots and no preview in the recent apps list
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        showCalculator();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mOpenPin);
        // Never stay on the PIN screen in the background
        if (mPinMode && !isFinishing()) {
            showCalculator();
        }
    }

    @Override
    public void onBackPressed() {
        if (mPinMode) {
            showCalculator();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------
    // Calculator (decoy)
    // ------------------------------------------------------------------

    private void showCalculator() {
        mPinMode = false;
        mHandler.removeCallbacks(mOpenPin);
        resetCalculator();

        LinearLayout root = newRoot();
        root.setGravity(Gravity.NO_GRAVITY);

        // Gear: hold for HOLD_MS to open the PIN screen
        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextColor(Color.parseColor("#4A4A4A"));
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        gear.setGravity(Gravity.CENTER);
        gear.setPadding(dp(16), dp(8), dp(16), dp(8));
        gear.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                mHandler.postDelayed(mOpenPin, HOLD_MS);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                mHandler.removeCallbacks(mOpenPin);
            }
            return true;
        });
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gearLp.gravity = Gravity.END;
        root.addView(gear, gearLp);

        mHint = null;
        if (!hasPin()) {
            mSetupFirst = null;
            mHint = new TextView(this);
            mHint.setTextColor(Color.parseColor("#E8830C"));
            mHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            mHint.setGravity(Gravity.CENTER);
            mHint.setPadding(dp(16), 0, dp(16), dp(4));
            mHint.setText("Ilk kurulum: 4-8 haneli bir PIN yaz ve = tusuna bas");
            root.addView(mHint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        mDisplay = new TextView(this);
        mDisplay.setTextColor(Color.WHITE);
        mDisplay.setGravity(Gravity.BOTTOM | Gravity.END);
        mDisplay.setSingleLine(true);
        mDisplay.setPadding(dp(16), 0, dp(16), dp(8));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                mDisplay, 24, 64, 2, TypedValue.COMPLEX_UNIT_SP);
        mDisplay.setText(mShown);
        root.addView(mDisplay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        final int util = Color.parseColor("#5F6368");
        final int digit = Color.parseColor("#2B2B2B");
        final int oper = Color.parseColor("#E8830C");

        pad.addView(row(key("C", util, 1f), key("⌫", util, 1f),
                key("±", util, 1f), key("÷", oper, 1f)));
        pad.addView(row(key("7", digit, 1f), key("8", digit, 1f),
                key("9", digit, 1f), key("×", oper, 1f)));
        pad.addView(row(key("4", digit, 1f), key("5", digit, 1f),
                key("6", digit, 1f), key("−", oper, 1f)));
        pad.addView(row(key("1", digit, 1f), key("2", digit, 1f),
                key("3", digit, 1f), key("+", oper, 1f)));
        pad.addView(row(key("0", digit, 2f), key(".", digit, 1f),
                key("=", oper, 1f)));
        root.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));

        setContentView(root);
    }

    private LinearLayout row(Button... keys) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : keys) {
            r.addView(b);
        }
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return r;
    }

    private Button key(final String label, int bg, float weight) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onKey(label));
        return b;
    }

    private void resetCalculator() {
        mInput.setLength(0);
        mAcc = 0;
        mOp = null;
        mShown = "0";
    }

    private void onKey(String k) {
        if (k.length() == 1 && k.charAt(0) >= '0' && k.charAt(0) <= '9') {
            if (mInput.length() < 12) {
                if (mInput.toString().equals("0")) {
                    mInput.setLength(0);
                }
                mInput.append(k);
            }
            show(mInput.toString());
        } else if (k.equals(".")) {
            if (mInput.indexOf(".") < 0) {
                if (mInput.length() == 0) {
                    mInput.append("0");
                }
                mInput.append(".");
            }
            show(mInput.toString());
        } else if (k.equals("C")) {
            resetCalculator();
            show("0");
        } else if (k.equals("⌫")) {
            if (mInput.length() > 0) {
                mInput.setLength(mInput.length() - 1);
            }
            show(mInput.length() == 0 ? "0" : mInput.toString());
        } else if (k.equals("±")) {
            if (mInput.length() > 0) {
                if (mInput.charAt(0) == '-') {
                    mInput.deleteCharAt(0);
                } else {
                    mInput.insert(0, '-');
                }
                show(mInput.toString());
            } else {
                mAcc = -mAcc;
                show(format(mAcc));
            }
        } else if (k.equals("=")) {
            if (handlePinEquals()) {
                return;
            }
            commit();
            mOp = null;
        } else {
            // + - x /
            commit();
            mOp = k;
        }
    }

    /**
     * Called when "=" is pressed. Returns true if the press was consumed as a PIN entry.
     * Only a plain run of 4-8 digits (no operator pending) can ever be a PIN, so normal
     * calculations are never affected.
     */
    private boolean handlePinEquals() {
        final String typed = mInput.toString();
        if (mOp != null || !typed.matches("\\d{4,8}")) {
            return false;
        }

        if (!hasPin()) {
            // First start: choose a PIN, then confirm it
            if (mSetupFirst == null) {
                mSetupFirst = typed;
                resetCalculator();
                show("0");
                setHint("PIN'i tekrar yaz ve = tusuna bas");
            } else if (typed.equals(mSetupFirst)) {
                savePin(typed);
                openVault();
            } else {
                mSetupFirst = null;
                resetCalculator();
                show("0");
                setHint("PIN'ler ayni degil. Yeni PIN'i bastan yaz ve = tusuna bas");
            }
            return true;
        }

        // Normal start: a wrong guess just behaves like a calculator (no hint that a PIN exists)
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        if (now < p.getLong(K_LOCK_UNTIL, 0)) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(p.getString(K_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(p.getString(K_HASH, ""), Base64.NO_WRAP);
            if (MessageDigest.isEqual(expected, hash(typed, salt))) {
                p.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0).apply();
                resetCalculator();
                show("0");
                openVault();
                return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        int fails = p.getInt(K_FAILS, 0) + 1;
        SharedPreferences.Editor ed = p.edit();
        if (fails >= MAX_FAILS) {
            ed.putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, now + LOCK_MS);
        } else {
            ed.putInt(K_FAILS, fails);
        }
        ed.apply();
        return false;
    }

    private void setHint(String text) {
        if (mHint != null) {
            mHint.setText(text);
        }
    }

    private void savePin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs().edit()
                .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(K_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
                .putInt(K_FAILS, 0)
                .putLong(K_LOCK_UNTIL, 0)
                .apply();
    }

    private void commit() {
        if (mInput.length() == 0) {
            return;
        }
        double v;
        try {
            v = Double.parseDouble(mInput.toString());
        } catch (NumberFormatException e) {
            v = 0;
        }
        mInput.setLength(0);
        if (mOp == null) {
            mAcc = v;
        } else if (mOp.equals("+")) {
            mAcc = mAcc + v;
        } else if (mOp.equals("−")) {
            mAcc = mAcc - v;
        } else if (mOp.equals("×")) {
            mAcc = mAcc * v;
        } else if (mOp.equals("÷")) {
            mAcc = mAcc / v;
        }
        show(format(mAcc));
    }

    private String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            mAcc = 0;
            mOp = null;
            return "Hata";
        }
        return new DecimalFormat("#.########").format(new BigDecimal(v));
    }

    private void show(String s) {
        mShown = s;
        if (mDisplay != null) {
            mDisplay.setText(s);
        }
    }

    // ------------------------------------------------------------------
    // PIN screen
    // ------------------------------------------------------------------

    private void showPinScreen() {
        mPinMode = true;
        final boolean creating = !hasPin();

        LinearLayout root = newRoot();
        root.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(creating ? "PIN belirle" : "PIN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText pin1 = pinField(creating ? "Yeni PIN (4-12 hane)" : "PIN");
        root.addView(pin1, fieldParams());

        final EditText pin2;
        if (creating) {
            pin2 = pinField("PIN tekrar");
            root.addView(pin2, fieldParams());
        } else {
            pin2 = null;
        }

        Button ok = new Button(this);
        ok.setText("Tamam");
        ok.setAllCaps(false);
        ok.setOnClickListener(v -> {
            if (creating) {
                onCreatePin(pin1.getText().toString(), pin2.getText().toString());
            } else {
                onVerifyPin(pin1, pin1.getText().toString());
            }
        });
        LinearLayout.LayoutParams okLp = fieldParams();
        okLp.topMargin = dp(16);
        root.addView(ok, okLp);

        Button back = new Button(this);
        back.setText("Geri");
        back.setAllCaps(false);
        back.setOnClickListener(v -> showCalculator());
        root.addView(back, fieldParams());

        setContentView(root);
        pin1.requestFocus();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.parseColor("#777777"));
        e.setTextColor(Color.WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PIN_MAX)});
        return e;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private void onCreatePin(String a, String b) {
        if (a.length() < PIN_MIN) {
            toast("PIN en az " + PIN_MIN + " haneli olmali");
            return;
        }
        if (!a.equals(b)) {
            toast("PIN'ler ayni degil");
            return;
        }
        savePin(a);
        openVault();
    }

    private void onVerifyPin(EditText field, String entered) {
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        long lockUntil = p.getLong(K_LOCK_UNTIL, 0);
        if (now < lockUntil) {
            toast("Cok fazla deneme. " + ((lockUntil - now) / 1000 + 1) + " sn bekle");
            field.setText("");
            return;
        }

        byte[] salt = Base64.decode(p.getString(K_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(p.getString(K_HASH, ""), Base64.NO_WRAP);
        if (MessageDigest.isEqual(expected, hash(entered, salt))) {
            p.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0).apply();
            openVault();
            return;
        }

        int fails = p.getInt(K_FAILS, 0) + 1;
        SharedPreferences.Editor ed = p.edit();
        if (fails >= MAX_FAILS) {
            ed.putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, now + LOCK_MS);
        } else {
            ed.putInt(K_FAILS, fails);
        }
        ed.apply();
        field.setText("");
        toast("Yanlis PIN");
    }

    private void openVault() {
        GateSession.unlock();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private boolean hasPin() {
        SharedPreferences p = prefs();
        return p.contains(K_HASH) && p.contains(K_SALT);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private LinearLayout newRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        final int base = dp(8);
        root.setPadding(base, base, base, base);
        // Targeting SDK 35 draws edge to edge, so keep content clear of system bars
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    base + insets.getSystemWindowInsetLeft(),
                    base + insets.getSystemWindowInsetTop(),
                    base + insets.getSystemWindowInsetRight(),
                    base + insets.getSystemWindowInsetBottom());
            return insets;
        });
        return root;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
