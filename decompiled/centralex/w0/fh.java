package w0;

import android.content.Intent;
import com.ex.auto.WifiFloaterService;
/* loaded from: classes.dex */
public final /* synthetic */ class fh implements Runnable {

    /* renamed from: a  reason: collision with root package name */
    public final /* synthetic */ int f3596a;
    public final /* synthetic */ WifiFloaterService b;

    public /* synthetic */ fh(WifiFloaterService wifiFloaterService, int i3) {
        this.f3596a = i3;
        this.b = wifiFloaterService;
    }

    @Override // java.lang.Runnable
    public final void run() {
        int i3 = this.f3596a;
        WifiFloaterService wifiFloaterService = this.b;
        switch (i3) {
            case 0:
                int i4 = WifiFloaterService.f1249n;
                try {
                    Intent intent = new Intent("android.settings.WIFI_SETTINGS");
                    intent.setFlags(268435456);
                    wifiFloaterService.startActivity(intent);
                    return;
                } catch (Exception unused) {
                    return;
                }
            default:
                int i5 = WifiFloaterService.f1249n;
                wifiFloaterService.a();
                return;
        }
    }
}
