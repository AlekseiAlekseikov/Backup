package w0;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Log;
import com.ex.auto.MainActivity;
import com.ex.auto.Politica;
import com.ex.auto.R;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
/* loaded from: classes.dex */
public final class kg {

    /* renamed from: a  reason: collision with root package name */
    public static final int f3856a = Politica.h(21);
    public static final int b = Politica.h(23);

    /* renamed from: c  reason: collision with root package name */
    public static final HashMap f3857c;

    static {
        Politica.h(24);
        f3857c = new HashMap();
    }

    public static Icon a(Context context, String str, String str2, int i3, int i4, boolean z2, boolean z3, float f) {
        float measureText;
        float f3;
        float h3;
        float f4;
        int i5;
        Paint paint = new Paint(129);
        paint.setColor(i3);
        Typeface typeface = Typeface.DEFAULT;
        paint.setTypeface(typeface);
        float h4 = h(context, 56);
        float f5 = f * h4;
        paint.setTextSize(h4);
        float measureText2 = paint.measureText(str);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float f6 = fontMetrics.descent - fontMetrics.ascent;
        if (str2.length() == 0) {
            f3 = 0.0f;
            measureText = 0.0f;
            h3 = 0.0f;
        } else {
            paint.setTextSize(f5);
            measureText = paint.measureText(str2);
            f3 = 0.0f;
            h3 = h(context, f < 0.5f ? 1 : 3);
        }
        int i6 = (int) (f6 / 0.61f);
        if (i6 < 1) {
            i6 = 1;
        }
        int h5 = h(context, i4);
        int i7 = (h5 * 2) + ((int) (measureText2 + h3 + measureText));
        if (z3) {
            Paint paint2 = new Paint(129);
            paint2.setTypeface(typeface);
            float h6 = h(context, 56);
            paint2.setTextSize(h6);
            float measureText3 = paint2.measureText("99999");
            paint2.setTextSize(h6 * 0.55f);
            i5 = (int) (paint2.measureText("km") + h(context, 1) + measureText3);
            f4 = measureText2;
        } else if (z2) {
            Paint paint3 = new Paint(129);
            paint3.setTypeface(typeface);
            float h7 = h(context, 56);
            float f7 = 0.82f * h7;
            f4 = measureText2;
            float h8 = h(context, 3);
            int h9 = h(context, 10);
            paint3.setTextSize(h7);
            float measureText4 = paint3.measureText("-40");
            paint3.setTextSize(f7);
            int measureText5 = (int) (measureText4 + h8 + paint3.measureText("°C"));
            int i8 = h9 * 2;
            paint3.setTextSize(h7);
            float measureText6 = paint3.measureText("100");
            paint3.setTextSize(f7);
            i5 = Math.max(measureText5 + i8, i8 + ((int) (measureText6 + h8 + paint3.measureText("%"))));
        } else {
            f4 = measureText2;
            i5 = 0;
        }
        int max = Math.max(i7, i5);
        Bitmap createBitmap = Bitmap.createBitmap(max >= 1 ? max : 1, i6, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(createBitmap);
        float f8 = ((i6 - f6) / 2.0f) - fontMetrics.ascent;
        paint.setTextSize(h4);
        float f9 = (max - i7) / 2.0f;
        if (f9 < f3) {
            f9 = f3;
        }
        float f10 = h5;
        canvas.drawText(str, f10 + f9, f8, paint);
        if (str2.length() > 0) {
            paint.setTextSize(f5);
            canvas.drawText(str2, f10 + f4 + h3 + f9, f8, paint);
        }
        Icon createWithBitmap = Icon.createWithBitmap(createBitmap);
        i2.g.d(createWithBitmap, "createWithBitmap(...)");
        return createWithBitmap;
    }

    public static void c(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(15021);
            notificationManager.cancel(15020);
        }
        HashMap hashMap = f3857c;
        hashMap.remove(15021);
        hashMap.remove(15020);
    }

    public static void d(Context context) {
        i2.g.e(context, "context");
        NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(15020);
        }
        f3857c.remove(15020);
    }

    public static void e(Context context) {
        i2.g.e(context, "context");
        NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(15021);
        }
        f3857c.remove(15021);
    }

    public static void f(Context context) {
        i2.g.e(context, "context");
        NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(15028);
        }
        f3857c.remove(15028);
    }

    public static void g(Context context) {
        i2.g.e(context, "context");
        NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(15026);
        }
        f3857c.remove(15026);
    }

    public static int h(Context context, int i3) {
        return (int) ((i3 * context.getResources().getDisplayMetrics().density) + 0.5f);
    }

    public static String i(float f) {
        float round = Math.round(f * 10.0f) / 10.0f;
        int i3 = (int) round;
        return round == ((float) i3) ? String.valueOf(i3) : String.format("%.1f", Arrays.copyOf(new Object[]{Float.valueOf(round)}, 1));
    }

    public static void j(Context context, int i3, int i4, int i5, String str, String str2, String str3, Icon icon, PendingIntent pendingIntent) {
        Context applicationContext = context.getApplicationContext();
        NotificationManager notificationManager = (NotificationManager) applicationContext.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel notificationChannel = new NotificationChannel("central_ex_status_temp", applicationContext.getString(R.string.k_temp_icon_channel), 2);
        notificationChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(notificationChannel);
        Integer valueOf = Integer.valueOf(i3);
        HashMap hashMap = f3857c;
        Integer num = (Integer) hashMap.get(valueOf);
        if (num != null && num.intValue() != i5) {
            try {
                notificationManager.cancel(i3);
            } catch (Throwable unused) {
            }
        }
        hashMap.put(Integer.valueOf(i3), Integer.valueOf(i5));
        Log.i("KmIconProbe", "postStatusIcon " + str + " notifId=" + i3 + " iconId=" + i4 + " rank=" + i5 + " text=\"" + str3 + '\"');
        if (pendingIntent == null) {
            pendingIntent = PendingIntent.getActivity(applicationContext, i4, new Intent(applicationContext, MainActivity.class), 201326592);
        }
        Notification build = new Notification.Builder(applicationContext, "central_ex_status_temp").setSmallIcon(icon).setContentTitle(str2).setContentText(str3).setContentIntent(pendingIntent).setOngoing(true).setShowWhen(false).build();
        i2.g.d(build, "build(...)");
        Bundle bundle = build.extras;
        bundle.putBoolean("flag_status_icon_notification", true);
        bundle.putInt("flag_status_icon_id", i4);
        bundle.putString("flag_status_icon_describe", str);
        bundle.putParcelable("flag_status_icon_icon", icon);
        bundle.putParcelable("flag_status_icon_pressed_icon", icon);
        bundle.putBoolean("flag_status_icon_hide", false);
        bundle.putInt("flag_status_icon_rank", i5);
        bundle.putInt("flag_status_icon_space_x", 1);
        bundle.putBoolean("flag_status_icon_is_pick_on", false);
        bundle.putInt("flag_status_icon_specific_width", 0);
        try {
            notificationManager.notify(i3, build);
        } catch (Throwable unused2) {
        }
    }

    public static Float k(CarPropertyManager carPropertyManager, int i3) {
        Integer c3 = g1.c(carPropertyManager, i3, 0);
        Float valueOf = c3 != null ? Float.valueOf(c3.intValue()) : g1.b(carPropertyManager, i3, 0);
        Log.i("OdoProbe", String.format("0x%x -> %s", Arrays.copyOf(new Object[]{Integer.valueOf(i3), (valueOf == null || (r0 = valueOf.toString()) == null) ? "null" : "null"}, 2)));
        if (valueOf != null) {
            float floatValue = valueOf.floatValue();
            if (floatValue < 1.0f || floatValue > 2000000.0f) {
                return null;
            }
            return valueOf;
        }
        return null;
    }

    public static Float l(CarPropertyManager carPropertyManager) {
        if (carPropertyManager == null) {
            return null;
        }
        AtomicInteger atomicInteger = g1.f3600a;
        return y7.N(g1.b(carPropertyManager, b, 0));
    }
}
