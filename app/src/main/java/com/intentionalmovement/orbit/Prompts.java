package com.intentionalmovement.orbit;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.List;

/** The page's own dialogs (alert, confirm, prompt, drop-down lists, pop-ups) as plain phone dialogs. */
final class Prompts implements PromptDelegate {
    private final Activity a;
    private final Runnable onPopupBlocked;

    Prompts(Activity a, Runnable onPopupBlocked) { this.a = a; this.onPopupBlocked = onPopupBlocked; }

    /** Completes the result once: with the user's answer, or "dismissed" if the dialog just goes away. */
    private static final class Once {
        final GeckoResult<PromptResponse> r = new GeckoResult<>();
        boolean done;
        void finish(PromptResponse v) { if (!done) { done = true; r.complete(v); } }
    }

    @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) {
        Once o = new Once();
        new AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message)
            .setPositiveButton("OK", (d, w) -> o.finish(p.dismiss()))
            .setOnDismissListener(d -> o.finish(p.dismiss())).show();
        return o.r;
    }

    @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) {
        Once o = new Once();
        new AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message)
            .setPositiveButton("OK", (d, w) -> o.finish(p.confirm(ButtonPrompt.Type.POSITIVE)))
            .setNegativeButton("Cancel", (d, w) -> o.finish(p.confirm(ButtonPrompt.Type.NEGATIVE)))
            .setOnDismissListener(d -> o.finish(p.dismiss())).show();
        return o.r;
    }

    @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) {
        Once o = new Once();
        EditText in = new EditText(a);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        if (p.defaultValue != null) in.setText(p.defaultValue);
        new AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message).setView(in)
            .setPositiveButton("OK", (d, w) -> o.finish(p.confirm(in.getText().toString())))
            .setNegativeButton("Cancel", (d, w) -> o.finish(p.dismiss()))
            .setOnDismissListener(d -> o.finish(p.dismiss())).show();
        return o.r;
    }

    @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) {
        Once o = new Once();
        List<ChoicePrompt.Choice> flat = new ArrayList<>();
        flatten(p.choices, flat);
        String[] labels = new String[flat.size()];
        boolean[] checked = new boolean[flat.size()];
        for (int i = 0; i < flat.size(); i++) { labels[i] = flat.get(i).label; checked[i] = flat.get(i).selected; }
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(p.title != null ? p.title : p.message);
        if (p.type == ChoicePrompt.Type.MULTIPLE) {
            b.setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on)
             .setPositiveButton("OK", (d, w) -> {
                 List<String> ids = new ArrayList<>();
                 for (int i = 0; i < flat.size(); i++) if (checked[i]) ids.add(flat.get(i).id);
                 o.finish(p.confirm(ids.toArray(new String[0])));
             });
        } else {
            b.setItems(labels, (d, i) -> o.finish(p.confirm(flat.get(i).id)));
        }
        b.setOnDismissListener(d -> o.finish(p.dismiss())).show();
        return o.r;
    }

    private static void flatten(ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out) {
        if (in == null) return;
        for (ChoicePrompt.Choice c : in) {
            if (c.items != null) flatten(c.items, out);
            else if (!c.separator && !c.disabled) out.add(c);
        }
    }

    /** Pop-ups the page opens by itself (not from a tap) are blocked. */
    @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) {
        if (onPopupBlocked != null) onPopupBlocked.run();
        return GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY));
    }
}
