package w0;

import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.nsd.NsdManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import com.ex.auto.ExSapoCastActivity;
import com.ex.auto.ExSapoCastFloaterService;
import com.ex.auto.PhoneSyncService;
import com.ex.auto.R;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/* loaded from: classes.dex */
public final class m {

    /* renamed from: a  reason: collision with root package name */
    public static volatile int f3919a = 0;

    /* renamed from: c  reason: collision with root package name */
    public static volatile ContextWrapper f3920c = null;

    /* renamed from: d  reason: collision with root package name */
    public static Context f3921d = null;

    /* renamed from: e  reason: collision with root package name */
    public static volatile boolean f3922e = false;
    public static volatile String f = null;

    /* renamed from: g  reason: collision with root package name */
    public static volatile long f3923g = 0;

    /* renamed from: h  reason: collision with root package name */
    public static volatile String f3924h = "pip";

    /* renamed from: i  reason: collision with root package name */
    public static volatile e6 f3925i;

    /* renamed from: k  reason: collision with root package name */
    public static NsdManager f3927k;

    /* renamed from: l  reason: collision with root package name */
    public static rd f3928l;
    public static final m b = new Object();

    /* renamed from: j  reason: collision with root package name */
    public static final m f3926j = new Object();

    public static String A(String str, String str2, String str3) {
        StringBuilder sb = new StringBuilder();
        sb.append(o2.k.E0(str).toString());
        sb.append(' ');
        String obj = o2.k.E0(str2).toString();
        if (obj.length() == 0) {
            obj = o2.k.E0(str3).toString();
        }
        sb.append(obj);
        return o2.k.E0(sb.toString()).toString();
    }

    public static byte[] B(byte[] bArr) {
        long floorDiv = Math.floorDiv(System.currentTimeMillis(), 900000L);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bArr, "HmacSHA256"));
        byte[] bArr2 = new byte[8];
        for (int i3 = 0; i3 < 8; i3++) {
            bArr2[i3] = (byte) ((floorDiv >> (56 - (i3 * 8))) & 255);
        }
        byte[] doFinal = mac.doFinal(bArr2);
        i2.g.d(doFinal, "doFinal(...)");
        byte[] copyOf = Arrays.copyOf(doFinal, 6);
        i2.g.d(copyOf, "copyOf(...)");
        return copyOf;
    }

    public static String C(String str) {
        Pattern compile = Pattern.compile("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"");
        i2.g.d(compile, "compile(...)");
        Matcher matcher = compile.matcher(str);
        i2.g.d(matcher, "matcher(...)");
        o2.e b3 = k1.a.b(matcher, 0, str);
        if (b3 == null) {
            return null;
        }
        String c02 = o2.t.c0((String) ((o2.d) b3.a()).get(1), "\\/", "/");
        if (o2.k.m0(c02)) {
            return null;
        }
        return o2.t.c0(c02, "100x100bb", "600x600bb");
    }

    public static void D(File file) {
        String m3 = m();
        if (m3 == null) {
            return;
        }
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            byte[] bArr = new byte[8192];
            while (true) {
                int read = fileInputStream.read(bArr);
                if (read < 0) {
                    break;
                }
                messageDigest.update(bArr, 0, read);
            }
            fileInputStream.close();
            byte[] digest = messageDigest.digest();
            i2.g.d(digest, "digest(...)");
            if (!y1.f.a0(digest, new d2(19)).equalsIgnoreCase(m3)) {
                throw new IOException("sha256 diverge");
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                a1.b.p(fileInputStream, th);
                throw th2;
            }
        }
    }

    public static void E(BufferedOutputStream bufferedOutputStream, int i3, byte[] bArr) {
        int length = bArr.length;
        i2.g.e(bufferedOutputStream, "out");
        byte[] bArr2 = {(byte) i3, (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
        synchronized (bufferedOutputStream) {
            try {
                bufferedOutputStream.write(bArr2);
                if (length > 0) {
                    bufferedOutputStream.write(bArr, 0, length);
                }
                bufferedOutputStream.flush();
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public static final void a(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawableResource(R.drawable.bg_master_shell);
        window.setDimAmount(0.58f);
        window.addFlags(2);
        View decorView = window.getDecorView();
        i2.g.d(decorView, "getDecorView(...)");
        decorView.addOnAttachStateChangeListener(new androidx.fragment.app.m0(3, dialog));
    }

    public static final boolean b(IBinder iBinder, BluetoothDevice bluetoothDevice) {
        if (iBinder == null) {
            return false;
        }
        Parcel obtain = Parcel.obtain();
        i2.g.d(obtain, "obtain(...)");
        Parcel obtain2 = Parcel.obtain();
        i2.g.d(obtain2, "obtain(...)");
        try {
            obtain.writeInterfaceToken("android.bluetooth.IBluetoothHeadsetClient");
            obtain.writeInt(1);
            bluetoothDevice.writeToParcel(obtain, 0);
            iBinder.transact(1, obtain, obtain2, 0);
            obtain2.readException();
            return obtain2.readInt() != 0;
        } finally {
            obtain2.recycle();
            obtain.recycle();
        }
    }

    public static String c(int i3, String str) {
        try {
            return x(str, i3, null, new n(0));
        } catch (Exception e3) {
            Log.w("GesturePatch", "adb falhou: " + e3.getClass().getSimpleName() + ": " + e3.getMessage(), e3);
            return "ADB_FAIL: " + e3.getClass().getSimpleName() + ": " + e3.getMessage();
        }
    }

    public static boolean d(Context context, h2.l lVar) {
        boolean z2;
        boolean e02 = o2.k.e0(c(8000, "[ -f '/data/local/gesture-launcher/launcher.apk' ] && echo SRC_OK || echo SRC_FAIL"), "SRC_OK", false);
        k5 a3 = l5.a(context, "https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json");
        if (e02 && p(context, a3)) {
            z2 = true;
        } else {
            try {
                File file = new File(context.getCacheDir(), "gesture-launcher.apk");
                if (a3 != null) {
                    k(a3.f3816a, file, lVar);
                    lVar.c("verify");
                    l5.b(context, file, a3.f3818d, a3.b);
                } else if (k1.c("https://raw.githubusercontent.com/swimapps/Outros/main/GeelyAutoLauncher-gesture-signed.apk", u.f4329h) == v4.b) {
                    throw new IllegalStateException("o patch de gestos nao esta mais no GitHub; a distribuicao propria e o unico caminho");
                } else {
                    k("https://raw.githubusercontent.com/swimapps/Outros/main/GeelyAutoLauncher-gesture-signed.apk", file, lVar);
                    lVar.c("verify");
                    D(file);
                }
                lVar.c("copy");
                StringBuilder sb = new StringBuilder("'");
                String absolutePath = file.getAbsolutePath();
                i2.g.d(absolutePath, "getAbsolutePath(...)");
                sb.append(o2.t.c0(absolutePath, "'", "'\\''"));
                sb.append('\'');
                z2 = o2.k.e0(c(60000, o2.m.V("\n                mkdir -p /data/local/gesture-launcher 2>/dev/null\n                cp -f " + sb.toString() + " '/data/local/gesture-launcher/launcher.apk' 2>&1 || exit 20\n                chown root:root '/data/local/gesture-launcher/launcher.apk' 2>&1 || true\n                chmod 644 '/data/local/gesture-launcher/launcher.apk' 2>&1 || true\n                chcon u:object_r:system_app_file:s0 '/data/local/gesture-launcher/launcher.apk' 2>&1 || true\n                [ -f '/data/local/gesture-launcher/launcher.apk' ] && echo SRC_OK || echo SRC_FAIL\n                ")), "SRC_OK", false);
                if (z2 && a3 != null) {
                    context.getSharedPreferences("tela_preta_prefs", 0).edit().putLong("gestures_vc_baixado", a3.b).apply();
                }
            } catch (Throwable th) {
                androidx.activity.result.b.s(th, new StringBuilder("ensureSource: "), "GesturePatch");
                z2 = false;
            }
        }
        if (z2) {
            lVar.c("mount");
            boolean a4 = le.f3909a.a(context, "visual.aplicarGestos", y1.s.W(new x1.a("src", "/data/local/gesture-launcher/launcher.apk")));
            if (!a4) {
                Log.w("GesturePatch", "apply: sem playbook ou bind não confirmado");
            }
            return a4;
        }
        return false;
    }

    public static final void e(Typeface typeface, Context context, int i3, int i4, int i5, View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            Typeface typeface2 = textView.getTypeface();
            int style = typeface2 != null ? typeface2.getStyle() : 0;
            if (!i2.g.a(textView.getTypeface(), Typeface.MONOSPACE)) {
                textView.setTypeface(Typeface.create(typeface, style));
            }
            if (!(view instanceof Button) && k1.a.P(-1, -16777216, Integer.valueOf(context.getColor(R.color.cx_text_primary)), Integer.valueOf(context.getColor(R.color.cx_text_secondary))).contains(Integer.valueOf(textView.getCurrentTextColor()))) {
                textView.setTextColor(textView.getCurrentTextColor() == context.getColor(R.color.cx_text_secondary) ? i3 : i4);
            }
        }
        if (view instanceof CompoundButton) {
            ((CompoundButton) view).setButtonTintList(new ColorStateList(new int[][]{new int[]{16842912}, new int[0]}, new int[]{i5, i3}));
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            int childCount = viewGroup.getChildCount();
            for (int i6 = 0; i6 < childCount; i6++) {
                View childAt = viewGroup.getChildAt(i6);
                i2.g.d(childAt, "getChildAt(...)");
                e(typeface, context, i3, i4, i5, childAt);
            }
        }
    }

    public static void f(Context context, int i3, boolean z2) {
        boolean z3 = false;
        int k3 = z2 ? k1.a.k(i3, 0, 255) : k1.a.k(i3, 12, 255);
        if (context != null && (!z2 || k3 > 0)) {
            try {
                Integer t2 = t();
                if (t2 != null && t2.intValue() == 0) {
                    z3 = true;
                }
                Settings.System.putInt(context.getContentResolver(), "screen_brightness", k3);
                if (z3) {
                    f2.d.i0(new File("/sys/class/leds/lcd-backlight/brightness"), String.valueOf(k3));
                    return;
                }
                return;
            } catch (Throwable th) {
                Log.w("BrightMem", "SCREEN_BRIGHTNESS recusado (" + th.getClass().getSimpleName() + ") — indo pelo /sys");
            }
        }
        try {
            try {
                new kotlinx.coroutines.scheduling.h(5555).b(3000, "setenforce 0 2>/dev/null");
            } catch (Exception unused) {
                f2.d.i0(new File("/sys/class/leds/lcd-backlight/brightness"), String.valueOf(k3));
            }
        } catch (Throwable th2) {
            androidx.activity.result.b.s(th2, new StringBuilder("applyBrightness: "), "BrightMem");
        }
    }

    public static void g(Context context, String str, String str2, String str3, String str4) {
        boolean z2;
        try {
            Field declaredField = Class.forName(str2.concat("$Stub")).getDeclaredField("TRANSACTION_disconnect");
            declaredField.setAccessible(true);
            int i3 = declaredField.getInt(null);
            try {
                BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
                if (defaultAdapter != null && defaultAdapter.isEnabled()) {
                    BluetoothDevice remoteDevice = defaultAdapter.getRemoteDevice(str3);
                    Context applicationContext = context.getApplicationContext();
                    v0 v0Var = new v0(str2, remoteDevice, i3, str4, str3, applicationContext);
                    try {
                        Intent intent = new Intent();
                        intent.setComponent(new ComponentName("com.android.bluetooth", str));
                        z2 = applicationContext.bindService(intent, v0Var, 1);
                    } catch (Throwable unused) {
                        z2 = false;
                    }
                    if (z2) {
                        return;
                    }
                    ud udVar = ud.f4381a;
                    ud.b("BtHfpClient", "disconnect " + str4 + ": bind recusado (" + str + ')', false);
                    applicationContext.unbindService(v0Var);
                }
            } catch (Throwable unused2) {
            }
        } catch (Throwable th) {
            ud udVar2 = ud.f4381a;
            StringBuilder l3 = androidx.activity.result.b.l("disconnect ", str4, ": não achei TRANSACTION_disconnect (");
            l3.append(th.getClass().getSimpleName());
            l3.append(") — não mexo");
            ud.b("BtHfpClient", l3.toString(), false);
        }
    }

    public static String h(String str, String str2, String str3) {
        i2.g.e(str, "artista");
        String obj = o2.k.E0(str).toString();
        Locale locale = Locale.ROOT;
        String lowerCase = obj.toLowerCase(locale);
        i2.g.d(lowerCase, "toLowerCase(...)");
        String lowerCase2 = o2.k.E0(str2).toString().toLowerCase(locale);
        i2.g.d(lowerCase2, "toLowerCase(...)");
        if (lowerCase2.length() == 0) {
            lowerCase2 = o2.k.E0(str3).toString().toLowerCase(locale);
            i2.g.d(lowerCase2, "toLowerCase(...)");
        }
        return lowerCase + '|' + lowerCase2;
    }

    public static void i(Context context, String str, h2.l lVar) {
        boolean z2;
        i2.g.e(context, "context");
        i2.g.e(str, "mac");
        try {
            BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
            if (defaultAdapter != null && defaultAdapter.isEnabled()) {
                BluetoothDevice remoteDevice = defaultAdapter.getRemoteDevice(str);
                Context applicationContext = context.getApplicationContext();
                w0 w0Var = new w0(remoteDevice, str, applicationContext, lVar);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.android.bluetooth", "com.android.bluetooth.hfpclient.HeadsetClientService"));
                try {
                    z2 = applicationContext.bindService(intent, w0Var, 1);
                } catch (Throwable th) {
                    Log.w("BtHfpClient", "bindService lançou: " + th.getClass().getSimpleName() + ": " + th.getMessage());
                    z2 = false;
                }
                if (z2) {
                    return;
                }
                ud udVar = ud.f4381a;
                ud.b("BtHfpClient", "bindService(com.android.bluetooth.hfpclient.HeadsetClientService) recusado — HFP-Client indisponível neste firmware", false);
                try {
                    applicationContext.unbindService(w0Var);
                } catch (Throwable unused) {
                }
                if (lVar != null) {
                    lVar.c(Boolean.FALSE);
                    return;
                }
                return;
            }
            Log.w("BtHfpClient", "adapter ausente ou desligado");
            if (lVar != null) {
                lVar.c(Boolean.FALSE);
            }
        } catch (Throwable th2) {
            androidx.activity.result.b.s(th2, androidx.activity.result.b.l("MAC inválido (", str, "): "), "BtHfpClient");
            if (lVar != null) {
                lVar.c(Boolean.FALSE);
            }
        }
    }

    public static void j(Context context, String str) {
        i2.g.e(str, "mac");
        g(context, "com.android.bluetooth.hfpclient.HeadsetClientService", "android.bluetooth.IBluetoothHeadsetClient", str, "HFP-Client");
        g(context, "com.android.bluetooth.a2dpsink.A2dpSinkService", "android.bluetooth.IBluetoothA2dpSink", str, "A2DP-Sink");
    }

    public static void k(String str, File file, h2.l lVar) {
        int i3;
        URLConnection openConnection = new URL(str).openConnection();
        i2.g.c(openConnection, "null cannot be cast to non-null type java.net.HttpURLConnection");
        HttpURLConnection httpURLConnection = (HttpURLConnection) openConnection;
        httpURLConnection.setConnectTimeout(30000);
        httpURLConnection.setReadTimeout(60000);
        httpURLConnection.setInstanceFollowRedirects(true);
        try {
            if (httpURLConnection.getResponseCode() != 200) {
                throw new IOException("HTTP " + httpURLConnection.getResponseCode());
            }
            int contentLength = httpURLConnection.getContentLength();
            InputStream inputStream = httpURLConnection.getInputStream();
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            try {
                byte[] bArr = new byte[65536];
                int i4 = -1;
                long j3 = 0;
                while (true) {
                    int read = inputStream.read(bArr);
                    if (read < 0) {
                        break;
                    }
                    fileOutputStream.write(bArr, 0, read);
                    j3 += read;
                    if (contentLength > 0 && (i3 = (int) ((100 * j3) / contentLength)) != i4) {
                        lVar.c("download:" + i3);
                        i4 = i3;
                    }
                }
                fileOutputStream.close();
                inputStream.close();
                httpURLConnection.disconnect();
                if (file.length() == 0) {
                    throw new IOException("download vazio");
                }
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    a1.b.p(fileOutputStream, th);
                    throw th2;
                }
            }
        } catch (Throwable th3) {
            httpURLConnection.disconnect();
            throw th3;
        }
    }

    public static String l() {
        int i3 = u.f4324a;
        try {
            String y2 = y(8000, o2.m.V("\n            cur=\"$(getprop service.adb.tcp.port)\"\n            per=\"$(getprop persist.adb.tcp.port)\"\n            if [ \"$cur\" = \"65010\" ] && [ \"$per\" = \"65010\" ]; then\n              echo \"ADB_PORT_OK=65010\"\n            else\n              setprop persist.adb.tcp.port 65010\n              setprop service.adb.tcp.port 65010\n              ( sleep 1; setprop ctl.restart adbd ) >/dev/null 2>&1 &\n              echo \"ADB_PORT_MIGRATED cur=$cur per=$per -> 65010\"\n            fi\n        "));
            Log.i("AdbLocal", "ensurePortMigrated: " + o2.k.E0(y2).toString());
            return y2;
        } catch (Exception e3) {
            Log.w("AdbLocal", "ensurePortMigrated falhou: " + e3.getMessage());
            return "ADB_PORT_MIGRATE_FAIL=" + e3.getMessage();
        }
    }

    public static String m() {
        if (k1.c("https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json", u.f4329h) == v4.b) {
            Log.i("GesturePatch", "fetchExpectedSha: o manifesto saiu do GitHub — sem referencia de sha");
            return null;
        }
        try {
            URL url = new URL("https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json");
            String str = new String(a1.b.X(url), o2.a.f2622a);
            Pattern compile = Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
            i2.g.d(compile, "compile(...)");
            Matcher matcher = compile.matcher(str);
            i2.g.d(matcher, "matcher(...)");
            o2.e b3 = k1.a.b(matcher, 0, str);
            if (b3 != null) {
                return (String) ((o2.d) b3.a()).get(1);
            }
            return null;
        } catch (Throwable th) {
            androidx.activity.result.b.s(th, new StringBuilder("fetchExpectedSha: "), "GesturePatch");
            return null;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:11:0x0030, code lost:
        if (r2 == null) goto L14;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static java.lang.String n(com.ex.auto.PhoneSyncService r4) {
        /*
            java.lang.String r4 = ""
            java.lang.Class<java.lang.String> r0 = java.lang.String.class
            java.lang.String r1 = "android.os.SystemProperties"
            java.lang.Class r1 = java.lang.Class.forName(r1)     // Catch: java.lang.Throwable -> L35
            java.lang.String r2 = "get"
            java.lang.Class[] r0 = new java.lang.Class[]{r0, r0}     // Catch: java.lang.Throwable -> L35
            java.lang.reflect.Method r0 = r1.getMethod(r2, r0)     // Catch: java.lang.Throwable -> L35
            java.lang.String r1 = "persist.device.real.serialno"
            java.lang.Object[] r1 = new java.lang.Object[]{r1, r4}     // Catch: java.lang.Throwable -> L35
            r2 = 0
            java.lang.Object r0 = r0.invoke(r2, r1)     // Catch: java.lang.Throwable -> L35
            boolean r1 = r0 instanceof java.lang.String     // Catch: java.lang.Throwable -> L35
            if (r1 == 0) goto L26
            java.lang.String r0 = (java.lang.String) r0     // Catch: java.lang.Throwable -> L35
            goto L27
        L26:
            r0 = r2
        L27:
            if (r0 == 0) goto L32
            int r1 = r0.length()     // Catch: java.lang.Throwable -> L35
            if (r1 <= 0) goto L30
            r2 = r0
        L30:
            if (r2 != 0) goto L37
        L32:
            java.lang.String r2 = android.os.Build.SERIAL     // Catch: java.lang.Throwable -> L35
            goto L37
        L35:
            java.lang.String r2 = android.os.Build.SERIAL
        L37:
            java.lang.String r0 = "SHA-256"
            java.security.MessageDigest r0 = java.security.MessageDigest.getInstance(r0)
            java.lang.StringBuilder r1 = new java.lang.StringBuilder
            java.lang.String r3 = "exsapo:"
            r1.<init>(r3)
            r1.append(r2)
            java.lang.String r1 = r1.toString()
            java.nio.charset.Charset r2 = o2.a.f2622a
            byte[] r1 = r1.getBytes(r2)
            java.lang.String r2 = "getBytes(...)"
            i2.g.d(r1, r2)
            byte[] r0 = r0.digest(r1)
            i2.g.b(r0)
            r1 = 4
            java.util.List r0 = y1.f.e0(r1, r0)
            w0.sa r1 = new w0.sa
            r2 = 22
            r1.<init>(r2)
            r2 = 30
            java.lang.String r4 = y1.g.e0(r0, r4, r1, r2)
            return r4
        */
        throw new UnsupportedOperationException("Method not decompiled: w0.m.n(com.ex.auto.PhoneSyncService):java.lang.String");
    }

    public static boolean o(Socket socket) {
        InetAddress address;
        byte[] address2;
        SocketAddress remoteSocketAddress = socket.getRemoteSocketAddress();
        InetSocketAddress inetSocketAddress = remoteSocketAddress instanceof InetSocketAddress ? (InetSocketAddress) remoteSocketAddress : null;
        if (inetSocketAddress == null || (address = inetSocketAddress.getAddress()) == null) {
            return false;
        }
        if (!address.isLoopbackAddress()) {
            byte[] address3 = address.getAddress();
            if (address3.length == 4) {
                try {
                    Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                    i2.g.d(networkInterfaces, "getNetworkInterfaces(...)");
                    while (networkInterfaces.hasMoreElements()) {
                        NetworkInterface nextElement = networkInterfaces.nextElement();
                        if (nextElement.isUp() && !nextElement.isLoopback()) {
                            for (InterfaceAddress interfaceAddress : nextElement.getInterfaceAddresses()) {
                                InetAddress address4 = interfaceAddress.getAddress();
                                if (address4 != null && (address2 = address4.getAddress()) != null && address2.length == 4 && address2[0] == address3[0] && address2[1] == address3[1] && address2[2] == address3[2]) {
                                }
                            }
                            continue;
                        }
                    }
                } catch (Exception unused) {
                }
            }
            return address.isSiteLocalAddress();
        }
        return true;
    }

    public static boolean p(Context context, k5 k5Var) {
        boolean z2 = true;
        if (k5Var == null) {
            context.getSharedPreferences("tela_preta_prefs", 0).getLong("gestures_vc_baixado", 0L);
            Log.i("GesturePatch", "isLocalSourceUpToDate: " + t4.f4297a + " — o servidor nao respondeu e o manifesto do GitHub nao existe mais; fico com a copia local sem conferir");
            return true;
        }
        long j3 = k5Var.b;
        long j4 = context.getSharedPreferences("tela_preta_prefs", 0).getLong("gestures_vc_baixado", 0L);
        if (j4 <= 0 || j4 != j3) {
            z2 = false;
        }
        if (!z2) {
            Log.i("GesturePatch", "isLocalSourceUpToDate: desatualizado (baixado=" + j4 + " servidor=" + j3 + ')');
        }
        return z2;
    }

    public static boolean q(Context context) {
        i2.g.e(context, "context");
        return (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    public static void s() {
        Context context = f3921d;
        if (context == null) {
            return;
        }
        try {
            if (f3924h.equals("fullscreen")) {
                context.startActivity(new Intent(context, ExSapoCastActivity.class).addFlags(805306368));
            } else {
                context.startService(new Intent(context, ExSapoCastFloaterService.class));
            }
        } catch (Exception e3) {
            Log.e("ExSapoCast", "launchUi: " + e3.getMessage());
        }
    }

    public static Integer t() {
        try {
            return o2.s.W(o2.k.E0(f2.d.h0(new File("/sys/class/leds/lcd-backlight/brightness"))).toString());
        } catch (Throwable unused) {
            return null;
        }
    }

    public static e.h u(DataInputStream dataInputStream) {
        try {
            int read = dataInputStream.read();
            if (read < 0) {
                return null;
            }
            int readInt = dataInputStream.readInt();
            if (readInt < 0 || readInt > 4194304) {
                throw new EOFException("length invalido: " + readInt);
            }
            byte[] bArr = new byte[readInt];
            if (readInt > 0) {
                dataInputStream.readFully(bArr);
            }
            return new e.h(read, bArr);
        } catch (EOFException unused) {
            return null;
        }
    }

    public static x1.a v(Context context) {
        k5 a3 = l5.a(context, "https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json");
        if (a3 != null) {
            return new x1.a(String.valueOf(a3.b), Boolean.valueOf(p(context, a3)));
        }
        String m3 = m();
        if (m3 == null) {
            return null;
        }
        return new x1.a(m3, Boolean.valueOf(o2.t.a0(o2.k.E0(c(15000, "sha256sum '/data/local/gesture-launcher/launcher.apk' 2>/dev/null | cut -d' ' -f1")).toString(), m3)));
    }

    public static void w(Context context, SharedPreferences sharedPreferences) {
        i2.g.e(context, "context");
        if (sharedPreferences.getBoolean("remember_brightness", false)) {
            int i3 = sharedPreferences.getInt(q(context) ? "bright_night" : "bright_day", -1);
            if (1 > i3 || i3 >= 256) {
                return;
            }
            f(context, i3, false);
            StringBuilder sb = new StringBuilder("restore: ");
            sb.append(q(context) ? "noite(farol on)" : "dia(farol off)");
            sb.append(" -> ");
            sb.append(i3);
            Log.i("BrightMem", sb.toString());
        }
    }

    public static String x(String str, int i3, h2.l lVar, h2.p pVar) {
        i2.g.e(str, "script");
        byte[] bytes = str.getBytes(o2.a.f2622a);
        i2.g.d(bytes, "getBytes(...)");
        if (bytes.length < 3500) {
            return (String) pVar.b(str, Integer.valueOf(i3));
        }
        String encodeToString = Base64.getEncoder().encodeToString(bytes);
        long currentTimeMillis = System.currentTimeMillis();
        String str2 = "'/data/local/tmp/_big_script_" + currentTimeMillis + ".b64'";
        String str3 = "'/data/local/tmp/_big_script_" + currentTimeMillis + ".sh'";
        pVar.b("rm -f " + str2 + ' ' + str3 + " 2>/dev/null; true", 10000);
        int length = (encodeToString.length() + 2999) / 3000;
        int i4 = 0;
        int i5 = 0;
        while (i4 < encodeToString.length()) {
            i5++;
            if (lVar != null) {
                lVar.c("Enviando dados… (" + i5 + '/' + length + ')');
            }
            int i6 = i4 + 3000;
            String substring = encodeToString.substring(i4, Math.min(i6, encodeToString.length()));
            i2.g.d(substring, "substring(...)");
            pVar.b("echo '" + substring + "' >> " + str2, 15000);
            i4 = i6;
        }
        long length2 = encodeToString.length() + length;
        Long Y = o2.s.Y(o2.k.E0((String) pVar.b("wc -c < " + str2 + " 2>/dev/null", 15000)).toString());
        if (Y != null && Y.longValue() >= length2) {
            if (lVar != null) {
                lVar.c("Aplicando… (pode levar um tempo)");
            }
            StringBuilder m3 = androidx.activity.result.b.m("base64 -d ", str2, " > ", str3, " 2>&1 && sh ");
            m3.append(str3);
            String str4 = (String) pVar.b(m3.toString(), Integer.valueOf(i3));
            pVar.b("rm -f " + str2 + ' ' + str3 + " 2>/dev/null; true", 10000);
            return str4;
        }
        pVar.b("rm -f " + str2 + ' ' + str3 + " 2>/dev/null; true", 10000);
        StringBuilder sb = new StringBuilder("ADBBIG_INCOMPLETO esperado=");
        sb.append(length2);
        sb.append(" veio=");
        Object obj = Y;
        if (Y == null) {
            obj = "?";
        }
        sb.append(obj);
        sb.append(" — nada foi executado");
        return sb.toString();
    }

    public static String y(int i3, String str) {
        i2.g.e(str, "cmd");
        return x(str, i3, null, new n(0));
    }

    public static void z(Context context, SharedPreferences sharedPreferences) {
        Integer t2;
        int intValue;
        i2.g.e(context, "context");
        if (sharedPreferences.getBoolean("remember_brightness", false) && (t2 = t()) != null && (intValue = t2.intValue()) > 0) {
            String str = q(context) ? "bright_night" : "bright_day";
            if (sharedPreferences.getInt(str, -1) == intValue) {
                return;
            }
            sharedPreferences.edit().putInt(str, intValue).apply();
        }
    }

    public synchronized String r(PhoneSyncService phoneSyncService, String str) {
        String sb;
        try {
            f3921d = phoneSyncService.getApplicationContext();
            f3924h = str.equals("fullscreen") ? "fullscreen" : "pip";
            StringBuilder sb2 = new StringBuilder(24);
            long nanoTime = System.nanoTime() ^ (hashCode() << 21);
            for (int i3 = 0; i3 < 24; i3++) {
                nanoTime = (nanoTime * 6364136223846793005L) + 1442695040888963407L;
                sb2.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".charAt((((int) (nanoTime >>> 33)) & Integer.MAX_VALUE) % 62));
            }
            sb = sb2.toString();
            i2.g.d(sb, "toString(...)");
            f = sb;
            f3923g = SystemClock.elapsedRealtime() + 300000;
            if (!f3922e) {
                f3922e = true;
                Thread thread = new Thread(new r0(6, this), "exsapocast-accept");
                thread.setDaemon(true);
                thread.start();
            }
        } finally {
        }
        return sb;
    }
}
