package w0;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.ex.auto.AppPipFloaterService;
import com.ex.auto.BlackoutFloaterService;
import com.ex.auto.CentralWatchdogService;
import com.ex.auto.DpadFloaterService;
import com.ex.auto.ExSapoCastFloaterService;
import com.ex.auto.HomeFloatingWidgetsService;
import com.ex.auto.HomeWidgetsPollService;
import com.ex.auto.LyricsFloaterService;
import com.ex.auto.MediaBarFloaterService;
import com.ex.auto.ModoOficinaTriggerService;
import com.ex.auto.QuickAccessFloaterService;
import com.ex.auto.R;
import com.ex.auto.SplitFloaterService;
import com.ex.auto.TempBattPillFloaterService;
import com.ex.auto.TimerFloaterService;
import com.ex.auto.VhalProviderService;
import com.ex.auto.WifiFloaterService;
import com.geely.driveregenfloater.DriveRegenFloaterService;
import java.io.File;
import java.util.List;
import org.json.JSONObject;
/* loaded from: classes.dex */
public final class qd {

    /* renamed from: a  reason: collision with root package name */
    public static final qd f4149a = new Object();
    public static final List b = y1.h.V(QuickAccessFloaterService.class, TempBattPillFloaterService.class, HomeFloatingWidgetsService.class, MediaBarFloaterService.class, LyricsFloaterService.class, WifiFloaterService.class, DpadFloaterService.class, SplitFloaterService.class, AppPipFloaterService.class, ExSapoCastFloaterService.class, BlackoutFloaterService.class, DriveRegenFloaterService.class, TimerFloaterService.class, HomeWidgetsPollService.class, VhalProviderService.class);

    /* renamed from: c  reason: collision with root package name */
    public static final ComponentName f4150c = new ComponentName("com.ex.auto", "com.ex.auto.MainActivity");

    /* renamed from: d  reason: collision with root package name */
    public static volatile boolean f4151d;

    /* renamed from: e  reason: collision with root package name */
    public static volatile boolean f4152e;

    public static boolean a(Context context) {
        i2.g.e(context, "ctx");
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences("tela_preta_prefs", 0);
            i2.g.d(sharedPreferences, "getSharedPreferences(...)");
            return sharedPreferences.getBoolean("modo_oficina", false);
        } catch (Throwable unused) {
            return false;
        }
    }

    public static void b(Context context, boolean z2) {
        try {
            for (ComponentName componentName : k1.a.I(f4150c)) {
                context.getPackageManager().setComponentEnabledSetting(componentName, z2 ? 1 : 2, 1);
            }
        } catch (Throwable th) {
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "não consegui mexer no ícone: " + th.getMessage(), false);
        }
    }

    public static void d(Context context) {
        i2.g.e(context, "ctx");
        SharedPreferences sharedPreferences = context.getSharedPreferences("tela_preta_prefs", 0);
        i2.g.b(sharedPreferences);
        if (sharedPreferences.getBoolean("modo_oficina", false)) {
            return;
        }
        boolean z2 = sharedPreferences.getBoolean("quick_access_enabled", false);
        boolean z3 = sharedPreferences.getBoolean("gestures_enabled", false);
        if (z2 || z3) {
            try {
                context.startService(new Intent(context, QuickAccessFloaterService.class));
            } catch (Throwable unused) {
            }
        }
        if (sharedPreferences.getBoolean("temp_batt_pill_enabled", false)) {
            try {
                context.startService(new Intent(context, TempBattPillFloaterService.class));
            } catch (Throwable unused2) {
            }
        }
        try {
            List list = HomeFloatingWidgetsService.f926a0;
            k1.B(context);
        } catch (Throwable unused3) {
        }
        if (sharedPreferences.getBoolean("media_bar_floater_enabled", false)) {
            try {
                context.startForegroundService(new Intent(context, MediaBarFloaterService.class));
            } catch (Throwable unused4) {
            }
        }
        if (sharedPreferences.getBoolean("lyrics_floater_enabled", false)) {
            try {
                context.startForegroundService(new Intent(context, LyricsFloaterService.class));
            } catch (Throwable unused5) {
            }
        }
    }

    public static void e(CentralWatchdogService centralWatchdogService) {
        if (f4152e) {
            return;
        }
        boolean z2 = true;
        f4152e = true;
        boolean z3 = !a(centralWatchdogService);
        try {
            if (centralWatchdogService.getPackageManager().getComponentEnabledSetting(f4150c) == 2) {
                z2 = false;
            }
            if (z2 == z3) {
                return;
            }
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "ícone fora de sincronia (deveria=" + z3 + ") — corrigindo", false);
            b(centralWatchdogService, z3);
        } catch (Throwable unused) {
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(10:18|(3:19|20|21)|(2:22|23)|(9:25|26|(1:47)(1:30)|31|32|33|34|35|36)|37|38|39|41|42|16) */
    /* JADX WARN: Can't wrap try/catch for region: R(41:1|(1:3)|4|5|6|(1:8)(1:180)|9|(1:11)(13:146|(1:148)|149|150|(3:152|(2:154|155)(1:157)|156)|158|159|(3:161|(2:163|164)(1:166)|165)|167|168|(3:172|(2:174|(2:176|177))|178)|170|171)|12|(1:14)|15|(21:18|19|20|21|22|23|25|26|(1:47)(1:30)|31|32|33|34|35|36|37|38|39|41|42|16)|55|56|(2:58|(1:60)(1:144))(1:145)|61|(1:63)|64|(3:66|(3:69|(21:71|72|(1:74)(1:132)|75|76|(2:77|(2:79|(2:81|82))(2:130|131))|83|(2:85|(2:87|88))|92|93|94|95|(1:97)(2:119|(1:121)(1:(1:123)(2:124|(1:126)(1:127))))|98|(1:118)(2:101|(1:103)(1:117))|104|(1:116)(1:107)|108|(1:115)(1:111)|112|113)|67)|133)|134|(2:136|(2:137|(1:143)(2:139|(2:141|142))))(0)|76|(3:77|(0)(0)|80)|83|(0)|92|93|94|95|(0)(0)|98|(0)|118|104|(0)|116|108|(0)|115|112|113) */
    /* JADX WARN: Removed duplicated region for block: B:107:0x0271  */
    /* JADX WARN: Removed duplicated region for block: B:113:0x0287  */
    /* JADX WARN: Removed duplicated region for block: B:123:0x02fc  */
    /* JADX WARN: Removed duplicated region for block: B:124:0x02ff  */
    /* JADX WARN: Removed duplicated region for block: B:179:0x0282 A[SYNTHETIC] */
    /* JADX WARN: Type inference failed for: r5v11, types: [java.lang.Object, java.lang.Iterable] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static w0.pd g(android.content.Context r24, java.lang.String r25) {
        /*
            Method dump skipped, instructions count: 965
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: w0.qd.g(android.content.Context, java.lang.String):w0.pd");
    }

    public static pd h(Context context, String str) {
        zd zdVar = zd.f4609a;
        SharedPreferences c3 = zd.c(context);
        try {
            if (c3.edit().putBoolean("modo_oficina_restaurando", true).commit()) {
                List list = u5.f4369a;
                u5.h(context);
                context.startForegroundService(new Intent(context, CentralWatchdogService.class));
                u5.l(context);
                if (c3.edit().putBoolean("modo_oficina", false).putLong("modo_oficina_ate", 0L).putBoolean("modo_oficina_restaurando", false).putBoolean("modo_oficina_entrada_pendente", false).putBoolean("modo_oficina_aviso_saida_pendente", true).commit()) {
                    new Handler(Looper.getMainLooper()).post(new a1.i(new JSONObject(f2.d.h0(u5.a(context))).getJSONArray("services"), 13, context));
                    if (u5.a(context).delete()) {
                        ModoOficinaTriggerService modoOficinaTriggerService = ModoOficinaTriggerService.f;
                        y7.u(context);
                        d(context);
                        u5.h(context);
                        context.startForegroundService(new Intent(context, CentralWatchdogService.class));
                        ud udVar = ud.f4381a;
                        ud.b("ModoOficina", "restaurar (" + str + "): estado completo confirmado", true);
                        new Handler(Looper.getMainLooper()).post(new e.l(context.getApplicationContext(), 2));
                        return new pd("Configurações e modificações restauradas e conferidas.", "", true);
                    }
                    throw new IllegalStateException("Check failed.");
                }
                throw new IllegalStateException("Check failed.");
            }
            throw new IllegalStateException("Check failed.");
        } catch (Exception e3) {
            context.startForegroundService(new Intent(context, CentralWatchdogService.class));
            ud udVar2 = ud.f4381a;
            StringBuilder l3 = androidx.activity.result.b.l("restaurar (", str, ") incompleto: ");
            l3.append(e3.getMessage());
            l3.append("; registro preservado");
            ud.b("ModoOficina", l3.toString(), true);
            return new pd("Restauração incompleta: " + e3.getMessage() + ". Tente novamente; o registro foi preservado.", "", false);
        }
    }

    public static void i(Context context) {
        for (Class cls : b) {
            try {
                context.stopService(new Intent(context, cls));
            } catch (Throwable unused) {
            }
        }
        try {
            Object systemService = context.getSystemService("notification");
            i2.g.c(systemService, "null cannot be cast to non-null type android.app.NotificationManager");
            ((NotificationManager) systemService).cancelAll();
        } catch (Throwable unused2) {
        }
    }

    public static boolean j(Context context) {
        List list = u5.f4369a;
        if (u5.a(context).exists()) {
            return true;
        }
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = context.getFilesDir();
        }
        return new File(externalFilesDir, "modo_oficina_estado.txt").exists();
    }

    public static String k(int i3, String str) {
        try {
            return m.x(str, i3, null, new n(0));
        } catch (Exception e3) {
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "shell indisponível (ConnAdaptor não mexido): " + e3.getMessage(), false);
            return "ADB_INDISPONIVEL: " + e3.getMessage();
        }
    }

    public static void l(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("tela_preta_prefs", 0);
        if (sharedPreferences.getBoolean("modo_oficina_restaurando", false)) {
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "restaurar tinha ficado pela metade — retomando no boot", false);
            new Thread(new e.l(context, 4)).start();
        } else if (sharedPreferences.getBoolean("modo_oficina", false)) {
            if (sharedPreferences.getBoolean("modo_oficina_entrada_pendente", false)) {
                new Thread(new e.l(context, 5)).start();
            }
            long j3 = sharedPreferences.getLong("modo_oficina_ate", 0L);
            if (1 <= j3 && j3 <= System.currentTimeMillis()) {
                new Thread(new e.l(context, 6)).start();
                return;
            }
            i(context);
            ModoOficinaTriggerService modoOficinaTriggerService = ModoOficinaTriggerService.f;
            y7.u(context);
        }
    }

    public final synchronized pd c(Context context, int i3) {
        pd pdVar;
        JSONObject k3;
        SharedPreferences.Editor putBoolean;
        long j3;
        zd zdVar = zd.f4609a;
        SharedPreferences c3 = zd.c(context);
        if (c3.getBoolean("modo_oficina", false)) {
            return new pd("Modo oficina já está ativo", "", false);
        }
        try {
            List list = u5.f4369a;
            k3 = u5.k(context);
            putBoolean = c3.edit().putBoolean("modo_oficina", true);
            if (i3 > 0) {
                j3 = (i3 * 3600000) + System.currentTimeMillis();
            } else {
                j3 = 0;
            }
        } catch (Exception e3) {
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "ocultar incompleto: " + e3.getMessage() + "; registro preservado", true);
            if (c3.getBoolean("modo_oficina", false)) {
                context.startForegroundService(new Intent(context, CentralWatchdogService.class));
            }
            pdVar = new pd("Modo oficina não foi concluído: " + e3.getMessage() + ". O registro foi preservado para restaurar.", "", false);
        }
        if (putBoolean.putLong("modo_oficina_ate", j3).putInt("modo_oficina_horas", i3).putBoolean("modo_oficina_entrada_pendente", true).commit()) {
            new Handler(Looper.getMainLooper()).post(new e.l(context, 7));
            u5.h(context);
            i(context);
            ModoOficinaTriggerService modoOficinaTriggerService = ModoOficinaTriggerService.f;
            y7.u(context);
            context.startForegroundService(new Intent(context, CentralWatchdogService.class));
            u5.f(context, k3);
            if (c3.edit().putBoolean("modo_oficina_entrada_pendente", false).commit()) {
                context.startForegroundService(new Intent(context, CentralWatchdogService.class));
                ud udVar2 = ud.f4381a;
                ud.b("ModoOficina", "ocultar: estado completo confirmado", true);
                String string = context.getString(R.string.k_modo_oficina_ocultou, Integer.valueOf(k3.getJSONArray("packages").length()));
                i2.g.d(string, "getString(...)");
                pdVar = new pd(string, "", true);
                return pdVar;
            }
            throw new IllegalStateException("Check failed.");
        }
        throw new IllegalStateException("Check failed.");
    }

    public final synchronized pd f(Context context, String str) {
        i2.g.e(context, "ctx");
        if (str.equals("retomada-boot")) {
            zd zdVar = zd.f4609a;
            if (!zd.c(context).getBoolean("modo_oficina_restaurando", false)) {
                return new pd("Restauração já concluída", "", true);
            }
        }
        List list = u5.f4369a;
        if (u5.a(context).exists()) {
            return h(context, str);
        } else if (!f4151d) {
            f4151d = true;
            pd g2 = g(context, str);
            f4151d = false;
            return g2;
        } else {
            ud udVar = ud.f4381a;
            ud.b("ModoOficina", "restaurar (" + str + ") ignorado — já há um em andamento", false);
            String string = context.getString(R.string.k_modo_oficina_restaurando);
            i2.g.d(string, "getString(...)");
            return new pd(string, "", true);
        }
    }
}
