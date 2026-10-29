package w0;

import android.util.Log;
import com.ex.auto.MainActivity;
/* loaded from: classes.dex */
public final /* synthetic */ class la implements Runnable {

    /* renamed from: a  reason: collision with root package name */
    public final /* synthetic */ int f3895a = 0;
    public final /* synthetic */ boolean b;

    /* renamed from: c  reason: collision with root package name */
    public final /* synthetic */ MainActivity f3896c;

    /* renamed from: d  reason: collision with root package name */
    public final /* synthetic */ e.i f3897d;

    public /* synthetic */ la(MainActivity mainActivity, boolean z2, e.i iVar) {
        this.f3896c = mainActivity;
        this.b = z2;
        this.f3897d = iVar;
    }

    @Override // java.lang.Runnable
    public final void run() {
        boolean z2;
        boolean z3 = false;
        switch (this.f3895a) {
            case 0:
                MainActivity mainActivity = this.f3896c;
                boolean z4 = this.b;
                e.i iVar = this.f3897d;
                int i3 = MainActivity.f1002z1;
                StringBuilder sb = new StringBuilder("\n        svc wifi disable\n        _n=0\n        while [ $_n -lt 20 ]; do\n          [ \"$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n          sleep 1; _n=$((_n + 1))\n        done\n        for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do\n          umount -l \"$_m\" 2>/dev/null\n        done\n        setprop dalvik.vm.usejit ");
                sb.append(z4 ? "true" : "false");
                sb.append("\n        setprop ctl.restart zygote\n    ");
                mainActivity.H1(40000, o2.m.V(sb.toString()));
                mainActivity.f1045v.postDelayed(new ua(iVar, 0, mainActivity), 45000L);
                return;
            default:
                boolean z5 = this.b;
                MainActivity mainActivity2 = this.f3896c;
                e.i iVar2 = this.f3897d;
                int i4 = MainActivity.f1002z1;
                if (z5) {
                    z2 = dh.c(mainActivity2);
                } else {
                    String str = dh.f3511a;
                    String str2 = dh.b;
                    if (dh.e(str, str2)) {
                        String g2 = dh.g(str);
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append("_n=0; while grep -F \" " + str + " \" /proc/mounts >/dev/null 2>&1 && [ $_n -lt 12 ]; do ");
                        sb2.append("umount " + g2 + " 2>/dev/null || umount -l " + g2 + " 2>/dev/null || break; _n=$((_n+1)); done\n");
                        StringBuilder sb3 = new StringBuilder("setprop ctl.restart ");
                        sb3.append(str2);
                        sb3.append(" 2>/dev/null || true\n");
                        sb2.append(sb3.toString());
                        sb2.append("echo \"SRC_INODE=$(stat -c %i ");
                        sb2.append(dh.g("/data/local/tmp/vcodec642.so"));
                        sb2.append(" 2>/dev/null)\"\n");
                        sb2.append("echo \"TGT_INODE=$(stat -c %i " + g2 + " 2>/dev/null)\"\n");
                        String sb4 = sb2.toString();
                        i2.g.d(sb4, "toString(...)");
                        String x2 = m.x(sb4, 30000, null, new n(17));
                        dh.f3512c = o2.k.E0(x2).toString();
                        boolean b = dh.b(dh.d(x2, "TGT_INODE"), dh.d(x2, "SRC_INODE"));
                        boolean z6 = !b;
                        StringBuilder sb5 = new StringBuilder("remove: ");
                        sb5.append(!b ? "OK" : "FAIL");
                        sb5.append(" — ");
                        sb5.append(o2.k.E0(o2.k.D0(200, x2)).toString());
                        Log.i("VideoCodec", sb5.toString());
                        z3 = z6;
                    }
                    z2 = z3;
                }
                mainActivity2.f1045v.post(new ma(mainActivity2, iVar2, z5, z2, 1));
                return;
        }
    }

    public /* synthetic */ la(boolean z2, MainActivity mainActivity, e.i iVar) {
        this.b = z2;
        this.f3896c = mainActivity;
        this.f3897d = iVar;
    }
}
