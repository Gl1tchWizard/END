package app.willswitch.end;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 8, 8);
    private static final int PANEL = Color.rgb(17, 17, 17);
    private static final int LINE = Color.rgb(42, 42, 42);
    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int MUTED = Color.rgb(130, 130, 130);
    private static final int ACID = Color.rgb(216, 255, 0);

    private TextView serviceStatus;
    private Button enableButton;

    private static final AppSpec[] APPS = new AppSpec[] {
            new AppSpec("Instagram", "com.instagram.android", "feed · Reels · Explore"),
            new AppSpec("TikTok", "com.zhiliaoapp.musically", "For You feed"),
            new AppSpec("Reddit", "com.reddit.frontpage", "home feeds"),
            new AppSpec("X", "com.twitter.android", "home feed"),
            new AppSpec("YouTube", "com.google.android.youtube", "Shorts + vertical scrolling"),
            new AppSpec("Facebook", "com.facebook.katana", "feed · Reels"),
            new AppSpec("Threads", "com.instagram.barcelona", "home feed"),
            new AppSpec("LinkedIn", "com.linkedin.android", "feed"),
            new AppSpec("Snapchat", "com.snapchat.android", "Spotlight · Discover (MVP)")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        render();
        if (!EndPrefs.disclosureAccepted(this)) showDisclosure();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(brand());
        root.addView(spacer(30));
        root.addView(statusCard());
        root.addView(spacer(28));

        root.addView(sectionTitle("LIMIT"));
        root.addView(body("How much feed exists before END."));
        root.addView(spacer(12));
        root.addView(choiceRow(new int[]{1,2,3,4,5}, EndPrefs.screens(this), value -> {
            EndPrefs.setScreens(this, value);
            render();
        }, "screens"));

        root.addView(spacer(28));
        root.addView(sectionTitle("RESET"));
        root.addView(body("When END appears, reopening the app does not reset it."));
        root.addView(spacer(12));
        root.addView(choiceRow(new int[]{5,10,20,30,60}, EndPrefs.cooldownMinutes(this), value -> {
            EndPrefs.setCooldownMinutes(this, value);
            render();
        }, "min"));

        root.addView(spacer(30));
        root.addView(sectionTitle("MAKE THESE FINITE"));
        root.addView(body("END counts feed progress in apps you select. On Android 14+, after the limit it blocks only forward feed swipes; taps, horizontal gestures and scrolling back remain usable."));
        root.addView(spacer(10));
        for (AppSpec app : APPS) root.addView(appRow(app));

        root.addView(spacer(24));
        TextView browser = body("Browser websites are not included in this Android MVP. Per-site browser protection belongs in the END extension.");
        browser.setPadding(dp(2), 0, dp(2), 0);
        root.addView(browser);

        root.addView(spacer(30));
        root.addView(sectionTitle("PRIVACY FIRST"));
        TextView privacy = body("No account. No analytics. No internet permission. No screen content. After END is reached on Android 14+, touch gestures in that selected app are handled locally only long enough to reject forward swipes and replay allowed gestures. Touch coordinates are not stored.");
        privacy.setTextColor(Color.rgb(160,160,160));
        root.addView(privacy);

        setContentView(scroll);
        updateServiceStatus();
    }

    private View brand() {
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.end_brand_mockup);
        brand.setAdjustViewBounds(true);
        brand.setScaleType(ImageView.ScaleType.FIT_START);
        brand.setContentDescription("END — life's not infinite — by WillSwitch");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brand.setLayoutParams(lp);
        return brand;
    }

    private View statusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(PANEL);

        TextView title = new TextView(this);
        title.setText("END SERVICE");
        title.setTextColor(MUTED);
        title.setTextSize(10);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(.14f);
        card.addView(title);

        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(20);
        serviceStatus.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(7);
        card.addView(serviceStatus, sLp);

        enableButton = new Button(this);
        enableButton.setAllCaps(false);
        enableButton.setText("Enable in Android settings");
        enableButton.setTextColor(BG);
        enableButton.setTextSize(13);
        enableButton.setTypeface(Typeface.DEFAULT_BOLD);
        enableButton.setBackgroundColor(ACID);
        enableButton.setOnClickListener(v -> {
            if (!EndPrefs.disclosureAccepted(this)) {
                showDisclosure();
                return;
            }
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        bLp.topMargin = dp(14);
        card.addView(enableButton, bLp);
        return card;
    }

    private View choiceRow(int[] values, int selected, IntChoice listener, String suffix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int value : values) {
            TextView item = new TextView(this);
            item.setGravity(Gravity.CENTER);
            item.setText(suffix.equals("screens") ? String.valueOf(value) : value + "m");
            item.setTextSize(13);
            item.setTypeface(Typeface.DEFAULT_BOLD);
            boolean on = value == selected;
            item.setTextColor(on ? BG : TEXT);
            item.setBackgroundColor(on ? ACID : PANEL);
            item.setOnClickListener(v -> listener.onPick(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (row.getChildCount() > 0) lp.leftMargin = dp(5);
            row.addView(item, lp);
        }
        return row;
    }

    private View appRow(AppSpec app) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(app.name);
        name.setTextColor(TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(name);
        TextView hint = new TextView(this);
        hint.setText(app.hint);
        hint.setTextColor(MUTED);
        hint.setTextSize(11);
        hint.setLineSpacing(0, 1.08f);
        copy.addView(hint);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(EndPrefs.isProtected(this, app.pkg));
        toggle.setButtonTintList(null);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> EndPrefs.setProtected(this, app.pkg, isChecked));
        row.addView(toggle);
        return row;
    }

    private TextView sectionTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(ACID);
        v.setTextSize(10);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(.16f);
        return v;
    }

    private TextView body(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(MUTED);
        v.setTextSize(12);
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    private Space spacer(int dp) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return s;
    }

    private void updateServiceStatus() {
        if (serviceStatus == null || enableButton == null) return;
        boolean enabled = isEndServiceEnabled();
        serviceStatus.setText(enabled ? "ACTIVE" : "OFF");
        serviceStatus.setTextColor(enabled ? ACID : Color.rgb(200,200,200));
        enableButton.setText(enabled ? "Accessibility settings" : "Enable in Android settings");
    }

    private boolean isEndServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String mine = getPackageName() + "/" + EndAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : services) {
            if (info.getId() != null && (info.getId().equals(mine) || info.getId().contains(getPackageName()))) return true;
        }
        return false;
    }

    private void showDisclosure() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView copy = new TextView(this);
        copy.setText("END uses Android Accessibility to detect feed activity and the name of the app currently on screen. It uses those events only to count scrolling in apps you choose.\n\nOn Android 14 and newer, once your limit is reached END temporarily handles touchscreen gestures while that selected app is foregrounded: an upward finger swipe that would request new feed content is discarded; taps, horizontal gestures and downward/back scrolling are replayed to the app. This is what creates a finite feed instead of locking the whole app.\n\nEND does not retrieve, read, save or transmit screen content, text, images, messages or passwords. Touch coordinates used after the limit are not stored. END has no Internet permission. Selected apps, scroll progress and lock timers stay on this device.\n\nYou can disable END at any time in Android Accessibility settings.");
        copy.setTextColor(Color.rgb(45,45,45));
        copy.setTextSize(14);
        copy.setLineSpacing(0,1.16f);
        box.addView(copy);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Before END can work")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Not now", (d, which) -> d.dismiss())
                .setPositiveButton("I understand", (d, which) -> {
                    EndPrefs.setDisclosureAccepted(this, true);
                    d.dismiss();
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(80,100,0)));
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface IntChoice { void onPick(int value); }

    private static class AppSpec {
        final String name, pkg, hint;
        AppSpec(String name, String pkg, String hint) {
            this.name = name; this.pkg = pkg; this.hint = hint;
        }
    }
}
