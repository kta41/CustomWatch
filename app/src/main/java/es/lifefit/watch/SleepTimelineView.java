package es.lifefit.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class SleepTimelineView extends View {
    public interface OnSelection {
        void selected(String detail);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SleepMetrics metrics;
    private final TimeZone zone;
    private final List<WatchSleepHistory.Sample> visible = new ArrayList<>();
    private OnSelection listener;
    private int selected = -1;

    public SleepTimelineView(Context context, SleepMetrics metrics, TimeZone zone) {
        super(context);
        this.metrics = metrics;
        this.zone = zone;
        for (WatchSleepHistory.Sample sample : metrics.samples) {
            if (sample.start < metrics.end && sample.end > metrics.start) visible.add(sample);
        }
        setFocusable(true);
        setClickable(true);
        setContentDescription("Línea temporal del sueño. Pulsa para recorrer los intervalos.");
    }

    public void setOnSelection(OnSelection listener) {
        this.listener = listener;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private String time(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    private String name(int state) {
        return state == 1 ? "Profundo" : state == 2 ? "Ligero" : "Despierto";
    }

    private float position(long time, float left, float width) {
        return left + width * (float) ((double) (time - metrics.start) / (metrics.end - metrics.start));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(78);
        float right = getWidth() - dp(8);
        float width = Math.max(1, right - left);
        float top = dp(18);
        float row = dp(42);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(174, 182, 180));
        for (int index = 0; index < 3; index++) {
            canvas.drawText(name(3 - index), 0, top + index * row + dp(16), paint);
            paint.setColor(Color.rgb(55, 62, 65));
            canvas.drawLine(left, top + index * row + dp(24), right, top + index * row + dp(24), paint);
            paint.setColor(Color.rgb(174, 182, 180));
        }
        int[] colors = {0, Color.rgb(130, 165, 121), Color.rgb(177, 201, 157), Color.rgb(210, 170, 127)};
        for (int index = 0; index < visible.size(); index++) {
            WatchSleepHistory.Sample sample = visible.get(index);
            float start = position(Math.max(metrics.start, sample.start), left, width);
            float end = position(Math.min(metrics.end, sample.end), left, width);
            float y = top + (3 - sample.state) * row + dp(7);
            paint.setColor(colors[sample.state]);
            canvas.drawRoundRect(start, y, Math.max(start + dp(1), end), y + dp(18), dp(3), dp(3), paint);
            if (index == selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Color.WHITE);
                canvas.drawRoundRect(start, y, Math.max(start + dp(1), end), y + dp(18), dp(3), dp(3), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        paint.setColor(Color.rgb(174, 182, 180));
        paint.setTextSize(dp(11));
        canvas.drawText(time(metrics.start), left, top + 3 * row + dp(18), paint);
        String endLabel = time(metrics.end);
        canvas.drawText(endLabel, right - paint.measureText(endLabel), top + 3 * row + dp(18), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float left = dp(78);
            float width = Math.max(1, getWidth() - dp(8) - left);
            double fraction = Math.max(0, Math.min(1, (event.getX() - left) / width));
            long time = metrics.start + (long) ((metrics.end - metrics.start) * fraction);
            selected = -1;
            for (int index = 0; index < visible.size(); index++) {
                WatchSleepHistory.Sample sample = visible.get(index);
                if (time >= sample.start && time < sample.end) {
                    selected = index;
                    break;
                }
            }
            notifySelection();
            super.performClick();
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        if (!visible.isEmpty()) selected = (selected + 1) % visible.size();
        notifySelection();
        return super.performClick();
    }

    private void notifySelection() {
        String detail = "Sin datos en este intervalo";
        if (selected >= 0) {
            WatchSleepHistory.Sample sample = visible.get(selected);
            long start = Math.max(metrics.start, sample.start);
            long end = Math.min(metrics.end, sample.end);
            detail = name(sample.state) + " · " + time(start) + " – " + time(end)
                    + " · " + (end - start) / 60000 + " min";
        }
        setContentDescription("Sueño. " + detail + ". Pulsa para recorrer los intervalos.");
        if (listener != null) listener.selected(detail);
        invalidate();
    }
}