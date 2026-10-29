package w0;

import android.os.Handler;
import android.util.Log;
import com.ecarx.eas.sdk.ECarXApiClient;
import com.ecarx.eas.sdk.mediacenter.MusicClient;
import com.ex.auto.CentralWatchdogService;
import com.ex.auto.MainActivity;
import com.ex.auto.QuickAccessFloaterService;
import ecarx.xsf.mediacenter.session.PlaybackState;
import java.io.IOException;
/* loaded from: classes.dex */
public final /* synthetic */ class n implements h2.p, ECarXApiClient.Callback {

    /* renamed from: a  reason: collision with root package name */
    public final /* synthetic */ int f3967a;

    public /* synthetic */ n(int i3) {
        this.f3967a = i3;
    }

    @Override // h2.p
    public Object b(Object obj, Object obj2) {
        int i3 = this.f3967a;
        String str = (String) obj;
        int intValue = ((Integer) obj2).intValue();
        switch (i3) {
            case 0:
                i2.g.e(str, "c");
                int i4 = m.f3919a;
                if (i4 != 0) {
                    try {
                        String b = new kotlinx.coroutines.scheduling.h(i4).b(intValue, str);
                        i2.g.d(b, "runShell(...)");
                        return b;
                    } catch (IOException unused) {
                        m.f3919a = 0;
                    }
                }
                int[] iArr = {65010, 5555};
                IOException e3 = null;
                for (int i5 = 0; i5 < 2; i5++) {
                    int i6 = iArr[i5];
                    try {
                        String b3 = new kotlinx.coroutines.scheduling.h(i6).b(intValue, str);
                        m.f3919a = i6;
                        i2.g.b(b3);
                        return b3;
                    } catch (IOException e4) {
                        e3 = e4;
                    }
                }
                if (e3 != null) {
                    throw e3;
                }
                throw new IOException("adbd local indisponível");
            case 1:
                int[] iArr2 = CentralWatchdogService.R2;
                i2.g.e(str, "c");
                String b4 = new kotlinx.coroutines.scheduling.h(5555).b(intValue, str);
                i2.g.d(b4, "runShell(...)");
                return b4;
            case 2:
                int[] iArr3 = CentralWatchdogService.R2;
                i2.g.e(str, "c");
                return CentralWatchdogService.c(intValue, str);
            case 3:
                i2.g.e(str, "cmd");
                x7 x7Var = x7.f4486a;
                return x7.a(intValue, str);
            case 4:
                int i7 = MainActivity.f1002z1;
                i2.g.e(str, "c");
                try {
                    return new kotlinx.coroutines.scheduling.h(5555).b(intValue, str);
                } catch (Exception e5) {
                    return "ADB_FAIL: " + e5.getMessage();
                }
            case MusicClient.TYPE_MEDIA_INNER /* 5 */:
            default:
                i2.g.e(str, "c");
                return dh.a(intValue, str);
            case MusicClient.TYPE_GESTURE /* 6 */:
                i2.g.e(str, "c");
                try {
                    return m.y(intValue, str);
                } catch (Exception e6) {
                    return "ADB_FAIL: " + e6.getMessage();
                }
            case PlaybackState.STATE_ERROR /* 7 */:
                i2.g.e(str, "c");
                try {
                    return m.y(intValue, str);
                } catch (Exception e7) {
                    return "ADB_FAIL: " + e7.getMessage();
                }
            case 8:
                i2.g.e(str, "c");
                try {
                    return m.y(intValue, str);
                } catch (Exception e8) {
                    return "ADB_FAIL: " + e8.getMessage();
                }
            case 9:
                int i8 = QuickAccessFloaterService.t0;
                i2.g.e(str, "c");
                return QuickAccessFloaterService.I(intValue, str);
            case 10:
                i2.g.e(str, "c");
                return zf.a(intValue, str);
            case 11:
                i2.g.e(str, "c");
                return zf.a(intValue, str);
            case 12:
                i2.g.e(str, "c");
                return zf.a(intValue, str);
            case 13:
                i2.g.e(str, "c");
                try {
                    return new kotlinx.coroutines.scheduling.h(5555).b(intValue, str);
                } catch (Exception e9) {
                    return "ADB_FAIL: " + e9.getMessage();
                }
            case 14:
                i2.g.e(str, "c");
                return dh.a(intValue, str);
            case 15:
                i2.g.e(str, "c");
                return dh.a(intValue, str);
            case 16:
                i2.g.e(str, "c");
                return dh.a(intValue, str);
        }
    }

    @Override // com.ecarx.eas.sdk.ECarXApiClient.Callback
    public void onAPIReady(boolean z2) {
        Log.i("MediaCenterBridge", "onAPIReady: " + z2);
        if (id.f3729g) {
            return;
        }
        if (z2) {
            Handler handler = id.f3733k;
            if (handler != null) {
                handler.post(new r0(9));
                return;
            }
            return;
        }
        Handler handler2 = id.f3733k;
        if (handler2 != null) {
            handler2.postDelayed(new r0(10), 2000L);
        }
    }
}
